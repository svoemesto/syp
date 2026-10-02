package ru.svoemesto.syp.core.images

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import ru.svoemesto.syp.core.storage.FileSystemStorage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверки листов превью.
 *
 * Закрывают требования задачи T049:
 *
 * 1. лист — сетка 16×16 ячеек по 135×75, то есть 256 кадров на лист;
 * 2. на `GOT.S01E01` создаётся ровно `ceil(88 643 / 256)` = **347** листов;
 * 3. **листы идут подряд без пропусков и перекрытий**: между соседними
 *    листами нет щели, один кадр не показывается дважды (ADR-0001);
 * 4. **лист появляется в хранилище целиком или никак**: он пишется во временный
 *    ключ и переносится на окончательный после того, как записан целиком
 *    (FR-091).
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class PreviewSheetTest {
    /** Число кадров эпизода S1E1: число из спецификации, не оценка. */
    private val s1e1Frames = 88_643

    @Test
    fun `раскладка первого среза — 16 на 16 ячеек по 135 на 75`() {
        val layout = PreviewLayout.STANDARD

        assertEquals(16, layout.columns)
        assertEquals(16, layout.rows)
        assertEquals(135, layout.cellWidth)
        assertEquals(75, layout.cellHeight)
        assertEquals(256, layout.framesPerSheet)
        assertEquals(2160, layout.sheetWidth)
        assertEquals(1200, layout.sheetHeight)
    }

    @Test
    fun `на эпизоде S1E1 создаётся 347 листов`() {
        assertEquals(347, PreviewSheet.sheetCount(s1e1Frames))
        assertEquals(347 * 256, 88_832, "347 листов покрывают 88 832 кадра")
        assertTrue(347 * 256 > s1e1Frames, "последний лист неполный, но он всё равно нужен")
    }

    @Test
    fun `листы идут подряд без пропусков и перекрытий`() {
        val sheets = PreviewSheet.all(episodeId = 7, frameCount = s1e1Frames)

        assertEquals(347, sheets.size)
        assertEquals(0, sheets.first().firstFrame)
        assertEquals(s1e1Frames - 1, sheets.last().lastFrame)
        sheets.zipWithNext().forEach { (previous, next) ->
            assertEquals(
                previous.lastFrame + 1,
                next.firstFrame,
                "листы ${previous.index} и ${next.index} идут подряд: между ними нет щели и наложения",
            )
        }
        // Каждый кадр эпизода принадлежит ровно одному листу.
        val covered = sheets.sumOf { it.frameNumbersCount }
        assertEquals(s1e1Frames, covered, "все кадры эпизода покрыты листами ровно по одному разу")
    }

    @Test
    fun `последний лист урезан по числу кадров`() {
        val sheets = PreviewSheet.all(episodeId = 7, frameCount = s1e1Frames)
        val last = sheets.last()

        assertEquals(88_576, last.firstFrame, "88 576 — начало 347-го листа при 256 кадрах на лист")
        assertEquals(88_642, last.lastFrame)
        assertEquals(67, last.frameNumbersCount, "в последнем листе 67 кадров из 256")
    }

    @Test
    fun `положение кадра в раскладке считается по порядку кадров`() {
        val sheet = PreviewSheet.of(episodeId = 7, index = 1, frameCount = s1e1Frames)

        assertEquals(256, sheet.firstFrame)
        assertEquals(511, sheet.lastFrame)
        assertEquals(0 to 0, sheet.positionOf(256).let { it.row to it.column })
        assertEquals(0 to 15, sheet.positionOf(271).let { it.row to it.column })
        assertEquals(1 to 0, sheet.positionOf(272).let { it.row to it.column })
        assertFailsWith<IllegalArgumentException> { sheet.positionOf(255) }
        assertFailsWith<IllegalArgumentException> { sheet.positionOf(512) }
    }

    @Test
    fun `ключи листа отражают эпизод и номер листа`(
        @TempDir root: Path,
    ) {
        val sheet = PreviewSheet.of(episodeId = 42, index = 3, frameCount = s1e1Frames)

        assertTrue(sheet.finalKey().contains("/42/"), "ключ содержит эпизод: ${sheet.finalKey()}")
        assertTrue(
            sheet.finalKey().endsWith("000003.png") || sheet.finalKey().endsWith("000003"),
            "ключ заканчивается номером листа с ведущими нулями: ${sheet.finalKey()}",
        )
        assertTrue(sheet.temporaryKey().contains("tmp"), "временный ключ отличается от окончательного")
        assertTrue(sheet.temporaryKey() != sheet.finalKey())
    }

    @Test
    fun `лист появляется в хранилище целиком или никак`(
        @TempDir root: Path,
    ) {
        val storage = FileSystemStorage(root)
        val builder = PreviewSheetBuilder(storage)
        val sheet = PreviewSheet.of(episodeId = 42, index = 0, frameCount = 600)
        val expected = sheet.layout.framesPerSheet
        val built = mutableListOf<Int>()

        val size =
            builder.build(sheet) { frame ->
                built.add(frame)
                ByteArrayInputStream(cellOf())
            }

        assertEquals(expected, built.size, "в лист попали все кадры диапазона, ни один не потерян")
        assertEquals(0, built.first())
        assertEquals(expected - 1, built.last())
        assertTrue(size > 0, "лист записан ненулевым числом байт")
        assertTrue(storage.exists(sheet.finalKey()), "окончательный ключ существует: лист перенесён целиком")
        assertFalse(storage.exists(sheet.temporaryKey()), "временный ключ освобождён после переноса")

        storage.get(sheet.finalKey()).use { stream ->
            val image = ImageIO.read(stream)
            assertEquals(sheet.layout.sheetWidth, image.width)
            assertEquals(sheet.layout.sheetHeight, image.height)
        }
    }

    @Test
    fun `превью не того размера не растягивают лист`(
        @TempDir root: Path,
    ) {
        val storage = FileSystemStorage(root)
        val builder = PreviewSheetBuilder(storage)
        val sheet = PreviewSheet.of(episodeId = 42, index = 0, frameCount = 600)

        val failure =
            assertFailsWith<java.io.IOException> {
                builder.build(sheet) { frame ->
                    ByteArrayInputStream(cellOf(width = 640, height = 360))
                }
            }

        assertTrue(
            failure.message!!.contains("перестал бы быть сеткой"),
            "текст отказа объясняет, что не так с размером превью: ${failure.message}",
        )
        assertFalse(storage.exists(sheet.finalKey()), "незавершённый лист на окончательном ключе не появляется")
    }

    /**
     * Готовит изображение превью заданного размера.
     *
     * @param width ширина изображения
     * @param height высота изображения
     * @return байты изображения в формате PNG
     */
    private fun cellOf(
        width: Int = PreviewLayout.STANDARD.cellWidth,
        height: Int = PreviewLayout.STANDARD.cellHeight,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.DARK_GRAY
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `раскладка с нулём ячеек отвергается`() {
        assertFailsWith<IllegalArgumentException> { PreviewLayout(columns = 0) }
        assertFailsWith<IllegalArgumentException> { PreviewLayout(cellWidth = 0) }
    }
}
