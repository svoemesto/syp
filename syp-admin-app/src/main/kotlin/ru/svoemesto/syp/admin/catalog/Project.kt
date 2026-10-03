package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.sql.Connection
import java.time.OffsetDateTime

/**
 * Фильм — произведение, с которым работает оператор.
 *
 * Фильм владеет справочником мест действия, списком эпизодов и настройками
 * анализа. Один эпизод принадлежит **ровно одному** фильму: это не соглашение,
 * а внешний ключ `videofile.id_project NOT NULL`.
 *
 * **Корень каталога** — обязательное поле, а не украшение. Сценарий сборки
 * обращается к файлам по путям относительно этого корня (FR-089a): у
 * пользователя своя копия дерева под своим корнем, и общий корень — единственное,
 * что переносит смысл пути с машины администратора на машину пользователя.
 * Без записанного корня относительный путь вычислить не из чего.
 *
 * Инварианты проверяются дважды: здесь, чтобы оператор получил внятный текст,
 * и ограничениями базы, чтобы мимо кода тоже было нельзя.
 *
 * @property id идентификатор; `null`, пока фильм не записан
 * @property name название фильма, уникально
 * @property sourceRoot корень каталога фильма: абсолютный путь без
 *   завершающего слэша
 * @property createdAt дата создания, проставляемая базой
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Project(
    val id: Long? = null,
    val name: String,
    val sourceRoot: String,
    val createdAt: OffsetDateTime? = null,
    val recordHash: String? = null,
) {
    init {
        require(name.isNotBlank()) {
            "Название фильма обязательно: пустое название не отличить ни от чего в списке"
        }
        require(sourceRoot.isNotBlank()) {
            "Корень каталога фильма обязателен: без него не вычислить относительный " +
                "путь к файлу эпизода, который попадёт в сценарий сборки (FR-089a)"
        }
        require(sourceRoot.startsWith("/")) {
            "Корень каталога фильма обязан быть абсолютным путём, задано «$sourceRoot»"
        }
        require(!sourceRoot.endsWith("/")) {
            "Корень каталога фильма не оканчивается слэшем, задано «$sourceRoot»: " +
                "иначе один и тот же каталог записывается двумя способами"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами фильма
     */
    fun toTable(): Table = Table(NAME, COLUMNS, { listOf(name, sourceRoot) }, recordHash)

    companion object {
        /** Имя таблицы фильмов. */
        const val NAME: String = "tbl_projects"

        /** Записываемые столбцы фильма в порядке значений. */
        val COLUMNS: List<String> = listOf("name", "source_root")

        /** Столбцы фильма в порядке чтения из базы. */
        val READ_COLUMNS: String = "id, name, source_root, created_at, recordhash"
    }
}

