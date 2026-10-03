package ru.svoemesto.syp.public.config

import io.minio.MinioClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.recipe.RecipeStore
import ru.svoemesto.syp.core.signing.VerificationKey
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.FileSystemStorage
import ru.svoemesto.syp.core.storage.MinioObjectStorage
import ru.svoemesto.syp.core.storage.ObjectStorage
import ru.svoemesto.syp.public.recipe.RecipeDeliveryController
import ru.svoemesto.syp.public.recipe.VerificationKeyEndpoint
import java.nio.file.Files
import java.nio.file.Path

/**
 * Сборка компонентов публичного бэкенда.
 *
 * Ключ проверки подписи собирается из переменных окружения. Закрытого ключа
 * у публичного бэкенда нет **и не должно быть**: сценарий приходит уже
 * подписанным, а подписывать что-либо самому здесь нечем (ADR-0011,
 * последствие 2). В `deploy/docker-compose.yml` контейнеру публичной части
 * передаётся только `SYP_SIGNING_PUBLIC_KEY`.
 *
 * **Исполнителя заданий здесь нет**: ни класса, ни зависимости очереди
 * (FR-085, research.md Т-20). Выдача сценария — операция над метаданными,
 * она выполняется сразу и в очередь не ставится.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class PublicConfiguration {
    /**
     * Собирает доступ к базе сырым JDBC.
     *
     * @return доступ к базе
     */
    @Bean
    fun database(): Db =
        Db(
            url = PublicPorts.requiredEnv(PublicPorts.ENV_DB_URL),
            user = PublicPorts.requiredEnv(PublicPorts.ENV_DB_USER),
            password = PublicPorts.requiredEnv(PublicPorts.ENV_DB_PASSWORD),
        )

    /**
     * Собирает объектное хранилище артефактов.
     *
     * Канонические байты сценария лежат там же, где листы превью, — на SSD
     * (Д-8). Реализация клиента MinIO в проекте ещё нет, и подменять его
     * файловым каталогом — то же решение, что принято в админском бэкенде.
     *
     * @return хранилище артефактов
     */
    @Bean
    fun objectStorage(): ObjectStorage {
        val endpoint = optional(PublicPorts.ENV_STORAGE_ENDPOINT)
        if (endpoint == null) {
            return FileSystemStorage(
                Path
                    .of(optional(PublicPorts.ENV_STORAGE_ROOT) ?: DEFAULT_STORAGE_ROOT)
                    .also { Files.createDirectories(it) },
            )
        }
        val client =
            MinioClient
                .builder()
                .endpoint(endpoint)
                .credentials(
                    optional(PublicPorts.ENV_STORAGE_ACCESS_KEY) ?: DEFAULT_STORAGE_ACCESS_KEY,
                    optional(PublicPorts.ENV_STORAGE_SECRET_KEY) ?: DEFAULT_STORAGE_SECRET_KEY,
                ).build()
        return MinioObjectStorage(client, optional(PublicPorts.ENV_STORAGE_BUCKET) ?: DEFAULT_STORAGE_BUCKET)
    }

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
     * Собирает хранилище сценариев.
     *
     * @param database доступ к базе
     * @return хранилище сценариев сборки
     */
    @Bean
    fun recipeStore(database: Db): RecipeStore = RecipeStore(database)

    /**
     * Собирает ключ проверки подписи из окружения.
     *
     * @return открытый ключ с идентификатором, которым подписываются сценарии
     * @throws IllegalStateException если переменные окружения не заданы: молча
     *   отдавать ключ по умолчанию означало бы проверять сценарии не тем ключом
     */
    @Bean
    fun verificationKey(): VerificationKey =
        VerificationKey.of(
            keyId = PublicPorts.requiredEnv(PublicPorts.ENV_SIGNING_KEY_ID),
            base64PublicKey = PublicPorts.requiredEnv(PublicPorts.ENV_SIGNING_PUBLIC_KEY),
            notBefore = optional(PublicPorts.ENV_SIGNING_KEY_NOT_BEFORE) ?: VerificationKey.DEFAULT_NOT_BEFORE,
        )

    /**
     * Собирает эндпоинт открытого ключа проверки.
     *
     * @param verificationKey ключ проверки
     * @return эндпоинт ключа
     */
    @Bean
    fun verificationKeyEndpoint(verificationKey: VerificationKey): VerificationKeyEndpoint = VerificationKeyEndpoint(verificationKey)

    /**
     * Собирает эндпоинты выдачи сценария.
     *
     * @param recipes хранилище сценариев
     * @param artifacts реестр артефактов
     * @return контроллер выдачи
     */
    @Bean
    fun recipeDeliveryController(
        recipes: RecipeStore,
        artifacts: ArtifactRegistry,
    ): RecipeDeliveryController = RecipeDeliveryController(recipes, artifacts)

    private companion object {
        /** Каталог артефактов по умолчанию, если окружение его не задаёт. */
        const val DEFAULT_STORAGE_ROOT: String = "/data/syp-storage"

        /** Корзина, если переменная окружения не задана. */
        const val DEFAULT_STORAGE_BUCKET: String = "syp-media"

        /** Ключ доступа, если переменная окружения не задана. */
        const val DEFAULT_STORAGE_ACCESS_KEY: String = "sypadmin"

        /** Секрет доступа, если переменная окружения не задана. */
        const val DEFAULT_STORAGE_SECRET_KEY: String = "sypadmin"
    }
}

/**
 * Чтение необязательной переменной окружения.
 *
 * @param name имя переменной
 * @return значение переменной либо `null`, если она не задана или пуста
 */
internal fun optional(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
