package ru.svoemesto.syp.admin.properties

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import ru.svoemesto.syp.core.db.Db

/**
 * Произвольные свойства: запись, чтение, замена и удаление.
 *
 * Проверка идёт по настоящей базе, а не по подставной: свойство — это связь
 * между таблицами, и подставное хранилище проверило бы только то, что само с
 * собой согласно.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@TestInstance(PER_CLASS)
class PropertyStoreTest {
    private lateinit var db: Db
    private lateinit var store: PropertyStore
    private var sceneId: Long = 0
    private var otherId: Long = 0

    /**
     * Берёт живую сцену, чтобы виды владельца и защита от несуществующего
     * проверялись на настоящих данных.
     */
    @BeforeAll
    fun prepare() {
        db = TestDb.assume()
        store = PropertyStore(db)
        // Владельцы заводятся здесь: тестовая база пуста, и брать из неё
        // сущности анализа нечего.
        sceneId = TestDb.createProject(db, "Свойства ${System.nanoTime()}")
        otherId = TestDb.createProject(db, "Свойства другие ${System.nanoTime()}")
    }

    /**
     * Свойство пишется и читается обратно тем же ключом и значением.
     */
    @Test
    fun `свойство пишется и читается`() {
        store.put(PropertyOwnerKind.PROJECT, sceneId, "локация", "Винтерфелл")
        val found = store.list(PropertyOwnerKind.PROJECT, sceneId)
        val property = found.first { it.key == "локация" }
        assertEquals("Винтерфелл", property.value, "значение обязано вернуться тем же")
    }

    /**
     * Повтор с тем же ключом заменяет значение, а не плодит вторую запись.
     *
     * Оператор вводит ключ заново при опечатке в значении, и две строки с одним
     * ключом — это ровно тот мусор, который потом нельзя ни показать, ни
     * разобрать.
     */
    @Test
    fun `повтор с тем же ключом заменяет значение`() {
        store.put(PropertyOwnerKind.PROJECT, sceneId, "локация", "Винтерфелл")
        store.put(PropertyOwnerKind.PROJECT, sceneId, "локация", "Белое укрытие")
        val matching = store.list(PropertyOwnerKind.PROJECT, sceneId).filter { it.key == "локация" }
        assertEquals(1, matching.size, "записей с одним ключом обязана остаться одна")
        assertEquals("Белое укрытие", matching.single().value)
    }

    /**
     * Свойства разных владельцев не смешиваются.
     */
    @Test
    fun `свойства разных владельцев не смешиваются`() {
        store.put(PropertyOwnerKind.PROJECT, sceneId, "только-сцена", "да")
        store.put(PropertyOwnerKind.PROJECT, otherId, "только-другая", "нет")
        val mine = store.list(PropertyOwnerKind.PROJECT, sceneId).map { it.key }
        val theirs = store.list(PropertyOwnerKind.PROJECT, otherId).map { it.key }
        assertTrue("только-сцена" in mine, mine.toString())
        assertTrue("только-сцена" !in theirs, theirs.toString())
    }

    /**
     * Удаление уносит именно это свойство, остальные не трогает.
     */
    @Test
    fun `удаление уносит только это свойство`() {
        store.put(PropertyOwnerKind.PROJECT, sceneId, "удалимое", "x")
        store.put(PropertyOwnerKind.PROJECT, sceneId, "остающееся", "y")
        val removed = store.delete(PropertyOwnerKind.PROJECT, sceneId, "удалимое")
        val keys = store.list(PropertyOwnerKind.PROJECT, sceneId).map { it.key }
        assertEquals(1, removed, "удаление должно снять ровно одну запись")
        assertTrue("удалимое" !in keys, keys.toString())
        assertTrue("остающееся" in keys, keys.toString())
    }

    /**
     * Несуществующий владелец не принимается: свойство не должно висеть в
     * пустоте. Отказ приходит из базы, а не из приложения, — иначе запись,
     * сделанная мимо адреса, прошла бы.
     */
    @Test
    fun `несуществующий владелец не принимается`() {
        val failure =
            runCatching {
                store.put(PropertyOwnerKind.PROJECT, 9_999_999L, "висяк", "x")
            }.exceptionOrNull()
        assertTrue(failure != null, "свойство на несуществующем проекте принято — привязка не настоящая")
        assertTrue(
            failure?.message.orEmpty().contains("9999999"),
            "отказ обязан называть, на ком свойство повисло. Получено: ${failure?.message}",
        )
    }

    /**
     * Вид владельца разбирается и отвергается внятно.
     */
    @Test
    fun `неизвестный вид владельца отвергается`() {
        val failure =
            runCatching { PropertyOwnerKind.of("ЧЕЛОВЕК") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException, "вид «ЧЕЛОВЕК» принят как есть")
        assertTrue(
            failure?.message.orEmpty().contains("SCENE"),
            "отказ обязан перечислить допустимые виды. Получено: ${failure?.message}",
        )
    }
}
