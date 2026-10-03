package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table

/**
 * Вектор признаков лица.
 *
 * Вектор лежит **отдельной таблицей**, а не столбцом лица: строка лица
 * читается на каждом показе лица в интерфейсе, и вектор на несколько сотен
 * чисел в каждой такой строке утяжелял бы её впустую (Р-09).
 *
 * Ключ модели эмбеддингов обязателен и не пуст: векторы, полученные разными
 * моделями, **несравнимы** между собой. Смешивание их в одной таблице без
 * ключа дало бы кластеры, в которых «похожие» лица объединены по числам,
 * которые ничего не значат друг об друге, и ошибку было бы не найти — числа
 * выглядят одинаково.
 *
 * @property faceId лицо-владелец, одновременно первичный ключ: у лица вектор
 *   один, а смена модели перезаписывает его целиком
 * @property modelKey ключ модели эмбеддингов, которой получен вектор
 * @property vector вектор признаков
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FaceEmbedding(
    val faceId: Long,
    val modelKey: String,
    val vector: FloatArray,
) {
    init {
        require(modelKey.isNotBlank()) {
            "Ключ модели эмбеддингов обязателен: векторы разных моделей несравнимы"
        }
        require(vector.isNotEmpty()) {
            "Пустой вектор признаков — не вектор: база его тоже не принимает"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами эмбеддинга
     */
    fun toTable(): Table =
        Table(
            FaceEmbeddingStore.TABLE,
            FaceEmbeddingStore.COLUMNS,
            { listOf(faceId, modelKey, vector) },
            null,
        )

    /** Равенство по значениям: массивы в Kotlin сравниваются по ссылке. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is FaceEmbedding &&
                    faceId == other.faceId &&
                    modelKey == other.modelKey &&
                    vector.contentEquals(other.vector)
            )

    /** Хеш по значениям, согласованный с [equals]. */
    override fun hashCode(): Int = (faceId.hashCode() * 31 + modelKey.hashCode()) * 31 + vector.contentHashCode()

    /** Текстовое представление без содержимого вектора. */
    override fun toString(): String = "FaceEmbedding(faceId=$faceId, modelKey=$modelKey, размер=${vector.size})"
}

/**
 * Хранилище эмбеддингов лиц.
 *
 * Класс держит одно правило, которое иначе выполнялось бы «по памяти»
 * вызывающих: **читаются только векторы одной модели**. Метод чтения требует
 * ключ модели, а не возвращает «какие-то векторы»: смешанная выборка разных
 * моделей выглядела бы как кластеризация и давала бы неверные кластеры без
 * единой ошибки.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FaceEmbeddingStore(
    private val db: Db,
) {
    /**
     * Записывает вектор лица.
     *
     * Запись идёт по различию значений (constitution III): вектор, совпавший
     * с уже сохранённым, повторно не пишется. Ключ модели входит в хеш, поэтому
     * переход на другую модель всегда даёт запись — векторы разных моделей не
     * путаются между собой и не смешиваются в одной строке (Р-09).
     *
     * @param embedding вектор с лицом и ключом модели
     * @return `true`, если строка записана или обновлена
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun save(embedding: FaceEmbedding): Boolean = db.useTransaction { connection -> saveInConnection(connection, embedding) }

    /**
     * Записывает вектор лица в уже открытой транзакции.
     *
     * @param connection открытое соединение
     * @param embedding вектор с лицом и ключом модели
     * @return `true`, если строка записана или обновлена
     */
    fun saveInConnection(
        connection: java.sql.Connection,
        embedding: FaceEmbedding,
    ): Boolean = Save.saveIfChanged(connection, embedding.toTable(), listOf("face_id"), listOf(embedding.faceId))

    /**
     * Записывает векторы пачкой в уже открытой транзакции.
     *
     * @param connection открытое соединение
     * @param embeddings векторы к записи
     * @return сколько строк записано или обновлено
     */
    fun saveAll(
        connection: java.sql.Connection,
        embeddings: List<FaceEmbedding>,
    ): Int = embeddings.count { saveInConnection(connection, it) }

    /**
     * Читает векторы лиц эпизода **один модели**.
     *
     * @param videofileId эпизод
     * @param modelKey ключ модели эмбеддингов
     * @return векторы в порядке идентификаторов лиц
     * @throws IllegalArgumentException если ключ модели пуст
     */
    fun listByVideofile(
        videofileId: Long,
        modelKey: String,
    ): List<FaceEmbedding> {
        require(modelKey.isNotBlank()) {
            "Ключ модели эмбеддингов обязателен: без него чтение вернуло бы смесь векторов"
        }
        return db.select(
            "SELECT e.face_id, e.embedding_model_key, e.vector " +
                "FROM ${FaceEmbeddingStore.TABLE} e " +
                "JOIN ${FaceStore.TABLE} f ON f.id = e.face_id " +
                "WHERE f.id_videofile = ? AND e.embedding_model_key = ? ORDER BY e.face_id",
            ::readRow,
            videofileId,
            modelKey,
        )
    }

    /**
     * Читает вектор лица заданной модели.
     *
     * @param faceId идентификатор лица
     * @param modelKey ключ модели эмбеддингов
     * @return вектор или `null`, если его нет
     */
    fun find(
        faceId: Long,
        modelKey: String,
    ): FaceEmbedding? =
        db.selectOne(
            "SELECT face_id, embedding_model_key, vector FROM ${FaceEmbeddingStore.TABLE} " +
                "WHERE face_id = ? AND embedding_model_key = ?",
            ::readRow,
            faceId,
            modelKey,
        )

    /**
     * Считает векторы эпизода заданный модели.
     *
     * @param videofileId эпизод
     * @param modelKey ключ модели эмбеддингов
     * @return число векторов
     */
    fun countByVideofile(
        videofileId: Long,
        modelKey: String,
    ): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM ${FaceEmbeddingStore.TABLE} e " +
                "JOIN ${FaceStore.TABLE} f ON f.id = e.face_id " +
                "WHERE f.id_videofile = ? AND e.embedding_model_key = ?",
            { it.int("total") },
            videofileId,
            modelKey,
        ) ?: 0

    /**
     * Строит эмбеддинг из типизированной строки выборки.
     *
     * @param row строка выборки
     * @return эмбеддинг
     */
    private fun readRow(row: Row): FaceEmbedding =
        FaceEmbedding(
            faceId = row.long("face_id"),
            modelKey = row.string("embedding_model_key"),
            vector = row.floatArray("vector"),
        )

    companion object {
        /** Имя таблицы эмбеддингов. */
        const val TABLE: String = "tbl_face_embeddings"

        /** Записываемые столбцы эмбеддинга в порядке значений. */
        val COLUMNS: List<String> = listOf("face_id", "embedding_model_key", "vector")
    }
}
