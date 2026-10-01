package ru.svoemesto.syp.core.storage

import java.io.InputStream
import java.nio.file.Path

/**
 * Объектное хранилище артефактов.
 *
 * На SSD хранятся листы превью, обученные модели и канонические байты
 * сценариев (FR-021, решение Д-8). Готовых видеофайлов на сервере нет:
 * они остаются на машине пользователя (ADR-0009), поэтому размещения `HDD`
 * в перечислении нет — и добавлять его нельзя без нового решения владельца.
 *
 * Интерфейс намеренно узкий: четыре операции, нужные для атомарной записи
 * артефакта (контракт очереди § 3.2) и чтения готового. Всё остальное —
 * деталь реализации, за которой стоит конкретное хранилище.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
interface ObjectStorage {
    /**
     * Пишет объект по ключу и перезаписывает его, если он уже есть.
     *
     * @param key ключ объекта
     * @param stream содержимое объекта; вызывающий закрывает поток
     * @param contentType тип содержимого
     * @param size размер в байтах, если известен заранее
     * @throws StorageException если запись не удалась
     */
    fun put(
        key: String,
        stream: InputStream,
        contentType: String,
        size: Long? = null,
    )

    /**
     * Переносит объект с одного ключа на другой **без перезаписи содержимого**.
     *
     * Операция атомарна с точки зрения вызывающего: после переноса исходный
     * ключ не существует, а конечный — полностью записан. Именно на этом
     * строится правило «незавершённый файл не считается готовым»: артефакт
     * переносится на окончательный ключ только после нулевого кода завершения.
     *
     * @param temporaryKey временный ключ
     * @param finalKey окончательный ключ
     * @throws StorageException если перенос не удался
     */
    fun move(
        temporaryKey: String,
        finalKey: String,
    )

    /**
     * Открывает объект на чтение.
     *
     * @param key ключ объекта
     * @return поток содержимого; вызывающий закрывает поток
     * @throws StorageException если объекта нет или он не читается
     */
    fun get(key: String): InputStream

    /**
     * Удаляет объект.
     *
     * Отсутствие объекта ошибкой не считается: удаление временного ключа
     * выполняется в том числе при сбое, когда объекта может не быть.
     *
     * @param key ключ объекта
     * @throws StorageException если удаление не удалось
     */
    fun delete(key: String)

    /**
     * Проверяет, существует ли объект.
     *
     * @param key ключ объекта
     * @return `true`, если объект есть
     * @throws StorageException если проверка не удалась
     */
    fun exists(key: String): Boolean

    /** Закрывает ресурсы хранилища. */
    fun close()
}

/**
 * Ошибка объектного хранилища.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class StorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Каталог файлов на диске: реализация хранилища для локальных каталогов.
 *
 * Нужна там, где артефакты лежат на диске, а не в объекте: каталог листов
 * превью на SSD. Ключ преобразуется в путь относительно корня, причём
 * выход за пределы корня запрещён — ключ из данных, а данные приходят из
 * сети.
 *
 * @property root корневой каталог хранилища
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FileSystemStorage(
    private val root: Path,
) : ObjectStorage {
    /** Разрешает ключ в путь внутри корня. */
    private fun resolve(key: String): Path {
        require(key.isNotBlank()) { "Пустой ключ объекта" }
        val normalized = key.trimStart('/')
        require(!normalized.split('/').any { it == ".." }) {
            "Ключ «$key» выходит за пределы корня хранилища"
        }
        return root.resolve(normalized)
    }

    override fun put(
        key: String,
        stream: InputStream,
        contentType: String,
        size: Long?,
    ) {
        val target = resolve(key)
        runCatching {
            target.parent?.let {
                java.nio.file.Files
                    .createDirectories(it)
            }
            stream.use { input ->
                java.nio.file.Files
                    .copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { throw StorageException("Не удалось записать объект «$key»: ${it.message}", it) }
    }

    override fun move(
        temporaryKey: String,
        finalKey: String,
    ) {
        val source = resolve(temporaryKey)
        val target = resolve(finalKey)
        runCatching {
            target.parent?.let {
                java.nio.file.Files
                    .createDirectories(it)
            }
            java.nio.file.Files.move(
                source,
                target,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }.onFailure { throw StorageException("Не удалось перенести «$temporaryKey» в «$finalKey»: ${it.message}", it) }
    }

    override fun get(key: String): InputStream {
        val target = resolve(key)
        return runCatching {
            java.nio.file.Files
                .newInputStream(target)
        }.getOrElse { throw StorageException("Не удалось прочитать объект «$key»: ${it.message}", it) }
    }

    override fun delete(key: String) {
        val target = resolve(key)
        runCatching {
            java.nio.file.Files
                .deleteIfExists(target)
        }.onFailure { throw StorageException("Не удалось удалить объект «$key»: ${it.message}", it) }
    }

    override fun exists(key: String): Boolean =
        java.nio.file.Files
            .exists(resolve(key))

    override fun close() = Unit
}
