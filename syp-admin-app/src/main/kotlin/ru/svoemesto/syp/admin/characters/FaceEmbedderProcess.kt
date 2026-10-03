package ru.svoemesto.syp.admin.characters

import org.slf4j.LoggerFactory
import ru.svoemesto.syp.core.media.RawFrame
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Программа-эмбеддер: превращает найденные лица в векторы признаков.
 *
 * Устроена так же, как программа-детектор: долгоживущая, кадры и ответы идут
 * через stdin и stdout, порядок кадров проверяется. Отдельная программа, а не
 * вызов внутри детектора, потому что векторы считаются после того, как лица уже
 * найдены и записаны, и потому что модель меняется независимо.
 *
 * @property programPath путь к программе
 * @property arguments аргументы запуска: путь к модели и провайдер
 * @property frameTimeout сколько ждать ответа на кадр
 */
class FaceEmbedderProcess(
    private val programPath: String,
    private val arguments: List<String>,
    private val frameTimeout: Duration = DEFAULT_FRAME_TIMEOUT,
) : Closeable {
    private val answers: BlockingQueue<ByteArray> = LinkedBlockingQueue()
    private val greetings: BlockingQueue<String> = LinkedBlockingQueue()

    /** Поток чтения ответов. */
    private var reader: Thread? = null
    private var process: Process? = null
    private var input: OutputStream? = null

    /**
     * Запускает программу и ждёт её приветствия.
     *
     * Без приветствия нельзя: программа может не поднять провайдер и тихо уйти на
     * другое устройство, и тогда весь анализ пойдёт в двадцать раз медленнее,
     * не падая нигде.
     */
    fun start() {
        val builder = ProcessBuilder(listOf(programPath) + arguments)
        val diagnostics = StringBuilder()
        process =
            builder.start().also { started ->
                input = started.outputStream
                // Вывод программы по ошибкам идёт отдельным потоком и в ответы
                // не попадает. Раньше потоки были слиты, и предупреждения
                // времени выполнения попадали в тот же поток, что и векторы:
                // ответ начитывался с середины чужого текста и получался мусором.
                val errors =
                    Thread {
                        started.errorStream.use { stream ->
                            val buffer = ByteArray(BUFFER_BYTES)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) {
                                    break
                                }
                                diagnostics.append(String(buffer, 0, read))
                            }
                        }
                    }
                errors.isDaemon = true
                errors.name = "syp-face-embedder-errors"
                errors.start()
                reader =
                    Thread {
                        started.inputStream.use { stream ->
                            // Приветствие — одна строка, и читается она побайтно,
                            // до перевода строки: буферизованное чтение заглянуло бы
                            // вперёд и съело начало первого ответа.
                            val greeting = StringBuilder()
                            while (true) {
                                val byte = stream.read()
                                if (byte < 0 || byte == NEWLINE) {
                                    break
                                }
                                greeting.append(byte.toChar())
                            }
                            greetings.offer(if (greeting.isEmpty()) "closed" else greeting.toString())
                            // Дальше поток двоичный: заголовок ответа, затем столько
                            // векторов, сколько в нём объявлено. Ответы приходят
                            // произвольными кусками, и кусок — это не ответ: раньше
                            // любой кусок и считался ответом, и номер кадра в нём
                            // оказывался мусором из середины файла.
                            val header = ByteArray(ANSWER_HEADER_BYTES)
                            while (readFully(stream, header)) {
                                val count = littleEndianShort(header, 4)
                                if (count < 0) {
                                    break
                                }
                                val body = ByteArray(count * ANSWER_VECTOR_BYTES)
                                if (!readFully(stream, body)) {
                                    break
                                }
                                answers.offer(header + body)
                            }
                        }
                        greetings.offer("closed")
                    }
                reader!!.isDaemon = true
                reader!!.name = "syp-face-embedder-reader"
                reader!!.start()
            }
        val greeting = greetings.poll(frameTimeout.toMillis(), TimeUnit.MILLISECONDS)
        if (greeting == null || greeting == "closed") {
            close()
            throw FaceEmbedderFailed(
                "Программа эмбеддер не поднялась за ${frameTimeout.seconds} с. " +
                    "Вывод программы: $diagnostics",
            )
        }
        if (!greeting.contains("\"status\": \"ready\"")) {
            close()
            throw FaceEmbedderFailed("Программа эмбеддер ответила: $greeting")
        }
    }

    /**
     * Считает векторы для лиц кадра.
     *
     * @param frame кадр
     * @param faces лица с рамками и пятью точками, в порядке их хранения
     * @return вектор на каждое лицо, в том же порядке
     * @throws FaceEmbedderFailed если программа молчит, отвечает не на тот кадр
     *   или отдаёт не тот размер ответа
     */
    fun embed(
        frame: RawFrame,
        faces: List<DetectedFace>,
    ): List<FloatArray> {
        val stream = input ?: throw FaceEmbedderFailed("Программа эмбеддер не запущена")
        if (faces.isEmpty()) {
            return emptyList()
        }
        // Запрос несёт и кадр: без пикселей эмбеддер посчитать нечего, ему
        // нужны не рамка и не точки сами по себе, а само лицо на кадре.
        val request =
            ByteBuffer.allocate(
                REQUEST_HEADER_BYTES +
                    frame.data.size +
                    REQUEST_COUNT_BYTES +
                    faces.size * REQUEST_FACE_BYTES,
            )
        request.order(ByteOrder.LITTLE_ENDIAN)
        request.putInt(frame.number)
        request.putShort(frame.format.width.toShort())
        request.putShort(frame.format.height.toShort())
        request.put(frame.data)
        request.putShort(faces.size.toShort())
        for (face in faces) {
            request.putShort(face.x1.toShort())
            request.putShort(face.y1.toShort())
            request.putShort(face.x2.toShort())
            request.putShort(face.y2.toShort())
            request.putFloat(face.confidence.toFloat())
            putPoint(request, face.points.eyeLeft)
            putPoint(request, face.points.eyeRight)
            putPoint(request, face.points.nose)
            putPoint(request, face.points.mouthLeft)
            putPoint(request, face.points.mouthRight)
        }
        stream.write(request.array())
        stream.flush()
        val answer = awaitAnswer(frame.number)
        if (answer.size < ANSWER_HEADER_BYTES) {
            throw FaceEmbedderFailed(
                "Ответ эмбеддера на кадр ${frame.number} мал: ${answer.size} байт",
            )
        }
        val number = littleEndianInt(answer, 0)
        if (number != frame.number) {
            throw FaceEmbedderFailed(
                "Программа эмбеддера ответила на кадр $number, а ждали ${frame.number}: " +
                    "кадры потеряли порядок (ADR-0001)",
            )
        }
        val count = littleEndianShort(answer, 4)
        val expected = ANSWER_HEADER_BYTES + count * ANSWER_VECTOR_BYTES
        if (answer.size < expected) {
            throw FaceEmbedderFailed(
                "Ответ эмбеддера на кадр $number оборван: ${answer.size} байт, ждали $expected",
            )
        }
        val buffer =
            ByteBuffer
                .wrap(answer, ANSWER_HEADER_BYTES, count * ANSWER_VECTOR_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
        return List(count) {
            FloatArray(ANSWER_VECTOR_VALUES) { buffer.float }
        }
    }

    /** Кладёт точку в запрос. */
    private fun putPoint(
        buffer: ByteBuffer,
        point: IntPoint,
    ) {
        buffer.putShort(point.x.toShort())
        buffer.putShort(point.y.toShort())
    }

    /** Читает ровно [length] байт; false — поток кончился раньше. */
    private fun readFully(
        stream: InputStream,
        target: ByteArray,
    ): Boolean {
        var read = 0
        while (read < target.size) {
            val count = stream.read(target, read, target.size - read)
            if (count < 0) {
                return false
            }
            read += count
        }
        return true
    }

    private fun awaitAnswer(number: Int): ByteArray {
        val answer =
            answers.poll(frameTimeout.toMillis(), TimeUnit.MILLISECONDS)
                ?: throw FaceEmbedderFailed(
                    "Программа эмбеддера молчит дольше ${frameTimeout.seconds} с на кадр $number",
                )
        return answer
    }

    /**
     * Закрывает программу.
     *
     * Закрытие штатное: программа читает конец ввода и завершается сама.
     */
    override fun close() {
        input?.runCatching { close() }
        input = null
        process?.runCatching { waitFor(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        process?.runCatching { destroy() }
        process = null
        reader?.runCatching { join(CLOSE_TIMEOUT_SECONDS * 1000L) }
        reader = null
    }

    companion object {
        /**
         * Сколько байт на лицо в запросе: четыре координаты рамки, уверенность
         * и пять точек.
         *
         * Считается из частей, а не константой: при первом написании стояло
         * `4 + 4 + 10 * 2`, то есть 28 байт, а пишется 32 — четыре координаты
         * и уверенность занимают не 8 байт, а 12. Запрос не влезал в отведённый
         * буфер, и эмбеддер падал на первом же лице — а падение уходило в
         * сообщение о сбое потока кадров и выглядело как поломка декодера.
         */
        const val REQUEST_FACE_BYTES: Int = 4 * 2 + 4 + 10 * 2

        /** Сколько байт в заголовке запроса: номер, ширина, высота. */
        const val REQUEST_HEADER_BYTES: Int = 8

        /** Сколько байт занимает число лиц в запросе. */
        const val REQUEST_COUNT_BYTES: Int = 2

        /** Сколько байт в заголовке ответа. */
        const val ANSWER_HEADER_BYTES: Int = 6

        /** Сколько чисел в векторе. */
        const val ANSWER_VECTOR_VALUES: Int = 128

        /** Сколько байт на вектор в ответе. */
        const val ANSWER_VECTOR_BYTES: Int = ANSWER_VECTOR_VALUES * 4

        /** Порция чтения. */
        const val BUFFER_BYTES: Int = 64 * 1024

        /** Перевод строки, на котором заканчивается приветствие. */
        private const val NEWLINE: Int = 10

        /** Сколько секунд ждать завершения программы. */
        const val CLOSE_TIMEOUT_SECONDS: Long = 10

        /** Сколько ждать ответа по умолчанию. */
        val DEFAULT_FRAME_TIMEOUT: Duration = Duration.ofSeconds(120)

        /** Читает четырёхбайтовое число в начале little-endian. */
        fun littleEndianShort(
            data: ByteArray,
            offset: Int,
        ): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        fun littleEndianInt(
            bytes: ByteArray,
            offset: Int,
        ): Int =
            ByteBuffer
                .wrap(bytes, offset, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int
    }
}

/** Программа эмбеддера не ответила или ответила не то, что от неё ждали. */
class FaceEmbedderFailed(
    message: String,
) : RuntimeException(message)

private val logger = LoggerFactory.getLogger("FaceEmbedderProcess")
