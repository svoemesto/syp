package ru.svoemesto.syp.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.integrity.ChecksumController
import ru.svoemesto.syp.admin.integrity.ChecksumEnqueuer
import ru.svoemesto.syp.admin.integrity.ChecksumRegistry
import ru.svoemesto.syp.admin.integrity.HashJob
import ru.svoemesto.syp.admin.jobs.AdminJobWorker
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.FileSystemStorage
import ru.svoemesto.syp.core.storage.ObjectStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Сборка справочника сумм и исполнителя задания `HASH`.
 *
 * Воркер собирается здесь же, а не в отдельной конфигурации: без него
 * поставленное задание не исполнилось бы, а невыполненное задание означало
 * бы, что сумма считается вечно.
 *
 * **Параллелизм и видеокарта.** Подсчёт суммы не использует видеокарту, но он
 * и не бесплатен: он читает 5,6 ГБ. Параллелизм задаётся развёртыванием, а не
 * кодом, — иначе смена числа заданий требовала бы пересборки.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class IntegrityConfiguration {
    /**
     * Собирает очередь заданий.
     *
     * @param database доступ к базе
     * @return очередь заданий
     */
    @Bean
    fun jobQueue(database: Db): JobQueue = JobQueue(database)

    /**
     * Собирает справочник сумм исходников.
     *
     * @param database доступ к базе
     * @return справочник сумм
     */
    @Bean
    fun checksumRegistry(database: Db): ChecksumRegistry = ChecksumRegistry(database)

    /**
     * Собирает исполнитель подсчёта суммы.
     *
     * @param episodeStore хранилище эпизодов
     * @param registry справочник сумм
     * @return исполнитель задания `HASH`
     */
    @Bean
    fun hashJob(
        episodeStore: EpisodeStore,
        registry: ChecksumRegistry,
    ): HashJob = HashJob(episodeStore, registry)

    /**
     * Собирает постановщик пересчёта.
     *
     * @param queue очередь заданий
     * @param episodeStore хранилище эпизодов
     * @param registry справочник сумм
     * @return постановщик подсчёта
     */
    @Bean
    fun checksumEnqueuer(
        queue: JobQueue,
        episodeStore: EpisodeStore,
        registry: ChecksumRegistry,
    ): ChecksumEnqueuer = ChecksumEnqueuer(queue, episodeStore, registry)

    /**
     * Собирает эндпоинты сверки целостности.
     *
     * @param enqueuer постановщик пересчёта
     * @param registry справочник сумм
     * @param episodeStore хранилище эпизодов
     * @return контроллер суммы
     */
    @Bean
    fun checksumController(
        enqueuer: ChecksumEnqueuer,
        registry: ChecksumRegistry,
        episodeStore: EpisodeStore,
    ): ChecksumController = ChecksumController(enqueuer, registry, episodeStore)

    /**
     * Собирает объектное хранилище артефактов.
     *
     * Артефакты размещаются на SSD (Д-8, FR-021), и подключение к MinIO в
     * этой порции ещё не сделано: клиент есть, а реализации — нет. Пока
     * артефакты пишутся в локальный каталог на SSD, путь к которому приходит
     * из окружения. На подсчёт суммы это не влияет: задание `HASH` не
     * производит артефактов вовсе.
     *
     * @return хранилище артефактов
     */
    @Bean
    fun objectStorage(): ObjectStorage =
        FileSystemStorage(
            Path
                .of(optional(ENV_STORAGE_ROOT) ?: DEFAULT_STORAGE_ROOT)
                .also { Files.createDirectories(it) },
        )

    /**
     * Собирает реестр артефактов.
     *
     * @param database доступ к базе
     * @param storage объектное хранилище
     * @return реестр артефактов
     */
    @Bean
    fun artifactRegistry(
        database: Db,
        storage: ObjectStorage,
    ): ArtifactRegistry = ArtifactRegistry(database, storage)

    /**
     * Собирает исполнителя заданий админского бэкенда.
     *
     * Список исполнителей приходит целиком: каждый вид задания объявляет
     * свой исполнитель сам, а воркер строит из них таблицу. Вид без
     * исполнителя уходит в ошибку с текстом, а не висит в очереди — это
     * поведение воркера, а не совпадение.
     *
     * @param queue очередь заданий
     * @param handlers все зарегистрированные исполнители
     * @param artifactRegistry реестр артефактов
     * @param database доступ к базе
     * @param concurrency сколько заданий выполняется одновременно
     * @param gpuConcurrency сколько заданий могут использовать видеокарту
     * @return воркер заданий
     */
    @Bean(initMethod = "start")
    fun adminJobWorker(
        queue: JobQueue,
        handlers: List<JobHandler>,
        artifactRegistry: ArtifactRegistry,
        database: Db,
    ): AdminJobWorker =
        AdminJobWorker(
            queue = queue,
            handlers = handlers.associateBy { it.kind },
            artifactRegistry = artifactRegistry,
            db = database,
            // Значения по умолчанию у методов @Bean недопустимы: Spring ищет
            // фабричный метод без аргументов, и параметр со значением по
            // умолчанию превращает бин в «фабричный метод не найден» — весь
            // контекст не поднимается. Поэтому значения читаются здесь.
            concurrency = optional(ENV_WORKER_CONCURRENCY)?.toInt() ?: DEFAULT_CONCURRENCY,
            gpuConcurrency = optional(ENV_GPU_CONCURRENCY)?.toInt() ?: DEFAULT_GPU_CONCURRENCY,
        )

    companion object {
        /** Имя переменной окружения с каталогом артефактов на SSD. */
        const val ENV_STORAGE_ROOT: String = "SYP_STORAGE_ROOT"

        /** Имя переменной окружения со сколько заданий выполняется одновременно. */
        const val ENV_WORKER_CONCURRENCY: String = "SYP_WORKER_CONCURRENCY"

        /** Имя переменной окружения со сколько заданий используют видеокарту. */
        const val ENV_GPU_CONCURRENCY: String = "SYP_GPU_CONCURRENCY"

        /** Каталог артефактов, если переменная окружения не задана. */
        const val DEFAULT_STORAGE_ROOT: String = "/data/syp-storage"

        /**
         * Сколько заданий выполняется одновременно.
         *
         * Значение по умолчанию — два: подсчёт суммы читает диск, и три
         * одновременных чтения не ускоряют работу, а мешают друг другу.
         */
        const val DEFAULT_CONCURRENCY: Int = 2

        /**
         * Сколько заданий могут использовать видеокарту.
         *
         * Подсчёт суммы видеокарты не касается; значение оставлено тем же, что
         * и в развёртывании, — чтобы следующий вид заданий не потребовал
         * пересборки ради смены числа.
         */
        const val DEFAULT_GPU_CONCURRENCY: Int = 1
    }
}
