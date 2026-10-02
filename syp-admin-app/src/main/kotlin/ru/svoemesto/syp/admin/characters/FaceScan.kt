package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.core.media.FrameChannel
import ru.svoemesto.syp.core.media.FrameFormat

/**
 * Итог прохода по кадрам серии.
 *
 * @property frames сколько кадров прочитано и передано детектору
 * @property framesWithFaces сколько кадров дали хотя бы одно лицо
 * @property faces сколько лиц найдено всего
 * @property detectorKey идентификатор детектора, которым получен результат
 * @property detectorIsStub заглушка ли это детектор
 * @property elapsedMillis сколько миллисекунд занял проход
 * @property format формат кадра в канале
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FaceScanResult(
    val frames: Int,
    val framesWithFaces: Int,
    val faces: Int,
    val detectorKey: String,
    val detectorIsStub: Boolean,
    val elapsedMillis: Long,
    val format: FrameFormat,
) {
    /**
     * Текст результата для оператора.
     *
     * Текст прямо называет заглушку, если это она: иначе «лиц не найдено»
     * выглядело бы как вывод детектора, а вывода не было.
     *
     * @return пояснение по-русски
     */
    fun note(seriesName: String): String {
        val base =
            "лица серии «$seriesName»: обработано кадров $frames, найдено лиц $faces" +
                (if (framesWithFaces > 0) ", кадров с лицами $framesWithFaces" else "") +
                ", время ${"%.1f".format(elapsedMillis / 1000.0)} с"
        return if (detectorIsStub) {
            "$base. Детектор — ЗАГЛУШКА «$detectorKey»: лица не искались, результат " +
                "не является детекцией (задача T063 не выполнена)"
        } else {
            "$base. Детектор «$detectorKey»"
        }
    }
}

/**
 * Проход по кадрам серии: канал кадров плюс детектор.
 *
 * Класс делает ровно одну работу и не делает трёх:
 *
 * 1. **не пишет кадры** — ни одного файла, ни одной записи в объектное
 *    хранилище: кадр живёт в переиспользуемом буфере от поступления до
 *    возврата от детектора (FR-024, ADR-0002);
 * 2. **не пропускает кадры** — каждый кадр серии доходит до детектора,
 *    адаптивного шага нет (ADR-0002);
 * 3. **не выдаёт частичный результат за полный** — число обработанных
 *    кадров обязано совпасть с числом кадров серии, иначе проход падает с
 *    текстом (SC-005, T073).
 *
 * Рамки на этом шаге проверяются на попадание в кадр, но **не сохраняются**:
 * хранение лиц — отдельная задача (T064), и до неё в базе не появляется ни
 * одной строки лица.
 *
 * @property channel канал сырых кадров
 * @property detector детектор лиц
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceScan(
    private val channel: FrameChannel,
    private val detector: FaceDetector,
) {
    /**
     * Проводит серию через детектор.
     *
     * @param series серия: берутся путь к файлу, разрешение и число кадров
     * @param maxFrames ограничение числа кадров; `0` — вся серия
     * @param progress приёмник числа обработанных кадров
     * @return итог прохода
     * @throws ru.svoemesto.syp.core.media.FrameChannelFailed если поток кадров
     *   оборвался, декодер вернул ненулевой код либо число обработанных
     *   кадров не совпало с числом кадров серии
     * @throws IllegalArgumentException если детектор вернул рамку вне кадра
     */
    fun scan(
        series: Series,
        maxFrames: Int = 0,
        progress: (Int) -> Unit = {},
    ): FaceScanResult {
        require(maxFrames >= 0) { "Ограничение числа кадров отрицательно: $maxFrames" }
        val format = FrameFormat(series.width, series.height)
        val expected = if (maxFrames > 0) maxFrames else series.frameCount
        var faces = 0
        var framesWithFaces = 0
        var processed = 0

        val result =
            channel.scan(
                sourcePath = series.sourcePath,
                format = format,
                maxFrames = maxFrames,
                onFrame = { frame ->
                    val found = detector.detect(frame)
                    found.forEach { it.requireInside(format.width, format.height) }
                    if (found.isNotEmpty()) {
                        faces += found.size
                        framesWithFaces++
                    }
                    processed = frame.number + 1
                    progress(processed)
                },
            )

        if (result.stoppedEarly) {
            // Ограничение кадров — приём замеров на префиксе. Для задания
            // FACES оно не используруется, но вызывающий должен знать, что
            // прогон неполон: иначе его приняли бы за полный охват серии.
            return FaceScanResult(
                frames = result.frames,
                framesWithFaces = framesWithFaces,
                faces = faces,
                detectorKey = detector.key,
                detectorIsStub = detector.isPlaceholder,
                elapsedMillis = result.elapsedMillis,
                format = format,
            )
        }

        if (processed != expected) {
            throw ru.svoemesto.syp.core.media.FrameChannelFailed(
                "Обработано кадров $processed, а в серии «${series.name}» их ${series.frameCount}. " +
                    "Кадры не пропускаются: результат неполным быть не может (FR-030, SC-005)",
            )
        }
        return FaceScanResult(
            frames = processed,
            framesWithFaces = framesWithFaces,
            faces = faces,
            detectorKey = detector.key,
            detectorIsStub = detector.isPlaceholder,
            elapsedMillis = result.elapsedMillis,
            format = format,
        )
    }
}
