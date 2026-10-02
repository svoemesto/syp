package ru.svoemesto.syp.core.signing

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Тесты канонизации и подписи.
 *
 * Закрывают правила формата сценария (contracts/recipe-format.md, раздел 3)
 * без обращения к базе: подпись должна быть воспроизводима побайтово, иначе
 * проверка подписи на машине пользователя превращается в лотерею.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Канонизация и подпись Ed25519")
class SigningTest {
    /** Пример сценария: версия формата и один фрагмент. */
    private val recipe =
        CanonicalObject.of(
            "schema_version" to 1,
            "serial_name" to "Игра престолов",
            "items" to
                listOf(
                    CanonicalObject.of(
                        "ordinal" to 0,
                        "relative_path" to "Season 01/GOT.S01E01.mkv",
                        "first_frame" to 1200,
                        "last_frame" to 1420,
                        "cut_first_frame" to 1188,
                        "cut_last_frame" to 1428,
                        "source_sha256" to "e".repeat(64),
                        "person_names" to listOf("Джейми Ланнистер", "Тиберт Ланнистер"),
                        "location_name" to "Лагерь",
                    ),
                ),
        )

    @Test
    @DisplayName("Канонизация воспроизводима побайтово")
    fun canonicalFormIsReproducible() {
        val first = Canonicalizer.canonicalBytes(recipe)
        val second = Canonicalizer.canonicalBytes(recipe)
        assertTrue(
            first.contentEquals(second),
            "одни и те же данные, сериализованные дважды, обязаны дать одинаковые байты",
        )
    }

    @Test
    @DisplayName("Порядок полей влияет на канонические байты")
    fun fieldOrderIsFixed() {
        val reordered =
            CanonicalObject.of(
                "serial_name" to "Игра престолов",
                "schema_version" to 1,
                "items" to
                    listOf(
                        CanonicalObject.of(
                            "ordinal" to 0,
                            "relative_path" to "Season 01/GOT.S01E01.mkv",
                            "first_frame" to 1200,
                            "last_frame" to 1420,
                            "cut_first_frame" to 1188,
                            "cut_last_frame" to 1428,
                            "source_sha256" to "e".repeat(64),
                            "person_names" to listOf("Джейми Ланнистер", "Тиберт Ланнистер"),
                            "location_name" to "Лагерь",
                        ),
                    ),
            )
        assertNotEquals(
            Canonicalizer.checksum(Canonicalizer.canonicalBytes(recipe)),
            Canonicalizer.checksum(Canonicalizer.canonicalBytes(reordered)),
            "порядок полей объявлен явно и является частью подписываемого",
        )
    }

    @Test
    @DisplayName("Перевод строки приводится к LF и экранируется по правилам JSON")
    fun lineEndingsAreNormalized() {
        val crlf = Canonicalizer.canonicalString("первая\r\nвторая\rтретья")
        assertEquals("первая\\nвторая\\nтретья", crlf)
        assertEquals("первая", Canonicalizer.canonicalString("первая"))
    }

    @Test
    @DisplayName("Кавычка и обратный слэш экранируются")
    fun specialCharactersAreEscaped() {
        assertEquals("\"ворота \\\"северные\\\"\"", Canonicalizer.quoted(Canonicalizer.canonicalString("ворота \"северные\"")))
        assertEquals("\"путь\\\\в\\\\\"", Canonicalizer.quoted(Canonicalizer.canonicalString("путь\\в\\")))
    }

    @Test
    @DisplayName("Числа приводятся к одному виду")
    fun numbersAreNormalized() {
        assertEquals("1000", Canonicalizer.canonicalNumber(java.math.BigDecimal("1000.000")))
        assertEquals("0.5", Canonicalizer.canonicalNumber(java.math.BigDecimal("0.500")))
        assertEquals("1000", Canonicalizer.canonicalBytes(1000).decodeToString())
    }

    @Test
    @DisplayName("Подпись ставится над каноническими байтами и проверяется")
    fun signatureRoundTrip() {
        val pair = Signer.generateKeyPair()
        val privateB64 = Signer.encodeBase64(pair.private)
        val publicB64 = Signer.encodeBase64(pair.public)

        val signer = Signer("key-2026-10", Signer.privateKeyFromBase64(privateB64))
        val verificationKey = VerificationKey.of("key-2026-10", publicB64)

        val canonical = Canonicalizer.canonicalBytes(recipe)
        val signature = signer.sign(canonical)

        assertTrue(verificationKey.verify(canonical, signature), "подпись должна проверяться")
        assertEquals("key-2026-10", verificationKey.keyId)
        assertEquals("Ed25519", verificationKey.algorithm)
    }

