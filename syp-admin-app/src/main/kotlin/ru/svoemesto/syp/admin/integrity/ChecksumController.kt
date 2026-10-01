package ru.svoemesto.syp.admin.integrity

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobSubject
import ru.svoemesto.syp.core.jobs.ParamsHash
import java.time.Instant

/**
 * Состояние суммы серии в ответе.
 *
 * Поля ответа — ровно те, что перечислены в контракте
 * [`admin-api.md`](../../../../../specs/001-first-vertical-slice/contracts/admin-api.md),
 * раздел 3.3. Значение [digest] заполнено только у готовой суммы: у
 * незавершённого подсчёта суммы не существует, и выдавать вместо неё пустую
 * строку означало бы выдать выдуманное значение.
 *
 * @property seriesId идентификатор серии
 * @property state состояние подсчёта
 * @property algorithm алгоритм подсчёта
 * @property digest значение суммы; `null`, пока сумма не посчитана
 * @property byteSize размер файла, на котором считали
 * @property fileMtime время изменения файла, на котором считали
 * @property computedAt когда сумма посчитана
 * @property isStale файл изменился после подсчёта
 * @property isUsable пригодна ли сумма для сверки на машине пользователя
 * @property errorText текст ошибки при сбое подсчёта
 * @property jobId задание, считающее или посчитавшее сумму
 * @property historyCount сколько записей пересчётов у серии всего
 * @property canRecalculate можно ли поставить пересчёт прямо сейчас
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ChecksumView(
    val seriesId: Long,
    val state: String,
    val algorithm: String,
    val digest: String?,
    val byteSize: Long,
    val fileMtime: Instant,
    val computedAt: Instant?,
    val isStale: Boolean,
    val isUsable: Boolean,
    val errorText: String?,
    val jobId: Long?,
    val historyCount: Int,
    val canRecalculate: Boolean,
)

/**
 * Ответ на постановку пересчёта.
 *
 * Ответ — `202`: работа принята в очередь, а не выполнена. Кнопка, которая
 * ждала бы окончания подсчёта файла в 5,6 ГБ, нарушала бы constitution IV.1
 * (FR-003).
 *
 * @property jobId идентификатор поставленного задания
 * @property seriesId серия, для которой считается сумма
 * @property state состояние задания на момент постановки
 * @property reason зачем поставлен пересчёт
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ChecksumEnqueuedView(
    val jobId: Long,
    val seriesId: Long,
    val state: String,
    val reason: String,
)

/**
 * Постановка подсчёта суммы.
 *
 * Подсчёт ставится в двух случаях, и оба приводят к одному и тому же
 * заданию:
 *
 * 1. **автоматически** — при регистрации серии: без суммы сценарий отдать
 *    нельзя, а узнать об этом через месяц работы невозможно (ADR-0009,
 *    последствие 4);
 * 2. **по кнопке** — оператор пересчитывает сам: он либо подозревает подмену
 *    файла, либо хочет проверить систему.
 *
 * **Повторная постановка означает пересчёт.** Здесь не действует правило о
 * пропуске задания с тем же хешем параметров (Р-10): для вида `HASH` его
 * результат — не артефакт в хранилище, а запись справочника, и нажатие
 * кнопки оператором есть осознанное требование пересчитать. История при этом
 * сохраняется целиком: новая сумма не затирает прежнюю, актуальной остаётся
 * ровно одна (FR-089).
 *
 * **Перед постановкой проверяется подмена источника.** Если размер файла или
 * время его изменения не совпадают с теми, при которых считалась актуальная
 * сумма, прежняя помечается устаревшей — иначе интерфейс показывал бы
 * «сумма посчитана» для файла, которого уже нет (FR-090).
 *
 * @property queue очередь заданий
 * @property seriesStore хранилище серий
 * @property registry справочник сумм
 * @property paramsBuilder собирает параметры задания из серии
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ChecksumEnqueuer(
    private val queue: JobQueue,
    private val seriesStore: SeriesStore,
    private val registry: ChecksumRegistry,
    private val paramsBuilder: (Series) -> String = { series -> defaultParams(series) },
) {
    /**
     * Ставит подсчёт суммы серии.
     *
     * @param seriesId идентификатор серии
     * @param reason зачем поставлен пересчёт
     * @return идентификатор поставленного задания
     * @throws DomainException с кодом `NOT_FOUND`, если серия не зарегистрирована
     */
    fun enqueue(
        seriesId: Long,
        reason: String,
    ): Long {
        val series =
            seriesStore.find(seriesId)
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "серия $seriesId не зарегистрирована: сумму считать нечего",
                )
        // Подмена источника обнаруживается до постановки: пересчёт всё равно
        // пойдёт, но оператор должен увидеть устаревшую прежнюю сумму, а не
        // новую на месте старой.
        registry.markStaleWhenSourceChanged(series)
        return queue.enqueue(
            kind = JobKind.HASH,
            subject = JobSubject.series(seriesId),
            paramsJson = paramsBuilder(series),
            paramsHash = paramsHashOf(series),
        )
    }

    /**
     * Ставит подсчёт автоматически, при регистрации серии.
     *
     * @param series только что зарегистрированная серия
     * @return идентификатор поставленного задания
     */
    fun enqueueAutomatic(series: Series): Long = enqueue(series.id!!, "серия зарегистрирована: сумма считается автоматически")

    companion object {
        /**
         * Параметры задания для серии.
         *
         * В параметры входит всё, что влияет на результат: сам путь, размер
         * файла и время его изменения. По ним же считается хеш параметров,
         * поэтому подмена файла даёт другое задание даже при том же пути.
         *
         * @param series серия
         * @return параметры задания в виде JSON
         */
        fun defaultParams(series: Series): String =
            "{\"algorithm\":\"${ChecksumEntry.ALGORITHM_SHA256}\"," +
                "\"sourcePath\":\"${series.sourcePath}\"," +
                "\"byteSize\":${series.byteSize}," +
                "\"fileMtime\":\"${series.fileMtime}\"}"

        /**
         * Хеш параметров задания подсчёта.
         *
         * @param series серия
         * @return 64 шестнадцатеричных символа в нижнем регистре
         */
        fun paramsHashOf(series: Series): String =
            ParamsHash.of(
                ChecksumEntry.ALGORITHM_SHA256,
                series.sourcePath,
                series.byteSize,
                series.fileMtime,
            )
    }
}

