package ru.svoemesto.syp.core.media

import ru.svoemesto.syp.core.jobs.JobProgress
import java.io.File
import java.io.InputStreamReader
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Единая точка запуска внешних программ.
 *
 * Класс существует, потому что в старом проекте не соблюдались два правила
 * сразу: **вывод и ошибки шли разными потоками**, и **код завершения не
 * проверялся ни разу**. Оба нарушения приводят к одному и тому же — задание
 * помечается «сделано» с оборванным файлом.
 *
 * Что гарантирует класс:
 *
 * 1. `redirectErrorStream(true)` — вывод и ошибки объединены в один поток.
 *    Ошибка не может остаться незамеченной в другом потоке.
 * 2. Код завершения проверяется **всегда**; ненулевой даёт [ProcessResult]
 *    с [ProcessResult.Outcome.FAILED], и вызывающий обязан перевести задание
 *    в `ERROR` (constitution IV.2).
 * 3. Прогресс читается из потока программы, а не вычисляется по индексу
 *    цикла: поток ffmpeg уже печатает `frame=.../...`, и дублировать этот
 *    разбор в коде было бы вторым источником правды (research.md Т-15).
 * 4. Есть таймаут и разрушающее прерывание: программа, зависшая навсегда,
 *    иначе занимала бы воркер до перезапуска контейнера.
 *
 * Путь к программе приходит **из конфигурации развёртывания**, а не из
 * данных задания: путь из данных — это путь из интернета (ADR-0010,
 * ограничение 2).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ExternalProgram(
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    /**
     * Запускает программу и ждёт её завершения.
     *
     * @param executablePath путь к программе; приходит из конфигурации
     * @param arguments аргументы запуска
     * @param workingDirectory рабочий каталог программы
     * @param environment дополнительные переменные окружения программы
     * @param progressReader разбор строки вывода в прогресс; `null`, если
     *   прогресс программе не нужен
     * @param onProgress вызывается при каждом распознанном изменении прогресса
     * @return результат запуска, **в том числе при неудаче**
     * @throws ExternalProgramException если программу не удалось запустить
     */
    fun run(
        executablePath: String,
        arguments: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        progressReader: ((String) -> JobProgress?)? = null,
        onProgress: ((JobProgress) -> Unit)? = null,
    ): ProcessResult {
        val startedAt = System.nanoTime()
        val command = listOf(executablePath) + arguments

        val builder =
            ProcessBuilder(command).apply {
                workingDirectory?.let { directory(it) }
                environment().putAll(environment)
                // Обязательно: без этого ошибка уходит в отдельный поток, который
                // никто не читает, и падение выглядит как пустой успех.
                redirectErrorStream(true)
            }

        val process =
            try {
                builder.start()
            } catch (failure: Exception) {
                throw ExternalProgramException(
                    "Не удалось запустить программу «$executablePath»: ${failure.message}. " +
                        "Проверьте путь в конфигурации развёртывания",
                    failure,
                )
            }

        val collected = StringBuilder()
        val lastReported = AtomicBoolean(false)
        var timedOut = false

        try {
            InputStreamReader(process.inputStream, Charsets.UTF_8).use { reader ->
                val line = StringBuilder()
                while (true) {
                    val read = reader.read()
                    if (read == -1) break
                    val character = read.toChar()
                    // \r без \n бывает у ffmpeg: прогресс печатается кареткой
                    // возврата, и построчное чтение по \n его пропускает.
                    if (character == '\n' || character == '\r') {
                        if (line.isNotEmpty()) {
                            val text = line.toString()
                            line.setLength(0)
                            collected.append(text).append('\n')
                            if (progressReader != null && onProgress != null) {
                                val progress = progressReader(text)
                                if (progress != null && lastReported.compareAndSet(false, true)) {
                                    onProgress(progress)
                                    lastReported.set(false)
                                }
                            }
                        }
                    } else {
                        line.append(character)
                    }
                }
                if (line.isNotEmpty()) {
                    collected.append(line)
                }
            }

            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                timedOut = true
                process.destroyForcibly()
                process.waitFor(DESTROY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            }
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw ExternalProgramException(
                "Программа «$executablePath» прервана: поток воркера остановлен",
                interrupted,
            )
        } finally {
            runCatching { process.inputStream.close() }
        }

        val exitCode = if (timedOut) -1 else process.exitValue()
        return ProcessResult(
            exitCode = exitCode,
            output = collected.toString(),
            durationMillis = (System.nanoTime() - startedAt) / 1_000_000,
            timedOut = timedOut,
        )
    }

    /**
     * Запускает программу и **требует** успеха.
     *
     * Метод для мест, где ненулевой код завершения означает не «вернуть
     * результат», а прервать задание с текстом ошибки: вызывающий не может
     * ошибиться и забыть проверить код возврата.
     *
     * @param executablePath путь к программе
     * @param arguments аргументы запуска
     * @param workingDirectory рабочий каталог программы
     * @param environment дополнительные переменные окружения программы
     * @param progressReader разбор строки вывода в прогресс
     * @param onProgress вызывается при каждом распознанном изменении прогресса
     * @return успешный результат запуска
     * @throws ExternalProgramFailed если программа завершилась с ненулевым
     *   кодом или была прервана по таймауту
     */
    fun runOrFail(
        executablePath: String,
        arguments: List<String>,
        workingDirectory: File? = null,
        environment: Map<String, String> = emptyMap(),
        progressReader: ((String) -> JobProgress?)? = null,
        onProgress: ((JobProgress) -> Unit)? = null,
    ): ProcessResult {
        val result = run(executablePath, arguments, workingDirectory, environment, progressReader, onProgress)
        if (!result.isSuccess) {
            throw ExternalProgramFailed(result, File(executablePath).name)
        }
        return result
    }

    /**
     * Проверяет, что программа существует и отвечает версией.
     *
     * Нужен для проверки окружения **до** постановки задания: отсутствие
     * ffmpeg обнаруживается при приёме эпизода, а не через час работы.
     *
     * @param executablePath путь к программе
     * @return текст ответа программы на `-version`
     * @throws ExternalProgramFailed если программа не найдена или не отвечает
     */
    fun probe(executablePath: String): String =
        runOrFail(executablePath, listOf("-version")).output.lineSequence().firstOrNull()
            ?: ""

    companion object {
        /** Таймаут по умолчанию: долгая работа нормальна, зависание — нет. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofHours(6)

        /** Сколько ждать после разрушающего прерывания. */
        private const val DESTROY_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * Ошибка запуска внешней программы.
 *
 * Отдельный тип отличается от «программа завершилась с ненулевым кодом»:
 * первое — не удалось даже начать, второе — работа началась и не удалась.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ExternalProgramException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Внешняя программа завершилась с ненулевым кодом или была прервана.
 *
 * Исключение, а не пустой результат: задание обязано уйти в `ERROR` с этим
 * текстом (FR-092). «Успех по умолчанию» здесь означал бы битый файл,
 * помеченный как готовый.
 *
 * @property result результат запуска, который привёл к ошибке
 * @property programName имя программы для текста ошибки
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ExternalProgramFailed(
    val result: ProcessResult,
    val programName: String,
) : RuntimeException(result.errorText(programName))
