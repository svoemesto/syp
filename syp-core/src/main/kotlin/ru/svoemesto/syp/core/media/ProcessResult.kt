package ru.svoemesto.syp.core.media

/**
 * Результат запуска внешней программы.
 *
 * Класс существует ради одного правила, которое в старом проекте не
 * соблюдалось **ни одним** из двенадцати вызовов ffmpeg: упавшая программа
 * обязана выглядеть как ошибка. Поэтому ненулевой код завершения — это
 * [outcome] с [Outcome.FAILED] и текстом, а не «пустой результат» (FR-004,
 * FR-092, SC-005).
 *
 * @property exitCode код завершения процесса
 * @property output объединённый вывод программы: стандартный вывод и вывод
 *   ошибок в одном потоке
 * @property durationMillis сколько выполнялась программа
 * @property timedOut прервана ли программа по таймауту
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ProcessResult(
    val exitCode: Int,
    val output: String,
    val durationMillis: Long,
    val timedOut: Boolean = false,
) {
    /** Как закончилась программа. */
    val outcome: Outcome
        get() =
            when {
                timedOut -> Outcome.TIMED_OUT
                exitCode == 0 -> Outcome.SUCCESS
                else -> Outcome.FAILED
            }

    /** Завершилась ли программа успешно. */
    val isSuccess: Boolean
        get() = outcome == Outcome.SUCCESS

    /**
     * Текст ошибки для оператора.
     *
     * Текст должен быть читаемым, а не «процесс завершился с кодом 1»:
     * оператор видит его в списке заданий и по нему решает, что делать.
     * Поэтому сюда попадают последние строки вывода программы.
     *
     * @param programName имя программы для сообщения
     * @return текст ошибки на русском
     */
    fun errorText(programName: String): String {
        require(!isSuccess) { "errorText вызван у успешного результата: это ошибка вызывающего кода" }
        val tail =
            output
                .trim()
                .lines()
                .takeLast(TAIL_LINES)
                .joinToString("\n")
        val headline =
            if (timedOut) {
                "$programName не уложилась в отведённое время"
            } else {
                "$programName завершилась с кодом $exitCode"
            }
        return if (tail.isEmpty()) headline else "$headline:\n$tail"
    }

    /** Как закончился запуск внешней программы. */
    enum class Outcome {
        /** Код завершения нулевой: работа выполнена. */
        SUCCESS,

        /** Код завершения ненулевой: работа не выполнена. */
        FAILED,

        /** Программа прервана по таймауту. */
        TIMED_OUT,
    }

    private companion object {
        /** Сколько последних строк вывода попадает в текст ошибки. */
        const val TAIL_LINES: Int = 20
    }
}
