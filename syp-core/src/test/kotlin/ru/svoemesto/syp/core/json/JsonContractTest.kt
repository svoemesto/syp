package ru.svoemesto.syp.core.json

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Проверка единого разборщика JSON.
 *
 * Тест существует по истории, а не по обычному поводу. Разборщик в приложении и
 * разборщик в тестах были разными: в тестах не был зарегистрирован модуль
 * времени, поэтому ни один тест не замечал, что ответ с датой не пишется, и
 * приложение отдавало 500 на живом стенде. Теперь разборщик один, и этот тест
 * фиксирует, что даты действительно проходят.
 */
class JsonContractTest {
    /** Данные с датами, как их отдаёт очередь заданий. */
    private data class WithDates(
        val id: Long,
        val createdAt: OffsetDateTime,
        val moment: Instant,
    )

    @Test
    fun `даты записываются и читаются без потерь`() {
        val value =
            WithDates(
                id = 1,
                createdAt = OffsetDateTime.of(2026, 10, 3, 12, 30, 0, 0, ZoneOffset.UTC),
                moment = Instant.ofEpochMilli(1_795_000_000_000),
            )
        val json = Json.mapper().writeValueAsString(value)

        assertTrue(
            json.contains("2026-10-03"),
            "дата должна писаться в читаемом виде, а не числом: $json",
        )
        val back = Json.mapper().readValue(json, WithDates::class.java)
        assertEquals(value.id, back.id)
        assertEquals(value.createdAt, back.createdAt)
        assertEquals(value.moment, back.moment)
    }

    @Test
    fun `неизвестное поле не ломает чтение`() {
        val json =
            """{"id":7,"createdAt":"2026-10-03T12:30:00Z","moment":"2026-01-01T00:00:00Z","что-то-новое":1}"""
        val back = Json.mapper().readValue(json, WithDates::class.java)

        assertEquals(7, back.id)
    }

    @Test
    fun `объекты без необязательных полей читаются`() {
        val json = """{"id":3,"createdAt":"2026-10-03T12:30:00Z","moment":"2026-01-01T00:00:00Z"}"""
        val back = Json.mapper().readValue(json, WithDates::class.java)

        assertEquals(3, back.id)
    }
}
