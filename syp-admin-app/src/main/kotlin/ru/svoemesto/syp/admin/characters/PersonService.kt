package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table

/**
 * Вид персоны.
 *
 * Виды те же, что и в ограничении базы `person_kind_known`, и набор закрыт:
 * новый вид без миграции база не примет, а значение из строки неизвестного
 * вида читается как отказ, а не как « PERSON ».
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class PersonKind {
    /** Именованная личность; ключ распознавателя обязателен. */
    PERSON,

    /** Лицо найдено, имя не подтверждено оператором. */
    UNRECOGNIZED,

    /** Рамка оказалась не лицом. */
    NONPERSON,
    ;

    /** Служебный ли вид: такие персоны заведены системой, а не оператором. */
    val isService: Boolean
        get() = this != PERSON

    companion object {
        /**
         * Разбирает вид персоны из строки базы.
         *
         * @param value значение столбца `kind`
         * @return вид персоны
         * @throws IllegalArgumentException если вид неизвестен: молча
         *   подставить обычную персону вместо служебной значило бы потерять
         *   заглушку
         */
        fun of(value: String): PersonKind =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестный вид персоны «$value»")
    }
}

/**
 * Персона фильма.
 *
 * Идентичность персоны не зависит от отображаемого имени: переименование не
 * ломает обученную модель, потому что модель знает ключ распознавателя, а не
 * имя (`docs/domains/characters/domain.md`, инвариант 5).
 *
 * @property id идентификатор персоны; `null`, пока не записана
 * @property projectId фильм-владелец
 * @property name отображаемое имя; уникально в пределах фильма
 * @property recognizerKey ключ класса в модели; пуст у служебных персон
 * @property kind вид персоны
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Person(
    val id: Long? = null,
    val projectId: Long,
    val name: String,
    val recognizerKey: String?,
    val kind: PersonKind,
    val recordHash: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Имя персоны обязательно" }
        if (kind == PersonKind.PERSON) {
            require(!recognizerKey.isNullOrBlank()) {
                "У именованной персоны «$name» обязателен ключ распознавателя"
            }
        } else {
            require(recognizerKey == null) {
                "У служебной персоны «$name» ключа распознавателя быть не должно, " +
                    "задано «$recognizerKey»: служебная персона — заглушка, а не класс модели"
            }
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами персоны
     */
    fun toTable(): Table =
        Table(
            PersonService.TABLE,
            PersonService.COLUMNS,
            {
                listOf(
                    projectId,
                    name,
                    recognizerKey,
                    kind.name,
                )
            },
            recordHash,
        )
}

