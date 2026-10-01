package ru.svoemesto.syp.admin.config

/**
 * Порты и адреса внешних сервисов админского бэкенда.
 *
 * Все значения читаются **из окружения**, а не заданы в коде: стек локальный,
 * но конкретные номера портов — параметр развёртывания (research.md Т-22),
 * и их смена не должна требовать пересборки.
 *
 * Порты веба берутся из диапазона 7910–7999, хранилища — из 9020–9099.
 * Конкретные значения живут в `deploy/.env`, который вне системы контроля
 * версий (constitution VIII).
 *
 * @property adminWebPort порт HTTP-сервера админского бэкенда
 * @property databaseUrl строка подключения к `syp-db`
 * @property databaseUser пользователь базы
 * @property databasePassword пароль пользователя базы
 * @property storageEndpoint адрес объектного хранилища `syp-storage`
 * @property storageAccessKey ключ доступа к хранилищу
 * @property storageSecretKey секретный ключ хранилища
 * @property storageBucket корзина артефактов на SSD
 * @property workerConcurrencyLimit сколько заданий админский воркер берёт одновременно
 * @property gpuConcurrencyLimit сколько заданий требуют видеокарты одновременно
 * @property signingKeyId идентификатор пары ключей подписи сценария
 * @property signingPrivateKey закрытый ключ подписи: **только** из окружения
 * @property signingPublicKey открытый ключ подписи в формате base64
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class AdminPorts(
    val adminWebPort: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val storageEndpoint: String,
    val storageAccessKey: String,
    val storageSecretKey: String,
    val storageBucket: String,
    val workerConcurrencyLimit: Int,
    val gpuConcurrencyLimit: Int,
    val signingKeyId: String,
    val signingPrivateKey: String,
    val signingPublicKey: String,
) {
    companion object {
        /** Имя переменной окружения с портом админского бэкенда. */
        const val ENV_ADMIN_WEB_PORT: String = "SYP_ADMIN_WEB_PORT"

        /** Имя переменной окружения с числом параллельных заданий. */
        const val ENV_WORKER_CONCURRENCY: String = "SYP_WORKER_CONCURRENCY"

        /** Имя переменной окружения с числом заданий на видеокарте. */
        const val ENV_GPU_CONCURRENCY: String = "SYP_GPU_CONCURRENCY"

        /** Имя переменной окружения с идентификатором ключа подписи. */
        const val ENV_SIGNING_KEY_ID: String = "SYP_SIGNING_KEY_ID"

        /** Имя переменной окружения с закрытым ключом подписи. */
        const val ENV_SIGNING_PRIVATE_KEY: String = "SYP_SIGNING_PRIVATE_KEY"

        /** Имя переменной окружения с открытым ключом подписи. */
        const val ENV_SIGNING_PUBLIC_KEY: String = "SYP_SIGNING_PUBLIC_KEY"

        /** Порт по умолчанию, если переменная окружения не задана. */
        const val DEFAULT_ADMIN_WEB_PORT: Int = 7910

        /**
         * Читает обязательную переменную окружения.
         *
         * Секрета без переменной окружения не бывает: вместо молчаливого
         * дефолта бэкенд не стартует (constitution VIII.5).
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
