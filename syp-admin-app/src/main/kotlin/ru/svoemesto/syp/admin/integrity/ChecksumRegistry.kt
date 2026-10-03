package ru.svoemesto.syp.admin.integrity

import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.time.OffsetDateTime

/**
 * Состояние записи справочника сумм.
 *
 * Ровно четыре значения — столько же, сколько у прогона анализа и сценария:
 * это состояния результата вычисления, а не состояния очереди. У задания
 * очереди их пять, потому что там ещё есть «взято воркером, работа не
 * началась» (FR-003).
 *
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class ChecksumState {
    /** Запись создана, подсчёт ещё не начался. */
    CREATING,

    /** Идёт чтение файла. */
    WORKING,

    /** Сумма посчитана и записана. */
    DONE,

    /** Подсчёт не удался; [ChecksumEntry.errorText] обязателен. */
    ERROR,
    ;

    companion object {
        /**
         * Разбирает состояние из строки базы.
         *
         * @param value значение столбца `state`
         * @return состояние
         * @throws IllegalArgumentException если значения нет в наборе
         */
        fun parse(value: String): ChecksumState =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "Неизвестное состояние суммы: «$value». Допустимы: ${entries.joinToString()}",
                )
    }
}

/**
 * Запись справочника сумм исходника.
 *
 * Запись **не затирается**: пересчёт создаёт новую, а прежняя остаётся и
 * помечается устаревшей. По ней видно, что файл менялся, — а это и есть
 * причина, по которой сумму приходится пересчитывать (FR-090).
 *
 * **Одна актуальная запись на эпизод** обеспечивается базой: частичный
 * уникальный индекс по `state = 'DONE' AND is_stale = FALSE`. Здесь это
 * правило не дублируется, а только соблюдается: новая актуальная запись
 * появляется лишь после того, как прежняя помечена устаревшей.
 *
 * **Прерванный подсчёт не оставляет запись `DONE`.** Запись появляется сразу,
 * но переходит в `DONE` только после того, как файл дочитан и сумма
 * получена; прерывание оставляет её в состоянии `CREATING` или `WORKING`,
 * из которого выхода нет — такая запись не является результатом.
 *
 * @property id идентификатор записи; `null`, пока не записана
 * @property videofileId эпизод-владелец
 * @property algorithm алгоритм; в модели только `SHA-256`
 * @property digest шестнадцатеричная сумма; `null`, пока подсчёт не
 *   завершён, — суммы у незавершённого подсчёта не существует
 * @property byteSize размер файла, на котором считали
 * @property fileMtime время изменения файла, на котором считали
 * @property state состояние подсчёта
 * @property isStale файл изменился после подсчёта
 * @property computedAt когда сумма посчитана
 * @property errorText текст ошибки при [ChecksumState.ERROR]
 * @property jobId задание, считавшее сумму
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ChecksumEntry(
    val id: Long? = null,
    val videofileId: Long,
    val algorithm: String = ALGORITHM_SHA256,
    val digest: String? = null,
    val byteSize: Long,
    val fileMtime: OffsetDateTime,
    val state: ChecksumState,
    val isStale: Boolean = false,
    val computedAt: OffsetDateTime? = null,
    val errorText: String? = null,
    val jobId: Long? = null,
    val recordHash: String? = null,
) {
    /** Сумма готова и пригодна для сверки на машине пользователя. */
    val isUsable: Boolean
        get() = state == ChecksumState.DONE && !isStale && computedAt != null

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами записи справочника
     */
    fun toTable(): Table =
        Table(
            ChecksumRegistry.TABLE,
            ChecksumRegistry.COLUMNS,
            {
                listOf(
                    videofileId,
                    algorithm,
                    digest,
                    byteSize,
                    fileMtime,
                    state.name,
                    isStale,
                    computedAt,
                    errorText,
                    jobId,
                )
            },
            recordHash,
        )

    companion object {
        /** Алгоритм подсчёта: в модели он единственный. */
        const val ALGORITHM_SHA256: String = "SHA-256"
    }
}

