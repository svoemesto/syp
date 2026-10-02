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
 * @property publicKeyBase64 открытый ключ в base64: так он приходит из окружения
 * @property algorithm имя алгоритма подписи
 * @property notBefore момент, с которого ключ считается доверенным: смена ключа
 *   даёт новый идентификатор, и по этому моменту видно, какой ключ новее
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class VerificationKey(
    val keyId: String,
    val publicKey: PublicKey,
    val publicKeyBase64: String,
    val algorithm: String = Signer.ALGORITHM,
    val notBefore: String = DEFAULT_NOT_BEFORE,
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
     * Открытый ключ в виде PEM.
     *
     * Именно PEM, а не голый base64: воркер проверяет подпись командой
     * `openssl`, и `openssl pkeyutl` с файлом открытого ключа читает PEM. Отдав
     * base64 без обрамления, пришлось бы собирать файл на стороне пользователя,
     * то есть отдавать не готовый ключ, а заготовку ключа.
     *
     * @return текст PEM с переводами строк по 64 символа
     */
    fun publicKeyPem(): String = toPem(publicKeyBase64)

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
            publicKeyPem = publicKeyPem(),
            notBefore = notBefore,
        )

    companion object {
        /**
         * Момент «ключ доверен с самого начала».
         *
         * Значение по умолчанию нужно только для ключей, созданных до появления
         * поля в контракте; у ключа, выпущенного развёртыванием, момент
         * задаётся явно.
         */
        const val DEFAULT_NOT_BEFORE: String = "1970-01-01T00:00:00Z"

        /**
         * Оборачивает base64 в текст PEM.
         *
         * @param base64 материал ключа в base64, без обрамления
         * @return текст PEM с переводами строк по 64 символа
         */
        fun toPem(base64: String): String {
            val material = base64.filterNot { it.isWhitespace() }
            val lines = material.chunked(PEM_LINE_LENGTH).joinToString("\n")
            return "$PEM_HEADER\n$lines\n$PEM_FOOTER\n"
        }

        /** Заголовок блока PEM с открытым ключом. */
        const val PEM_HEADER: String = "-----BEGIN PUBLIC KEY-----"

        /** Конец блока PEM с открытым ключом. */
        const val PEM_FOOTER: String = "-----END PUBLIC KEY-----"

        /** Длина строки base64 внутри блока PEM. */
        const val PEM_LINE_LENGTH: Int = 64

        /**
         * Собирает ключ проверки из строки переменной окружения.
         *
         * @param keyId идентификатор пары ключей
         * @param base64PublicKey открытый ключ в base64
         * @param notBefore момент, с которого ключ считается доверенным
         * @return ключ проверки
         * @throws SigningException если ключ не распознан
         */
        fun of(
            keyId: String,
            base64PublicKey: String,
            notBefore: String = DEFAULT_NOT_BEFORE,
        ): VerificationKey {
            require(keyId.isNotBlank()) {
                "Идентификатор ключа обязателен: воркер должен знать, каким ключом " +
                    "подписан сценарий (FR-089c)"
            }
            return VerificationKey(
                keyId = keyId,
                publicKey = Signer.publicKeyFromBase64(base64PublicKey),
                publicKeyBase64 = base64PublicKey.trim(),
                notBefore = notBefore,
            )
        }

        /**
         * Строит ключ проверки из уже разобранного открытого ключа.
         *
         * @param keyId идентификатор пары ключей
         * @param publicKey открытый ключ
         * @param notBefore момент, с которого ключ считается доверенным
         * @return ключ проверки
         */
        fun of(
            keyId: String,
            publicKey: PublicKey,
            notBefore: String = DEFAULT_NOT_BEFORE,
        ): VerificationKey =
            VerificationKey(
                keyId = keyId,
                publicKey = publicKey,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded),
                notBefore = notBefore,
            )
    }
}

/**
 * Ответ с открытым ключом проверки.
 *
 * Форма ответа — часть контракта публичного API: воркер читает её, а не
 * догадывается о полях. Ключ отдаётся **в PEM**, потому что проверяется
 * командой `openssl`, а не кодом на стороне пользователя.
 *
 * @property keyId идентификатор пары ключей
 * @property algorithm имя алгоритма подписи
 * @property publicKeyPem открытый ключ в виде текста PEM
 * @property notBefore момент, с которого ключ считается доверенным
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class VerificationKeyResponse(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val notBefore: String,
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
