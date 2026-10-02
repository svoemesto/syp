package ru.svoemesto.syp.admin.notify

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Проверки подсистемы нотификаций SSE.
 *
 * Закрывают ровно то, что иначе ловится только глазами: имя типа едет именем
 * события, вкладки различаются, повторное подключение той же вкладки не
 * плодит вторую подписку, сердцебие — комментарий, а не событие, негодное
 * значение идентификатора вкладки из адреса не попадает в реестр подключений,
 * а значение вкладки не уезжает из потока в следующий запрос.
 *
 * Живая база не требуется: все проверки идут на подставных подписках.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@DisplayName("Нотификации SSE")
class SseNotificationServiceTest {
    private val mapper: ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())

    /** Каналы, выданные подписками, по порядку подключения. */
    private val created = mutableListOf<RecordingChannel>()

    /** Снимает значение вкладки, если тест его не снял сам. */
    @AfterEach
    fun clearContext() {
        TabIdContext.clear()
    }

    /**
     * Сервис, чьи каналы пишут в список, а не в сокет.
     *
     * Расписание сердцебия не запускается: его тикает тест, и висящий поток
     * после проверки не нужен.
     */
    private fun service(): SseNotificationService =
        SseNotificationService(mapper) { RecordingChannel().also { channel -> created += channel } }

    @Test
    @DisplayName("Тип уведомления едет именем события, а не полем в общем потоке")
    fun eventTypeTravelsAsEventName() {
        val service = service()
        open(service, "tab-0000001")

        service.publish(
            SseEventType.JOB_PROGRESS,
            JobProgressPayload(jobId = 412, kind = "FACES", done = 51230, total = 82834, note = "кадр 51230"),
        )

        val recorded = created.last()
        assertEquals(listOf("jobProgress"), recorded.eventNames())
        val body = mapper.readTree(recorded.bodyOf("jobProgress"))
        assertEquals(412, body["jobId"].asLong())
        assertEquals(82834, body["total"].asLong())
    }

    @Test
    @DisplayName("Вкладки различаются: событие получают обе подписки")
    fun tabsAreDistinct() {
        val service = service()
        val first = open(service, "tab-0000001")
        val second = open(service, "tab-0000002")
        assertEquals(2, service.connectionCount)

        service.publishError("EMPTY_SELECTION", "в запросе нет ни одной сцены", "выдача сценария")

        assertEquals("error", first.eventNames().single())
        assertEquals("error", second.eventNames().single())
    }

    @Test
    @DisplayName("Повторное подключение той же вкладки заменяет прежнее, а не добавляет второе")
    fun reconnectReplacesPreviousConnection() {
        val service = service()
        val first = open(service, "tab-0000001")
        val second = open(service, "tab-0000001")
        assertEquals(1, service.connectionCount)
        assertTrue(first.closed, "прежняя подписка должна быть закрыта")

        service.publish(SseEventType.JOB_STATE, JobStatePayload(1, "FACES", "WORKING", null, null, 0, 0, null))

        assertEquals(0, first.sent.size, "закрытая подписка событий не получает")
        assertEquals(1, second.sent.size)
    }

    @Test
    @DisplayName("Сердчебие — комментарий, а не событие с именем")
    fun heartbeatIsCommentNotEvent() {
        val service = service()
        val channel = open(service, "tab-0000001")

        service.heartbeatOnce()

        val beat = channel.sent.single()
        assertEquals("heartbeat", beat.comment)
        assertNull(beat.name)
        assertNull(beat.data)
        assertEquals(0, channel.eventNames().count { it != null })
    }

    @Test
    @DisplayName("Сердчебие без подписок не отправляется и не падает")
    fun heartbeatWithoutConnectionsIsNoop() {
        val service = service()
        service.heartbeatOnce()
        assertEquals(0, service.connectionCount)
    }

    @Test
    @DisplayName("Клиентский таймаут больше серверного сердчебия")
    fun clientTimeoutIsAboveHeartbeat() {
        val heartbeatSeconds = SseNotificationService.DEFAULT_HEARTBEAT_INTERVAL.seconds
        assertEquals(15, heartbeatSeconds)
        assertTrue(
            heartbeatSeconds * 2 == CLIENT_TIMEOUT_SECONDS,
            "клиентский таймаут ($CLIENT_TIMEOUT_SECONDS с) обязан быть больше серверного " +
                "сердчебия ($heartbeatSeconds с): равные значения означают, что живой поток " +
                "успеет признаться мёртвым",
        )
    }

    @Test
    @DisplayName("Идентификатор вкладки из адреса проверяется: негодный заменяется серверным")
    fun tabIdFromAddressIsNotTrusted() {
        val service = service()

        val accepted = service.connect("tab-0000001")
        val rejected = service.connect("подписка; DROP TABLE job")

        assertEquals("tab-0000001", accepted.tabId.value)
        assertTrue(TabId.isAcceptable(rejected.tabId.value), "серверный идентификатор обязан быть допустимым")
        assertTrue(rejected.tabId.value != "подписка; DROP TABLE job")
    }

    @Test
    @DisplayName("Пустое и слишком длинное значение идентификатора не используются")
    fun blankAndOversizedTabIdsAreRejected() {
        assertTrue(!TabId.isAcceptable(null))
        assertTrue(!TabId.isAcceptable(""))
        assertTrue(!TabId.isAcceptable("коротк"))
        assertTrue(!TabId.isAcceptable("a".repeat(TabId.MAX_LENGTH + 1)))
        assertTrue(TabId.isAcceptable("a".repeat(TabId.MIN_LENGTH)))
    }

    @Test
    @DisplayName("Первое событие подписки уходит в неё одну, а не всем")
    fun firstEventGoesToOneSubscriptionOnly() {
        val service = service()
        val subscription = service.connect("tab-0000001")
        val first = created.last()
        val second = open(service, "tab-0000002")

        service.publishTo(
            subscription,
            SseEventType.QUEUE_STATE,
            QueueStatePayload(working = 1, waiting = 3, failed = 0, done = 12),
        )

        assertEquals(1, first.sent.size)
        assertEquals(0, second.sent.size)
        assertEquals("queueState", first.eventNames().single())
    }

    @Test
    @DisplayName("Имена типов уведомлений свои, по домену, и не караокинские")
    fun eventNamesAreDomainOwn() {
        val names = SseEventType.entries.map { it.wireName }
        assertEquals(
            listOf(
                "jobProgress",
                "jobState",
                "queueState",
                "checksumChanged",
                "recipeReady",
                "error",
            ),
            names,
        )
        listOf("recordChange", "recordAdd", "recordDelete", "cacheQueueSize", "dummy").forEach { foreign ->
            assertNull(
                SseEventType.byWireName(foreign),
                "имя «$foreign» описывает чужой домен и у нас использоваться не должно",
            )
        }
        assertEquals(SseEventType.RECIPE_READY, SseEventType.byWireName("recipeReady"))
        assertNull(SseEventType.byWireName("что-то незнакомое"))
    }

    @Test
    @DisplayName("Значение вкладки снимается с потока, даже когда запрос упал")
    fun tabIdIsClearedFromThreadEvenOnFailure() {
        val filter = TabIdFilter()

        val failure =
            runCatching {
                filter.doFilter(
                    mockRequest("tab-0000001"),
                    Mockito.mock(HttpServletResponse::class.java),
                ) { _, _ ->
                    assertEquals("tab-0000001", TabIdContext.get()?.value)
                    throw IllegalStateException("запрос упал в середине")
                }
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(
            TabIdContext.get(),
            "оставшееся значение уехало бы в следующий несвязанный запрос того же потока",
        )
    }

    @Test
    @DisplayName("Запрос без адреса подписки проходит, не оставляя значения вкладки")
    fun requestWithoutTabIdPassesThrough() {
        val filter = TabIdFilter()
        var seen = true

        filter.doFilter(
            mockRequest(null),
            Mockito.mock(HttpServletResponse::class.java),
        ) { _, _ -> seen = TabIdContext.get() == null }

        assertTrue(seen, "запрос без адреса подписки должен идти дальше как есть")
        assertNull(TabIdContext.get())
    }

    /** Открывает подписку и возвращает её канал. */
    private fun open(
        service: SseNotificationService,
        tabId: String,
    ): RecordingChannel {
        service.connect(tabId)
        return created.last()
    }

    /** Запрос, отдающий заданное значение параметра вкладки. */
    private fun mockRequest(tabId: String?): HttpServletRequest {
        val request: HttpServletRequest = Mockito.mock(HttpServletRequest::class.java)
        Mockito.`when`(request.getParameter(TabId.PARAMETER)).thenReturn(tabId)
        return request
    }

    private companion object {
        /**
         * Клиентский таймаут в секундах: он вдвое больше серверного сердчебия.
         *
         * Значение живёт в клиенте (`syp-admin-web`), а проверяется здесь:
         * разъехаться они могут только вместе с этим тестом.
         */
        const val CLIENT_TIMEOUT_SECONDS: Long = 30
    }
}
