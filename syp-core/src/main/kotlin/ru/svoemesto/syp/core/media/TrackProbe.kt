package ru.svoemesto.syp.core.media

/**
 * Дорожка видеофайла, как её видит зонд.
 *
 * @property index номер дорожки в файле, как его называет зонд; уникален в
 *   пределах видеофайла и служит ключом при повторном определении
 * @property ordinal порядковый номер среди дорожек того же вида
 * @property codecType вид дорожки: видео, аудио, субтитры, данные, вложение
 * @property codecName название кодека, каким его называет зонд
 */
data class MediaTrack(
    val index: Int,
    val ordinal: Int,
    val codecType: String,
    val codecName: String?,
)

/**
 * Определение дорожек видеофайла зондом.
 *
 * **Зачем сохранять, а не спрашивать каждый раз.** Видеофайл — это единицы
 * гигабайт, а список дорожек нужен при каждом открытии: показать в интерфейсе,
 * повесить на дорожку свойство. Файл при этом не перечитывается, а список лежит
 * в базе.
 *
 * **Чем определяем.** `ffprobe` — он уже есть в образе бэкенда, и тем же
 * декодером, каким читаются кадры. `mediainfo` даёт богаче, но его в образе нет,
 * и ради списка дорожек тащить второй инструмент незачем.
 *
 * @property probePath путь к программе-зонду; приходит из конфигурации
 *   развёртывания, а не из данных (ADR-0010)
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class TrackProbe(
    private val probePath: String,
) {
    /**
     * Определяет дорожки файла.
     *
     * @param sourcePath путь к видеофайлу
     * @return дорожки по возрастанию номера
     * @throws TrackProbeFailed если зонд не запустился или не ответил
     */
    fun probe(sourcePath: String): List<MediaTrack> {
        val output =
            try {
                val process =
                    ProcessBuilder(
                        probePath,
                        "-v",
                        "error",
                        "-show_entries",
                        "stream=index,codec_type,codec_name",
                        "-of",
                        "default=noprint_wrappers=1:nokey=0",
                        sourcePath,
                    ).redirectErrorStream(false).start()
                val text = process.inputStream.bufferedReader().use { it.readText() }
                val errors = process.errorStream.bufferedReader().use { it.readText() }
                if (!process.waitFor(PROBE_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw TrackProbeFailed("Зонд не ответил за $PROBE_TIMEOUT_SECONDS с на «$sourcePath»")
                }
                if (process.exitValue() != 0) {
                    throw TrackProbeFailed(
                        "Зонд закончил с кодом ${process.exitValue()}: ${errors.trim().take(300)}",
                    )
                }
                text
            } catch (failure: TrackProbeFailed) {
                throw failure
            } catch (failure: Exception) {
                throw TrackProbeFailed(
                    "Не удалось запустить зонд «$probePath»: ${failure.message}. " +
                        "Проверьте путь в конфигурации развёртывания (ADR-0010)",
                )
            }
        val tracks = mutableListOf<MediaTrack>()
        val perType = mutableMapOf<String, Int>()
        // Строки вида: `index=0` / `codec_type=video` / `codec_name=h264`, по три
        // на дорожку. Разбор здесь намеренно простой: зонд печатает ключи
        // словами, а не JSON, и лишняя зависимость ради трёх полей не нужна.
        var index: Int? = null
        var type: String? = null
        var name: String? = null

        fun flush() {
            val current = index
            val currentType = type
            if (current != null && currentType != null) {
                val position = perType.getOrDefault(currentType, 0)
                perType[currentType] = position + 1
                tracks +=
                    MediaTrack(
                        index = current,
                        ordinal = position,
                        codecType = currentType,
                        codecName = name,
                    )
            }
            index = null
            type = null
            name = null
        }
        for (line in output.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("index=") -> index = trimmed.removePrefix("index=").trim().toIntOrNull()
                trimmed.startsWith("codec_type=") -> type = trimmed.removePrefix("codec_type=").trim()
                trimmed.startsWith("codec_name=") -> name = trimmed.removePrefix("codec_name=").trim()
            }
        }
        flush()
        if (tracks.isEmpty()) {
            throw TrackProbeFailed("Зонд не нашёл в «$sourcePath» ни одной дорожки — файл пуст или не видео")
        }
        return tracks.sortedBy { it.index }
    }

    private companion object {
        /** Сколько ждать ответа зонда: файл большой, но метаданные читаются из заголовка. */
        const val PROBE_TIMEOUT_SECONDS: Long = 60
    }
}

/** Отказ определения дорожек: зонд молчал, ответил неразборчиво или файла нет. */
class TrackProbeFailed(
    message: String,
) : RuntimeException(message)
