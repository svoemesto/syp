package ru.svoemesto.syp.core.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Доступ к базе сырым JDBC.
 *
 * Constitution III запрещает отображение объектов: ни JPA, ни Hibernate, ни
 * автогенерации схемы. Модуль работает с `java.sql` напрямую, схема создаётся
 * и меняется только нумерованными добавочными миграциями
 * `deploy/syp-db/NN_*.sql` (PostgreSQL 16).
 *
 * Класс даёт три вещи, которых нет у «сырого» JDBC и без которых пишется
 * весь остальной код проекта:
 *
 * - **транзакцию по умолчанию**: [useTransaction] открывает соединение,
 *   начинает транзакцию, коммитит при успехе и откатывает при исключении —
 *   освобождать соединение вручную не нужно, а забытый откат невозможен;
 * - **выборку в типизированный список**: [select] и [selectOne] переводят
 *   строки в [Row] без отображения объектов;
 * - **закрытие ресурсов**: [Row] закрывает курсор сам.
 *
 * Пул соединений намеренно не используется: на первом срезе соединение
 * открывается на время операции, а не живёт весь процесс. Появление пула
 * требует отдельного решения с замерами под нагрузкой.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class Db(
    private val dataSource: DataSource,
) {
    /**
     * Создаёт доступ к базе по параметрам подключения.
     *
     * Пароль передаётся параметром и приходит из переменной окружения
     * (constitution VIII.5); в коде и в файлах репозитория его нет.
     *
     * @param url строка подключения JDBC
     * @param user пользователь базы
     * @param password пароль пользователя базы
     */
    constructor(url: String, user: String, password: String) :
        this(SimpleDataSource(url, user, password))

    /**
     * Выполняет блок в транзакции.
     *
     * Транзакция открывается перед вызовом блока и фиксируется после
     * успешного возврата. Исключение внутри блока откатывает транзакцию и
     * пробрасывается наружу: молча проглоченная ошибка записи здесь означала
     * бы «работа выполнена, а данных нет».
     *
     * @param T тип значения, возвращаемого блоком
     * @param block работа с соединением
     * @return значение, вычисленное блоком
     * @throws DbException если транзакция не удалась
     */
    fun <T> useTransaction(block: (Connection) -> T): T {
        dataSource.connection.use { connection ->
            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                return result
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw DbException("Транзакция не выполнена: ${failure.message}", failure)
            } finally {
                runCatching { connection.autoCommit = previousAutoCommit }
            }
        }
    }

    /**
     * Выполняет операцию обновления без явной транзакции.
     *
     * Метод предназначен для одиночных операций, где своя транзакция не
     * нужна. Для составных операций используется [useTransaction].
     *
     * @param block работа с соединением
     */
    fun <T> use(block: (Connection) -> T): T = dataSource.connection.use(block)

    /**
     * Выбирает строки запроса.
     *
     * @param T тип строки результата
     * @param sql текст запроса с параметрами `?`
     * @param mapping функция построения строки результата
     * @param parameters значения параметров запроса
     * @return список строк, пустой список при отсутствии результатов
     * @throws DbException если запрос не выполнился
     */
    fun <T> select(
        sql: String,
        mapping: (Row) -> T,
        vararg parameters: Any?,
    ): List<T> =
        use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement, parameters)
                statement.executeQuery().use { resultSet ->
                    val rows = mutableListOf<T>()
                    while (resultSet.next()) {
                        rows.add(mapping(Row(resultSet)))
                    }
                    rows
                }
            }
        }

    /**
     * Выбирает единственную строку.
     *
     * @param T тип строки результата
     * @param sql текст запроса с параметрами `?`
     * @param mapping функция построения строки результата
     * @param parameters значения параметров запроса
     * @return строка результата или `null`, если строк нет
     * @throws DbException если строк больше одной: это признак расхождения
     *   данных, а не повод молча взять первую
     */
    fun <T> selectOne(
        sql: String,
        mapping: (Row) -> T,
        vararg parameters: Any?,
    ): T? =
        select(sql, mapping, *parameters).let { rows ->
            when (rows.size) {
                0 -> null
                1 -> rows.first()
                else -> throw DbException(
                    "Ожидалась одна строка, получено ${rows.size}: выборка неоднозначна",
                )
            }
        }

    /**
     * Выполняет операцию изменения и возвращает число затронутых строк.
     *
     * @param sql текст запроса с параметрами `?`
     * @param parameters значения параметров запроса
     * @return число затронутых строк
     * @throws DbException если запрос не выполнился
     */
    fun update(
        sql: String,
        vararg parameters: Any?,
    ): Int =
        use { connection ->
            connection.prepareStatement(sql).use { statement ->
                bind(statement, parameters)
                statement.executeUpdate()
            }
        }

    /**
     * Подставляет значения параметров в подготовленный запрос.
     *
     * @param statement подготовленный запрос
     * @param parameters значения параметров
     */
    private fun bind(
        statement: PreparedStatement,
        parameters: Array<out Any?>,
    ) {
        parameters.forEachIndexed { index, value ->
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
    }
}

