package ru.svoemesto.syp.public.recipe

import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.contract.ErrorItem
import ru.svoemesto.syp.core.recipe.BuildRecipe
import ru.svoemesto.syp.core.recipe.BuildRecipeItem
import ru.svoemesto.syp.core.recipe.RecipeFormat
import ru.svoemesto.syp.core.recipe.RecipeState
import ru.svoemesto.syp.core.recipe.RecipeStore
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.ArtifactState
import java.nio.charset.StandardCharsets

/**
 * Выдача сценария сборки пользователю.
 *
 * Пользователь получает **файл сценария** и всё, что нужно для его проверки:
 * подпись, сумму содержимого и открытый ключ. Видеофайла здесь нет и не
 * появится: ни отдачи, ни приёма, ни хранения (ADR-0009, FR-088). Эндпоинта
 * с видеосодержимым в этом контроллере нет, и это не запрет в договорённостях,
 * а отсутствие.
 *
 * **Сценарий здесь не создаётся.** Он выдан админским бэкендом и подписан там
 * же, где живёл закрытый ключ: у этого контроллера нет ни эндпоинта выдачи,
 * ни закрытого ключа (ADR-0014, решение владельца 2026-10-03). Здесь только
 * чтение уже подписанного и хранимого.
 *
 * **Доставка ручная**: пользователь сохраняет файл браузером и сам кладёт его в
 * папку сценариев. Ссылок с подпиской, автообновления и докачивания в первом
 * срезе нет — это сознательное ограничение объёма, а не недоработка
 * (ADR-0009, решение владельца 2026-10-02).
 *
 * Закрытого ключа подписи у публичного бэкенда нет: сценарий приходит уже
 * подписанным, а в ответе называется **идентификатор** ключа, а не сам ключ
 * (ADR-0011, FR-089c).
 *
 * @property recipes хранилище сценариев
 * @property artifacts реестр артефактов с каноническими байтами
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class RecipeDeliveryController(
    private val recipes: RecipeStore,
    private val artifacts: ArtifactRegistry,
) {
    /**
     * Перечисляет сценарии сериала.
     *
     * Сценарий — ресурс с адресом, а не состояние вкладки: перезагрузка
     * страницы ничего не теряет, и список отдаётся по сериалу, а не по
     * содержимому окна.
     *
     * @param movieId сериал
     * @return сценарии, свежие сверху
     */
    @GetMapping("/api/recipes")
    fun list(
        @RequestParam("movieId") movieId: Long,
    ): List<RecipeSummary> = recipes.listByMovie(movieId, LIST_LIMIT).map { it.toSummary() }

    /**
     * Отдаёт состав сценария перед скачиванием.
     *
     * Показывается всё, что войдёт в подборку, и обе пары границ фрагмента:
     * решение «устраивает меня округление или нет» принимается до работы, а не
     * после (FR-082, FR-083).
     *
     * @param recipeId идентификатор сценария
     * @return состав сценария
     * @throws DomainException с кодом `NOT_FOUND`, если сценария нет
     */
    @GetMapping("/api/recipes/{recipeId}")
    fun describe(
        @PathVariable recipeId: Long,
    ): RecipeComposition {
        val recipe = requireRecipe(recipeId)
        return RecipeComposition(
            recipeId = recipe.id!!,
            name = recipe.name,
            state = recipe.state.name,
            schemaVersion = recipe.schemaVersion,
            isStale = recipe.isStale,
            staleReason = staleReasonOf(recipe),
            signingKeyId = recipe.signingKeyId,
            contentSha256 = recipe.contentSha256,
            itemCount = recipes.items(recipeId).size,
            expectedDurationMs = recipe.expectedDurationMs,
            expectedFrameCount = recipe.expectedFrameCount,
            items = recipes.items(recipeId).map { it.toCompositionItem() },
        )
    }

    /**
     * Отдаёт файл сценария для сохранения браузером.
     *
     * Отдаётся **только** артефакт в состоянии `READY`: незавершённый файл не
     * считается готовым, а отдать его пользователю — значит отдать подпись под
     * байтами, которых он не получит (FR-091).
     *
     * @param recipeId идентификатор сценария
     * @return файл сценария вложением
     * @throws DomainException с кодом `NOT_FOUND` или `CONFLICT`
     */
    @GetMapping("/api/recipes/{recipeId}/file")
    fun file(
        @PathVariable recipeId: Long,
    ): ResponseEntity<ByteArray> {
        val recipe = requireSigned(recipeId)
        val artifactId =
            recipe.artifactId
                ?: throw DomainException(
                    ErrorCode.CONFLICT,
                    "у сценария $recipeId нет артефакта с каноническими байтами",
                )
        val artifact = artifacts.find(artifactId)
        if (artifact == null || artifact.state != ArtifactState.READY) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "файл сценария $recipeId не готов: артефакт в состоянии " +
                    "${artifact?.state?.name ?: "ОТСУТСТВИЕТ"} (FR-091)",
            )
        }
        val bytes = artifacts.openReady(artifactId).use { it.readBytes() }
        return ResponseEntity
            .ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename(fileNameOf(recipe.name), StandardCharsets.UTF_8)
                    .build()
                    .toString(),
            ).contentType(MediaType.parseMediaType(RecipeFormat.CONTENT_TYPE))
            .contentLength(bytes.size.toLong())
            .body(bytes)
    }

    /**
     * Отдаёт подпись сценария отдельно от файла.
     *
     * Подпись хранится **вне** подписываемого файла намеренно: иначе пришлось
     * бы подписывать «файл с местом для подписи», и определение «что подписано»
     * расплылось бы (контракт рецепта, раздел 3). Отсюда и отдельный эндпоинт.
     *
     * @param recipeId идентификатор сценария
     * @return подпись, сумма содержимого и идентификатор ключа
     * @throws DomainException с кодом `NOT_FOUND` или `CONFLICT`
     */
    @GetMapping("/api/recipes/{recipeId}/signature")
    fun signature(
        @PathVariable recipeId: Long,
    ): RecipeSignatureResponse {
        val recipe = requireSigned(recipeId)
        return RecipeSignatureResponse(
            recipeId = recipe.id!!,
            contentSha256 = recipe.contentSha256!!,
            algorithm = SIGNATURE_ALGORITHM,
            signingKeyId = recipe.signingKeyId!!,
            signature = recipe.signature!!,
        )
    }

    /** Читает сценарий или отказывает внятно. */
    private fun requireRecipe(recipeId: Long): BuildRecipe =
        recipes.find(recipeId)
            ?: throw DomainException(
                ErrorCode.NOT_FOUND,
                "сценарий $recipeId не найден",
                listOf(ErrorItem("recipe", recipeId.toString(), "нет такого сценария")),
            )

    /** Читает сценарий и требует, чтобы он был подписан. */
    private fun requireSigned(recipeId: Long): BuildRecipe {
        val recipe = requireRecipe(recipeId)
        if (recipe.state != RecipeState.DONE || !recipe.isSigned) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "сценарий $recipeId в состоянии ${recipe.state.name}: выдать нечего, " +
                    "подпись и сумма содержимого не выдаются (FR-089c)",
                listOf(ErrorItem("recipe", recipeId.toString(), "сценарий не подписан")),
            )
        }
        return recipe
    }

    /** Имя файла сценария для сохранения браузером. */
    private fun fileNameOf(recipeName: String): String =
        recipeName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .ifEmpty { "recipe" } + RecipeFormat.FILE_SUFFIX

    private companion object {
        /** Сколько сценариев показывает список. */
        const val LIST_LIMIT: Int = 50

        /** Имя алгоритма подписи в ответе. */
        const val SIGNATURE_ALGORITHM: String = "Ed25519"
    }
}

