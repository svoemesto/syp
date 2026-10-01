package ru.svoemesto.syp.core.db

import java.security.MessageDigest

/**
 * Описание таблицы для сохранения по различию значений.
 *
 * Образец — `KaraokeDbTable` старого проекта: сущность переносится в
 * таблицу по списку столбцов, сохранение считает хеш значений и пишет строку
 * только при реальном изменении (constitution III). Отображение объектов
 * запрещено, поэтому перенос значений сделан явно: [values] возвращает
 * список в том же порядке, что и [columns].
 *
 * Таблица объявляется один раз на доменную сущность. Столбец `recordhash`
 * добавляется последним и служебным: он не входит в [columns] и заполняется
 * механизмом сохранения сам.
 *
 * @property name имя таблицы в базе
 * @property columns имена столбцов в порядке значений [values]
 * @property casts приведение типа в SQL для столбцов, значение которых
 *   передаётся строкой, а драйвер сам привести не может: `jsonb` и `json`
 *   отклоняют строку без явного `::jsonb`
 * @property values значения столбцов текущего состояния строки
 * @property recordHash хеш значений, прочитанный при загрузке, либо `null`,
 *   если строка не загружалась из базы
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class Table(
    val name: String,
    val columns: List<String>,
    private val valuesProvider: () -> List<Any?>,
    val recordHash: String? = null,
    private val casts: Map<String, String> = emptyMap(),
) {
    /** Значения столбцов текущего состояния строки. */
    val values: List<Any?>
        get() = valuesProvider()

    /**
     * Вычисляет хеш значений строки в каноническом виде.
     *
     * Канонический вид задаётся явно: значения соединяются разделителем,
     * недоступные значения заменяются пустой строкой, порядок столбцов
     * фиксирован объявлением таблицы. Из-за этого хеш воспроизводим — без
     * этого сравнение «изменилось ли» превратилось бы в лотерею.
     *
     * @return SHA-256 значений строки в виде 64 шестнадцатеричных символов
     */
    fun computeRecordHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val piece =
                when (value) {
                    null -> ""
                    is ByteArray -> value.joinToString("") { byte -> "%02x".format(byte) }
                    else -> value.toString()
                }
            digest.update(piece.toByteArray(Charsets.UTF_8))
            // Разделитель не даёт «ab» + «c» и «a» + «bc» дать один хеш.
            digest.update(0x1F)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Текст запроса вставки строки.
     *
     * @param withRecordHash добавлять ли служебный столбец `recordhash`
     * @return текст SQL с параметрами `?`
     */
    fun insertSql(withRecordHash: Boolean): String {
        val all = if (withRecordHash) columns + RECORD_HASH_COLUMN else columns
        val marks = all.joinToString(", ") { placeholder(it) }
        return "INSERT INTO $name (${all.joinToString(", ")}) VALUES ($marks)"
    }

    /**
     * Текст запроса обновления строки по первичному ключу.
     *
     * @param primaryKeyColumns столбцы первичного ключа, участвующие в условии
     * @param withRecordHash добавлять ли служебный столбец `recordhash`
     * @return текст SQL с параметрами `?`: сначала значения, потом ключ
     */
    fun updateSql(
        primaryKeyColumns: List<String>,
        withRecordHash: Boolean,
    ): String {
        val assigned = if (withRecordHash) columns + RECORD_HASH_COLUMN else columns
        val assignment = assigned.joinToString(", ") { "$it = ${placeholder(it)}" }
        val condition = primaryKeyColumns.joinToString(" AND ") { "$it = ?" }
        return "UPDATE $name SET $assignment WHERE $condition"
    }

    /** Текст запроса выборки одной строки по первичному ключу. */
    fun selectSql(primaryKeyColumns: List<String>): String {
        val condition = primaryKeyColumns.joinToString(" AND ") { "$it = ?" }
        return "SELECT ${columns.joinToString(", ")} FROM $name WHERE $condition"
    }

    /** Текст запроса выборки строки со служебным столбцом. */
    fun selectWithHashSql(primaryKeyColumns: List<String>): String {
        val condition = primaryKeyColumns.joinToString(" AND ") { "$it = ?" }
        return "SELECT ${columns.joinToString(", ")}, $RECORD_HASH_COLUMN " +
            "FROM $name WHERE $condition"
    }

    /**
     * Значение-заглушка столбца с учётом приведения типа.
     *
     * Столбец `jsonb` не принимает строку без явного приведения: драйвер
     * отправляет её как `character varying`, и база отвечает отказом. Поэтому
     * для таких столбцов заглушка дополняется `::jsonb`; остальные остаются
     * как есть, и приведение для них не требуется.
     *
     * @param column имя столбца
     * @return текст заглушки значения
     */
    private fun placeholder(column: String): String = casts[column]?.let { "?::$it" } ?: "?"

    companion object {
        /** Имя служебного столбца с хешем значений строки. */
        const val RECORD_HASH_COLUMN: String = "recordhash"
    }
}
