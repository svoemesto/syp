package ru.svoemesto.syp.core.jobs

import java.security.MessageDigest

/**
 * Прогресс задания.
 *
 * Прогресс задаётся явно (research.md Т-15): для проходов внешних программ —
 * поток прогресса самой программы, для собственных вычислений — счётчик
 * обработанных единиц из известного общего объёма, для подсчёта суммы — число
 * прочитанных байт. Никакого «прогресс по индексу цикла»: он показывает
 * движение, когда работы ещё нет, и стоит на месте, когда она идёт.
 *
 * Прогресс пишется в задание периодически, а не в конце: интерфейс должен
 * видеть движение, а пользователь — понимать, что задание не зависло.
 *
 * @property done сколько единиц работы обработано
 * @property total сколько единиц работы всего, `0` пока неизвестно
 * @property note что задание делает сейчас: фаза, кадр, этап, прочитано байт
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class JobProgress(
    val done: Long,
    val total: Long,
    val note: String = "",
) {
    /**
     * Доля выполнения от `0.0` до `1.0`.
     *
     * Пока общий объём неизвестен, доля равна `0.0`: показывать «50 %» при
     * неизвестном знаменателе — значит показывать выдуманное число.
     */
    val fraction: Double
        get() = if (total <= 0L) 0.0 else (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)

    /** Прогресс не известен: объём работы ещё не вычислен. */
    val isUnknownTotal: Boolean
        get() = total <= 0L

    /**
     * Проверяет, что прогресс не уехал назад.
     *
     * Прогресс задания монотонен: вернуться назад может только восстановление
     * из сохранённого значения после прерывания, и тогда это осознанное
     * действие, а не побочный эффект расчёта.
     *
     * @param previous предыдущее значение прогресса
     * @throws IllegalArgumentException если новый прогресс меньше прежнего
     */
    fun requireNotBehind(previous: JobProgress) {
        if (done < previous.done) {
            throw IllegalArgumentException(
                "Прогресс задания уменьшился: было ${previous.done}, стало $done. " +
                    "Прогресс монотонен (FR-003)",
            )
        }
        if (total != 0L && previous.total != 0L && total < previous.total) {
            throw IllegalArgumentException(
                "Общий объём работы уменьшился: было ${previous.total}, стало $total. " +
                    "Прогресс монотонен (FR-003)",
            )
        }
    }

    companion object {
        /** Пустой прогресс: работа не начата, объём неизвестен. */
        val EMPTY: JobProgress = JobProgress(0, 0)

        /**
         * Разбирает строку прогресса вида `frame=12345/88643`.
         *
         * Так прогресс выводит ffmpeg; из неё же берётся прирост по кадрам.
         *
         * @param text строка потока прогресса
         * @return прогресс или `null`, если строка не разобрана
         */
        fun parseFfmpegProgress(text: String): JobProgress? {
            val match = FRAME_PROGRESS.find(text) ?: return null
            val done = match.groupValues[1].toLongOrNull() ?: return null
            val total = match.groupValues[2].toLongOrNull() ?: return null
            val speed = match.groupValues[3]
            return JobProgress(
                done = done,
                total = total,
                note = if (speed.isEmpty()) "" else "скорость $speed",
            )
        }

        /** Шаблон прогресса ffmpeg: `frame=12345/88643`. */
        private val FRAME_PROGRESS =
            Regex("""frame=\s*(\d+)\s*/\s*(\d+)(?:[^\n]*?speed=\s*([\d.]+x))?""")

        /**
         * Строит прогресс по числу обработанных единиц.
         *
         * @param done сколько обработано
         * @param total сколько всего
         * @param note что делается сейчас
         * @return прогресс
         */
        fun of(
            done: Long,
            total: Long,
            note: String = "",
        ): JobProgress = JobProgress(done, total, note)
    }
}

/**
 * Хеш входных параметров задания.
 *
 * По нему воркер понимает, можно ли пропустить уже выполненную работу
 * (Р-10, контракт очереди § 3.3). В расчёт входят версия алгоритма, набор
 * порогов и настроек серии и состав входа — всё, что меняет результат.
 *
 * Хеш считается по значениям **в указанном порядке**: одинаковые значения в
 * разном порядке дают разный хеш, и это правильно — разный порядок означает
 * разные параметры.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object ParamsHash {
    /**
     * Считает SHA-256 набора значений в указанном порядке.
     *
     * @param values значения параметров
     * @return 64 шестнадцатеричных символа в нижнем регистре
     */
    fun of(vararg values: Any?): String = of(values.toList())

    /**
     * Считает SHA-256 набора значений в указанном порядке.
     *
     * @param values значения параметров
     * @return 64 шестнадцатеричных символа в нижнем регистре
     */
    fun of(values: List<Any?>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val piece =
                when (value) {
                    null -> ""
                    is ByteArray -> value.joinToString("") { byte -> "%02x".format(byte) }
                    else -> value.toString()
                }
            digest.update(piece.toByteArray(Charsets.UTF_8))
            // Разделитель не даёт «ab» + «c» и «a» + «bc» дать один хеш.
            digest.update(0x1F)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

/**
 * Задание очереди в виде строки базы.
 *
 * @property id идентификатор задания
 * @property kind вид задания
 * @property state состояние
 * @property subject предмет работы
 * @property paramsJson параметры задания в виде JSON
 * @property paramsHash хеш параметров
 * @property algorithmVersion версия алгоритма, `null` пока не определена
 * @property progress текущий прогресс
 * @property errorText текст ошибки, обязателен в состоянии [JobState.ERROR]
 * @property createdAt когда задание поставлено в очередь
 * @property startedAt когда задание начали выполнять
 * @property finishedAt когда задание закончили
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Job(
    val id: Long,
    val kind: JobKind,
    val state: JobState,
    val subject: JobSubject,
    val paramsJson: String,
    val paramsHash: String,
    val algorithmVersion: String?,
    val progress: JobProgress,
    val errorText: String?,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?,
) {
    /** Задание ещё можно взять в работу: оно ждёт и не начато. */
    val isPickable: Boolean
        get() = state == JobState.WAITING
}
