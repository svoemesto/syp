package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.media.ExternalProgram
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Параметры файла эпизода, определённые опросом.
 *
 * Все величины получены из самого файла: оператор их не вводит, иначе они
 * расходились бы с содержимым (FR-002).
 *
 * **Время нигде не хранится отдельно от номера кадра** (ADR-0001). Здесь
 * хранятся [timeBaseNum] и [timeBaseDen] — длительность кадра в секундах, —
 * а [durationNum] и [durationDen] вычислены из них и числа кадров. Время
 * любого кадра восстанавливается как `номер * timeBaseNum / timeBaseDen`, и
 * второго источника правды рядом нет.
 *
 * @property byteSize размер файла в байтах
 * @property fileMtime время изменения файла: по нему обнаруживается подмена
 *   источника и устаревает посчитанная сумма (FR-090)
 * @property frameCount число кадров эпизода
 * @property timeBaseNum числитель длительности кадра в секундах: для
 *   `24000/1001` кадра в секунду это 1001
 * @property timeBaseDen знаменатель длительности кадра в секундах: для
 *   `24000/1001` кадра в секунду это 24 000
 * @property width ширина кадра в пикселях
 * @property height высота кадра в пикселях
 * @property durationNum числитель длительности эпизода в секундах
 * @property durationDen знаменатель длительности эпизода в секундах
 * @property videoCodec кодек видео
 * @property videoProfile профиль видео: признак совместимости при сборке
 * @property pixelFormat формат пикселей: признак совместимости при сборке
 * @property audioCodec кодек аудио; `null`, если звука нет
 * @property audioChannels число аудиоканалов; `null`, если звука нет
 * @property audioSampleRate частота дискретизации аудио; `null`, если звука нет
 * @property containerDurationSeconds длительность, объявленная контейнером.
 *   Сохраняется как диагностическая величина: она **не** является вторым
 *   источником правды о времени, потому что считается контейнером по
 *   последнему пакету и с величиной, вычисленной по кадрам, не совпадает
 * @property keyframes карта ключевых кадров эпизода
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SourceParameters(
    val byteSize: Long,
    val fileMtime: Instant,
    val frameCount: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    val width: Int,
    val height: Int,
    val durationNum: Long,
    val durationDen: Long,
    val videoCodec: String,
    val videoProfile: String?,
    val pixelFormat: String,
    val audioCodec: String?,
    val audioChannels: Int?,
    val audioSampleRate: Int?,
    val containerDurationSeconds: Double?,
    val keyframes: KeyframeMap,
) {
    /** Длительность одного кадра в секундах. */
    fun frameDurationSeconds(): Double = timeBaseNum.toDouble() / timeBaseDen

    /** Длительность эпизода в секундах, вычисленная по кадрам. */
    fun durationSeconds(): Double = durationNum.toDouble() / durationDen

    /**
     * Время кадра в секундах.
     *
     * @param frame номер кадра с нуля
     * @return время от начала эпизода в секундах
     */
    fun timeOfFrame(frame: Long): Double = frame * frameDurationSeconds()

    /**
     * Номер кадра по времени.
     *
     * @param seconds время от начала эпизода в секундах
     * @return ближайший номер кадра
     */
    fun frameOfTime(seconds: Double): Long = Math.round(seconds / frameDurationSeconds())
}

/**
 * Один разобранный раздел вывода `ffprobe`.
 *
 * Разбор сделан без библиотеки JSON: `ffprobe` умеет отдавать вывод
 * построчным, `ключ=значение`, и это позволяет обойтись без разбора
 * вложенных структур вроде `disposition` — нужные ключи лежат на верхнем
 * уровне потока.
 *
 * @property values значения ключей раздела
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ProbeSection(
    private val values: Map<String, String>,
) {
    /** Значение ключа или `null`, если ключа нет. */
    operator fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() && it != "N/A" }

    /**
     * Значение ключа вида `числитель/знаменатель`.
     *
     * @param key имя ключа
     * @return пара целых либо `null`, если ключа нет или он не разбирается
     */
    fun rational(key: String): Pair<BigInteger, BigInteger>? {
        val text = this[key] ?: return null
        val parts = text.split('/')
        if (parts.size != 2) return null
        val numerator = parts[0].trim().toBigIntegerOrNull() ?: return null
        val denominator = parts[1].trim().toBigIntegerOrNull() ?: return null
        return numerator to denominator
    }

    /**
     * Обязательное текстовое значение ключа.
     *
     * @param key имя ключа
     * @param sourcePath путь к файлу: попадает в текст отказа
     * @return значение ключа
     * @throws DomainException если ключа нет: это отказ, а не пустое значение
     */
    fun requireText(
        key: String,
        sourcePath: Path,
    ): String = this[key] ?: throw unreadableFile(sourcePath, "в видеопотоке нет «$key»")

    /**
     * Обязательное целое значение ключа.
     *
     * @param key имя ключа
     * @param sourcePath путь к файлу: попадает в текст отказа
     * @return значение ключа
     * @throws DomainException если ключа нет или он не число
     */
    fun requireInt(
        key: String,
        sourcePath: Path,
    ): Int = this[key]?.toIntOrNull() ?: throw unreadableFile(sourcePath, "в видеопотоке нет целого «$key»")
}

