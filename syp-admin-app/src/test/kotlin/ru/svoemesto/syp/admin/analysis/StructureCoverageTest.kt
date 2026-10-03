package ru.svoemesto.syp.admin.analysis

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.ProjectSettingsStore
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.images.PreviewSheet
import ru.svoemesto.syp.core.media.ExternalProgram
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки покрытия эпизода и числа листов превью (задача T055).
 *
 * Требование задачи — на `GOT.S01E01`: сцены покрывают все 88 643 кадра без
 * разрывов и перекрытий, число листов равно 347, первая граница — кадр 0,
 * последний участок заканчивается кадром 88 642.
 *
 * Проверка идёт в двух видах:
 *
 * 1. **на уменьшенном эпизоде** — всегда, на подставной внешней программе. Она
 *    ловит сам дефект: дыру в покрытии, перекрытие соседних участков или
 *    неверное число листов;
 * 2. **на настоящем файле** — только если он доступен и явно разрешён
 *    переменного `SYP_TEST_SOURCE`. Файл эпизода лежит на архиве 5,6 ГБ, и его
 *    прогон занимает минуты; молча пропустить такую проверку нельзя, поэтому
 *    пропуск виден в отчёте и не выдаётся за выполненную.
 *
 * Дыра в покрытии на экране выглядит нормально — просто часть эпизода не
 * показана, — и заметить её можно только сравнением с числом кадров (FR-011).
 * Именно это сравнение и делает проверка.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureCoverageTest {
    private lateinit var db: Db
    private lateinit var videofileStore: VideofileStore
    private lateinit var structure: StructureService
    private lateinit var workRoot: java.nio.file.Path

    private companion object {
        /** Число кадров первого эпизода архива. */
        const val S1E1_FRAMES: Int = 88_643

        /** Число листов превью на 88 643 кадра при 256 кадрах на лист. */
        const val S1E1_SHEETS: Int = 347

        /** Имя переменной окружения с путём к настоящему файлу эпизода. */
        const val ENV_SOURCE: String = "SYP_TEST_SOURCE"
    }

    /**
     * Поднимает доступ к базе и хранилища анализа.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    @BeforeAll
    fun openDatabase() {
        db = TestDatabase.assumeDatabase()
        videofileStore = VideofileStore(db)
        structure =
            StructureService(
                db,
                AnalysisRunStore(db),
                RawBoundaryStore(db),
            )
        workRoot =
            java.nio.file.Files
                .createTempDirectory("syp-structure-coverage-work")
    }

    @Test
    fun `сцены покрывают эпизод без разрывов и перекрытий`() {
        val frameCount = 600
        val videofile = newVideofile(frameCount)
        val run = beginRun(videofile)
        // Границы сцен обязаны быть подмножеством границ планов: порог сцены
        // не ниже порога плана, поэтому любая граница сцены — граница плана
        // (ADR-0005). Проверка этого правила живёт в самом DetectionResult.
        val shotBoundaries = (1 until frameCount / 60).map { it * 60 }
        val sceneBoundaries = shotBoundaries.filter { it in listOf(120, 240, 360, 480, 540) }

        val (scenes, shots) =
            structure.applyDetection(
                runId = run,
                videofileId = videofile.id!!,
                detection =
                    DetectionResult(
                        sceneBoundaries = sceneBoundaries,
                        shotBoundaries = shotBoundaries,
                        scores = frameCount,
                    ),
            )
        assertTrue(scenes > 0, "структура обязана содержать сцены")
        assertTrue(shots >= scenes, "планов не может быть меньше, чем сцен")

        val storedScenes = structure.listScenes(videofile.id)
        assertEquals(0, storedScenes.first().firstFrame, "структура обязана начинаться с первого кадра эпизода")
        assertEquals(
            frameCount - 1,
            storedScenes.last().lastFrame,
            "структура обязана заканчиваться последним кадром эпизода",
        )
        storedScenes.forEachIndexed { index, scene ->
            val next = storedScenes.getOrNull(index + 1)
            if (next != null) {
                assertEquals(
                    scene.lastFrame + 1,
                    next.firstFrame,
                    "между сценами ${scene.id} и ${next.id} есть разрыв или перекрытие",
                )
            }
        }
    }

    @Test
    fun `число листов равно округлению числа кадров вверх`() {
        assertEquals(
            S1E1_SHEETS,
            PreviewSheet.sheetCount(S1E1_FRAMES),
            "на $S1E1_FRAMES кадрах при 256 кадрах на лист должно быть $S1E1_SHEETS листов",
        )
        val first = PreviewSheet.of(1, 0, S1E1_FRAMES)
        val last = PreviewSheet.of(1, S1E1_SHEETS - 1, S1E1_FRAMES)
        assertEquals(0, first.firstFrame, "первый лист начинается с кадра 0")
        assertEquals(
            S1E1_FRAMES - 1,
            last.lastFrame,
            "последний лист заканчивается последним кадром эпизода",
        )
        assertEquals(
            S1E1_FRAMES,
            PreviewSheet.all(1, S1E1_FRAMES).sumOf { it.frameNumbersCount },
            "листы обязаны покрывать кадры эпизода без пропусков и наложений",
        )
    }

    @Test
    fun `настоящий эпизод разбирается без дыр в покрытии`() {
        // Пропуск объявляется штатным средствомJUnit, а не тихим `return`:
        // прогон проверок обязан показать этот случай как пропущенный, иначе
        // «разбор настоящего эпизода выполнен» окажется неправдой.
        val source = System.getenv(ENV_SOURCE)
        org.junit.jupiter.api.Assumptions.assumeTrue(!source.isNullOrBlank()) {
            "переменная $ENV_SOURCE не задана: разбор настоящего эпизода (${S1E1_FRAMES} кадра) " +
                "не выполнялся. Проверка покрытия на уменьшенном эпизоде выполнена"
        }
        val probe =
            ru.svoemesto.syp.admin.catalog
                .SourceProbe(ExternalProgram(), "ffprobe")
        val detected =
            probe.probe(
                java.nio.file.Path
                    .of(source),
            )
        val videofile = newVideofileAt(detected.frameCount, source, detected.byteSize)
        val detector = SceneDetector(ExternalProgram(), System.getenv("SYP_TEST_FFMPEG") ?: "ffmpeg")
        val settings = ProjectSettingsStore(db).read(videofile.projectId)
        val detection =
            detector.detect(
                videofile = videofile,
                sceneThreshold = settings.number(ru.svoemesto.syp.admin.catalog.ProjectSetting.SCENE_THRESHOLD),
                shotThreshold = settings.number(ru.svoemesto.syp.admin.catalog.ProjectSetting.SHOT_THRESHOLD),
            )
        val run = beginRun(videofile)
        structure.applyDetection(run, videofile.id!!, detection)

        val scenes = structure.listScenes(videofile.id!!)
        assertEquals(0, scenes.first().firstFrame, "первый граница структуры — кадр 0")
        assertEquals(
            detected.frameCount - 1,
            scenes.last().lastFrame,
            "последний участок обязан заканчиваться кадром ${detected.frameCount - 1}",
        )
        scenes.forEachIndexed { index, scene ->
            val next = scenes.getOrNull(index + 1)
            if (next != null) {
                assertEquals(
                    scene.lastFrame + 1,
                    next.firstFrame,
                    "между сценами ${scene.id} и ${next.id} есть разрыв или перекрытие",
                )
            }
        }
    }

    /**
     * Заводит эпизод с указанным числом кадров на вымышленном пути.
     *
     * @param frameCount число кадров
     * @return записанный эпизод
     */
    private fun newVideofile(frameCount: Int): Videofile = newVideofileAt(frameCount, "/srv/got/S1E1-${System.nanoTime()}.mkv", 1000)

    /**
     * Заводит эпизод по указанному пути и размеру файла.
     *
     * @param frameCount число кадров
     * @param sourcePath путь к файлу эпизода
     * @param byteSize размер файла в байтах
     * @return записанный эпизод
     */
    private fun newVideofileAt(
        frameCount: Int,
        sourcePath: String,
        byteSize: Long,
    ): Videofile {
        val project = ProjectStore(db).create("Покрытие ${System.nanoTime()}", "/srv/got")
        return videofileStore.insert(
            Videofile(
                projectId = project.id!!,
                ordinal = 0,
                name =
                    java.nio.file.Path
                        .of(sourcePath)
                        .fileName
                        .toString(),
                sourcePath = sourcePath,
                byteSize = byteSize,
                fileMtime = java.time.OffsetDateTime.parse("2024-11-05T10:00:00Z"),
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
     * Заводит прогон анализа.
     *
     * @param videofile эпизод
     * @return идентификатор прогона
     */
    private fun beginRun(videofile: Videofile): Long {
        val run =
            AnalysisRunStore(db).begin(
                AnalysisRun(
                    videofileId = videofile.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = DetectionResult.ALGORITHM_VERSION,
                    paramsHash = "c".repeat(64),
                ),
            )
        return requireNotNull(run.id) { "прогон заведён без идентификатора" }
    }
}
