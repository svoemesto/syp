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
 * Четвёртое правило — **изменения видны без опроса**. Очередь сообщает
 * подписчику о каждой смене состояния и о каждом движении прогресса
 * ([JobQueueListener]); без этого интерфейс узнаёт о завершении работы только
 * по собственному повторному запросу. Уведомление уходит **после** фиксации
 * транзакции, и ошибка подписчика не отменяет запись: потерянное уведомление —
 * это молчащий экран, а не испорченные данные.
 *
 * @property db доступ к базе сырым JDBC
 * @property listener наблюдатель изменений очереди; по умолчанию никто не
 *   слушает, и очередь работает как раньше
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class JobQueue(
    private val db: Db,
    private val listener: JobQueueListener = JobQueueListener.NONE,
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
    ): Long {
        val created =
            db.use { connection ->
                connection
                    .prepareStatement(
                        """
                        INSERT INTO job (kind, state, subject_type, subject_id, params,
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
        // Задание появилось в очереди — интерфейс обязан узнать об этом сразу,
        // а не через pollInterval, когда до него дотянется воркер.
        val queued = db.use { connection -> readSignal(connection, created) }
        queued?.let { signal -> notify { listener.onStateChanged(signal) } }
        return created
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

        val claimed =
            db.useTransaction { connection ->
                // Сначала находим кандидата, затем захватываем его условным UPDATE.
                // Условие по состоянию делает захват атомарным: из двух воркеров
                // ровно один увидит одну затронутую строку.
                connection
                    .prepareStatement(
                        "SELECT $JOB_COLUMNS FROM job " +
                            "WHERE state = 'WAITING' AND kind IN ($placeholders) " +
                            "ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED",
                    ).use { statement ->
                        kinds.forEachIndexed { index, kind -> statement.setString(index + 1, kind.name) }
                        statement.executeQuery().use { resultSet ->
                            if (!resultSet.next()) {
                                null
                            } else {
                                val candidate = readJob(resultSet)
                                val updated =
                                    connection
                                        .prepareStatement(
                                            "UPDATE job SET state = 'CREATING', started_at = now() " +
                                                "WHERE id = ? AND state = 'WAITING'",
                                        ).use { update ->
                                            update.setLong(1, candidate.id)
                                            update.executeUpdate()
                                        }
                                if (updated > 0) candidate.copy(state = JobState.CREATING) else null
                            }
                        }
                    }
            }
        // Транзакция к этому месту уже зафиксирована: уведомление не может
        // опередить запись, на которую ссылается.
        claimed?.let { job -> notify { listener.onStateChanged(job.toSignal()) } }
        return claimed
    }

    /**
     * Строит снимок изменения из прочитанного задания.
     *
     * @return снимок с тем же состоянием, прогрессом и текстом ошибки
     */
    private fun Job.toSignal(): JobSignal =
        JobSignal(
            id = id,
            kind = kind,
            state = state,
            subject = subject,
            progress = progress,
            errorText = errorText,
        )

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
        val updated =
            db.useTransaction { connection ->
                val current =
                    readSignal(connection, jobId)
                        ?: throw DbException("Задание $jobId не найдено")
                progress.requireNotBehind(current.progress)
                connection
                    .prepareStatement(
                        "UPDATE job SET progress_done = ?, progress_total = ?, progress_note = ? " +
                            "WHERE id = ?",
                    ).use { statement ->
                        statement.setLong(1, progress.done)
                        statement.setLong(2, progress.total)
                        statement.setString(3, progress.note)
                        statement.setLong(4, jobId)
                        statement.executeUpdate()
                    }
                current.copy(progress = progress)
            }
        notify { listener.onProgressChanged(updated) }
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
        val cancelled =
            db.useTransaction { connection ->
                val current = readSignal(connection, jobId)
                requireNotNull(current) { "Задание $jobId не найдено" }
                if (current.state.isTerminal) {
                    throw DbException(
                        "Задание $jobId уже в состоянии ${current.state.name}: отменить его нельзя. " +
                            "Возврат из ERROR в очередь делается только явной повторной постановкой",
                    )
                }
                connection
                    .prepareStatement(
                        """
                        UPDATE job
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
                current.copy(state = JobState.WAITING, errorText = null)
            }
        notify { listener.onStateChanged(cancelled) }
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
        val requeued =
            db.useTransaction { connection ->
                val current =
                    readSignal(connection, jobId)
                        ?: throw DbException(
                            "Задание $jobId не найдено: возвращать его некуда",
                        )
                if (current.state.isTerminal) {
                    throw DbException(
                        "Задание $jobId уже в состоянии ${current.state.name}: возвращать его некуда",
                    )
                }
                connection
                    .prepareStatement(
                        """
                        UPDATE job
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
                current.copy(state = JobState.WAITING, errorText = null)
            }
        notify { listener.onStateChanged(requeued) }
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
            connection.prepareStatement("SELECT $JOB_COLUMNS FROM job WHERE id = ?").use { statement ->
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
            "SELECT $JOB_COLUMNS FROM job$condition ORDER BY created_at, id LIMIT $limit",
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
                          FROM job
                          JOIN artifact ON artifact.job_id = job.id
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
            UPDATE job
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
        val changed =
            db.useTransaction { connection ->
                val current = readSignal(connection, jobId)
                requireNotNull(current) { "Задание $jobId не найдено" }
                JobTransitions.require(current.state, to)
                val affected =
                    connection
                        .prepareStatement(
                            "UPDATE job SET state = ?, error_text = ?, finished_at = now() WHERE id = ? AND state = ?",
                        ).use { statement ->
                            statement.setString(1, to.name)
                            statement.setString(2, errorText)
                            statement.setLong(3, jobId)
                            statement.setString(4, current.state.name)
                            statement.executeUpdate()
                        }
                if (affected == 0) {
                    throw DbException(
                        "Задание $jobId изменилось под рукой: состояние было ${current.state.name}, " +
                            "а к моменту записи — уже другое. Повторите чтение",
                    )
                }
                current.copy(state = to, errorText = errorText)
            }
        notify { listener.onStateChanged(changed) }
    }

    /**
     * Передаёт изменение подписчику, не позволяя ему сорвать саму работу.
     *
     * Очередь к этому моменту уже записана, и повторять её запись из-за
     * упавшего уведомления нельзя: подписчик — наблюдатель, а не участник
     * отказа. Причина сбоя пишется в журнал записи и не поднимается наружу.
     *
     * @param block вызов подписчика
     */
    private fun notify(block: () -> Unit) {
        runCatching(block).onFailure { failure ->
            java.util.logging.Logger
                .getLogger("ru.svoemesto.syp.core.jobs.JobQueue")
                .warning("Подписчик очереди заданий не принял уведомление: ${failure.message}")
        }
    }

    /**
     * Читает снимок изменений задания.
     *
     * @param connection открытое соединение
     * @param jobId идентификатор задания
     * @return снимок либо `null`, если задания нет
     */
    private fun readSignal(
        connection: java.sql.Connection,
        jobId: Long,
    ): JobSignal? =
        connection
            .prepareStatement(
                "SELECT id, kind, state, subject_type, subject_id, progress_done, progress_total, " +
                    "progress_note, error_text FROM job WHERE id = ?",
            ).use { statement ->
                statement.setLong(1, jobId)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        null
                    } else {
                        JobSignal(
                            id = resultSet.getLong("id"),
                            kind = JobKind.parse(resultSet.getString("kind")),
                            state = JobState.parse(resultSet.getString("state")),
                            subject =
                                JobSubject(
                                    type = resultSet.getString("subject_type"),
                                    identifier =
                                        resultSet
                                            .getLong("subject_id")
                                            .let { if (resultSet.wasNull()) null else it },
                                ),
                            progress =
                                JobProgress(
                                    done = resultSet.getLong("progress_done"),
                                    total = resultSet.getLong("progress_total"),
                                    note = resultSet.getString("progress_note") ?: "",
                                ),
                            errorText = resultSet.getString("error_text"),
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
            createdAt = resultSet.getString(columns.indexOf("created_at") + 1),
            startedAt = resultSet.getString(columns.indexOf("started_at") + 1),
            finishedAt = resultSet.getString(columns.indexOf("finished_at") + 1),
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
            createdAt = row.string("created_at"),
            startedAt = row.stringOrNull("started_at"),
            finishedAt = row.stringOrNull("finished_at"),
        )

    private companion object {
        /** Столбцы задания в фиксированном порядке. */
        val JOB_COLUMNS = (
            "id, kind, state, subject_type, subject_id, params, params_hash, " +
                "algorithm_version, progress_done, progress_total, progress_note, " +
                "error_text, created_at, started_at, finished_at"
        )
    }
}
