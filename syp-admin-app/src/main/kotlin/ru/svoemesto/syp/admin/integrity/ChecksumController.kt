package ru.svoemesto.syp.admin.integrity

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobSubject
import ru.svoemesto.syp.core.jobs.ParamsHash
import java.time.Instant

/**
 * Состояние суммы эпизода в ответе.
 *
 * Поля ответа — ровно те, что перечислены в контракте
 * [`admin-api.md`](../../../../../specs/001-first-vertical-slice/contracts/admin-api.md),
 * раздел 3.3. Значение [digest] заполнено только у готовой суммы: у
 * незавершённого подсчёта суммы не существует, и выдавать вместо неё пустую
 * строку означало бы выдать выдуманное значение.
 *
 * @property episodeId идентификатор эпизода
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
 * @property historyCount сколько записей пересчётов у эпизода всего
 * @property canRecalculate можно ли поставить пересчёт прямо сейчас
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ChecksumView(
    val episodeId: Long,
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
 * @property episodeId эпизод, для которой считается сумма
 * @property state состояние задания на момент постановки
 * @property reason зачем поставлен пересчёт
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ChecksumEnqueuedView(
    val jobId: Long,
    val episodeId: Long,
    val state: String,
    val reason: String,
)

/**
 * Постановка подсчёта суммы.
 *
 * Подсчёт ставится в двух случаях, и оба приводят к одному и тому же
 * заданию:
 *
 * 1. **автоматически** — при регистрации эпизода: без суммы сценарий отдать
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
 * @property episodeStore хранилище эпизодов
 * @property registry справочник сумм
 * @property paramsBuilder собирает параметры задания из эпизода
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ChecksumEnqueuer(
    private val queue: JobQueue,
    private val episodeStore: EpisodeStore,
    private val registry: ChecksumRegistry,
    private val paramsBuilder: (Episode) -> String = { episode -> defaultParams(episode) },
) {
    /**
     * Ставит подсчёт суммы эпизода.
     *
     * @param episodeId идентификатор эпизода
     * @param reason зачем поставлен пересчёт
     * @return идентификатор поставленного задания
     * @throws DomainException с кодом `NOT_FOUND`, если эпизод не зарегистрирована
     */
    fun enqueue(
        episodeId: Long,
        reason: String,
    ): Long {
        val episode =
            episodeStore.find(episodeId)
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "эпизод $episodeId не зарегистрирована: сумму считать нечего",
                )
        // Подмена источника обнаруживается до постановки: пересчёт всё равно
        // пойдёт, но оператор должен увидеть устаревшую прежнюю сумму, а не
        // новую на месте старой.
        registry.markStaleWhenSourceChanged(episode)
        return queue.enqueue(
            kind = JobKind.HASH,
            subject = JobSubject.episode(episodeId),
            paramsJson = paramsBuilder(episode),
            paramsHash = paramsHashOf(episode),
        )
    }

    /**
     * Ставит подсчёт автоматически, при регистрации эпизода.
     *
     * @param episode только что зарегистрированный эпизод
     * @return идентификатор поставленного задания
     */
    fun enqueueAutomatic(episode: Episode): Long = enqueue(episode.id!!, "эпизод зарегистрирована: сумма считается автоматически")

    companion object {
        /**
         * Параметры задания для эпизода.
         *
         * В параметры входит всё, что влияет на результат: сам путь, размер
         * файла и время его изменения. По ним же считается хеш параметров,
         * поэтому подмена файла даёт другое задание даже при том же пути.
         *
         * @param episode эпизод
         * @return параметры задания в виде JSON
         */
        fun defaultParams(episode: Episode): String =
            "{\"algorithm\":\"${ChecksumEntry.ALGORITHM_SHA256}\"," +
                "\"sourcePath\":\"${episode.sourcePath}\"," +
                "\"byteSize\":${episode.byteSize}," +
                "\"fileMtime\":\"${episode.fileMtime}\"}"

        /**
         * Хеш параметров задания подсчёта.
         *
         * @param episode эпизод
         * @return 64 шестнадцатеричных символа в нижнем регистре
         */
        fun paramsHashOf(episode: Episode): String =
            ParamsHash.of(
                ChecksumEntry.ALGORITHM_SHA256,
                episode.sourcePath,
                episode.byteSize,
                episode.fileMtime,
            )
    }
}

