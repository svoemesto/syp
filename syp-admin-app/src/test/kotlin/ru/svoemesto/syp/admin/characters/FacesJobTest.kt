package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.analysis.AnalysisKind
import ru.svoemesto.syp.admin.analysis.AnalysisRunStore
import ru.svoemesto.syp.admin.analysis.AnalysisState
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.admin.jobs.AdminJobWorker
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobState
import ru.svoemesto.syp.core.jobs.JobSubject
import ru.svoemesto.syp.core.jobs.ParamsHash
import ru.svoemesto.syp.core.media.FrameChannel
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
 * Проверки задания `FACES` (задача T062).
 *
 * Проверяется то, что видно со стороны очереди и базы:
 *
 * 1. успешный проход даёт задание в `DONE` с прогрессом, равным числу кадров
 *    эпизода, и записывает прогон вида `FACES`;
 * 2. **прогон называет заглушку детектора** — результат заглушки никогда не
 *    должен быть выдан за результат настоящего детектора;
 * 3. ненулевой код декодера даёт `ERROR` с текстом, а не `DONE` (SC-005,
 *    constitution IV.2);
 * 4. прогон неудачи остаётся в базе с текстом ошибки.
 *
 * Проход идёт на подставном декодере: сбой должен быть воспроизводим.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FacesJobTest {
    private lateinit var db: Db
    private lateinit var queue: JobQueue
    private lateinit var videofileStore: VideofileStore
    private lateinit var runStore: AnalysisRunStore
    private lateinit var registry: ArtifactRegistry
    private lateinit var storageRoot: Path
    private lateinit var decoderRoot: Path

    private companion object {
        /** Сколько раз воркер пытается взять именно наше задание. */
        const val CLAIM_ATTEMPTS: Int = 10
    }

    /**
     * Поднимает доступ к базе и хранилища домена.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        queue = JobQueue(db)
        videofileStore = VideofileStore(db)
        runStore = AnalysisRunStore(db)
        storageRoot = Files.createTempDirectory("syp-faces-storage")
        registry = ArtifactRegistry(db, FileSystemStorage(storageRoot))
        decoderRoot = Files.createTempDirectory("syp-faces-decoder")
    }

    @Test
    fun `успешный проход даёт задание в DONE и прогон вида FACES`() {
        val videofile = newVideofile(frames = 6)
        val decoder = FakeDecoder.write(decoderRoot.resolve("ok"), frames = 6)
        val worker = workerFor(decoder)
        val job = enqueueAndClaim(videofile)

        val done = worker.runJob(job)
        val stored = assertNotNull(queue.find(job.id), "задание обязано остаться в базе")

        assertTrue(done, "проход обязан закончиться успешно. Текст задания: ${stored.errorText}")
        assertEquals(JobState.DONE, stored.state, "успешный проход даёт DONE")
        assertEquals(
            6L,
            stored.progress.total,
            "общий объём работы равен числу кадров эпизода: адаптивного шага нет (ADR-0002)",
        )
        assertEquals(6L, stored.progress.done, "прогресс обязан дойти до конца эпизода")

        val run =
            assertNotNull(
                runStore.latest(videofile.id!!, AnalysisKind.FACES),
                "прогон вида FACES обязан остаться в базе",
            )
        assertEquals(AnalysisState.DONE, run.state, "прогон успешного прохода — DONE")
        assertEquals(
            StubFaceDetector.KEY,
            run.algorithmVersion,
            "прогон обязан называть детектор, которым он получен: заглушку нельзя спутать " +
                "с настоящим детектором",
        )
    }

    @Test
    fun `текст результата называет заглушку детектора`() {
        val videofile = newVideofile(frames = 3)
        val decoder = FakeDecoder.write(decoderRoot.resolve("stub"), frames = 3)
        val worker = workerFor(decoder)
        val job = enqueueAndClaim(videofile)

        worker.runJob(job)
        val stored = assertNotNull(queue.find(job.id))

        assertTrue(
            stored.progress.note.contains("ЗАГЛУШКА"),
            "текст задания обязан называть заглушку: результат без детектора нельзя " +
                "показывать как результат детекции. Получено: ${stored.progress.note}",
        )
    }

    @Test
    fun `эпизод без разбора даёт отказ, а не пустой результат`() {
        // Очередь упорядочена только по времени создания, поэтому FACES может
        // встать раньше ANALYZE. Без стража лица тогда ищутся, привязываться им
        // не к чему, и задание выглядит успешным на пустом месте.
        val videofile = newVideofile(frames = 4, withStructure = false)
        val decoder = FakeDecoder.write(decoderRoot.resolve("nostructure"), frames = 4)
        val worker = workerFor(decoder)
        val job = enqueueAndClaim(videofile)

        val done = worker.runJob(job)
        val stored = assertNotNull(queue.find(job.id))
        val errorText = stored.errorText ?: ""

        assertFalse(done, "эпизод без разбора не может дать задание в успех")
        assertEquals(JobState.ERROR, stored.state, "эпизод без разбора — это отказ, а не результат")
        assertTrue(
            errorText.contains("не разобран") && errorText.contains("Разобрать заново"),
            "отказ обязан называть причину и что делать оператору, а не «внутренняя ошибка». " +
                "Получено: $errorText",
        )
    }

    @Test
    fun `ненулевой код декодера переводит задание в ошибку с текстом`() {
        val videofile = newVideofile(frames = 4)
        val decoder = FakeDecoder.write(decoderRoot.resolve("broken"), frames = 4, exit = 1)
        val worker = workerFor(decoder)
        val job = enqueueAndClaim(videofile)

        val done = worker.runJob(job)
        val stored = assertNotNull(queue.find(job.id))
        val errorText = stored.errorText ?: ""

        assertFalse(done, "упавший декодер не может дать задание в состоянии DONE")
        assertEquals(JobState.ERROR, stored.state, "ненулевой код декодера ведёт в ERROR")
        assertTrue(
            errorText.contains("подставной декодер"),
            "текст ошибки обязан содержать вывод декодера: у потока кадров отдельный " +
                "поток ошибок, и потерять его нельзя (FR-092). Получено: $errorText",
        )
        val run =
            assertNotNull(
                runStore.latest(videofile.id!!, AnalysisKind.FACES),
                "прогон неудачи обязан остаться в базе",
            )
        assertEquals(AnalysisState.ERROR, run.state, "прогон неудачи не остаётся в состоянии работы")
        assertTrue(!run.errorText.isNullOrBlank(), "прогон в состоянии ERROR обязан нести текст ошибки")
    }

    /**
     * Собирает воркер с одним исполнителем `FACES`.
     *
     * @param decoder путь к подставному декодеру
     * @return воркер
     */
    private fun workerFor(decoder: Path): AdminJobWorker {
        val detector: FaceDetector = StubFaceDetector()
        val job =
            FacesJob(
                videofileStore = videofileStore,
                runStore = runStore,
                scan = FaceScan(FrameChannel(decoder.toString()), detector),
                detectorKey = detector.key,
                structure =
                    ru.svoemesto.syp.admin.analysis.StructureService(
                        db = db,
                        runStore = runStore,
                        boundaryStore =
                            ru.svoemesto.syp.admin.analysis
                                .RawBoundaryStore(db),
                    ),
            )
        return AdminJobWorker(
            queue = queue,
            handlers = mapOf<JobKind, JobHandler>(JobKind.FACES to job),
            artifactRegistry = registry,
            db = db,
            concurrency = 1,
            // Задание FACES требует видеокарты, поэтому слот под неё должен
            // быть: иначе воркер не взял бы задание вида FACES вовсе.
            gpuConcurrency = 1,
        )
    }

    /**
     * Заводит эпизод с указанным числом кадров.
     *
     * @param frames число кадров эпизода
     * @return записанный эпизод
     */
    private fun newVideofile(frames: Int): Videofile = newVideofile(frames, withStructure = true)

    /**
     * Заводит эпизод, у которого разбор либо есть, либо отсутствует.
     *
     * @param frames число кадров
     * @param withStructure заводить ли один живой план
     * @return записанный эпизод
     */
    private fun newVideofile(
        frames: Int,
        withStructure: Boolean,
    ): Videofile {
        val project = ProjectStore(db).create("Лица ${System.nanoTime()}", "/srv/got")
        val videofile =
            videofileStore.insert(
                Videofile(
                    projectId = project.id!!,
                    ordinal = 0,
                    name = "S01E01",
                    sourcePath = "/srv/got/S01E01-${System.nanoTime()}.mkv",
                    byteSize = 1000,
                    fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
                    frameCount = frames,
                    timeBaseNum = 1001,
                    timeBaseDen = 24_000,
                    width = 4,
                    height = 2,
                    durationNum = frames.toLong() * 1001,
                    durationDen = 24_000,
                    videoCodec = "h264",
                    videoProfile = "High",
                    pixelFormat = "yuv420p",
                    keyframeMap = KeyframeMap.build(frames, listOf(0)),
                ),
            )
        if (withStructure) {
            // Один живой план на весь эпизод: лицам нужно к чему привязываться,
            // иначе страж свежести разбора откажет — и правильно.
            db.use { connection ->
                connection
                    .prepareStatement(
                        "INSERT INTO tbl_shots (id_videofile, first_frame, last_frame, size, size_origin, origin) " +
                            "VALUES (?, 0, ?, 'NONE', 'AUTO', 'AUTO')",
                    ).use { statement ->
                        statement.setLong(1, videofile.id!!)
                        statement.setInt(2, (frames - 1).coerceAtLeast(0))
                        statement.executeUpdate()
                    }
            }
        }
        return videofile
    }

    /**
     * Ставит задание в очередь и берёт его в работу.
     *
     * @param videofile эпизод
     * @return взятое задание
     */
    private fun enqueueAndClaim(videofile: Videofile): Job {
        val jobId =
            queue.enqueue(
                kind = JobKind.FACES,
                subject = JobSubject.videofile(videofile.id!!),
                paramsJson = "{\"detector\":\"${StubFaceDetector.KEY}\"}",
                paramsHash = ParamsHash.of(StubFaceDetector.KEY, 4, 2),
                algorithmVersion = StubFaceDetector.KEY,
            )
        // Очередь общая для набора проверок: чужое задание возвращается на
        // место, а своё забирается.
        repeat(CLAIM_ATTEMPTS) {
            val claimed = queue.claim(listOf(JobKind.FACES)) ?: return@repeat
            if (claimed.id == jobId) {
                return claimed
            }
            queue.requeue(claimed.id, "возвращено проверкой лица")
        }
        error("задание $jobId поставлено, но воркер его не взял за $CLAIM_ATTEMPTS попыток")
    }
}
