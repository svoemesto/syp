package ru.svoemesto.syp.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/**
 * Определение дорожек подставным зондом.
 *
 * Настоящий видеофайл в набор тестов не кладём: он весит единицы гигабайт и
 * лежит только на машине стенда. Здесь проверяется разбор ответа зонда — тот
 * самый, где ошибка выглядит как «пустой список дорожек» и заметна не сразу.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class TrackProbeTest {
    /**
     * Дорожки разбираются и нумеруются в порядке возрастания номера.
     */
    @Test
    fun `дорожки разбираются и нумеруются`() {
        val probe =
            fakeProbe(
                """
                index=0
                codec_name=h264
                codec_type=video

                index=1
                codec_name=ac3
                codec_type=audio

                index=5
                codec_name=subrip
                codec_type=subtitle
                """.trimIndent(),
            )
        val tracks = TrackProbe(probe.toString()).probe("/ srv/x.mkv")
        assertEquals(listOf(0, 1, 5), tracks.map { it.index }, "номера дорожек как их назвал зонд")
        assertEquals(listOf("video", "audio", "subtitle"), tracks.map { it.codecType })
        assertEquals("h264", tracks.first().codecName)
    }

    /**
     * Порядковый номер считается отдельно по видам: у видео он всегда первый, а
     * у пятой аудиодорожки он четвёртый, и путать их нельзя.
     */
    @Test
    fun `порядковый номер считается по видам`() {
        val probe =
            fakeProbe(
                (0..3).joinToString("\n\n") { n ->
                    "index=$n\ncodec_name=ac3\ncodec_type=audio"
                },
            )
        val tracks = TrackProbe(probe.toString()).probe("/ srv/x.mkv")
        assertEquals(listOf(0, 1, 2, 3), tracks.map { it.ordinal }, "порядок внутри одного вида")
    }

    /**
     * Файл без дорожек — это авария, а не пустой список: по нему дальше нечем
     * работать, и молчаливая пустота хуже явного отказа.
     */
    @Test
    fun `файл без дорожек отвергается`() {
        val failure =
            assertThrows<TrackProbeFailed> {
                TrackProbe(fakeProbe("").toString()).probe("/ srv/x.mkv")
            }
        assertEquals(true, failure.message.orEmpty().contains("ни одной дорожки"), failure.message)
    }

    /**
     * Зонд, которого нет, — это тоже отказ с внятным текстом, а не пустой
     * список: иначе интерфейс показал бы «в файле нет дорожек».
     */
    @Test
    fun `отсутствующий зонд отвергается`() {
        val failure =
            assertThrows<TrackProbeFailed> {
                TrackProbe("/ srv/зонда-нет").probe("/ srv/x.mkv")
            }
        assertEquals(true, failure.message.orEmpty().contains("зонд"), failure.message)
    }

    /**
     * Подставной зонд: печатает заданный ответ в стандартный вывод.
     *
     * @param output ответ зонда
     * @return путь к скрипту
     */
    private fun fakeProbe(output: String): java.nio.file.Path {
        val directory = Files.createTempDirectory("syp-track-probe")
        val script = directory.resolve("fake-probe.sh")
        Files.writeString(script, "#!/bin/sh\ncat <<'PROBE_EOF'\n$output\nPROBE_EOF\n")
        script.toFile().setExecutable(true)
        return script
    }
}