/**
 * Эндпоинты сверки целостности исходника.
 *
 * Пара методов, у которой есть честный ответ на вопрос «можно ли выдавать
 * сценарий этого эпизода» и «как пересчитать сумму». Ничего больше: выдача
 * сценария в админке невозможна (FR-085, ADR-0009), и эндпоинта выдачи
 * здесь нет.
 *
 * @property enqueuer постановщик подсчёта
 * @property registry справочник сумм
 * @property episodeStore хранилище эпизодов
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class ChecksumController(
    private val enqueuer: ChecksumEnqueuer,
    private val registry: ChecksumRegistry,
    private val episodeStore: EpisodeStore,
) {
    /**
     * Отдаёт состояние суммы эпизода.
     *
     * Ответ идёт по **последней** записи, а не по актуальной: интерфейсу
     * нужно показать «считается» и «ошибка», а не пустую страницу. Отдельного
     * ответа «суммы нет» не делается — вместо него `409` с кодом
     * `CHECKSUM_NOT_READY`, как требует контракт.
     *
     * @param episodeId идентификатор эпизода
     * @return состояние суммы эпизода
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет; с кодом
     *   `CHECKSUM_NOT_READY`, если сумма ещё ни разу не считалась
     */
    @GetMapping("/api/episodes/{episodeId}/checksum")
    fun readChecksum(
        @PathVariable episodeId: Long,
    ): ChecksumView {
        val episode = requireEpisode(episodeId)
        val entry =
            registry.latest(episodeId)
                ?: throw DomainException(
                    ErrorCode.CHECKSUM_NOT_READY,
                    "сумма эпизода «${episode.name}» ещё не считалась. Поставьте пересчёт: " +
                        "без актуальной суммы сценарий сборки выдать нельзя (FR-089)",
                )
        // Сверка подмены источника при чтении: файл мог смениться после того,
        // как оператор последний раз смотрел на страницу.
        registry.markStaleWhenSourceChanged(episode)
        val refreshed = registry.latest(episodeId) ?: entry
        return refreshed.toView(registry.history(episodeId).size)
    }

    /**
     * Ставит пересчёт суммы эпизода.
     *
     * @param episodeId идентификатор эпизода
     * @return поставленное задание, код `202`
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    @PostMapping("/api/episodes/{episodeId}/checksum")
    fun startChecksum(
        @PathVariable episodeId: Long,
    ): ResponseEntity<ChecksumEnqueuedView> {
        requireEpisode(episodeId)
        val reason = "пересчёт по запросу оператора"
        val jobId = enqueuer.enqueue(episodeId, reason)
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(
                ChecksumEnqueuedView(
                    jobId = jobId,
                    episodeId = episodeId,
                    state = ru.svoemesto.syp.core.jobs.JobState.WAITING.name,
                    reason = reason,
                ),
            )
    }

    /**
     * Читает эпизод или отказывает.
     *
     * @param episodeId идентификатор эпизода
     * @return эпизод
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    private fun requireEpisode(episodeId: Long): Episode =
        episodeStore.find(episodeId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "эпизод $episodeId не зарегистрирована")
}

/**
 * Описание записи справочника для ответа.
 *
 * @param historyCount сколько записей пересчётов у эпизода всего
 * @return описание суммы
 */
internal fun ChecksumEntry.toView(historyCount: Int): ChecksumView =
    ChecksumView(
        episodeId = episodeId,
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