/**
 * Краткая строка списка сценариев.
 *
 * @property recipeId идентификатор сценария
 * @property name название сценария
 * @property state состояние выдачи
 * @property isStale сценарий выдан при другой версии формата
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность подборки
 * @property expectedFrameCount расчётное число кадров
 * @property signingKeyId идентификатор ключа подписи
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeSummary(
    val recipeId: Long,
    val name: String,
    val state: String,
    val isStale: Boolean,
    val itemCount: Int,
    val expectedDurationMs: Long?,
    val expectedFrameCount: Long?,
    val signingKeyId: String?,
)

/**
 * Состав сценария: что показывается пользователю до скачивания.
 *
 * @property recipeId идентификатор сценария
 * @property name название сценария
 * @property state состояние выдачи
 * @property schemaVersion версия формата
 * @property isStale сценарий выдан при другой версии формата
 * @property staleReason чем именно устарел; `null`, если актуален
 * @property signingKeyId идентификатор ключа подписи
 * @property contentSha256 сумма содержимого файла сценария
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность подборки
 * @property expectedFrameCount расчётное число кадров
 * @property items фрагменты в порядке следования
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeComposition(
    val recipeId: Long,
    val name: String,
    val state: String,
    val schemaVersion: Int,
    val isStale: Boolean,
    val staleReason: String?,
    val signingKeyId: String?,
    val contentSha256: String?,
    val itemCount: Int,
    val expectedDurationMs: Long?,
    val expectedFrameCount: Long?,
    val items: List<RecipeCompositionItem>,
)

/**
 * Фрагмент в составе сценария.
 *
 * Показываются **обе** пары границ: расхождение между ними и есть то
 * округление, о котором пользователь решает заранее (ADR-0006, FR-082).
 *
 * @property ordinal порядковый номер фрагмента
 * @property sceneId сцена-источник
 * @property episodeId серия-источник
 * @property episodeName название серии
 * @property relativePath путь к файлу серии от корня сериала
 * @property sourceSha256 эталонная сумма файла серии
 * @property firstFrame расчётная граница начала
 * @property lastFrame расчётная граница конца
 * @property cutFirstFrame фактическая граница начала
 * @property cutLastFrame фактическая граница конца
 * @property title название сцены
 * @property location место действия
 * @property persons персонажи сцены
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeCompositionItem(
    val ordinal: Int,
    val sceneId: Long,
    val episodeId: Long,
    val episodeName: String,
    val relativePath: String,
    val sourceSha256: String,
    val firstFrame: Int,
    val lastFrame: Int,
    val cutFirstFrame: Int,
    val cutLastFrame: Int,
    val title: String?,
    val location: String?,
    val persons: List<String>,
)

/**
 * Ответ с подписью сценария.
 *
 * Тройка «подпись, сумма содержимого, идентификатор ключа» приходит и уходит
 * вместе: подпись без суммы нечего проверять, сумма без подписи не защищает
 * (FR-089c).
 *
 * @property recipeId идентификатор сценария
 * @property contentSha256 сумма содержимого файла сценария
 * @property algorithm имя алгоритма подписи
 * @property signingKeyId идентификатор ключа подписи
 * @property signature подпись в base64
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeSignatureResponse(
    val recipeId: Long,
    val contentSha256: String,
    val algorithm: String,
    val signingKeyId: String,
    val signature: String,
)

/** Строит краткую строку списка из строки сценария. */
private fun BuildRecipe.toSummary(): RecipeSummary =
    RecipeSummary(
        recipeId = id!!,
        name = name,
        state = state.name,
        isStale = isStale,
        itemCount = itemCount,
        expectedDurationMs = expectedDurationMs,
        expectedFrameCount = expectedFrameCount,
        signingKeyId = signingKeyId,
    )

