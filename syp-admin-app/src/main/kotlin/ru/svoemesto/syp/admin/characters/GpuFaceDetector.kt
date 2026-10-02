package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.media.RawFrame
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Детектор лиц на видеокарте, запущенный внутри контейнера админки.
 *
 * **Что это замена.** Заглушка объявляет себя заглушкой и лица не ищет; этот
 * класс ищет их по-настоящему, через программу детектора в том же контейнере,
 * что и очередь заданий. Отдельного контейнера детекции нет и быть не должно:
 * контейнеров стека ровно шесть (research.md Т-01, Т-20).
 *
 * **Путь и модель — из конфигурации развёртывания.** Ни путь к программе, ни
 * путь к модели, ни пороги не приходят из данных задания: путь из данных — это
 * путь из интернета в командную строку (ADR-0010, ограничение 2). Секретов в
 * этих значениях нет, версии среды исполнения и модели зафиксированы в образе
 * и в тексте ключа детектора.
 *
 * **Ключ детектора описывает настоящую конфигурацию.** В прогон анализа
 * попадают модель, провайдер вычислений, размер входа и порог уверенности:
 * результат, полученный другой моделью или на другом размере входа, нельзя
 * выдать за этот (FR-090, Р-10). Ключ собирается из строки приветствия самой
 * программы, а не из того, что предполагала конфигурация: если программа
 * поднялась с другой моделью, в прогоне будет записана именно она.
 *
 * Класс переживает один проход и закрывается вместе с ним: программа
 * детектора — процесс, и оставленный процесс держал бы видеокарту. Закрытие
 * происходит в [FaceScan], а следующий проход поднимает программу заново.
 *
 * @property program путь к программе детектора
 * @property modelPath путь к файлу модели
 * @property provider провайдер вычислений
 * @property inputWidth ширина входа сети
 * @property inputHeight высота входа сети
 * @property scoreThreshold порог уверенности
 * @property nmsThreshold порог перекрытия рамок
 * @property frameTimeout сколько ждать ответа на один кадр
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class GpuFaceDetector(
    private val program: String,
    private val modelPath: String,
    private val provider: String = DEFAULT_PROVIDER,
    private val inputWidth: Int = DEFAULT_INPUT_SIZE,
    private val inputHeight: Int = DEFAULT_INPUT_SIZE,
    private val scoreThreshold: Double = DEFAULT_SCORE_THRESHOLD,
    private val nmsThreshold: Double = DEFAULT_NMS_THRESHOLD,
    private val frameTimeout: Duration = FaceDetectorProcess.DEFAULT_FRAME_TIMEOUT,
) : FaceDetector,
    AutoCloseable {
    private val process =
        FaceDetectorProcess(
            programPath = program,
            arguments =
                listOf(
                    "--model",
                    modelPath,
                    "--provider",
                    provider,
                    "--input-width",
                    inputWidth.toString(),
                    "--input-height",
                    inputHeight.toString(),
                    "--score-threshold",
                    scoreThreshold.toString(),
                    "--nms-threshold",
                    nmsThreshold.toString(),
                ),
            frameTimeout = frameTimeout,
        )

    /**
     * Идентификатор реализации детектора; попадает в прогон анализа.
     *
     * Ключ собирается из строки приветствия программы **после** её запуска,
     * поэтому он всегда описывает реально поднятую конфигурацию. До запуска
     * ключ неполон и проход с ним невозможен: [detect] запускает программу
     * раньше, чем ключ понадобится.
     */
    override val key: String
        get() = startedKey

    /** Настоящий детектор заглушкой не является. */
    override val isPlaceholder: Boolean = false

    private var startedKey: String = "$KEY_PREFIX (не запущен)"

    /**
     * Ищет лица в одном кадре.
     *
     * @param frame кадр полного разрешения; буфер переиспользуется, ссылку на
     *   него хранить нельзя
     * @return найденные лица
     * @throws FaceDetectorFailed если программа детектора не поднялась,
     *   не ответила или оборвала ответ
     */
    override fun detect(frame: RawFrame): List<DetectedFace> {
        val running = startIfNeeded()
        return running.detect(frame)
    }

    /**
     * Останавливает программу детектора.
     *
     * @throws FaceDetectorFailed если программа завершилась с ненулевым кодом:
     *   такой код означает, что часть кадров не обработана, и результат
     *   прохода неполон (SC-005)
     */
    override fun close() {
        process.close()
    }

    /** Хвост вывода программы детектора: текст отказа должен быть виден. */
    fun diagnosticsText(): String = process.diagnosticsText()

    /**
     * Поднимает программу, если она ещё не поднята.
     *
     * @return запущенная программа детектора
     * @throws FaceDetectorFailed если модель или среда исполнения не
     *   поднялись: отказ с текстом, а не пустой результат
     */
    private fun startIfNeeded(): FaceDetectorProcess {
        requireProgramExists()
        if (!process.isRunning) {
            process.start()
        }
        startedKey = keyOf(process.handshake, modelPath)
        return process
    }

    /**
     * Проверяет, что программа и модель на месте.
     *
     * Проверка до запуска даёт текст «файла нет» вместо «процесс завершился с
     * кодом 2»: первое читается, второе приходится разбирать.
     */
    private fun requireProgramExists() {
        val programFile = Path.of(program)
        if (!Files.isRegularFile(programFile) || !Files.isExecutable(programFile)) {
            throw FaceDetectorFailed(
                "Программа детектора «$program» не найдена или не исполняется. " +
                    "Проверьте путь в конфигурации развёртывания (ADR-0010)",
            )
        }
        if (!Files.isRegularFile(Path.of(modelPath))) {
            throw FaceDetectorFailed(
                "Модель детектора «$modelPath» не найдена. Проверьте путь в конфигурации " +
                    "развёртывания (ADR-0010)",
            )
        }
    }

    companion object {
        /** Начало ключа детектора: вид модели и её происхождение. */
        const val KEY_PREFIX: String = "gpu-face-detector"

        /** Провайдер вычислений по умолчанию. */
        const val DEFAULT_PROVIDER: String = "CUDAExecutionProvider"

        /** Размер входа сети по умолчанию. */
        const val DEFAULT_INPUT_SIZE: Int = 640

        /**
         * Порог уверенности по умолчанию.
         *
         * Значение 0,6 — исходное из репозитория модели. На двадцати кадрах
         * `GOT.S01E01` при 0,9 не находится ни одного лица, при 0,6 — на 12
         * кадрах из 20; подбор порога по серии и проверка на 200 кадрах —
         * задача T076 (М-02), здесь зафиксировано лишь рабочее значение.
         */
        const val DEFAULT_SCORE_THRESHOLD: Double = 0.6

        /** Порог перекрытия рамок по умолчанию. */
        const val DEFAULT_NMS_THRESHOLD: Double = 0.3

        /**
         * Собирает ключ детектора из строки приветствия программы.
         *
         * Строка приветствия — единственный источник правды о том, что
         * поднялось: модель, провайдер, версии среды и пороги берутся из неё,
         * а не из предположений конфигурации.
         *
         * @param greeting строка приветствия в формате JSON
         * @param modelPath путь к модели, если строка не разобралась
         * @return ключ детектора для прогона анализа
         */
        fun keyOf(
            greeting: String,
            modelPath: String,
        ): String {
            val values = parseGreeting(greeting)
            val model = values["model"]?.let(::fileName) ?: fileName(modelPath)
            val provider = values["provider"] ?: "провайдер-не-сообщён"
            val input = values["input"] ?: "${DEFAULT_INPUT_SIZE}x$DEFAULT_INPUT_SIZE"
            val threshold = values["score_threshold"] ?: DEFAULT_SCORE_THRESHOLD
            return "$KEY_PREFIX:$model:$provider:$input@$threshold"
        }

        /**
         * Разбирает простые поля строки приветствия.
         *
         * Разбор намеренно примитивный: программа пишет одну короткую строку
         * по известному формату, а тянуть в бэкенд полноценный разборщик JSON
         * ради шести значений незачем. Неразобранная строка не ломает ключ —
         * он остаётся описательным, но разборчивым человеком.
         *
         * @param greeting строка приветствия
         * @return карта «имя поля — значение»
         */
        private fun parseGreeting(greeting: String): Map<String, String> {
            val values = HashMap<String, String>()
            QUOTED.findAll(greeting).forEach { match ->
                values[match.groupValues[1]] = match.groupValues[2]
            }
            INPUT.find(greeting)?.groupValues?.let { match ->
                values["input"] = "${match[1]}x${match[2]}"
            }
            return values
        }

        /**
         * Имя файла без каталога.
         *
         * @param path путь к файлу
         * @return имя файла
         */
        private fun fileName(path: String): String = path.substringAfterLast('/')

        /** Поле вида «"имя": "значение"» в строке приветствия. */
        private val QUOTED = Regex("\"([a-z_]+)\"\\s*:\\s*\"([^\"]*)\"")

        /** Поле вида «"input": [640, 640]» в строке приветствия. */
        private val INPUT = Regex("\"input\"\\s*:\\s*\\[\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\]")
    }
}
