package ru.svoemesto.syp.core.recipe

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.sql.Connection
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Состояние сценария сборки.
 *
 * Ровно четыре значения, как у прогона анализа и записи справочника сумм: это
 * состояния результата вычисления, а не состояния очереди заданий. Пять
 * состояний у очереди потому, что там есть ещё «взято воркером, работа не
 * началась» (FR-003).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class RecipeState {
    /** Сценарий заведён, но ещё не подписан. */
    CREATING,

    /** Идёт сборка канонических байтов и подпись. */
    WORKING,

    /** Сценарий подписан, артефакт готов и сценарий выдаётся пользователю. */
    DONE,

    /** Выдача не удалась; [BuildRecipe.errorText] обязателен. */
    ERROR,
    ;

    companion object {
        /**
         * Разбирает состояние из строки базы.
         *
         * @param value значение столбца `state`
         * @return состояние
         * @throws IllegalArgumentException если значения нет в наборе
         */
        fun parse(value: String): RecipeState =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "Неизвестное состояние сценария: «$value». Допустимы: ${entries.joinToString()}",
                )
    }
}

/**
 * Сценарий сборки в базе.
 *
 * Подпись, сумма содержимого и идентификатор ключа присутствуют **либо все
 * три, либо ни одного** — тем же ограничением база, но здесь состав полей
 * виден по типам: `null` у всех трёх означает «ещё не подписано», а подпись
 * без идентификатора ключа означала бы, что сценарий не сказано, каким ключом
 * он подписан (FR-089c).
 *
 * @property id идентификатор сценария; `null`, пока не записан
 * @property movieId фильм-владелец
 * @property name название сценария
 * @property schemaVersion версия формата сценария
 * @property state состояние выдачи
 * @property artifactId артефакт с каноническими байтами; у `DONE` обязателен
 * @property contentSha256 SHA-256 канонических байтов
 * @property signature подпись в base64
 * @property signingKeyId идентификатор пары ключей
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность по фактическим границам
 * @property expectedFrameCount расчётное число кадров
 * @property createdAt момент выдачи
 * @property finishedAt момент завершения выдачи
 * @property errorText текст ошибки при [RecipeState.ERROR]
 * @property isStale сценарий создан при другой версии формата: помечается при
 *   выдаче, ничего не удаляет (FR-090, ADR-0014)
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class BuildRecipe(
    val id: Long? = null,
    val movieId: Long,
    val name: String,
    val schemaVersion: Int = RecipeFormat.SCHEMA_VERSION,
    val state: RecipeState,
    val artifactId: Long? = null,
    val contentSha256: String? = null,
    val signature: String? = null,
    val signingKeyId: String? = null,
    val itemCount: Int = 0,
    val expectedDurationMs: Long? = null,
    val expectedFrameCount: Long? = null,
    val createdAt: OffsetDateTime,
    val finishedAt: OffsetDateTime? = null,
    val errorText: String? = null,
    val isStale: Boolean = false,
    val recordHash: String? = null,
) {
    /** Сценарий готов к выдаче: подписан, содержимое однозначно, ключ назван. */
    val isSigned: Boolean
        get() = signature != null && contentSha256 != null && signingKeyId != null

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами сценария
     */
    fun toTable(): Table =
        Table(
            RecipeStore.TABLE,
            RecipeStore.COLUMNS,
            {
                listOf(
                    movieId,
                    name,
                    schemaVersion,
                    state.name,
                    artifactId,
                    contentSha256,
                    signature,
                    signingKeyId,
                    itemCount,
                    expectedDurationMs,
                    expectedFrameCount,
                    createdAt,
                    finishedAt,
                    errorText,
                    isStale,
                )
            },
            recordHash,
        )

    companion object {
        /** Столбцы сценария в порядке чтения из базы, вместе со служебным хешем. */
        val READ_COLUMNS: String =
            "id, id_movie, name, schema_version, state, artifact_id, content_sha256, " +
                "signature, signing_key_id, item_count, expected_duration_ms, " +
                "expected_frame_count, created_at, finished_at, error_text, is_stale, recordhash"
    }
}

