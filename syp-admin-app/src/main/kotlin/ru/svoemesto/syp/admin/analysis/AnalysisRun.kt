package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.time.OffsetDateTime

/**
 * Вид прогона анализа.
 *
 * Разделение на два вида не декоративно: структура эпизода и лица считаются
 * разными заданиями с разными порогами и разными версиями алгоритма, и
 * смешивать их в одном прогоне означало бы невозможность понять, чем
 * получен результат.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class AnalysisKind {
    /** Границы сцен и планов, листы превью. */
    STRUCTURE,

    /** Лица и эмбеддинги. */
    FACES,
    ;

    companion object {
        /**
         * Разбирает вид прогона из строки базы.
         *
         * @param value значение столбца `kind`
         * @return вид прогона
         * @throws IllegalArgumentException если вида нет в наборе
         */
        fun parse(value: String): AnalysisKind =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестный вид прогона анализа: «$value»")
    }
}

/**
 * Состояние прогона анализа.
 *
 * Набор тот же, что у записи справочника сумм, и по той же причине: это
 * состояния результата вычисления. Пятое состояние очереди — «взято
 * воркером» — здесь лишнее, потому что прогон заводит сам исполнитель
 * задания.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class AnalysisState {
    /** Прогон заведён, работа не началась. */
    CREATING,

    /** Идёт работа. */
    WORKING,

    /** Работа завершена. */
    DONE,

    /** Работа не удалась; текст ошибки обязателен. */
    ERROR,
    ;

    companion object {
        /**
         * Разбирает состояние из строки базы.
         *
         * @param value значение столбца `state`
         * @return состояние
         * @throws IllegalArgumentException если состояния нет в наборе
         */
        fun parse(value: String): AnalysisState =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестное состояние прогона: «$value»")
    }
}

/**
 * Прогон анализа эпизода.
 *
 * Прогон существует ради одного: результат должен зависеть от версии
 * алгоритма и набора параметров (FR-090). Без прогона «тот же результат» не
 * отличить от «тот же результат при других порогах», а это разные вещи.
 *
 * **Повторный анализ создаёт новый прогон.** Прежние прогоны и их сырые
 * границы остаются: без них невозможно сравнить «было и стало» и нельзя
 * понять, что именно изменилось — алгоритм, пороги или сам файл (FR-093).
 *
 * @property id идентификатор прогона; `null`, пока не записан
 * @property videofileId эпизод, которую разбирали
 * @property kind вид прогона
 * @property algorithmVersion версия алгоритма, которой получен результат
 * @property paramsHash хеш входов задания: по нему видно, при каких порогах
 *   и настройках считалось
 * @property state состояние прогона
 * @property startedAt когда прогон начался
 * @property finishedAt когда прогон закончился
 * @property errorText текст ошибки при [AnalysisState.ERROR]
 * @property isStale результат помечен устаревшим: версия или параметры
 *   изменились после прогона (FR-090)
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class AnalysisRun(
    val id: Long? = null,
    val videofileId: Long,
    val kind: AnalysisKind,
    val algorithmVersion: String,
    val paramsHash: String,
    val state: AnalysisState = AnalysisState.CREATING,
    val startedAt: OffsetDateTime? = null,
    val finishedAt: OffsetDateTime? = null,
    val errorText: String? = null,
    val isStale: Boolean = false,
    val recordHash: String? = null,
) {
    init {
        require(algorithmVersion.isNotBlank()) { "Версия алгоритма прогона обязательна" }
        require(paramsHash.isNotBlank()) { "Хеш параметров прогона обязателен" }
        if (state == AnalysisState.ERROR) {
            require(!errorText.isNullOrBlank()) {
                "Прогон в состоянии ERROR обязан нести текст ошибки (FR-092)"
            }
        }
    }

    /** Прогон дал результат. */
    val isFinished: Boolean
        get() = state == AnalysisState.DONE || state == AnalysisState.ERROR

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами прогона
     */
    fun toTable(): Table =
        Table(
            AnalysisRunStore.NAME,
            AnalysisRunStore.COLUMNS,
            {
                listOf(
                    videofileId,
                    kind.name,
                    algorithmVersion,
                    paramsHash,
                    state.name,
                    startedAt,
                    finishedAt,
                    errorText,
                    isStale,
                )
            },
            recordHash,
        )

    companion object {
        /** Столбцы прогона в порядке чтения из базы. */
        val READ_COLUMNS: String =
            "id, id_videofile, kind, algorithm_version, params_hash, state, started_at, " +
                "finished_at, error_text, is_stale, recordhash"
    }
}

