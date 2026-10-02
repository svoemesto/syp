package ru.svoemesto.syp.core.signing

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверка подписи **сторонним инструментом**.
 *
 * Подпись проверяется не тем же кодом, который её ставил: иначе ошибка
 * канонизации осталась бы незамеченной — код, который неверно определяет
 * «что подписано», так же неверно это и проверяет (компонент
 * [recipe-signer](../../../docs/domains/selection/components/recipe-signer.md),
 * раздел «Интерфейсы и Контракты»).
 *
 * Инструмент — `openssl`, потому что на машине пользователя проверка идёт
 * именно им и дополнительных средств ставить не нужно.
 *
 * Без `openssl` тест **пропускается**, а не падает: подпись проверяется ещё и
 * тестом на открытом ключе, и молчаливый пропуск не выдаётся за выполненную
 * проверку.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Проверка подписи сторонним инструментом")
class OpenSslVerificationTest {
    /** Имя программы проверки. Задано явно, а не берётся из данных. */
    private val openssl: String = System.getenv("SYP_OPENSSL_PATH") ?: "openssl"

    /** Сценарий для подписи: версия формата и один фрагмент. */
    private val recipe =
        CanonicalObject.of(
            "schemaVersion" to 1,
            "movieName" to "Игры Престолов",
            "items" to
                listOf(
                    CanonicalObject.of(
                        "ordinal" to 1,
                        "relativePath" to "GOT.S01/GOT.S01E01.BDRip.1080p.mkv",
                        "cutFirstFrame" to 1188,
                    ),
                ),
        )

    @Test
    @DisplayName("openssl подтверждает подпись и отвергает подменённый файл")
    fun opensslVerifiesAndRejectsTamperedFile() {
        assumeTrue(opensslAvailable(), "openssl на машине не найден: сторонняя проверка пропущена")

        val directory = Files.createTempDirectory("syp-signature-check-")
        try {
            val pair = Signer.generateKeyPair()
            val signer =
                Signer(
                    "syp-2026-10",
                    Signer.privateKeyFromBase64(Signer.encodeBase64(pair.private)),
                )
            val canonical = Canonicalizer.canonicalBytes(recipe)
            val signature = signer.sign(canonical)

            val content = directory.resolve("recipe.syp-recipe.json")
            val tampered = directory.resolve("tampered.syp-recipe.json")
            val signatureFile = directory.resolve("recipe.sig")
            val publicKey = directory.resolve("public.pem")

            Files.write(content, canonical)
            Files.write(signatureFile, Base64.getDecoder().decode(signature))
            Files.write(publicKey, VerificationKey.toPem(Signer.encodeBase64(pair.public)).toByteArray())
            // Подмена одного байта после подписания — ровно то, что делает
            // правка файла, прошедшего через браузер и диск пользователя.
            val broken = canonical.copyOf()
            broken[broken.size / 2] = (broken[broken.size / 2] + 1).toByte()
            Files.write(tampered, broken)

            val verified = run(verify(content, publicKey, signatureFile))
            // Вывод команд печатается: отчёт T133 должен содержать обе команды и
            // их вывод, а не пересказ проверки.
            println("проверка подписи: ${verify(content, publicKey, signatureFile).joinToString(" ")}")
            println("вывод: ${verified.text}; код возврата: ${verified.exitCode}")
            assertEquals(0, verified.exitCode, "openssl должен подтвердить подпись: ${verified.text}")

            val refused = run(verify(tampered, publicKey, signatureFile))
            println("проверка подменённого файла: ${verify(tampered, publicKey, signatureFile).joinToString(" ")}")
            println("вывод: ${refused.text}; код возврата: ${refused.exitCode}")
            assertTrue(refused.exitCode != 0, "openssl обязан отвергнуть подменённый файл, а он вернул 0")
        } finally {
            Files.walk(directory).sorted(Comparator.reverseOrder()).forEach { it.toFile().delete() }
        }
    }

    /** Собирает команду проверки подписи. */
    private fun verify(
        content: Path,
        publicKey: Path,
        signature: Path,
    ): List<String> =
        listOf(
            openssl,
            "pkeyutl",
            "-verify",
            "-pubin",
            "-inkey",
            publicKey.toString(),
            "-rawin",
            "-in",
            content.toString(),
            "-sigfile",
            signature.toString(),
        )

    /** Проверяет, что программа есть в системе. */
    private fun opensslAvailable(): Boolean =
        runCatching {
            ProcessBuilder(openssl, "version")
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)

    /**
     * Запускает внешнюю программу и собирает её вывод.
     *
     * Рабочий каталог программе не задаётся: все пути передаются абсолютными,
     * а смена каталога в дочернем процессе происходит до запуска программы и
     * при относительных элементах в `PATH` сбивает поиск исполняемого файла.
     */
    private fun run(arguments: List<String>): Result {
        val process =
            ProcessBuilder(arguments)
                // Объединение потоков обязательно: иначе stderr процесса
                // остаётся в буфере и при ошибке чтения из него программа
                // зависает (constitution IV, R-43).
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return Result(exitCode, output.trim())
    }

    /** Итог запуска внешней программы. */
    private data class Result(
        val exitCode: Int,
        val text: String,
    )
}