/**
 * Фрагмент сценария в базе.
 *
 * Значения сцены — **снимки строк**, а не ссылки: правка справочника после
 * выдачи не должна «слепить» уже скачанный сценарий, а локальный показ
 * работает без сервера (FR-089d).
 *
 * @property recipeId сценарий-владелец
 * @property ordinal порядковый номер фрагмента, с единицы
 * @property sceneId сцена-источник
 * @property episodeId эпизод-источник
 * @property episodeName название эпизода-снимок
 * @property relativePath путь к файлу эпизода от корня фильма
 * @property sourceSha256 снимок эталонной суммы на момент выдачи
 * @property firstFrame расчётная граница начала
 * @property lastFrame расчётная граница конца
 * @property cutFirstFrame фактическая граница начала
 * @property cutLastFrame фактическая граница конца
 * @property sceneTitle название сцены-снимок
 * @property locationName место действия-снимок
 * @property personNames имена персонажей-снимок
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class BuildRecipeItem(
    val recipeId: Long,
    val ordinal: Int,
    val sceneId: Long,
    val episodeId: Long,
    val episodeName: String,
    val relativePath: String,
    val sourceSha256: String,
    val firstFrame: Int,
    val lastFrame: Int,
    val cutFirstFrame: Int,
    val cutLastFrame: Int,
    val sceneTitle: String? = null,
    val locationName: String? = null,
    val personNames: List<String> = emptyList(),
    val recordHash: String? = null,
) {
    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами фрагмента
     */
    fun toTable(): Table =
        Table(
            RecipeStore.ITEM_TABLE,
            RecipeStore.ITEM_COLUMNS,
            {
                listOf(
                    recipeId,
                    ordinal,
                    sceneId,
                    episodeId,
                    episodeName,
                    relativePath,
                    sourceSha256,
                    firstFrame,
                    lastFrame,
                    cutFirstFrame,
                    cutLastFrame,
                    sceneTitle,
                    locationName,
                    JsonStrings.write(personNames),
                )
            },
            recordHash,
            mapOf(PERSON_NAMES_COLUMN to "jsonb"),
        )

    /** Строит фрагмент канонической формы сценария. */
    fun toDocumentItem(): RecipeItemDocument =
        RecipeItemDocument(
            ordinal = ordinal,
            sceneId = sceneId,
            sceneTitle = sceneTitle,
            location = locationName,
            persons = personNames,
            episodeId = episodeId,
            episodeName = episodeName,
            relativePath = relativePath,
            sourceSha256 = sourceSha256,
            firstFrame = firstFrame,
            lastFrame = lastFrame,
            cutFirstFrame = cutFirstFrame,
            cutLastFrame = cutLastFrame,
        )

    companion object {
        /** Столбец со снимком имён персонажей. */
        const val PERSON_NAMES_COLUMN: String = "person_names"

        /** Столбцы фрагмента в порядке чтения из базы, вместе со служебным хешем. */
        val READ_COLUMNS: String =
            "recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, " +
                "source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, " +
                "scene_title, location_name, person_names, recordhash"
    }
}

