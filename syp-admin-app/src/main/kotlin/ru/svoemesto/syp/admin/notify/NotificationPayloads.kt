package ru.svoemesto.syp.admin.notify

/**
 * Тело события «ход задания».
 *
 * Поля ровно те, по которым экран показывает движение. Объём работы приходит
 * отдельно от сделанного: до конца задания он бывает нулевым, и делить на
 * ноль нельзя (ADR-0006 — показывать обе величины, если они разошлись).
 *
 * @property jobId идентификатор задания
 * @property kind вид задания
 * @property done сделано единиц работы
 * @property total ожидается единиц работы; `0`, пока объём неизвестен
 * @property note пояснение для оператора
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class JobProgressPayload(
    val jobId: Long,
    val kind: String,
    val done: Long,
    val total: Long,
    val note: String,
)

/**
 * Тело события «смена состояния задания».
 *
 * @property jobId идентификатор задания
 * @property kind вид задания
 * @property state состояние после изменения
 * @property subjectType вид предмета работы либо `null`
 * @property subjectId идентификатор предмета работы либо `null`
 * @property done сделано единиц работы
 * @property total ожидается единиц работы
 * @property errorText текст ошибки при состоянии `ERROR`, иначе `null`
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class JobStatePayload(
    val jobId: Long,
    val kind: String,
    val state: String,
    val subjectType: String?,
    val subjectId: Long?,
    val done: Long,
    val total: Long,
    val errorText: String?,
)

/**
 * Тело события «состояние очереди».
 *
 * @property working заданий взято в работу
 * @property waiting заданий ждёт
 * @property failed заданий завершилось ошибкой
 * @property done заданий завершилось успешно
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class QueueStatePayload(
    val working: Long,
    val waiting: Long,
    val failed: Long,
    val done: Long,
)

/**
 * Тело события «изменилась сумма исходника».
 *
 * @property seriesId серия
 * @property state состояние подсчёта
 * @property digest посчитанная сумма либо `null`, пока её нет
 * @property isStale помечена ли сумма устаревшей
 * @property errorText текст ошибки при состоянии `ERROR`, иначе `null`
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ChecksumChangedPayload(
    val seriesId: Long,
    val state: String,
    val digest: String?,
    val isStale: Boolean,
    val errorText: String?,
)

/**
 * Тело события «сценарий готов».
 *
 * @property recipeId идентификатор сценария
 * @property name название сценария
 * @property itemCount число фрагментов
 * @property expectedDurationMs расчётная длительность подборки либо `null`
 * @property expectedFrameCount расчётное число кадров либо `null`
 * @property signingKeyId идентификатор ключа подписи; **не сам ключ**
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeReadyPayload(
    val recipeId: Long,
    val name: String,
    val itemCount: Int,
    val expectedDurationMs: Long?,
    val expectedFrameCount: Long?,
    val signingKeyId: String,
)

/**
 * Тело события «ошибка».
 *
 * @property code машинный код отказа; интерфейс принимает решение по коду, а
 *   не по тексту
 * @property message текст отказа на русском для оператора
 * @property operation что именно оператор делал, когда отказ случился
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ErrorPayload(
    val code: String,
    val message: String,
    val operation: String,
)
