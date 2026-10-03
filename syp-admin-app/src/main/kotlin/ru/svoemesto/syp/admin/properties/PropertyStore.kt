package ru.svoemesto.syp.admin.properties

import ru.svoemesto.syp.core.db.Db

/**
 * Произвольное свойство в хранении.
 *
 * @property id идентификатор записи
 * @property key ключ; вводит оператор
 * @property value значение; вводит оператор
 * @property ordinal порядок показа в интерфейсе
 */
data class StoredProperty(
    val id: Long,
    val key: String,
    val value: String,
    val ordinal: Int,
)

/**
 * Вид владельца свойства.
 *
 * Перечисление, а не строка с именем класса, как в старом проекте: строкой связь
 * рвётся молча при переносе или переименовании класса в коде, и это не
 * гипотеза — миграция 24, изменённая после применения, показала, как легко файл
 * расходится с базой.
 *
 * @property value значение, каким вид хранится в базе
 */
enum class PropertyOwnerKind(
    val value: String,
) {
    /** Сериал целиком. */
    PROJECT("PROJECT"),

    /** Видеофайл внутри сериала. */
    VIDEOFILE("VIDEOFILE"),

    /** Дорожка видеофайла. */
    TRACK("TRACK"),

    /** План. */
    SHOT("SHOT"),

    /** Сцена. */
    SCENE("SCENE"),

    /** Персона. */
    PERSON("PERSON"),
    ;

    companion object {
        /**
         * Разбирает вид владельца из строки.
         *
         * @param value значение из базы или адреса
         * @return вид владельца
         * @throws IllegalArgumentException если вида нет
         */
        fun of(value: String): PropertyOwnerKind =
            entries.firstOrNull { it.value == value.uppercase() }
                ?: throw IllegalArgumentException(
                    "Вид владельца свойства «$value» неизвестен: допустимы " +
                        entries.joinToString(", ") { it.value },
                )
    }
}

/**
 * Хранилище произвольных свойств.
 *
 * Свойство привязано к владельцу видом и номером, а не именем класса. Проверку
 * существования владельца ведёт триггер в базе (миграция 26): свойство не может
 * зависнуть на несуществующем владельце, и нельзя записать его мимо базы.
 *
 * @property db соединение с базой
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class PropertyStore(
    private val db: Db,
) {
    /**
     * Читает свойства владельца по порядку показа.
     *
     * @param kind вид владельца
     * @param ownerId номер владельца
     * @return свойства
     */
    fun list(
        kind: PropertyOwnerKind,
        ownerId: Long,
    ): List<StoredProperty> =
        db.use { connection ->
            connection
                .prepareStatement(
                    "SELECT id, property_key, property_value, ordinal FROM $TABLE " +
                        "WHERE owner_kind = ? AND owner_id = ? ORDER BY ordinal, id",
                ).use { statement ->
                    statement.setString(1, kind.value)
                    statement.setLong(2, ownerId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(
                                    StoredProperty(
                                        id = rows.getLong("id"),
                                        key = rows.getString("property_key"),
                                        value = rows.getString("property_value"),
                                        ordinal = rows.getInt("ordinal"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    /**
     * Записывает свойство владельца.
     *
     * Ключ уникален на владельца, поэтому повторная запись с тем же ключом
     * заменяет значение, а не плодит дубли: оператор вводит ключ заново при
     * опечатке в значении, и второй строки с тем же ключом быть не должно.
     *
     * @param kind вид владельца
     * @param ownerId номер владельца
     * @param key ключ
     * @param value значение
     * @return номер записи
     * @throws ru.svoemesto.syp.core.db.DbException если владельца нет или ключ пуст
     */
    fun put(
        kind: PropertyOwnerKind,
        ownerId: Long,
        key: String,
        value: String,
    ): Long =
        db.use { connection ->
            val order =
                connection
                    .prepareStatement(
                        "SELECT coalesce(max(ordinal) + 1, 0) FROM $TABLE " +
                            "WHERE owner_kind = ? AND owner_id = ?",
                    ).use { statement ->
                        statement.setString(1, kind.value)
                        statement.setLong(2, ownerId)
                        statement.executeQuery().use { rows ->
                            if (rows.next()) rows.getInt(1) else 0
                        }
                    }
            connection
                .prepareStatement(
                    "INSERT INTO $TABLE (owner_kind, owner_id, property_key, property_value, ordinal) " +
                        "VALUES (?, ?, ?, ?, ?) ON CONFLICT (owner_kind, owner_id, property_key) " +
                        "DO UPDATE SET property_value = EXCLUDED.property_value",
                ).use { statement ->
                    statement.setString(1, kind.value)
                    statement.setLong(2, ownerId)
                    statement.setString(3, key)
                    statement.setString(4, value)
                    statement.setInt(5, order)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement(
                    "SELECT id FROM $TABLE WHERE owner_kind = ? AND owner_id = ? AND property_key = ?",
                ).use { statement ->
                    statement.setString(1, kind.value)
                    statement.setLong(2, ownerId)
                    statement.setString(3, key)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) rows.getLong(1) else 0L
                    }
                }
        }

    /**
     * Удаляет свойство владельца.
     *
     * @param kind вид владельца
     * @param ownerId номер владельца
     * @param key ключ
     * @return сколько записей удалено
     */
    fun delete(
        kind: PropertyOwnerKind,
        ownerId: Long,
        key: String,
    ): Int =
        db.use { connection ->
            connection
                .prepareStatement(
                    "DELETE FROM $TABLE WHERE owner_kind = ? AND owner_id = ? AND property_key = ?",
                ).use { statement ->
                    statement.setString(1, kind.value)
                    statement.setLong(2, ownerId)
                    statement.setString(3, key)
                    statement.executeUpdate()
                }
        }

    private companion object {
        /** Таблица свойств. */
        const val TABLE: String = "tbl_properties"
    }
}
