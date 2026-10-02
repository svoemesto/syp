package ru.svoemesto.syp.admin.jobs

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobState
import ru.svoemesto.syp.core.media.ExternalProgramFailed
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Воркер заданий админского бэкенда.
 *
 * Берёт виды `ANALYZE`, `FACES`, `TRAIN`, `HASH`. Вида `ASSEMBLE` не
 * существует: собрать подборку за пользователя на сервере нечем (FR-085,
 * research.md Т-20, ADR-0009).
 *
 * Что воркер делает и чего не делает:
 *
 * - **берёт** задание из очереди и ведёт его через состояния
 *   `CREATING → WORKING → DONE` или `ERROR`;
 * - **не выводит** результат из факта существования файла: пропуск задания с
 *   тем же хешем параметров разрешён **только** если артефакт зарегистрирован в
 *   состоянии `READY` (Р-10, контракт очереди § 3.3);
 * - при ненулевом коде внешней программы переводит задание в `ERROR` с
 *   читаемым текстом, а не в `DONE` (constitution IV.2, SC-005);
 * - при своём прерывании возвращает задание в очередь **с сохранённым
 *   прогрессом**.
 *
 * Параллелизм ограничен двумя числами развёртывания: сколько заданий
 * одновременно и сколько из них могут использовать видеокарту (Р-13,
 * контракт очереди § 4).
 *
 * @property queue очередь заданий
 * @property handlers исполнители по видам заданий
 * @property artifactRegistry реестр артефактов: по нему проверяется готовность
 *   результата при пропуске задания
 * @property db доступ к базе для служебных отметок
 * @property concurrency сколько заданий выполняется одновременно
 * @property gpuConcurrency сколько заданий могут использовать видеокарту
 * @property pollInterval как часто воркер смотрит в очередь
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class AdminJobWorker(
    private val queue: JobQueue,
    private val handlers: Map<JobKind, JobHandler>,
    private val artifactRegistry: ArtifactRegistry,
    private val db: Db,
    private val concurrency: Int = 2,
    private val gpuConcurrency: Int = 1,
    private val pollInterval: Duration = Duration.ofSeconds(2),
) {
    private val running = AtomicBoolean(false)
    private var executor: java.util.concurrent.ExecutorService? = null

    /** Работает ли воркер в данный момент. */
    val isRunning: Boolean
        get() = running.get()

    /**
     * Запускает воркер.
     *
     * Повторный запуск ничего не делает: два воркера в одном процессе брали бы
     * задания наперегонки и удваивали бы нагрузку без выигрыша.
     *
     * @throws IllegalStateException если число слотов неположительно или больше
     *   числа слотов видеокарты
     */
    fun start() {
        require(concurrency > 0) { "Число параллельных заданий неположительно: $concurrency" }
        require(gpuConcurrency > 0) { "Число заданий на видеокарте неположительно: $gpuConcurrency" }
        require(gpuConcurrency <= concurrency) {
            "Заданий на видеокарте ($gpuConcurrency) больше, чем всего слотов " +
                "($concurrency): видеокарта одна, но лишний слот ей не поможет"
        }
        if (!running.compareAndSet(false, true)) {
            return
        }
        val pool =
            Executors.newFixedThreadPool(concurrency) { runnable ->
                Thread(runnable, "syp-job-worker").apply { isDaemon = true }
            }
        executor = pool
        repeat(concurrency) { pool.submit { loop() } }
    }

    /**
     * Останавливает воркер.
     *
     * Задания в работе не бросаются: они возвращаются в очередь с сохранённым
     * прогрессом, и следующий запуск продолжит их с того места.
     *
     * @param waitSeconds сколько ждать завершения текущих заданий
     */
    fun stop(waitSeconds: Long = 30) {
        if (!running.compareAndSet(true, false)) {
            return
        }
        executor?.let { pool ->
            pool.shutdown()
            if (!pool.awaitTermination(waitSeconds, TimeUnit.SECONDS)) {
                pool.shutdownNow()
            }
        }
        executor = null
    }

    /**
     * Выполняет одно задание, если очередь не пуста.
     *
     * Метод нужен и для автоматической работы воркера, и для проверки: тест
     * вызывает его напрямую и получает результат без потоков и ожидания.
     *
     * @return `true`, если задание было взято и доведено до конца
     * @throws IllegalStateException если для взятого вида нет исполнителя
     */
    fun runOnce(): Boolean {
        val job = queue.claim(JobKinds.forGpuSlots(gpuConcurrency)) ?: return false
        return runJob(job)
    }

    /**
     * Выполняет уже взятое задание.
     *
     * @param job задание в состоянии [JobState.CREATING]
     * @return `true`, если задание доведено до состояния `DONE`
     */
    fun runJob(job: Job): Boolean {
        val handler =
            handlers[job.kind]
                ?: run {
                    // Вид без исполнителя — дефект развёртывания, а не сбой работы.
                    // Задание уходит в ошибку с текстом, а не висит в очереди.
                    queue.fail(
                        job.id,
                        "Для вида задания ${job.kind.name} не зарегистрирован исполнитель. " +
                            "Это дефект развёртывания, а не сбой работы",
                    )
                    return false
                }

        return try {
            queue.startWork(job.id)
            val result = handler.execute(job) { progress -> queue.reportProgress(job.id, progress) }
            reportFinalTotal(job.id, result.progressTotal)
            result.artifactId?.let { artifactId ->
                markArtifactsReady(artifactId, job.id)
            }
            queue.complete(job.id)
            true
        } catch (failure: ExternalProgramFailed) {
            // Ненулевой код завершения: ошибка задания с текстом, а не «готово».
            queue.fail(job.id, failure.message ?: failure.programName)
            false
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            queue.requeue(job.id, "воркер остановлен")
            throw failure
        } catch (failure: Throwable) {
            queue.fail(job.id, failure.message ?: failure::class.simpleName ?: "неизвестная ошибка")
            false
        }
    }

    /**
     * Дописывает общий объём работы, о котором задание сообщило в конце.
     *
     * Объём известен по завершении работы, и без него задание нельзя пометить
     * завершённым: ограничение базы `job_done_total_set` требует ненулевого
     * `progress_total` в состоянии `DONE`.
     *
     * Объём дописывается к **текущему** прогрессу задания, а не к прогрессу
     * на момент взятия. Иначе уже показанный оператору счётчик уехал бы назад
     * — прогресс монотонен (FR-003), и задание, отработавшее полностью,
     * упало бы в ошибку с текстом «прогресс уменьшился».
     *
     * @param jobId идентификатор задания
     * @param progressTotal общий объём работы по завершении
     * @throws ru.svoemesto.syp.core.db.DbException если задание не найдено
     */
    private fun reportFinalTotal(
        jobId: Long,
        progressTotal: Long,
    ) {
        if (progressTotal <= 0) {
            return
        }
        val current =
            queue.find(jobId)
                ?: throw ru.svoemesto.syp.core.db
                    .DbException("Задание $jobId не найдено при записи общего объёма")
        if (current.progress.total == progressTotal) {
            return
        }
        queue.reportProgress(jobId, current.progress.copy(total = progressTotal))
    }

    /**
     * Проверяет, можно ли пропустить задание как уже выполненное.
     *
     * Проверяется **регистрация результата в состоянии `READY`**, а не
     * существование файла: «файл есть» не означает «работа выполнена»
     * (Р-10, контракт очереди § 3.3).
     *
     * @param kind вид задания
     * @param paramsHash хеш параметров
     * @return `true`, если результат зарегистрирован как готовый
     */
    fun isAlreadyDone(
        kind: JobKind,
        paramsHash: String,
    ): Boolean = queue.hasCompletedWithReadyArtifact(kind, paramsHash)

    /** Основной цикл воркера. */
    private fun loop() {
        while (running.get()) {
            val worked =
                runCatching { runOnce() }.getOrElse { failure ->
                    Thread.sleep(pollInterval.toMillis())
                    if (failure is InterruptedException) throw failure
                    false
                }
            if (!worked) {
                Thread.sleep(pollInterval.toMillis())
            }
        }
        // Воркер остановлен: задания в работе возвращаются в очередь.
        returnWorkingJobs()
    }

    /** Помечает артефакты задания как готовые к выдаче. */
    private fun markArtifactsReady(
        artifactId: Long,
        jobId: Long,
    ) {
        val artifact = artifactRegistry.find(artifactId) ?: return
        if (artifact.jobId != null && artifact.jobId != jobId) {
            // Артефакт принадлежит другому заданию: подменять регистрацию
            // молча нельзя, это признак расхождения в коде.
            throw IllegalStateException(
                "Артефакт $artifactId принадлежит заданию ${artifact.jobId}, " +
                    "а помечается готовым по заданию $jobId",
            )
        }
    }

    /** Возвращает задания, оставшиеся в работе, в очередь с прогрессом. */
    private fun returnWorkingJobs() {
        db.use { connection ->
            connection
                .prepareStatement(
                    """
                    UPDATE tbl_jobs
                       SET state = 'WAITING',
                           started_at = NULL,
                           progress_note = COALESCE(progress_note, '') ||
                               ' — воркер остановлен, задание вернулось в очередь с сохранённым прогрессом'
                     WHERE state IN ('CREATING', 'WORKING')
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeUpdate()
                }
        }
    }
}
