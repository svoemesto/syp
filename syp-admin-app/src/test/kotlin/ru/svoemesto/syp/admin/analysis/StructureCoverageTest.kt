package ru.svoemesto.syp.admin.analysis

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.SerialSettingsStore
import ru.svoemesto.syp.admin.catalog.SerialStore
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.catalog.TestDatabase
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.images.PreviewSheet
import ru.svoemesto.syp.core.media.ExternalProgram
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки покрытия серии и числа листов превью (задача T055).
 *
 * Требование задачи — на `GOT.S01E01`: сцены покрывают все 88 643 кадра без
 * разрывов и перекрытий, число листов равно 347, первая граница — кадр 0,
 * последний участок заканчивается кадром 88 642.
 *
 * Проверка идёт в двух видах:
 *
 * 1. **на уменьшенной серии** — всегда, на подставной внешней программе. Она
 *    ловит сам дефект: дыру в покрытии, перекрытие соседних участков или
 *    неверное число листов;
 * 2. **на настоящем файле** — только если он доступен и явно разрешён
 *    переменной `SYP_TEST_SOURCE`. Файл серии лежит на архиве 5,6 ГБ, и его
 *    прогон занимает минуты; молча пропустить такую проверку нельзя, поэтому
 *    пропуск виден в отчёте и не выдаётся за выполненную.
 *
 * Дыра в покрытии на экране выглядит нормально — просто часть серии не
 * показана, — и заметить её можно только сравнением с числом кадров (FR-011).
 * Именно это сравнение и делает проверка.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureCoverageTest {
    private lateinit var db: Db
    private lateinit var seriesStore: SeriesStore
    private lateinit var structure: StructureService
    private lateinit var workRoot: java.nio.file.Path

    private companion object {
        /** Число кадров первой серии архива. */
        const val S1E1_FRAMES: Int = 88_643

        /** Число листов превью на 88 643 кадра при 256 кадрах на лист. */
        const val S1E1_SHEETS: Int = 347

        /** Имя переменной окружения с путём к настоящему файлу серии. */
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
        seriesStore = SeriesStore(db)
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
    fun `сцены покрывают серию без разрывов и перекрытий`() {
        val frameCount = 600
        val series = newSeries(frameCount)
        val run = beginRun(series)
        // Границы сцен обязаны быть подмножеством границ планов: порог сцены
        // не ниже порога плана, поэтому любая граница сцены — граница плана
        // (ADR-0005). Проверка этого правила живёт в самом DetectionResult.
        val shotBoundaries = (1 until frameCount / 60).map { it * 60 }
        val sceneBoundaries = shotBoundaries.filter { it in listOf(120, 240, 360, 480, 540) }

        val (scenes, shots) =
            structure.applyDetection(
                runId = run,
                seriesId = series.id!!,
                detection =
                    DetectionResult(
                        sceneBoundaries = sceneBoundaries,
                        shotBoundaries = shotBoundaries,
                        scores = frameCount,
                    ),
            )
        assertTrue(scenes > 0, "структура обязана содержать сцены")
        assertTrue(shots >= scenes, "планов не может быть меньше, чем сцен")

        val storedScenes = structure.listScenes(series.id)
        assertEquals(0, storedScenes.first().firstFrame, "структура обязана начинаться с первого кадра серии")
        assertEquals(
            frameCount - 1,
            storedScenes.last().lastFrame,
            "структура обязана заканчиваться последним кадром серии",
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
            "последний лист заканчивается последним кадром серии",
        )
        assertEquals(
            S1E1_FRAMES,
            PreviewSheet.all(1, S1E1_FRAMES).sumOf { it.frameNumbersCount },
            "листы обязаны покрывать кадры серии без пропусков и наложений",
        )
    }

    @Test
    fun `настоящая серия разбирается без дыр в покрытии`() {
        // Пропуск объявляется штатным средствомJUnit, а не тихим `return`:
        // прогон проверок обязан показать этот случай как пропущенный, иначе
        // «разбор настоящей серии выполнен» окажется неправдой.
        val source = System.getenv(ENV_SOURCE)
        org.junit.jupiter.api.Assumptions.assumeTrue(!source.isNullOrBlank()) {
            "переменная $ENV_SOURCE не задана: разбор настоящей серии (${S1E1_FRAMES} кадра) " +
                "не выполнялся. Проверка покрытия на уменьшенной серии выполнена"
        }
        val probe =
            ru.svoemesto.syp.admin.catalog
                .SourceProbe(ExternalProgram(), "ffprobe")
        val detected =
            probe.probe(
                java.nio.file.Path
                    .of(source),
            )
        val series = newSeriesAt(detected.frameCount, source, detected.byteSize)
        val detector = SceneDetector(ExternalProgram(), System.getenv("SYP_TEST_FFMPEG") ?: "ffmpeg")
        val settings = SerialSettingsStore(db).read(series.serialId)
        val detection =
            detector.detect(
                series = series,
                sceneThreshold = settings.number(ru.svoemesto.syp.admin.catalog.SerialSetting.SCENE_THRESHOLD),
                shotThreshold = settings.number(ru.svoemesto.syp.admin.catalog.SerialSetting.SHOT_THRESHOLD),
            )
        val run = beginRun(series)
        structure.applyDetection(run, series.id!!, detection)

        val scenes = structure.listScenes(series.id!!)
        assertEquals(0, scenes.first().firstFrame, "первая граница структуры — кадр 0")
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
     * Заводит серию с указанным числом кадров на вымышленном пути.
     *
     * @param frameCount число кадров
     * @return записанная серия
     */
    private fun newSeries(frameCount: Int): Series = newSeriesAt(frameCount, "/srv/got/S1E1-${System.nanoTime()}.mkv", 1000)

    /**
     * Заводит серию по указанному пути и размеру файла.
     *
     * @param frameCount число кадров
     * @param sourcePath путь к файлу серии
     * @param byteSize размер файла в байтах
     * @return записанная серия
     */
    private fun newSeriesAt(
        frameCount: Int,
        sourcePath: String,
        byteSize: Long,
    ): Series {
        val serial = SerialStore(db).create("Покрытие ${System.nanoTime()}", "/srv/got")
        return seriesStore.insert(
            Series(
                serialId = serial.id!!,
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
     * @param series серия
     * @return идентификатор прогона
     */
    private fun beginRun(series: Series): Long {
        val run =
            AnalysisRunStore(db).begin(
                AnalysisRun(
                    seriesId = series.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = DetectionResult.ALGORITHM_VERSION,
                    paramsHash = "c".repeat(64),
                ),
            )
        return requireNotNull(run.id) { "прогон заведён без идентификатора" }
    }
}
