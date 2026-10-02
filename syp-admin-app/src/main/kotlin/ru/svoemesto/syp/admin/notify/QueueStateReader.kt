package ru.svoemesto.syp.admin.notify

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row

/**
 * Счётчик состояний очереди заданий.
 *
 * Отдельный класс ради одного: состояние очереди читается и при подключении,
 * и при каждой смене состояния задания, а отличать эти два чтения по месту
 * невозможно — это один и тот же вопрос с двумя моментами.
 *
 * @property working заданий взято в работу
 * @property waiting заданий ждёт
 * @property failed заданий завершилось ошибкой
 * @property done заданий завершилось успешно
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class QueueState(
    val working: Long,
    val waiting: Long,
    val failed: Long,
    val done: Long,
)

/**
 * Чтение состояния очереди заданий.
 *
 * Один запрос с группировкой по состоянию, а не четыре счётчика по одному:
 * очередь читается при каждой смене состояния, и четыре обращения к базе на
 * одно событие — это четыре лишних соединения там, где хватает одного.
 *
 * Признак «воркер работает» сюда **намеренно не входит**. Ответ на вопрос
 * «работает ли воркер» знает сам воркер, а читатель очереди о нём не знает
 * ничего: взять его здесь означало бы связать очередь с исполнителем, а
 * очередь от исполнителя не зависит (ADR-0009 — работа идёт через задание,
 * а не через того, кто её взял). В первом срезе кнопок старта и остановки
 * очереди нет, и спрашивать нечего.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class QueueStateReader(
    private val db: Db,
) {
    /**
     * Считает задания по состояниям.
     *
     * @return состояние очереди
     * @throws ru.svoemesto.syp.core.db.DbException если выборка не удалась
     */
    fun read(): QueueState {
        val rows =
            db.select(
                "SELECT state, count(*) AS total FROM tbl_jobs GROUP BY state",
                ::readCount,
            )
        val counts = rows.associate { it.first to it.second }
        return QueueState(
            working = count(counts, "CREATING") + count(counts, "WORKING"),
            waiting = count(counts, "WAITING"),
            failed = count(counts, "ERROR"),
            done = count(counts, "DONE"),
        )
    }

    /** Отсутствующее состояние означает ноль заданий, а не ошибку чтения. */
    private fun count(
        counts: Map<String, Long>,
        state: String,
    ): Long = counts[state] ?: 0L

    /** Строит пару «состояние — сколько» из строки выборки. */
    private fun readCount(row: Row): Pair<String, Long> = row.string("state") to row.long("total")
}

/**
 * Строит тело события «состояние очереди».
 *
 * Отдельное тело, а не сама [QueueState], потому что событие уходит в поток
 * и его форма — часть контракта: переименование поля состояния очереди не
 * должно молча ломать интерфейс.
 *
 * @return тело события
 */
internal fun QueueState.toPayload(): QueueStatePayload =
    QueueStatePayload(
        working = working,
        waiting = waiting,
        failed = failed,
        done = done,
    )