/**
 * Разобранное описание файла: потоки и контейнер.
 *
 * @property sections потоки файла в порядке объявления
 * @property container значения раздела контейнера
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ProbeDescription(
    private val sections: List<ProbeSection>,
    private val container: Map<String, String>,
) {
    /**
     * Первый поток заданного вида.
     *
     * @param codecType вид потока: `video` или `audio`
     * @return поток или `null`, если таких потоков нет
     */
    fun firstStream(codecType: String): ProbeSection? = sections.firstOrNull { it["codec_type"] == codecType }

    /**
     * Значение ключа контейнера.
     *
     * @param key имя ключа
     * @return значение или `null`, если ключа нет
     */
    fun containerValue(key: String): String? = container[key]?.takeIf { it.isNotBlank() && it != "N/A" }
}

/**
 * Опрос файла эпизода.
 *
 * Опрос идёт в три прохода, и каждый нужен по своей причине:
 *
 * 1. **заголовок контейнера и потоков** — `ffprobe -show_streams
 *    -show_format`. Разрешение, кодек, профиль, формат пикселей, частокадровая
 *    база, параметры звука и размер файла. Читается только начало файла.
 * 2. **счётчик видеопакетов** — `ffprobe -count_packets`. В Matroska число
 *    кадров у потока **не записано**: `nb_frames` возвращает `N/A`, поэтому
 *    кадры приходится считать. Счётчик обходит файл, ничего не декодируя:
 *    эпизод `GOT.S01E01` на 5,6 ГБ считается за 20 секунд.
 * 3. **ключевые кадры** — `ffprobe -skip_frame nokey`. Показывает только
 *    ключевые кадры, поэтому проход короче полного перебора и не декодирует
 *    изображение. Из отметок времени получаются номера кадров.
 *
 * Число кадров **не берётся** из объявленной контейнером длительности:
 * округление её до целого кадра расходится с фактом, а расхождение в
 * несколько кадров разъезжается по всем границам сцен и планов (ADR-0001).
 *
 * Каждый отказ — [DomainException] с кодом `SOURCE_UNREADABLE` и текстом на
 * русском. «Успех с пустым результатом» невозможен: без числа кадров и карты
 * ключевых кадров эпизод не определяется вовсе (FR-092).
 *
 * @property program единая точка запуска внешних программ
 * @property ffprobePath путь к программе `ffprobe`; приходит из конфигурации
 *   развёртывания, а не из данных задания (ADR-0010)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SourceProbe(
    private val program: ExternalProgram,
    private val ffprobePath: String,
) {
    /**
     * Определяет параметры файла эпизода.
     *
     * @param sourcePath путь к исходному видеофайлу
     * @return параметры файла вместе с картой ключевых кадров
     * @throws DomainException с кодом `SOURCE_UNREADABLE`, если файла нет, он
     *   не читается или параметры определить не удалось
     */
    fun probe(sourcePath: Path): SourceParameters {
        val file = sourcePath.toFile()
        if (!file.isFile) {
            throw unreadableFile(sourcePath, "файла нет или это не обычный файл")
        }
        if (!file.canRead()) {
            throw unreadableFile(sourcePath, "файл не доступен для чтения: проверьте права на источник")
        }

        val described = parseDescription(run(sourcePath, "описание потоков", listOf("-show_streams", "-show_format", "-of", "default")))
        val video = described.firstStream("video") ?: throw unreadableFile(sourcePath, "в файле нет видеопотока")
        val audio = described.firstStream("audio")

        val rate =
            video.rational("r_frame_rate") ?: video.rational("avg_frame_rate")
                ?: throw unreadableFile(sourcePath, "у видеопотока не объявлена частокадровая база")
        if (rate.first <= BigInteger.ZERO || rate.second <= BigInteger.ZERO) {
            throw unreadableFile(sourcePath, "частокадровая база объявлена как «${rate.first}/${rate.second}»")
        }

        // Частота кадров известна как «кадров в секунду», а хранится
        // длительность кадра в секундах: это обратная величина, и именно ею
        // пользуется ADR-0001 при переходе от номера кадра ко времени.
        val timeBase = reduce(rate.second, rate.first)
        val frameCount = countFrames(sourcePath)
        val duration = reduce(BigInteger.valueOf(frameCount.toLong()) * timeBase.first, timeBase.second)
        val keyframes = readKeyframes(sourcePath, video, timeBase, frameCount)
        val attributes = Files.readAttributes(sourcePath, BasicFileAttributes::class.java)

        return SourceParameters(
            byteSize = file.length(),
            fileMtime = attributes.lastModifiedTime().toInstant(),
            frameCount = frameCount,
            timeBaseNum = timeBase.first.toInt(),
            timeBaseDen = timeBase.second.toInt(),
            width = video.requireInt("width", sourcePath),
            height = video.requireInt("height", sourcePath),
            durationNum = duration.first.toLong(),
            durationDen = duration.second.toLong(),
            videoCodec = video.requireText("codec_name", sourcePath),
            videoProfile = video["profile"]?.takeIf { it != "unknown" },
            pixelFormat = video.requireText("pix_fmt", sourcePath),
            audioCodec = audio?.get("codec_name"),
            audioChannels = audio?.get("channels")?.toIntOrNull(),
            audioSampleRate = audio?.get("sample_rate")?.toIntOrNull(),
            containerDurationSeconds = described.containerValue("duration")?.toDoubleOrNull(),
            keyframes = keyframes,
        )
    }

    /**
     * Считает видеопакеты: в Matroska `nb_frames` не заполняется.
     *
     * @param sourcePath путь к файлу
     * @return число видеокадров
     * @throws DomainException если счётчик недоступен или пуст
     */
    private fun countFrames(sourcePath: Path): Int {
        val output =
            run(
                sourcePath,
                "подсчёт видеокадров",
                listOf("-select_streams", "v:0", "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0"),
            )
        val counted =
            output
                .trim()
                .lines()
                .firstOrNull()
                ?.trim()
                ?.toIntOrNull()
                ?: throw unreadableFile(sourcePath, "не удалось сосчитать видеокадры: ffprobe не вернул счётчик пакетов")
        if (counted <= 0) {
            throw unreadableFile(sourcePath, "в видеопотоке нет ни одного пакета: файл не читается как видео")
        }
        return counted
    }

    /**
     * Читает отметки ключевых кадров и переводит их в номера кадров.
     *
     * Перевод выполняется точной арифметикой на дробных числах: время кадра
     * отличается от его номера ровно на `timeBaseNum / timeBaseDen` секунд, и
     * ошибка в доли кадра накапливалась бы на длинном эпизоде до нескольких
     * кадров.
     *
     * @param sourcePath путь к файлу
     * @param video описание видеопотока
     * @param timeBase длительность кадра в секундах: числитель и знаменатель
     * @param frameCount число кадров эпизода
     * @return карта ключевых кадров
     * @throws DomainException если ключевых кадров нет или отметка вышла за
     *   пределы эпизода
     */
    private fun readKeyframes(
        sourcePath: Path,
        video: ProbeSection,
        timeBase: Pair<BigInteger, BigInteger>,
        frameCount: Int,
    ): KeyframeMap {
        val output =
            run(
                sourcePath,
                "чтение ключевых кадров",
                listOf(
                    "-select_streams",
                    "v:0",
                    "-skip_frame",
                    "nokey",
                    "-show_entries",
                    "frame=best_effort_timestamp",
                    "-of",
                    "csv=p=0",
                ),
            )
        val streamBase =
            video.rational("time_base")
                ?: throw unreadableFile(sourcePath, "у видеопотока не объявлена шкала отметок времени")
        val keyframes = mutableListOf<Int>()
        output.lineSequence().forEach { line ->
            val timestamp = line.trim().removeSuffix(",").trim()
            if (timestamp.isEmpty() || timestamp == "N/A") return@forEach
            val seconds = BigDecimal(timestamp) * BigDecimal(streamBase.first) / BigDecimal(streamBase.second)
            val exact = seconds * BigDecimal(timeBase.second) / BigDecimal(timeBase.first)
            val frame = exact.setScale(0, RoundingMode.HALF_UP).toInt()
            if (frame < 0 || frame >= frameCount) {
                throw unreadableFile(
                    sourcePath,
                    "отметка ключевого кадра $timestamp сходится на кадр $frame, а эпизод содержит " +
                        "$frameCount кадров: число кадров и частокадровая база не согласуются",
                )
            }
            keyframes.add(frame)
        }
        if (keyframes.isEmpty()) {
            throw unreadableFile(
                sourcePath,
                "в файле не найдено ни одного ключевого кадра: нарезка без перекодирования невозможна, " +
                    "а перекодирование запрещено (ADR-0006)",
            )
        }
        return KeyframeMap.build(frameCount, keyframes)
    }

    /**
     * Запускает `ffprobe` и отдаёт его вывод.
     *
     * Код возврата проверяется **всегда**: успех с неполным выводом здесь
     * означал бы эпизод с выдуманными параметрами (FR-092, SC-005).
     *
     * @param sourcePath путь к файлу: `ffprobe` требует его последним аргументом
     * @param action что делает проход: попадает в текст ошибки
     * @param arguments аргументы вызова `ffprobe`
     * @return объединённый вывод программы
     * @throws DomainException если программа не запустилась или завершилась с
     *   ненулевым кодом
     */
    private fun run(
        sourcePath: Path,
        action: String,
        arguments: List<String>,
    ): String {
        // Баннер и конфигурация сборки ffprobe печатаются в тот же поток, что и
        // результат: без `-v error` первая строка вывода перестаёт быть
        // данными, и разбор молча уходит в никуда.
        val result = program.run(ffprobePath, listOf("-v", "error") + arguments + sourcePath.toString())
        if (!result.isSuccess) {
            val tail =
                result
                    .errorText(File(ffprobePath).name)
                    .lines()
                    .drop(1)
                    .joinToString("; ")
            throw DomainException(
                ErrorCode.SOURCE_UNREADABLE,
                "не удалось выполнить $action файлом «$sourcePath»: " +
                    if (tail.isBlank()) "ffprobe завершился с кодом ${result.exitCode}" else tail,
            )
        }
        return result.output
    }

    /**
     * Разбирает вывод `ffprobe -show_streams -show_format`.
     *
     * Вложенные разделы потока (`disposition`, `side_data`) пропускаются: нужные
     * ключи лежат на верхнем уровне потока, а их смешивание с содержимым
     * вложенных разделов означало бы читать чужой ключ.
     *
     * @param output вывод программы
     * @return разобранное описание потоков и контейнера
     */
    private fun parseDescription(output: String): ProbeDescription {
        val sections = mutableListOf<ProbeSection>()
        val stack = ArrayDeque<String>()
        var container = emptyMap<String, String>()
        var current: MutableMap<String, String>? = null

        output.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[") && line.endsWith("]") && !line.startsWith("[/") -> {
                    val name = line.substring(1, line.length - 1)
                    stack.addLast(name)
                    if (name == "STREAM") current = linkedMapOf()
                }

                line.startsWith("[/") -> {
                    val name = line.substring(2, line.length - 1)
                    if (name == "STREAM") {
                        current?.let { sections.add(ProbeSection(it)) }
                        current = null
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                }

                "=" in line -> {
                    val separator = line.indexOf('=')
                    val key = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    when (stack.lastOrNull()) {
                        "FORMAT" -> container = container + (key to value)
                        "STREAM" -> current?.put(key, value)
                        else -> Unit
                    }
                }
            }
        }
        return ProbeDescription(sections, container)
    }

    companion object {
        /**
         * Приводит пару целых к взаимно простому виду.
         *
         * @param numerator числитель
         * @param denominator знаменатель
         * @return пара с НОД, равным единице
         */
        fun reduce(
            numerator: BigInteger,
            denominator: BigInteger,
        ): Pair<BigInteger, BigInteger> {
            val divisor = numerator.gcd(denominator)
            return numerator / divisor to denominator / divisor
        }
    }
}

/**
 * Отказ с кодом `SOURCE_UNREADABLE`.
 *
 * Вынесено в отдельную функцию, потому что такой отказ возникает в десятке
 * мест опроса, и текст в каждом из них должен быть одинаково понятным
 * оператору: что за файл и что с ним не так (FR-092).
 *
 * @param sourcePath путь к файлу
 * @param reason причина на русском
 * @return исключение, готовое к отдаче клиенту
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun unreadableFile(
    sourcePath: Path,
    reason: String,
): DomainException =
    DomainException(
        ErrorCode.SOURCE_UNREADABLE,
        "исходный файл эпизода «$sourcePath» недоступен: $reason",
    )