    @Test
    @DisplayName("Подпись не проходит под изменёнными байтами")
    fun signatureRejectsTamperedBytes() {
        val pair = Signer.generateKeyPair()
        val signer = Signer("key", Signer.privateKeyFromBase64(Signer.encodeBase64(pair.private)))
        val verificationKey = VerificationKey.of("key", Signer.encodeBase64(pair.public))

        val canonical = Canonicalizer.canonicalBytes(recipe)
        val signature = signer.sign(canonical)

        val tampered =
            Canonicalizer.canonicalBytes(
                CanonicalObject.of("schema_version" to 1, "serial_name" to "Подменённый сериал", "items" to emptyList<Any>()),
            )
        assertTrue(
            !verificationKey.verify(tampered, signature),
            "подпись под изменёнными данными проверяться не должна",
        )
    }

    @Test
    @DisplayName("Смена ключа даёт новый идентификатор, прежний ключ остаётся рабочим")
    fun keyRotationKeepsOldKeyValid() {
        val first = Signer.generateKeyPair()
        val second = Signer.generateKeyPair()

        val firstKey = VerificationKey.of("key-1", Signer.encodeBase64(first.public))
        val secondKey = VerificationKey.of("key-2", Signer.encodeBase64(second.public))

        val signer = Signer("key-1", Signer.privateKeyFromBase64(Signer.encodeBase64(first.private)))
        val canonical = Canonicalizer.canonicalBytes(recipe)
        val signature = signer.sign(canonical)

        // Прежние сценарии остаются проверяемыми по прежнему ключу.
        assertTrue(firstKey.verify(canonical, signature), "прежний ключ обязан проверять прежние сценарии")
        assertTrue(!secondKey.verify(canonical, signature), "новый ключ не проверяет подпись прежнего")
        assertNotEquals(firstKey.keyId, secondKey.keyId)
    }

    @Test
    @DisplayName("В ответе наружу нет закрытого ключа")
    fun responseCarriesOnlyPublicKey() {
        val pair = Signer.generateKeyPair()
        val key = VerificationKey.of("key-1", Signer.encodeBase64(pair.public))
        val response = key.toResponse()

        assertEquals("key-1", response.keyId)
        assertEquals("Ed25519", response.algorithm)
        assertEquals(key.publicKeyPem(), response.publicKeyPem)
        // Закрытой половины в классе ответа нет по построению: перечисляем
        // поля класса и убеждаемся, что среди них ровно четыре и все известные.
        val fields =
            response::class.java.declaredFields
                .map { it.name }
                .toSet()
        assertEquals(
            setOf("keyId", "algorithm", "publicKeyPem", "notBefore"),
            fields,
            "ответ содержит только идентификатор, алгоритм, открытый ключ и момент начала",
        )
    }

    @Test
    @DisplayName("Открытый ключ отдаётся в виде PEM, пригодном для openssl")
    fun publicKeyIsPem() {
        val pair = Signer.generateKeyPair()
        val key = VerificationKey.of("key-1", Signer.encodeBase64(pair.public))
        val pem = key.publicKeyPem()

        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"), "PEM начинается с заголовка: $pem")
        assertTrue(pem.endsWith("-----END PUBLIC KEY-----\n"), "PEM заканчивается подписью и переводом строки")
        pem.trim().lines().drop(1).dropLast(1).forEach { line ->
            assertTrue(line.length <= 64, "строка base64 не длиннее 64 символов, получено ${line.length}")
        }
        // Материал ключа внутри PEM обязан разбираться тем же разборщиком,
        // которым ключ приходит из окружения: иначе файл пришлось бы править
        // на стороне пользователя, а это уже не «готовый ключ».
        val recovered = Signer.publicKeyFromBase64(pem)
        assertEquals(
            key.publicKeyBase64,
            java.util.Base64
                .getEncoder()
                .encodeToString(recovered.encoded),
            "ключ из PEM совпадает с ключом из base64",
        )
    }

    @Test
    @DisplayName("Контрольная сумма содержимого — 64 hex в нижнем регистре")
    fun checksumFormat() {
        val checksum = Canonicalizer.checksum(Canonicalizer.canonicalBytes(recipe))
        assertTrue(checksum.matches(Regex("^[0-9a-f]{64}$")), "получено: $checksum")
    }
}
