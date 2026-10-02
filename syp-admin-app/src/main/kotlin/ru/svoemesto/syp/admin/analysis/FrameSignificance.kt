package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Table

/**
 * Подсказка смены крупности на кадре.
 *
 * Подсказка — **подсказка**, а не размеченный размер плана: размер вычисляется
 * по лицу и может быть исправлен оператором (ADR-0003). Хранить здесь нужно
 * лишь то, что видно на самом кадре и помогает оператору ориентироваться
 * (FR-023).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class SizeHint {
    /** Крупность меняется резко: кадр стоит посмотреть. */
    SHARP_CHANGE,
}

/**
 * Значимый кадр серии.
 *
 * В таблице **нет строки на каждый кадр**. На серии `GOT.S01E01` это 88 643
 * строки, из которых почти все ничего не несут: полная таблица кадров
 * запрещена правилом Р-07, а признак «ключевой кадр» живёт в битовой карте
 * серии. Здесь остаётся только то, что интерфейс показывает: границы сцен,
 * границы планов, кадры с найденными лицами и подсказки смены крупности.
 *
 * Строка **не создаётся** для кадра, у которого нет ни одного из этих
 * признаков: пустая строка в таблице значимых кадров — это ровно та полная
 * таблица, которой быть не должно.
 *
 * @property id идентификатор кадра в таблице; `null`, пока не записан
 * @property episodeId серия-владелец
 * @property frameNumber номер кадра, нумерация с нуля (ADR-0001)
 * @property isSceneBoundary начинается ли здесь новая сцена
 * @property isShotBoundary начинается ли здесь новый план
 * @property faceCount сколько лиц найдено в кадре
 * @property sizeHint подсказка смены крупности либо `null`
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FrameSignificance(
    val id: Long? = null,
    val episodeId: Long,
    val frameNumber: Int,
    val isSceneBoundary: Boolean = false,
    val isShotBoundary: Boolean = false,
    val faceCount: Int = 0,
    val sizeHint: SizeHint? = null,
    val recordHash: String? = null,
) {
    init {
        require(frameNumber >= 0) { "Номер кадра не может быть отрицательным, задано $frameNumber" }
        require(faceCount >= 0) { "Число лиц не может быть отрицательным, задано $faceCount" }
        require(isSignificant) {
            "Кадр $frameNumber не имеет ни одного признака: строка для него не создаётся (Р-07)"
        }
    }

    /** Есть ли у кадра хоть один признак, ради которого строка и существует. */
    val isSignificant: Boolean
        get() = isSceneBoundary || isShotBoundary || faceCount > 0 || sizeHint != null

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами кадра
     */
    fun toTable(): Table =
        Table(
            FrameSignificanceStore.TABLE,
            FrameSignificanceStore.COLUMNS,
            {
                listOf(
                    episodeId,
                    frameNumber,
                    isSceneBoundary,
                    isShotBoundary,
                    faceCount,
                    sizeHint?.name,
                )
            },
            recordHash,
        )

    companion object {
        /** Столбцы кадра в порядке чтения из базы. */
        val READ_COLUMNS: String =
            "id, id_episode, frame_number, is_scene_boundary, is_shot_boundary, face_count, " +
                "size_hint, recordhash"
    }
}