/**
 * Хранилище сценариев сборки.
 *
 * Персистентность — сырой JDBC с сохранением по различию значений: значения
 * строки приводятся к хешу, и запись выполняется только при реальном
 * изменении (constitution III). Столбец `recordhash` заполняется механизмом
 * сохранения сам и в список записываемых столбцов не входит.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class RecipeStore(
    private val db: Db,
) {
    /**
     * Читает сценарий по идентификатору.
     *
     * @param recipeId идентификатор сценария
     * @return сценарий или `null`, если его нет
     */
    fun find(recipeId: Long): BuildRecipe? =
        db.selectOne("SELECT ${BuildRecipe.READ_COLUMNS} FROM $TABLE WHERE id = ?", ::readRow, recipeId)

    /**
     * Перечисляет сценарии фильма, свежие сверху.
     *
     * @param movieId идентификатор фильма
     * @param limit сколько сценариев вернуть
     * @return сценарии в порядке убывания времени выдачи
     */
    fun listByMovie(
        movieId: Long,
        limit: Int,
    ): List<BuildRecipe> =
        db.select(
            "SELECT ${BuildRecipe.READ_COLUMNS} FROM $TABLE WHERE id_movie = ? " +
                "ORDER BY created_at DESC, id DESC LIMIT ?",
            ::readRow,
            movieId,
            limit,
        )

    /**
     * Перечисляет фрагменты сценария в порядке следования.
     *
     * @param recipeId идентификатор сценария
     * @return фрагменты по порядковым номерам
     */
    fun items(recipeId: Long): List<BuildRecipeItem> =
        db.select(
            "SELECT ${BuildRecipeItem.READ_COLUMNS} FROM $ITEM_TABLE WHERE recipe_id = ? ORDER BY ordinal",
            ::readItemRow,
            recipeId,
        )

    /**
     * Записывает сценарий и его фрагменты.
     *
     * Фрагменты пишутся в одной транзакции со сценарием: сценарий в состоянии
     * `DONE` без фрагментов — это не подборка, а дефект выдачи, и база такой
     * строкой не даст закрыться (`item_count > 0`).
     *
     * @param recipe сценарий для записи
     * @param items фрагменты сценария
     * @return записанный сценарий с идентификатором и хешем
     * @throws IllegalArgumentException если список фрагментов пуст
     */
    fun insert(
        recipe: BuildRecipe,
        items: List<BuildRecipeItem>,
    ): BuildRecipe {
        require(items.isNotEmpty()) { "Сценарий без фрагментов не записывается: подборка без сцен пуста" }
        return db.useTransaction { connection ->
            val identifier = insertRecipe(connection, recipe)
            items.forEach { Save.insertIfAbsent(connection, it.copy(recipeId = identifier).toTable()) }
            // Чтение идёт **тем же** соединением: строка ещё не зафиксирована и
            // через другое соединение не видна. Чтение после коммита вернуло бы
            // `null` на первом же сценарии.
            readWithin(connection, identifier)
                ?: throw IllegalStateException(
                    "Сценарий записан под идентификатором $identifier, но сразу после " +
                        "записи не прочитан: это дефект, а не результат",
                )
        }
    }

    /**
     * Сохраняет изменения сценария, если значения изменились.
     *
     * @param recipe сценарий с заполненным [BuildRecipe.id]
     * @return `true`, если строка переписана; `false`, если изменений не было
     * @throws IllegalArgumentException если у сценария нет идентификатора
     */
    fun save(recipe: BuildRecipe): Boolean {
        val recipeId =
            recipe.id
                ?: throw IllegalArgumentException("У сценария «${recipe.name}» нет идентификатора: сохранять нечего")
        return db.useTransaction { connection ->
            Save.saveIfChanged(connection, recipe.toTable(), listOf("id"), listOf(recipeId))
        }
    }

    /**
     * Сохраняет фрагмент, если значения изменились.
     *
     * @param item фрагмент
     * @return `true`, если строка переписана
     */
    fun saveItem(item: BuildRecipeItem): Boolean =
        db.useTransaction { connection ->
            Save.saveIfChanged(
                connection,
                item.toTable(),
                listOf("recipe_id", "ordinal"),
                listOf(item.recipeId, item.ordinal),
            )
        }

    /** Вставляет строку сценария и возвращает выданный базой идентификатор. */
    private fun insertRecipe(
        connection: Connection,
        recipe: BuildRecipe,
    ): Long {
        val table = recipe.toTable()
        return connection
            .prepareStatement(table.insertSql(withRecordHash = true) + " RETURNING id")
            .use { statement ->
                bindRow(statement, table, table.computeRecordHash())
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    resultSet.getLong(1)
                }
            }
    }

    /**
     * Подставляет значения строки и её хеш в подготовленный запрос.
     *
     * Значения передаются **своими типами**, а не строками: `timestamptz` не
     * принимает текстовое представление момента, и подстановка строкой дала бы
     * отказ базы на самом первом же сценарии. Поведение совпадает с общим
     * механизмом сохранения — различается только то, что этот запрос
     * возвращает выданный базой идентификатор.
     */
    private fun bindRow(
        statement: java.sql.PreparedStatement,
        table: Table,
        recordHash: String,
    ) {
        table.values.forEachIndexed { index, value ->
            val position = index + 1
            val column = table.columns[index]
            when {
                value == null -> statement.setObject(position, null)
                column == BuildRecipeItem.PERSON_NAMES_COLUMN ->
                    // `jsonb` отклоняет строку без явного приведения: драйвер
                    // отправляет её как `character varying`, и база отвечает отказом.
                    statement.setObject(position, value, java.sql.Types.OTHER)
                value is Boolean -> statement.setBoolean(position, value)
                value is Int -> statement.setInt(position, value)
                value is Long -> statement.setLong(position, value)
                value is OffsetDateTime -> statement.setObject(position, value)
                value is java.sql.Timestamp -> statement.setTimestamp(position, value)
                value is String -> statement.setString(position, value)
                else -> statement.setObject(position, value)
            }
        }
        statement.setString(table.values.size + 1, recordHash)
    }

    /** Читает сценарий по идентификатору в пределах открытого соединения. */
    private fun readWithin(
        connection: Connection,
        recipeId: Long,
    ): BuildRecipe? =
        connection
            .prepareStatement("SELECT ${BuildRecipe.READ_COLUMNS} FROM $TABLE WHERE id = ?")
            .use { statement ->
                statement.setLong(1, recipeId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) readRow(Row(resultSet)) else null
                }
            }

    /** Строит сценарий из типизированной строки выборки. */
    private fun readRow(row: Row): BuildRecipe =
        BuildRecipe(
            id = row.long("id"),
            movieId = row.long("id_movie"),
            name = row.string("name"),
            schemaVersion = row.int("schema_version"),
            state = RecipeState.parse(row.string("state")),
            artifactId = row.longOrNull("artifact_id"),
            contentSha256 = row.stringOrNull("content_sha256"),
            signature = row.stringOrNull("signature"),
            signingKeyId = row.stringOrNull("signing_key_id"),
            itemCount = row.int("item_count"),
            expectedDurationMs = row.longOrNull("expected_duration_ms"),
            expectedFrameCount = row.longOrNull("expected_frame_count"),
            createdAt = readTimestamp(row, "created_at"),
            finishedAt = readOptionalTimestamp(row, "finished_at"),
            errorText = row.stringOrNull("error_text"),
            isStale = row.booleanOrNull("is_stale") == true,
            recordHash = row.stringOrNull("recordhash"),
        )

    /** Строит фрагмент из типизированной строки выборки. */
    private fun readItemRow(row: Row): BuildRecipeItem =
        BuildRecipeItem(
            recipeId = row.long("recipe_id"),
            ordinal = row.int("ordinal"),
            sceneId = row.long("scene_id"),
            episodeId = row.long("id_episode"),
            episodeName = row.string("episode_name"),
            relativePath = row.string("relative_path"),
            sourceSha256 = row.string("source_sha256"),
            firstFrame = row.int("first_frame"),
            lastFrame = row.int("last_frame"),
            cutFirstFrame = row.int("cut_first_frame"),
            cutLastFrame = row.int("cut_last_frame"),
            sceneTitle = row.stringOrNull("scene_title"),
            locationName = row.stringOrNull("location_name"),
            personNames = JsonStrings.read(row.json("person_names")),
            recordHash = row.stringOrNull("recordhash"),
        )

    /** Строит сценарий из готовой строки результата внутри транзакции. */
    private fun readTimestamp(
        row: Row,
        column: String,
    ): OffsetDateTime =
        (row.raw(column) as? java.sql.Timestamp)
            ?.toInstant()
            ?.atOffset(ZoneOffset.UTC)
            ?: throw IllegalStateException("Не прочитано обязательное время «$column»")

    /** Читает необязательное время. */
    private fun readOptionalTimestamp(
        row: Row,
        column: String,
    ): OffsetDateTime? = (row.raw(column) as? java.sql.Timestamp)?.toInstant()?.atOffset(ZoneOffset.UTC)

    companion object {
        /** Имя таблицы сценариев. */
        const val TABLE: String = "tbl_build_recipes"

        /** Имя таблицы фрагментов сценария. */
        const val ITEM_TABLE: String = "tbl_build_recipe_items"

        /** Записываемые столбцы сценария в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_movie",
                "name",
                "schema_version",
                "state",
                "artifact_id",
                "content_sha256",
                "signature",
                "signing_key_id",
                "item_count",
                "expected_duration_ms",
                "expected_frame_count",
                "created_at",
                "finished_at",
                "error_text",
                "is_stale",
            )

        /** Записываемые столбцы фрагмента в порядке значений. */
        val ITEM_COLUMNS: List<String> =
            listOf(
                "recipe_id",
                "ordinal",
                "scene_id",
                "id_episode",
                "episode_name",
                "relative_path",
                "source_sha256",
                "first_frame",
                "last_frame",
                "cut_first_frame",
                "cut_last_frame",
                "scene_title",
                "location_name",
                "person_names",
            )
    }
}

