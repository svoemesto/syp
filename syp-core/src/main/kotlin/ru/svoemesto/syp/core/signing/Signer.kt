package ru.svoemesto.syp.core.signing

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Подпись сценария алгоритмом Ed25519.
 *
 * Подпись ставится над **каноническими байтами целиком**, а не над хешем
 * объекта: при разборе JSON порядок ключей не гарантирован, поэтому подпись
 * над объектом воспроизводима только при явной канонизации
 * (research.md Т-23, контракт сценария, раздел 3).
 *
 * Закрытый ключ приходит **только** из переменной окружения. В репозитории его
 * нет, и guard `tools/check-no-secrets.sh` обязан быть пуст
 * (constitution VIII, ADR-0011).
 *
 * Подпись и её идентификатор хранятся **отдельно** от подписываемых байтов:
 * подпись без суммы нечего проверять, сумма без подписи не защищает (модель
 * данных, инвариант 1 раздела 2.20).
 *
 * @property keyId идентификатор пары ключей: по нему воркер выбирает ключ
 *   проверки, а не получает закрытый ключ
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class Signer(
    private val keyId: String,
    private val privateKey: PrivateKey,
) {
    /**
     * Идентификатор пары ключей.
     *
     * Открыт наружу, потому что идентификатор едет вместе с подписью: по нему
     * воркер выбирает ключ проверки, и без него подпись не сказала бы, каким
     * ключом подписан сценарий (FR-089c).
     */
    val signingKeyId: String
        get() = keyId

    /**
     * Подписывает канонические байты.
     *
     * @param canonicalBytes байты, полученные от
     *   [Canonicalizer.canonicalBytes]
     * @return подпись в формате base64
     * @throws SigningException если подпись не удалось вычислить
     */
    fun sign(canonicalBytes: ByteArray): String {
        require(keyId.isNotBlank()) {
            "Идентификатор ключа подписи обязателен: без него сценарий нельзя " +
                "проверить на машине пользователя (FR-089c)"
        }
        return runCatching {
            Signature.getInstance(ALGORITHM).run {
                initSign(privateKey)
                update(canonicalBytes)
                Base64.getEncoder().encodeToString(sign())
            }
        }.getOrElse {
            throw SigningException("Не удалось подписать сценарий ключом «$keyId»: ${it.message}", it)
        }
    }

    /**
     * Подписывает значение: сначала канонизирует, потом подписывает.
     *
     * @param value значение любого поддерживаемого типа
     * @return подпись в формате base64
     * @throws SigningException если подпись не удалось вычислить
     */
    fun signValue(value: Any?): String = sign(Canonicalizer.canonicalBytes(value))

    /**
     * Возвращает идентификатор ключа вместе с подписью.
     *
     * Идентификатор едет **вместе** с подписью: сменился ключ — сценарий
     * остался проверяемым по старому, но только если сказано, каким именно
     * ключом он подписан.
     *
     * @param canonicalBytes подписываемые байты
     * @return пара «идентификатор ключа — подпись»
     * @throws SigningException если подпись не удалось вычислить
     */
    fun signWithKeyId(canonicalBytes: ByteArray): Pair<String, String> = keyId to sign(canonicalBytes)

    companion object {
        /** Имя алгоритма подписи. */
        const val ALGORITHM: String = "Ed25519"

        /**
         * Читает закрытый ключ из строки в формате base64.
         *
         * Формат ключа задаётся переменной окружения `SYP_SIGNING_PRIVATE_KEY`.
         * Принимаются оба варианта: DER в base64 (`PKCS#8`) и PEM-подобный
         * текст с почерком PEM. Текст с заголовком блока считается ключом,
         * сам заголовок в задании ключа и в репозитории не хранится.
         *
         * @param base64Key содержимое переменной окружения
         * @return закрытый ключ
         * @throws SigningException если строка не является ключом Ed25519
         */
        fun privateKeyFromBase64(base64Key: String): PrivateKey {
            val der = decodeKeyMaterial(base64Key)
            return runCatching {
                KeyFactory.getInstance(ALGORITHM).generatePrivate(PKCS8EncodedKeySpec(der))
            }.getOrElse {
                throw SigningException(
                    "Переменная окружения с закрытым ключом не содержит ключ Ed25519 " +
                        "в формате PKCS#8. Ключ создаётся командой " +
                        "bash deploy/do.sh keys_generate",
                    it,
                )
            }
        }

        /**
         * Генерирует новую пару ключей.
         *
         * Вызывается вне сервера, при выпуске ключа; закрытая половина уходит
         * в переменную окружения и больше нигде не сохраняется.
         *
         * @return пара ключей Ed25519
         * @throws SigningException если генерация не удалась
         */
        fun generateKeyPair(): KeyPair =
            runCatching {
                // Размер ключа Ed25519 фиксирован алгоритмом и не задаётся:
                // вызов initialize с длиной приводит к InvalidParameterException.
                KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair()
            }.getOrElse { throw SigningException("Не удалось создать пару ключей Ed25519: ${it.message}", it) }

        /**
         * Кодирует ключ в base64 для переменной окружения.
         *
         * @param key ключ
         * @return содержимое переменной окружения
         */
        fun encodeBase64(key: java.security.Key): String = Base64.getEncoder().encodeToString(key.encoded)

        /**
         * Убирает заголовки PEM и переносы строк, оставляя base64.
         *
         * @param raw содержимое переменной окружения
         * @return материал ключа в DER
         * @throws SigningException если строка не разбирается
         */
        internal fun decodeKeyMaterial(raw: String): ByteArray {
            val withoutHeaders =
                raw
                    .replace(Regex("-----BEGIN [A-Z ]+-----"), "")
                    .replace(Regex("-----END [A-Z ]+-----"), "")
            return runCatching { Base64.getDecoder().decode(withoutHeaders.filterNot { it.isWhitespace() }) }
                .getOrElse {
                    throw SigningException("Ключ не декодируется из base64: ${it.message}", it)
                }
        }

        /**
         * Читает открытый ключ из строки в формате base64.
         *
         * @param base64Key содержимое переменной окружения
         * @return открытый ключ
         * @throws SigningException если строка не является ключом Ed25519
         */
        fun publicKeyFromBase64(base64Key: String): PublicKey {
            val der = decodeKeyMaterial(base64Key)
            return runCatching {
                KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(der))
            }.getOrElse {
                throw SigningException(
                    "Открытый ключ не распознан: ожидается Ed25519 в формате X.509 " +
                        "в base64",
                    it,
                )
            }
        }
    }
}

/**
 * Ошибка подписи или разбора ключа.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SigningException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
