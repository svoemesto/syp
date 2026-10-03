package ru.svoemesto.syp.admin.catalog

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.core.media.ExternalProgram
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Сверка параметров эпизода с измеренными (задача T038).
 *
 * Что здесь проверяется. Опрос файла [SourceProbe] снимает параметры тремя
 * проходами `ffprobe`. Каждый проход — отдельное решение: счётчик кадров
 * обходит файл, карта ключевых кадров читает только их. Ошибка в любом из
 * проходов разъезжается по всем границам сцен и планов, потому что номер
 * кадра — единственный источник правды (ADR-0001). Поэтому параметры системы
 * сверяются не со «значением в задаче», а с **прямым замером того же файла
 * тем же `ffprobe`**: так видно, чьё расхождение — системы или измерения.
 *
 * Проверка требует файла эпизода и `ffprobe`. Без них она **пропускается**,
 * а не падает: без архива юнит-проверки домена должны оставаться
 * работоспособными.
 *
 * Файл эпизода задаётся переменный окружения [ENV_EPISODE]. Для эпизода
 * `GOT.S01E01` дополнительно сверяются зафиксированные числа спецификации:
 * 88 643 кадра, 1920×1080, 24000/1001, 5 598 286 865 байт и 792 ключевых
 * кадра. Сверка с ними выполняется только когда базовое имя файла совпадает,
 * иначе она была бы выдуманным числом для чужого файла.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class VideofileParametersParityTest {
    /**
     * Сверяет параметры определённые системой с прямым замером файла.
     *
     * @throws org.opentest4j.TestAbortedException если файл эпизода или
     *   программа `ffprobe` недоступны: проверка пропускается
     */
    @Test
    fun `параметры эпизода совпадают с измеренными`() {
        val videofilePath = requireVideofile()
        val ffprobe = requireProgram("ffprobe")
        val probe = SourceProbe(ExternalProgram(), ffprobe)

        val measured =
            probe.probe(
                videofilePath,
            )
        val direct = directMeasurements(ffprobe, videofilePath)

        println("=== СВЕРКА ПАРАМЕТРОВ ЭПИЗОДА: ${videofilePath.fileName} ===")
        println("--- измерение ffprobe напрямую ---")
        println("  число видеопакетов (count_packets): ${direct.packets}")
        println("  ключевых кадров (skip_frame nokey): ${direct.keyframes}")
        println("  размер файла (format=size), байт: ${direct.size}")
        println("--- параметры, определённые системой ---")
        println("  число кадров: ${measured.frameCount}")
        println("  частокадровая база: ${measured.timeBaseNum}/${measured.timeBaseDen} с на кадр")
        println("  разрешение: ${measured.width}x${measured.height}")
        println("  размер файла, байт: ${measured.byteSize}")
        println("  длительность по кадрам, с: ${"%.3f".format(measured.durationSeconds())}")
        println("  длительность, объявленная контейнером, с: ${measured.containerDurationSeconds}")
        println("  кодек/профиль/формат пикселей: ${measured.videoCodec}/${measured.videoProfile}/${measured.pixelFormat}")
        println("  звук: ${measured.audioCodec}, каналов ${measured.audioChannels}, частота ${measured.audioSampleRate}")
        println("  ключевых кадров: ${measured.keyframes.keyframeCount()}, карта ${measured.keyframes.byteLength} байт")
        println("--- сверка ---")
        println("  кадры: система ${measured.frameCount}, измерение ${direct.packets}")
        println("  ключевые кадры: система ${measured.keyframes.keyframeCount()}, измерение ${direct.keyframes}")
        println("  размер: система ${measured.byteSize}, измерение ${direct.size}")

        assertEquals(direct.packets, measured.frameCount, "число кадров системы разошлось с измерением")
        assertEquals(direct.keyframes, measured.keyframes.keyframeCount(), "число ключевых кадров разошлось с измерением")
        assertEquals(direct.size, measured.byteSize, "размер файла разошся с измерением")
        assertEquals(KeyframeMap.requiredLength(measured.frameCount), measured.keyframes.byteLength)

        if (videofilePath.fileName.toString().startsWith(EXPECTED_S1E1_PREFIX)) {
            println("--- сверка с числами спецификации для $EXPECTED_S1E1_PREFIX ---")
            assertEquals(EXPECTED_FRAMES, measured.frameCount, "число кадров $EXPECTED_S1E1_PREFIX разошлось со спецификацией")
            assertEquals(EXPECTED_WIDTH, measured.width)
            assertEquals(EXPECTED_HEIGHT, measured.height)
            assertEquals(EXPECTED_FPS_NUM, measured.timeBaseDen, "24000/1001 — в системе хранится длительность кадра: знаменатель 24000")
            assertEquals(EXPECTED_FPS_DEN, measured.timeBaseNum, "1001/24000 — в системе хранится длительность кадра: числитель 1001")
            assertEquals(EXPECTED_BYTES, measured.byteSize)
            assertEquals(EXPECTED_KEYFRAMES, measured.keyframes.keyframeCount())
            // Длительность по кадрам обязана отличаться от объявленной
            // контейнером: это и есть второй источник правды, который
            // ADR-0001 запрещает (округление контейнерной длительности до
            // целого кадра расходится с фактом на кадр).
            assertTrue(
                measured.durationSeconds() > 3697.0 && measured.durationSeconds() < 3697.5,
                "длительность по кадрам ${measured.durationSeconds()} вне ожидаемой полосы 3697,0…3697,5 с",
            )
        }
    }

    /**
     * Прямые измерения файла тем же `ffprobe`, минуя систему.
     *
     * @property packets число видеопакетов
     * @property keyframes число ключевых кадров
     * @property size размер файла в байтах
     */
    private data class DirectMeasurements(
        val packets: Int,
        val keyframes: Int,
        val size: Long,
    )

    /**
     * Снимает параметры файла напрямую, без участия системы.
     *
     * @param ffprobe путь к программе
     * @param videofilePath путь к файлу эпизода
     * @return прямые измерения
     */
    private fun directMeasurements(
        ffprobe: String,
        videofilePath: Path,
    ): DirectMeasurements {
        val program = ExternalProgram()
        val packets =
            program
                .runOrFail(
                    ffprobe,
                    listOf(
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-count_packets",
                        "-show_entries",
                        "stream=nb_read_packets",
                        "-of",
                        "csv=p=0",
                        videofilePath.toString(),
                    ),
                ).output
                .trim()
                .lines()
                .first()
                .trim()
                .toInt()
        val keyframes =
            program
                .runOrFail(
                    ffprobe,
                    listOf(
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-skip_frame",
                        "nokey",
                        "-show_entries",
                        "frame=best_effort_timestamp",
                        "-of",
                        "csv=p=0",
                        videofilePath.toString(),
                    ),
                ).output
                .lineSequence()
                .count { it.isNotBlank() }
        val size =
            program
                .runOrFail(
                    ffprobe,
                    listOf("-v", "error", "-show_entries", "format=size", "-of", "csv=p=0", videofilePath.toString()),
                ).output
                .trim()
                .toLong()
        return DirectMeasurements(packets, keyframes, size)
    }

    /**
     * Путь к проверяемому файлу эпизода из окружения.
     *
     * @return путь к файлу эпизода
     * @throws org.opentest4j.TestAbortedException если переменный не задана или
     *   файла нет: сверять не с чем, и это не повод падать
     */
    private fun requireVideofile(): Path {
        val declared = System.getenv(ENV_EPISODE)
        assumeTrue(!declared.isNullOrBlank()) {
            "Переменная $ENV_EPISODE не задана: сверка параметров эпизода с измеренными пропущена. " +
                "Укажите путь к файлу эпизода, например $EXAMPLE_EPISODE"
        }
        val path = Paths.get(declared!!)
        assumeTrue(Files.isRegularFile(path)) { "Файла эпизода $path нет: сверка пропущена" }
        return path
    }

    /**
     * Полный путь к программе `ffprobe`.
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
        assumeTrue(found != null) { "Программа $name не найдена в PATH: сверка параметров эпизода пропущена" }
        return found!!
    }

    private companion object {
        /** Имя переменной окружения с путём к файлу эпизода. */
        const val ENV_EPISODE: String = "SYP_SOURCE_EPISODE"

        /** Пример значения переменной — путь к файлу эпизода в архиве. */
        const val EXAMPLE_EPISODE: String = "/disks/HDD_16Tb_Clouds/GOT/GOT.S01/GOT.S01E01.BDRip.1080p.mkv"

        /** Начало имени файла эпизода, для которой зафиксированы числа спецификации. */
        const val EXPECTED_S1E1_PREFIX: String = "GOT.S01E01"

        /** Число кадров `GOT.S01E01` по спецификации. */
        const val EXPECTED_FRAMES: Int = 88_643

        /** Ширина кадра `GOT.S01E01`. */
        const val EXPECTED_WIDTH: Int = 1920

        /** Высота кадра `GOT.S01E01`. */
        const val EXPECTED_HEIGHT: Int = 1080

        /** Числитель частоты кадров `GOT.S01E01`; в системе — знаменатель длительности кадра. */
        const val EXPECTED_FPS_NUM: Int = 24_000

        /** Знаменатель частоты кадров `GOT.S01E01`; в системе — числитель длительности кадра. */
        const val EXPECTED_FPS_DEN: Int = 1001

        /** Размер файла `GOT.S01E01`, байт. */
        const val EXPECTED_BYTES: Long = 5_598_286_865

        /** Число ключевых кадров `GOT.S01E01`. */
        const val EXPECTED_KEYFRAMES: Int = 792
    }
}
