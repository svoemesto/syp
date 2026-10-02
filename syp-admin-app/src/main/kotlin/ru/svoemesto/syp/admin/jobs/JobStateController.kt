package ru.svoemesto.syp.admin.jobs

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobState

/**
 * Состояние заданий очереди: что сейчас в работе и с каким прогрессом.
 *
 * Отдельная от постановки задания часть: принять задание и узнать о нём —
 * разные вопросы, и второй возникает много раз за время работы задания.
 * Пока такого эндпоинта не было, интерфейс узнавал о ходе работы только по
 * тому, что кнопка постановки нажата, а по завершении не узнавал ничего.
 *
 * @property queue очередь заданий
 */
@RestController
class JobStateController(
    private val queue: JobQueue,
) {
    /**
     * Отдаёт задания очереди: по умолчанию все, что попало в выборку.
     *
     * Порядок — по времени постановки, чтобы порядок в списке совпадал с
     * порядком нажатия кнопок.
     *
     * @param states состояния; пустой список означает «без фильтра»
     * @param limit ограничение числа заданий в ответе
     * @return задания с прогрессом
     */
    @GetMapping("/api/jobs")
    fun list(
        @RequestParam(required = false) states: List<JobState>?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<JobView> = queue.list(states ?: emptyList(), limit).map { JobView.of(it) }

    /**
     * Отдаёт одно задание по номеру.
     *
     * @param jobId номер задания
     * @return задание либо отказ 404, если такого задания нет
     */
    @GetMapping("/api/jobs/{jobId}")
    fun one(
        @PathVariable jobId: Long,
    ): ResponseEntity<JobView> {
        val job = queue.find(jobId)
        return if (job == null) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity.status(HttpStatus.OK).body(JobView.of(job))
        }
    }
}

/**
 * Представление задания для интерфейса.
 *
 * Прогресс отдаётся тремя способами, а не одним: полоса рисуется по
 * [progressPercent], счётчик показывается по [progressDone] и
 * [progressTotal], а пояснение хода берётся из [progressNote]. У задания,
 * чей итог заранее неизвестен, [progressTotal] пуст, а [progressUnknown]
 * говорит об этом прямо — иначе полоса при нулевом счётчике выглядела бы
 * заполненной.
 *
 * @property id номер задания
 * @property kind вид задания
 * @property state состояние задания
 * @property subjectType вид объекта задания
 * @property subjectId номер объекта задания
 * @property progressDone сделано
 * @property progressTotal всего, если известно
 * @property progressUnknown признак «итог неизвестен»
 * @property progressNote пояснение хода работы
 * @property progressPercent процент, если известен
 * @property errorText текст ошибки, если задание упало
 * @property createdAt момент постановки
 * @property startedAt момент начала работы
 * @property finishedAt момент завершения
 */
data class JobView(
    val id: Long,
    val kind: String,
    val state: String,
    val subjectType: String?,
    val subjectId: Long?,
    val progressDone: Long,
    val progressTotal: Long?,
    val progressUnknown: Boolean,
    val progressNote: String,
    val progressPercent: Double?,
    val errorText: String?,
    val createdAt: String?,
    val startedAt: String?,
    val finishedAt: String?,
) {
    companion object {
        /**
         * Строит представление задания.
         *
         * @param job задание очереди
         * @return представление для интерфейса
         */
        fun of(job: Job): JobView {
            val progress = job.progress
            return JobView(
                id = job.id,
                kind = job.kind.name,
                state = job.state.name,
                subjectType = job.subject.type,
                subjectId = job.subject.identifier,
                progressDone = progress.done,
                progressTotal = progress.total.takeIf { it > 0 },
                progressUnknown = progress.isUnknownTotal,
                progressNote = progress.note,
                progressPercent =
                    if (progress.isUnknownTotal) {
                        null
                    } else {
                        (progress.fraction * 100).toInt().toDouble()
                    },
                errorText = job.errorText,
                createdAt = job.createdAt,
                startedAt = job.startedAt,
                finishedAt = job.finishedAt,
            )
        }
    }
}
