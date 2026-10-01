package ru.svoemesto.syp.admin.catalog

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.media.ExternalProgram
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки опроса файла серии.
 *
 * Закрываются требования задачи T031: определяются число кадров, числитель и
 * знаменатель частокадровой базы, разрешение, длительность, кодек, профиль,
 * формат пикселей и параметры звука; **время вычисляется от номера кадра и
 * частокадровой базы**, а не хранится отдельно (ADR-0001).
 *
 * Подготовка проверки требует `ffmpeg` и `ffprobe`: без них тесты
 * **пропускаются**, а не падают — юнит-проверки домена должны оставаться
 * работоспособными на машине без видеоинструментов.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SourceProbeTest {
    /**
     * Путь к программе в `PATH`.
     *
     * @param name имя программы
     * @return полный путь
     * @throws org.opentest4j.TestAbortedException если программы нет: тест
     *   помечается пропущенным, а не падающим
     */
    private fun requireProgram(name: String): String {
        val found =
            (System.getenv("PATH") ?: "")
                .split(":")
                .filter { it.isNotBlank() }
                .map { Paths.get(it, name) }
                .firstOrNull { Files.isExecutable(it) }
                ?.toString()
        assumeTrue(found != null) {
            "Программа $name не найдена в PATH: проверки опроса файла пропущены"
        }
        return found!!
    }

    /**
     * Готовит проверочный ролик известных параметров: 2 секунды, 25 кадров в
     * секунду, 128 на 72, ключевой кадр каждые 25 кадров, звука нет.
     *
     * @return путь к файлу, который удаляет вызывающий
     */
    private fun syntheticSeries(): Path {
        val target = Files.createTempDirectory("syp-probe").resolve("series.mkv")
        val result =
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
                        "testsrc=duration=2:size=128x72:rate=25",
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
        assertTrue(result.isSuccess)
        return target
    }

    /**
     * Опрашивает подготовленный ролик.
     *
     * @param series путь к файлу
     * @return параметры файла
     */
    private fun probeOf(series: Path): SourceParameters = SourceProbe(ExternalProgram(), requireProgram("ffprobe")).probe(series)

    @Test
    fun `определяются все параметры файла`() {
        val series = syntheticSeries()

        try {
            val parameters = probeOf(series)

            // Две секунды по 25 кадров в секунду — ровно 50 кадров.
            assertEquals(50, parameters.frameCount)
            assertEquals(128, parameters.width)
            assertEquals(72, parameters.height)
            assertEquals("h264", parameters.videoCodec)
            assertEquals("yuv420p", parameters.pixelFormat)
            assertNotNull(parameters.videoProfile)
            assertTrue(parameters.byteSize > 0)
            assertTrue(parameters.fileMtime.toEpochMilli() > 0)

            // Ролик снят без звука: параметры звука пустые, а не выдуманные.
            assertNull(parameters.audioCodec)
            assertNull(parameters.audioChannels)
            assertNull(parameters.audioSampleRate)
        } finally {
            series.deleteIfExists()
        }
    }

    @Test
    fun `частокадровая база хранится длительностью кадра, а не частотой`() {
        val series = syntheticSeries()

        try {
            val parameters = probeOf(series)

            // 25 кадров в секунду — это 1/25 секунды на кадр, и именно эта
            // величина переводит номер кадра во время (ADR-0001).
            assertEquals(1, parameters.timeBaseNum)
            assertEquals(25, parameters.timeBaseDen)
            assertEquals(2.0, parameters.durationSeconds(), 1e-9)
            assertEquals(1.0 / 25.0, parameters.frameDurationSeconds(), 1e-12)
        } finally {
            series.deleteIfExists()
        }
    }

    @Test
    fun `длительность вычисляется по кадрам, а не берётся у контейнера`() {
        val series = syntheticSeries()

        try {
            val parameters = probeOf(series)

            // Длительность обязана следовать из числа кадров и базы, а не из
            // объявленной контейнером величины: та считается по последнему
            // пакету и на длинной серии расходится с фактом. Сверка идёт
            // умножением крест-накрест: целочисленное деление здесь потеряло бы
            // точность и обесценило проверку.
            assertEquals(
                parameters.frameCount.toLong() * parameters.timeBaseNum * parameters.durationDen,
                parameters.durationNum * parameters.timeBaseDen,
            )
            assertEquals(2L, parameters.durationNum / parameters.durationDen)
        } finally {
            series.deleteIfExists()
        }
    }

    @Test
    fun `время кадра и номер кадра обратимы`() {
        val series = syntheticSeries()

        try {
            val parameters = probeOf(series)

            for (frame in listOf(0L, 1L, 25L, 49L)) {
                assertEquals(frame, parameters.frameOfTime(parameters.timeOfFrame(frame)))
            }
        } finally {
            series.deleteIfExists()
        }
    }

    @Test
    fun `карта ключевых кадров заполняется при опросе`() {
        val series = syntheticSeries()

        try {
            val keyframes = probeOf(series).keyframes

            assertEquals(KeyframeMap.requiredLength(50), keyframes.byteLength)
            // Ключевой кадр есть в начале и в начале второго опорного кадра.
            assertTrue(keyframes.isKeyframe(0))
            assertTrue(keyframes.isKeyframe(25))
            assertTrue(keyframes.keyframeCount() >= 2)
        } finally {
            series.deleteIfExists()
        }
    }

    @Test
    fun `несуществующий файл даёт отказ с кодом SOURCE_UNREADABLE и путём в тексте`() {
        val missing = Paths.get("/srv/нет-такого-каталога/нет-такого-файла.mkv")

        val failure =
            assertFailsWith<DomainException> {
                SourceProbe(ExternalProgram(), requireProgram("ffprobe")).probe(missing)
            }

        assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
        assertEquals(400, failure.toBody().status)
        val text = failure.toBody().message
        assertTrue(
            text.contains(missing.toString()),
            "текст ошибки должен называть путь файла, а не «файл недоступен»: $text",
        )
    }

    @Test
    fun `файл без видеопотока даёт отказ, а не нулевые параметры`() {
        val notAVideo = Files.createTempDirectory("syp-probe").resolve("notes.txt")
        Files.writeString(notAVideo, "это не видеофайл\n")

        try {
            val failure =
                assertFailsWith<DomainException> {
                    SourceProbe(ExternalProgram(), requireProgram("ffprobe")).probe(notAVideo)
                }

            assertEquals(ErrorCode.SOURCE_UNREADABLE, failure.code)
            assertTrue(failure.toBody().message.isNotBlank())
        } finally {
            notAVideo.deleteIfExists()
        }
    }

    @Test
    fun `дробь сокращается до взаимно простого вида`() {
        val two = BigInteger.valueOf(2)
        val one = BigInteger.ONE
        val fpsNumerator = BigInteger.valueOf(24000)
        val fpsDenominator = BigInteger.valueOf(1001)

        assertEquals(one to one, SourceProbe.reduce(two, two))
        // Длительность кадра — обратная величина к частоте кадров: именно её
        // хранит серия, и именно она переводит номер кадра во время
        // (ADR-0001). Для 24000/1001 это 1001/24000 секунды на кадр.
        assertEquals(fpsDenominator to fpsNumerator, SourceProbe.reduce(fpsDenominator, fpsNumerator))
    }
}
