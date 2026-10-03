package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.core.media.FrameChannel
import ru.svoemesto.syp.core.media.FrameFormat
import ru.svoemesto.syp.core.media.RawFrame

/**
 * Итог прохода по кадрам эпизода.
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
    fun note(videofileName: String): String {
        val base =
            "лица эпизода «$videofileName»: обработано кадров $frames, найдено лиц $faces" +
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
 * Приёмник рамок кадра.
 *
 * Проход по кадрам **не знает**, что будет с рамками: он их находит и
 * передаёт. Что с ними будет — запись в базу (T064), проверка пропорций
 * (T067), эмбеддинг (T066) — решает вызывающий. Разделение это не
 * украшение: проход по 88 643 кадрам не должен знать про персон, а запись
 * лиц не должна знать про канал кадров.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
fun interface FaceSink {
    /**
     * Принимает найденные лица одного кадра.
     *
     * @param frameNumber номер кадра
     * @param width ширина кадра
     * @param height высота кадра
     * @param found найденные лица в порядке отдачи детектором
     */
    fun accept(
        frameNumber: Int,
        width: Int,
        height: Int,
        found: List<DetectedFace>,
        frame: RawFrame,
    )
}

/**
 * Проход по кадрам эпизода: канал кадров плюс детектор.
 *
 * Класс делает ровно одну работу и не делает трёх:
 *
 * 1. **не пишет кадры** — ни одного файла, ни одной записи в объектное
 *    хранилище: кадр живёт в переиспользуемом буфере от поступления до
 *    возврата от детектора (FR-024, ADR-0002);
 * 2. **не пропускает кадры** — каждый кадр эпизода доходит до детектора,
 *    адаптивного шага нет (ADR-0002);
 * 3. **не выдаёт частичный результат за полный** — число обработанных
 *    кадров обязано совпасть с числом кадров эпизода, иначе проход падает с
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
     * Проводит эпизод через детектор.
     *
     * Детектор, который умеет закрываться, закрывается здесь же — в том числе
     * при отказе: оставленный процесс детекции держал бы видеокарту и помешал
     * бы следующему заданию.
     *
     * @param videofile эпизод: берутся путь к файлу, разрешение и число кадров
     * @param maxFrames ограничение числа кадров; `0` — весь эпизод
     * @param progress приёмник числа обработанных кадров
     * @param sink приёмник найденных рамок; `null` — рамки не сохраняются
     * @return итог прохода
     * @throws ru.svoemesto.syp.core.media.FrameChannelFailed если поток кадров
     *   оборвался, декодер вернул ненулевой код либо число обработанных
     *   кадров не совпало с числом кадров эпизода
     * @throws IllegalArgumentException если детектор вернул рамку вне кадра
     * @throws FaceDetectorFailed если программа детектора не поднялась, не
     *   ответила на кадр, оборвала ответ или завершилась с ненулевым кодом
     */
    fun scan(
        videofile: Videofile,
        maxFrames: Int = 0,
        progress: (Int) -> Unit = {},
        sink: FaceSink? = null,
    ): FaceScanResult {
        require(maxFrames >= 0) { "Ограничение числа кадров отрицательно: $maxFrames" }
        var failure: Throwable? = null
        try {
            return scanVideofile(videofile, maxFrames, progress, sink)
        } catch (refused: Throwable) {
            failure = refused
            throw refused
        } finally {
            // Детектор на видеокарте — это процесс, и оставленный процесс
            // держал бы карту и не отдал её следующему заданию. Заглушка не
            // закрывается: закрывать нечего.
            //
            // Отказ при закрытии не подменяет собой исходный: сначала был
            // отказ детектора, и именно он — виновник. Текст закрытия
            // добавляется к нему, а не затирает.
            try {
                (detector as? AutoCloseable)?.close()
            } catch (onClose: Throwable) {
                if (failure != null) {
                    failure.addSuppressed(onClose)
                } else {
                    throw onClose
                }
            }
        }
    }

    /**
     * Проводит эпизод через детектор без управления его жизненным циклом.
     *
     * @param videofile эпизод: берутся путь к файлу, разрешение и число кадров
     * @param maxFrames ограничение числа кадров; `0` — весь эпизод
     * @param progress приёмник числа обработанных кадров
     * @param sink приёмник найденных рамок; `null` — рамки не сохраняются
     * @return итог прохода
     */
    private fun scanVideofile(
        videofile: Videofile,
        maxFrames: Int,
        progress: (Int) -> Unit,
        sink: FaceSink?,
    ): FaceScanResult {
        require(maxFrames >= 0) { "Ограничение числа кадров отрицательно: $maxFrames" }
        val format = FrameFormat(videofile.width, videofile.height)
        val expected = if (maxFrames > 0) maxFrames else videofile.frameCount
        var faces = 0
        var framesWithFaces = 0
        var processed = 0

        val result =
            channel.scan(
                sourcePath = videofile.sourcePath,
                format = format,
                maxFrames = maxFrames,
                onFrame = { frame ->
                    val found = detector.detect(frame)
                    found.forEach { it.requireInside(format.width, format.height) }
                    if (found.isNotEmpty()) {
                        faces += found.size
                        framesWithFaces++
                    }
                    // Приёмник вызывается на каждом кадре, в том числе на
                    // кадре без лиц: иначе проход по кадрам эпизода, где лиц
                    // почти нет, выглядел бы как «кадров нет вообще».
                    sink?.accept(frame.number, format.width, format.height, found, frame)
                    processed = frame.number + 1
                    progress(processed)
                },
            )

        if (result.stoppedEarly) {
            // Ограничение кадров — приём замеров на префиксе. Для задания
            // FACES оно не используруется, но вызывающий должен знать, что
            // прогон неполон: иначе его приняли бы за полный охват эпизода.
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
                "Обработано кадров $processed, а в эпизоде «${videofile.name}» их ${videofile.frameCount}. " +
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
