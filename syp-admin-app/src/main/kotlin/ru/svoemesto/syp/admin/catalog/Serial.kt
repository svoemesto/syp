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
 * Сериал — произведение, с которым работает оператор.
 *
 * Сериал владеет справочником мест действия, списком серий и настройками
 * анализа. Одна серия принадлежит **ровно одному** сериалу: это не соглашение,
 * а внешний ключ `series.serial_id NOT NULL`.
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
 * @property id идентификатор; `null`, пока сериал не записан
 * @property name название сериала, уникально
 * @property sourceRoot корень каталога сериала: абсолютный путь без
 *   завершающего слэша
 * @property createdAt дата создания, проставляемая базой
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Serial(
    val id: Long? = null,
    val name: String,
    val sourceRoot: String,
    val createdAt: OffsetDateTime? = null,
    val recordHash: String? = null,
) {
    init {
        require(name.isNotBlank()) {
            "Название сериала обязательно: пустое название не отличить ни от чего в списке"
        }
        require(sourceRoot.isNotBlank()) {
            "Корень каталога сериала обязателен: без него не вычислить относительный " +
                "путь к файлу серии, который попадёт в сценарий сборки (FR-089a)"
        }
        require(sourceRoot.startsWith("/")) {
            "Корень каталога сериала обязан быть абсолютным путём, задано «$sourceRoot»"
        }
        require(!sourceRoot.endsWith("/")) {
            "Корень каталога сериала не оканчивается слэшем, задано «$sourceRoot»: " +
                "иначе один и тот же каталог записывается двумя способами"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами сериала
     */
    fun toTable(): Table = Table(NAME, COLUMNS, { listOf(name, sourceRoot) }, recordHash)

    companion object {
        /** Имя таблицы сериалов. */
        const val NAME: String = "serial"

        /** Записываемые столбцы сериала в порядке значений. */
        val COLUMNS: List<String> = listOf("name", "source_root")

        /** Столбцы сериала в порядке чтения из базы. */
        val READ_COLUMNS: String = "id, name, source_root, created_at, recordhash"
    }
}

/**
 * Сериал вместе с числом своих серий.
 *
 * Отдельная величина, а не поле сериала: число серий вычисляется, в таблице его
 * нет, и хранить его означало бы держать вторую правду о составе сериала,
 * которая расходилась бы с фактом после каждого удаления.
 *
 * @property serial сам сериал
 * @property seriesCount сколько серий заведено в сериале
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SerialSummary(
    val serial: Serial,
    val seriesCount: Int,
)

/**
 * Хранилище сериалов.
 *
 * Запись идёт через общий механизм сохранения по различию значений с
 * `recordhash`: строка переписывается, только если её значения действительно
 * изменились (constitution III). Чтение — сырым JDBC, без отображения
 * объектов.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SerialStore(
    private val db: Db,
) {
    /**
     * Создаёт сериал.
     *
     * Значения настроек анализа по умолчанию создаёт триггер базы: сериал,
     * заведённый позже, получает те же одиннадцать настроек, что и сериал,
     * существовавший на момент миграции (ADR-0003, data-model 2.22).
     *
     * @param name название сериала
     * @param sourceRoot корень каталога сериала на машине администратора
     * @return созданный сериал со значением `id` и датой создания
     * @throws DomainException с кодом `CONFLICT`, если название уже занято
     */
    fun create(
        name: String,
        sourceRoot: String,
    ): Serial {
        val serial = Serial(name = name.trim(), sourceRoot = sourceRoot.trim())
        return db.useTransaction { connection ->
            val existing = findByName(connection, serial.name)
            if (existing != null) {
                throw DomainException(
                    ErrorCode.CONFLICT,
                    "сериал «${serial.name}» уже заведён: переименуйте или удалите прежний",
                )
            }
            Save.insertIfAbsent(connection, serial.toTable())
            readRequired(connection, findByName(connection, serial.name))
        }
    }

    /**
     * Читает сериал по идентификатору.
     *
     * @param serialId идентификатор сериала
     * @return сериал или `null`, если его нет
     */
    fun find(serialId: Long): Serial? = db.selectOne(SELECT_BY_ID, ::readRow, serialId)

    /**
     * Перечисляет сериалы с числом серий каждого.
     *
     * Число серий считается тем же запросом, а не обращением на сериал: список
     * сериалов показывается на каждом экране, и обращение на строку превратило
     * бы открытие списка в десятки походов в базу.
     *
     * @return сериалы с числом серий в порядке создания
     */
    fun listWithSeriesCount(): List<SerialSummary> =
        db.select(
            "$SELECT_WITH_COUNT ORDER BY s.created_at, s.id",
            { row -> SerialSummary(readRow(row), row.int("series_count")) },
        )

    /**
     * Перечисляет сериалы.
     *
     * @return сериалы в порядке создания
     */
    fun list(): List<Serial> = db.select("SELECT ${Serial.READ_COLUMNS} FROM serial ORDER BY created_at, id", ::readRow)

    /**
     * Сохраняет изменения сериала, если значения изменились.
     *
     * @param serial сериал с заполненным [Serial.id]
     * @return `true`, если строка переписана
     * @throws DomainException если у сериала нет идентификатора
     */
    fun save(serial: Serial): Boolean {
        val serialId =
            serial.id
                ?: throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "у сериала «${serial.name}» нет идентификатора: сохранять нечего",
                )
        return db.useTransaction { connection ->
            Save.saveIfChanged(connection, serial.toTable(), listOf("id"), listOf(serialId))
        }
    }

    /**
     * Удаляет сериал вместе со всеми производными данными.
     *
     * Каскад задан в базе: серии, сцены, планы, лица, персоны, версии моделей,
     * фильтры, сценарии сборки, справочник сумм и настройки. Файл источника
     * при этом **не трогается** — он лежит в архиве и принадлежит не системе.
     *
     * @param serialId идентификатор сериала
     * @return `true`, если сериал был удалён
     */
    fun delete(serialId: Long): Boolean = db.update("DELETE FROM serial WHERE id = ?", serialId) > 0

    /**
     * Считает серии сериала.
     *
     * @param serialId идентификатор сериала
     * @return число серий
     */
    fun countSeries(serialId: Long): Int =
        db.selectOne("SELECT count(*) AS total FROM series WHERE serial_id = ?", { it.int("total") }, serialId) ?: 0

    /**
     * Следующий свободный порядковый номер серии в сериале.
     *
     * @param serialId идентификатор сериала
     * @return номер, который можно занять
     */
    fun nextSeriesOrdinal(serialId: Long): Int =
        (
            db.selectOne(
                "SELECT COALESCE(max(ordinal), -1) + 1 AS next_ordinal FROM series WHERE serial_id = ?",
                { it.int("next_ordinal") },
                serialId,
            ) ?: 0
        )

    /** Читает сериал по названию в пределах открытого соединения. */
    private fun findByName(
        connection: Connection,
        name: String,
    ): Serial? =
        connection
            .prepareStatement("SELECT ${Serial.READ_COLUMNS} FROM serial WHERE name = ?")
            .use { statement ->
                statement.setString(1, name)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) read(resultSet) else null
                }
            }

    /** Читает сериал в пределах открытого соединения по идентификатору. */
    private fun readRequired(
        connection: Connection,
        serial: Serial?,
    ): Serial =
        serial
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "сериал записан, но сразу после записи не прочитан: это дефект, а не результат",
            )

    /** Строит сериал из готовой строки результата. */
    private fun read(resultSet: java.sql.ResultSet): Serial =
        Serial(
            id = resultSet.getLong("id"),
            name = resultSet.getString("name"),
            sourceRoot = resultSet.getString("source_root"),
            createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java),
            recordHash = resultSet.getString("recordhash"),
        )

    /** Строит сериал из типизированной строки выборки. */
    private fun readRow(row: Row): Serial =
        Serial(
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
        /** Выборка одного сериала по идентификатору. */
        val SELECT_BY_ID: String = "SELECT ${Serial.READ_COLUMNS} FROM serial WHERE id = ?"

        /** Выборка сериалов с числом серий каждого. */
        val SELECT_WITH_COUNT: String =
            "SELECT s.id, s.name, s.source_root, s.created_at, s.recordhash, " +
                "(SELECT count(*) FROM series WHERE serial_id = s.id) AS series_count " +
                "FROM serial s"
    }
}
