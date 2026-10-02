package ru.svoemesto.syp.public.recipe

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.core.json.Json
import ru.svoemesto.syp.core.recipe.BuildRecipe
import ru.svoemesto.syp.core.recipe.BuildRecipeItem
import ru.svoemesto.syp.core.recipe.RecipeFormat
import ru.svoemesto.syp.core.recipe.RecipeState
import ru.svoemesto.syp.core.recipe.RecipeStore
import ru.svoemesto.syp.core.signing.Signer
import ru.svoemesto.syp.core.signing.VerificationKey
import ru.svoemesto.syp.core.storage.ArtifactKind
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.FileSystemStorage
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверки формата сценария, требующие живой базы.
 *
 * Закрывают свойства 1—5 и 8 контракта
 * [`recipe-format.md`](../../../../../../specs/001-first-vertical-slice/contracts/recipe-format.md):
 * ограничения базы отвергают сценарий без подписи, сумму не из 64
 * шестнадцатеричных символов, абсолютный путь и путь с `..`, а также
 * фактические границы, не охватывающие расчётные.
 *
 * База поднимается одноразовым контейнером `postgres:16` скриптом
 * `tools/run-db-tests.sh`; без неё тесты помечаются пропущенными, а не
 * падают.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Формат сценария: ограничения базы и выдача")
class RecipeFormatContractTest {
    private val issuedAt: OffsetDateTime =
        OffsetDateTime.of(2026, 10, 2, 12, 41, 7, 0, ZoneOffset.UTC)

    /** Подписант тестовой парой ключей: закрытый ключ в репозиторий не попадает. */
    private val pair = Signer.generateKeyPair()

    private val signer = Signer("syp-test", Signer.privateKeyFromBase64(Signer.encodeBase64(pair.private)))

    private val verificationKey = VerificationKey.of("syp-test", Signer.encodeBase64(pair.public))

    /** Собирает хранилище сценариев на поднятой базе. */
    private fun store(): RecipeStore {
        TestDatabase.setUp()
        return RecipeStore(TestDatabase.assumeDatabase())
    }

    /** Собирает реестр артефактов поверх временного каталога. */
    private fun artifacts(root: java.nio.file.Path): ArtifactRegistry =
        ArtifactRegistry(TestDatabase.assumeDatabase(), FileSystemStorage(root))

    private fun newMovie(): Long = TestDatabase.insertMovie("Игры Престолов")

    private fun newEpisode(
        movieId: Long,
        name: String,
    ): Long = TestDatabase.insertEpisode(movieId, name)

    private fun newScene(
        episodeId: Long,
        firstFrame: Int,
        lastFrame: Int,
    ): Long = TestDatabase.insertScene(episodeId, firstFrame, lastFrame)

    private fun item(
        recipeId: Long,
        ordinal: Int,
        sceneId: Long,
        episodeId: Long,
        episodeName: String,
        relativePath: String = "GOT.S01/$episodeName.mkv",
        firstFrame: Int = 1200,
        lastFrame: Int = 1455,
        cutFirstFrame: Int = 1188,
        cutLastFrame: Int = 1460,
    ) = BuildRecipeItem(
        recipeId = recipeId,
        ordinal = ordinal,
        sceneId = sceneId,
        episodeId = episodeId,
        episodeName = episodeName,
        relativePath = relativePath,
        sourceSha256 = "3f786850e387550fdab836ed7e6dc881de23001b1a2c3d4e5f60718293a4b5c6",
        firstFrame = firstFrame,
        lastFrame = lastFrame,
        cutFirstFrame = cutFirstFrame,
        cutLastFrame = cutLastFrame,
        sceneTitle = "Джейми у ворот",
        locationName = "Лагерь Джейми",
        personNames = listOf("Джейми Ланистер", "Тиберт Ланнистер"),
    )

