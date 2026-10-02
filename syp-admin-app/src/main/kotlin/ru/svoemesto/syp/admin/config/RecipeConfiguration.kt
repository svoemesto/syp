package ru.svoemesto.syp.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.catalog.SerialSettingsStore
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.integrity.ChecksumRegistry
import ru.svoemesto.syp.admin.notify.NotificationPublisher
import ru.svoemesto.syp.admin.recipe.RecipeController
import ru.svoemesto.syp.admin.selection.RecipeBuilder
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.recipe.RecipeCatalog
import ru.svoemesto.syp.core.recipe.RecipeStore
import ru.svoemesto.syp.core.signing.Signer
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import java.time.Clock

/**
 * Сборка выдачи сценария сборки.
 *
 * **Закрытый ключ подписи живёт здесь и только здесь.** Он приходит из
 * `SYP_SIGNING_PRIVATE_KEY` в контейнер админки, и `deploy/docker-compose.yml`
 * публичной части эту переменную не передаёт: там только `SYP_SIGNING_PUBLIC_KEY`.
 * Именно поэтому выдачу и подпись перенесли в админскую часть (ADR-0014,
 * решение владельца 2026-10-03).
 *
 * **Исполнителя заданий здесь нет.** Выдача сценария — операция над
 * метаданными: она не читает видео и не считает ничего тяжёлого, поэтому в
 * очередь не ставится (FR-003, research.md Т-20). Ставить её в очередь
 * означало бы ждать задание, чтобы сделать то, что делается за миллисекунды.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class RecipeConfiguration {
    /**
     * Собирает закрытый ключ подписи из окружения.
     *
     * @return подписант ключом контейнера админки
     * @throws IllegalStateException если переменные окружения не заданы: молча
     *   подписывать «чем попало» означало бы выпускать сценарии, которые
     *   машина пользователя не проверит
     */
    @Bean
    fun recipeSigner(): Signer =
        Signer(
            keyId = AdminPorts.requiredEnv(AdminPorts.ENV_SIGNING_KEY_ID),
            privateKey =
                Signer.privateKeyFromBase64(
                    AdminPorts.requiredEnv(AdminPorts.ENV_SIGNING_PRIVATE_KEY),
                ),
        )

    /**
     * Собирает хранилище сценариев.
     *
     * @param database доступ к базе
     * @return хранилище сценариев сборки
     */
    @Bean
    fun recipeStore(database: Db): RecipeStore = RecipeStore(database)

    /**
     * Собирает каталог сценариев: актуальность и помечание устаревшими.
     *
     * @param database доступ к базе
     * @return каталог сценариев
     */
    @Bean
    fun recipeCatalog(database: Db): RecipeCatalog = RecipeCatalog(database)

    /**
     * Собирает генератор сценария.
     *
     * @param database доступ к базе
     * @param seriesStore хранилище серий
     * @param checksums справочник эталонных сумм
     * @param settingsStore настройки сериала
     * @param recipes хранилище сценариев
     * @param catalog каталог сценариев
     * @param artifacts реестр артефактов
     * @param signer подписант
     * @param clock источник момента выдачи
     * @return генератор сценария сборки
     */
    @Bean
    fun recipeBuilder(
        database: Db,
        seriesStore: SeriesStore,
        checksums: ChecksumRegistry,
        settingsStore: SerialSettingsStore,
        recipes: RecipeStore,
        catalog: RecipeCatalog,
        artifacts: ArtifactRegistry,
        signer: Signer,
        clock: Clock,
    ): RecipeBuilder =
        RecipeBuilder(
            db = database,
            seriesStore = seriesStore,
            checksums = checksums,
            settingsStore = settingsStore,
            recipes = recipes,
            catalog = catalog,
            artifacts = artifacts,
            signer = signer,
            clock = clock,
        )

    /**
     * Собирает эндпоинты выдачи сценария.
     *
     * @param builder генератор сценария
     * @param recipes хранилище сценариев
     * @param catalog каталог сценариев
     * @param notifications публикация уведомлений о событиях домена
     * @return контроллер выдачи
     */
    @Bean
    fun recipeController(
        builder: RecipeBuilder,
        recipes: RecipeStore,
        catalog: RecipeCatalog,
        notifications: NotificationPublisher,
    ): RecipeController = RecipeController(builder, recipes, catalog, notifications)

    /**
     * Собирает источник момента выдачи.
     *
     * Значение по умолчанию у метода `@Bean` недопустим: Spring ищет фабричный
     * метод без аргументов, и параметр со значением по умолчанию превращает бин
     * в «фабричный метод не найден» — весь контекст не поднимается. Поэтому
     * часы задаются здесь явно.
     *
     * @return системные часы в зоне UTC
     */
    @Bean
    fun recipeClock(): Clock = Clock.systemUTC()
}
