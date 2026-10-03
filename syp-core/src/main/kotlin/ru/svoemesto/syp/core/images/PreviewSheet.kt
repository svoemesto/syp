package ru.svoemesto.syp.core.images

import ru.svoemesto.syp.core.storage.ObjectStorage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.imageio.ImageIO

/**
 * Раскладка листа превью.
 *
 * Размеры листа — **настройка**, а не константа кода: объём превью на эпизод
 * зависит от того, сколько места осталось на SSD, и пороги подбираются
 * замером М-07 (ADR-0003, FR-021). Поэтому раскладка приходит параметром и
 * проверяется на непротиворечивость, а не зашита.
 *
 * Значения по умолчанию соответствуют решению по первому срезу: 16 на 16
 * ячеек по 135×75, то есть 256 кадров на лист.
 *
 * @property columns число ячеек по горизонтали
 * @property rows число ячеек по вертикали
 * @property cellWidth ширина превью в ячейке, пикселей
 * @property cellHeight высота превью в ячейке, пикселей
 * @property contentType тип содержимого листа
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class PreviewLayout(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val cellWidth: Int = DEFAULT_CELL_WIDTH,
    val cellHeight: Int = DEFAULT_CELL_HEIGHT,
    val contentType: String = DEFAULT_CONTENT_TYPE,
) {
    init {
        require(columns > 0 && rows > 0) {
            "Раскладка листа превью задана нулевым числом ячеек: $columns на $rows"
        }
        require(cellWidth > 0 && cellHeight > 0) {
            "Размер превью в ячейке должен быть положительным, задано ${cellWidth}x$cellHeight"
        }
    }

    /** Сколько кадров помещается на лист. */
    val framesPerSheet: Int
        get() = columns * rows

    /** Ширина листа, пикселей. */
    val sheetWidth: Int
        get() = columns * cellWidth

    /** Высота листа, пикселей. */
    val sheetHeight: Int
        get() = rows * cellHeight

    companion object {
        /** Ячеек по горизонтали по умолчанию. */
        const val DEFAULT_COLUMNS: Int = 16

        /** Ячеек по вертикали по умолчанию. */
        const val DEFAULT_ROWS: Int = 16

        /** Ширина превью по умолчанию, пикселей. */
        const val DEFAULT_CELL_WIDTH: Int = 135

        /** Высота превью по умолчанию, пикселей. */
        const val DEFAULT_CELL_HEIGHT: Int = 75

        /** Тип содержимого листа. */
        const val DEFAULT_CONTENT_TYPE: String = "image/png"

        /**
         * Раскладка первого среза: 16 на 16 ячеек по 135×75.
         */
        val STANDARD: PreviewLayout = PreviewLayout()
    }
}