    @Test
    @DisplayName("Сценарий без подписи не может перейти в DONE")
    fun doneRecipeRequiresSignature() {
        val store = store()
        val movieId = newMovie()
        TestDatabase.rejected(
            "сценарий в состоянии DONE без подписи, суммы и идентификатора ключа",
        ) {
            store.insert(
                BuildRecipe(
                    movieId = movieId,
                    name = "Без подписи",
                    state = RecipeState.DONE,
                    itemCount = 1,
                    expectedDurationMs = 100,
                    expectedFrameCount = 100,
                    createdAt = issuedAt,
                    finishedAt = issuedAt,
                ),
                listOf(item(1, 1, 1, 1, "GOT.S01E01")),
            )
        }
    }

    @Test
    @DisplayName("Сумма содержимого не из 64 шестнадцатеричных символов отвергается")
    fun contentSha256MustBeHex64() {
        val store = store()
        val movieId = newMovie()
        TestDatabase.rejected("сумма содержимого из 63 символов") {
            TestDatabase.insertRecipeRow(
                movieId = movieId,
                name = "Плохая сумма",
                state = RecipeState.DONE,
                artifactId = null,
                contentSha256 = "3f786850e387550fdab836ed7e6dc881de23001b1a2c3d4e5f60718293a4b5c",
                signature = "AAAA",
                signingKeyId = "syp-test",
                createdAt = issuedAt,
                finishedAt = issuedAt,
            )
        }
    }

    @Test
    @DisplayName("Абсолютный путь фрагмента отвергается")
    fun absoluteItemPathIsRejected() {
        val store = store()
        val movieId = newMovie()
        val episodeId = newEpisode(movieId, "GOT.S01E01")
        val sceneId = newScene(episodeId, 1200, 1455)
        TestDatabase.rejected("путь, уводящий за пределы копии фильма") {
            store.insert(
                BuildRecipe(
                    movieId = movieId,
                    name = "Абсолютный путь",
                    state = RecipeState.CREATING,
                    createdAt = issuedAt,
                ),
                listOf(item(1, 1, sceneId, episodeId, "GOT.S01E01", relativePath = "/GOT.S01E01.mkv")),
            )
        }
    }

    @Test
    @DisplayName("Путь с `..` отвергается")
    fun escapingItemPathIsRejected() {
        val store = store()
        val movieId = newMovie()
        val episodeId = newEpisode(movieId, "GOT.S01E02")
        val sceneId = newScene(episodeId, 1200, 1455)
        TestDatabase.rejected("путь с сегментом `..`") {
            store.insert(
                BuildRecipe(
                    movieId = movieId,
                    name = "Выход за пределы",
                    state = RecipeState.CREATING,
                    createdAt = issuedAt,
                ),
                listOf(item(1, 1, sceneId, episodeId, "GOT.S01E02", relativePath = "GOT.S01/../GOT.S01E02.mkv")),
            )
        }
    }

    @Test
    @DisplayName("Начало позже расчётного и конец раньше расчётного отвергаются")
    fun cutBoundariesMustCoverPlannedOnes() {
        val store = store()
        val movieId = newMovie()
        val episodeId = newEpisode(movieId, "GOT.S01E03")
        val sceneId = newScene(episodeId, 1200, 1455)
        listOf(
            item(1, 1, sceneId, episodeId, "GOT.S01E03", cutFirstFrame = 1201),
            item(1, 1, sceneId, episodeId, "GOT.S01E03", cutLastFrame = 1454),
        ).forEach { broken ->
            TestDatabase.rejected("фактические границы не охватывают расчётные") {
                store.insert(
                    BuildRecipe(
                        movieId = movieId,
                        name = "Границы ${broken.cutFirstFrame}…${broken.cutLastFrame}",
                        state = RecipeState.CREATING,
                        createdAt = issuedAt,
                    ),
                    listOf(broken),
                )
            }
        }
    }

