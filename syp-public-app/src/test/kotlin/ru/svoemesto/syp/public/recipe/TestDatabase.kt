package ru.svoemesto.syp.public.recipe

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.svoemesto.syp.core.db.Db
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicLong

/**
 * Подготовка базы для проверок формата сценария.
 *
 * База поднимается одноразовым контейнером `postgres:16` скриптом
 * `tools/run-db-tests.sh`; параметры подключения приходят через окружение.
 * Без базы тесты **пропускаются**, а не падают: модуль без неё должен собираться
 * и проходить проверки, не требующие данных.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object TestDatabase {
    /** Имя переменной окружения со строкой подключения. */
    const val ENV_URL: String = "SYP_TEST_DB_URL"

    /** Имя переменной окружения с пользователем базы. */
    const val ENV_USER: String = "SYP_TEST_DB_USER"

    /** Имя переменной окружения с паролем базы. */
    const val ENV_PASSWORD: String = "SYP_TEST_DB_PASSWORD"

    private var instance: Db? = null

    private val tempRoots = mutableListOf<Path>()

    /** Подключена ли база для тестов. */
    val isAvailable: Boolean
        get() = System.getenv(ENV_URL)?.isNotBlank() == true

    /**
     * Готовит подключение один раз на весь набор тестов.
     *
     * @throws org.opentest4j.TestAbortedException если база не поднята
     */
    fun setUp() {
        assumeTrue(isAvailable) {
            "Переменная $ENV_URL не задана: проверки формата сценария пропущены. " +
                "Поднимите базу через bash tools/run-db-tests.sh"
        }
        if (instance == null) {
            val database =
                Db(
                    url = System.getenv(ENV_URL),
                    user = System.getenv(ENV_USER) ?: "postgres",
                    password = System.getenv(ENV_PASSWORD) ?: "postgres",
                )
            connection().use { it.createStatement().use { statement -> statement.execute("SELECT 1") } }
            instance = database
        }
    }

    /**
     * Отдаёт подключение или помечает тест пропущенным.
     *
     * @return готовый доступ к базе
     */
    fun assumeDatabase(): Db {
        assumeTrue(isAvailable && instance != null) {
            "База для проверок формата сценария не поднята: переменная $ENV_URL не задана"
        }
        return instance!!
    }

    /**
     * Выполняет блок и требует, чтобы база его отвергла.
     *
     * Проверка ограничений обязана идти **через базу**: правило, которое держит
     * только код, держит ровно до следующей правки кода.
     *
     * @param what что именно должно быть отвергнуто
     * @param block операция, которая обязана быть отвергнута
     */
    fun rejected(
        what: String,
        block: () -> Unit,
    ) {
        var refused = false
        try {
            block()
        } catch (failure: Throwable) {
            refused = true
            assumeTrue(
                failure !is org.opentest4j.TestAbortedException,
                "проверка оборвалась на подготовке, а не на отказе базы",
            )
        }
        assertTrue(refused, "база обязана отвергнуть: $what")
    }

    /**
     * Заводит фильм для теста.
     *
     * @param name название фильма, уникальное внутри прогона
     * @return идентификатор фильма
     */
    fun insertProject(name: String): Long =
        connection().use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO tbl_projects (name, source_root) VALUES (?, ?) RETURNING id",
                ).use { statement ->
                    statement.setString(1, "$name ${counter.incrementAndGet()}")
                    statement.setString(2, "/disks/HDD_16Tb_Clouds/GOT")
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /**
     * Заводит эпизод для теста с минимальным набором параметров.
     *
     * @param projectId фильм-владелец
     * @param name название эпизода
     * @return идентификатор эпизода
     */
    fun insertVideofile(
        projectId: Long,
        name: String,
    ): Long =
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO tbl_videofiles (id_project, ordinal, name, source_path, file_size, file_mtime,
                                        frame_count, time_base_num, time_base_den, width, height,
                                        duration_num, duration_den, video_codec, pixel_format)
                    VALUES (?, (SELECT coalesce(max(ordinal), 0) + 1 FROM tbl_videofiles WHERE id_project = ?),
                            ?, ?, 1, now(), 1000, 1001, 24000, 1920, 1080, 1, 1, 'h264', 'yuv420p')
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, projectId)
                    statement.setLong(2, projectId)
                    statement.setString(3, name)
                    statement.setString(4, "/disks/HDD_16Tb_Clouds/GOT/$name.mkv")
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /**
     * Заводит сцену для теста.
     *
     * @param videofileId эпизод-владелец
     * @param firstFrame расчётная граница начала
     * @param lastFrame расчётная граница конца
     * @return идентификатор сцены
     */
    fun insertScene(
        videofileId: Long,
        firstFrame: Int,
        lastFrame: Int,
    ): Long =
        connection().use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO tbl_scenes (id_videofile, first_frame, last_frame) VALUES (?, ?, ?) RETURNING id",
                ).use { statement ->
                    statement.setLong(1, videofileId)
                    statement.setInt(2, firstFrame)
                    statement.setInt(3, lastFrame)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        resultSet.getLong(1)
                    }
                }
        }

    /**
     * Пишет строку сценария в обход ограничений приложения.
     *
     * Нужна проверкам самих ограничений: сценарий с плохой суммой нельзя
     * записать обычным путём, а проверить надо именно то, что база его не
     * пропустит.
     *
     * @param projectId фильм-владелец
     * @param name название сценария
     * @param state состояние выдачи
     * @param artifactId артефакт
     * @param contentSha256 сумма содержимого
     * @param signature подпись
     * @param signingKeyId идентификатор ключа
     * @param createdAt момент выдачи
     * @param finishedAt момент завершения
     */
    fun insertRecipeRow(
        projectId: Long,
        name: String,
        state: ru.svoemesto.syp.core.recipe.RecipeState,
        artifactId: Long?,
        contentSha256: String?,
        signature: String?,
        signingKeyId: String?,
        createdAt: OffsetDateTime,
        finishedAt: OffsetDateTime?,
    ) {
        connection().use { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO tbl_build_recipes (id_project, name, schema_version, state, artifact_id,
                                              content_sha256, signature, signing_key_id, item_count,
                                              expected_duration_ms, expected_frame_count,
                                              created_at, finished_at)
                    VALUES (?, ?, 1, ?, ?, ?, ?, ?, 1, 100, 100, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, projectId)
                    statement.setString(2, name)
                    statement.setString(3, state.name)
                    statement.setObject(4, artifactId)
                    statement.setString(5, contentSha256)
                    statement.setString(6, signature)
                    statement.setString(7, signingKeyId)
                    // Время передаётся как OffsetDateTime: драйвер отправляет
                    // Instant строкой, и `timestamptz` такую строку не принимает.
                    statement.setObject(8, createdAt)
                    statement.setObject(9, finishedAt)
                    statement.executeUpdate()
                }
        }
    }

    /**
     * Создаёт временный каталог хранилища на время прогона.
     *
     * @param prefix начало имени каталога
     * @return готовый каталог
     */
    fun tempStorageRoot(prefix: String): Path {
        val root = Files.createTempDirectory("syp-$prefix-")
        tempRoots.add(root)
        return root
    }

    /** Открывает соединение с тестовой базой. */
    fun connection(): Connection =
        DriverManager.getConnection(
            System.getenv(ENV_URL),
            System.getenv(ENV_USER) ?: "postgres",
            System.getenv(ENV_PASSWORD) ?: "postgres",
        )

    private val counter = AtomicLong(0)
}

/** Проверка, что отказ действительно был. Обёртка нужна ради читаемости вызова. */
private fun assertTrue(
    condition: Boolean,
    message: String,
) {
    org.junit.jupiter.api.Assertions
        .assertTrue(condition, message)
}
