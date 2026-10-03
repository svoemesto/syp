package ru.svoemesto.syp.admin.notify

import ru.svoemesto.syp.admin.integrity.ChecksumEntry
import ru.svoemesto.syp.core.recipe.BuildRecipe

/**
 * Публикация уведомлений о событиях домена.
 *
 * Собрана в одном классе по одной причине: три вызывающих места — задание
 * подсчёта суммы, выдача сценария и отказ выдачи — должны говорить одно и то
 * же и не должны знать, как устроена рассылка.
 *
 * Область класса намеренно узкая: **только те события, которых нет в очереди
 * заданий**. Ход задания и состояние очереди публикует слушатель очереди —
 * он видит все переходы, включая те, которых не было в коде этого класса.
 *
 * @property notifications нотификации SSE
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class NotificationPublisher(
    private val notifications: SseNotificationService,
) {
    /**
     * Публикует изменение состояния суммы исходника.
     *
     * @param entry запись справочника сумм
     */
    fun checksumChanged(entry: ChecksumEntry) {
        notifications.publish(
            SseEventType.CHECKSUM_CHANGED,
            ChecksumChangedPayload(
                videofileId = entry.videofileId,
                state = entry.state.name,
                digest = entry.digest,
                isStale = entry.isStale,
                errorText = entry.errorText,
            ),
        )
    }

    /**
     * Публикует готовность сценария сборки.
     *
     * @param recipe выданный сценарий
     */
    fun recipeReady(recipe: BuildRecipe) {
        notifications.publish(
            SseEventType.RECIPE_READY,
            RecipeReadyPayload(
                recipeId = recipe.id!!,
                name = recipe.name,
                itemCount = recipe.itemCount,
                expectedDurationMs = recipe.expectedDurationMs,
                expectedFrameCount = recipe.expectedFrameCount,
                signingKeyId = recipe.signingKeyId!!,
            ),
        )
    }

    /**
     * Публикует отказ операции, которая не является заданием очереди.
     *
     * @param code машинный код отказа
     * @param message текст отказа на русском
     * @param operation что делал оператор
     */
    fun failed(
        code: String,
        message: String,
        operation: String,
    ) {
        notifications.publishError(code, message, operation)
    }
}
