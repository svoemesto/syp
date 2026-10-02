package ru.svoemesto.syp.admin.notify

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Нотификации SSE для интерфейса SYP.
 *
 * Сервис держит по одному каналу на **подключение вкладки** и рассылает
 * события всем открытым подключениям. Ключ подключения — идентификатор
 * вкладки, и вкладки различаются намеренно: один браузер с двумя открытыми
 * экранами — это две подписки, и закрытие одной не должно гасить вторую.
 *
 * Четыре правила, из-за которых класс написан, а не сделан на двадцать строк:
 *
 * 1. **Имя типа едет как имя события.** Общий поток с полем «тип сообщения»
 *    означал бы, что обработчик разбирает это поле руками и что одно
 *    непрочитанное сообщение роняет чужой обработчик.
 * 2. **Сердцебие отправляется всегда, даже когда делать нечего.** Молчащее
 *    соединение выглядит для клиента живым, пока не отвалится по таймауту
 *    внутри сети, — и оператор всё это время смотрит на неподвижный экран.
 * 3. **Отправка не удалась — подписка снимается, а не повторяется.** Мёртвый
 *    канал в реестре — это утечка: и подписка, и память живут до перезапуска
 *    контейнера.
 * 4. **Подписка не роняет работу.** Ошибка отправки ловится, подписка
 *    снимается, и вызывающий код об этом не узнаёт: потерянное уведомление —
 *    это молчащий экран, а не неверные данные.
 *
 * @property mapper разбор значений в JSON
 * @property heartbeatInterval как часто отправляется сердцебие
 * @property channelFactory создаёт канал отправки; подставляется в проверке,
 *   где вместо сокета нужен список отправленного
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SseNotificationService(
    private val mapper: ObjectMapper,
    private val heartbeatInterval: Duration = DEFAULT_HEARTBEAT_INTERVAL,
    private val channelFactory: () -> NotificationChannel = { SseEmitterChannel() },
) {
    private val log = LoggerFactory.getLogger(SseNotificationService::class.java)

    /**
     * Открытые подключения по идентификатору вкладки.
     *
     * Ключ — идентификатор вкладки: повторное подключение той же вкладки
     * заменяет прежнее, а не добавляет второе, иначе одна вкладка получала бы
     * каждое событие дважды.
     */
    private val connections = ConcurrentHashMap<String, NotificationChannel>()

    private var heartbeat: ScheduledExecutorService? = null

    /**
     * Подписка вкладки.
     *
     * @property tabId идентификатор вкладки; он уже проверен или подставлен
     *   сервером
     * @property emitter поток подписки, который отдаётся в ответ HTTP
     * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
     */
    data class Subscription(
        val tabId: TabId,
        val emitter: SseEmitter,
    )

    /** Сколько подключений открыто сейчас. */
    val connectionCount: Int
        get() = connections.size

    /**
     * Запускает отправку сердцебия.
     *
     * Повторный запуск ничего не делает: два расписания на один сервис
     * отправляли бы вдвое больше пустых строк.
     */
    @Synchronized
    fun start() {
        if (heartbeat != null) {
            return
        }
        val scheduler =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "syp-sse-heartbeat").apply { isDaemon = true }
            }
        scheduler.scheduleWithFixedDelay(
            ::heartbeatOnce,
            heartbeatInterval.toMillis(),
            heartbeatInterval.toMillis(),
            TimeUnit.MILLISECONDS,
        )
        heartbeat = scheduler
    }

    /** Останавливает сердцебие и закрывает все подключения. */
    @Synchronized
    fun stop() {
        heartbeat?.shutdownNow()
        heartbeat = null
        connections.keys.toList().forEach { tabId -> close(tabId) }
    }

    /**
     * Открывает подписку вкладки.
     *
     * Первое событие подписки отправляет вызывающий: сервис перевозит поток и
     * ничего не знает о состоянии очереди.
     *
     * @param rawTabId значение из адреса; недоверенное и потому проверяемое
     * @return подписка, которую отдаёт контроллер в ответ
     */
    fun connect(rawTabId: String?): Subscription {
        val tabId = TabId.of(rawTabId)
        // Та же вкладка переподключилась: прежнее подключение закрывается, иначе
        // одна вкладка получала бы каждое событие дважды.
        close(tabId.value)
        val channel = channelFactory()
        connections[tabId.value] = channel
        channel.emitter.onCompletion { connections.remove(tabId.value) }
        channel.emitter.onTimeout {
            connections.remove(tabId.value)
            channel.close()
        }
        channel.emitter.onError { connections.remove(tabId.value) }
        return Subscription(tabId, channel.emitter)
    }

    /**
     * Отправляет событие всем открытым подпискам.
     *
     * @param type тип события; его имя едет именем события
     * @param payload тело события
     */
    fun publish(
        type: SseEventType,
        payload: Any,
    ) {
        val data = write(payload)
        connections.keys.toList().forEach { tabId -> deliver(tabId, type.wireName, data) }
    }

    /**
     * Отправляет событие в одну подписку.
     *
     * Так открывается подписка: первым событием уходит состояние очереди, и
     * подключившийся экран не ждёт первого изменения, чтобы понять, что
     * очередь пуста.
     *
     * @param subscription подписка вкладки
     * @param type тип события
     * @param payload тело события
     */
    fun publishTo(
        subscription: Subscription,
        type: SseEventType,
        payload: Any,
    ) {
        deliver(subscription.tabId.value, type.wireName, write(payload))
    }

    /**
     * Отправляет событие «ошибка».
     *
     * @param code машинный код отказа
     * @param message текст отказа на русском
     * @param operation что делал оператор
     */
    fun publishError(
        code: String,
        message: String,
        operation: String,
    ) {
        publish(SseEventType.ERROR, ErrorPayload(code, message, operation))
    }

    /**
     * Отправляет сердцебие всем открытым подпискам.
     *
     * Сердцебие — **комментарий**, а не событие: у него нет типа и тела,
     * клиенту нечего с ним делать, кроме как убедиться, что поток жив. Событие
     * с именем было бы типом без смысла и добавило бы его в перечень типов.
     */
    fun heartbeatOnce() {
        if (connections.isEmpty()) {
            return
        }
        connections.keys.toList().forEach { tabId ->
            val channel = connections[tabId] ?: return@forEach
            try {
                channel.sendComment(HEARTBEAT_COMMENT)
            } catch (failure: RuntimeException) {
                log.info("Подписка вкладки {} не приняла сердцебие: {}", tabId, failure.message)
                connections.remove(tabId)
            }
        }
    }

    /**
     * Отправляет событие подписке и снимает подписку, если отправка не вышла.
     *
     * @param tabId идентификатор вкладки
     * @param name имя события
     * @param data тело события
     */
    private fun deliver(
        tabId: String,
        name: String,
        data: String,
    ) {
        val channel = connections[tabId] ?: return
        try {
            channel.sendEvent(name, data)
        } catch (failure: IOException) {
            log.info("Подписка вкладки {} закрыта: {}", tabId, failure.message)
            connections.remove(tabId)
        } catch (failure: RuntimeException) {
            // Отправка не должна ронять работу: потерянное уведомление — это
            // молчащий экран, а не неверные данные.
            log.warn("Не удалось отправить событие «{}» в подписку {}: {}", name, tabId, failure.message)
            connections.remove(tabId)
        }
    }

    /** Закрывает подписку вкладки, если она открыта. */
    private fun close(tabId: String) {
        connections.remove(tabId)?.close()
    }

    /** Пишет тело события в JSON. */
    private fun write(payload: Any): String = mapper.writeValueAsString(payload)

    companion object {
        /**
         * Как часто отправляется сердцебие.
         *
         * Значение из караокинской админки, и совпадает с её пятнадцатью
         * секундами: меньше — лишний трафик на пустом потоке, больше — прокси
         * вправе посчитать соединение зависшим. Клиентский таймаут обязан быть
         * **больше** этого значения, иначе он сработает раньше живого потока.
         */
        val DEFAULT_HEARTBEAT_INTERVAL: Duration = Duration.ofSeconds(15)

        /** Текст комментария сердцебия. */
        const val HEARTBEAT_COMMENT: String = "heartbeat"

        /**
         * Подписка не имеет собственного таймаута.
         *
         * `0` означает «не ограничивать»: поток жив, пока жив сердцебие, а
         * ограничение контейнера оборвало бы подписку в 15 секунд — ровно
         * тогда, когда сердцебие ещё не пришло.
         */
        const val NO_TIMEOUT_MS: Long = 0L
    }
}
