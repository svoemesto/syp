package ru.svoemesto.syp.core.db

import java.sql.Connection

/**
 * Сохранение по различию значений.
 *
 * Механизм диффированного сохранения образца `KaraokeDbTable`:
 *
 * 1. вычисляется хеш значений строки;
 * 2. если в базе уже есть строка с тем же хешем — запись не выполняется вовсе,
 *    это и есть «изменений нет»;
 * 3. иначе выполняется `INSERT` или `UPDATE` вместе с новым хешем.
 *
 * Правило «проверить регистрацию артефакта, а не наличие файла» (Р-10) этим
 * механизмом **не подменяется**: оно проверяет состояние `READY`, а не совпадение
 * хешей строки.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object Save {
    /**
     * Сохраняет строку, если её значения изменились.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param table описание таблицы
     * @param primaryKeyColumns столбцы первичного ключа
     * @param keyValues значения первичного ключа в том же порядке
     * @return `true`, если строка записана, `false`, если изменений не было
     * @throws DbException если запись не удалась
     */
    fun saveIfChanged(
        connection: Connection,
        table: Table,
        primaryKeyColumns: List<String>,
        keyValues: List<Any?>,
    ): Boolean {
        require(primaryKeyColumns.size == keyValues.size) {
            "Число значений первичного ключа (${keyValues.size}) не совпадает " +
                "с числом его столбцов (${primaryKeyColumns.size})"
        }

        val currentHash = table.computeRecordHash()
        if (currentHash == table.recordHash) {
            // Значения не менялись: запись не нужна.
            return false
        }

        val storedHash = readRecordHash(connection, table, primaryKeyColumns, keyValues)

        return if (storedHash == null) {
            insertRow(connection, table, currentHash)
        } else {
            updateRow(connection, table, primaryKeyColumns, keyValues, currentHash)
        }
    }

    /**
     * Вставляет строку, если её ещё нет в базе.
     *
     * Используется там, где повторная вставка той же строки — признак
     * расхождения, а не повод обновить.
     *
     * @param connection открытое соединение
     * @param table описание таблицы
     * @return `true`, если строка вставлена
     * @throws DbException если строка уже существует
     */
    fun insertIfAbsent(
        connection: Connection,
        table: Table,
    ): Boolean {
        connection.prepareStatement(table.insertSql(withRecordHash = true)).use { statement ->
            bindAll(statement, table.values, table.computeRecordHash())
            return statement.executeUpdate() > 0
        }
    }

    /**
     * Удаляет строку по первичному ключу.
     *
     * @param connection открытое соединение
     * @param table описание таблицы
     * @param primaryKeyColumns столбцы первичного ключа
     * @param keyValues значения первичного ключа
     * @return `true`, если строка была удалена
     * @throws DbException если удаление не удалось
     */
    fun delete(
        connection: Connection,
        table: Table,
        primaryKeyColumns: List<String>,
        keyValues: List<Any?>,
    ): Boolean {
        val condition = primaryKeyColumns.joinToString(" AND ") { "$it = ?" }
        connection.prepareStatement("DELETE FROM ${table.name} WHERE $condition").use { statement ->
            keyValues.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            return statement.executeUpdate() > 0
        }
    }

    /**
     * Читает сохранённый хеш строки.
     *
     * @return хеш или `null`, если строки нет либо хеш ещё не записывался
     */
    private fun readRecordHash(
        connection: Connection,
        table: Table,
        primaryKeyColumns: List<String>,
        keyValues: List<Any?>,
    ): String? {
        val condition = primaryKeyColumns.joinToString(" AND ") { "$it = ?" }
        val sql = "SELECT ${Table.RECORD_HASH_COLUMN} FROM ${table.name} WHERE $condition"
        return connection.prepareStatement(sql).use { statement ->
            keyValues.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { resultSet ->
                if (resultSet.next()) resultSet.getString(1) else null
            }
        }
    }

    /** Вставляет строку вместе с её хешем. */
    private fun insertRow(
        connection: Connection,
        table: Table,
        currentHash: String,
    ): Boolean {
        connection.prepareStatement(table.insertSql(withRecordHash = true)).use { statement ->
            bindAll(statement, table.values, currentHash)
            statement.executeUpdate()
        }
        return true
    }

    /** Обновляет строку и её хеш. */
    private fun updateRow(
        connection: Connection,
        table: Table,
        primaryKeyColumns: List<String>,
        keyValues: List<Any?>,
        currentHash: String,
    ): Boolean {
        connection
            .prepareStatement(
                table.updateSql(primaryKeyColumns, withRecordHash = true),
            ).use { statement ->
                bindAll(statement, table.values, currentHash)
                // В `UPDATE` за значениями строки идёт служебный хеш, а затем —
                // значения ключа из условия `WHERE`. Позиция ключа считается от
                // числа значений **и хеша**: без служебного хеша в счёте ключ
                // встаёт на его место, затирает его, а последний параметр
                // остаётся невыставленным, и база отвечает «не указано
                // значение для параметра». Ошибка проявляется только при
                // обновлении строки, то есть на первом же изменении значения.
                val keyOffset = table.values.size + 1
                keyValues.forEachIndexed { index, value ->
                    statement.setObject(keyOffset + index + 1, value)
                }
                statement.executeUpdate()
            }
        return true
    }

    /** Подставляет значения строки и её хеш в подготовленный запрос. */
    private fun bindAll(
        statement: java.sql.PreparedStatement,
        values: List<Any?>,
        currentHash: String,
    ) {
        values.forEachIndexed { index, value ->
            val position = index + 1
            when (value) {
                null -> statement.setObject(position, null)
                is Boolean -> statement.setBoolean(position, value)
                is Int -> statement.setInt(position, value)
                is Long -> statement.setLong(position, value)
                is Double -> statement.setDouble(position, value)
                is Float -> statement.setFloat(position, value)
                is ByteArray -> statement.setBytes(position, value)
                else -> statement.setObject(position, value)
            }
        }
        statement.setString(values.size + 1, currentHash)
    }
}