    @Test
    @DisplayName("Повторная выдача тех же данных даёт побайтово те же канонические байты")
    fun repeatedIssueGivesSameBytes() {
        val store = store()
        val movieId = newMovie()
        val episodeId = newEpisode(movieId, "GOT.S01E04")
        val sceneId = newScene(episodeId, 1200, 1455)
        val document =
            ru.svoemesto.syp.core.recipe.RecipeDocument(
                movieId = movieId,
                movieName = "Игры Престолов",
                recipeId = 0,
                recipeName = "Джейми — выходы",
                signingKeyId = signer.signingKeyId,
                createdAt = issuedAt,
                audioTrackCount = 1,
                expectedDurationMs = 272,
                expectedFrameCount = 272,
                items =
                    listOf(
                        item(0, 1, sceneId, episodeId, "GOT.S01E04").toDocumentItem(),
                    ),
            )
        val first = RecipeFormat.canonicalBytes(document)
        val second = RecipeFormat.canonicalBytes(document)
        assertTrue(first.contentEquals(second), "одни и те же данные дают одинаковые байты")

        // Подписанные байты — ровно те, что отдаются пользователю, и они
        // разбираются обычным разбором JSON: без кавычек воркер файл не прочтёт.
        val json = Json.mapper().readTree(first)
        assertEquals(1, json["schemaVersion"].asInt())
        assertEquals("Джейми Ланистер", json["items"][0]["persons"][0].asText())
        assertTrue(verificationKey.verify(first, signer.sign(first)), "подпись проверяется")
    }

    @Test
    @DisplayName("Файл сценария отдаётся только в состоянии READY и в канонической форме")
    fun fileIsServedOnlyWhenArtifactIsReady() {
        val store = store()
        val registry = artifacts(TestDatabase.tempStorageRoot("recipe-$issuedAt"))
        val movieId = newMovie()
        val episodeId = newEpisode(movieId, "GOT.S01E05")
        val sceneId = newScene(episodeId, 1200, 1455)
        val document =
            ru.svoemesto.syp.core.recipe.RecipeDocument(
                movieId = movieId,
                movieName = "Игры Престолов",
                recipeId = 0,
                recipeName = "Джейми — выходы",
                signingKeyId = signer.signingKeyId,
                createdAt = issuedAt,
                audioTrackCount = 1,
                expectedDurationMs = 272,
                expectedFrameCount = 272,
                items = listOf(item(0, 1, sceneId, episodeId, "GOT.S01E05").toDocumentItem()),
            )
        val bytes = RecipeFormat.canonicalBytes(document)
        val signed =
            ru.svoemesto.syp.core.recipe.RecipeSigner
                .sign(signer, bytes)
        val artifact = registry.begin(null, ArtifactKind.RECIPE, "recipes/55.json", RecipeFormat.CONTENT_TYPE)
        registry.writeTemporary(artifact, bytes.inputStream())
        registry.markReady(artifact, signed.contentSha256, bytes.size.toLong())

        val stored =
            store.insert(
                BuildRecipe(
                    movieId = movieId,
                    name = "Джейми — выходы",
                    state = RecipeState.DONE,
                    artifactId = artifact.id,
                    contentSha256 = signed.contentSha256,
                    signature = signed.signature,
                    signingKeyId = signed.signingKeyId,
                    itemCount = 1,
                    expectedDurationMs = 272,
                    expectedFrameCount = 272,
                    createdAt = issuedAt,
                    finishedAt = issuedAt,
                ),
                listOf(item(0, 1, sceneId, episodeId, "GOT.S01E05")),
            )

        val recipeId = requireNotNull(stored.id)
        val controller = RecipeDeliveryController(store, registry)
        val response = controller.file(recipeId)
        assertEquals(200, response.statusCode.value())
        assertTrue(
            response.body!!.contentEquals(bytes),
            "отдаются ровно те байты, которые подписаны: иначе проверка подписи у пользователя не сойдётся",
        )
        assertTrue(
            response.headers.getFirst("Content-Disposition")!!.contains(".syp-recipe.json"),
            "файл сохраняется браузером с расширением сценария",
        )
        assertEquals(
            listOf("Джейми Ланистер", "Тиберт Ланнистер"),
            store.items(recipeId)[0].personNames,
            "снимок имён персонажей пережил запись в jsonb без потерь",
        )
    }
}
