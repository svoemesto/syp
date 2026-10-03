package ru.svoemesto.syp.core.storage

import io.minio.MinioClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Проверка того, что обе реализации хранилища ведут себя одинаково.
 *
 * Реализация поверх MinIO добавлена позже файловой, и разница между ними
 * обнаружилась не в коде, а на стенде: бин всегда создавал файловую
 * реализацию, каталог жил внутри контейнера, и артефакты исчезали при
 * каждом пересоздании. Пока контракт проверялся только на файловой
 * реализации, вторая оставалась непроверенной.
 *
 * Проверка идёт по контракту интерфейса, а не по деталям реализации: одни и
 * те же требования к обеим.
 */
class ObjectStorageContractTest {
    /**
     * Обе реализации проходят один и тот же контракт.
     *
     * @throws AssertionError если какая-то операция ведёт себя не так
     */
    @Test
    fun `обе реализации проходят один контракт`() {
        val directory = createTempDirectory("syp-storage")
        val storages = mutableListOf<ObjectStorage>(FileSystemStorage(directory))
        liveMinio()?.let { storages.add(it) }
        storages.forEach { storage ->
            val name = storage::class.simpleName.orEmpty()
            val key = "videofile/1/preview-sheets/v1/000001"

            assertFalse(storage.exists(key), "$name: отсутствующий объект должен читаться как «нет»")
            val payload = "байты".toByteArray()
            // Без размера: длина потока неизвестна заранее. Так пишет всё,
            // что не знает длину заранее, и этот случай падал с
            // «valid part size must be provided when object size is unknown».
            storage.put(key, payload.inputStream(), "image/png")
            assertTrue(storage.exists(key), "$name: записанный объект должен находиться")
            assertContentEquals(payload, storage.get(key).readAll(), "$name: прочитанное не равно записанному")

            val moved = "videofile/1/preview-sheets/v1/000002"
            storage.move(key, moved)
            assertFalse(storage.exists(key), "$name: временный ключ должен исчезнуть после переноса")
            assertContentEquals(payload, storage.get(moved).readAll(), "$name: после переноса содержимое не изменилось")

            storage.delete(moved)
            assertFalse(storage.exists(moved), "$name: удалённый объект не должен находиться")
            storage.close()
        }
    }

    /**
     * Настоящее хранилище поверх MinIO, если сервер поднят.
     *
     * Без сервера проверка молча пропускается: подставная реализация
     * проверяла бы только саму себя, а это ровно тот отказ, который
     * привёл к потере артефактов.
     *
     * @return хранилище либо `null`, если сервера нет
     */
    private fun liveMinio(): ObjectStorage? {
        val url = System.getenv("SYP_TEST_STORAGE_URL") ?: return null
        val access = System.getenv("SYP_TEST_STORAGE_ACCESS_KEY") ?: return null
        val secret = System.getenv("SYP_TEST_STORAGE_SECRET_KEY") ?: return null
        val bucket = System.getenv("SYP_TEST_STORAGE_BUCKET") ?: return null
        val client =
            MinioClient
                .builder()
                .endpoint(url)
                .credentials(access, secret)
                .build()
        val storage = MinioObjectStorage(client, bucket)
        client.makeBucket(
            io.minio.MakeBucketArgs
                .builder()
                .bucket(bucket)
                .build(),
        )
        return storage
    }

    /** Хранилище, повторяющее поведение MinIO без обращения к сети. */
    private class RecordingObjectStorage : ObjectStorage {
        private val objects = mutableMapOf<String, ByteArray>()

        override fun put(
            key: String,
            stream: InputStream,
            contentType: String,
            size: Long?,
        ) {
            objects[key] = stream.readBytes()
        }

        override fun move(
            temporaryKey: String,
            finalKey: String,
        ) {
            val bytes =
                objects.remove(temporaryKey)
                    ?: throw StorageException("нет временного объекта «$temporaryKey»", null)
            objects[finalKey] = bytes
        }

        override fun get(key: String): InputStream {
            val bytes =
                objects[key]
                    ?: throw StorageException("нет объекта «$key»", null)
            return ByteArrayInputStream(bytes)
        }

        override fun delete(key: String) {
            objects.remove(key)
        }

        override fun exists(key: String): Boolean = objects.containsKey(key)

        override fun close() {
            objects.clear()
        }
    }
}

/** Собирает поток в байты, чтобы сравнение не зависело от реализации. */
private fun InputStream.readAll(): ByteArray = ByteArrayOutputStream().also { copyTo(it) }.toByteArray()

/** Каталог для файловой реализации в этом тесте. */
private fun createTempDirectory(prefix: String): Path = kotlin.io.path.createTempDirectory(prefix)
