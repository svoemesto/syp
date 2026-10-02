package ru.svoemesto.syp.admin.recipe

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.notify.NotificationPublisher
import ru.svoemesto.syp.admin.selection.RecipeBuilder
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.contract.ErrorItem
import ru.svoemesto.syp.core.recipe.BuildRecipe
import ru.svoemesto.syp.core.recipe.BuildRecipeItem
import ru.svoemesto.syp.core.recipe.RecipeCatalog
import ru.svoemesto.syp.core.recipe.RecipeStore

/**
 * Выдача сценария сборки в админском бэкенде.
 *
 * **Сценарий выдаёт админка** — генерация и подпись выполняются там, где живёт
 * закрытый ключ подписи. Публичная часть только читает уже подписанное и
 * хранимое: у неё нет ни эндпоинта создания, ни закрытого ключа (ADR-0014,
 * решение владельца 2026-10-03).
 *
 * Готовый видеофайл админка по-прежнему **не собирает**: это делает машина
 * пользователя из своей копии исходников (FR-085, ADR-0009). Эндпоинта с
 * видеосодержимым в этом контроллере нет, и это отсутствие, а не запрет в
 * договорённостях.
 *
 * Выдача **не ставится в очередь**: это операция над метаданными, и тяжёлой
 * работы в ней нет (FR-003, research.md Т-20). Ни в одном бэкенде исполнителя
 * этого действия не существует.
 *
 * @property builder генератор сценария
 * @property recipes хранилище сценариев
 * @property catalog каталог сценариев: актуальность и устаревание
 * @property notifications уведомления интерфейса; `null` — публиковать некуда,
 *   и выдача от этого работает как раньше
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class RecipeController(
    private val builder: RecipeBuilder,
    private val recipes: RecipeStore,
    private val catalog: RecipeCatalog,
    private val notifications: NotificationPublisher? = null,
) {
    /**
     * Выдаёт сценарий по выбранным сценам.
     *
     * Ответ — `201` со всем, что нужно для проверки сценария на машине
     * пользователя: числом фрагментов, расчётными величинами, суммой
     * содержимого и **идентификатором** ключа подписи. Сам ключ, закрытая его
     * половина тем более, наружу не уходит: он нужен только здесь, чтобы
     * подписать (ADR-0011, FR-089c).
     *
     * @param request имя сценария и выбранные сцены
     * @return созданный сценарий
     * @throws DomainException с кодом `EMPTY_SELECTION`, `NOT_FOUND`,
     *   `BAD_REQUEST`, `STALE_RESULT`, `EPISODE_NOT_ANALYZED`,
     *   `INCOMPATIBLE_EPISODE` или `CHECKSUM_NOT_READY`
     */
    @PostMapping("/api/recipes")
    fun issue(
        @RequestBody request: RecipeIssueRequest,
    ): ResponseEntity<RecipeIssuedResponse> {
        val recipe =
            try {
                builder.issue(
                    recipeName = requireName(request.name),
                    sceneIds = request.sceneIds.distinct(),
                )
            } catch (failure: DomainException) {
                // Выдача не задание очереди: очередь об отказе не знает и
                // сообщить о нём не может. Без этого события отказ виден только
                // тому экрану, который его вызвал.
                notifications?.failed(failure.code.name, failure.message ?: ISSUE_OPERATION, ISSUE_OPERATION)
                throw failure
            }
        notifications?.recipeReady(recipe)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(
                RecipeIssuedResponse(
                    recipeId = recipe.id!!,
                    name = recipe.name,
                    state = recipe.state.name,
                    schemaVersion = recipe.schemaVersion,
                    isStale = recipe.isStale,
                    itemCount = recipe.itemCount,
                    expectedDurationMs = recipe.expectedDurationMs,
                    expectedFrameCount = recipe.expectedFrameCount,
                    contentSha256 = recipe.contentSha256!!,
                    signingKeyId = recipe.signingKeyId!!,
                    downloadUrl = "/api/recipes/${recipe.id}/file",
                ),
            )
    }

    /**
     * Перечисляет сценарии фильма, свежие сверху.
     *
     * @param movieId фильм
     * @return сценарии фильма
     */
    @GetMapping("/api/recipes")
    fun list(
        @RequestParam("movieId") movieId: Long,
    ): List<RecipeSummaryResponse> = recipes.listByMovie(movieId, LIST_LIMIT).map { it.toSummary() }

    /**
     * Отдаёт состав сценария: что войдёт в подборку и обе пары границ
     * фрагмента, чтобы округление было видно до работы, а не после неё
     * (FR-082, FR-083).
     *
     * @param recipeId идентификатор сценария
     * @return состав сценария
     * @throws DomainException с кодом `NOT_FOUND`, если сценария нет
     */
    @GetMapping("/api/recipes/{recipeId}")
    fun describe(
        @PathVariable recipeId: Long,
    ): RecipeCompositionResponse {
        val recipe = requireRecipe(recipeId)
        val freshness = catalog.freshness(recipe, recipe.schemaVersion)
        return RecipeCompositionResponse(
            recipeId = recipe.id!!,
            name = recipe.name,
            state = recipe.state.name,
            schemaVersion = recipe.schemaVersion,
            isStale = freshness.isStale,
            staleReason = freshness.reason,
            signingKeyId = recipe.signingKeyId,
            contentSha256 = recipe.contentSha256,
            itemCount = recipes.items(recipeId).size,
            expectedDurationMs = recipe.expectedDurationMs,
            expectedFrameCount = recipe.expectedFrameCount,
            items = recipes.items(recipeId).map { it.toCompositionItem() },
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

    /** Требует непустого названия сценария. */
    private fun requireName(name: String?): String {
        if (name.isNullOrBlank()) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "у сценария должно быть название: по нему пользователь узнает подборку в списке",
            )
        }
        return name.trim()
    }

    private companion object {
        /** Сколько сценариев показывает список. */
        const val LIST_LIMIT: Int = 50

        /** Чем оператор занят в момент отказа: показывается в уведомлении. */
        const val ISSUE_OPERATION: String = "выдача сценария сборки"
    }
}

