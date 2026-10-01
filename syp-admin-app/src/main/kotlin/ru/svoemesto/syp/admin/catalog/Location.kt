package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table

/**
 * Место действия — элемент справочника сериала.
 *
 * Справочник ведётся вручную и назначается сцене только из него:
 * автоматического определения места действия в проекте нет и не будет
 * (FR-050, FR-052, constitution). Поэтому здесь нет ни вычисления, ни
 * сопоставления — только заведённые человеком названия.
 *
 * Название уникально в пределах сериала: две одинаковые локации в одном
 * сериале означали бы две правды об одном месте действия.
 *
 * @property id идентификатор; `null`, пока локация не записана
 * @property serialId сериал-владелец
 * @property name название места действия
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Location(
    val id: Long? = null,
    val serialId: Long,
    val name: String,
    val recordHash: String? = null,
) {
    init {
        require(name.isNotBlank()) {
            "Название места действия обязательно: без названия справочник бесполезен"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами локации
     */
    fun toTable(): Table = Table(NAME, COLUMNS, { listOf(serialId, name) }, recordHash)

    companion object {
        /** Имя таблицы мест действия. */
        const val NAME: String = "location"

        /** Записываемые столбцы локации в порядке значений. */
        val COLUMNS: List<String> = listOf("serial_id", "name")

        /** Столбцы локации в порядке чтения из базы. */
        const val READ_COLUMNS: String = "id, serial_id, name, recordhash"
    }
}

/**
 * Хранилище мест действия.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class LocationStore(
    private val db: Db,
) {
    /**
     * Добавляет место действия в справочник сериала.
     *
     * @param serialId сериал-владелец
     * @param name название места действия
     * @return записанная локация с идентификатором
     * @throws DomainException с кодом `CONFLICT`, если название уже занято
     */
    fun add(
        serialId: Long,
        name: String,
    ): Location {
        val location = Location(serialId = serialId, name = name.trim())
        val existing = findByName(serialId, location.name)
        if (existing != null) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "место действия «${location.name}» уже есть в справочнике сериала",
            )
        }
        return db.useTransaction { connection ->
            Save.insertIfAbsent(connection, location.toTable())
            readRequired(connection, findByName(connection, serialId, location.name))
        }
    }

    /**
     * Перечисляет места действия сериала.
     *
     * @param serialId сериал-владелец
     * @return локации по алфавиту
     */
    fun listBySerial(serialId: Long): List<Location> =
        db.select(
            "SELECT ${Location.READ_COLUMNS} FROM location WHERE serial_id = ? ORDER BY name",
            ::readRow,
            serialId,
        )

    /**
     * Удаляет место действия.
     *
     * У сцен, которым оно было назначено, ссылка на локацию обнуляется
     * (`ON DELETE SET NULL`): сцена остаётся, а место действия у неё больше не
     * заявлено. Молчаливая подмена на другую локацию здесь означала бы
     * изменение размеченных данных без правки оператора (constitution IV.4).
     *
     * @param locationId идентификатор локации
     * @return `true`, если локация была удалена
     */
    fun delete(locationId: Long): Boolean = db.update("DELETE FROM location WHERE id = ?", locationId) > 0

    /** Ищет место действия по названию. */
    private fun findByName(
        serialId: Long,
        name: String,
    ): Location? =
        db.selectOne(
            "SELECT ${Location.READ_COLUMNS} FROM location WHERE serial_id = ? AND name = ?",
            ::readRow,
            serialId,
            name,
        )

    /** Ищет место действия по названию в пределах открытого соединения. */
    private fun findByName(
        connection: java.sql.Connection,
        serialId: Long,
        name: String,
    ): Location? =
        connection
            .prepareStatement("SELECT ${Location.READ_COLUMNS} FROM location WHERE serial_id = ? AND name = ?")
            .use { statement ->
                statement.setLong(1, serialId)
                statement.setString(2, name)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) read(resultSet) else null
                }
            }

    /** Отдаёт найденную локацию или отказ. */
    private fun readRequired(
        connection: java.sql.Connection,
        location: Location?,
    ): Location =
        location
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "место действия записано, но сразу после записи не прочитано: это дефект, а не результат",
            )

    /** Строит локацию из готовой строки результата. */
    private fun read(resultSet: java.sql.ResultSet): Location =
        Location(
            id = resultSet.getLong("id"),
            serialId = resultSet.getLong("serial_id"),
            name = resultSet.getString("name"),
            recordHash = resultSet.getString("recordhash"),
        )

    /** Строит локацию из типизированной строки выборки. */
    private fun readRow(row: Row): Location =
        Location(
            id = row.long("id"),
            serialId = row.long("serial_id"),
            name = row.string("name"),
            recordHash = row.stringOrNull("recordhash"),
        )
}
