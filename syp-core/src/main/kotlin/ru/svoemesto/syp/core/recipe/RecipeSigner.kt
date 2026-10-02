package ru.svoemesto.syp.core.recipe

import ru.svoemesto.syp.core.signing.Canonicalizer
import ru.svoemesto.syp.core.signing.Signer

/**
 * Подпись сценария и всё, что едет вместе с ней.
 *
 * Тройка «подпись, сумма содержимого, идентификатор ключа» приходит и уходит
 * **вместе**: подпись без суммы нечего проверять, сумма без подписи не
 * защищает (инвариант 10 домена Selection, модель данных 2.20).
 *
 * Подпись ставится над **каноническими байтами целиком**, а не над объектом и
 * не над хешем полей по отдельности: при разборе JSON порядок ключей не
 * гарантирован, и подпись над объектом была бы лотереей (research.md Т-23).
 *
 * Подпись хранится **вне** подписываемого файла: иначе пришлось бы подписывать
 * «файл с местом для подписи», и определение «что подписано» расплылось бы
 * (контракт рецепта, раздел 3).
 *
 * @property signingKeyId идентификатор пары ключей, которой подписан файл
 * @property contentSha256 SHA-256 канонических байтов, 64 hex в нижнем регистре
 * @property signature подпись Ed25519 в формате base64
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SignedRecipe(
    val signingKeyId: String,
    val contentSha256: String,
    val signature: String,
)

/**
 * Подписание сценария.
 *
 * Код подписи лежит в общем модуле `syp-core`, а **закрытый ключ** — только в
 * контейнере админки: публичная часть получает уже подписанный сценарий и
 * отдаёт открытый ключ с идентификатором, подписывать ей нечем (ADR-0011).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object RecipeSigner {
    /**
     * Подписывает канонические байты сценария.
     *
     * @param signer подписант ключом сервера
     * @param canonicalBytes байты файла сценария в канонической форме
     * @return подпись вместе с суммой содержимого и идентификатором ключа
     */
    fun sign(
        signer: Signer,
        canonicalBytes: ByteArray,
    ): SignedRecipe =
        SignedRecipe(
            signingKeyId = signer.signingKeyId,
            contentSha256 = Canonicalizer.checksum(canonicalBytes),
            signature = signer.sign(canonicalBytes),
        )

    /**
     * Собирает, канонизирует и подписывает сценарий.
     *
     * @param signer подписант ключом сервера
     * @param document сценарий
     * @return пара «канонические байты, подпись»
     */
    fun issue(
        signer: Signer,
        document: RecipeDocument,
    ): Pair<ByteArray, SignedRecipe> {
        val bytes = RecipeFormat.canonicalBytes(document)
        return bytes to sign(signer, bytes)
    }
}