/**
 * Лист превью эпизода.
 *
 * Лист, а не отдельные файлы: поштучное хранение превью означало бы десятки
 * тысяч запросов на одну эпизод и не давало бы атомарности на уровне страницы
 * матрицы кадров (research.md Т-03, FR-091).
 *
 * **Ключи листов отражают последовательность кадров без пропусков.** Лист `n`
 * содержит кадры от `n * framesPerSheet` до `min((n + 1) * framesPerSheet - 1,
 * frameCount - 1)`, нумерация с нуля (ADR-0001): между листами нет щели и нет
 * наложения, иначе один кадр показывался бы дважды, а другой — ни разу.
 *
 * @property videofileId эпизод-владелец
 * @property index номер листа, с нуля
 * @property firstFrame первый кадр листа
 * @property lastFrame последний кадр листа
 * @property layout раскладка листа
 * @property sheetCount сколько листов у эпизода всего
 * @property frameCount сколько кадров у эпизода всего
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class PreviewSheet(
    val videofileId: Long,
    val index: Int,
    val firstFrame: Int,
    val lastFrame: Int,
    val layout: PreviewLayout = PreviewLayout.STANDARD,
    val sheetCount: Int = 0,
    val frameCount: Int = 0,
) {
    init {
        require(index >= 0) { "Номер листа превью не может быть отрицательным, задано $index" }
        require(firstFrame >= 0) { "Первый кадр листа не может быть отрицательным, задано $firstFrame" }
        require(lastFrame >= firstFrame) {
            "Лист превью $index: последний кадр $lastFrame раньше первого $firstFrame"
        }
    }

    /** Номера кадров листа по возрастанию. */
    fun frameNumbers(): IntRange = firstFrame..lastFrame

    /** Сколько кадров на листе. */
    val frameNumbersCount: Int
        get() = lastFrame - firstFrame + 1

    /**
     * Положение кадра в раскладке листа.
     *
     * @param frameNumber номер кадра
     * @return номер строки и номер столбца
     * @throws IllegalArgumentException если кадр не принадлежит листу
     */
    fun positionOf(frameNumber: Int): CellPosition {
        require(frameNumber in firstFrame..lastFrame) {
            "Кадр $frameNumber не принадлежит листу $index (кадры $firstFrame..$lastFrame)"
        }
        val offset = frameNumber - firstFrame
        return CellPosition(row = offset / layout.columns, column = offset % layout.columns)
    }

    /**
     * Окончательный ключ листа в хранилище.
     *
     * Ключ содержит идентификатор эпизода и номер листа: по нему лист находится
     * однозначно, а по номеру листа — какие кадры он содержит.
     *
     * @return ключ объекта
     */
    fun finalKey(): String = "videofile/$videofileId/preview-sheets/${KEY_PREFIX}/%06d".format(index)

    /**
     * Временный ключ листа, в который он пишется до переноса.
     *
     * Письмо идёт во временный ключ, а перенос на окончательный — только
     * после того, как лист записан целиком: незавершённый лист не считается
     * готовым (FR-091, ADR-0009).
     *
     * @return временный ключ объекта
     */
    fun temporaryKey(): String = "videofile/$videofileId/preview-sheets/tmp/${KEY_PREFIX}/%06d.part".format(index)

    override fun toString(): String = "PreviewSheet(videofile=$videofileId, index=$index, frames=$firstFrame..$lastFrame)"

    companion object {
        /** Префикс ключа листа: версия раскладки входит в ключ намеренно. */
        const val KEY_PREFIX: String = "v1"

        /**
         * Сколько листов нужно эпизода с указанным числом кадров.
         *
         * @param frameCount число кадров эпизода
         * @param layout раскладка листа
         * @return число листов: округление вверх, последний лист может быть
         *   неполным, но он всё равно нужен — иначе последние кадры эпизода не
         *   было бы видно
         * @throws IllegalArgumentException если число кадров неположительно
         */
        fun sheetCount(
            frameCount: Int,
            layout: PreviewLayout = PreviewLayout.STANDARD,
        ): Int {
            require(frameCount > 0) { "Число кадров эпизода должно быть положительным, задано $frameCount" }
            return (frameCount + layout.framesPerSheet - 1) / layout.framesPerSheet
        }

        /**
         * Собирает лист эпизода по его номеру.
         *
         * @param videofileId эпизод-владелец
         * @param index номер листа, с нуля
         * @param frameCount число кадров эпизода
         * @param layout раскладка листа
         * @return лист с вычисленным диапазоном кадров
         * @throws IllegalArgumentException если номера листа нет
         */
        fun of(
            videofileId: Long,
            index: Int,
            frameCount: Int,
            layout: PreviewLayout = PreviewLayout.STANDARD,
        ): PreviewSheet {
            val count = sheetCount(frameCount, layout)
            require(index in 0 until count) {
                "Листа $index у эпизода из $frameCount кадров нет: листов $count (нумерация с нуля)"
            }
            val first = index * layout.framesPerSheet
            val last = minOf(first + layout.framesPerSheet - 1, frameCount - 1)
            return PreviewSheet(videofileId, index, first, last, layout, count, frameCount)
        }

        /**
         * Разбивает эпизод на листы.
         *
         * @param videofileId эпизод-владелец
         * @param frameCount число кадров эпизода
         * @param layout раскладка листа
         * @return все листы эпизода в порядке номеров
         */
        fun all(
            videofileId: Long,
            frameCount: Int,
            layout: PreviewLayout = PreviewLayout.STANDARD,
        ): List<PreviewSheet> = (0 until sheetCount(frameCount, layout)).map { of(videofileId, it, frameCount, layout) }
    }
}