/**
 * Справочник сумм исходных файлов эпизодов.
 *
 * Реестр хранит **историю** пересчётов и отвечает на вопросы, без которых
 * сценарий отдать нельзя (ADR-0009, FR-089):
 *
 * 1. **Какая сумма актуальная** — та, что посчитана при текущих размере и
 *    времени изменения файла. Такая запись на эпизод ровно один; вторая
 *    актуальная запись в базу не попадёт (частичный уникальный индекс).
 * 2. **Устарела ли она** — если размер файла или время его изменения не
 *    совпадают с теми, при которых считали. Прежнее значение при этом
 *    сохраняется: по нему видно, что файл менялся.
 * 3. **Что было раньше** — полная история: пересчёт не затирает прежнюю
 *    запись, а создаёт новую рядом с ней.
 *
 * Порядок действий при пересчёте задан явно: сначала прежняя актуальная сумма
 * помечается устаревшей, затем новая приводится в состояние `DONE`. Обратный
 * порядок нарушил бы частичный уникальный индекс — вторая актуальная запись
 * того же эпизода невозможна, и это защита базы, а не соглашение в коде.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ChecksumRegistry(
    private val db: Db,
) {
    /**
     * Открывает новый подсчёт для эпизода.
     *
     * Незавершённые записи того же эпизода (из прерванного подсчёта) удаляются:
     * они не результат и ничего не говорят — по ним видно лишь то, что
     * подсчёт когда-то начался. История **посчитанных** сумм при этом не
     * трогается.
     *
     * @param videofile эпизод, для которой считается сумма
     * @param jobId задание, считающее сумму
     * @return созданная запись в состоянии [ChecksumState.CREATING]
     */
    fun begin(
        videofile: Videofile,
        jobId: Long?,
    ): ChecksumEntry =
        db.useTransaction { connection ->
            val videofileId =
                videofile.id
                    ?: throw ru.svoemesto.syp.core.db.DbException(
                        "у эпизода «${videofile.name}» нет идентификатора: подсчёт ставить некуда",
                    )
            // Два подсчёта одного эпизода несовместимы: они бы делили файл между
            // собой, а оператор видел бы два задания, каждое из которых
            // утверждает, что считает. Наличие работы проверяется до удаления
            // незавершённых записей, иначе отказ стёр бы след уже идущего счёта.
            // Собственное задание подсчёта исключается: оно и есть тот
            // подсчёт, ради которого запись заводится.
            val running =
                connection
                    .prepareStatement(
                        "SELECT count(*) FROM tbl_jobs WHERE kind = 'HASH' AND subject_type = 'EPISODE' " +
                            "AND subject_id = ? AND state IN ('CREATING', 'WORKING') " +
                            "AND (CAST(? AS BIGINT) IS NULL OR id <> CAST(? AS BIGINT))",
                    ).use { statement ->
                        statement.setLong(1, videofileId)
                        statement.setObject(2, jobId)
                        statement.setObject(3, jobId)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            resultSet.getLong(1)
                        }
                    }
            if (running > 0) {
                throw DomainException(
                    ErrorCode.CONFLICT,
                    "сумма эпизода «${videofile.name}» уже считается: второй подсчёт того же эпизода " +
                        "перемешал бы две работы в один результат",
                )
            }
            connection
                .prepareStatement(
                    "DELETE FROM $TABLE WHERE id_videofile = ? AND state IN ('CREATING', 'WORKING')",
                ).use { statement ->
                    statement.setLong(1, videofileId)
                    statement.executeUpdate()
                }
            val entry =
                ChecksumEntry(
                    videofileId = videofileId,
                    byteSize = videofile.byteSize,
                    fileMtime = videofile.fileMtime,
                    state = ChecksumState.CREATING,
                    jobId = jobId,
                )
            Save.insertIfAbsent(connection, entry.toTable())
            readByVideofileAndSize(connection, videofileId, videofile.byteSize, videofile.fileMtime, jobId)
                ?: throw ru.svoemesto.syp.core.db.DbException(
                    "Запись справочника сумм создана, но сразу после записи не прочитана: это дефект, а не результат",
                )
        }

    /**
     * Переводит запись в состояние работы.
     *
     * @param entryId идентификатор записи
     * @throws ru.svoemesto.syp.core.db.DbException если записи нет
     */
    fun markWorking(entryId: Long) {
        db.update(
            "UPDATE $TABLE SET state = 'WORKING' WHERE id = ? AND state = 'CREATING'",
            entryId,
        )
    }

    /**
     * Записывает посчитанную сумму и делает её актуальной.
     *
     * Обе операции выполняются одной транзакцией: помечение прежней суммы
     * устаревшей и появление новой актуальной — это одно событие. Разорванное
     * во времени состояние, при котором актуальных сумм ноль, означало бы
     * «эпизод пригодна» ровно в тот момент, когда она непригодна.
     *
     * @param entryId идентификатор записи
     * @param digest посчитанная сумма
     * @param byteSize размер файла, на котором считали
     * @param fileMtime время изменения файла, на котором считали
     * @return запись в состоянии [ChecksumState.DONE]
     * @throws ru.svoemesto.syp.core.db.DbException если записи нет или сумма
     *   не записалась
     */
    fun complete(
        entryId: Long,
        digest: String,
        byteSize: Long,
        fileMtime: OffsetDateTime,
    ): ChecksumEntry {
        require(DIGEST.matches(digest)) {
            "Сумма «$digest» не в формате 64 шестнадцатеричных символов в нижнем регистре: " +
                "именно такой вывод даёт sha256sum на машине пользователя, и сверка идёт без преобразований"
        }
        return db.useTransaction { connection ->
            val current =
                readById(connection, entryId)
                    ?: throw ru.svoemesto.syp.core.db.DbException(
                        "Запись справочника сумм $entryId не найдена: подсчёт некуда записывать результат",
                    )
            // Сначала прежняя актуальная сумма уходит в архив устаревших,
            // и только потом появляется новая: иначе в момент её вставки у
            // эпизода было бы две актуальные суммы, что база не допускает.
            connection
                .prepareStatement(
                    "UPDATE $TABLE SET is_stale = TRUE WHERE id_videofile = ? AND is_stale = FALSE " +
                        "AND state = 'DONE' AND id <> ?",
                ).use { statement ->
                    statement.setLong(1, current.videofileId)
                    statement.setLong(2, entryId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "UPDATE $TABLE SET state = 'DONE', digest = ?, byte_size = ?, file_mtime = ?, " +
                        "computed_at = now(), error_text = NULL, is_stale = FALSE WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, digest)
                    statement.setLong(2, byteSize)
                    statement.setObject(3, fileMtime)
                    statement.setLong(4, entryId)
                    statement.executeUpdate()
                }
            readById(connection, entryId)
                ?: throw ru.svoemesto.syp.core.db.DbException(
                    "Запись справочника сумм $entryId обновлена, но сразу после записи не прочитана",
                )
        }
    }

    /**
     * Помечает подсчёт неудачным.
     *
     * Текст ошибки обязателен: ограничение базы
     * `source_file_checksum_error_only_on_error` отклоняет состояние `ERROR`
     * без него, и это правильно — «подсчёт не удался» без причины ничем не
     * отличается от сбоя записи.
     *
     * @param entryId идентификатор записи
     * @param errorText текст ошибки на русском
     * @throws ru.svoemesto.syp.core.db.DbException если записи нет
     */
    fun fail(
        entryId: Long,
        errorText: String,
    ) {
        require(errorText.isNotBlank()) {
            "Подсчёт суммы $entryId помечается ошибкой: текст обязателен (FR-092)"
        }
        db.update(
            "UPDATE $TABLE SET state = 'ERROR', error_text = ? WHERE id = ?",
            errorText,
            entryId,
        )
    }

    /**
     * Читает запись по идентификатору.
     *
     * @param entryId идентификатор записи
     * @return запись или `null`, если её нет
     */
    fun find(entryId: Long): ChecksumEntry? = db.selectOne("$READ_SQL WHERE id = ?", ::readRow, entryId)

    /**
     * Актуального сумма эпизода.
     *
     * @param videofileId идентификатор эпизода
     * @return запись в состоянии `DONE` без признака устаревания либо
     *   `null`, если такой записи нет
     */
    fun current(videofileId: Long): ChecksumEntry? =
        db.selectOne(
            "$READ_SQL WHERE id_videofile = ? AND state = 'DONE' AND is_stale = FALSE",
            ::readRow,
            videofileId,
        )

    /**
     * Последняя запись справочника для эпизода.
     *
     * Возвращается любой, включая незавершённую: интерфейсу нужно показать
     * «считается» или «ошибка», а не пустую страницу (FR-003).
     *
     * @param videofileId идентификатор эпизода
     * @return последняя по идентификатору запись либо `null`
     */
    fun latest(videofileId: Long): ChecksumEntry? =
        db.selectOne("$READ_SQL WHERE id_videofile = ? ORDER BY id DESC LIMIT 1", ::readRow, videofileId)

    /**
     * История пересчётов эпизода, свежий первый.
     *
     * @param videofileId идентификатор эпизода
     * @return записи в обратном порядке идентификаторов
     */
    fun history(videofileId: Long): List<ChecksumEntry> =
        db.select("$READ_SQL WHERE id_videofile = ? ORDER BY id DESC", ::readRow, videofileId)

    /**
     * Помечает актуальную сумму устаревшей, если источник изменился.
     *
     * Сравниваются размер файла и время его изменения с теми, при которых
     * считалась сумма. Расхождение по любому из них означает подмену или
     * перезапись источника: прежняя сумма больше не описывает файл и
     * помечается устаревшей (FR-090). Значение при этом сохраняется —
     * стереть его значило бы стереть след подмены.
     *
     * @param videofile эпизод с текущими параметрами источника
     * @return число помеченных устаревшими записей
     */
    fun markStaleWhenSourceChanged(videofile: Videofile): Int {
        val videofileId =
            videofile.id
                ?: return 0
        return db.update(
            "UPDATE $TABLE SET is_stale = TRUE " +
                "WHERE id_videofile = ? AND state = 'DONE' AND is_stale = FALSE " +
                "AND (byte_size <> ? OR file_mtime <> ?)",
            videofileId,
            videofile.byteSize,
            videofile.fileMtime,
        )
    }

    /**
     * Пригодна ли эпизод к выдаче сценария.
     *
     * Единственный признак — наличие актуальной суммы. Молча выдать сценарий
     * эпизода без суммы нельзя: пользователь узнал бы об этом через час работы
     * на своей машине (ADR-0009, последствие 4).
     *
     * @param videofileId идентификатор эпизода
     * @return `true`, если актуальный сумма есть
     */
    fun isUsable(videofileId: Long): Boolean = current(videofileId)?.isUsable == true

    /** Читает запись по идентификатору в пределах открытого соединения. */
    private fun readById(
        connection: java.sql.Connection,
        entryId: Long,
    ): ChecksumEntry? =
        connection.prepareStatement("$READ_SQL WHERE id = ?").use { statement ->
            statement.setLong(1, entryId)
            statement.executeQuery().use { resultSet -> if (resultSet.next()) read(resultSet) else null }
        }

    /**
     * Читает только что созданную запись.
     *
     * Идентификатор выдаёт база, поэтому запись ищется по значениям, которыми
     * она только что записана: эпизод, размер, время изменения и задание.
     * Берётся **последняя** из совпавших: у пересчёта значения совпадают с
     * прежними, и поиск без порядка вернул бы прежнюю запись — подсчёт был бы
     * записан не туда, куда он поставлен.
     */
    private fun readByVideofileAndSize(
        connection: java.sql.Connection,
        videofileId: Long,
        byteSize: Long,
        fileMtime: OffsetDateTime,
        jobId: Long?,
    ): ChecksumEntry? =
        connection
            .prepareStatement(
                "$READ_SQL WHERE id_videofile = ? AND byte_size = ? AND file_mtime = ? " +
                    "AND job_id IS NOT DISTINCT FROM ? ORDER BY id DESC LIMIT 1",
            ).use { statement ->
                statement.setLong(1, videofileId)
                statement.setLong(2, byteSize)
                statement.setObject(3, fileMtime)
                statement.setObject(4, jobId)
                statement.executeQuery().use { resultSet -> if (resultSet.next()) read(resultSet) else null }
            }

    /** Строит запись из готовой строки результата. */
    private fun read(resultSet: java.sql.ResultSet): ChecksumEntry =
        ChecksumEntry(
            id = resultSet.getLong("id"),
            videofileId = resultSet.getLong("id_videofile"),
            algorithm = resultSet.getString("algorithm"),
            digest = resultSet.getString("digest"),
            byteSize = resultSet.getLong("byte_size"),
            fileMtime = resultSet.getObject("file_mtime", OffsetDateTime::class.java),
            state = ChecksumState.parse(resultSet.getString("state")),
            isStale = resultSet.getBoolean("is_stale"),
            computedAt = resultSet.getObject("computed_at", OffsetDateTime::class.java),
            errorText = resultSet.getString("error_text"),
            jobId = resultSet.getLong("job_id").let { if (resultSet.wasNull()) null else it },
            recordHash = resultSet.getString("recordhash"),
        )

    /** Строит запись из типизированной строки выборки. */
    private fun readRow(row: Row): ChecksumEntry =
        ChecksumEntry(
            id = row.long("id"),
            videofileId = row.long("id_videofile"),
            algorithm = row.string("algorithm"),
            digest = row.stringOrNull("digest"),
            byteSize = row.long("byte_size"),
            fileMtime =
                (row.raw("file_mtime") as? java.sql.Timestamp)
                    ?.toInstant()
                    ?.atOffset(java.time.ZoneOffset.UTC)
                    ?: throw ru.svoemesto.syp.core.db
                        .DbException("у записи суммы не прочитано время изменения файла"),
            state = ChecksumState.parse(row.string("state")),
            isStale = row.booleanOrNull("is_stale") == true,
            computedAt =
                (row.raw("computed_at") as? java.sql.Timestamp)
                    ?.toInstant()
                    ?.atOffset(java.time.ZoneOffset.UTC),
            errorText = row.stringOrNull("error_text"),
            jobId = row.longOrNull("job_id"),
            recordHash = row.stringOrNull("recordhash"),
        )

    companion object {
        /** Имя таблицы справочника сумм. */
        const val TABLE: String = "tbl_source_file_checksums"

        /** Записываемые столбцы записи в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_videofile",
                "algorithm",
                "digest",
                "byte_size",
                "file_mtime",
                "state",
                "is_stale",
                "computed_at",
                "error_text",
                "job_id",
            )

        /** Формат значения суммы: 64 шестнадцатеричных символа в нижнем регистре. */
        private val DIGEST: Regex = Regex("^[0-9a-f]{64}$")

        /** Столбцы записи в порядке чтения из базы. */
        private val READ_SQL: String =
            (
                "SELECT id, id_videofile, algorithm, digest, byte_size, file_mtime, state, is_stale, " +
                    "computed_at, error_text, job_id, recordhash FROM $TABLE"
            )
    }
}
