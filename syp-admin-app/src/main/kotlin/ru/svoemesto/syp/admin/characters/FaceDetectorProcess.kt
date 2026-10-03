package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.media.FrameFormat
import ru.svoemesto.syp.core.media.RawFrame
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Программа детектора лиц, запущенная из контейнера админки.
 *
 * **Среда исполнения — внутри контейнера.** Программа не приходит с машины
 * пользователя и не запускается вне стека: путь к ней и путь к модели
 * читаются из конфигурации развёртывания, секретов в них нет, версии среды
 * зафиксированы в образе (ADR-0010, ограничения 2 и 3). Контейнеров стека
 * ровно шесть, поэтому отдельный воркер детекции заводить нельзя: программа
 * запускается тем же бэкендом, который и так держит очередь заданий.
 *
 * Обмен идёт в обе стороны, поэтому программа живёт от начала прохода до его
 * конца и умирает вместе с ним: один процесс на одну эпизод, а не на кадр.
 *
 * **Об отступлении от `redirectErrorStream(true)`.** Правило обязательно
 * (ADR-0010, ограничение 4), но протокол здесь — двоичный: по стандартному
 * выводу идут заголовки и рамки, и строка журнала в этом потоке испортила бы
 * ответ. Отступление ровно то же, что и в
 * [ru.svoemesto.syp.core.media.FrameChannel], и смысл правила сохранён: текст
 * ошибки читает отдельный поток в ограниченный буфер, попадает в текст отказа
 * и виден оператору в задании (FR-092). Код завершения проверяется всегда.
 *
 * @property programPath путь к программе; приходит из конфигурации
 * @property arguments аргументы запуска: модель, провайдер, пороги
 * @property frameTimeout сколько ждать ответа на один кадр
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceDetectorProcess(
    private val programPath: String,
    private val arguments: List<String>,
    private val frameTimeout: Duration = DEFAULT_FRAME_TIMEOUT,
) : Closeable {
    private val answers: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val greetings: BlockingQueue<String> = LinkedBlockingQueue()
    private val diagnostics = Diagnostics()
    private val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    private var process: Process? = null
    private var input: OutputStream? = null
    private var reader: Thread? = null

    /** Описание программы из строки приветствия: модель, провайдер, версии. */
    var handshake: String = ""
        private set

    /** Запущена ли программа сейчас. */
    val isRunning: Boolean
        get() = process != null

    /**
     * Запускает программу и читает её строку приветствия.
     *
     * Запуск до первого кадра — единственное место, где видно, что среда не
     * поднялась: нет видеокарты, нет библиотеки провайдера, нет модели. Отказ
     * здесь означает понятный текст в задании, а не «лиц не найдено» через
     * двадцать минут работы.
     *
     * @return текст приветствия программы
     * @throws FaceDetectorFailed если программа не запустилась, не ответила
     *   приветствием или завершилась с ненулевым кодом при старте
     */
    fun start(): String {
        check(process == null) { "Программа детектора уже запущена" }
        val builder = ProcessBuilder(listOf(programPath) + arguments)
        // Намеренное отступление от правила «вывод и ошибки в один поток»:
        // причина записана в классе. Ошибка при этом не теряется — её читает
        // отдельный поток и возвращает в тексте отказа.
        builder.redirectErrorStream(false)
        val started =
            try {
                builder.start()
            } catch (failure: Exception) {
                throw FaceDetectorFailed(
                    "Не удалось запустить программу детектора «$programPath»: ${failure.message}. " +
                        "Проверьте путь в конфигурации развёртывания (ADR-0010)",
                    failure,
                )
            }
        process = started
        input = started.outputStream
        val errors = Thread({ diagnostics.read(started.errorStream) }, "syp-face-detector-stderr")
        errors.isDaemon = true
        errors.start()

        // Поток чтения запускается первым: он читает и строку приветствия, и
        // двоичные ответы, потому что поток вывода программы один на оба.
        val reader = Thread({ readProgram(running = started, stream = started.inputStream) }, "syp-face-detector-read")
        reader.isDaemon = true
        reader.start()
        this.reader = reader

        val greeting = readGreeting(started)
        handshake = greeting
        return greeting
    }

    /**
     * Отправляет кадр программе и ждёт ответа.
     *
     * @param frame кадр полного разрешения из переиспользуемого буфера
     * @return найденные лица в координатах кадра полного разрешения
     * @throws FaceDetectorFailed если программа не ответила за отведённое
     *   время, ответила не на тот кадр или вернула оборванный ответ
     */
    fun detect(frame: RawFrame): List<DetectedFace> {
        val running = process ?: throw FaceDetectorFailed("Программа детектора не запущена")
        val stream = input ?: throw FaceDetectorFailed("Программа детектора не запущена")
        val code = formatCode(frame.format)
        header.clear()
        header.putInt(frame.number)
        header.putInt(frame.format.width)
        header.putInt(frame.format.height)
        header.putInt(code)
        try {
            stream.write(header.array())
            stream.write(frame.data)
            stream.flush()
        } catch (failure: Exception) {
            throw FaceDetectorFailed(
                "Кадр ${frame.number} не отправлен программе детектора: ${failure.message}. " +
                    "Вывод программы: ${diagnostics.text()}",
                failure,
            )
        }

        val answer = awaitAnswer(frame.number, running)
        if (answer.isEmpty()) {
            throw FaceDetectorFailed(
                "Ответ на кадр ${frame.number} оборван: программа детектора закрыла поток, " +
                    "не дописав ответ. Вывод программы: ${diagnostics.text()}",
            )
        }
        if (answer.size < ANSWER_HEADER_BYTES) {
            throw FaceDetectorFailed(
                "Ответ на кадр ${frame.number} оборван: ${answer.size} байт из $ANSWER_HEADER_BYTES. " +
                    "Вывод программы: ${diagnostics.text()}",
            )
        }
        val number = littleEndianInt(answer, HEADER_OFFSET_NUMBER)
        val count = littleEndianInt(answer, HEADER_OFFSET_COUNT)
        if (number != frame.number) {
            throw FaceDetectorFailed(
                "Программа детектора ответила на кадр $number, а ждали ${frame.number}: " +
                    "кадры потеряли порядок (ADR-0001)",
            )
        }
        val expected = ANSWER_HEADER_BYTES + count * ANSWER_FACE_BYTES
        if (answer.size < expected) {
            throw FaceDetectorFailed(
                "Ответ на кадр $number оборван: ${answer.size} байт из $expected. " +
                    "Вывод программы: ${diagnostics.text()}",
            )
        }
        val faces = ArrayList<DetectedFace>(count)
        val buffer =
            ByteBuffer
                .wrap(answer, ANSWER_HEADER_BYTES, count * ANSWER_FACE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        repeat(count) {
            val x1 = buffer.short.toInt()
            val y1 = buffer.short.toInt()
            val x2 = buffer.short.toInt()
            val y2 = buffer.short.toInt()
            val confidence = buffer.float.toDouble()
            val points =
                FacePoints(
                    eyeLeft = IntPoint(buffer.short.toInt(), buffer.short.toInt()),
                    eyeRight = IntPoint(buffer.short.toInt(), buffer.short.toInt()),
                    nose = IntPoint(buffer.short.toInt(), buffer.short.toInt()),
                    mouthLeft = IntPoint(buffer.short.toInt(), buffer.short.toInt()),
                    mouthRight = IntPoint(buffer.short.toInt(), buffer.short.toInt()),
                )
            faces.add(DetectedFace(x1, y1, x2, y2, confidence, points))
        }
        return faces
    }

    /**
     * Закрывает программу и проверяет её код завершения.
     *
     * Закрытие штатное: программа читает конец потока кадров и выходит с
     * ненулевым кодом, если не смогла обработать кадр. Такой код — отказ
     * задания с текстом, а не «нулевой результат» (SC-005, FR-092).
     */
    override fun close() {
        val running = process ?: return
        process = null
        input = null
        runCatching { running.outputStream.close() }
        val finished = running.waitFor(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        if (!finished) {
            running.destroyForcibly()
            running.waitFor(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            throw FaceDetectorFailed(
                "Программа детектора «$programPath» не завершилась за ${CLOSE_TIMEOUT.toSeconds()} с " +
                    "после конца эпизода. Вывод программы: ${diagnostics.text()}",
            )
        }
        reader?.join(READER_JOIN_MILLIS)
        val code = running.exitValue()
        if (code != 0) {
            throw FaceDetectorFailed(
                "Программа детектора «$programPath» завершилась с кодом $code. " +
                    "Вывод программы: ${diagnostics.text()}",
            )
        }
    }

    /**
     * Прерывает работу программы.
     *
     * Вызывается при остановке воркера: оставшийся процесс держал бы видеокарту
     * и не вернул бы её следующему заданию.
     *
     * @param interrupted признак прерывания потока воркера
     */
    fun abort(interrupted: Boolean) {
        process?.destroyForcibly()
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }

    /** Хвост вывода программы: текст отказа должен быть виден оператору. */
    fun diagnosticsText(): String = diagnostics.text()

    /**
     * Читает строку приветствия программы.
     *
     * Чтением потока вывода владеет **один** поток — тот же, что читает
     * ответы. Причина практическая: строка приветствия и двоичные ответы идут
     * одним потоком, и два читателя разобрали бы его между собой — тот, кто
     * начал раньше, съел бы начало другого. Здесь строка читается первым
     * потоком и кладётся в очередь, а ожидание идёт с таймаутом: чтение из
     * канала по одному байту блокируется насмерть, и проверка «успел ли
     * процесс» после чтения не срабатывает никогда.
     *
     * @param running запущенный процесс
     * @return строка приветствия
     * @throws FaceDetectorFailed если программа не ответила вовремя, умерла
     *   на старте или не сказала, чем является
     */
    private fun readGreeting(running: Process): String {
        val deadline = System.nanoTime() + frameTimeout.toNanos()
        while (true) {
            val greeting = greetings.poll(POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS)
            if (greeting != null) {
                return greeting
            }
            if (running.isAlive.not()) {
                throw FaceDetectorFailed(
                    "Программа детектора «$programPath» завершилась на старте с кодом " +
                        "${runCatching { running.exitValue() }.getOrDefault(-1)}. " +
                        "Вывод программы: ${diagnostics.text()}",
                )
            }
            if (System.nanoTime() >= deadline) {
                throw FaceDetectorFailed(
                    "Программа детектора «$programPath» не объявила себя готовой за " +
                        "${frameTimeout.toSeconds()} с. Вывод программы: ${diagnostics.text()}",
                )
            }
        }
    }

    /**
     * Ждёт ответ на кадр.
     *
     * Ожидание идёт небольшими шагами, а не одним долгим: упавшая программа
     * должна называть свою ошибку сразу, а не через две минуты молчания. Падение
     * видно по коду завершения и тексту вывода, и оба уже прочитаны.
     *
     * @param number номер кадра, на который ждём ответ
     * @param running запущенный процесс
     * @return ответ программы; пустой буфер означает оборванный ответ
     * @throws FaceDetectorFailed если программа не ответила за отведённое время
     *   или завершилась, не ответив
     */
    private fun awaitAnswer(
        number: Int,
        running: Process,
    ): ByteArray {
        val deadline = System.nanoTime() + frameTimeout.toNanos()
        while (true) {
            val answer = answers.poll(POLL_SLICE_MILLIS, TimeUnit.MILLISECONDS)
            if (answer != null) {
                return answer
            }
            if (running.isAlive.not()) {
                val code = runCatching { running.exitValue() }.getOrDefault(-1)
                throw FaceDetectorFailed(
                    "Программа детектора «$programPath» завершилась с кодом $code, не ответив " +
                        "на кадр $number. Вывод программы: ${diagnostics.text()}",
                )
            }
            if (System.nanoTime() >= deadline) {
                throw FaceDetectorFailed(
                    "Программа детектора не ответила на кадр $number за " +
                        "${frameTimeout.toSeconds()} с. Вывод программы: ${diagnostics.text()}",
                )
            }
        }
    }

    /**
     * Читает поток вывода программы: сначала приветствие, затем ответы.
     *
     * @param running запущенный процесс
     * @param stream поток вывода программы
     */
    private fun readProgram(
        running: Process,
        stream: InputStream,
    ) {
        try {
            readGreetingLine(stream)
            readAnswers(stream)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Читает строку приветствия и кладёт её в очередь ожидания.
     *
     * @param stream поток вывода программы
     */
    private fun readGreetingLine(stream: InputStream) {
        val line = ByteArrayOutputStream()
        val single = ByteArray(1)
        while (true) {
            val read = stream.read(single)
            if (read < 0) {
                // Программа умерла, не поздоровавшись: текст причины уже
                // прочитан отдельным потоком, ожидание снимет его по коду
                // завершения.
                return
            }
            if (single[0].toInt().toChar() == '\n') {
                greetings.put(line.toString(StandardCharsets.UTF_8))
                return
            }
            line.write(single, 0, 1)
        }
    }

    /**
     * Читает двоичные ответы программы в очередь.
     *
     * Оборванный на середине ответ кладётся в очередь **пустым буфером**: без
     * этого молчание и оборванный ответ выглядели бы одинаково, а разница
     * между ними — это разница между «программа думает» и «результата нет».
     *
     * @param stream поток вывода программы
     */
    private fun readAnswers(stream: InputStream) {
        // Разбор ответа идёт прямо по байтам, без общего буфера: поток чтения
        // и поток прохода работают одновременно, а общий буфер означал бы
        // гонку — один поток сбрасывал бы его, пока другой читает из него
        // число лиц.
        try {
            while (true) {
                val head = readFully(stream, ANSWER_HEADER_BYTES) ?: return
                val count = littleEndianInt(head, HEADER_OFFSET_COUNT)
                val rest = readFully(stream, count * ANSWER_FACE_BYTES)
                if (rest == null) {
                    answers.put(ByteArray(0))
                    return
                }
                val answer = ByteArray(ANSWER_HEADER_BYTES + rest.size)
                head.copyInto(answer)
                rest.copyInto(answer, ANSWER_HEADER_BYTES)
                answers.put(answer)
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Читает 32-битное число из массива байт в порядке от младшего байта.
     *
     * @param bytes массив байт
     * @param offset с какого байта начинается число
     * @return прочитанное число
     */
    private fun littleEndianInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * Читает ровно указанное число байт или возвращает `null` на конце потока.
     *
     * @param stream поток ответов
     * @param count сколько байт нужно
     * @return буфер нужной длины либо `null`, если поток закрылся
     */
    private fun readFully(
        stream: InputStream,
        count: Int,
    ): ByteArray? {
        if (count == 0) {
            return ByteArray(0)
        }
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val step = stream.read(buffer, read, count - read)
            if (step < 0) {
                return null
            }
            read += step
        }
        return buffer
    }

    /**
     * Код формата кадра для заголовка протокола.
     *
     * @param format формат кадра
     * @return код формата
     * @throws IllegalArgumentException если формат канала не поддерживается
     */
    private fun formatCode(format: FrameFormat): Int =
        when (format.pixelFormat) {
            FrameFormat.BGR24 -> FORMAT_BGR24
            FrameFormat.RGB24 -> FORMAT_RGB24
            FrameFormat.GRAY -> FORMAT_GRAY
            else -> throw IllegalArgumentException(
                "Программа детектора не понимает формат кадра ${format.pixelFormat}",
            )
        }

    /**
     * Буфер хвоста вывода программы.
     *
     * @see ru.svoemesto.syp.core.media.FrameChannel
     */
    private class Diagnostics {
        private val buffer = ByteArrayOutputStream()

        /**
         * Читает поток ошибок до конца.
         *
         * @param stream поток ошибок программы
         */
        fun read(stream: InputStream) {
            val chunk = ByteArray(CHUNK)
            try {
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
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /** Хвост вывода программы одной строкой. */
        fun text(): String = synchronized(buffer) { buffer.toString(StandardCharsets.UTF_8).trim() }
    }

    companion object {
        /** Число байт в заголовке кадра: номер, ширина, высота, формат. */
        const val HEADER_BYTES: Int = 16

        /** Число байт в заголовке ответа: номер, время, число лиц. */
        const val ANSWER_HEADER_BYTES: Int = 12

        /** Число байт на одно лицо в ответе. */
        const val ANSWER_FACE_BYTES: Int = 32

        /** Смещение номера кадра в заголовке ответа. */
        const val HEADER_OFFSET_NUMBER: Int = 0

        /** Смещение числа лиц в заголовке ответа. */
        const val HEADER_OFFSET_COUNT: Int = 8

        /** Код формата: три байта на пиксель, синий-зелёный-красный. */
        const val FORMAT_BGR24: Int = 1

        /** Код формата: три байта на пиксель, красный-зелёный-синий. */
        const val FORMAT_RGB24: Int = 2

        /** Код формата: один байт на пиксель, оттенки серого. */
        const val FORMAT_GRAY: Int = 3

        /** Сколько ждать ответа на один кадр до отказа. */
        val DEFAULT_FRAME_TIMEOUT: Duration = Duration.ofMinutes(2)

        /** Сколько ждать завершения программы после конца эпизода. */
        val CLOSE_TIMEOUT: Duration = Duration.ofMinutes(2)

        /** Сколько байт вывода программы удерживается для текста ошибки. */
        const val KEEP_BYTES: Int = 64 * 1024

        /** Размер порции при чтении вывода программы. */
        private const val CHUNK: Int = 8 * 1024

        /** Сколько ждать конца потоков чтения. */
        private const val READER_JOIN_MILLIS: Long = 5_000

        /** Шаг ожидания ответа на кадр: упавшая программа должна быть замечена сразу. */
        private const val POLL_SLICE_MILLIS: Long = 100

        /** Сколько ждать после разрушающего прерывания программы. */
        private const val DESTROY_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * Программа детектора лиц не дала результата.
 *
 * Отдельный тип означает «работа началась, но не удалась»: сбой запуска,
 * сбой среды исполнения и оборванный ответ — три разные причины, и подменять
 * их пустым списком лиц нельзя (SC-005, FR-092).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceDetectorFailed(
    message: String,
    cause: Throwable? = null,
) : ru.svoemesto.syp.core.media.FrameConsumerFailed(message, cause)