/**
 * Запрос выдачи сценария.
 *
 * @property name название сценария
 * @property sceneIds выбранные сцены в порядке следования
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeIssueRequest(
    val name: String?,
    val sceneIds: List<Long> = emptyList(),
)

/**
 * Ответ выдачи сценария.
 *
 * Поля повторяют то, что уже было в контракте публичной части, и добавлен
 * признак устаревания. Файл сценария отдаёт публичная часть; `downloadUrl`
 * указывает именно туда, и переход происходит по этому адресу.
 *
 * @property recipeId идентификатор сценария
 * @property name название сценария
 * @property state состояние выдачи
 * @property schemaVersion версия формата
 * @property isStale помечен ли сценарий устаревшим
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность подборки
 * @property expectedFrameCount расчётное число кадров
 * @property contentSha256 сумма содержимого файла сценария
 * @property signingKeyId идентификатор ключа подписи; **не сам ключ**
 * @property downloadUrl адрес файла сценария у публичной части
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeIssuedResponse(
    val recipeId: Long,
    val name: String,
    val state: String,
    val schemaVersion: Int,
    val isStale: Boolean,
    val itemCount: Int,
    val expectedDurationMs: Long?,
    val expectedFrameCount: Long?,
    val contentSha256: String,
    val signingKeyId: String,
    val downloadUrl: String,
)

/**
 * Краткая строка списка сценариев.
 *
 * @property recipeId идентификатор сценария
 * @property name название сценария
 * @property state состояние выдачи
 * @property isStale помечен ли сценарий устаревшим
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность подборки
 * @property expectedFrameCount расчётное число кадров
 * @property signingKeyId идентификатор ключа подписи
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeSummaryResponse(
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
 * Состав сценария.
 *
 * @property recipeId идентификатор сценария
 * @property name название сценария
 * @property state состояние выдачи
 * @property schemaVersion версия формата
 * @property isStale помечен ли сценарий устаревшим
 * @property staleReason чем именно устарел; `null`, если актуален
 * @property signingKeyId идентификатор ключа подписи
 * @property contentSha256 сумма содержимого файла сценария
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность подборки
 * @property expectedFrameCount расчётное число кадров
 * @property items фрагменты в порядке следования
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeCompositionResponse(
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
    val items: List<RecipeCompositionItemResponse>,
)

/**
 * Фрагмент в составе сценария.
 *
 * Показываются **обе** пары границ: расхождение между ними и есть то округление,
 * о котором решает пользователь (ADR-0006, FR-082). Значения сцены — снимки
 * строк на момент выдачи, а не ссылки (FR-089d).
 *
 * @property ordinal порядковый номер фрагмента
 * @property sceneId сцена-источник
 * @property episodeId эпизод-источник
 * @property episodeName название эпизода-снимок
 * @property relativePath путь к файлу эпизода от корня фильма
 * @property sourceSha256 эталонная сумма файла эпизода
 * @property firstFrame расчётная граница начала
 * @property lastFrame расчётная граница конца
 * @property cutFirstFrame фактическая граница начала
 * @property cutLastFrame фактическая граница конца
 * @property title название сцены-снимок
 * @property location место действия-снимок
 * @property persons персонажи сцены-снимок
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeCompositionItemResponse(
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

/** Строит краткую строку списка из строки сценария. */
private fun BuildRecipe.toSummary(): RecipeSummaryResponse =
    RecipeSummaryResponse(
        recipeId = id!!,
        name = name,
        state = state.name,
        isStale = isStale,
        itemCount = itemCount,
        expectedDurationMs = expectedDurationMs,
        expectedFrameCount = expectedFrameCount,
        signingKeyId = signingKeyId,
    )

/** Строит фрагмент состава из строки фрагмента в базе. */
private fun BuildRecipeItem.toCompositionItem(): RecipeCompositionItemResponse =
    RecipeCompositionItemResponse(
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
