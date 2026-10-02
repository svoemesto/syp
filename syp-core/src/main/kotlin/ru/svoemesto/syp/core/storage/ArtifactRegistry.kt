package ru.svoemesto.syp.core.storage

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.DbException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Регистрация артефактов заданий.
 *
 * Реестр даёт атомарность (FR-091): пока состояние не [ArtifactState.READY],
 * артефакт не считается готовым и не может быть использован ни сборкой, ни
 * показом, ни проверкой готовности серии.
 *
 * Порядок записи задан контрактом очереди § 3.2 и нарушать его нельзя:
 *
 * ```
 * WRITING ──(создан временный ключ)──► WRITING
 *    │
 *    ├─ нулевой код завершения + проверка результата ──► перенос на окончательный ключ ──► READY
 *    │
 *    └─ сбой ──► удаление временного объекта ──► FAILED
 * ```
 *
 * **READY невозможно получить без переноса**: метод [markReady] требует, чтобы
 * окончательный ключ уже существовал. Это и есть «незавершённый файл не
 * считается готовым».
 *
 * @property db доступ к базе сырым JDBC
 * @property storage объектное хранилище
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ArtifactRegistry(
    private val db: Db,
    private val storage: ObjectStorage,
) {
    /**
     * Открывает запись артефакта по временному ключу.
     *
     * Запись создаётся сразу в состоянии [ArtifactState.WRITING]: существующий
     * артефакт не выдаётся наружу, пока работа не закончена.
     *
     * @param jobId задание, которому принадлежит артефакт, или `null` для
     *   артефактов вне задания
     * @param kind вид артефакта
     * @param finalKey окончательный ключ объекта
     * @param contentType тип содержимого
     * @return запись созданного артефакта со временным ключом
     * @throws DbException если запись не создалась
     */
    fun begin(
        jobId: Long?,
        kind: ArtifactKind,
        finalKey: String,
        contentType: String,
    ): Artifact =
        db.useTransaction { connection ->
            val temporaryKey = temporaryKeyFor(finalKey)
            connection
                .prepareStatement(
                    """
                    INSERT INTO artifact (job_id, kind, placement, object_key, content_type, state)
                    VALUES (?, ?, 'SSD', ?, ?, 'WRITING')
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setString(2, kind.name)
                    statement.setString(3, finalKey)
                    statement.setString(4, contentType)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        Artifact(
                            id = resultSet.getLong(1),
                            jobId = jobId,
                            kind = kind,
                            objectKey = finalKey,
                            contentType = contentType,
                            byteSize = null,
                            checksum = null,
                            state = ArtifactState.WRITING,
                            temporaryKey = temporaryKey,
                        )
                    }
                }
        }

    /**
     * Открывает поток для записи по временному ключу.
     *
     * @param artifact запись артефакта, полученная из [begin]
     * @param stream содержимое; вызывающий закрывает поток
     * @throws StorageException если запись не удалась
     */
    fun writeTemporary(
        artifact: Artifact,
        stream: InputStream,
    ) {
        storage.put(artifact.temporaryKey, stream, artifact.contentType)
    }

    /**
     * Завершает артефакт: переносит временный объект на окончательный ключ и
     * переводит запись в состояние [ArtifactState.READY].
     *
     * Вызывается **только** после того, как все внешние программы задания
     * вернули нулевой код: перенос здесь и есть признак «работа выполнена».
     *
     * @param artifact запись артефакта
     * @param checksum SHA-256 содержимого: фиксирует входы результата (Р-10)
     * @param byteSize размер в байтах
     * @return обновлённая запись в состоянии [ArtifactState.READY]
     * @throws StorageException если временного объекта нет или перенос не удался
     * @throws DbException если запись не переведена в `READY`
     */
    fun markReady(
        artifact: Artifact,
        checksum: String,
        byteSize: Long,
    ): Artifact {
        require(checksum.matches(HEX_64)) {
            "Контрольная сумма «$checksum» не является SHA-256 в виде 64 " +
                "шестнадцатеричных символов в нижнем регистре"
        }
        require(byteSize >= 0) { "Размер артефакта отрицателен: $byteSize" }

        // Окончательного ключа до переноса не существует — это и есть
        // «незавершённый файл не считается готовым».
        if (!storage.exists(artifact.temporaryKey)) {
            throw StorageException(
                "Временный объект «${artifact.temporaryKey}» отсутствует: " +
                    "артефакт не может быть помечен готовым (FR-091)",
            )
        }
        storage.move(artifact.temporaryKey, artifact.objectKey)

        return db.useTransaction { connection ->
            val affected =
                connection
                    .prepareStatement(
                        """
                        UPDATE artifact
                           SET state = 'READY', checksum = ?, byte_size = ?
                         WHERE id = ? AND state = 'WRITING'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, checksum)
                        statement.setLong(2, byteSize)
                        statement.setLong(3, artifact.id)
                        statement.executeUpdate()
                    }
            if (affected == 0) {
                throw DbException(
                    "Артефакт ${artifact.id} уже не в состоянии WRITING: " +
                        "повторное завершение невозможно",
                )
            }
            artifact.copy(state = ArtifactState.READY, checksum = checksum, byteSize = byteSize)
        }
    }

    /**
     * Помечает артефакт неудачным и удаляет временный объект.
     *
     * @param artifact запись артефакта
     * @param reason причина сбоя: записывается в журнал задания, чтобы
     *   оператор понял, почему артефакта нет
     * @return обновлённая запись в состоянии [ArtifactState.FAILED]
     * @throws DbException если запись не найдена
     */
    fun markFailed(
        artifact: Artifact,
        reason: String,
    ): Artifact {
        // Временный объект удаляется всегда: незавершённый файл на SSD не
        // нужен никому, а ограничение «положительный размер» не даёт
        // зарегистрировать его как готовый.
        runCatching { storage.delete(artifact.temporaryKey) }
        db.useTransaction { connection ->
            connection
                .prepareStatement(
                    "UPDATE artifact SET state = 'FAILED' WHERE id = ?",
                ).use { statement ->
                    statement.setLong(1, artifact.id)
                    statement.executeUpdate()
                }
        }
        return artifact.copy(state = ArtifactState.FAILED)
    }

    /**
     * Открывает готовый артефакт на чтение.
     *
     * Чтение артефакта, который не в состоянии [ArtifactState.READY],
     * запрещено: незавершённый файл не считается готовым (FR-091).
     *
     * @param artifactId идентификатор артефакта
     * @return поток содержимого
     * @throws DbException если артефакта нет или он не готов
     * @throws StorageException если объект не читается
     */
    fun openReady(artifactId: Long): InputStream {
        val artifact =
            find(artifactId)
                ?: throw DbException("Артефакт $artifactId не найден")
        if (artifact.state != ArtifactState.READY) {
            throw DbException(
                "Артефакт $artifactId в состоянии ${artifact.state.name}: " +
                    "незавершённый файл не считается готовым (FR-091)",
            )
        }
        return storage.get(artifact.objectKey)
    }

    /**
     * Регистрирует артефакт, собранный **снаружи** этого класса.
     *
     * Нужен там, где объект уже записан и перенесён на окончательный ключ
     * собственным сборщиком — например, лист превью, который укладывает в
     * сетку внешняя программа. Запись создаётся сразу в состоянии
     * [ArtifactState.READY], но **только** после того, как существование
     * объекта по окончательному ключу проверено: незавершённый файл не
     * считается готовым, и здесь это правило не обходится (FR-091).
     *
     * @param jobId задание, которому принадлежит артефакт
     * @param kind вид артефакта
     * @param finalKey окончательный ключ объекта
     * @param contentType тип содержимого
     * @param byteSize размер объекта в байтах
     * @param checksum SHA-256 содержимого
     * @return запись созданного артефакта
     * @throws StorageException если объекта по окончательному ключу нет
     * @throws DbException если запись не создалась
     */
    fun registerBuilt(
        jobId: Long?,
        kind: ArtifactKind,
        finalKey: String,
        contentType: String,
        byteSize: Long,
        checksum: String,
    ): Artifact {
        require(checksum.matches(HEX_64)) {
            "Контрольная сумма «$checksum» не является SHA-256 в виде 64 " +
                "шестнадцатеричных символов в нижнем регистре"
        }
        require(byteSize >= 0) { "Размер артефакта отрицателен: $byteSize" }
        if (!storage.exists(finalKey)) {
            throw StorageException(
                "Объект «$finalKey» отсутствует: артефакт не может быть зарегистрирован " +
                    "как готовый (FR-091)",
            )
        }
        return db.useTransaction { connection ->
            connection
                .prepareStatement(
                    """
                    INSERT INTO artifact (job_id, kind, placement, object_key, content_type,
                                          byte_size, checksum, state)
                    VALUES (?, ?, 'SSD', ?, ?, ?, ?, 'READY')
                    ON CONFLICT (kind, object_key) DO UPDATE
                        SET job_id = EXCLUDED.job_id,
                            content_type = EXCLUDED.content_type,
                            byte_size = EXCLUDED.byte_size,
                            checksum = EXCLUDED.checksum,
                            state = 'READY'
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.setObject(1, jobId)
                    statement.setString(2, kind.name)
                    statement.setString(3, finalKey)
                    statement.setString(4, contentType)
                    statement.setLong(5, byteSize)
                    statement.setString(6, checksum)
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        Artifact(
                            id = resultSet.getLong(1),
                            jobId = jobId,
                            kind = kind,
                            objectKey = finalKey,
                            contentType = contentType,
                            byteSize = byteSize,
                            checksum = checksum,
                            state = ArtifactState.READY,
                            temporaryKey = temporaryKeyFor(finalKey),
                        )
                    }
                }
        }
    }

    /**
     * Читает запись артефакта.
     *
     * @param artifactId идентификатор артефакта
     * @return запись или `null`, если артефакта нет
     * @throws DbException если выборка не удалась
     */
    fun find(artifactId: Long): Artifact? =
        db.selectOne(
            "SELECT id, job_id, kind, object_key, content_type, byte_size, checksum, state " +
                "FROM artifact WHERE id = ?",
            ::readRow,
            artifactId,
        )

    /**
     * Ищет готовый артефакт нужного вида с указанным ключом.
     *
     * Именно эта проверка, а не «файл существует», определяет, можно ли
     * пропустить уже выполненную работу (Р-10, контракт очереди § 3.3).
     *
     * @param kind вид артефакта
     * @param objectKey ключ объекта
     * @return запись в состоянии `READY` либо `null`
     * @throws DbException если выборка не удалась
     */
    fun findReady(
        kind: ArtifactKind,
        objectKey: String,
    ): Artifact? =
        db.selectOne(
            "SELECT id, job_id, kind, object_key, content_type, byte_size, checksum, state " +
                "FROM artifact WHERE kind = ? AND object_key = ? AND state = 'READY'",
            ::readRow,
            kind.name,
            objectKey,
        )

    /**
     * Перечисляет артефакты задания с указанными состояниями.
     *
     * @param jobId задание
     * @param states состояния, которые нужно показать; пустой список — все
     * @return артефакты в порядке создания
     * @throws DbException если выборка не удалась
     */
    fun listForJob(
        jobId: Long,
        states: List<ArtifactState> = emptyList(),
    ): List<Artifact> {
        val condition =
            if (states.isEmpty()) {
                ""
            } else {
                " AND state IN (${states.joinToString(", ") { "?" }})"
            }
        return db.select(
            "SELECT id, job_id, kind, object_key, content_type, byte_size, checksum, state " +
                "FROM artifact WHERE job_id = ?$condition ORDER BY id",
            ::readRow,
            jobId,
            *states.map { it.name }.toTypedArray(),
        )
    }

    /** Строит временный ключ для артефакта по окончательному. */
    private fun temporaryKeyFor(finalKey: String): String = "tmp/$SESSION/$finalKey"

    /** Строит запись артефакта из строки выборки. */
    private fun readRow(row: ru.svoemesto.syp.core.db.Row): Artifact =
        Artifact(
            id = row.long("id"),
            jobId = row.longOrNull("job_id"),
            kind = ArtifactKind.parse(row.string("kind")),
            objectKey = row.string("object_key"),
            contentType = row.stringOrNull("content_type") ?: "",
            byteSize = row.longOrNull("byte_size"),
            checksum = row.stringOrNull("checksum"),
            state = ArtifactState.parse(row.string("state")),
            temporaryKey = temporaryKeyFor(row.string("object_key")),
        )

    private companion object {
        /** Признак SHA-256 в виде 64 шестнадцатеричных символов в нижнем регистре. */
        val HEX_64 = Regex("^[0-9a-f]{64}$")

        /**
         * Префикс временных ключей текущего запуска. Временный объект, не
         * перенесённый до конца работы, удаляется целиком: на следующем
         * запуске он не нужен и только занимает место на SSD.
         */
        val SESSION: String =
            run {
                val digest =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(
                            java.lang.management.ManagementFactory
                                .getRuntimeMXBean()
                                .name
                                .toByteArray(),
                        )
                digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
            }
    }
}

/**
 * Вид артефакта.
 *
 * Набор задан миграцией `05_jobs.sql` и моделью данных (раздел 2.18):
 * `PREVIEW_SHEET`, `MODEL`, `RECIPE`. Значений `ASSEMBLY` и `CUT_FRAGMENT`
 * нет — видеофайлы на сервере не хранятся (ADR-0009).
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class ArtifactKind {
    /** Лист превью кадров. */
    PREVIEW_SHEET,

    /** Обученная модель распознавания. */
    MODEL,

    /** Канонические байты сценария сборки. */
    RECIPE,
    ;

    companion object {
        /**
         * Разбирает вид артефакта из строки базы.
         *
         * @param value значение столбца `kind`
         * @return вид артефакта
         * @throws IllegalArgumentException если вида нет в наборе
         */
        fun parse(value: String): ArtifactKind =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "Неизвестный вид артефакта: «$value». Допустимы: ${entries.joinToString()}",
                )
    }
}

/**
 * Состояние артефакта.
 *
 * Ровно три состояния. `HDD` в перечислении размещения нет и появится только
 * новой миграцией и новым решением владельца.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class ArtifactState {
    /** Записывается по временному ключу; наружу не выдаётся. */
    WRITING,

    /** Готов: объект перенесён на окончательный ключ. */
    READY,

    /** Сбой: временный объект удалён. */
    FAILED,
    ;

    companion object {
        /**
         * Разбирает состояние артефакта из строки базы.
         *
         * @param value значение столбца `state`
         * @return состояние
         * @throws IllegalArgumentException если состояния нет в наборе
         */
        fun parse(value: String): ArtifactState =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException(
                    "Неизвестное состояние артефакта: «$value». " +
                        "Допустимы: ${entries.joinToString()}",
                )
    }
}

/**
 * Запись артефакта.
 *
 * @property id идентификатор записи
 * @property jobId задание-владелец или `null`
 * @property kind вид артефакта
 * @property objectKey окончательный ключ объекта
 * @property contentType тип содержимого
 * @property byteSize размер в байтах, `null` пока не готово
 * @property checksum SHA-256 содержимого, `null` пока не готово
 * @property state состояние артефакта
 * @property temporaryKey ключ, по которому объект пишется до переноса
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Artifact(
    val id: Long,
    val jobId: Long?,
    val kind: ArtifactKind,
    val objectKey: String,
    val contentType: String,
    val byteSize: Long?,
    val checksum: String?,
    val state: ArtifactState,
    val temporaryKey: String,
)
