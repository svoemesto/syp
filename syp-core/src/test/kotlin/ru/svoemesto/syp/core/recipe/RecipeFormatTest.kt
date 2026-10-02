package ru.svoemesto.syp.core.recipe

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import ru.svoemesto.syp.core.signing.Canonicalizer
import ru.svoemesto.syp.core.signing.Signer
import ru.svoemesto.syp.core.signing.VerificationKey
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Контрактные тесты формата сценария сборки.
 *
 * Закрывают правила разделов 2 и 3 контракта
 * [`recipe-format.md`](../../../../../specs/001-first-vertical-slice/contracts/recipe-format.md)
 * без обращения к базе: подпись должна быть воспроизводима побайтово, иначе
 * проверка на машине пользователя превращается в лотерею.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Формат сценария сборки")
class RecipeFormatTest {
    /** Момент выдачи, одинаковый во всех прогонах: иначе байты не сравнить. */
    private val issuedAt: OffsetDateTime =
        OffsetDateTime.of(2026, 10, 2, 12, 41, 7, 0, ZoneOffset.UTC)

    /** Первый фрагмент: расчётные границы уже округлены до ключевых кадров. */
    private fun firstItem(
        sceneTitle: String? = "Джейми у ворот",
        location: String? = "Лагерь Джейми",
        persons: List<String> = listOf("Джейми Ланистер"),
    ): RecipeItemDocument =
        RecipeItemDocument(
            ordinal = 1,
            sceneId = 1,
            sceneTitle = sceneTitle,
            location = location,
            persons = persons,
            episodeId = 7,
            episodeName = "GOT.S01E01",
            relativePath = "GOT.S01/GOT.S01E01.BDRip.1080p.mkv",
            sourceSha256 = "3f786850e387550fdab836ed7e6dc881de23001b1a2c3d4e5f60718293a4b5c6",
            firstFrame = 1200,
            lastFrame = 1455,
            cutFirstFrame = 1188,
            cutLastFrame = 1460,
        )

    /** Документ сценария целиком. */
    private fun document(items: List<RecipeItemDocument> = listOf(firstItem())): RecipeDocument =
        RecipeDocument(
            movieId = 4,
            movieName = "Игры Престолов",
            recipeId = 55,
            recipeName = "Джейми — выходы",
            signingKeyId = "syp-2026-10",
            createdAt = issuedAt,
            audioTrackCount = 1,
            expectedDurationMs = 272,
            expectedFrameCount = 272,
            items = items,
        )

    @Test
    @DisplayName("Повторная выдача тех же данных даёт побайтово те же байты")
    fun repeatedIssueGivesSameBytes() {
        val first = RecipeFormat.canonicalBytes(document())
        val second = RecipeFormat.canonicalBytes(document())
        assertTrue(
            first.contentEquals(second),
            "одни и те же данные, выданные дважды, обязаны дать одинаковые байты: " +
                "иначе проверка подписи превращается в лотерею (свойство 8 контракта)",
        )
    }

    @Test
    @DisplayName("Порядок полей совпадает с порядком контракта, раздел 2.2")
    fun fieldOrderFollowsContract() {
        val text = RecipeFormat.canonicalBytes(document()).decodeToString()
        val order =
            listOf(
                "schemaVersion",
                "movieId",
                "movieName",
                "recipeId",
                "recipeName",
                "signingKeyId",
                "createdAt",
                "rootLayout",
                "audioTrackCount",
                "expectedDurationMs",
                "expectedFrameCount",
                "items",
            )
        val positions = order.map { text.indexOf("\"$it\"") }
        assertTrue(
            positions.none { it < 0 },
            "в сценарии нет обязательных полей контракта: ${order.filterIndexed { i, _ -> positions[i] < 0 }}",
        )
        assertEquals(
            positions.sorted(),
            positions,
            "поля идут в порядке контракта: найдено $positions для $order",
        )
        assertTrue(
            text.contains("\"cutFirstFrame\": 1188"),
            "имена полей фрагмента совпадают с контрактом, а не с именами столбцов базы",
        )
    }

