package ru.svoemesto.syp.core.jobs

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.svoemesto.syp.core.db.Db
import java.sql.DriverManager

/**
 * Доступ к базе для контрактных тестов.
 *
 * Тесты очереди требуют живой базы (задача T029). База поднимается
 * одноразовым контейнером `postgres:16` скриптом
 * `tools/run-db-tests.sh`; параметры подключения приходят через окружение.
 *
 * Если переменные не заданы, тесты **пропускаются**, а не падают: юнит-проверки
 * без базы должны оставаться работоспособными. Пропуск виден в отчёте и не
 * выдаётся за выполненную проверку.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object TestDb {
    /** Имя переменной окружения со строкой подключения. */
    const val ENV_URL: String = "SYP_TEST_DB_URL"

    /** Имя переменной окружения с пользователем базы. */
    const val ENV_USER: String = "SYP_TEST_DB_USER"

    /** Имя переменной окружения с паролем базы. */
    const val ENV_PASSWORD: String = "SYP_TEST_DB_PASSWORD"

    private var instance: Db? = null

    /** Подключена ли база для тестов. */
    val isAvailable: Boolean
        get() = System.getenv(ENV_URL)?.isNotBlank() == true

    /**
     * Готовит подключение один раз на весь набор тестов.
     *
     * Тесты, требующие базы, вызывают [assumeDatabase] вместо прямого
     * обращения: без базы они помечаются пропущенными.
     */
    fun setUp() {
        assumeTrue(isAvailable) {
            "Переменная $ENV_URL не задана: контрактные тесты очереди пропущены. " +
                "Поднимите базу через bash tools/run-db-tests.sh"
        }
        instance =
            Db(
                url = System.getenv(ENV_URL),
                user = System.getenv(ENV_USER) ?: "postgres",
                password = System.getenv(ENV_PASSWORD) ?: "postgres",
            )
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SELECT 1")
            }
        }
    }

    /**
     * Отдаёт подключение или помечает тест пропущенным.
     *
     * @return готовый доступ к базе
     */
    fun assumeDatabase(): Db {
        assumeTrue(isAvailable && instance != null) {
            "База для контрактных тестов не поднята: переменная $ENV_URL не задана"
        }
        return instance!!
    }

    /** Открывает соединение с тестовой базой. */
    fun connection(): java.sql.Connection =
        DriverManager.getConnection(
            System.getenv(ENV_URL),
            System.getenv(ENV_USER) ?: "postgres",
            System.getenv(ENV_PASSWORD) ?: "postgres",
        )
}
