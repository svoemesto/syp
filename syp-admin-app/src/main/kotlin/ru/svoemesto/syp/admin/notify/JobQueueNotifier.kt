package ru.svoemesto.syp.admin.notify

import ru.svoemesto.syp.core.jobs.JobQueueListener
import ru.svoemesto.syp.core.jobs.JobSignal

/**
 * Перевод изменений очереди заданий в уведомления интерфейса.
 *
 * Слушатель сидит на самой очереди, а не на воркере и не на контроллерах,
 * потому что очередь — **единственное** место, где меняется состояние
 * задания. Повесь уведомление на воркера, и отмена задания оператором
 * останется незамеченной; повесь на контроллер, и возврат задания в очередь
 * после остановки воркера тоже останется незамеченным.
 *
 * Прогресс и смена состояния разведены по разным типам событий, а состояние
 * очереди отправляется вслед за сменой состояния: оператор смотрит на очередь
 * целиком, и «работает одно, ждут семь» — не то же самое, что «сколько кадров
 * обработано».
 *
 * @property notifications нотификации SSE
 * @property queueStateReader состояние очереди
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class JobQueueNotifier(
    private val notifications: SseNotificationService,
    private val queueStateReader: QueueStateReader,
) : JobQueueListener {
    /**
     * Состояние задания изменилось.
     *
     * @param signal снимок изменения
     */
    override fun onStateChanged(signal: JobSignal) {
        notifications.publish(SseEventType.JOB_STATE, signal.toStatePayload())
        publishQueueState()
    }

    /**
     * Прогресс задания изменился.
     *
     * @param signal снимок изменения
     */
    override fun onProgressChanged(signal: JobSignal) {
        notifications.publish(SseEventType.JOB_PROGRESS, signal.toProgressPayload())
    }

    /**
     * Отправляет состояние очереди.
     *
     * Ошибка чтения глушится: очередь об этом узнавать не должна — её дело
     * записать переход, а не разбираться, покажет ли кто-нибудь счётчик.
     */
    private fun publishQueueState() {
        runCatching {
            notifications.publish(SseEventType.QUEUE_STATE, queueStateReader.read().toPayload())
        }
    }
}

/** Строит тело события «ход задания» из снимка изменения. */
private fun JobSignal.toProgressPayload(): JobProgressPayload =
    JobProgressPayload(
        jobId = id,
        kind = kind.name,
        done = progress.done,
        total = progress.total,
        note = progress.note,
    )

/** Строит тело события «смена состояния» из снимка изменения. */
private fun JobSignal.toStatePayload(): JobStatePayload =
    JobStatePayload(
        jobId = id,
        kind = kind.name,
        state = state.name,
        subjectType = subject.type,
        subjectId = subject.identifier,
        done = progress.done,
        total = progress.total,
        errorText = errorText,
    )
