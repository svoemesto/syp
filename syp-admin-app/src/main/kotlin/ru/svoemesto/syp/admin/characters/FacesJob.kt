package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.admin.analysis.AnalysisKind
import ru.svoemesto.syp.admin.analysis.AnalysisRun
import ru.svoemesto.syp.admin.analysis.AnalysisRunStore
import ru.svoemesto.syp.admin.analysis.MonotonicProgress
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.admin.jobs.JobResult
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobProgress
import ru.svoemesto.syp.core.jobs.ParamsHash
import ru.svoemesto.syp.core.media.FrameChannelFailed

/**
 * Задание `FACES`: проход по кадрам серии с передачей их детектору лиц.
 *
 * **Граница задания, а не сама детекция.** Проход, проверки и учёт сделаны;
 * сама детекция — задача T063, которой нужна среда исполнения видеокарты
 * (на машине карта есть, `onnxruntime`, `torch` и `nvcc` не установлены).
 * Пока детектора нет, в коде стоит заглушка, и задание **говорит об этом в
 * тексте результата** — иначе «лиц не найдено» выглядело бы как вывод
 * детектора.
 *
 * Что задание гарантирует уже сейчас:
 *
 * 1. **кадры не попадают на диск** — единственный буфер переиспользуется от
 *    кадра к кадру, файлов не создаётся (FR-024, ADR-0002). На диск идут
 *    только превью, и их собирает задание `ANALYZE`;
 * 2. **обрабатывается каждый кадр** — адаптивного шага нет, порядок кадров
 *    совпадает с порядком в файле (ADR-0002);
 * 3. **частичный результат невозможен** — число обработанных кадров обязано
 *    совпасть с числом кадров серии, оборванный кадр и ненулевой код декодера
 *    ведут в `ERROR` с текстом (SC-005, constitution IV.2);
 * 4. **прогон виден в базе** — вид прогона `FACES`, ключ детектора и хеш
 *    входов записываются, поэтому результат заглушки никогда не будет выдан
 *    за результат настоящего детектора.
 *
 * Прогресс монотонен и переживает перезапуск воркера — тем же счётчиком, что
 * и в задании `ANALYZE` (FR-003).
 *
 * @property seriesStore хранилище серий: из него берётся предмет задания
 * @property runStore хранилище прогонов анализа
 * @property scan проход по кадрам с детектором
 * @property detectorKey идентификатор детектора для прогона
 * @property faceSinks сборка приёмника рамок; `null` — рамки не сохраняются
 * @property settingsStore настройки сериала: из них берётся порог пропорции
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FacesJob(
    private val seriesStore: SeriesStore,
    private val runStore: AnalysisRunStore,
    private val scan: FaceScan,
    private val detectorKey: String,
    private val faceSinks: FaceSinkFactory? = null,
    private val settingsStore: ru.svoemesto.syp.admin.catalog.SerialSettingsStore? = null,
    private val planBinding: FacePlanBinding? = null,
) : JobHandler {
    /** Вид задания, который обрабатывает исполнитель. */
    override val kind: JobKind = JobKind.FACES

    /**
     * Проводит серию через детектор лиц.
     *
     * @param job задание с предметом «серия»
     * @param progress приёмник прогресса
     * @return результат выполнения
     * @throws DomainException с кодом `NOT_FOUND`, если серия не зарегистрирована
     * @throws FrameChannelFailed если поток кадров оборвался или декодер
     *   завершился с ненулевым кодом: воркер переведёт задание в `ERROR`
     */
    override fun execute(
        job: Job,
        progress: (JobProgress) -> Unit,
    ): JobResult {
        val series = requireSeries(job)
        val total = series.frameCount.toLong()
        val report = MonotonicProgress(progress, job.progress, total)
        val run =
            runStore.begin(
                AnalysisRun(
                    seriesId = series.id!!,
                    kind = AnalysisKind.FACES,
                    algorithmVersion = detectorKey,
                    paramsHash = paramsHashOf(series, detectorKey),
                ),
            )
        val runId =
            run.id
                ?: throw ru.svoemesto.syp.core.db.DbException(
                    "Прогон поиска лиц заведён, но без идентификатора: результат записывать некуда",
                )
        runStore.startWork(runId)

        // Приёмник рамок собирается до прохода и один раз: служебные персоны
        // сериала читаются здесь, а не на каждом из 88 643 кадров.
        val sink =
            if (faceSinks != null && settingsStore != null) {
                faceSinks.forSeries(series, settingsStore.read(series.serialId))
            } else {
                null
            }

        return try {
            val result =
                scan.scan(
                    series = series,
                    sink = sink,
                    progress = { done ->
                        // Отчёт идёт пачками: на 88 643 кадрах отчёт по
                        // каждому кадру означал бы 88 643 записи в базу задания
                        // и ничего оператору не добавил бы.
                        if (done.toLong() == total || done % PROGRESS_STEP == 0) {
                            report.report(
                                done.toLong(),
                                "поиск лиц: кадр $done из ${series.frameCount}",
                            )
                        }
                    },
                )
            // Лица записываются по кадрам, а план им соответствует по диапазону
            // кадров. Если между детекцией и пересчётом границы плана поменялись,
            // соответствие надо пересчитать здесь же: иначе лица остались бы
            // привязаны к плану, которого для них уже нет, до следующего
            // изменения границ — а оно может не наступить неделями.
            val rebound = planBinding?.rebindSeries(series.id!!) ?: 0
            val note =
                if (rebound > 0) {
                    "${result.note(series.name)}; перепривязано лиц к планам: $rebound"
                } else {
                    result.note(series.name)
                }
            report.report(total, note)
            runStore.complete(runId)
            JobResult(
                note = note,
                progressTotal = total,
            )
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            runStore.fail(runId, "поиск лиц прерван, задание вернётся в очередь и продолжит работу")
            throw interrupted
        } catch (failure: Throwable) {
            runStore.fail(
                runId,
                failure.message?.takeIf { it.isNotBlank() } ?: "поиск лиц не удался",
            )
            throw failure
        }
    }

    /**
     * Читает серию по предмету задания.
     *
     * @param job задание
     * @return серия
     * @throws DomainException с кодом `NOT_FOUND`, если предмет задания не
     *   серия либо серия не зарегистрирована
     */
    private fun requireSeries(job: Job): Series {
        val subject = job.subject
        val seriesId = subject.identifier
        if (subject.type != SUBJECT_SERIES || seriesId == null) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "заданию FACES нужен предмет «серия», а у него «${subject.type}»: искать лица не в чем",
            )
        }
        return seriesStore.find(seriesId)
            ?: throw DomainException(
                ErrorCode.NOT_FOUND,
                "серия $seriesId не зарегистрирована: искать лица не в чем",
            )
    }

    companion object {
        /** Тип предмета задания для серии. */
        const val SUBJECT_SERIES: String = "SERIES"

        /** Через сколько кадров задание отчитывается о прогрессе. */
        const val PROGRESS_STEP: Int = 500

        /**
         * Хеш входов прохода по кадрам.
         *
         * В хеш входят детектор и размеры кадра: результат, полученный другим
         * детектором или в другом разрешении, нельзя выдавать за этот
         * (FR-090, Р-10).
         *
         * @param series серия
         * @param detectorKey идентификатор детектора
         * @return 64 шестнадцатеричных символа в нижнем регистре
         */
        fun paramsHashOf(
            series: Series,
            detectorKey: String,
        ): String = ParamsHash.of(detectorKey, series.width, series.height)
    }
}
