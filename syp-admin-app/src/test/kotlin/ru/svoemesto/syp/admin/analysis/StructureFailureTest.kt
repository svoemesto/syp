package ru.svoemesto.syp.admin.analysis

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.jobs.AdminJobWorker
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobProgress
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobState
import ru.svoemesto.syp.core.jobs.JobSubject
import ru.svoemesto.syp.core.jobs.ParamsHash
import ru.svoemesto.syp.core.media.ExternalProgram
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.FileSystemStorage
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Проверки сбоя и прерывания анализа структуры (задача T056).
 *
 * Закрываются три требования:
 *
 * 1. **Оборванная внешняя программа переводит задание в `ERROR` с текстом.**
 *    Не в `DONE`: в старом проекте код возврата не проверял ни один из
 *    двенадцати вызовов ffmpeg, и упавший декодер давал «готово» (SC-005,
 *    constitution IV.2).
 * 2. **Листы превью не помечаются готовыми.** Незавершённый файл не считается
 *    готовым артефактом (FR-091).
 * 3. **Повторный запуск продолжает работу, а не начинает с нуля молча.**
 *    Уже готовые листы не собираются и не регистрируются заново, а
 *    сохранённый прогресс не откатывается назад (FR-003, research.md Т-15).
 *
 * Сбой устраивается подставной внешней программой, а не настоящим `ffmpeg`:
 * сбой должен быть воспроизводим, иначе проверка однажды пройдёт на удаче и
 * больше никогда не сработает.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureFailureTest {
    private lateinit var db: Db
    private lateinit var queue: JobQueue
    private lateinit var episodeStore: EpisodeStore
    private lateinit var settingsStore: MovieSettingsStore
    private lateinit var runStore: AnalysisRunStore
    private lateinit var boundaryStore: RawBoundaryStore
    private lateinit var frameStore: FrameSignificanceStore
    private lateinit var structure: StructureService
    private lateinit var staleness: Staleness
    private lateinit var registry: ArtifactRegistry
    private lateinit var storageRoot: Path
    private lateinit var workRoot: Path

    private companion object {
        /** Сколько раз воркер пытается взять именно наше задание. */
        const val CLAIM_ATTEMPTS: Int = 10
    }

    /**
     * Поднимает доступ к базе и хранилища анализа.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        queue = JobQueue(db)
        episodeStore = EpisodeStore(db)
        settingsStore = MovieSettingsStore(db)
        runStore = AnalysisRunStore(db)
        boundaryStore = RawBoundaryStore(db)
        frameStore = FrameSignificanceStore(db)
        structure = StructureService(db, runStore, boundaryStore)
        staleness = Staleness(db, runStore)
        storageRoot = Files.createTempDirectory("syp-structure-failure-storage")
        workRoot = Files.createTempDirectory("syp-structure-failure-work")
        registry = ArtifactRegistry(db, FileSystemStorage(storageRoot))
    }

    /**
     * Заводит эпизод с указанным числом кадров.
     *
     * @param frameCount число кадров эпизода
     * @return записанный эпизод
     */
    private fun newEpisode(frameCount: Int): Episode {
        val movie = MovieStore(db).create("Сбой ${System.nanoTime()}", "/srv/got")
        return episodeStore.insert(
            Episode(
                movieId = movie.id!!,
                ordinal = 0,
                name = "S1E1",
                sourcePath = "/srv/got/S1E1-${System.nanoTime()}.mkv",
                byteSize = 1000,
                fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                frameCount = frameCount,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                width = 1920,
                height = 1080,
                durationNum = frameCount.toLong() * 1001,
                durationDen = 24_000,
                videoCodec = "h264",
                videoProfile = "High",
                pixelFormat = "yuv420p",
                keyframeMap = KeyframeMap.build(frameCount, listOf(0)),
            ),
        )
    }

    /**
     * Собирает исполнителя задания `ANALYZE` над подставной программой.
     *
     * @param script путь к подставной программе
     * @return воркер с одним зарегистрированным исполнителем
     */
    private fun workerFor(script: Path): AdminJobWorker {
        val program = ExternalProgram()
        val job =
            StructureJob(
                episodeStore = episodeStore,
                runStore = runStore,
                structure = structure,
                frames = frameStore,
                detector = SceneDetector(program, script.toString()),
                program = program,
                ffmpegPath = script.toString(),
                settingsStore = settingsStore,
                artifactRegistry = registry,
                staleness = staleness,
                storage = FileSystemStorage(storageRoot),
                workRoot = workRoot,
            )
        return AdminJobWorker(
            queue = queue,
            handlers = mapOf<JobKind, JobHandler>(JobKind.ANALYZE to job),
            artifactRegistry = registry,
            db = db,
            concurrency = 1,
            gpuConcurrency = 0,
        )
    }

    /**
     * Ставит задание анализа в очередь и берёт его в работу.
     *
     * @param episode эпизод
     * @return взятое задание
     */
    private fun enqueueAndClaim(episode: Episode): Job {
        val jobId =
            queue.enqueue(
                kind = JobKind.ANALYZE,
                subject = JobSubject.episode(episode.id!!),
                paramsJson = "{\"sceneThreshold\":8,\"shotThreshold\":4}",
                paramsHash = ParamsHash.of(DetectionResult.ALGORITHM_VERSION, 8.0, 4.0),
                algorithmVersion = DetectionResult.ALGORITHM_VERSION,
            )
        // Очередь общая для набора проверок, и в ней может лежать задание,
        // оставшееся от другой проверки. Такое возвращается на место: тест не
        // должен прибирать за соседом, но и брать чужое задание нельзя.
        repeat(CLAIM_ATTEMPTS) {
            val claimed = queue.claim(listOf(JobKind.ANALYZE)) ?: return@repeat
            if (claimed.id == jobId) {
                return claimed
            }
            queue.requeue(claimed.id, "возвращено проверкой структуры")
        }
        error("задание $jobId поставлено, но воркер его не взял за $CLAIM_ATTEMPTS попыток")
    }

    @Test
    fun `оборванная программа переводит задание в ошибку с текстом, а листы не готовы`() {
        val episode = newEpisode(600)
        val script = FakeFfmpeg.write(workRoot.resolve("broken"), sheetExit = 1)
        val worker = workerFor(script)
        val job = enqueueAndClaim(episode)

        val done = worker.runJob(job)
        val stored = assertNotNull(queue.find(job.id), "задание обязано остаться в базе")
        val errorText = stored.errorText ?: ""

        assertFalse(done, "оборванная программа не может дать задание в состоянии DONE")
        assertEquals(JobState.ERROR, stored.state, "ненулевой код завершения ведёт в ERROR, а не в DONE")
        assertTrue(
            errorText.isNotBlank(),
            "задание в ERROR обязано нести текст ошибки: «процесс упал» ошибкой не является (FR-092)",
        )
        assertTrue(
            errorText.contains("ffmpeg"),
            "текст ошибки должен называть программу, иначе оператор не поймёт, что именно упало. " +
                "Получено: $errorText",
        )

        assertEquals(
            0,
            countReadySheets(episode.id!!),
            "незавершённый лист превью не может быть помечен готовым (FR-091)",
        )
        val run =
            assertNotNull(
                runStore.latest(episode.id, AnalysisKind.STRUCTURE),
                "прогон обязан остаться в базе: по нему видно, чем закончилась попытка",
            )
        assertEquals(
            AnalysisState.ERROR,
            run.state,
            "прогон неудачи не должен остаться в состоянии работы навсегда",
        )
        assertTrue(
            !run.errorText.isNullOrBlank(),
            "прогон в состоянии ERROR обязан нести текст ошибки",
        )
    }

    @Test
    fun `прерванное задание возвращается в очередь с сохранённым прогрессом`() {
        val episode = newEpisode(600)
        val job = enqueueAndClaim(episode)
        queue.startWork(job.id)
        queue.reportProgress(job.id, JobProgress.of(300, 1200, "детекция границ: кадр 300 из 600"))

        queue.requeue(job.id, "проверка прерывания")
        val returned = assertNotNull(queue.find(job.id), "задание обязано остаться в очереди")

        assertEquals(JobState.WAITING, returned.state, "прерванное задание возвращается в очередь")
        assertEquals(300L, returned.progress.done, "прогресс не должен начинаться заново после прерывания")
        assertEquals(1200L, returned.progress.total, "общий объём работы сохраняется вместе с прогрессом")
        assertTrue(
            returned.progress.note.contains("прервано"),
            "в пояснении прогресса должна быть видна причина возврата в очередь. " +
                "Получено: ${returned.progress.note}",
        )
    }

    @Test
    fun `повторный запуск не собирает заново готовые листы`() {
        val episode = newEpisode(600)
        // 600 кадров по 256 на лист — это три листа: два полных и неполный
        // последний, без него конец эпизода не был бы виден.
        val script = FakeFfmpeg.write(workRoot.resolve("resume"), sheetCount = 3)
        val worker = workerFor(script)

        val firstJob = enqueueAndClaim(episode)
        val firstDone = worker.runJob(firstJob)
        assertTrue(
            firstDone,
            "первый запуск обязан закончиться успешно. Текст задания: " +
                "${assertNotNull(queue.find(firstJob.id)).errorText}",
        )
        assertEquals(3, countReadySheets(episode.id!!), "эпизод из 600 кадров даёт три листа превью")

        val secondJob = enqueueAndClaim(episode)
        assertTrue(worker.runJob(secondJob), "повторный запуск обязан закончиться успешно")
        assertEquals(3, countReadySheets(episode.id!!), "число готовых листов не должно расти")
        assertEquals(
            0,
            registry.listForJob(secondJob.id).size,
            "повторный запуск не должен заново регистрировать уже готовые листы: " +
                "работа продолжается, а не начинается с нуля",
        )
    }

    @Test
    fun `счётчик прогресса не уезжает назад`() {
        val seen = mutableListOf<Long>()
        val reporter =
            MonotonicProgress(
                sink = { progress -> seen.add(progress.done) },
                saved = JobProgress.of(500, 1000, "сохранённый прогресс"),
                total = 1000,
            )
        reporter.report(10, "детекция границ: кадр 10 из 1000")
        reporter.report(400, "детекция границ: кадр 400 из 1000")
        reporter.report(700, "детекция границ: кадр 700 из 1000")
        reporter.report(1000, "детекция границ: кадр 1000 из 1000")

        assertEquals(
            listOf(700L, 1000L),
            seen,
            "показанные значения ниже сохранённого прогресса подавляются: оператор увидел бы, " +
                "что работа идёт наоборот",
        )
        assertTrue(
            seen.all { it >= 500L },
            "ни одно показанное значение не может быть меньше уже сохранённого",
        )
    }

    /**
     * Считает готовые листы превью эпизода.
     *
     * @param episodeId эпизод
     * @return число листов в состоянии `READY`
     */
    private fun countReadySheets(episodeId: Long): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM tbl_artifacts WHERE kind = 'PREVIEW_SHEET' " +
                "AND state = 'READY' AND object_key LIKE ?",
            { it.int("total") },
            "episode/$episodeId/preview-sheets/%",
        ) ?: 0
}
