package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.core.media.FrameFormat
import ru.svoemesto.syp.core.media.RawFrame
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Проверка переключения модели детектора конфигурацией (задача T076).
 *
 * Проверяется ровно то, что заявлено: **модель выбирается конфигурацией, а не
 * компиляцией**. Один и тот же кадр идёт в детектор с разными моделями, и
 * результаты обязаны различаться — иначе смена модели в конфигурации была бы
 * объявлена, но ничего бы не меняла. Второе, не менее важное: два разных
 * вида модели не должны молча давать один ключ детектора, иначе результат
 * одного вида можно выдать за другой (FR-090).
 *
 * Проверка идёт на подставной программе детектора: настоящая требует
 * видеокарты и файлов весов, которых в репозитории нет — веса моделей в git
 * не попадают по решению владельца. Проверяется граница «конфигурация →
 * аргументы программы → рамки и ключ детектора».
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceModelSwitchTest {
    private companion object {
        /** Ширина кадра. */
        const val WIDTH: Int = 8

        /** Высота кадра. */
        const val HEIGHT: Int = 4
    }

    @Test
    fun `один и тот же кадр, разные модели, разные рамки и разные ключи`() {
        val directory = Files.createTempDirectory("syp-face-models-switch")
        assumeTrue(pythonAvailable(), "python3 не найден: подставная программа детектора на нём написана")
        val program = FakeFaceDetector.write(directory.resolve("detector"), shiftByKind = 2)
        val first = modelFile(directory, "yunet-2023mar.onnx")
        val second = modelFile(directory, "det-10g.onnx")

        val yunet = detect(program, first, GpuFaceDetector.DEFAULT_MODEL_KIND)
        val other = detect(program, second, "scrfd")

        assertEquals(1, yunet.faces.size, "подставная программа выдаёт по одной рамке на кадр")
        assertEquals(1, other.faces.size, "подставная программа выдаёт по одной рамке на кадр")
        assertNotEquals(
            yunet.faces,
            other.faces,
            "разные модели на одном кадре обязаны дать разные рамки: смена модели " +
                "в конфигурации не должна быть объявленной, но бесполезной",
        )
        assertNotEquals(
            yunet.key,
            other.key,
            "ключ детектора обязан различаться у разных моделей: результат с другой " +
                "моделью нельзя выдать за этот (FR-090)",
        )
    }

    @Test
    fun `вид модели попадает в ключ детектора`() {
        val greeting =
            """{"status": "ready", "model": "/opt/syp/face-detector/models/det_10g.onnx", """ +
                """"model_kind": "scrfd", "provider": "CUDAExecutionProvider", """ +
                """"onnxruntime": "1.29.0", "numpy": "2.5.3", "input": [640, 640], """ +
                """"score_threshold": 0.5, "nms_threshold": 0.4}"""

        val key = GpuFaceDetector.keyOf(greeting, "/opt/syp/face-detector/models/det_10g.onnx")

        assertTrue(key.contains("det_10g.onnx"), "в ключе обязательно имя файла модели. Получено: $key")
        assertTrue(key.contains("scrfd"), "в ключе обязателен вид модели. Получено: $key")
        assertTrue(
            key.contains("CUDAExecutionProvider"),
            "в ключе обязателен провайдер вычислений. Получено: $key",
        )
    }

    @Test
    fun `тот же вид и та же модель дают один и тот же ключ`() {
        val greeting =
            """{"status": "ready", "model": "/models/a.onnx", "model_kind": "yunet", """ +
                """"provider": "CUDAExecutionProvider", "input": [640, 640], """ +
                """"score_threshold": 0.6, "nms_threshold": 0.3}"""

        val first = GpuFaceDetector.keyOf(greeting, "/models/a.onnx")
        val second = GpuFaceDetector.keyOf(greeting, "/models/a.onnx")

        assertEquals(first, second, "одинаковая конфигурация обязана давать один ключ детектора")
    }

    /**
     * Рамки и ключ детектора, полученные одной конфигурацией.
     *
     * @property faces найденные лица
     * @property key ключ детектора после запуска программы
     */
    private data class Outcome(
        val faces: List<DetectedFace>,
        val key: String,
    )

    /**
     * Прогоняет один кадр через детектор с заданной моделью.
     *
     * @param program подставная программа детектора
     * @param model файл модели
     * @param modelKind вид модели
     * @return рамки и ключ детектора
     */
    private fun detect(
        program: Path,
        model: Path,
        modelKind: String,
    ): Outcome {
        val detector =
            GpuFaceDetector(
                program = program.toString(),
                modelPath = model.toString(),
                modelKind = modelKind,
            )
        detector.use {
            val faces = it.detect(frame())
            return Outcome(faces = faces, key = detector.key)
        }
    }

    /**
     * Создаёт пустой файл модели с заданным именем.
     *
     * Настоящих весов в репозитории нет, а проверке важно имя файла: именно
     * оно попадает в ключ детектора.
     *
     * @param directory каталог для файла
     * @param name имя файла
     * @return путь к файлу модели
     */
    private fun modelFile(
        directory: Path,
        name: String,
    ): Path {
        val model = directory.resolve(name)
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
     * Кадр для проверки переключения модели.
     *
     * @return кадр минимального размера
     */
    private fun frame(): RawFrame =
        RawFrame(
            number = 0,
            data = ByteArray(WIDTH * HEIGHT * 3),
            format = FrameFormat(WIDTH, HEIGHT),
        )
}