    @Test
    @DisplayName("Отсутствующее необязательное значение не пишется вовсе")
    fun absentOptionalFieldsAreOmitted() {
        val text =
            RecipeFormat
                .canonicalBytes(
                    document(listOf(firstItem(sceneTitle = null, location = null, persons = emptyList()))),
                ).decodeToString()
        assertFalse(text.contains("\"sceneTitle\""), "названия сцены нет — поле не пишется")
        assertFalse(text.contains("\"location\""), "места действия нет — поле не пишется")
        assertFalse(text.contains("\"persons\""), "персонажей нет — поле не пишется")
        assertFalse(text.contains("null"), "пустое значение не пишется как null (правило 5 раздела 2.1)")
    }

    @Test
    @DisplayName("Момент выдачи приводится к UTC с точностью до секунд")
    fun issuedAtIsNormalized() {
        val shifted =
            document().copy(createdAt = issuedAt.withOffsetSameInstant(java.time.ZoneOffset.ofHours(3)))
        val text = RecipeFormat.canonicalBytes(shifted).decodeToString()
        assertTrue(
            text.contains("\"createdAt\": \"2026-10-02T12:41:07Z\""),
            "момент выдачи должен быть в UTC и с точностью до секунд, получено: $text",
        )
    }

    @Test
    @DisplayName("Кавычка в названии сцены экранируется")
    fun stringsAreEscapedForJson() {
        val text =
            RecipeFormat
                .canonicalBytes(document(listOf(firstItem(sceneTitle = "Ворота \"северные\""))))
                .decodeToString()
        assertTrue(
            text.contains("\"sceneTitle\": \"Ворота \\\"северные\\\"\""),
            "кавычка внутри значения обязана быть экранирована, иначе файл не разберётся: $text",
        )
    }

    @Test
    @DisplayName("Подпись проверяется открытым ключом и не проходит на изменённых байтах")
    fun signatureVerifiesAndDetectsTampering() {
        val pair = Signer.generateKeyPair()
        val signer = Signer("syp-2026-10", Signer.privateKeyFromBase64(Signer.encodeBase64(pair.private)))
        val verificationKey = VerificationKey.of("syp-2026-10", Signer.encodeBase64(pair.public))

        val (bytes, signed) = RecipeSigner.issue(signer, document())
        assertEquals(64, signed.contentSha256.length, "сумма содержимого — 64 шестнадцатеричных символа")
        assertEquals(
            Canonicalizer.checksum(bytes),
            signed.contentSha256,
            "сумма содержимого считается по тем же байтам, что и подписаны",
        )
        assertTrue(verificationKey.verify(bytes, signed.signature), "подпись должна проверяться")

        val tampered = bytes.copyOf().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() }
        assertFalse(
            verificationKey.verify(tampered, signed.signature),
            "изменение одного байта после подписания обязано обнаруживаться (свойство 2 контракта)",
        )
    }

    @Test
    @DisplayName("Фактические границы обязаны охватывать расчётные")
    fun cutBoundariesMustCoverPlannedOnes() {
        assertFailsWith<IllegalArgumentException> {
            firstItem().copy(cutFirstFrame = 1201)
        }
        assertFailsWith<IllegalArgumentException> {
            firstItem().copy(cutLastFrame = 1454)
        }
    }

    @Test
    @DisplayName("Путь за пределы копии сериала отвергается")
    fun relativePathRules() {
        listOf(
            "/GOT.S01/GOT.S01E01.mkv",
            "../GOT.S01E01.mkv",
            "GOT.S01/../GOT.S01E01.mkv",
            "GOT.S01/..",
            "GOT.S01\\GOT.S01E01.mkv",
            "GOT.S01//GOT.S01E01.mkv",
            "",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>("путь «$path» обязан быть отвергнут") {
                RecipePaths.requireInsideMovieTree(path)
            }
        }
        RecipePaths.requireInsideMovieTree("GOT.S01/GOT.S01E01.BDRip.1080p.mkv")
    }
}
