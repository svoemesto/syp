package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.core.media.FrameChannel
import ru.svoemesto.syp.core.media.FrameFormat
import ru.svoemesto.syp.core.media.RawFrame
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки детектора лиц на видеокарте (задача T063).
 *
 * Проверяется не сама нейросеть, а **граница с программой детектора**: кадр
 * уходит в процесс, ответ приходит и разбирается в рамки, ключ детектора
 * описывает поднятую конфигурацию, программа закрывается вместе с проходом, а
 * каждый сбой — молчание, чужой номер кадра, оборванный ответ, ненулевой код
 * завершения — приводит к отказу с текстом, а не к «нулевому результату»
 * (constitution IV.2, SC-005, FR-092).
 *
 * Настоящая программа детектора требует видеокарты и модели; для этих проверок
 * она подменяется программой, которая говорит на том же протоколе и
 * выдаёт заданный сценарий сбоя. Проверка пропускается, если на машине нет
 * `python3`: подставная программа на нём написана.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class GpuFaceDetectorTest {
    private companion object {
        /** Ширина кадра подставного декодера. */
        const val WIDTH: Int = 4

        /** Высота кадра подставного декодера. */
        const val HEIGHT: Int = 2

        /** Имя файла подставной модели. */
        const val MODEL: String = "fake.onnx"
    }

    @Test
    fun `кадр уходит в программу, рамки приходят обратно`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-ok")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 3)
        val program = FakeFaceDetector.write(directory.resolve("detector"), faceX2 = 4, faceY2 = 2)
        val detector = detector(program)
        val scan = FaceScan(FrameChannel(decoder.toString()), detector)

        val result = scan.scan(series(frames = 3))

        assertEquals(3, result.frames, "обработано должно быть ровно столько кадров, сколько в серии")
        assertEquals(3, result.faces, "программа выдаёт по одной рамке на кадр")
        assertEquals(3, result.framesWithFaces, "кадров с лицами: все три")
        assertFalse(result.detectorIsStub, "настоящий детектор заглушкой не является")
        assertFalse(
            result.note("S01E01").contains("ЗАГЛУШКА"),
            "текст результата не должен называть настоящий детектор заглушкой",
        )
    }

    @Test
    fun `ключ детектора описывает поднятую конфигурацию`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-key")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 1)
        val program = FakeFaceDetector.write(directory.resolve("detector"))
        val detector = detector(program)
        val scan = FaceScan(FrameChannel(decoder.toString()), detector)

        val result = scan.scan(series(frames = 1))

        assertTrue(
            result.detectorKey.startsWith("${GpuFaceDetector.KEY_PREFIX}:"),
            "ключ детектора обязан называть реализацию и её конфигурацию. Получено: ${result.detectorKey}",
        )
        assertTrue(
            result.detectorKey.contains("fake.onnx") && result.detectorKey.contains("640x640"),
            "в ключе обязаны быть модель и размер входа сети: результат с другой моделью " +
                "нельзя выдать за этот (FR-090). Получено: ${result.detectorKey}",
        )
        assertTrue(
            result.detectorKey != StubFaceDetector.KEY,
            "ключ настоящего детектора не должен совпадать с ключом заглушки",
        )
    }

    @Test
    fun `ненулевой код завершения программы даёт отказ с текстом ошибки`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-exit")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 4)
        val program =
            FakeFaceDetector.write(
                directory.resolve("detector"),
                mode = FakeFaceDetector.MODE_FAIL,
                failAt = 1,
                stderrText = "подставленный сбой детектора",
            )
        val scan = FaceScan(FrameChannel(decoder.toString()), detector(program))

        val failure = assertFailsWith<FaceDetectorFailed> { scan.scan(series(frames = 4)) }

        assertTrue(
            failure.message.orEmpty().contains("подставленный сбой детектора"),
            "текст отказа обязан содержать вывод программы: иначе оператор не поймёт причину. " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `ответ на чужой кадр — отказ, а не молчание`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-wrong")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 4)
        val program =
            FakeFaceDetector.write(
                directory.resolve("detector"),
                mode = FakeFaceDetector.MODE_WRONG_NUMBER,
                failAt = 1,
            )
        val scan = FaceScan(FrameChannel(decoder.toString()), detector(program))

        val failure = assertFailsWith<FaceDetectorFailed> { scan.scan(series(frames = 4)) }

        assertTrue(
            failure.message.orEmpty().contains("кадры потеряли порядок"),
            "ответ на чужой номер кадра означает потерю порядка кадров (ADR-0001). " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `молчание программы даёт отказ по таймауту кадра`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-silent")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 4)
        val program =
            FakeFaceDetector.write(
                directory.resolve("detector"),
                mode = FakeFaceDetector.MODE_SILENT,
                failAt = 1,
            )
        val detector =
            GpuFaceDetector(
                program = program.toString(),
                modelPath = modelFile(program).toString(),
                frameTimeout = Duration.ofSeconds(3),
            )
        val scan = FaceScan(FrameChannel(decoder.toString()), detector)

        val failure = assertFailsWith<FaceDetectorFailed> { scan.scan(series(frames = 4)) }

        assertTrue(
            failure.message.orEmpty().contains("не ответила на кадр"),
            "отказ по таймауту кадра означает, что программа замолчала. Получено: ${failure.message}",
        )
    }

    @Test
    fun `оборванный ответ — отказ, а не частичный результат`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-truncated")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 4)
        val program =
            FakeFaceDetector.write(
                directory.resolve("detector"),
                mode = FakeFaceDetector.MODE_TRUNCATED,
                failAt = 1,
            )
        val detector =
            GpuFaceDetector(
                program = program.toString(),
                modelPath = modelFile(program).toString(),
                frameTimeout = Duration.ofSeconds(3),
            )
        val scan = FaceScan(FrameChannel(decoder.toString()), detector)

        val failure = assertFailsWith<FaceDetectorFailed> { scan.scan(series(frames = 4)) }

        assertTrue(
            failure.message.orEmpty().contains("Ответ на кадр") &&
                failure.message.orEmpty().contains("оборван"),
            "программа ответила, но ответ неполон: это отказ, а не пустой кадр. " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `отсутствие программы и модели даёт внятный отказ до запуска`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-missing")

        val noProgram =
            assertFailsWith<FaceDetectorFailed> {
                detector(directory.resolve("нет-такой-программы")).detect(frame())
            }
        assertTrue(
            noProgram.message.orEmpty().contains("не найдена или не исполняется"),
            "отсутствие программы должно называться прямо. Получено: ${noProgram.message}",
        )

        val program = FakeFaceDetector.write(directory.resolve("detector"))
        val noModel =
            assertFailsWith<FaceDetectorFailed> {
                GpuFaceDetector(
                    program = program.toString(),
                    modelPath = directory.resolve("нет-такой-модели.onnx").toString(),
                ).detect(frame())
            }
        assertTrue(
            noModel.message.orEmpty().contains("Модель детектора"),
            "отсутствие модели должно называться прямо. Получено: ${noModel.message}",
        )
    }

    @Test
    fun `программа закрывается вместе с проходом и поднимается заново`() {
        val directory = Files.createTempDirectory("syp-gpu-faces-restart")
        val decoder = FakeDecoder.write(directory.resolve("decoder"), frames = 2)
        val program = FakeFaceDetector.write(directory.resolve("detector"))
        val detector = detector(program)
        val scan = FaceScan(FrameChannel(decoder.toString()), detector)

        val first = scan.scan(series(frames = 2))
        val second = scan.scan(series(frames = 2))

        assertEquals(2, first.faces, "первый проход обязан получить рамки")
        assertEquals(2, second.faces, "второй проход обязан поднять программу заново и получить рамки")
    }

    /**
     * Собирает детектор над подставной программой.
     *
     * Файл модели создаётся пустым: подставная программа его не читает, но
     * детектор проверяет наличие файла **до** запуска — иначе отказ звучал бы
     * «процесс завершился с кодом 2», и по тексту нельзя было бы понять, что
     * не так.
     *
     * @param program путь к подставной программе
     * @return детектор лиц на видеокарте
     */
    private fun detector(program: Path): GpuFaceDetector {
        assumeTrue(pythonAvailable(), "python3 не найден: подставная программа детектора на нём написана")
        return GpuFaceDetector(program = program.toString(), modelPath = modelFile(program).toString())
    }

    /**
     * Создаёт пустой файл модели рядом с подставной программой.
     *
     * @param program путь к подставной программе
     * @return путь к файлу модели
     */
    private fun modelFile(program: Path): Path {
        val model = program.resolveSibling(MODEL)
        Files.write(model, byteArrayOf(0))
        return model
    }

    /**
     * Есть ли в системе интерпретатор для подставной программы.
     *
     * @return признак наличия `python3`
     */
    private fun pythonAvailable(): Boolean =
        listOf("python3", "python")
            .any { command ->
                runCatching {
                    ProcessBuilder(command, "--version").redirectErrorStream(true).start().waitFor() == 0
                }.getOrDefault(false)
            }

    /**
     * Кадр для проверки отказа до запуска программы.
     *
     * @return кадр минимального размера
     */
    private fun frame(): RawFrame =
        RawFrame(
            number = 0,
            data = ByteArray(WIDTH * HEIGHT * 3),
            format = FrameFormat(WIDTH, HEIGHT),
        )

    /**
     * Собирает серию нужного размера.
     *
     * @param frames сколько кадров в серии
     * @return серия на вымышленном пути: подставной декодер файл не читает
     */
    private fun series(frames: Int): Series =
        Series(
            serialId = 1,
            ordinal = 0,
            name = "S01E01",
            sourcePath = "/srv/got/S01E01.mkv",
            byteSize = 1000,
            fileMtime = OffsetDateTime.parse("2024-11-05T10:00:00Z"),
            frameCount = frames,
            timeBaseNum = 1001,
            timeBaseDen = 24_000,
            width = WIDTH,
            height = HEIGHT,
            durationNum = frames.toLong() * 1001,
            durationDen = 24_000,
            videoCodec = "h264",
            videoProfile = "High",
            pixelFormat = "yuv420p",
            keyframeMap = KeyframeMap.build(frames, listOf(0)),
        )
}