/**
 * Хранилище значимых кадров.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FrameSignificanceStore(
    private val db: Db,
) {
    /**
     * Записывает значимые кадры серии одной транзакцией.
     *
     * Признаки кадров приходят пакетами из разных источников — границы от
     * детектора, лица от детектора лиц, подсказки от анализа крупности, — и
     * записываются они одним изменением: половина признаков означала бы, что
     * система «знает» о кадре не всё, что знает.
     *
     * Признаки уже существующих кадров обновляются, а не заменяются: кадр на
     * границе плана, в котором нашлись лица, остаётся одним кадром.
     *
     * @param frames значимые кадры серии
     * @return число затронутых строк
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun upsertAll(frames: List<FrameSignificance>): Int {
        if (frames.isEmpty()) {
            return 0
        }
        frames.forEach { frame ->
            require(frame.isSignificant) {
                "Кадр ${frame.frameNumber} не имеет ни одного признака: строка для него не создаётся (Р-07)"
            }
        }
        return db.useTransaction { connection ->
            connection.prepareStatement(UPSERT_SQL).use { statement ->
                frames.forEach { frame ->
                    statement.setLong(1, frame.episodeId)
                    statement.setInt(2, frame.frameNumber)
                    statement.setBoolean(3, frame.isSceneBoundary)
                    statement.setBoolean(4, frame.isShotBoundary)
                    statement.setInt(5, frame.faceCount)
                    statement.setString(6, frame.sizeHint?.name)
                    statement.addBatch()
                }
                statement.executeBatch().size
            }
        }
    }

    /**
     * Отмечает кадры как границы сцен.
     *
     * Отдельный метод нужен потому, что границы приходят после кадров с
     * лицами: те же номера кадров уже лежат в таблице, и их признак надо
     * поднять, а не заводить вторую строку.
     *
     * @param episodeId идентификатор серии
     * @param frameNumbers номера кадров — границ сцен
     * @return число затронутых строк
     */
    fun markSceneBoundaries(
        episodeId: Long,
        frameNumbers: Collection<Int>,
    ): Int = markBoundaries(episodeId, frameNumbers, SCENE_COLUMN)

    /**
     * Отмечает кадры как границы планов.
     *
     * @param episodeId идентификатор серии
     * @param frameNumbers номера кадров — границ планов
     * @return число затронутых строк
     */
    fun markShotBoundaries(
        episodeId: Long,
        frameNumbers: Collection<Int>,
    ): Int = markBoundaries(episodeId, frameNumbers, SHOT_COLUMN)

    /**
     * Читает значимые кадры диапазона по возрастанию номера.
     *
     * @param episodeId идентификатор серии
     * @param fromFrame первый кадр диапазона включительно
     * @param toFrame последний кадр диапазона включительно
     * @param limit максимум строк в ответе: страницы пагинируются всегда
     * @returns кадры диапазона
     */
    fun listRange(
        episodeId: Long,
        fromFrame: Int,
        toFrame: Int,
        limit: Int = DEFAULT_LIMIT,
    ): List<FrameSignificance> =
        db.select(
            "$READ_SQL WHERE id_episode = ? AND frame_number BETWEEN ? AND ? " +
                "ORDER BY frame_number LIMIT $limit",
            ::readRow,
            episodeId,
            fromFrame,
            toFrame,
        )

    /**
     * Считает значимые кадры серии.
     *
     * @param episodeId идентификатор серии
     * @return число значимых кадров
     */
    fun countByEpisode(episodeId: Long): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM $TABLE WHERE id_episode = ?",
            { it.int("total") },
            episodeId,
        ) ?: 0

    /** Поднимает признак границы у перечисленных кадров. */
    private fun markBoundaries(
        episodeId: Long,
        frameNumbers: Collection<Int>,
        column: String,
    ): Int {
        if (frameNumbers.isEmpty()) {
            return 0
        }
        return db.useTransaction { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO $TABLE (id_episode, frame_number, $column) VALUES (?, ?, TRUE) " +
                        "ON CONFLICT (id_episode, frame_number) DO UPDATE SET $column = TRUE",
                ).use { statement ->
                    frameNumbers.forEach { frame ->
                        statement.setLong(1, episodeId)
                        statement.setInt(2, frame)
                        statement.addBatch()
                    }
                    statement.executeBatch().size
                }
        }
    }

    /** Строит кадр из типизированной строки выборки. */
    private fun readRow(row: Row): FrameSignificance =
        FrameSignificance(
            id = row.long("id"),
            episodeId = row.long("id_episode"),
            frameNumber = row.int("frame_number"),
            isSceneBoundary = row.booleanOrNull("is_scene_boundary") == true,
            isShotBoundary = row.booleanOrNull("is_shot_boundary") == true,
            faceCount = row.int("face_count"),
            sizeHint = row.stringOrNull("size_hint")?.let { SizeHint.valueOf(it) },
            recordHash = row.stringOrNull("recordhash"),
        )

    companion object {
        /** Имя таблицы значимых кадров. */
        const val TABLE: String = "tbl_frames"

        /** Записываемые столбцы кадра в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_episode",
                "frame_number",
                "is_scene_boundary",
                "is_shot_boundary",
                "face_count",
                "size_hint",
            )

        /** Столбец признака границы сцены. */
        const val SCENE_COLUMN: String = "is_scene_boundary"

        /** Столбец признака границы плана. */
        const val SHOT_COLUMN: String = "is_shot_boundary"

        /** Столбцы кадра в порядке чтения из базы. */
        val READ_SQL: String = "SELECT ${FrameSignificance.READ_COLUMNS} FROM $TABLE"

        /** Пакетная вставка с обновлением уже существующих кадров. */
        val UPSERT_SQL: String =
            "INSERT INTO $TABLE (id_episode, frame_number, is_scene_boundary, is_shot_boundary, " +
                "face_count, size_hint) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id_episode, frame_number) DO UPDATE SET " +
                "is_scene_boundary = $TABLE.is_scene_boundary OR EXCLUDED.is_scene_boundary, " +
                "is_shot_boundary = $TABLE.is_shot_boundary OR EXCLUDED.is_shot_boundary, " +
                "face_count = GREATEST($TABLE.face_count, EXCLUDED.face_count), " +
                "size_hint = COALESCE(EXCLUDED.size_hint, $TABLE.size_hint)"

        /** Сколько строк отдавать по умолчанию: списки кадров пагинируются всегда. */
        const val DEFAULT_LIMIT: Int = 200
    }
}
