package ru.svoemesto.syp.core.signing

import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Открытый ключ проверки подписи сценария.
 *
 * Воркер на машине пользователя получает этот ключ из публичной части и
 * проверяет им сценарий **до** разбора и **до** нарезки (FR-089c). Закрытого
 * ключа у воркера нет и быть не должно: иначе подпись перестала бы отличать
 * «свой сценарий» от «чужого» (ADR-0011, альтернатива «ключ в публичной
 * части» отклонена).
 *
 * Ключ приходит **с идентификатором**: смена ключа даёт новый идентификатор,
 * а сценарии, подписанные прежним ключом, остаются проверяемыми по нему.
 * Поэтому идентификатор — часть контракта, а не служебная надпись.
 *
 * @property keyId идентификатор пары ключей
 * @property publicKey сам открытый ключ
 * @property publicKeyBase64 открытый ключ в base64: так он отдаётся наружу
 * @property algorithm имя алгоритма подписи
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class VerificationKey(
    val keyId: String,
    val publicKey: PublicKey,
    val publicKeyBase64: String,
    val algorithm: String = Signer.ALGORITHM,
) {
    /**
     * Проверяет подпись над каноническими байтами.
     *
     * @param canonicalBytes подписываемые байты
     * @param signature подпись в формате base64
     * @return `true`, если подпись верна
     * @throws SignatureVerificationException если подпись не разбирается
     */
    fun verify(
        canonicalBytes: ByteArray,
        signature: String,
    ): Boolean {
        val decoded =
            runCatching { Base64.getDecoder().decode(signature.trim()) }
                .getOrElse {
                    throw SignatureVerificationException(
                        "Подпись «${signature.take(32)}…» не декодируется из base64: ${it.message}",
                        it,
                    )
                }
        return runCatching {
            Signature.getInstance(algorithm).run {
                initVerify(publicKey)
                update(canonicalBytes)
                verify(decoded)
            }
        }.getOrElse {
            throw SignatureVerificationException(
                "Не удалось проверить подпись ключом «$keyId»: ${it.message}",
                it,
            )
        }
    }

    /**
     * Отдаёт ключ в виде, пригодном для ответа HTTP.
     *
     * Закрытая половина сюда не попадает по построению: в классе её просто
     * нет, поэтому эндпоинт не может отдать её даже по ошибке.
     *
     * @return описание ключа для ответа
     */
    fun toResponse(): VerificationKeyResponse =
        VerificationKeyResponse(
            keyId = keyId,
            algorithm = algorithm,
            publicKey = publicKeyBase64,
        )

    companion object {
        /**
         * Собирает ключ проверки из строки переменной окружения.
         *
         * @param keyId идентификатор пары ключей
         * @param base64PublicKey открытый ключ в base64
         * @return ключ проверки
         * @throws SigningException если ключ не распознан
         */
        fun of(
            keyId: String,
            base64PublicKey: String,
        ): VerificationKey {
            require(keyId.isNotBlank()) {
                "Идентификатор ключа обязателен: воркер должен знать, каким ключом " +
                    "подписан сценарий (FR-089c)"
            }
            return VerificationKey(
                keyId = keyId,
                publicKey = Signer.publicKeyFromBase64(base64PublicKey),
                publicKeyBase64 = base64PublicKey.trim(),
            )
        }

        /**
         * Строит ключ проверки из уже разобранного открытого ключа.
         *
         * @param keyId идентификатор пары ключей
         * @param publicKey открытый ключ
         * @return ключ проверки
         */
        fun of(
            keyId: String,
            publicKey: PublicKey,
        ): VerificationKey =
            VerificationKey(
                keyId = keyId,
                publicKey = publicKey,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
            )
    }
}

/**
 * Ответ с открытым ключом проверки.
 *
 * Форма ответа — часть контракта публичного API: воркер читает её, а не
 * догадывается о полях.
 *
 * @property keyId идентификатор пары ключей
 * @property algorithm имя алгоритма подписи
 * @property publicKey открытый ключ в base64
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class VerificationKeyResponse(
    val keyId: String,
    val algorithm: String,
    val publicKey: String,
)

/**
 * Подпись не прошла проверку или не разбирается.
 *
 * Отдельный тип отличается от «подпись неверна»: второе — нормальный ответ
 * для подделанного сценария, первое — проблема разбора, и обе должны
 * приводить к отказу, а не к «попробуем ещё раз» (FR-089c, SC-012).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SignatureVerificationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