/**
 * Место кадра в сетке листа.
 *
 * @property row строка, с нуля
 * @property column столбец, с нуля
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CellPosition(
    val row: Int,
    val column: Int,
)

/**
 * Сборка листа превью из готовых изображений кадров.
 *
 * Собиратель **не декодирует видео**: превью кадров уже нарезаны заданием
 * анализа, здесь они только укладываются в сетку. Это разделение выбрано
 * потому, что переделывать один лист дешевле, чем прогонять весь эпизод
 * заново (research.md Т-03).
 *
 * Лист пишется **во временный ключ** и переносится на окончательный после
 * того, как он записан целиком. Незавершённый лист не считается готовым и
 * никогда не показывается интерфейсу (FR-091).
 *
 * @property storage объектное хранилище артефактов
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class PreviewSheetBuilder(
    private val storage: ObjectStorage,
) {
    /**
     * Собирает лист и кладёт его в хранилище под окончательным ключом.
     *
     * @param sheet лист с вычисленным диапазоном кадров
     * @param frameImage выдаёт изображение кадра по его номеру; превью
     *   должно быть ровно по размеру ячейки, иначе лист растянется и
     *   перестанет быть сеткой
     * @return число байт записанного листа
     * @throws IOException если превью кадра недоступно или запись не удалась
     */
    @Throws(IOException::class)
    fun build(
        sheet: PreviewSheet,
        frameImage: (Int) -> InputStream?,
    ): Long {
        val image = BufferedImage(sheet.layout.sheetWidth, sheet.layout.sheetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            sheet.frameNumbers().forEach { frame ->
                val preview =
                    frameImage(frame)
                        ?: throw IOException(
                            "Превью кадра $frame для листа ${sheet.index} эпизода ${sheet.videofileId} недоступно",
                        )
                preview.use { stream ->
                    val cell =
                        ImageIO.read(stream)
                            ?: throw IOException("Превью кадра $frame не является изображением")
                    if (cell.width != sheet.layout.cellWidth || cell.height != sheet.layout.cellHeight) {
                        throw IOException(
                            "Превью кадра $frame имеет размер ${cell.width}x${cell.height}, " +
                                "а ячейка листа — ${sheet.layout.cellWidth}x${sheet.layout.cellHeight}: " +
                                "лист перестал бы быть сеткой",
                        )
                    }
                    val position = sheet.positionOf(frame)
                    graphics.drawImage(
                        cell,
                        position.column * sheet.layout.cellWidth,
                        position.row * sheet.layout.cellHeight,
                        null,
                    )
                }
            }
        } finally {
            graphics.dispose()
        }

        val bytes = ByteArrayOutputStream()
        ImageIO.write(image, formatName(sheet.layout.contentType), bytes)
        val temporary = sheet.temporaryKey()
        storage.put(temporary, bytes.toByteArray().inputStream(), sheet.layout.contentType, bytes.size().toLong())
        // Перенос выполняется после того, как лист записан целиком: до этого
        // момента по окончательному ключу лежит лишь обрывок файла.
        storage.move(temporary, sheet.finalKey())
        return bytes.size().toLong()
    }

    /**
     * Пишет изображение в поток.
     *
     * @param image изображение
     * @param contentType тип содержимого
     * @param out поток назначения
     */
    fun write(
        image: BufferedImage,
        contentType: String,
        out: OutputStream,
    ) {
        if (!ImageIO.write(image, formatName(contentType), out)) {
            throw IOException("Формат $contentType не поддерживается для записи листа превью")
        }
    }

    /** Имя формата по типу содержимого. */
    private fun formatName(contentType: String): String =
        when {
            contentType.contains("png") -> "png"
            contentType.contains("jpeg") || contentType.contains("jpg") -> "jpg"
            else -> "png"
        }
}
