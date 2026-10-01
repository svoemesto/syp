package ru.svoemesto.syp.admin.jobs

/**
 * Виды заданий, которые берёт админский воркер.
 *
 * Перечень закрытый и совпадает с ограничением `job_kind_known` миграции
 * `05_jobs.sql`: база отвергает любой другой вид, поэтому «случайно взяли не
 * то» невозможно даже при ошибке в коде.
 *
 * Вида `ASSEMBLE` здесь нет и быть не может: сборка подборки выполняется на
 * машине пользователя по сценарию (ADR-0009, research.md Т-20).
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object JobKinds {
    /** Все виды заданий админского воркера, в порядке учёта параллелизма. */
    val ADMIN: List<ru.svoemesto.syp.core.jobs.JobKind> =
        listOf(
            ru.svoemesto.syp.core.jobs.JobKind.HASH,
            ru.svoemesto.syp.core.jobs.JobKind.ANALYZE,
            ru.svoemesto.syp.core.jobs.JobKind.FACES,
            ru.svoemesto.syp.core.jobs.JobKind.TRAIN,
        )

    /** Виды заданий, требующие видеокарты: выполняются строго по одному. */
    val GPU: Set<ru.svoemesto.syp.core.jobs.JobKind> =
        setOf(ru.svoemesto.syp.core.jobs.JobKind.FACES)

    /**
     * Виды заданий для указанного числа слотов видеокарты.
     *
     * Разделение по видеокарте и по процессору не декоративно: видеокарта одна,
     * и два задания, требующие её одновременно, будут мешать друг другу
     * (контракт очереди § 4).
     *
     * @param gpuSlots сколько заданий могут одновременно использовать видеокарту
     * @return список видов, которые воркер берёт при таком распределении
     */
    fun forGpuSlots(gpuSlots: Int): List<ru.svoemesto.syp.core.jobs.JobKind> {
        require(gpuSlots >= 0) { "Число слотов видеокарты отрицательно: $gpuSlots" }
        if (gpuSlots == 0) {
            return ADMIN.filterNot { it.requiresGpu }
        }
        return ADMIN
    }

    /**
     * Виды заданий, которые воркер берёт на процессорных слотах.
     *
     * @return список видов, не требующих видеокарты
     */
    fun cpuOnly(): List<ru.svoemesto.syp.core.jobs.JobKind> = ADMIN.filterNot { it.requiresGpu }
}

/**
 * Исполнитель одного задания.
 *
 * Исполнитель возвращает результат, а **не** меняет состояние задания:
 * состоянием занимается [AdminJobWorker], чтобы переходы состояний шли
 * через один проверяемый путь, а не через каждый вид задания отдельно.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
interface JobHandler {
    /** Вид задания, который обрабатывает исполнитель. */
    val kind: ru.svoemesto.syp.core.jobs.JobKind

    /**
     * Выполняет задание.
     *
     * @param job задание к выполнению
     * @param progress приёмник прогресса: исполнитель сообщает движение, а не
     *   возлагает это на интерфейс
     * @return результат выполнения
     * @throws ru.svoemesto.syp.core.media.ExternalProgramFailed если внешняя
     *   программа завершилась с ненулевым кодом
     */
    fun execute(
        job: ru.svoemesto.syp.core.jobs.Job,
        progress: (ru.svoemesto.syp.core.jobs.JobProgress) -> Unit,
    ): JobResult
}

/**
 * Результат выполнения задания.
 *
 * @property artifactId артефакт результата в состоянии `READY` либо `null`,
 *   если задание артефакта не производит
 * @property note пояснение для оператора: что получилось
 * @property progressTotal общий объём работы, известный по завершении
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class JobResult(
    val artifactId: Long? = null,
    val note: String = "",
    val progressTotal: Long = 1,
)