/**
 * Эндпоинты сверки целостности исходника.
 *
 * Пара методов, у которой есть честный ответ на вопрос «можно ли выдавать
 * сценарий этой серии» и «как пересчитать сумму». Ничего больше: выдача
 * сценария в админке невозможна (FR-085, ADR-0009), и эндпоинта выдачи
 * здесь нет.
 *
 * @property enqueuer постановщик подсчёта
 * @property registry справочник сумм
 * @property seriesStore хранилище серий
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class ChecksumController(
    private val enqueuer: ChecksumEnqueuer,
    private val registry: ChecksumRegistry,
    private val seriesStore: SeriesStore,
) {
    /**
     * Отдаёт состояние суммы серии.
     *
     * Ответ идёт по **последней** записи, а не по актуальной: интерфейсу
     * нужно показать «считается» и «ошибка», а не пустую страницу. Отдельного
     * ответа «суммы нет» не делается — вместо него `409` с кодом
     * `CHECKSUM_NOT_READY`, как требует контракт.
     *
     * @param seriesId идентификатор серии
     * @return состояние суммы серии
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет; с кодом
     *   `CHECKSUM_NOT_READY`, если сумма ещё ни разу не считалась
     */
    @GetMapping("/api/series/{seriesId}/checksum")
    fun readChecksum(
        @PathVariable seriesId: Long,
    ): ChecksumView {
        val series = requireSeries(seriesId)
        val entry =
            registry.latest(seriesId)
                ?: throw DomainException(
                    ErrorCode.CHECKSUM_NOT_READY,
                    "сумма серии «${series.name}» ещё не считалась. Поставьте пересчёт: " +
                        "без актуальной суммы сценарий сборки выдать нельзя (FR-089)",
                )
        // Сверка подмены источника при чтении: файл мог смениться после того,
        // как оператор последний раз смотрел на страницу.
        registry.markStaleWhenSourceChanged(series)
        val refreshed = registry.latest(seriesId) ?: entry
        return refreshed.toView(registry.history(seriesId).size)
    }

    /**
     * Ставит пересчёт суммы серии.
     *
     * @param seriesId идентификатор серии
     * @return поставленное задание, код `202`
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    @PostMapping("/api/series/{seriesId}/checksum")
    fun startChecksum(
        @PathVariable seriesId: Long,
    ): ResponseEntity<ChecksumEnqueuedView> {
        requireSeries(seriesId)
        val reason = "пересчёт по запросу оператора"
        val jobId = enqueuer.enqueue(seriesId, reason)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(
                ChecksumEnqueuedView(
                    jobId = jobId,
                    seriesId = seriesId,
                    state = ru.svoemesto.syp.core.jobs.JobState.WAITING.name,
                    reason = reason,
                ),
            )
    }

    /**
     * Читает серию или отказывает.
     *
     * @param seriesId идентификатор серии
     * @return серия
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    private fun requireSeries(seriesId: Long): Series =
        seriesStore.find(seriesId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "серия $seriesId не зарегистрирована")
}

/**
 * Описание записи справочника для ответа.
 *
 * @param historyCount сколько записей пересчётов у серии всего
 * @return описание суммы
 */
internal fun ChecksumEntry.toView(historyCount: Int): ChecksumView =
    ChecksumView(
        seriesId = seriesId,
        state = state.name,
        algorithm = algorithm,
        digest = digest,
        byteSize = byteSize,
        fileMtime = fileMtime.toInstant(),
        computedAt = computedAt?.toInstant(),
        isStale = isStale,
        isUsable = isUsable,
        errorText = errorText,
        jobId = jobId,
        historyCount = historyCount,
        canRecalculate = state == ChecksumState.DONE || state == ChecksumState.ERROR,
    )
