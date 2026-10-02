package ru.svoemesto.syp.admin.notify

import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Канал отправки уведомлений в одну подписку.
 *
 * Отдельно от сервиса по одной причине: сервис занимается тем, **кому** и
 * **что** отправлять, а канал — тем, как это уходит в поток. Пока канал был бы
 * самим `SseEmitter`, проверка рассылки требовала бы настоящего HTTP-ответа,
 * и проверяла бы сеть вместо рассылки.
 *
 * Настоящий канал — [SseEmitterChannel]. Проверка подставляет свой, который
 * пишет в список вместо сокета.
 *
 * @property emitter поток, который отдаётся в ответ HTTP
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
interface NotificationChannel {
    /** Поток подписки, который контроллер отдаёт в ответ. */
    val emitter: SseEmitter

    /**
     * Отправляет событие.
     *
     * @param name имя события: тип уведомления едет именем, а не полем
     * @param data тело события в виде JSON
     */
    fun sendEvent(
        name: String,
        data: String,
    )

    /**
     * Отправляет сердцебие.
     *
     * @param text текст комментария
     */
    fun sendComment(text: String)

    /** Закрывает подписку. Закрытие уже закрытой подписки не является ошибкой. */
    fun close()
}

/**
 * Канал поверх `SseEmitter`.
 *
 * @property emitter поток подписки
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SseEmitterChannel(
    override val emitter: SseEmitter = SseEmitter(SseNotificationService.NO_TIMEOUT_MS),
) : NotificationChannel {
    /**
     * Отправляет событие с именем типа.
     *
     * @param name имя события
     * @param data тело события в виде JSON
     */
    override fun sendEvent(
        name: String,
        data: String,
    ) {
        emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON))
    }

    /**
     * Отправляет комментарий сердцебия.
     *
     * @param text текст комментария
     */
    override fun sendComment(text: String) {
        emitter.send(SseEmitter.event().comment(text))
    }

    /** Закрывает поток подписки. */
    override fun close() {
        runCatching { emitter.complete() }
    }
}
