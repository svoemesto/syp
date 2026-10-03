package ru.svoemesto.syp.core.media

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Сколько знаков вывода декодера попадает в сообщение об обрыве. */
private const val DIAGNOSTICS_TAIL: Int = 400

/**
 * Формат кадра в канале.
 *
 * Формат задан явно, потому что от него зависит и команда декодеру, и
 * проверка рамки лица: рамка, вышедшая за пределы кадра, отвергается, а
 * «пределы кадра» определены только форматом.
 *
 * @property width ширина кадра в пикселях
 * @property height высота кадра в пикселях
 * @property pixelFormat имя формата пикселей для декодера, `bgr24` по умолчанию
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FrameFormat(
    val width: Int,
    val height: Int,
    val pixelFormat: String = BGR24,
) {
    init {
        require(width > 0 && height > 0) { "Размер кадра неположителен: ${width}x$height" }
        require(pixelFormat.isNotBlank()) { "Формат пикселей не задан" }
    }

    /** Сколько байт занимает один пиксель. */
    val bytesPerPixel: Int
        get() =
            when (pixelFormat) {
                BGR24, RGB24 -> 3
                GRAY -> 1
                else -> throw IllegalArgumentException(
                    "Формат пикселей $pixelFormat каналом не поддерживается: " +
                        "поддерживаются $BGR24, $RGB24 и $GRAY",
                )
            }

    /** Сколько байт занимает один кадр целиком. */
    val frameBytes: Int
        get() = width * height * bytesPerPixel

    companion object {
        /** Три байта на пиксель, порядок каналов синий-зелёный-красный. */
        const val BGR24: String = "bgr24"

        /** Три байта на пиксель, порядок каналов красный-зелёный-синий. */
        const val RGB24: String = "rgb24"

        /** Один байт на пиксель, оттенки серого. */
        const val GRAY: String = "gray"
    }
}