/**
 * Хранилище прогонов анализа.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class AnalysisRunStore(
    private val db: Db,
) {
    /**
     * Заводит новый прогон анализа.
     *
     * Прежние прогоны **не трогаются**: повторный анализ обязан оставить их
     * для сравнения (FR-093).
     *
     * @param run прогон для записи
     * @return записанный прогон с идентификатором
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun begin(run: AnalysisRun): AnalysisRun =
        db.useTransaction { connection ->
            Save.insertIfAbsent(connection, run.toTable())
            val identifier = readIdentifier(connection, run)
            // Читать надо **в этой же** транзакции: чтение через отдельное
            // соединение увидело бы базу до фиксации и не нашло бы строку,
            // которая только что записана.
            connection
                .prepareStatement("$READ_SQL WHERE id = ?")
                .use { statement ->
                    statement.setLong(1, identifier)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            readRow(Row(resultSet))
                        } else {
                            throw ru.svoemesto.syp.core.db.DbException(
                                "Прогон анализа записан, но сразу после записи не прочитан: это дефект, а не результат",
                            )
                        }
                    }
                }
        }

    /**
     * Переводит прогон в состояние работы.
     *
     * @param runId идентификатор прогона
     */
    fun startWork(runId: Long) {
        db.update(
            "UPDATE $NAME SET state = 'WORKING', started_at = COALESCE(started_at, now()) WHERE id = ?",
            runId,
        )
    }

    /**
     * Помечает прогон завершённым.
     *
     * @param runId идентификатор прогона
     */
    fun complete(runId: Long) {
        db.update(
            "UPDATE $NAME SET state = 'DONE', finished_at = now(), error_text = NULL WHERE id = ?",
            runId,
        )
    }

    /**
     * Переводит прогон в ошибку с читаемым текстом.
     *
     * @param runId идентификатор прогона
     * @param errorText текст ошибки на русском
     */
    fun fail(
        runId: Long,
        errorText: String,
    ) {
        require(errorText.isNotBlank()) {
            "Прогон $runId переводится в ERROR: текст ошибки обязателен (FR-092)"
        }
        db.update(
            "UPDATE $NAME SET state = 'ERROR', finished_at = now(), error_text = ? WHERE id = ?",
            errorText,
            runId,
        )
    }

    /**
     * Помечает прогоны эпизода устаревшими.
     *
     * Помечаются прогоны, полученные при других входах: смена порога или
     * версии алгоритма делает прежний результат устаревшим, но **не удаляет**
     * его — удаление уничтожило бы ручные правки оператора (FR-090, SC-006).
     *
     * @param videofileId идентификатор эпизода
     * @param kind вид прогона
     * @param paramsHash актуальный хеш параметров
     * @return число помеченных прогонов
     */
    fun markStaleExcept(
        videofileId: Long,
        kind: AnalysisKind,
        paramsHash: String,
    ): Int =
        db.update(
            "UPDATE $NAME SET is_stale = TRUE WHERE id_videofile = ? AND kind = ? AND params_hash <> ?",
            videofileId,
            kind.name,
            paramsHash,
        )

    /**
     * Читает прогон по идентификатору.
     *
     * @param runId идентификатор прогона
     * @return прогон или `null`, если его нет
     */
    fun find(runId: Long): AnalysisRun? = db.selectOne("$READ_SQL WHERE id = ?", ::readRow, runId)

    /**
     * Последний прогон эпизода заданного вида.
     *
     * @param videofileId идентификатор эпизода
     * @param kind вид прогона
     * @return последний по идентификатору прогон либо `null`
     */
    fun latest(
        videofileId: Long,
        kind: AnalysisKind,
    ): AnalysisRun? =
        db.selectOne(
            "$READ_SQL WHERE id_videofile = ? AND kind = ? ORDER BY id DESC LIMIT 1",
            ::readRow,
            videofileId,
            kind.name,
        )

    /**
     * Все прогоны эпизода, свежие первыми.
     *
     * @param videofileId идентификатор эпизода
     * @return прогоны в обратном порядке идентификаторов
     */
    fun listByVideofile(videofileId: Long): List<AnalysisRun> =
        db.select(
            "$READ_SQL WHERE id_videofile = ? ORDER BY id DESC",
            ::readRow,
            videofileId,
        )

    /** Читает идентификатор только что записанного прогона. */
    private fun readIdentifier(
        connection: java.sql.Connection,
        run: AnalysisRun,
    ): Long =
        connection
            .prepareStatement(
                "SELECT id FROM $NAME WHERE id_videofile = ? AND kind = ? AND params_hash = ? " +
                    "ORDER BY id DESC LIMIT 1",
            ).use { statement ->
                statement.setLong(1, run.videofileId)
                statement.setString(2, run.kind.name)
                statement.setString(3, run.paramsHash)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getLong(1)
                    } else {
                        throw ru.svoemesto.syp.core.db.DbException(
                            "Прогон анализа записан, но идентификатор не прочитан",
                        )
                    }
                }
            }

    /** Строит прогон из типизированной строки выборки. */
    private fun readRow(row: Row): AnalysisRun =
        AnalysisRun(
            id = row.long("id"),
            videofileId = row.long("id_videofile"),
            kind = AnalysisKind.parse(row.string("kind")),
            algorithmVersion = row.string("algorithm_version"),
            paramsHash = row.string("params_hash"),
            state = AnalysisState.parse(row.string("state")),
            startedAt = timestampOf(row.raw("started_at")),
            finishedAt = timestampOf(row.raw("finished_at")),
            errorText = row.stringOrNull("error_text"),
            isStale = row.booleanOrNull("is_stale") == true,
            recordHash = row.stringOrNull("recordhash"),
        )

    /** Приводит значение времени из базы к типу с часовым поясом. */
    private fun timestampOf(value: Any?): OffsetDateTime? = (value as? java.sql.Timestamp)?.toInstant()?.atOffset(java.time.ZoneOffset.UTC)

    companion object {
        /** Имя таблицы прогонов. */
        const val NAME: String = "tbl_analysis_runs"

        /** Записываемые столбцы прогона в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_videofile",
                "kind",
                "algorithm_version",
                "params_hash",
                "state",
                "started_at",
                "finished_at",
                "error_text",
                "is_stale",
            )

        /** Текст запроса выборки прогона. */
        val READ_SQL: String = "SELECT ${AnalysisRun.READ_COLUMNS} FROM $NAME"
    }
}
