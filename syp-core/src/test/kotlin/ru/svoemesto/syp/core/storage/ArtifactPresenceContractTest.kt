package ru.svoemesto.syp.core.storage

import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.core.jobs.TestDb
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Проверка того, что «артефакт готов» означает наличие объекта, а не только
 * запись в базе.
 *
 * На стенде задание отрапортовало «все 347 листов превью готовы ранее» и
 * ничего не переделало, хотя записи в базе были, а объектов не было:
 * артефакты лежали в каталоге внутри контейнера, контейнер пересоздали.
 * Проверка по записи выглядела правдоподобно и была неверна по сути.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArtifactPresenceContractTest {
    /** Каталог, в котором лежат объекты теста. */
    private lateinit var root: java.nio.file.Path

    /** Реестр артефактов поверх временного каталога. */
    private lateinit var artifacts: ArtifactRegistry

    /** Готовит базу и реестр перед набором. */
    @BeforeAll
    fun setUp() {
        TestDb.setUp()
        root = Files.createTempDirectory("syp-presence")
        artifacts = ArtifactRegistry(TestDb.assumeDatabase(), FileSystemStorage(root))
    }

    /**
     * Запись без объекта не считается готовой.
     *
     * @throws AssertionError если реестр ответил «готово» при отсутствующем файле
     */
    @Test
    fun `запись без объекта не считается готовой`() {
        val key = "presence/${System.nanoTime()}/sheet.jpg"
        val record = artifacts.begin(null, ArtifactKind.PREVIEW_SHEET, key, "image/jpeg")
        artifacts.writeTemporary(record, "байт".toByteArray().inputStream())
        artifacts.markReady(record, checksum = "a".repeat(64), byteSize = 4)

        // Сценарий стенда: артефакт был готов, хранилище сменили, объект
        // исчез, а запись в базе осталась. Именно так задание рапортовало
        // «все 347 листов готовы ранее».
        Files.delete(root.resolve(key))

        assertNull(
            artifacts.findReady(ArtifactKind.PREVIEW_SHEET, key),
            "объекта в хранилище нет, но реестр ответил «готово» — работа будет пропущена",
        )
    }

    /**
     * Запись с объектом считается готовой.
     *
     * @throws AssertionError если реестр не увидел существующий объект
     */
    @Test
    fun `запись с объектом считается готовой`() {
        val storage = FileSystemStorage(Files.createTempDirectory("syp-present-here"))
        val key = "presence/${System.nanoTime()}/sheet.jpg"
        val registry = ArtifactRegistry(TestDb.assumeDatabase(), storage)
        val record = registry.begin(null, ArtifactKind.PREVIEW_SHEET, key, "image/jpeg")
        registry.writeTemporary(record, "байт".toByteArray().inputStream())
        storage.put(key, "байт".toByteArray().inputStream(), "image/jpeg", size = 4)
        registry.markReady(record, checksum = "a".repeat(64), byteSize = 4)

        val found = registry.findReady(ArtifactKind.PREVIEW_SHEET, key)
        check(found != null) { "объект записан, но реестр его не увидел" }
    }
}