/**
 * Фильм вместе с числом своих эпизодов.
 *
 * Отдельная величина, а не поле фильма: число эпизодов вычисляется, в таблице его
 * нет, и хранить его означало бы держать вторую правду о составе фильма,
 * которая расходилась бы с фактом после каждого удаления.
 *
 * @property project сам фильм
 * @property videofileCount сколько эпизодов заведено в фильме
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ProjectSummary(
    val project: Project,
    val videofileCount: Int,
)

/**
 * Хранилище фильмов.
 *
 * Запись идёт через общий механизм сохранения по различию значений с
 * `recordhash`: строка переписывается, только если её значения действительно
 * изменились (constitution III). Чтение — сырым JDBC, без отображения
 * объектов.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ProjectStore(
    private val db: Db,
) {
    /**
     * Создаёт фильм.
     *
     * Значения настроек анализа по умолчанию создаёт триггер базы: фильм,
     * заведённый позже, получает те же одиннадцать настроек, что и фильм,
     * существовавший на момент миграции (ADR-0003, data-model 2.22).
     *
     * @param name название фильма
     * @param sourceRoot корень каталога фильма на машине администратора
     * @return созданный фильм со значением `id` и датой создания
     * @throws DomainException с кодом `CONFLICT`, если название уже занято
     */
    fun create(
        name: String,
        sourceRoot: String,
    ): Project {
        val project = Project(name = name.trim(), sourceRoot = sourceRoot.trim())
        return db.useTransaction { connection ->
            val existing = findByName(connection, project.name)
            if (existing != null) {
                throw DomainException(
                    ErrorCode.CONFLICT,
                    "фильм «${project.name}» уже заведён: переименуйте или удалите прежний",
                )
            }
            Save.insertIfAbsent(connection, project.toTable())
            readRequired(connection, findByName(connection, project.name))
        }
    }

    /**
     * Читает фильм по идентификатору.
     *
     * @param projectId идентификатор фильма
     * @return фильм или `null`, если его нет
     */
    fun find(projectId: Long): Project? = db.selectOne(SELECT_BY_ID, ::readRow, projectId)

    /**
     * Перечисляет фильмы с числом эпизодов каждого.
     *
     * Число эпизодов считается тем же запросом, а не обращением на фильм: список
     * фильмов показывается на каждом экране, и обращение на строку превратило
     * бы открытие списка в десятки походов в базу.
     *
     * @return фильмы с числом эпизодов в порядке создания
     */
    fun listWithVideofileCount(): List<ProjectSummary> =
        db.select(
            "$SELECT_WITH_COUNT ORDER BY s.created_at, s.id",
            { row -> ProjectSummary(readRow(row), row.int("videofile_count")) },
        )

    /**
     * Перечисляет фильмы.
     *
     * @return фильмы в порядке создания
     */
    fun list(): List<Project> = db.select("SELECT ${Project.READ_COLUMNS} FROM tbl_projects ORDER BY created_at, id", ::readRow)

    /**
     * Сохраняет изменения фильма, если значения изменились.
     *
     * @param project фильм с заполненным [Project.id]
     * @return `true`, если строка переписана
     * @throws DomainException если у фильма нет идентификатора
     */
    fun save(project: Project): Boolean {
        val projectId =
            project.id
                ?: throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "у фильма «${project.name}» нет идентификатора: сохранять нечего",
                )
        return db.useTransaction { connection ->
            Save.saveIfChanged(connection, project.toTable(), listOf("id"), listOf(projectId))
        }
    }

    /**
     * Удаляет фильм вместе со всеми производными данными.
     *
     * Каскад задан в базе: эпизода, сцены, планы, лица, персоны, версии моделей,
     * фильтры, сценарии сборки, справочник сумм и настройки. Файл источника
     * при этом **не трогается** — он лежит в архиве и принадлежит не системе.
     *
     * @param projectId идентификатор фильма
     * @return `true`, если фильм был удалён
     */
    fun delete(projectId: Long): Boolean = db.update("DELETE FROM tbl_projects WHERE id = ?", projectId) > 0

    /**
     * Считает эпизоды фильма.
     *
     * @param projectId идентификатор фильма
     * @return число эпизодов
     */
    fun countVideofile(projectId: Long): Int =
        db.selectOne("SELECT count(*) AS total FROM tbl_videofiles WHERE id_project = ?", { it.int("total") }, projectId) ?: 0

    /**
     * Следующий свободный порядковый номер эпизода в фильме.
     *
     * @param projectId идентификатор фильма
     * @return номер, который можно занять
     */
    fun nextVideofileOrdinal(projectId: Long): Int =
        (
            db.selectOne(
                "SELECT COALESCE(max(ordinal), -1) + 1 AS next_ordinal FROM tbl_videofiles WHERE id_project = ?",
                { it.int("next_ordinal") },
                projectId,
            ) ?: 0
        )

    /** Читает фильм по названию в пределах открытого соединения. */
    private fun findByName(
        connection: Connection,
        name: String,
    ): Project? =
        connection
            .prepareStatement("SELECT ${Project.READ_COLUMNS} FROM tbl_projects WHERE name = ?")
            .use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) read(resultSet) else null
                }
            }

    /** Читает фильм в пределах открытого соединения по идентификатору. */
    private fun readRequired(
        connection: Connection,
        project: Project?,
    ): Project =
        project
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "фильм записан, но сразу после записи не прочитан: это дефект, а не результат",
            )

    /** Строит фильм из готовой строки результата. */
    private fun read(resultSet: java.sql.ResultSet): Project =
        Project(
            id = resultSet.getLong("id"),
            name = resultSet.getString("name"),
            sourceRoot = resultSet.getString("source_root"),
            createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java),
            recordHash = resultSet.getString("recordhash"),
        )

    /** Строит фильм из типизированной строки выборки. */
    private fun readRow(row: Row): Project =
        Project(
            id = row.long("id"),
            name = row.string("name"),
            sourceRoot = row.string("source_root"),
            createdAt =
                (row.raw("created_at") as? java.sql.Timestamp)
                    ?.toInstant()
                    ?.atOffset(java.time.ZoneOffset.UTC),
            recordHash = row.stringOrNull("recordhash"),
        )

    private companion object {
        /** Выборка одного фильма по идентификатору. */
        val SELECT_BY_ID: String = "SELECT ${Project.READ_COLUMNS} FROM tbl_projects WHERE id = ?"

        /** Выборка фильмов с числом эпизодов каждого. */
        val SELECT_WITH_COUNT: String =
            "SELECT s.id, s.name, s.source_root, s.created_at, s.recordhash, " +
                "(SELECT count(*) FROM tbl_videofiles WHERE id_project = s.id) AS videofile_count " +
                "FROM tbl_projects s"
    }
}
