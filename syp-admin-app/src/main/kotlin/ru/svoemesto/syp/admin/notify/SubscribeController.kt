package ru.svoemesto.syp.admin.notify

import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Подписка интерфейса на уведомления SSE.
 *
 * Единственный эндпоинт потока событий. Отдельный путь, а не флаг у
 * существующих: поток событий и обычный ответ JSON несовместимы по nature
 * ответа, и смешивать их в одном эндпоинте — значит условно менять тип
 * содержимого по параметру.
 *
 * **Идентификатор вкладки приходит от клиента и потому недоверенен**: он
 * проверяется по форме, а непрошедшее значение заменяется серверным
 * ([TabId]). Никакой другой роли у него нет: он различает вкладки и ничего
 * больше.
 *
 * @property notifications нотификации SSE
 * @property queueStateReader состояние очереди для первого события подписки
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class SubscribeController(
    private val notifications: SseNotificationService,
    private val queueStateReader: QueueStateReader,
) {
    /**
     * Открывает поток событий для вкладки.
     *
     * Первым событием уходит состояние очереди: подключившийся экран не
     * должен ждать первого изменения, чтобы понять, что очередь пуста.
     *
     * @param tabId идентификатор вкладки, сгенерированный клиентом
     * @return поток событий
     */
    @GetMapping(
        value = ["/api/subscribe"],
        produces = [MediaType.TEXT_EVENT_STREAM_VALUE],
    )
    fun subscribe(
        @RequestParam(name = TabId.PARAMETER, required = false) tabId: String?,
    ): SseEmitter {
        val subscription = notifications.connect(tabId)
        notifications.publishTo(
            subscription,
            SseEventType.QUEUE_STATE,
            queueStateReader.read().toPayload(),
        )
        return subscription.emitter
    }
}
