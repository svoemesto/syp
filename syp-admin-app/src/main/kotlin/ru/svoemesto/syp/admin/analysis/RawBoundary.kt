package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Table

/**
 * Уровень сырой границы.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class BoundaryLevel {
    /** Граница сцены. */
    SCENE,

    /** Граница плана внутри сцены. */
    SHOT,
    ;

    companion object {
        /**
         * Разбирает уровень из строки базы.
         *
         * @param value значение столбца `level`
         * @return уровень
         * @throws IllegalArgumentException если уровня нет в наборе
         */
        fun parse(value: String): BoundaryLevel =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестный уровень границы: «$value»")
    }
}

/**
 * Сырая граница — результат автоматики.
 *
 * Сырой результат **не изменяется ручными правками**. Правка оператора
 * меняет рабочую границу сцены или плана и её признак происхождения, а этот
 * результат остаётся тем, что выдал алгоритм. Только так можно показать
 * оператору «вот что предложила машина, вот что сделал человек» и увидеть
 * расхождение, а не накопить его (FR-093, ADR-0007).
 *
 * Границы хранятся номерами кадров, а не временем: номер кадра — единственный
 * источник правды (ADR-0001).
 *
 * @property id идентификатор границы; `null`, пока не записана
 * @property runId прогон, которым граница получена
 * @property level уровень: граница сцены или граница плана
 * @property firstFrame первый кадр участка, нумерация с нуля
 * @property lastFrame последний кадр участка
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RawBoundary(
    val id: Long? = null,
    val runId: Long,
    val level: BoundaryLevel,
    val firstFrame: Int,
    val lastFrame: Int,
    val recordHash: String? = null,
) {
    init {
        require(firstFrame >= 0) { "Первый кадр границы не может быть отрицательным, задано $firstFrame" }
        require(lastFrame >= firstFrame) {
            "Последний кадр границы $lastFrame раньше первого $firstFrame: диапазон вывернут наизнанку"
        }
    }

    /** Длина участка в кадрах. */
    val frameCount: Int
        get() = lastFrame - firstFrame + 1

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами границы
     */
    fun toTable(): Table =
        Table(
            RawBoundaryStore.TABLE,
            RawBoundaryStore.COLUMNS,
            { listOf(runId, level.name, firstFrame, lastFrame) },
            recordHash,
        )

    companion object {
        /** Столбцы границы в порядке чтения из базы. */
        val READ_COLUMNS: String = "id, run_id, level, first_frame, last_frame, recordhash"
    }
}

/**
 * Хранилище сырых границ.
 *
 * Запись **только добавляется**. Исправление сырого результата автоматики не
 * предусмотрено: исправленный результат перестал бы быть результатом
 * автоматики, а сравнивать было бы не с чем (FR-093).
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class RawBoundaryStore(
    private val db: Db,
) {
    /**
     * Записывает сырые границы прогона одной транзакцией.
     *
     * Пакетная запись по одной транзакции — не ускорение, а требование
     * согласованности: частично записанный результат означал бы, что система
     * «знает» о границах, которых на самом деле нет.
     *
     * @param boundaries границы одного уровня одного прогона
     * @return число записанных границ
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun appendAll(boundaries: List<RawBoundary>): Int = db.useTransaction { connection -> appendAll(connection, boundaries) }

    /**
     * Записывает сырые границы в пределах уже открытой транзакции.
     *
     * Метод нужен там, где границы должны попасть в базу **вместе** с рабочей
     * структурой: сырая граница без рабочей сцены означала бы, что система
     * знает о предложении алгоритма и забыла о решении по нему.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param boundaries границы одного уровня одного прогона
     * @return число записанных границ
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun appendAll(
        connection: java.sql.Connection,
        boundaries: List<RawBoundary>,
    ): Int {
        if (boundaries.isEmpty()) {
            return 0
        }
        return connection.prepareStatement(INSERT_SQL).use { statement ->
            boundaries.forEach { boundary ->
                statement.setLong(1, boundary.runId)
                statement.setString(2, boundary.level.name)
                statement.setInt(3, boundary.firstFrame)
                statement.setInt(4, boundary.lastFrame)
                statement.addBatch()
            }
            statement.executeBatch().size
        }
    }

    /**
     * Читает сырые границы прогона заданного уровня по возрастанию первого кадра.
     *
     * @param runId идентификатор прогона
     * @param level уровень границ
     * @return границы в порядке следования
     */
    fun listByRun(
        runId: Long,
        level: BoundaryLevel,
    ): List<RawBoundary> =
        db.select(
            "$READ_SQL WHERE run_id = ? AND level = ? ORDER BY first_frame",
            ::readRow,
            runId,
            level.name,
        )

    /**
     * Читает сырые границы прогона обоих уровней.
     *
     * @param runId идентификатор прогона
     * @return границы по уровням: сначала сцены, затем планы
     */
    fun listAllByRun(runId: Long): Map<BoundaryLevel, List<RawBoundary>> =
        db
            .select(
                "$READ_SQL WHERE run_id = ? ORDER BY level DESC, first_frame",
                ::readRow,
                runId,
            ).groupBy { it.level }

    /**
     * Считает сырые границы прогона.
     *
     * @param runId идентификатор прогона
     * @param level уровень границ
     * @return число границ
     */
    fun countByRun(
        runId: Long,
        level: BoundaryLevel,
    ): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM $TABLE WHERE run_id = ? AND level = ?",
            { it.int("total") },
            runId,
            level.name,
        ) ?: 0

    /** Строит границу из типизированной строки выборки. */
    private fun readRow(row: Row): RawBoundary =
        RawBoundary(
            id = row.long("id"),
            runId = row.long("run_id"),
            level = BoundaryLevel.parse(row.string("level")),
            firstFrame = row.int("first_frame"),
            lastFrame = row.int("last_frame"),
            recordHash = row.stringOrNull("recordhash"),
        )

    companion object {
        /** Имя таблицы сырых границ. */
        const val TABLE: String = "tbl_raw_boundaries"

        /** Записываемые столбцы границы в порядке значений. */
        val COLUMNS: List<String> = listOf("run_id", "level", "first_frame", "last_frame")

        /** Текст запроса выборки границы. */
        val READ_SQL: String = "SELECT ${RawBoundary.READ_COLUMNS} FROM $TABLE"

        /** Текст пакетной вставки границы. */
        val INSERT_SQL: String = "INSERT INTO $TABLE (run_id, level, first_frame, last_frame) VALUES (?, ?, ?, ?)"
    }
}