/**
 * Ошибка доступа к базе.
 *
 * Отдельный тип нужен, чтобы отличать «не удалось записать» от «бизнес-правило
 * запрещает»: первое — инфраструктурная ошибка, второе — ожидаемый отказ с
 * понятным текстом для оператора.
 *
 * @property cause исходная ошибка, если она была
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class DbException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Источник данных без пула соединений.
 *
 * Каждый вызов открывает новое соединение. Это осознанное упрощение первого
 * среза: пул требует замеров под нагрузкой и отдельного решения.
 *
 * @property url строка подключения JDBC
 * @property user пользователь базы
 * @property password пароль пользователя базы
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SimpleDataSource(
    private val url: String,
    private val user: String,
    private val password: String,
) : DataSource {
    override fun getConnection(): Connection = DriverManager.getConnection(url, user, password)

    override fun getConnection(
        username: String,
        password: String,
    ): Connection = DriverManager.getConnection(url, username, password)

    override fun getLogWriter(): java.io.PrintWriter? = null

    override fun setLogWriter(out: java.io.PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): java.util.logging.Logger =
        java.util.logging.Logger
            .getLogger("ru.svoemesto.syp.core.db")

    override fun <T : Any?> unwrap(iface: Class<T>?): T? = null

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

/**
 * Строка результата запроса.
 *
 * Обёртка над курсором, дающая типизированный доступ к значениям по имени
 * столбца и освобождающая курсор при закрытии. Отображения объектов здесь
 * нет: это чтение значения, а не материализация сущности (constitution III).
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class Row(
    private val resultSet: ResultSet,
) {
    /** Имена столбцов текущей строки. */
    val columns: List<String>
        get() =
            resultSet.metaData.let { meta ->
                (1..meta.columnCount).map { meta.getColumnLabel(it) }
            }

    /** Значение столбца по имени. */
    fun raw(name: String): Any? = resultSet.getObject(name)

    /** Строковое значение столбца. */
    fun string(name: String): String = resultSet.getString(name)

    /** Строковое значение столбца или `null`. */
    fun stringOrNull(name: String): String? = resultSet.getString(name)

    /** Целое значение столбца. */
    fun int(name: String): Int = resultSet.getInt(name)

    /** Целое значение столбца или `null`. */
    fun intOrNull(name: String): Int? = resultSet.getInt(name).let { if (resultSet.wasNull()) null else it }

    /** Большое целое значение столбца. */
    fun long(name: String): Long = resultSet.getLong(name)

    /** Большое целое значение столбца или `null`. */
    fun longOrNull(name: String): Long? = resultSet.getLong(name).let { if (resultSet.wasNull()) null else it }

    /** Значение с двойной точностью столбца. */
    fun doubleOrNull(name: String): Double? = resultSet.getDouble(name).let { if (resultSet.wasNull()) null else it }

    /** Логическое значение столбца. */
    fun booleanOrNull(name: String): Boolean? = resultSet.getBoolean(name).let { if (resultSet.wasNull()) null else it }

    /** Значение типа JSON или JSONB в виде строки. */
    fun json(name: String): String = resultSet.getString(name)

    /** Двоичное значение столбца. */
    fun bytesOrNull(name: String): ByteArray? = resultSet.getBytes(name)
}
