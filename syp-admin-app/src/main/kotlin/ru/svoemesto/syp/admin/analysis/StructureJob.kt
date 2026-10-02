package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.MovieSetting
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.admin.jobs.JobResult
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.images.PreviewLayout
import ru.svoemesto.syp.core.images.PreviewSheet
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobProgress
import ru.svoemesto.syp.core.media.ExternalProgram
import ru.svoemesto.syp.core.media.ExternalProgramFailed
import ru.svoemesto.syp.core.media.ProcessResult
import ru.svoemesto.syp.core.storage.ArtifactKind
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.ObjectStorage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Задание `ANALYZE`: структура эпизода и листы превью.
 *
 * **Прогресс задаётся потоком внешней программы, а не индексом цикла**
 * (FR-003, research.md Т-15). Здесь это значит три вещи:
 *
 * 1. обе фазы задания — детекция границ и изготовление листов — отчитываются
 *    тем же счётчиком `frame=…/…`, который печатает ffmpeg, и разбор этого
 *    счётчика один ([JobProgress.parseFfmpegProgress]);
 * 2. счётчик задания **монотонен** и переживает перезапуск воркера: при
 *    возврате задания в очередь сохранённое значение остаётся, а новый
 *    прогон не может откатить его назад — иначе интерфейс увидел бы, как
 *    работа идёт назад;
 * 3. общий объём известен заранее — это удвоенное число кадров эпизода, по
 *    одному проходу на фазу, — поэтому прогресс сразу настоящий, а не
 *    «неизвестно, сколько всего».
 *
 * **Две фазы — два прохода ffmpeg.** Один проход на обе задачи невозможен:
 * детекция границ требует полного потока кадров без преобразования, а лист
 * превью — уменьшенных кадров, уложенных в сетку. Объединение означало бы
 * либо потерю точности границ, либо потерю превью.
 *
 * **Структура пишется в одной транзакции с сырыми границами**, а листы
 * превью — по одному, каждый под своим артефактом: 347 листов это 347
 * регистраций, и общая транзакция на весь ряд означала бы, что прерванная
 * на середине работа не оставила бы ничего.
 *
 * **Повторный запуск не начинает с нуля молча.** Уже готовые листы
 * (`READY`) не собираются и не регистрируются заново. Детекция границ
 * при этом проходит заново: поток `scdet` нельзя продолжить с середины, и
 * честнее сказать об этом в отчёте, чем показать нулевой прогресс.
 *
 * @property episodeStore хранилище эпизодов: из него берётся путь и число кадров
 * @property runStore хранилище прогонов
 * @property structure запись рабочей структуры по результату детекции
 * @property frames хранилище значимых кадров
 * @property detector детектор границ сцен и планов
 * @property program единая точка запуска внешних программ
 * @property ffmpegPath путь к программе; приходит из конфигурации (ADR-0010)
 * @property settingsStore настройки фильма: пороги и раскладка листа
 * @property artifactRegistry реестр артефактов листов превью
 * @property staleness пометка результатов, полученных при других входах
 * @property storage объектное хранилище артефактов
 * @property workRoot каталог для промежуточных файлов внешней программы
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class StructureJob(
    private val episodeStore: EpisodeStore,
    private val runStore: AnalysisRunStore,
    private val structure: StructureService,
    private val frames: FrameSignificanceStore,
    private val detector: SceneDetector,
    private val program: ExternalProgram,
    private val ffmpegPath: String,
    private val settingsStore: MovieSettingsStore,
    private val artifactRegistry: ArtifactRegistry,
    private val staleness: Staleness,
    private val storage: ObjectStorage,
    private val workRoot: Path,
) : JobHandler {
    /** Вид задания, который обрабатывает исполнитель. */
    override val kind: JobKind = JobKind.ANALYZE

    /**
     * Находит границы, пишет структуру эпизода и собирает листы превью.
     *
     * @param job задание с предметом «эпизод»
     * @param progress приёмник прогресса из потока внешней программы
     * @return результат выполнения
     * @throws DomainException с кодом `NOT_FOUND`, если эпизод не зарегистрирована
     * @throws ExternalProgramFailed если `ffmpeg` завершился с ненулевым кодом:
     *   воркер переведёт задание в `ERROR` с текстом (FR-092, SC-005)
     */
    override fun execute(
        job: Job,
        progress: (JobProgress) -> Unit,
    ): JobResult {
        val episode = requireEpisode(job)
        val settings = settingsStore.read(episode.movieId)
        val sceneThreshold = settings.number(MovieSetting.SCENE_THRESHOLD)
        val shotThreshold = settings.number(MovieSetting.SHOT_THRESHOLD)
        val layout = layoutOf(settings.integer(MovieSetting.PREVIEW_SHEET_COLS), settings.integer(MovieSetting.PREVIEW_SHEET_ROWS))
        val paramsHash = SceneDetector.paramsHashOf(settings)
        val total = episode.frameCount.toLong() * PHASES
        val report = MonotonicProgress(progress, job.progress, total)

        val run =
            runStore.begin(
                AnalysisRun(
                    episodeId = episode.id!!,
                    kind = AnalysisKind.STRUCTURE,
                    algorithmVersion = DetectionResult.ALGORITHM_VERSION,
                    paramsHash = paramsHash,
                ),
            )
        val runId =
            run.id
                ?: throw ru.svoemesto.syp.core.db.DbException(
                    "Прогон анализа заведён, но без идентификатора: результат записывать некуда",
                )
        runStore.startWork(runId)

        return try {
            // Фаза 1. Детекция границ.
            val detection =
                detector.detect(
                    episode = episode,
                    sceneThreshold = sceneThreshold,
                    shotThreshold = shotThreshold,
                    progress = { streamed ->
                        report.report(
                            streamed.done,
                            "детекция границ: кадр ${streamed.done} из ${episode.frameCount}",
                        )
                    },
                )
            report.report(
                episode.frameCount.toLong(),
                "детекция границ: найдено ${detection.sceneBoundaries.size} сцен и " +
                    "${detection.shotBoundaries.size} планов, разбор структуры",
            )
            val episodeId = episode.id!!
            val (sceneCount, shotCount) = structure.applyDetection(runId, episodeId, detection)
            frames.markSceneBoundaries(episodeId, detection.sceneBoundaries)
            frames.markShotBoundaries(episodeId, detection.shotBoundaries)

            // Фаза 2. Листы превью.
            val sheets = buildPreviewSheets(job.id, episode, layout, report)

            runStore.complete(runId)
            // Прогоны, сделанные при других входах, помечаются устаревшими —
            // после того, как новый результат записан, а не вместо него.
            staleness.markStaleExcept(episodeId, AnalysisKind.STRUCTURE, paramsHash)

            JobResult(
                note =
                    "структура эпизода «${episode.name}»: сцен $sceneCount, планов $shotCount, " +
                        "листов превью ${sheets.built} из ${sheets.expected}" +
                        if (sheets.skipped > 0) ", готовых ранее ${sheets.skipped}" else "",
                progressTotal = total,
            )
        } catch (interrupted: InterruptedException) {
            // Прерывание не является неудачей: задание вернётся в очередь и
            // будет досчитано. Но прогон обязан это показать — иначе он
            // остался бы в состоянии работы навсегда, без следа о том, что
            // произошло.
            Thread.currentThread().interrupt()
            runStore.fail(runId, "анализ прерван, задание вернётся в очередь и продолжит работу")
            throw interrupted
        } catch (failure: Throwable) {
            // Текст ошибки обязателен: прогон в состоянии ERROR без него
            // означал бы «работа не удалась» без объяснения, почему.
            runStore.fail(
                runId,
                failure.message?.takeIf { it.isNotBlank() } ?: "анализ структуры не удался",
            )
            throw failure
        }
    }

    /**
     * Собирает листы превью эпизода одним проходом внешней программы.
     *
     * Листы, уже зарегистрированные в состоянии `READY`, не собираются
     * заново: перезапуск задания продолжает работу, а не начинает её с
     * начала (FR-003, T056).
     *
     * @param jobId задание-владелец артефактов
     * @param episode эпизод
     * @param layout раскладка листа
     * @param report счётчик прогресса
     * @return число собранных, пропущенных и ожидаемых листов
     * @throws ExternalProgramFailed если программа завершилась с ненулевым кодом
     * @throws IOException если временные файлы не создались или не читаются
     */
    private fun buildPreviewSheets(
        jobId: Long,
        episode: Episode,
        layout: PreviewLayout,
        report: MonotonicProgress,
    ): SheetOutcome {
        val sheetCount = PreviewSheet.sheetCount(episode.frameCount, layout)
        val episodeId = episode.id!!
        val pending =
            (0 until sheetCount)
                .map { PreviewSheet.of(episodeId, it, episode.frameCount, layout) }
                .filterNot { artifactRegistry.findReady(ArtifactKind.PREVIEW_SHEET, it.finalKey()) != null }
        val skipped = sheetCount - pending.size
        if (pending.isEmpty()) {
            report.report(
                episode.frameCount.toLong() * PHASES,
                "листы превью: все $sheetCount готовы ранее",
            )
            return SheetOutcome(built = 0, skipped = skipped, expected = sheetCount)
        }

        Files.createDirectories(workRoot)
        val directory = Files.createTempDirectory(workRoot, "sheets-")
        try {
            val output =
                program.run(
                    executablePath = ffmpegPath,
                    arguments =
                        listOf(
                            "-hide_banner",
                            "-nostdin",
                            "-i",
                            episode.sourcePath,
                            "-vf",
                            "scale=${layout.cellWidth}:${layout.cellHeight},tile=${layout.columns}x${layout.rows}",
                            "-fps_mode",
                            "passthrough",
                            "-start_number",
                            "0",
                            directory.resolve(SHEET_NAME_TEMPLATE).toString(),
                        ),
                    progressReader = JobProgress::parseFfmpegProgress,
                    onProgress = { streamed ->
                        report.report(
                            episode.frameCount.toLong() + streamed.done,
                            "листы превью: кадр ${streamed.done} из ${episode.frameCount}",
                        )
                    },
                )
            requireSuccess(output, PROGRAM_NAME)
            var built = 0
            pending.forEach { sheet ->
                val produced = directory.resolve(SHEET_NAME_TEMPLATE.format(sheet.index))
                if (!Files.isRegularFile(produced)) {
                    throw IOException(
                        "внешняя программа не выдала лист ${sheet.index} эпизода ${episode.id}: " +
                            "ожидался файл ${produced.fileName}. Листов ожидалось $sheetCount",
                    )
                }
                val size = Files.size(produced)
                val checksum = checksumOf(produced)
                Files.newInputStream(produced).use { input ->
                    storage.put(sheet.temporaryKey(), input, layout.contentType, size)
                }
                storage.move(sheet.temporaryKey(), sheet.finalKey())
                artifactRegistry.registerBuilt(
                    jobId = jobId,
                    kind = ArtifactKind.PREVIEW_SHEET,
                    finalKey = sheet.finalKey(),
                    contentType = layout.contentType,
                    byteSize = size,
                    checksum = checksum,
                )
                built++
            }
            report.report(
                episode.frameCount.toLong() * PHASES,
                "листы превью: собрано $built, готово ранее $skipped, всего $sheetCount",
            )
            return SheetOutcome(built = built, skipped = skipped, expected = sheetCount)
        } finally {
            // Промежуточные файлы на диске — не результат: система хранит
            // превью в объектном хранилище, и оставленные файлы занимали бы
            // место на SSD без нужды (Д-8).
            runCatching {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
    }

    /**
     * Считает SHA-256 файла.
     *
     * @param file файл
     * @return 64 шестнадцатеричных символа в нижнем регистре
     * @throws IOException если файл не читается
     */
    private fun checksumOf(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(READ_BUFFER)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Раскладка листа из настроек фильма.
     *
     * @param columns число столбцов
     * @param rows число строк
     * @return раскладка с размерами ячейки первого среза
     */
    private fun layoutOf(
        columns: Int,
        rows: Int,
    ): PreviewLayout =
        PreviewLayout(
            columns = columns,
            rows = rows,
            cellWidth = PreviewLayout.DEFAULT_CELL_WIDTH,
            cellHeight = PreviewLayout.DEFAULT_CELL_HEIGHT,
        )

    /**
     * Читает эпизод по предмету задания.
     *
     * @param job задание
     * @return эпизод
     * @throws DomainException с кодом `NOT_FOUND`, если предмет задания не
     *   эпизод либо эпизод не зарегистрирована
     */
    private fun requireEpisode(job: Job): Episode {
        val subject = job.subject
        val episodeId = subject.identifier
        if (subject.type != SUBJECT_EPISODE || episodeId == null) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "заданию ANALYZE нужен предмет «эпизод», а у него «${subject.type}»: " +
                    "анализировать нечего",
            )
        }
        return episodeStore.find(episodeId)
            ?: throw DomainException(
                ErrorCode.NOT_FOUND,
                "эпизод $episodeId не зарегистрирована: структуру разбирать нечего",
            )
    }

    /**
     * Итог изготовления листов.
     *
     * @property built сколько листов собрано этим запуском
     * @property skipped сколько листов было готово ранее
     * @property expected сколько листов у эпизода всего
     */
    private data class SheetOutcome(
        val built: Int,
        val skipped: Int,
        val expected: Int,
    )

    companion object {
        /** Тип предмета задания для эпизода. */
        const val SUBJECT_EPISODE: String = "EPISODE"

        /** Имя программы в тексте ошибки. */
        const val PROGRAM_NAME: String = "ffmpeg"

        /**
         * Сколько проходов внешней программы делает задание.
         *
         * Общий объём работы равен числу кадров, умноженному на это число:
         * один проход детектирует границы, второй укладывает превью в
         * листы. Показывать «100 %» после одного прохода было бы враньём —
         * половина работы ещё впереди.
         */
        const val PHASES: Long = 2

        /** Шаблон имени промежуточного файла листа. */
        const val SHEET_NAME_TEMPLATE: String = "sheet-%06d.png"

        /** Буфер чтения файла листа, байт. */
        private const val READ_BUFFER: Int = 64 * 1024
    }
}

/**
 * Монотонный счётчик прогресса задания.
 *
 * Счётчик не даёт прогрессу поехать назад и не даёт ему упасть ниже уже
 * показанного оператору значения после перезапуска воркера. Это ровно то,
 * что требует контракт очереди: прерванное задание возвращается в очередь
 * **с сохранённым прогрессом**, а не начинает счёт заново (FR-003,
 * research.md Т-15).
 *
 * @property sink куда уходит отчёт о прогрессе
 * @property floor значение, ниже которого счётчик не опускается
 * @property total общий объём работы
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class MonotonicProgress(
    private val sink: (JobProgress) -> Unit,
    saved: JobProgress,
    val total: Long,
) {
    private var last: Long = minOf(saved.done, total)

    init {
        require(total >= 0L) { "Общий объём работы отрицателен: $total" }
    }

    /** Последнее показанное значение прогресса. */
    val shown: Long
        get() = last

    /**
     * Показывает прогресс, не позволяя ему уменьшиться.
     *
     * @param done сколько единиц работы сделано
     * @param note что задание делает сейчас
     */
    fun report(
        done: Long,
        note: String,
    ) {
        val bounded = done.coerceIn(0L, total)
        if (bounded <= last && last < total) {
            // Повтор того же значения: сообщать о нём второй раз незачем,
            // а уменьшение запрещено правилом монотонности.
            return
        }
        last = maxOf(last, bounded)
        sink(JobProgress.of(last, total, note))
    }

    /**
     * Показывает итоговое значение прогресса.
     *
     * @param note чем закончилась работа
     */
    fun complete(note: String) {
        last = total
        sink(JobProgress.of(total, total, note))
    }
}

/**
 * Признак успешного завершения внешней программы.
 *
 * Вынесено, чтобы проверка кода возврата была в одном месте задания: во
 * старом проекте её не проверял ни один из двенадцати вызовов ffmpeg
 * (constitution IV.2, SC-005).
 *
 * @property result результат запуска
 * @throws ExternalProgramFailed если код завершения ненулевой либо программа
 *   была прервана по таймауту
 */
@Throws(ExternalProgramFailed::class)
fun requireSuccess(
    result: ProcessResult,
    programName: String,
) {
    if (!result.isSuccess) {
        throw ExternalProgramFailed(result, programName)
    }
}
