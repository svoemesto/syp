package ru.svoemesto.syp.core.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

/**
 * Извлечение одного кадра: проверка на подставном декодере.
 *
 * Настоящий ffmpeg в проверке не участвует намеренно: проверка отвечает за то,
 * что канал декодера построен верно и что кадр приходит байт в байт. Попадание
 * в нужный кадр проверяется на живом стенде, где есть настоящее видео.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FrameExtractorTest {
    /**
     * Кадр приходит от декодера без искажений.
     */
    @Test
    fun `кадр приходит от декодера как есть`() {
        val directory = Files.createTempDirectory("syp-frame-extract")
        val decoder = writeFakeDecoder(directory, payload = "КАДР-КАК-ЕСТЬ")
        val bytes =
            FrameExtractor(decoder.toString()).extract(
                sourcePath = directory.resolve("episod.mkv").toString(),
                frameNumber = 1000,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                frameCount = 10_000,
            )
        assertEquals("КАДР-КАК-ЕСТЬ", String(bytes), "кадр обязан прийти без изменений")
    }

    /**
     * Декодеру передаётся именно номер кадра, а не кадр с нуля.
     *
     * Ошибка здесь выглядит как «картинка есть, но не та»: сдвиг на кадр
     * незаметен в тесте, который проверяет лишь факт получения байтов.
     */
    @Test
    fun `декодеру передаётся момент середины кадра`() {
        val directory = Files.createTempDirectory("syp-frame-seek")
        val decoder = writeFakeDecoder(directory, payload = "x", echoArguments = true)
        val bytes =
            FrameExtractor(decoder.toString()).extract(
                sourcePath = "/ srv/x.mkv",
                frameNumber = 1000,
                timeBaseNum = 1001,
                timeBaseDen = 24_000,
                frameCount = 10_000,
                widthTarget = 720,
            )
        val arguments = String(bytes).lines().filter { it.isNotBlank() }
        assertTrue(
            arguments.any { it == "41.729" },
            "переход обязан идти на середину кадра 1000, а на 41,729 с. Получено: ${arguments.joinToString(" ")}",
        )
        assertTrue(
            arguments.indexOf("-ss") < arguments.indexOf("-i"),
            "переход по времени обязан идти до входа: после входа он читает видео с начала. $arguments",
        )
        assertTrue(
            arguments.indexOf("-vf") > arguments.indexOf("-i"),
            "фильтр масштаба обязан идти после входа, иначе ffmpeg его не применяет. $arguments",
        )
    }

    /**
     * Кадра за пределами эпизода нет — и сказать об этом раньше, чем запускать
     * декодер.
     */
    @Test
    fun `кадр за пределами эпизода отвергается`() {
        val directory = Files.createTempDirectory("syp-frame-range")
        val decoder = writeFakeDecoder(directory, payload = "x")
        val extractor = FrameExtractor(decoder.toString())
        val failure =
            assertThrows<IllegalArgumentException> {
                extractor.extract("/ srv/x.mkv", 99_999, 1001, 24_000, frameCount = 10_000)
            }
        assertTrue(
            failure.message.orEmpty().contains("10 000") || failure.message.orEmpty().contains("10000"),
            "отказ обязан называть, сколько кадров в эпизоде. Получено: ${failure.message}",
        )
    }

    /**
     * Вырожденная база времени означает, что номер кадра не переводится в
     * момент, а не что кадр где-то есть.
     */
    @Test
    fun `вырожденная база времени отвергается`() {
        val directory = Files.createTempDirectory("syp-frame-timebase")
        val decoder = writeFakeDecoder(directory, payload = "x")
        val failure =
            assertThrows<IllegalArgumentException> {
                FrameExtractor(decoder.toString())
                    .extract("/ srv/x.mkv", 10, 0, 0, frameCount = 10_000)
            }
        assertTrue(
            failure.message.orEmpty().contains("База времени"),
            "отказ обязан называть, что именно вырождено. Получено: ${failure.message}",
        )
    }

    /**
     * Подставной декодер: печатает в стандартный вывод заданный текст, по
     * требованию — переданные аргументы.
     *
     * @param directory каталог для скрипта
     * @param payload что печатать в стандартный вывод
     * @param echoArguments печатать ли аргументы вместо payload
     * @return путь к скрипту
     */
    private fun writeFakeDecoder(
        directory: java.nio.file.Path,
        payload: String,
        echoArguments: Boolean = false,
    ): java.nio.file.Path {
        val body =
            if (echoArguments) {
                "#!/bin/sh\nprintf '%s\\n' \"\$@\""
            } else {
                "#!/bin/sh\nprintf '%s' '$payload'"
            }
        val script = directory.resolve("fake-decoder.sh")
        Files.writeString(script, body + "\n")
        script.toFile().setExecutable(true)
        return script
    }
}
