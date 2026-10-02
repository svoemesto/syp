package ru.svoemesto.syp.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.analysis.AnalysisEnqueuer
import ru.svoemesto.syp.admin.analysis.AnalysisRunStore
import ru.svoemesto.syp.admin.analysis.FrameSignificanceStore
import ru.svoemesto.syp.admin.analysis.RawBoundaryStore
import ru.svoemesto.syp.admin.analysis.SceneDetector
import ru.svoemesto.syp.admin.analysis.Staleness
import ru.svoemesto.syp.admin.analysis.StructureController
import ru.svoemesto.syp.admin.analysis.StructureJob
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.LocationStore
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.media.ExternalProgram
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.ObjectStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Сборка домена разметки: прогоны, структура, листы превью, устаревание.
 *
 * Всё собирается здесь, а не в классах домена: путь к `ffmpeg` и каталог
 * промежуточных файлов приходят из окружения развёртывания, а не из кода
 * (ADR-0010, ограничение 2). Путь из данных задания — это путь из
 * интернета, и оба этих адреса кода пришлось бы ещё и хранить.
 *
 * **Промежуточные файлы — не результат.** Внешняя программа раскладывает
 * листы во временный каталог на SSD, оттуда они уходят в объектное
 * хранилище и удаляются: система хранит превью в хранилище, и оставленные
 * файлы занимали бы место впустую (Д-8, FR-021).
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class AnalysisConfiguration {
    /**
     * Собирает хранилище прогонов анализа.
     *
     * @param database доступ к базе
     * @return хранилище прогонов
     */
    @Bean
    fun analysisRunStore(database: Db): AnalysisRunStore = AnalysisRunStore(database)

    /**
     * Собирает хранилище сырых границ.
     *
     * @param database доступ к базе
     * @return хранилище сырых границ
     */
    @Bean
    fun rawBoundaryStore(database: Db): RawBoundaryStore = RawBoundaryStore(database)

    /**
     * Собирает хранилище значимых кадров.
     *
     * @param database доступ к базе
     * @return хранилище значимых кадров
     */
    @Bean
    fun frameSignificanceStore(database: Db): FrameSignificanceStore = FrameSignificanceStore(database)

    /**
     * Собирает сервис рабочего структуры эпизода.
     *
     * @param database доступ к базе
     * @param runStore хранилище прогонов
     * @param boundaryStore хранилище сырых границ
     * @return сервис структуры
     */
    @Bean
    fun structureService(
        database: Db,
        runStore: AnalysisRunStore,
        boundaryStore: RawBoundaryStore,
    ): StructureService = StructureService(database, runStore, boundaryStore)

    /**
     * Собирает пометку устаревания результатов.
     *
     * @param database доступ к базе
     * @param runStore хранилище прогонов
     * @return пометка устаревания
     */
    @Bean
    fun staleness(
        database: Db,
        runStore: AnalysisRunStore,
    ): Staleness = Staleness(database, runStore)

    // Единая точка запуска внешних программ собирается один раз, в
    // CatalogConfiguration: два бина с именем `externalProgram` в одном
    // контексте не поднимаются вообще, и админка не стартует. Здесь бин
    // внедряется по типу, отдельного объявления не требуется.

    /**
     * Собирает детектор границ сцен и планов.
     *
     * @param program единая точка запуска внешних программ
     * @return детектор границ
     */
    @Bean
    fun sceneDetector(program: ExternalProgram): SceneDetector = SceneDetector(program, ffmpegPath())

    /**
     * Собирает исполнителя задания `ANALYZE`.
     *
     * @param episodeStore хранилище эпизодов
     * @param runStore хранилище прогонов
     * @param structure сервис рабочей структуры
     * @param frames хранилище значимых кадров
     * @param detector детектор границ
     * @param program единая точка запуска внешних программ
     * @param settingsStore настройки фильма
     * @param artifactRegistry реестр артефактов
     * @param staleness пометка устаревания
     * @param storage объектное хранилище
     * @return исполнитель анализа структуры
     */
    @Bean
    fun structureJob(
        episodeStore: EpisodeStore,
        runStore: AnalysisRunStore,
        structure: StructureService,
        frames: FrameSignificanceStore,
        detector: SceneDetector,
        program: ExternalProgram,
        settingsStore: MovieSettingsStore,
        artifactRegistry: ArtifactRegistry,
        staleness: Staleness,
        storage: ObjectStorage,
    ): StructureJob =
        StructureJob(
            episodeStore = episodeStore,
            runStore = runStore,
            structure = structure,
            frames = frames,
            detector = detector,
            program = program,
            ffmpegPath = ffmpegPath(),
            settingsStore = settingsStore,
            artifactRegistry = artifactRegistry,
            staleness = staleness,
            storage = storage,
            workRoot = workRoot(),
        )

    /**
     * Собирает постановщик анализа структуры.
     *
     * @param queue очередь заданий
     * @param settingsStore настройки фильма
     * @return постановщик анализа
     */
    @Bean
    fun analysisEnqueuer(
        queue: JobQueue,
        settingsStore: MovieSettingsStore,
    ): AnalysisEnqueuer = AnalysisEnqueuer(queue, settingsStore)

    /**
     * Собирает эндпоинты структуры эпизода и превью.
     *
     * @param enqueuer постановщик анализа
     * @param episodeStore хранилище эпизодов
     * @param runStore хранилище прогонов
     * @param structure сервис рабочей структуры
     * @param boundaryStore хранилище сырых границ
     * @param frameStore хранилище значимых кадров
     * @param staleness состояние актуальности результата
     * @param settingsStore настройки фильма
     * @param artifactRegistry реестр артефактов
     * @param storage объектное хранилище
     * @param locations справочник мест действия
     * @return контроллер структуры
     */
    @Bean
    fun structureController(
        enqueuer: AnalysisEnqueuer,
        episodeStore: EpisodeStore,
        runStore: AnalysisRunStore,
        structure: StructureService,
        boundaryStore: RawBoundaryStore,
        frameStore: FrameSignificanceStore,
        staleness: Staleness,
        settingsStore: MovieSettingsStore,
        artifactRegistry: ArtifactRegistry,
        storage: ObjectStorage,
        locations: LocationStore,
    ): StructureController =
        StructureController(
            enqueuer = enqueuer,
            episodeStore = episodeStore,
            runStore = runStore,
            structure = structure,
            boundaryStore = boundaryStore,
            frameStore = frameStore,
            staleness = staleness,
            settingsStore = settingsStore,
            artifactRegistry = artifactRegistry,
            storage = storage,
            locations = locations,
        )

    companion object {
        /** Имя переменной окружения с путём к программе `ffmpeg`. */
        const val ENV_FFMPEG_PATH: String = "SYP_FFMPEG_PATH"

        /** Имя переменной окружения с каталогом промежуточных файлов. */
        const val ENV_WORK_ROOT: String = "SYP_WORK_ROOT"

        /** Путь к `ffmpeg`, если переменная окружения не задана. */
        const val DEFAULT_FFMPEG_PATH: String = "ffmpeg"

        /** Каталог промежуточных файлов, если переменная окружения не задана. */
        const val DEFAULT_WORK_ROOT: String = "/data/syp-work"

        /**
         * Путь к программе `ffmpeg` из окружения.
         *
         * @return путь к программе
         */
        fun ffmpegPath(): String = System.getenv(ENV_FFMPEG_PATH)?.takeIf { it.isNotBlank() } ?: DEFAULT_FFMPEG_PATH

        /**
         * Каталог промежуточных файлов из окружения; создаётся при первом
         * обращении.
         *
         * @return путь к каталогу
         */
        fun workRoot(): Path =
            Path
                .of(System.getenv(ENV_WORK_ROOT)?.takeIf { it.isNotBlank() } ?: DEFAULT_WORK_ROOT)
                .also { Files.createDirectories(it) }
    }
}