/**
 * Чтение и запись списка строк в виде JSON-массива.
 *
 * Своего разбора JSON в общем модуле нет: подключать библиотеку ради одного
 * столбца `jsonb` означало бы тянуть в общий модуль то, что в нём быть не
 * должно (ADR-0011, последствие 4). Значения — имена персонажей и названия
 * эпизодов, то есть обычный текст; экранирование задаётся теми же правилами, что
 * и в канонической форме сценария, иначе одно и то же имя записалось бы в
 * двух форматах по-разному.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object JsonStrings {
    /**
     * Записывает список строк как JSON-массив.
     *
     * @param values значения
     * @return текст массива; пустой список даёт `[]`
     */
    fun write(values: List<String>): String = values.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"" + escape(it) + "\"" }

    /**
     * Разбирает JSON-массив строк.
     *
     * @param text текст массива
     * @return значения массива; пустой массив даёт пустой список
     * @throws IllegalArgumentException если текст не является массивом строк
     */
    fun read(text: String): List<String> {
        val trimmed = text.trim()
        require(trimmed.startsWith("[") && trimmed.endsWith("]")) {
            "Ожидался массив строк, получено: ${trimmed.take(40)}"
        }
        if (trimmed == "[]") return emptyList()
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var insideString = false
        var escaped = false
        var closed = false
        trimmed.substring(1, trimmed.length - 1).forEach { character ->
            when {
                escaped -> {
                    current.append(unescape(character))
                    escaped = false
                }
                character == '\\' && insideString -> escaped = true
                character == '"' -> {
                    if (insideString) {
                        values.add(current.toString())
                        current.setLength(0)
                        closed = true
                    }
                    insideString = !insideString
                }
                insideString -> current.append(character)
                character.isWhitespace() || character == ',' -> Unit
                else -> throw IllegalArgumentException("Ожидался массив строк, получен символ «$character»")
            }
        }
        require(closed && !insideString && !escaped) {
            "Массив строк не закрыт: ${trimmed.take(40)}"
        }
        return values
    }

    /** Экранирует значение для записи в JSON. */
    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
            .replace("\t", "\\t")

    /** Возвращает символ, записанный после обратного слэша. */
    private fun unescape(character: Char): Char =
        when (character) {
            'n' -> '\n'
            't' -> '\t'
            'r' -> '\n'
            'b' -> '\b'
            'f' -> '\u000C'
            else -> character
        }
}
