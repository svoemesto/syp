package ru.svoemesto.syp.core.jobs

/**
 * Состояние задания.
 *
 * Ровно пять состояний, ровно этот набор — так же, как в правилах очереди
 * проекта. Меньше нельзя: без промежуточного состояния не отличить «взято в
 * работу» от «идёт работа», а интерфейс обязан это различать (FR-003).
 * Больше нельзя: лишнее состояние всегда означает, что кто-то не решил, что
 * делать при нём.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class JobState {
    /** Задание в очереди, ещё не взято. Ставит постановка в очередь. */
    WAITING,

    /** Задание взято воркером, идёт подготовка. Ставит воркер при захвате. */
    CREATING,

    /** Идёт собственно работа. Ставит воркер. */
    WORKING,

    /** Работа завершена и все внешние программы вернули нулевой код. */
    DONE,

    /** Работа не удалась. Обязателен текст ошибки: «процесс упал» ошибкой не является. */
    ERROR,
    ;

    /** Терминальное ли состояние: из него задание само не выходит. */
    val isTerminal: Boolean
        get() = this == DONE || this == ERROR

    companion object {
        /** Набор состояний в том виде, в каком он задан правилами очереди. */
        val ALL: List<JobState> = entries

        /**
         * Состояния, из которых задание может уйти в ошибку.
         *
         * @return список состояний, разрешающих переход в [ERROR]
         */
        fun canFail(): List<JobState> = listOf(WAITING, CREATING, WORKING)

        /**
         * Разбирает состояние из строки базы.
         *
         * @param value значение столбца `state`
         * @return состояние
         * @throws IllegalArgumentException если значения нет в наборе
         */
        fun parse(value: String): JobState =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "Неизвестное состояние задания: «$value». Допустимы: ${entries.joinToString()}",
                )
    }
}

/**
 * Разрешённые переходы между состояниями.
 *
 * Переходы заданы явно, а не «как получится»: невозможность попасть в
 * состояние обеспечивается проверкой здесь, а не рассуждением о коде.
 *
 * ```
 * WAITING ──захват воркером──► CREATING ──начало──► WORKING ──успех──► DONE
 *    │                             │                       │
 *    │                             └───────► ERROR ◄───────┘
 *    └─────────────────────────────────────────────────► ERROR (отмена оператором)
 * ```
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object JobTransitions {
    /** Таблица переходов: откуда и куда можно. */
    private val ALLOWED: Map<JobState, Set<JobState>> =
        mapOf(
            JobState.WAITING to setOf(JobState.CREATING, JobState.ERROR),
            JobState.CREATING to setOf(JobState.WORKING, JobState.ERROR),
            JobState.WORKING to setOf(JobState.DONE, JobState.ERROR),
            JobState.DONE to emptySet(),
            JobState.ERROR to emptySet(),
        )

    /**
     * Разрешён ли переход из одного состояния в другое.
     *
     * @param from текущее состояние
     * @param to новое состояние
     * @return `true`, если переход допустим
     */
    fun isAllowed(
        from: JobState,
        to: JobState,
    ): Boolean = to in (ALLOWED[from] ?: emptySet())

    /**
     * Проверяет переход и объясняет отказ.
     *
     * @param from текущее состояние
     * @param to новое состояние
     * @throws JobTransitionException если переход запрещён
     */
    fun require(
        from: JobState,
        to: JobState,
    ) {
        if (!isAllowed(from, to)) {
            throw JobTransitionException(
                "Переход задания ${from.name} → ${to.name} запрещён. " +
                    "Из ${from.name} можно только в ${(ALLOWED[from] ?: emptySet()).joinToString()}",
            )
        }
    }
}

/**
 * Отказ при попытке недопустимого перехода состояния задания.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class JobTransitionException(
    message: String,
) : IllegalStateException(message)

/**
 * Вид задания.
 *
 * Набор видов задан контрактом очереди. Вида `ASSEMBLE` **не существует**:
 * сборка подборки выполняется на машине пользователя по сценарию (ADR-0009),
 * и на сервере ей нечем заниматься.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class JobKind {
    /** Границы сцен и планов, листы превью, карта ключевых кадров. */
    ANALYZE,

    /** Поиск лиц в каждом кадре, эмбеддинги, кластеры. */
    FACES,

    /** Обучение распознавания, новая версия модели. */
    TRAIN,

    /** Подсчёт `sha256` исходного файла серии. */
    HASH,
    ;

    /** Требует ли этот вид задания видеокарты. Видеокарта одна (contract § 4). */
    val requiresGpu: Boolean
        get() = this == FACES

    companion object {
        /**
         * Разбирает вид задания из строки базы.
         *
         * @param value значение столбца `kind`
         * @return вид задания
         * @throws IllegalArgumentException если вида нет в наборе
         */
        fun parse(value: String): JobKind =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "Неизвестный вид задания: «$value». Допустимы: ${entries.joinToString()}",
                )
    }
}

/**
 * Предмет работы задания.
 *
 * Задание либо привязано к объекту базы (серия, версия модели), либо нет.
 * Пустой предмет — не ошибка: анализ структуры вбора не требует.
 *
 * @property type вид объекта или `null`
 * @property identifier идентификатор объекта или `null`
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class JobSubject(
    val type: String?,
    val identifier: Long?,
) {
    companion object {
        /** Задание не привязано к объекту. */
        val NONE: JobSubject = JobSubject(null, null)

        /** Задание работает с серией. */
        fun series(seriesId: Long): JobSubject = JobSubject("SERIES", seriesId)

        /** Задание работает с версией модели. */
        fun modelVersion(versionId: Long): JobSubject = JobSubject("MODEL_VERSION", versionId)
    }
}
