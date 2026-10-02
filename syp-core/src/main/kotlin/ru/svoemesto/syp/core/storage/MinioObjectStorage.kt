package ru.svoemesto.syp.core.storage

import io.minio.CopyObjectArgs
import io.minio.CopySource
import io.minio.GetObjectArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import java.io.InputStream

/**
 * Объектное хранилище поверх MinIO.
 *
 * До появления этой реализации бин хранилища всегда создавал файловую
 * реализацию, а каталог жил внутри контейнера бэкенда. Контейнер
 * пересоздаётся при каждой смене образа, и вместе с ним исчезали листы
 * превью, кадры и канонические байты сценариев: в базе строки оставались, а
 * файлов за ними не было. На стенде это выглядело как «лист превью не
 * найден», хотя триста сорок семь листов были посчитаны в базе.
 *
 * Клиент MinIO уже был в зависимостях, но не использовался нигде — хранилище
 * было поднято и пустовало.
 *
 * @property client подключённый клиент MinIO
 * @property bucket корзина, в которой лежат объекты
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class MinioObjectStorage(
    private val client: MinioClient,
    private val bucket: String,
) : ObjectStorage {
    /**
     * Пишет объект по ключу, перезаписывая прежний.
     *
     * @param key ключ объекта
     * @param stream содержимое; вызывающий закрывает поток
     * @param contentType тип содержимого
     * @param size размер, если известен заранее: при известном размере клиент
     *   не обязан читать поток целиком в память
     * @throws StorageException если запись не удалась
     */
    override fun put(
        key: String,
        stream: InputStream,
        contentType: String,
        size: Long?,
    ) {
        // Размер части отдан клиенту: у него есть ограничение снизу в 5 МиБ,
        // и подставлять размер объекта вместо части нельзя — на маленьких
        // объектах запись падала с IllegalArgumentException.
        val known = size ?: -1L
        try {
            client.putObject(
                PutObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .stream(stream, known, -1L)
                    .contentType(contentType)
                    .build(),
            )
        } catch (failure: Exception) {
            throw StorageException("Не удалось записать объект «$key» в корзину «$bucket»", failure)
        }
    }

    /**
     * Переносит объект на постоянный ключ.
     *
     * Перенос сделан копированием с последующим удалением временного ключа:
     * именно этот порядок и требуется контрактом очереди, где артефакт
     * появляется под постоянным именем только целиком.
     *
     * @param temporaryKey ключ временного объекта
     * @param finalKey ключ постоянного объекта
     * @throws StorageException если перенос не удался
     */
    override fun move(
        temporaryKey: String,
        finalKey: String,
    ) {
        try {
            client.copyObject(
                CopyObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(finalKey)
                    .source(
                        CopySource
                            .builder()
                            .bucket(bucket)
                            .`object`(temporaryKey)
                            .build(),
                    ).build(),
            )
            client.removeObject(
                RemoveObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(temporaryKey)
                    .build(),
            )
        } catch (failure: Exception) {
            throw StorageException("Не удалось перенести объект «$temporaryKey» в «$finalKey»", failure)
        }
    }

    /**
     * Открывает объект на чтение.
     *
     * @param key ключ объекта
     * @return поток содержимого; вызывающий закрывает
     * @throws StorageException если объекта нет или чтение не удалось
     */
    override fun get(key: String): InputStream =
        try {
            client.getObject(
                GetObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            )
        } catch (failure: Exception) {
            throw StorageException("Не удалось прочитать объект «$key» из корзины «$bucket»", failure)
        }

    /**
     * Удаляет объект, если он есть.
     *
     * @param key ключ объекта
     * @throws StorageException если удаление не удалось
     */
    override fun delete(key: String) {
        try {
            client.removeObject(
                RemoveObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            )
        } catch (failure: Exception) {
            throw StorageException("Не удалось удалить объект «$key» из корзины «$bucket»", failure)
        }
    }

    /**
     * Есть ли объект по ключу.
     *
     * Отсутствие — не отказ: проверка обязана отвечать «нет» на пустое место, а
     * не бросать, иначе вызывающий приш бы различать два вида «нет».
     *
     * @param key ключ объекта
     * @return `true`, если объект есть
     */
    override fun exists(key: String): Boolean =
        try {
            client.statObject(
                StatObjectArgs
                    .builder()
                    .bucket(bucket)
                    .`object`(key)
                    .build(),
            )
            true
        } catch (missing: ErrorResponseException) {
            // «Нет объекта» и «нет корзины» — это ответ «нет», а отказ доступа,
            // неверные ключи и неисправность хранилища — это отказ. Раньше любой
            // отказ читался как «нет», и проверка наличия артефакта сообщала
            // «артефакта нет» там, где на самом деле не удалось спросить.
            when (missing.errorResponse().code()) {
                "NoSuchKey", "NoSuchObject", "NoSuchBucket" -> false
                else -> throw StorageException("Не удалось проверить объект «$key» в корзине «$bucket»", missing)
            }
        } catch (failure: Exception) {
            throw StorageException("Не удалось проверить объект «$key» в корзине «$bucket»", failure)
        }

    /** Закрывает клиент и освобождает его ресурсы. */
    override fun close() {
        runCatching { client.close() }
    }
}