/**
 * Текст пометки устаревания для показа пользователю.
 *
 * Публичная часть не знает, какая версия формата действует сейчас: версия
 * формата — настройка сериала, и она принадлежит админскому бэкенду. Поэтому
 * здесь объясняется только то, что видно из самой строки сценария: помечен он
 * устаревшим или нет. Сравнение версий делает админка при выдаче (ADR-0014).
 */
private fun staleReasonOf(recipe: BuildRecipe): String? =
    if (recipe.isStale) {
        "сценарий выдан при другой версии формата. Он сохранён и по-прежнему " +
            "проверяем по своему ключу; новый сценарий попросите у оператора (FR-090)"
    } else {
        null
    }

/** Строит фрагмент состава из строки фрагмента в базе. */
private fun BuildRecipeItem.toCompositionItem(): RecipeCompositionItem =
    RecipeCompositionItem(
        ordinal = ordinal,
        sceneId = sceneId,
        episodeId = episodeId,
        episodeName = episodeName,
        relativePath = relativePath,
        sourceSha256 = sourceSha256,
        firstFrame = firstFrame,
        lastFrame = lastFrame,
        cutFirstFrame = cutFirstFrame,
        cutLastFrame = cutLastFrame,
        title = sceneTitle,
        location = locationName,
        persons = personNames,
    )
