package ru.svoemesto.syp.core.jobs

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.DbException

/**
 * Очередь заданий.
 *
 * Реализует контракт очереди
 * ([`job-queue.md`](../../../../../specs/001-first-vertical-slice/contracts/job-queue.md)):
 * пять состояний, переходы только разрешённые, прогресс переживает перезапуск
 * воркера, отмена возвращает задание в очередь **с сохранённым прогрессом**.
 *
 * Три правила, ради которых класс написан:
 *
 * 1. **Переходы проверяются, а не подразумеваются.** Попытка перевести задание
 *    из `DONE` в `WORKING` приводит к отказу, а не к тихой порче состояния.
 * 2. **Прогресс хранится в базе, а не в памяти воркера.** Перезапуск воркера
 *    не теряет уже показанный оператору прогресс (research.md Т-15).
 * 3. **Возврат в `ERROR` из любого состояния**, но выход из `ERROR` — только
 *    явной повторной постановкой: молчаливый автоматический повтор скрывает
 *    неисправность, а SC-005 требует, чтобы ошибка была видна.
 *
 * Захват задания выполняется одним оператором `UPDATE ... WHERE state =
 * 'WAITING'`: два воркера не могут взять одно задание, потому что второй
 * увидит ноль затронутых строк.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class JobQueue(
    private val db: Db,
) {
    /**
     * Ставит задание в очередь.
     *
     * @param kind вид задания
     * @param subject предмет работы
     * @param paramsJson параметры задания в виде JSON
     * @param paramsHash хеш параметров: по нему определяется, можно ли
     *   пропустить уже выполненную работу
     * @param algorithmVersion версия алгоритма
     * @return идентификатор созданного задания
     * @throws DbException если задание не записалось
     */
    fun enqueue(
        kind: JobKind,
        subject: JobSubject,
        paramsJson: String,
        paramsHash: String,
        algorithmVersion: String? = null,
    ): Long =
        db.use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO tbl_jobs (kind, state, subject_type, subject_id, params,
                                     params_hash, algorithm_version, progress_done,
                                     progress_total)
                    VALUES (?, 'WAITING', ?, ?, ?::jsonb, ?, ?, 0, 0)
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, kind.name)
                    statement.setString(2, subject.type)
                    statement.setObject(3, subject.identifier)
                    statement.setString(4, paramsJson)
                    statement.setString(5, paramsHash)
                    statement.setString(6, algorithmVersion)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /**
     * Берёт задание в работу.
     *
     * Задание выбирается по времени постановки: сначала те, что ждут дольше.
     * Состояние сразу становится [JobState.CREATING] — «взято воркером» —
     * чтобы второго претендента на то же задание не было даже в момент
     * захвата.
     *
     * @param kinds виды заданий, которые готов брать этот воркер. У админского
     *   бэкенда — все четыре; пустой список запрещён
     * @return взятое задание или `null`, если очередь пуста
     * @throws DbException если очередь пуста или вид не задан
     */
    fun claim(kinds: List<JobKind>): Job? {
        require(kinds.isNotEmpty()) {
            "Список видов заданий пуст: воркер без видов заданий не имеет смысла. " +
                "У публичного бэкенда исполнителя нет вовсе (FR-085)"
        }
        val placeholders = kinds.joinToString(", ") { "?" }

        return db.useTransaction { connection ->
            // Сначала находим кандидата, затем захватываем его условным UPDATE.
            // Условие по состоянию делает захват атомарным: из двух воркеров
            // ровно один увидит одну затронутую строку.
            connection
                .prepareStatement(
                    "SELECT $JOB_COLUMNS FROM tbl_jobs " +
                        "WHERE state = 'WAITING' AND kind IN ($placeholders) " +
                        // Лица ждут разбора того же самого предмета. Правило
                        // «разборы вперёд» было бы голодным: один зависший
                        // ANALYZE заблокировал бы все FACES разом. Здесь ждёт
                        // только тот FACES, у которого впереди разбор его же
                        // эпизода, и как только разбор дойдёт до конца — снимет
                        // ожидание.
                        FACES_AWAITS_ANALYSIS_SQL +
                        "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
                ).use { statement ->
                    kinds.forEachIndexed { index, kind -> statement.setString(index + 1, kind.name) }
                    statement.executeQuery().use { resultSet ->
                        if (!resultSet.next()) {
                            null
                        } else {
                            val candidate = readJob(resultSet)
                            val claimed =
                                connection
                                    .prepareStatement(
                                        "UPDATE tbl_jobs SET state = 'CREATING', started_at = now() " +
                                            "WHERE id = ? AND state = 'WAITING'",
                                    ).use { update ->
                                        update.setLong(1, candidate.id)
                                        update.executeUpdate()
                                    }
                            if (claimed > 0) candidate.copy(state = JobState.CREATING) else null
                        }
                    }
                }
        }
    }

    /**
     * Переводит взятое задание в состояние работы.
     *
     * @param jobId идентификатор задания
     * @throws DbException если задание не найдено или переход запрещён
     */
    fun startWork(jobId: Long) = transition(jobId, JobState.WORKING)

    /**
     * Помечает задание как завершённое.
     *
     * Задание **нельзя** пометить завершённым, если у него нет общего объёма
     * работы: ограничение базы `job_done_total_set` требует ненулевого
     * `progress_total` в состоянии `DONE`. Работа, о которой нечего сказать
     * «сколько», не считается завершённой.
     *
     * @param jobId идентификатор задания
     * @throws DbException если задание не найдено или переход запрещён
     */
    fun complete(jobId: Long) = transition(jobId, JobState.DONE)

    /**
     * Переводит задание в ошибку с читаемым текстом.
     *
     * Текст ошибки обязателен: ограничение базы `job_error_only_on_error`
     * отклоняет состояние `ERROR` без него. «Процесс завершился с кодом 1»
     * текстом ошибки не является — это следствие, а не причина.
     *
     * @param jobId идентификатор задания
     * @param errorText текст ошибки на русском
     * @throws DbException если текст пуст или переход запрещён
     */
    fun fail(
        jobId: Long,
        errorText: String,
    ) {
        require(errorText.isNotBlank()) {
            "Задание $jobId переводится в ERROR: текст ошибки обязателен (FR-092)"
        }
        transition(jobId, JobState.ERROR, errorText)
    }

    /**
     * Записывает прогресс задания.
     *
     * Прогресс монотонен: уменьшение считается ошибкой вызывающего кода, а не
     * тихо принимается. Возврат задания в очередь после прерывания прогресс
     * не уменьшает — его и не меняют, поэтому правило соблюдается.
     *
     * @param jobId идентификатор задания
     * @param progress новый прогресс
     * @throws DbException если прогресс уменьшился или задание не найдено
     */
    fun reportProgress(
        jobId: Long,
        progress: JobProgress,
    ) {
        db.useTransaction { connection ->
            val previous =
                readProgress(connection, jobId)
                    ?: throw DbException("Задание $jobId не найдено")
            progress.requireNotBehind(previous)
            connection
                .prepareStatement(
                    "UPDATE tbl_jobs SET progress_done = ?, progress_total = ?, progress_note = ? " +
                        "WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, progress.done)
                    statement.setLong(2, progress.total)
                    statement.setString(3, progress.note)
                    statement.setLong(4, jobId)
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Отменяет задание оператором.
     *
     * Задание возвращается в очередь **с сохранённым прогрессом**: работа не
     * начинается заново молча (контракт очереди § 3.5, FR-003).
     *
     * @param jobId идентификатор задания
     * @param reason причина отмены: попадает в текст ошибки, чтобы оператор
     *   потом понимал, почему задание вернулось в очередь
     * @throws DbException если задание уже в терминальном состоянии
     */
    fun cancel(
        jobId: Long,
        reason: String,
    ) {
        require(reason.isNotBlank()) {
            "Отмена задания $jobId без причины невозможна: без причины отмена неотличима от сбоя"
        }
        db.useTransaction { connection ->
            val current = readState(connection, jobId)
            if (current.isTerminal) {
                throw DbException(
                    "Задание $jobId уже в состоянии ${current.name}: отменить его нельзя. " +
                        "Возврат из ERROR в очередь делается только явной повторной постановкой",
                )
            }
            connection
                .prepareStatement(
                    """
                    UPDATE tbl_jobs
                       SET state = 'WAITING',
                           started_at = NULL,
                           finished_at = NULL,
                           error_text = NULL,
                           progress_note = ?
                     WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    // Сохранённый прогресс не трогается: он и есть признак того,
                    // что работа не начинается заново.
                    statement.setString(1, "отменено оператором: $reason")
                    statement.setLong(2, jobId)
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Возвращает задание в очередь после прерывания воркера.
     *
     * Отличие от [cancel] в том, что причина — не решение человека, а
     * остановка процесса. Прогресс сохраняется в обоих случаях.
     *
     * @param jobId идентификатор задания
     * @param reason причина прерывания
     * @throws DbException если задание уже в терминальном состоянии
     */
    fun requeue(
        jobId: Long,
        reason: String,
    ) {
        db.useTransaction { connection ->
            val current = readState(connection, jobId)
            if (current.isTerminal) {
                throw DbException(
                    "Задание $jobId уже в состоянии ${current.name}: возвращать его некуда",
                )
            }
            connection
                .prepareStatement(
                    """
                    UPDATE tbl_jobs
                       SET state = 'WAITING',
                           started_at = NULL,
                           finished_at = NULL,
                           error_text = NULL,
                           progress_note = ?
                     WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, "прервано: $reason")
                    statement.setLong(2, jobId)
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Читает задание по идентификатору.
     *
     * @param jobId идентификатор задания
     * @return задание или `null`, если его нет
     * @throws DbException если выборка не удалась
     */
    fun find(jobId: Long): Job? =
        db.use { connection ->
            connection.prepareStatement("SELECT $JOB_COLUMNS FROM tbl_jobs WHERE id = ?").use { statement ->
                statement.setLong(1, jobId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) readJob(resultSet) else null
                }
            }
        }

    /**
     * Перечисляет задания с указанными состояниями.
     *
     * @param states состояния, которые нужно показать; пустой список означает
     *   «все состояния»
     * @param limit максимум строк в ответе
     * @return задания в порядке постановки
     * @throws DbException если выборка не удалась
     */
    fun list(
        states: List<JobState> = emptyList(),
        limit: Int = 200,
    ): List<Job> {
        val condition =
            if (states.isEmpty()) {
                ""
            } else {
                " WHERE state IN (${states.joinToString(", ") { "?" }})"
            }
        return db.select(
            "SELECT $JOB_COLUMNS FROM tbl_jobs$condition ORDER BY created_at, id LIMIT $limit",
            ::readJobRow,
            *states.map { it.name }.toTypedArray(),
        )
    }

    /**
     * Проверяет, выполнялось ли задание такого вида с такими параметрами и
     * завершилось ли оно успешно.
     *
     * Проверяется **регистрация результата**, а не наличие файла (Р-10,
     * контракт очереди § 3.3): «файл есть» не означает «работа выполнена».
     *
     * @param kind вид задания
     * @param paramsHash хеш параметров
     * @return `true`, если есть успешно завершённое задание с таким хешем и
     *   зарегистрированным результатом
     * @throws DbException если выборка не удалась
     */
    fun hasCompletedWithReadyArtifact(
        kind: JobKind,
        paramsHash: String,
    ): Boolean {
        val count =
            db.use { connection ->
                connection
                    .prepareStatement(
                        """
                        SELECT count(*)
                          FROM tbl_jobs job
                          JOIN tbl_artifacts artifact ON artifact.job_id = job.id
                         WHERE job.kind = ?
                           AND job.params_hash = ?
                           AND job.state = 'DONE'
                           AND artifact.state = 'READY'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, kind.name)
                        statement.setString(2, paramsHash)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next()
                            resultSet.getLong(1)
                        }
                    }
            }
        return count > 0
    }

    /**
     * Помечает устаревшими результаты с другим набором параметров.
     *
     * Старые результаты **не удаляются** и пересчёт автоматически не
     * запускается: автоматический пересчёт уничтожил бы ручные правки
     * оператора (SC-006, контракт очереди § 3.3).
     *
     * @param kind вид задания
     * @param currentParamsHash актуальный хеш параметров
     * @return число помеченных устаревшими заданий
     * @throws DbException если обновление не удалось
     */
    fun markStaleExcept(
        kind: JobKind,
        currentParamsHash: String,
    ): Int =
        db.update(
            """
            UPDATE tbl_jobs
               SET progress_note = COALESCE(progress_note, '') ||
                   ' — результат помечен устаревшим: изменились параметры задания'
             WHERE kind = ?
               AND params_hash <> ?
               AND state = 'DONE'
               AND finished_at IS NOT NULL
               AND progress_note NOT LIKE '%помечен устаревшим%'
            """.trimIndent(),
            kind.name,
            currentParamsHash,
        )

    /**
     * Общий переход состояния с проверкой разрешённости.
     *
     * @param jobId идентификатор задания
     * @param to новое состояние
     * @param errorText текст ошибки при переходе в [JobState.ERROR]
     * @throws DbException если задание не найдено или переход запрещён
     */
    private fun transition(
        jobId: Long,
        to: JobState,
        errorText: String? = null,
    ) {
        db.useTransaction { connection ->
            val current = readState(connection, jobId)
            JobTransitions.require(current, to)
            val affected =
                connection
                    .prepareStatement(
                        "UPDATE tbl_jobs SET state = ?, error_text = ?, finished_at = now() WHERE id = ? AND state = ?",
                    ).use { statement ->
                        statement.setString(1, to.name)
                        statement.setString(2, errorText)
                        statement.setLong(3, jobId)
                        statement.setString(4, current.name)
                        statement.executeUpdate()
                    }
            if (affected == 0) {
                throw DbException(
                    "Задание $jobId изменилось под рукой: состояние было ${current.name}, " +
                        "а к моменту записи — уже другое. Повторите чтение",
                )
            }
        }
    }

    /** Читает состояние задания. */
    private fun readState(
        connection: java.sql.Connection,
        jobId: Long,
    ): JobState {
        connection.prepareStatement("SELECT state FROM tbl_jobs WHERE id = ?").use { statement ->
            statement.setLong(1, jobId)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next()) throw DbException("Задание $jobId не найдено")
                return JobState.parse(resultSet.getString(1))
            }
        }
    }

    /** Читает сохранённый прогресс задания. */
    private fun readProgress(
        connection: java.sql.Connection,
        jobId: Long,
    ): JobProgress? {
        connection
            .prepareStatement(
                "SELECT progress_done, progress_total, progress_note FROM tbl_jobs WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, jobId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) return null
                    return JobProgress(
                        done = resultSet.getLong(1),
                        total = resultSet.getLong(2),
                        note = resultSet.getString(3) ?: "",
                    )
                }
            }
    }

    /** Строит задание из строки результата запроса. */
    private fun readJob(resultSet: java.sql.ResultSet): Job {
        val columns = JOB_COLUMNS.split(", ")
        return Job(
            id = resultSet.getLong(columns.indexOf("id") + 1),
            kind = JobKind.parse(resultSet.getString(columns.indexOf("kind") + 1)),
            state = JobState.parse(resultSet.getString(columns.indexOf("state") + 1)),
            subject =
                JobSubject(
                    type = resultSet.getString(columns.indexOf("subject_type") + 1),
                    identifier =
                        resultSet
                            .getLong(columns.indexOf("subject_id") + 1)
                            .let { if (resultSet.wasNull()) null else it },
                ),
            paramsJson = resultSet.getString(columns.indexOf("params") + 1),
            paramsHash = resultSet.getString(columns.indexOf("params_hash") + 1),
            algorithmVersion = resultSet.getString(columns.indexOf("algorithm_version") + 1),
            progress =
                JobProgress(
                    done = resultSet.getLong(columns.indexOf("progress_done") + 1),
                    total = resultSet.getLong(columns.indexOf("progress_total") + 1),
                    note = resultSet.getString(columns.indexOf("progress_note") + 1) ?: "",
                ),
            errorText = resultSet.getString(columns.indexOf("error_text") + 1),
            createdAt = instantText(resultSet, columns, "created_at"),
            startedAt = instantText(resultSet, columns, "started_at"),
            finishedAt = instantText(resultSet, columns, "finished_at"),
        )
    }

    /** Строит задание из готовой строки результата [ru.svoemesto.syp.core.db.Row]. */
    private fun readJobRow(row: ru.svoemesto.syp.core.db.Row): Job =
        Job(
            id = row.long("id"),
            kind = JobKind.parse(row.string("kind")),
            state = JobState.parse(row.string("state")),
            subject = JobSubject(row.stringOrNull("subject_type"), row.longOrNull("subject_id")),
            paramsJson = row.string("params"),
            paramsHash = row.string("params_hash"),
            algorithmVersion = row.stringOrNull("algorithm_version"),
            progress =
                JobProgress(
                    done = row.long("progress_done"),
                    total = row.long("progress_total"),
                    note = row.stringOrNull("progress_note") ?: "",
                ),
            errorText = row.stringOrNull("error_text"),
            createdAt = instantText(row, "created_at"),
            startedAt = instantText(row, "started_at"),
            finishedAt = instantText(row, "finished_at"),
        )

    private companion object {
        /** Столбцы задания в фиксированном порядке. */
        val JOB_COLUMNS = (
            "id, kind, state, subject_type, subject_id, params, params_hash, " +
                "algorithm_version, progress_done, progress_total, progress_note, " +
                "error_text, created_at, started_at, finished_at"
        )

        /**
         * Условие «лица ждут разбора своего предмета».
         *
         * Задание `FACES` не берётся в работу, пока по тому же эпизоду есть
         * недоделанный `ANALYZE`. Само правило в очереди одно: порядок заданий
         * задан временем создания, и без этого условия лица, поставленные раньше
         * разбора, вставали в очередь первыми и получали эпизод без планов.
         *
         * Ожидание снимается, когда разбор доходит до конца: условие смотрит
         * только на незавершённые состояния, поэтому упавший разбор (`ERROR`)
         * лица не блокирует — иначе один неудачный разбор остановил бы работу
         * навсегда.
         */
        const val FACES_AWAITS_ANALYSIS_SQL: String =
            "AND NOT (kind = 'FACES' AND EXISTS (" +
                "SELECT 1 FROM tbl_jobs ahead " +
                "WHERE ahead.kind = 'ANALYZE' " +
                "AND ahead.subject_type = tbl_jobs.subject_type " +
                "AND ahead.subject_id = tbl_jobs.subject_id " +
                "AND ahead.state IN ('CREATING', 'WAITING', 'WORKING'))) "
    }

    /**
     * Читает момент времени из результата запроса и отдаёт его в ISO-8601.
     *
     * Зачем: значение, прочитанное как строка, приходит в формате самой
     * базы — «2026-10-02 18:46:29.165745+00». Формат не стандартный: вместо
     * `T` стоит пробел, смещение без минут. Разбор такого момента в браузере
     * даёт «неверная дата», и читатель не видит ни ошибки, ни подсказки, что
     * время есть, но оно нечитаемо.
     *
     * @param resultSet результат запроса
     * @param columns имена столбцов в порядке чтения
     * @param name имя столбца
     * @return момент времени в ISO-8601 либо `null`, если столбец пуст
     */
    private fun instantText(
        resultSet: java.sql.ResultSet,
        columns: List<String>,
        name: String,
    ): String {
        val index = columns.indexOf(name) + 1
        if (index <= 0) {
            return ""
        }
        val moment = resultSet.getTimestamp(index) ?: return ""
        return moment.toInstant().toString()
    }

    /**
     * Читает момент времени из готовой строки и отдаёт его в ISO-8601.
     *
     * @param row готовая строка результата
     * @param name имя столбца
     * @return момент времени в ISO-8601 либо `null`, если столбец пуст
     */
    private fun instantText(
        row: ru.svoemesto.syp.core.db.Row,
        name: String,
    ): String {
        val raw = runCatching { row.stringOrNull(name) }.getOrNull() ?: return ""
        if (raw.isBlank()) {
            return ""
        }
        return runCatching {
            java.time.OffsetDateTime
                .parse(raw.replace(' ', 'T'))
                .toInstant()
                .toString()
        }.getOrDefault(raw)
    }
}
