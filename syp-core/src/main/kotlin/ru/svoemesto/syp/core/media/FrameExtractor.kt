package ru.svoemesto.syp.core.media

import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Извлечение одного кадра из видео по его номеру.
 *
 * **Зачем отдельным классом, а не через [FrameChannel].** Каналь читает кадры
 * подряд и служит проходу детекции, где нужен весь поток. Оператор же хочет
 * посмотреть один кадр — часто кадр с номером в десятки тысяч, и последовательное
 * чтение до него шло бы минутами. Здесь переход по времени, а уже он отдаёт
 * нужный кадр сразу.
 *
 * **Кадры на диск не пишутся** (ADR-0002): кадр живёт в памяти до конца ответа.
 *
 * **Попадание в кадр точное.** Переход идёт на середину кадра по времени его
 * показа, а не на его начало: при переходе ровно на границу ffmpeg отдаёт
 * соседний кадр, и оператор увидел бы не тот кадр, который выбрал.
 *
 * @property ffmpegPath путь к программе-декодеру; приходит из конфигурации
 *   развёртывания, а не из данных (ADR-0010)
 * @property timeout сколько ждать завершения декодера
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FrameExtractor(
    private val ffmpegPath: String,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    /**
     * Отдаёт кадр в виде масштабированной картинки.
     *
     * @param sourcePath путь к файлу эпизода
     * @param frameNumber номер кадра, с нуля
     * @param timeBaseNum числитель базы времени
     * @param timeBaseDen знаменатель базы времени
     * @param frameCount сколько кадров в эпизоде
     * @param widthTarget ширина картинки; `0` — без масштабирования
     * @return байты картинки
     * @throws FrameExtractionFailed если декодер не запустился, кадра нет или
     *   он не отдался
     */
    fun extract(
        sourcePath: String,
        frameNumber: Int,
        timeBaseNum: Int,
        timeBaseDen: Int,
        frameCount: Int,
        widthTarget: Int = 0,
    ): ByteArray {
        require(timeBaseNum > 0 && timeBaseDen > 0) {
            "База времени эпизода вырождена: $timeBaseNum/$timeBaseDen"
        }
        require(frameNumber in 0 until frameCount) {
            "Кадра $frameNumber в эпизоде нет: их $frameCount, нумерация с нуля"
        }
        // Середина показа кадра: переход на середину не сбивается на соседний.
        val seconds = (frameNumber + 0.5) * timeBaseNum.toDouble() / timeBaseDen
        val arguments =
            buildList {
                add("-hide_banner")
                add("-nostdin")
                add("-loglevel")
                add("error")
                // Переход идёт до входа, фильтр масштаба — после: `-vf` до `-i`
                // ffmpeg не применяет и отвечает «Option vf cannot be applied
                // to input url», то есть не кадр, а отказ.
                add("-ss")
                add("%.3f".format(java.util.Locale.ROOT, seconds))
                add("-i")
                add(sourcePath)
                if (widthTarget > 0) {
                    add("-vf")
                    add("scale=$widthTarget:-2")
                }
                add("-frames:v")
                add("1")
                add("-f")
                add("image2")
                add("-c:v")
                add(ENCODER)
                add("-")
            }
        val process =
            try {
                ProcessBuilder(listOf(ffmpegPath) + arguments).redirectErrorStream(false).start()
            } catch (failure: Exception) {
                throw FrameExtractionFailed(
                    "Не удалось запустить декодер «$ffmpegPath»: ${failure.message}. " +
                        "Проверьте путь в конфигурации развёртывания (ADR-0010)",
                )
            }
        return try {
            val bytes = process.inputStream.use { it.readBytes() }
            val errors = process.errorStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                throw FrameExtractionFailed(
                    "Декодер не отдал кадр $frameNumber за ${timeout.toMinutes()} минут",
                )
            }
            if (bytes.isEmpty()) {
                throw FrameExtractionFailed(
                    "Кадра $frameNumber декодер не отдал: ${errors.trim().take(300).ifEmpty { "вывод пуст" }}",
                )
            }
            bytes
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }

    private companion object {
        /** Сколько ждать завершения декодера одного кадра. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)

        /** Кодек картинки: по кадру в масштабе 720 он весит десятки килобайт. */
        const val ENCODER: String = "mjpeg"
    }
}

/** Отказ извлечения кадра: кадра нет, декодер молчит или упал. */
class FrameExtractionFailed(
    message: String,
) : RuntimeException(message)
