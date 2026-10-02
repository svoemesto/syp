package ru.svoemesto.syp.admin.characters

import org.junit.jupiter.api.Test
import ru.svoemesto.syp.admin.catalog.KeyframeMap
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.core.media.FrameChannel
import ru.svoemesto.syp.core.media.FrameChannelFailed
import ru.svoemesto.syp.core.media.RawFrame
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки прохода по кадрам (задача T062).
 *
 * Закрываются четыре требования задания:
 *
 * 1. **кадры идут потоком и не сохраняются** — ни одного файла за проход не
 *    появляется (FR-024, ADR-0002);
 * 2. **адаптивного шага нет** — детектор получает кадры подряд, номера идут
 *    без пропусков (ADR-0002);
 * 3. **детектор — заглушка, и это видно** — ключ заглушки попадает в итог, а
 *    текст прямо называет заглушку: иначе «лиц не найдено» читалось бы как
 *    вывод детектора;
 * 4. **сбой виден** — ненулевой код декодера и оборванный кадр приводят к
 *    отказу с текстом, а не к «нулевому результату» (constitution IV.2,
 *    SC-005).
 *
 * Проверки идут на подставном декодере: сбой должен быть воспроизводим, а
 * машина без видеокарты и без доступа к архиву для этого не годится.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceScanTest {
    private companion object {
        /** Ширина кадра подставного декодера. */
        const val WIDTH: Int = 4

        /** Высота кадра подставного декодера. */
        const val HEIGHT: Int = 2
    }

    @Test
    fun `детектор получает каждый кадр подряд, без адаптивного шага`() {
        val directory = Files.createTempDirectory("syp-faces-scan-frames")
        val decoder = FakeDecoder.write(directory, frames = 7)
        val seen = mutableListOf<Int>()
        val scan = scanOver(decoder, CountingDetector(seen))

        val result = scan.scan(series(frames = 7))

        assertEquals((0 until 7).toList(), seen, "детектор обязан получить каждый кадр серии по порядку")
        assertEquals(7, result.frames, "обработано должно быть ровно столько кадров, сколько в серии")
    }

    @Test
    fun `за проход не появляется ни одного файла`() {
        val workRoot = Files.createTempDirectory("syp-faces-scan-work")
        val decoder = FakeDecoder.write(workRoot.resolve("decoder"), frames = 5)
        val before = filesUnder(workRoot)
        val scan = scanOver(decoder, StubFaceDetector())

        scan.scan(series(frames = 5))

        assertEquals(
            before,
            filesUnder(workRoot),
            "проход по кадрам не имеет права создавать файлы: кадры остаются в буфере (FR-024)",
        )
    }

    @Test
    fun `заглушка объявляет себя заглушкой в результате`() {
        val directory = Files.createTempDirectory("syp-faces-scan-stub")
        val decoder = FakeDecoder.write(directory, frames = 3)
        val scan = scanOver(decoder, StubFaceDetector())

        val result = scan.scan(series(frames = 3))

        assertEquals(StubFaceDetector.KEY, result.detectorKey, "в результате должен стоять ключ детектора")
        assertTrue(result.detectorIsStub, "заглушка обязана объявлять себя заглушкой")
        assertEquals(0, result.faces, "заглушка не выдаёт ни одного лица")
        assertTrue(
            result.note("S01E01").contains("ЗАГЛУШКА"),
            "текст результата обязан называть заглушку: «лиц не найдено» иначе читается " +
                "как вывод детектора. Получено: ${result.note("S01E01")}",
        )
    }

    @Test
    fun `ненулевой код декодера даёт отказ с текстом ошибки`() {
        val directory = Files.createTempDirectory("syp-faces-scan-exit")
        val decoder =
            FakeDecoder.write(
                directory,
                frames = 2,
                exit = 3,
                stderrText = "подставленный сбой декодера",
            )
        val scan = scanOver(decoder, StubFaceDetector())

        val failure =
            assertFailsWith<FrameChannelFailed> {
                scan.scan(series(frames = 2))
            }

        assertTrue(
            failure.message.orEmpty().contains("подставленный сбой декодера"),
            "текст ошибки декодера обязан попасть в отказ: у потока кадров отдельный " +
                "поток вывода ошибок, и потерять его нельзя (FR-092). " +
                "Получено: ${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("3"),
            "в тексте отказа должен называться код завершения декодера. " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `оборванный кадр — отказ, а не нулевой результат`() {
        val directory = Files.createTempDirectory("syp-faces-scan-truncated")
        // 4x2 в bgr24 — это 24 байта на кадр; последний кадр недописан.
        val decoder = FakeDecoder.write(directory, frames = 1, truncateBytes = 10)
        val scan = scanOver(decoder, StubFaceDetector())

        val failure =
            assertFailsWith<FrameChannelFailed> {
                scan.scan(series(frames = 2))
            }

        assertTrue(
            failure.message.orEmpty().contains("оборвался"),
            "неполный кадр обязан называться в отказе: такой поток результатом быть не может (SC-005). " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `кадров меньше, чем в серии, — отказ`() {
        val directory = Files.createTempDirectory("syp-faces-scan-short")
        val decoder = FakeDecoder.write(directory, frames = 2)
        val scan = scanOver(decoder, StubFaceDetector())

        val failure =
            assertFailsWith<FrameChannelFailed> {
                scan.scan(series(frames = 4))
            }

        assertTrue(
            failure.message.orEmpty().contains("Обработано кадров 2"),
            "неполный охват серии обязан быть отказом, а не результатом: кадры не пропускаются (FR-030). " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `рамка вне кадра отвергается`() {
        val directory = Files.createTempDirectory("syp-faces-scan-box")
        val decoder = FakeDecoder.write(directory, frames = 1)
        val outside = DetectedFace(x1 = 0, y1 = 0, x2 = WIDTH + 10, y2 = HEIGHT, confidence = 0.9)
        val scan = scanOver(decoder, FixedDetector(outside))

        val failure =
            assertFailsWith<IllegalArgumentException> {
                scan.scan(series(frames = 1))
            }

        assertTrue(
            failure.message.orEmpty().contains("выходит за пределы кадра"),
            "рамка за пределами кадра обязана отвергаться на границе канала. " +
                "Получено: ${failure.message}",
        )
    }

    @Test
    fun `найденные лица считаются, кадры с лицами считаются отдельно`() {
        val directory = Files.createTempDirectory("syp-faces-scan-count")
        val decoder = FakeDecoder.write(directory, frames = 3)
        // Лицо появляется в кадрах 0 и 2, в кадре 1 — нет.
        val perFrame = PerFrameDetector { number -> if (number % 2 == 0) 2 else 0 }
        val scan = scanOver(decoder, perFrame)

        val result = scan.scan(series(frames = 3))

        assertEquals(4, result.faces, "всего лиц: два в первом кадре и два в третьем")
        assertEquals(2, result.framesWithFaces, "кадров с лицами: первый и третий")
        assertFalse(result.detectorIsStub, "детектор, выдавший лица, заглушкой не является")
    }

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

    /**
     * Перечисляет файлы каталога с путями относительно него.
     *
     * @param root каталог
     * @return относительные пути файлов по возрастанию
     */
    private fun filesUnder(root: Path): List<String> =
        Files
            .walk(root)
            .use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .map { root.relativize(it).toString() }
                    .sorted()
                    .toList()
            }

    /**
     * Собирает проход по кадрам над подставным декодером.
     *
     * @param decoder путь к подставному декодеру
     * @param detector детектор лиц
     * @return проход по кадрам
     */
    private fun scanOver(
        decoder: Path,
        detector: FaceDetector,
    ): FaceScan = FaceScan(FrameChannel(decoder.toString()), detector)
}

/**
 * Детектор, который только считает кадры.
 *
 * @property seen список номеров полученных кадров
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class CountingDetector(
    private val seen: MutableList<Int>,
) : FaceDetector {
    /** Ключ проверочного детектора. */
    override val key: String = "counting-1"

    /**
     * Запоминает номер кадра.
     *
     * @param frame кадр
     * @return пустой список: проверка считает кадры, а не лица
     */
    override fun detect(frame: RawFrame): List<DetectedFace> {
        seen.add(frame.number)
        return emptyList()
    }
}

/**
 * Детектор, который всегда возвращает одну и ту же рамку.
 *
 * @property face рамка, которую возвращает детектор
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FixedDetector(
    private val face: DetectedFace,
) : FaceDetector {
    /** Ключ проверочного детектора. */
    override val key: String = "fixed-1"

    /**
     * Возвращает заданную рамку.
     *
     * @param frame кадр
     * @return список из одной рамки
     */
    override fun detect(frame: RawFrame): List<DetectedFace> = listOf(face)
}

/**
 * Детектор, число лиц в кадре которого задаётся функцией от номера кадра.
 *
 * @property facesIn сколько лиц выдать в кадре с заданным номером
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class PerFrameDetector(
    private val facesIn: (Int) -> Int,
) : FaceDetector {
    /** Ключ проверочного детектора. */
    override val key: String = "per-frame-1"

    /**
     * Возвращает рамки в пределах кадра.
     *
     * @param frame кадр
     * @return столько рамок, сколько задано для номера кадра
     */
    override fun detect(frame: RawFrame): List<DetectedFace> =
        (0 until facesIn(frame.number)).map { index ->
            DetectedFace(
                x1 = index,
                y1 = 0,
                x2 = index + 1,
                y2 = 1,
                confidence = 0.5,
            )
        }
}