/**
 * Персоны фильма и их служебные заглушки.
 *
 * Сервис держит три правила, каждое из которых иначе выполнялось бы «по
 * памяти» call-сайтов:
 *
 * 1. **у каждого фильма есть обе заглушки.** Их заводит триггер базы
 *    (миграция `11_service_persons.sql`), а [ensureServicePersons] доводит
 *    дело до конца для фильма, заведённого раньше этой миграции;
 * 2. **«нет персоны» — это заглушка, а не пустая ссылка.** [servicePerson]
 *    отдаёт конкретную персону вместо `null`, и ссылка у лица непустая всегда
 *    (Р-12, FR-033);
 * 3. **служебная персона не переименовывается и не удаляется.** Это её вид,
 *    а не её имя; переименование сделало бы заглушку обычной персоной, а
 *    удаление — оставило бы систему без заглушки. Оба действия отвергаются
 *    с текстом, а не выполняются «на всякий случай».
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class PersonService(
    private val db: Db,
) {
    /**
     * Заводит обе служебные персоны фильма, если их ещё нет.
     *
     * Метод идемпотентен и безопасен для повторного вызова: уникальный
     * индекс `person_service_kind_unique_idx` не даёт завести вторую
     * заглушку того же вида, а `ON CONFLICT` не даёт упасть гонке двух
     * одновременных вызовов.
     *
     * @param projectId идентификатор фильма
     * @return обе служебные персоны в порядке видов: неопознанная, «не лицо»
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     */
    fun ensureServicePersons(projectId: Long): List<Person> {
        requireProject(projectId)
        return PersonKind.entries
            .filter { it.isService }
            .map { ensureOne(projectId, it, nameOf(it)) }
    }

    /**
     * Читает служебную персону фильма, заводя её при отсутствии.
     *
     * Метод — точка, которой пользуется всё остальное: «нет персоны»
     * выражается заглушкой, поэтому метод, возвращающий `null`, в домене
     * отсутствует (Р-12).
     *
     * @param projectId идентификатор фильма
     * @param kind вид служебной персоны
     * @return служебная персона фильма
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     * @throws IllegalArgumentException если запрошен не служебный вид
     */
    fun servicePerson(
        projectId: Long,
        kind: PersonKind,
    ): Person {
        requireProject(projectId)
        require(kind.isService) { "Служебной является только персона вида $kind" }
        return ensureOne(projectId, kind, nameOf(kind))
    }

    /**
     * Читает персону по идентификатору.
     *
     * @param personId идентификатор персоны
     * @return персона или `null`, если её нет
     */
    fun find(personId: Long): Person? = db.selectOne(SELECT_BY_ID, ::readRow, personId)

    /**
     * Читает всех персон фильма: сначала служебные, затем именованные по
     * имени.
     *
     * @param projectId идентификатор фильма
     * @return персоны фильма
     */
    fun listByProject(projectId: Long): List<Person> =
        db.select(
            "$SELECT_ALL WHERE id_project = ? ORDER BY (kind = 'PERSON'), name",
            ::readRow,
            projectId,
        )

    /**
     * Создаёт именованную персону.
     *
     * @param projectId идентификатор фильма
     * @param name отображаемое имя
     * @param recognizerKey ключ класса в модели
     * @return созданная персона
     * @throws DomainException с кодом `CONFLICT`, если имя занято или совпадает
     *   с именем служебной персоны: под именем «Не лицо» нельзя завести
     *   живого человека, иначе оператор перепутает заглушку и персону
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     */
    fun create(
        projectId: Long,
        name: String,
        recognizerKey: String,
    ): Person {
        requireProject(projectId)
        val person =
            Person(
                projectId = projectId,
                name = name.trim(),
                recognizerKey = recognizerKey.trim(),
                kind = PersonKind.PERSON,
            )
        if (person.name in RESERVED_NAMES) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "Имя «${person.name}» занято служебной персоной: под ним нельзя завести " +
                    "именованную персону, иначе заглушка и персона станут неразличимы",
            )
        }
        return db.useTransaction { connection ->
            val duplicate = nameTaken(connection, projectId, person.name)
            if (duplicate) {
                throw DomainException(
                    ErrorCode.CONFLICT,
                    "Персона «${person.name}» уже есть в фильме $projectId: имена уникальны",
                )
            }
            Save.insertIfAbsent(connection, person.toTable())
            readRequired(
                connection,
                findInConnection(connection, person.projectId, person.name),
                "Персона «${person.name}» записана, но не читается",
            )
        }
    }

    /**
     * Переименовывает именованную персону.
     *
     * Переименование не ломает модель: модель знает ключ распознавателя, а
     * не имя (ADR-0004, инвариант 5 домена персонажей).
     *
     * @param personId идентификатор персоны
     * @param name новое отображаемое имя
     * @return переименованная персона
     * @throws DomainException с кодом `NOT_FOUND`, если персоны нет
     * @throws DomainException с кодом `CONFLICT`, если у персоны служебный вид
     *   или имя занято другой персоной либо служебной заглушкой
     */
    fun rename(
        personId: Long,
        name: String,
    ): Person {
        val current = requirePerson(personId)
        if (current.kind.isService) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "Служебную персону «${current.name}» вида ${current.kind} переименовать нельзя: " +
                    "её вид и есть смысл, а имя — только подпись",
            )
        }
        val trimmed = name.trim()
        if (trimmed in RESERVED_NAMES) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "Имя «$trimmed» занято служебной персоной: под ним нельзя завести " +
                    "именованную персону, иначе заглушка и персона станут неразличимы",
            )
        }
        val renamed = current.copy(name = trimmed)
        return db.useTransaction { connection ->
            if (nameTaken(connection, current.projectId, trimmed)) {
                throw DomainException(
                    ErrorCode.CONFLICT,
                    "Персона «$trimmed» уже есть в фильме ${current.projectId}: имена уникальны",
                )
            }
            Save.saveIfChanged(
                connection,
                renamed.toTable(),
                listOf("id"),
                listOf(personId),
            )
            readRequired(
                connection,
                findInConnection(connection, current.projectId, trimmed),
                "Персона $personId переименована, но новое имя не читается",
            )
        }
    }

    /**
     * Удаляет именованную персону, переводя её лица в неопознанных.
     *
     * Лица при этом **не удаляются**: неопознанное лицо не теряется при
     * удалении персоны (FR-036, SC-006). Перевод выполняется в той же
     * транзакции, что и удаление, — промежуточного состояния «лицо без
     * персоны» в базе не бывает, а столбец `person_id` к тому же объявлен
     * `NOT NULL`.
     *
     * @param personId идентификатор персоны
     * @return `true`, если персона удалена
     * @throws DomainException с кодом `NOT_FOUND`, если персоны нет
     * @throws DomainException с кодом `CONFLICT`, если персона служебная
     */
    fun delete(personId: Long): Boolean {
        val person = requirePerson(personId)
        if (person.kind.isService) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "Служебную персону «${person.name}» вида ${person.kind} удалить нельзя: " +
                    "«нет персоны» выражается ею, а не пустой ссылкой (Р-12)",
            )
        }
        return db.useTransaction { connection ->
            val unrecognized =
                findInConnection(connection, person.projectId, UNRECOGNIZED_NAME)
                    ?: throw ru.svoemesto.syp.core.db.DbException(
                        "В фильме ${person.projectId} нет служебной персоны «$UNRECOGNIZED_NAME»: " +
                            "переводить лица некуда. Проверьте миграцию 11_service_persons.sql",
                    )
            connection
                .prepareStatement(
                    "UPDATE tbl_faces SET person_id = ? WHERE person_id = ?",
                ).use { statement ->
                    statement.setLong(1, requireNotNull(unrecognized.id))
                    statement.setLong(2, personId)
                    statement.executeUpdate()
                }
            Save.delete(
                connection,
                person.toTable(),
                listOf("id"),
                listOf(personId),
            )
        }
    }

    /**
     * Заводит одну служебную персону, если её ещё нет.
     *
     * @param projectId идентификатор фильма
     * @param kind вид служебной персоны
     * @param name отображаемое имя заглушки
     * @return служебная персона
     */
    private fun ensureOne(
        projectId: Long,
        kind: PersonKind,
        name: String,
    ): Person =
        db.useTransaction { connection ->
            val existing = findKindInConnection(connection, projectId, kind)
            if (existing != null) {
                existing
            } else {
                val person = Person(projectId = projectId, name = name, recognizerKey = null, kind = kind)
                Save.insertIfAbsent(connection, person.toTable())
                readRequired(
                    connection,
                    findKindInConnection(connection, projectId, kind),
                    "служебная персона вида $kind фильма $projectId записана, но не читается",
                )
            }
        }

    /**
     * Отображаемое имя служебной персоны по виду.
     *
     * @param kind вид персоны
     * @return имя заглушки
     */
    private fun nameOf(kind: PersonKind): String =
        when (kind) {
            PersonKind.UNRECOGNIZED -> UNRECOGNIZED_NAME
            PersonKind.NONPERSON -> NON_PERSON_NAME
            PersonKind.PERSON -> throw IllegalArgumentException("У вида PERSON служебного имени нет")
        }

    /**
     * Проверяет, что фильм заведён.
     *
     * @param projectId идентификатор фильма
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     */
    private fun requireProject(projectId: Long) {
        val exists = db.selectOne("SELECT 1 AS present FROM tbl_projects WHERE id = ?", { it.int("present") }, projectId)
        if (exists == null) {
            throw DomainException(ErrorCode.NOT_FOUND, "Фильм $projectId не заведён: персон у него нет")
        }
    }

    /**
     * Читает персону по идентификатору или отвергает запрос.
     *
     * @param personId идентификатор персоны
     * @return персона
     * @throws DomainException с кодом `NOT_FOUND`, если персоны нет
     */
    private fun requirePerson(personId: Long): Person =
        find(personId)
            ?: throw DomainException(
                ErrorCode.NOT_FOUND,
                "Персона $personId не найдена: менять нечего",
            )

    /**
     * Читает персону по фильму и имени.
     *
     * @param connection открытое соединение
     * @param projectId идентификатор фильма
     * @param name имя персоны
     * @return персона или `null`
     */
    private fun findInConnection(
        connection: java.sql.Connection,
        projectId: Long,
        name: String,
    ): Person? = firstRow(connection, "$SELECT_ALL WHERE id_project = ? AND name = ?", projectId, name)

    /**
     * Читает служебную персону по виду.
     *
     * Поиск идёт **по виду, а не по имени**: переименование заглушки не
     * должно приводить к появлению второй заглушки, иначе «нет персоны»
     * снова можно было бы выразить двумя способами.
     *
     * @param connection открытое соединение
     * @param projectId идентификатор фильма
     * @param kind вид персоны
     * @return персона или `null`
     */
    private fun findKindInConnection(
        connection: java.sql.Connection,
        projectId: Long,
        kind: PersonKind,
    ): Person? = firstRow(connection, "$SELECT_ALL WHERE id_project = ? AND kind = ?", projectId, kind.name)

    /**
     * Читает первую строку выборки персон.
     *
     * @param connection открытое соединение
     * @param sql текст выборки с параметрами `?`
     * @param parameters значения параметров
     * @return персона или `null`, если строк нет
     */
    private fun firstRow(
        connection: java.sql.Connection,
        sql: String,
        vararg parameters: Any?,
    ): Person? =
        connection.prepareStatement(sql).use { statement ->
            parameters.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) readRow(Row(resultSet)) else null
            }
        }

    /**
     * Проверяет, занято ли имя в фильме.
     *
     * @param connection открытое соединение
     * @param projectId идентификатор фильма
     * @param name имя персоны
     * @return `true`, если имя уже занято
     */
    private fun nameTaken(
        connection: java.sql.Connection,
        projectId: Long,
        name: String,
    ): Boolean =
        connection
            .prepareStatement("SELECT 1 FROM tbl_persons WHERE id_project = ? AND name = ?")
            .use { statement ->
                statement.setLong(1, projectId)
                statement.setString(2, name)
                statement.executeQuery().use { it.next() }
            }

    /**
     * Строит персону из типизированной строки выборки.
     *
     * @param row строка выборки
     * @return персона
     */
    private fun readRow(row: Row): Person =
        Person(
            id = row.long("id"),
            projectId = row.long("id_project"),
            name = row.string("name"),
            recognizerKey = row.stringOrNull("recognizer_key"),
            kind = PersonKind.of(row.string("kind")),
            recordHash = row.stringOrNull(Table.RECORD_HASH_COLUMN),
        )

    /**
     * Требует найденную персону.
     *
     * @param connection открытое соединение
     * @param person персона или `null`
     * @return найденная персона
     * @throws ru.svoemesto.syp.core.db.DbException если персона не найдена
     *   после записи: это расхождение данных, а не пустой результат
     */
    private fun readRequired(
        connection: java.sql.Connection,
        person: Person?,
        what: String,
    ): Person =
        person
            ?: throw ru.svoemesto.syp.core.db.DbException(
                "$what: у фильма не оказалось ни одной строки",
            )

    companion object {
        /** Имя таблицы персон. */
        const val TABLE: String = "tbl_persons"

        /** Записываемые столбцы персоны в порядке значений. */
        val COLUMNS: List<String> = listOf("id_project", "name", "recognizer_key", "kind")

        /** Имя служебной персоны «распознано, но имя не подтверждено». */
        const val UNRECOGNIZED_NAME: String = "Распознано, имя не подтверждено"

        /** Имя служебной персоны «не лицо». */
        const val NON_PERSON_NAME: String = "Не лицо"

        /** Имена служебных персон; под ними именованную персону не заводят. */
        val RESERVED_NAMES: Set<String> = setOf(UNRECOGNIZED_NAME, NON_PERSON_NAME)

        /** Столбцы персоны в порядке чтения из базы. */
        private const val READ_COLUMNS: String =
            "id, id_project, name, recognizer_key, kind, ${Table.RECORD_HASH_COLUMN}"

        /** Выборка одной персоны по идентификатору. */
        val SELECT_BY_ID: String = "SELECT $READ_COLUMNS FROM $TABLE WHERE id = ?"

        /** Начало общей выборки персон. */
        val SELECT_ALL: String = "SELECT $READ_COLUMNS FROM $TABLE"
    }
}
