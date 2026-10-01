package ru.svoemesto.syp.public.config

/**
 * Порты и адреса внешних сервисов публичного бэкенда.
 *
 * Все значения читаются **из окружения** (research.md Т-22). У модуля нет
 * исполнителя заданий, поэтому здесь нет и параметров параллелизма воркера:
 * публичная часть ничего не исполняет (FR-085, research.md Т-20).
 *
 * Закрытого ключа подписи у публичного бэкенда **нет и быть не должно**:
 * сценарий приходит уже подписанным, а ключ подписи публикуется как открытый
 * ключ для проверки на машине пользователя (ADR-0011, последствие 2).
 *
 * @property publicWebPort порт HTTP-сервера публичного бэкенда
 * @property databaseUrl строка подключения к `syp-db`
 * @property databaseUser пользователь базы
 * @property databasePassword пароль пользователя базы
 * @property storageEndpoint адрес объектного хранилища `syp-storage`
 * @property storageAccessKey ключ доступа к хранилищу
 * @property storageSecretKey секретный ключ хранилища
 * @property storageBucket корзина артефактов на SSD
 * @property signingKeyId идентификатор пары ключей подписи, которой подписан сценарий
 * @property signingPublicKey открытый ключ подписи в формате base64
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class PublicPorts(
    val publicWebPort: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val storageEndpoint: String,
    val storageAccessKey: String,
    val storageSecretKey: String,
    val storageBucket: String,
    val signingKeyId: String,
    val signingPublicKey: String,
) {
    companion object {
        /** Имя переменной окружения с портом публичного бэкенда. */
        const val ENV_PUBLIC_WEB_PORT: String = "SYP_PUBLIC_WEB_PORT"

        /** Имя переменной окружения с идентификатором ключа подписи. */
        const val ENV_SIGNING_KEY_ID: String = "SYP_SIGNING_KEY_ID"

        /** Имя переменной окружения с открытым ключом подписи. */
        const val ENV_SIGNING_PUBLIC_KEY: String = "SYP_SIGNING_PUBLIC_KEY"

        /** Порт по умолчанию, если переменная окружения не задана. */
        const val DEFAULT_PUBLIC_WEB_PORT: Int = 7911

        /**
         * Читает обязательную переменную окружения.
         *
         * @param name имя переменной
         * @return значение переменной
         * @throws IllegalStateException если переменная не задана или пуста
         */
        fun requiredEnv(name: String): String = required(name)

        /**
         * Читает числовую переменную окружения с запасным значением.
         *
         * @param name имя переменной
         * @param fallback значение, если переменная не задана или не число
         * @return разобранное число или [fallback]
         */
        fun intEnv(
            name: String,
            fallback: Int,
        ): Int = intOr(name, fallback)
    }
}

/**
 * Чтение обязательной переменной окружения.
 *
 * Вынесено отдельной функцией намеренно: файл конфигурации называется
 * `Ports.kt` — на это имя ссылаются план и задачи спеки, — а правило линтера
 * `standard:filename` требует, чтобы имя файла совпадал с именем единственного
 * объявления в нём. Второе объявление снимает противоречие, не меняя ни имён
 * файлов, ни вызывающего кода.
 *
 * @param name имя переменной
 * @return значение переменной
 * @throws IllegalStateException если переменная не задана или пуста
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun required(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException(
            "Переменная окружения $name не задана. Секреты и параметры " +
                "развёртывания передаются только через окружение (constitution VIII.5).",
        )

/**
 * Чтение числовой переменной окружения с запасным значением.
 *
 * @param name имя переменной
 * @param fallback значение, если переменная не задана или не число
 * @return разобранное число или [fallback]
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun intOr(
    name: String,
    fallback: Int,
): Int = System.getenv(name)?.trim()?.toIntOrNull() ?: fallback
