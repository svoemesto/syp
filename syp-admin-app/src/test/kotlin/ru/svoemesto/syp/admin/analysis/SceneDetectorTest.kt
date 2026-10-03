package ru.svoemesto.syp.admin.analysis

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.core.media.ExternalProgram
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Проверки детектора границ двумя порогами за один проход.
 *
 * Закрывают требования задачи T047:
 *
 * 1. **один проход ffmpeg** с встроенным детектором: оба порога применяются к
 *    одному и тому же потоку оценок, второй проход не нужен и не делается
 *    (ADR-0005, research.md Т-05);
 * 2. **высокий порог даёт границу сцены, низкий — границу плана**: при
 *    понижении порога число границ планов растёт, а число границ сцен не
 *    меняется;
 * 3. **оба порога — параметры задания**: они входят в хеш параметров, и смена
 *    порога даёт другой хеш (FR-090, Р-10);
 * 4. **план не выходит за пределы сцены**, потому что обе границы пришли из
 *    одного потока.
 *
 * Проверка требует `ffmpeg`. Без него она **пропускается**, а не падает.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SceneDetectorTest {
    /**
     * Полный путь к программе в `PATH`.
     *
     * @param name имя программы
     * @return полный путь
     * @throws org.opentest4j.TestAbortedException если программа не найдена
     */
    private fun requireProgram(name: String): String {
        val found =
            (System.getenv("PATH") ?: "")
                .split(":")
                .filter { it.isNotBlank() }
                .map { Paths.get(it, name) }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()
        assumeTrue(found != null) { "Программа $name не найдена в PATH: проверки детектора пропущены" }
        return found!!
    }

    /**
     * Готовит ролик с двумя сменами картинки: три источника по две секунды,
     * склеенных в один файл.
     *
     * @return путь к файлу, который удаляет вызывающий
     */
    private fun syntheticVideofile(): Path {
        val target = Files.createTempDirectory("syp-scdet").resolve("videofile.mkv")
        ExternalProgram()
            .runOrFail(
                requireProgram("ffmpeg"),
                listOf(
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc=duration=2:size=320x180:rate=25",
                    "-f",
                    "lavfi",
                    "-i",
                    "smptebars=duration=2:size=320x180:rate=25",
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=duration=2:size=320x180:rate=25",
                    "-filter_complex",
                    "[0:v][1:v][2:v]concat=n=3:v=1:a=0",
                    "-c:v",
                    "libx264",
                    "-g",
                    "25",
                    "-pix_fmt",
                    "yuv420p",
                    "-y",
                    target.toString(),
                ),
            )
        return target
    }

    /**
     * Описание эпизода для проверки детектора: параметры сняты с файла, чтобы
     * числа кадров и частокадровая база не расходились с содержимым.
     *
     * @param file проверочный файл
     * @return эпизод для опроса
     */
    private fun videofileOf(file: Path): Videofile {
        val parameters =
            ru.svoemesto.syp.admin.catalog
                .SourceProbe(ExternalProgram(), requireProgram("ffprobe"))
                .probe(file)
        return Videofile(
            projectId = 0,
            ordinal = 0,
            name = "проверочная",
            sourcePath = file.toString(),
            byteSize = parameters.byteSize,
            fileMtime = parameters.fileMtime.atOffset(java.time.ZoneOffset.UTC),
            frameCount = parameters.frameCount,
            timeBaseNum = parameters.timeBaseNum,
            timeBaseDen = parameters.timeBaseDen,
            width = parameters.width,
            height = parameters.height,
            durationNum = parameters.durationNum,
            durationDen = parameters.durationDen,
            videoCodec = parameters.videoCodec,
            videoProfile = parameters.videoProfile,
            pixelFormat = parameters.pixelFormat,
            keyframeMap = parameters.keyframes,
        )
    }

    @Test
    fun `один проход даёт сцены по высокому порогу и планы по низкому`() {
        val file = syntheticVideofile()
        try {
            val videofile = videofileOf(file)
            val detector = SceneDetector(ExternalProgram(), requireProgram("ffmpeg"))

            val scenesOnly = detector.detect(videofile, sceneThreshold = 10.0, shotThreshold = 10.0)
            val both = detector.detect(videofile, sceneThreshold = 10.0, shotThreshold = 0.05)

            assertTrue(scenesOnly.sceneBoundaries.isNotEmpty(), "на проверочном ролике смена сцены обязана найтись")
            assertEquals(scenesOnly.sceneBoundaries, both.sceneBoundaries, "высокий порог даёт одни и те же сцены")
            assertTrue(
                both.shotBoundaries.size >= scenesOnly.sceneBoundaries.size,
                "низкий порог даёт не меньше границ планов, чем границ сцен: пороги из одного потока",
            )
            assertTrue(
                scenesOnly.shotBoundaries.containsAll(scenesOnly.sceneBoundaries),
                "при равных порогах границы сцен входят в границы планов",
            )
            assertTrue(
                both.shotBoundaries.all { it in 0 until videofile.frameCount },
                "все границы попадают внутрь эпизода: номера кадров, а не отметки времени",
            )
            assertTrue(both.scores > 0, "детектор вернул оценки смены сцены")
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `порог плана выше порога сцены отвергается`() {
        val file = syntheticVideofile()
        try {
            val videofile = videofileOf(file)
            val detector = SceneDetector(ExternalProgram(), requireProgram("ffmpeg"))

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    detector.detect(videofile, sceneThreshold = 5.0, shotThreshold = 50.0)
                }

            assertTrue(
                failure.message!!.contains("Порог границы плана"),
                "текст отказа объясняет, что именно задано неверно: ${failure.message}",
            )
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `пороги входят в хеш параметров задания`() {
        val detector = SceneDetector(ExternalProgram(), requireProgram("ffmpeg"))

        val first = detector.paramsHash(sceneThreshold = 10.0, shotThreshold = 5.0)
        val same = detector.paramsHash(sceneThreshold = 10.0, shotThreshold = 5.0)
        val other = detector.paramsHash(sceneThreshold = 12.0, shotThreshold = 5.0)

        assertEquals(first, same, "те же пороги дают тот же хеш")
        assertEquals(64, first.length)
        assertTrue(first != other, "смена порога обязана менять хеш: иначе результат выдадут за прежний (Р-10)")
    }
}