/**
 * Кадр, переданный потребителю.
 *
 * **Буфер переиспользуется от кадра к кадру.** Это не оптимизация ради
 * оптимизации: один кадр `GOT.S01E01` — это 6 220 800 байт, а копирование его
 * на каждом из 88 643 кадров означало бы 551 ГБ лишней работы памяти на одну
 * эпизод. Потребитель обязан использовать кадр до возврата управления и не
 * хранить ссылку на буфер.
 *
 * @property number номер кадра с нуля, сквозному по эпизоду (ADR-0001)
 * @property data буфер кадра; длина равна [format].frameBytes
 * @property format формат кадра
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class RawFrame(
    val number: Int,
    val data: ByteArray,
    val format: FrameFormat,
)

/**
 * Итог прохода по кадрам.
 *
 * @property frames сколько кадров прочитано и передано потребителю
 * @property bytes сколько байт прочитано
 * @property stoppedEarly остановлен ли проход по лимиту кадров
 * @property elapsedMillis сколько миллисекунд занял проход
 * @property diagnostics хвост вывода декодера: текст ошибки не теряется
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FrameChannelResult(
    val frames: Int,
    val bytes: Long,
    val stoppedEarly: Boolean,
    val elapsedMillis: Long,
    val diagnostics: String,
)

/**
 * Канал сырых кадров от декодера к потребителю.
 *
 * Класс решает задачу, ради которой существует: **кадр полного разрешения
 * идёт в процесс потоком и не остаётся на диске** (FR-024, ADR-0002).
 * Ни одного файла кадров класс не создаёт — буфер один, он переиспользуется.
 *
 * **Ошибка декодера не может потеряться.** Это требование constitution IV.2,
 * и здесь оно выполнено иначе, чем в [ExternalProgram]:
 *
 * - `redirectErrorStream(true)` для потока кадров **неприменим**: кадры —
 *   это двоичные данные, и строка журнала, подмешанная в поток, попала бы
 *   внутрь картинки. Молча испорченный кадр — ровно тот дефект, ради
 *   которого правило и написано;
 * - поэтому вывод ошибок читается **отдельным потоком** в ограниченный
 *   буфер и целиком попадает в текст ошибки, а хвост возвращается вместе с
 *   итогом прохода. Ни одна ошибка не остаётся незамеченной — она видна
 *   оператору в задании (FR-092);
 * - код завершения проверяется **всегда**, когда проход не прерван по
 *   лимиту кадров; оборванный на середине кадр — ошибка, а не «нулевой
 *   результат» (SC-005).
 *
 * **Адаптивного шага нет**: кадры читаются подряд, без пропусков (ADR-0002).
 *
 * @property ffmpegPath путь к программе-декодеру; приходит из конфигурации
 *   развёртывания, а не из данных задания (ADR-0010)
 * @property timeout сколько ждать завершения декодера
 * @property stallTimeout сколько ждать очередных данных от декодера; живой, но
 *   молчащий декодер обязан обрываться отказом, а не висять (T-189)
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FrameChannel(
    private val ffmpegPath: String,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    /**
     * Сколько ждать очередных данных от декодера, прежде чем считать его
     * зависшим.
     */
    private val stallTimeout: Duration = DEFAULT_STALL_TIMEOUT,
) {
    /**
     * Читает кадры эпизода и передаёт их потребителю по одному.
     *
     * @param sourcePath путь к файлу эпизода
     * @param format формат кадра
     * @param maxFrames ограничение числа кадров; `0` — весь эпизод. Ненулевое
     *   значение применяется только замерами на префиксе: задание `FACES`
     *   всегда передаёт `0`, иначе часть кадров осталась бы без лица
     * @param onFrame потребитель кадра; обязан вернуться до следующего кадра
     * @return итог прохода
     * @throws FrameChannelFailed если декодер не запустился, завершился с
     *   ненулевым кодом, отдал неполный кадр или не завершился вовремя
     */
    fun scan(
        sourcePath: String,
        format: FrameFormat,
        maxFrames: Int = 0,
        onFrame: (RawFrame) -> Unit,
    ): FrameChannelResult {
        require(maxFrames >= 0) { "Ограничение числа кадров отрицательно: $maxFrames" }
        val startedAt = System.nanoTime()
        val arguments =
            listOf(
                "-hide_banner",
                "-nostdin",
                "-loglevel",
                "error",
                "-i",
                sourcePath,
                "-f",
                "rawvideo",
                "-pix_fmt",
                format.pixelFormat,
                "-",
            )
        val builder = ProcessBuilder(listOf(ffmpegPath) + arguments)
        // Намеренное отступление от правила «вывод и ошибки в один поток».
        // Причина записана в класса: кадры — двоичные данные, и текст ошибки
        // в таком потоке испортил бы изображение. Ошибка при этом не теряется:
        // её читает отдельный поток и возвращает в тексте отказа.
        builder.redirectErrorStream(false)
        val process =
            try {
                builder.start()
            } catch (failure: Exception) {
                throw FrameChannelFailed(
                    "Не удалось запустить декодер «$ffmpegPath»: ${failure.message}. " +
                        "Проверьте путь в конфигурации развёртывания (ADR-0010)",
                )
            }

        val diagnostics = Diagnostics()
        val reader = Thread({ diagnostics.read(process.errorStream) }, "syp-frame-channel-stderr")
        reader.isDaemon = true
        reader.start()

        // Поток кадров берём отдельно: сторожевой отсчёт обязан иметь
        // возможность закрыть его сам. Пока декодер жив и молчит, чтение
        // блокировано, и без закрытия потока отказ не наступит никогда.
        val frameStream = process.inputStream
        // Живой декодер, который не отдаёт ни кадра, ни байта, оставлял
        // задание в состоянии «идёт работа» навсегда: состояние утверждало
        // работу, и ответ API говорил то же. Видно это было только сверкой
        // трёх фактов. Отсчёт ведётся не на кадр, а на данные: медленный, но
        // живой декодер обязан дожить, поэтому отсчёт сбрасывается на каждом
        // прочитанном куске.
        val lastProgress =
            java.util.concurrent.atomic
                .AtomicLong(System.nanoTime())
        val stalled =
            java.util.concurrent.atomic
                .AtomicBoolean(false)
        val stallGuard =
            Thread({
                while (process.isAlive && !stalled.get()) {
                    Thread.sleep(STALL_POLL_MILLIS)
                    val idleNanos = System.nanoTime() - lastProgress.get()
                    if (process.isAlive && idleNanos > stallTimeout.toNanos()) {
                        stalled.set(true)
                        // Убивать только сам декодер мало: его потомки держат
                        // конец трубы, и чтение кадров осталось бы заблокированным
                        // на живом, но молчащем потомке — задание снова висело бы
                        // в состоянии «идёт работа».
                        process.toHandle().descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                        runCatching { frameStream.close() }
                        return@Thread
                    }
                }
            }, "syp-frame-channel-stall")
        stallGuard.isDaemon = true
        stallGuard.start()

        val frameBytes = format.frameBytes
        val buffer = ByteArray(frameBytes)
        var frames = 0
        var read = 0
        var total = 0L
        var stoppedEarly = false
        try {
            frameStream.use { stream ->
                while (true) {
                    val count = stream.read(buffer, read, frameBytes - read)
                    if (count < 0) {
                        break
                    }
                    lastProgress.set(System.nanoTime())
                    read += count
                    total += count
                    if (read == frameBytes) {
                        onFrame(RawFrame(frames, buffer, format))
                        frames++
                        read = 0
                        if (maxFrames > 0 && frames >= maxFrames) {
                            stoppedEarly = true
                            break
                        }
                    }
                }
            }
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw FrameChannelFailed("Декодирование прервано: поток воркера остановлен")
        } catch (failure: FrameChannelFailed) {
            process.destroyForcibly()
            throw failure
        } catch (refused: FrameConsumerFailed) {
            // Отказ потребителя — не отказ канала. «Рамка вне кадра» и «детектор
            // не поднялся» говорят о потребителе, а не о потоке, и заворачивать
            // их в «поток оборвался» значило бы потерять текст виновника.
            // Процесс при этом всё равно уничтожается: дочитывать эпизод после
            // отказа незачем.
            process.destroyForcibly()
            throw refused
        } catch (failure: IllegalArgumentException) {
            // Отказ потребителя — не отказ канала. Рамка вне кадра или
            // перевёрнутая рамка говорят о детекторе, а не о потоке, и
            // заворачивать их в «поток оборвался» значило бы потерять текст
            // виновника. Процесс при этом всё равно уничтожается: дочитывать
            // эпизод после отказа незачем.
            process.destroyForcibly()
            throw failure
        } catch (failure: Exception) {
            process.destroyForcibly()
            // Закрытие потока самим сторожем выглядит как «поток оборвался»,
            // и без этой проверки отказ о зависании терялся бы: виноват
            // декодер, а не поток.
            if (stalled.get()) {
                throw stalledFailure(frames, diagnostics)
            }
            // Причина в сообщение попадала как `failure.message`, а у половины
            // исключений сообщение пустое, и в журнал уходило «оборвался на кадре
            // N: null» — разбирать было нечем. Класс исключения и вывод
            // декодера обязаны быть в тексте: иначе любой обрыв читается одинаково.
            throw FrameChannelFailed(
                "Поток кадров оборвался на кадре $frames: " +
                    "${failure.javaClass.simpleName}" +
                    (failure.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "") +
                    ". Вывод декодера: ${diagnostics.text().take(DIAGNOSTICS_TAIL).trim()}. " +
                    "Кадры на диск не пишутся, результата у задания нет",
            )
        }

        if (stalled.get()) {
            // Декодер уничтожен сторожевым отсчётом, поэтому оставшийся
            // неполный кадр — следствие зависания, а не обрыв потока. Первым
            // идёт отказ о зависании: он называет кадр, на котором
            // остановились данные, и вывод программы.
            process.destroyForcibly()
            stallGuard.interrupt()
            reader.join(READER_JOIN_MILLIS)
            throw stalledFailure(frames, diagnostics)
        }

        stallGuard.interrupt()

        if (stoppedEarly) {
            // Проход остановлен намеренно, по лимиту кадров: декодер доживать
            // не должен, а его код завершения после уничтожения процесса
            // ничего не значит и проверке не подлежит.
            process.destroyForcibly()
            reader.join(READER_JOIN_MILLIS)
            return FrameChannelResult(frames, total, stoppedEarly, elapsedSince(startedAt), diagnostics.text())
        }

        if (read > 0) {
            process.destroyForcibly()
            throw FrameChannelFailed(
                "Поток кадров оборвался на кадре $frames: пришло $read байт из $frameBytes. " +
                    "Неполный кадр результатом не является (SC-005). " +
                    "Вывод декодера: ${diagnostics.text()}",
            )
        }

        val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            throw FrameChannelFailed(
                "Декодер «$ffmpegPath» не завершился за ${timeout.toMinutes()} минут на $frames кадрах. " +
                    "Вывод декодера: ${diagnostics.text()}",
            )
        }
        reader.join(READER_JOIN_MILLIS)
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw FrameChannelFailed(
                "Декодер «$ffmpegPath» завершился с кодом $exitCode на кадре $frames. " +
                    "Вывод декодера: ${diagnostics.text()}",
            )
        }
        return FrameChannelResult(frames, total, stoppedEarly, elapsedSince(startedAt), diagnostics.text())
    }

    /**
     * Отказ о зависшем декодере.
     *
     * @param frames сколько кадров успело прийти
     * @param diagnostics хвост вывода декодера
     * @return отказ с номером кадра и выводом программы
     */
    private fun stalledFailure(
        frames: Int,
        diagnostics: Diagnostics,
    ): FrameChannelFailed =
        FrameChannelFailed(
            "Декодер «$ffmpegPath» жив, но не отдаёт данных дольше ${stallTimeout.seconds} с " +
                "на кадре $frames: ни кадра, ни байта. Зависший декодер уничтожен. " +
                "Вывод декодера: ${diagnostics.text()}",
        )

    /**
     * Сколько миллисекунд прошло с указанного момента.
     *
     * @param startedAt момент начала в наносекундах
     * @return прошедшее время в миллисекундах
     */
    private fun elapsedSince(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    /**
     * Буфер хвоста вывода декодера.
     *
     * Хвост нужен для текста ошибки, а не для разбора: полный журнал `ffmpeg`
     * на длинном эпизоде занимает десятки мегабайт, и хранить его в памяти
     * задания незачем. Хранятся последние [KEEP_BYTES] байт — там всегда
     * находится причина отказа.
     *
     * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
     */
    private class Diagnostics {
        private val buffer = ByteArrayOutputStream()

        /**
         * Читает поток ошибок до конца.
         *
         * @param stream поток ошибок декодера
         */
        fun read(stream: InputStream) {
            val chunk = ByteArray(CHUNK)
            stream.use { input ->
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) {
                        break
                    }
                    synchronized(buffer) {
                        buffer.write(chunk, 0, count)
                        if (buffer.size() > KEEP_BYTES) {
                            val excess = buffer.size() - KEEP_BYTES
                            val tail = buffer.toByteArray().copyOfRange(excess, buffer.size())
                            buffer.reset()
                            buffer.write(tail, 0, tail.size)
                        }
                    }
                }
            }
        }

        /** Хвост вывода декодера одной строкой. */
        fun text(): String = synchronized(buffer) { buffer.toString(Charsets.UTF_8).trim() }
    }

    companion object {
        /** Сколько байт вывода декодера удерживается для текста ошибки. */
        const val KEEP_BYTES: Int = 64 * 1024

        /** Размер порции при чтении вывода декодера. */
        private const val CHUNK: Int = 8 * 1024

        /** Сколько ждать конца потока вывода декодера. */
        private const val READER_JOIN_MILLIS: Long = 5_000

        /** Как часто сторожевой отсчёт смотрит, не идёт ли время. */
        private const val STALL_POLL_MILLIS = 2_000L

        /** Сколько ждать после разрушающего прерывания декодера. */
        private const val DESTROY_TIMEOUT_MILLIS: Long = 10_000

        /** Таймаут по умолчанию: эпизод длинный, зависание — нет. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofHours(6)

        /**
         * Сколько ждать очередных данных от декодера, прежде чем считать его
         * зависшим.
         *
         * Две минуты: полный кадр в 4K по bgr24 — около 24 МБ, и он обязан
         * пройти из трубы быстро; минуты хватает с запасом для медленного
         * диска, а зависание оператор видит за минуты, а не через шесть часов
         * общего ожидания.
         */
        val DEFAULT_STALL_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}

/**
 * Потребитель кадра отказался принимать кадр.
 *
 * Отдельный тип нужен, чтобы отказ потребителя не выглядел отказом канала:
 * «детектор не поднялся» и «поток кадров оборвался» — разные причины с
 * разными действиями оператора, и заворачивать первую во вторую значило бы
 * потерять виновника. Канал такой отказ пробрасывает как есть.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
open class FrameConsumerFailed(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Поток кадров не дал результата.
 *
 * Отдельный тип отличается от «программа не запустилась»: здесь работа
 * началась, но кадров не дала. Текст ошибки обязателен — он попадает в
 * задание, и оператор видит его в очереди (FR-092).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FrameChannelFailed(
    message: String,
) : RuntimeException(message)
