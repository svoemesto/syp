package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.db.Table
import java.time.OffsetDateTime

/**
 * Сезон сериала.
 *
 * Произведение устроено в три уровня: сериал состоит из сезонов, сезон — из
 * эпизодов. У художественного фильма ни сезонов, ни эпизодов нет, и его
 * обозначение — `S00E00`: ноль означает «сезона нет», а не «забыли внести».
 *
 * @property id идентификатор сезона
 * @property movieId сериал-владелец
 * @property ordinal номер сезона, начиная с единицы
 * @property name название сезона, например «Первый сезон»
 * @property createdAt дата создания
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Season(
    val id: Long? = null,
    val movieId: Long,
    val ordinal: Int,
    val name: String,
    val createdAt: OffsetDateTime,
) {
    /**
     * Обозначение сезона в виде `S02`.
     *
     * @return строка вида `S02`
     */
    fun designation(): String = "S%02d".format(ordinal)

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами сезона
     */
    fun toTable(): Table = Table(NAME, COLUMNS, { listOf(movieId, ordinal, name, createdAt) })

    companion object {
        /** Имя таблицы сезонов. */
        const val NAME: String = "tbl_seasons"

        /** Записываемые столбцы сезона в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_movie",
                "ordinal",
                "name",
                "created_at",
            )

        /** Столбцы сезона в порядке чтения из базы. */
        val READ_COLUMNS: String = "id, id_movie, ordinal, name, created_at, recordhash"
    }
}
