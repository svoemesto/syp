package ru.svoemesto.syp.admin.notify

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * Канал подписки, который вместо сокета пишет в список.
 *
 * Настоящий канал уходит в сокет, и проверять его пришлось бы по сети: тест
 * был бы проверкой сети, а не рассылки. Здесь подменён единственный выход —
 * отправка, — поэтому в список попадает ровно то, что уходит по сети: имя
 * события, тело и комментарий сердчебия.
 *
 * @property emitter поток подписки; он есть и в проверке, но не используется:
 *   отправки идут мимо него
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class RecordingChannel(
    override val emitter: SseEmitter = SseEmitter(SseNotificationService.NO_TIMEOUT_MS),
) : NotificationChannel {
    /** Одно отправленное событие либо сердцебие. */
    data class Sent(
        /** Имя события; `null`, если отправлено сердцебие. */
        val name: String?,
        /** Текст комментария сердчебия; `null`, если отправлено событие. */
        val comment: String?,
        /** Тело события; `null`, если отправлено сердчебие. */
        val data: String?,
    )

    /** Что отправлено, по порядку. */
    val sent = mutableListOf<Sent>()

    /** Канал закрыт ли. */
    var closed: Boolean = false
        private set

    /**
     * Отправляет событие.
     *
     * @param name имя события
     * @param data тело события
     */
    override fun sendEvent(
        name: String,
        data: String,
    ) {
        sent.add(Sent(name = name, comment = null, data = data))
    }

    /**
     * Отправляет сердцебие.
     *
     * @param text текст комментария
     */
    override fun sendComment(text: String) {
        sent.add(Sent(name = null, comment = text, data = null))
    }

    /** Канал закрыт. */
    override fun close() {
        closed = true
    }

    /**
     * События по именам, в порядке отправки.
     *
     * @return имена отправленных событий
     */
    fun eventNames(): List<String?> = sent.map { it.name }

    /**
     * Тело события по его имени.
     *
     * @param name имя события
     * @return тело либо `null`, если такого события не было
     */
    fun bodyOf(name: String): String? = sent.firstOrNull { it.name == name }?.data
}
