package ru.svoemesto.syp.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.analysis.AnalysisRunStore
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.admin.characters.CharactersController
import ru.svoemesto.syp.admin.characters.Clustering
import ru.svoemesto.syp.admin.characters.FaceDetector
import ru.svoemesto.syp.admin.characters.FaceEmbeddingStore
import ru.svoemesto.syp.admin.characters.FacePlanBinding
import ru.svoemesto.syp.admin.characters.FaceScan
import ru.svoemesto.syp.admin.characters.FaceSinkFactory
import ru.svoemesto.syp.admin.characters.FaceStore
import ru.svoemesto.syp.admin.characters.FacesJob
import ru.svoemesto.syp.admin.characters.GpuFaceDetector
import ru.svoemesto.syp.admin.characters.NonPersonFilter
import ru.svoemesto.syp.admin.characters.PersonService
import ru.svoemesto.syp.admin.characters.StubFaceDetector
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.FrameChannel

/**
 * Сборка домена персонажей: проход по кадрам, задание `FACES`, персоны.
 *
 * **Детектор выбирается конфигурацией развёртывания, а не кодом.** Если задан
 * путь к программе детектора и путь к модели, поднимается детектор на
 * видеокарте; если путь не задан — заглушка, которая объявляет себя
 * заглушкой и лица не ищет. Подмена одного другим молча невозможна: у
 * заглушки в прогоне анализа стоит её ключ (FR-090, Р-10).
 *
 * Среда исполнения детектора живёт **внутри образа бэкенда**: отдельного
 * контейнера детекции нет и быть не должно, контейнеров стека ровно шесть
 * (research.md Т-01, Т-20). Путь к программе и к модели приходит из
 * окружения: путь из данных задания — это путь из интернета в командную
 * строку (ADR-0010, ограничение 2). Секретов в этих значениях нет, версии
 * среды и модели зафиксированы в образе.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class CharactersConfiguration {
    /**
     * Собирает канал сырых кадров.
     *
     * @return канал кадров с путём к декодеру из окружения развёртывания
     */
    @Bean
    fun frameChannel(): FrameChannel = FrameChannel(AnalysisConfiguration.ffmpegPath())

    /**
     * Собирает детектор лиц.
     *
     * @return детектор на видеокарте, если путь к программе задан
     *   конфигурацией развёртывания; иначе заглушка
     */
    @Bean
    fun faceDetector(): FaceDetector {
        val program = detectorProgram()
        if (program.isNullOrBlank()) {
            return StubFaceDetector()
        }
        val model =
            detectorModel()
                ?: throw IllegalStateException(
                    "Задан путь к программе детектора «$program», но не задан путь к модели " +
                        "(${ENV_FACE_MODEL_PATH}). Детектор без модели не поднимается: " +
                        "либо задайте обе переменные, либо не задавайте ни одну",
                )
        return GpuFaceDetector(
            program = program,
            modelPath = model,
            modelKind = env(ENV_FACE_MODEL_KIND, GpuFaceDetector.DEFAULT_MODEL_KIND),
            provider = env(ENV_FACE_PROVIDER, GpuFaceDetector.DEFAULT_PROVIDER),
            inputWidth = env(ENV_FACE_INPUT_WIDTH, GpuFaceDetector.DEFAULT_INPUT_SIZE.toString()).toInt(),
            inputHeight = env(ENV_FACE_INPUT_HEIGHT, GpuFaceDetector.DEFAULT_INPUT_SIZE.toString()).toInt(),
            scoreThreshold = env(ENV_FACE_SCORE_THRESHOLD, GpuFaceDetector.DEFAULT_SCORE_THRESHOLD.toString()).toDouble(),
            nmsThreshold = env(ENV_FACE_NMS_THRESHOLD, GpuFaceDetector.DEFAULT_NMS_THRESHOLD.toString()).toDouble(),
        )
    }

    /**
     * Собирает проход по кадрам с детектором.
     *
     * @param channel канал сырых кадров
     * @param detector детектор лиц
     * @return проход по кадрам
     */
    @Bean
    fun faceScan(
        channel: FrameChannel,
        detector: FaceDetector,
    ): FaceScan = FaceScan(channel, detector)

    /**
     * Собирает исполнителя задания `FACES`.
     *
     * @param episodeStore хранилище серий
     * @param runStore хранилище прогонов анализа
     * @param scan проход по кадрам с детектором
     * @param detector детектор лиц: его ключ попадает в прогон анализа
     * @return исполнитель задания поиска лиц
     */
    @Bean
    fun facesJob(
        episodeStore: EpisodeStore,
        runStore: AnalysisRunStore,
        scan: FaceScan,
        detector: FaceDetector,
        faceSinks: FaceSinkFactory,
        settingsStore: MovieSettingsStore,
    ): FacesJob =
        FacesJob(
            episodeStore = episodeStore,
            runStore = runStore,
            scan = scan,
            detectorKey = detector.key,
            faceSinks = faceSinks,
            settingsStore = settingsStore,
        )

    /**
     * Собирает сервис персон сериала.
     *
     * @param database доступ к базе
     * @return сервис персон со служебными заглушками
     */
    @Bean
    fun personService(database: Db): PersonService = PersonService(database)

    /**
     * Собирает хранилище лиц.
     *
     * @param database доступ к базе
     * @return хранилище лиц серии
     */
    @Bean
    fun faceStore(database: Db): FaceStore = FaceStore(database)

    /**
     * Собирает отбрасывание рамок, которые лицом не являются.
     *
     * @param database доступ к базе
     * @param personService сервис персон: он даёт служебную персону «не лицо»
     * @return фильтр нелицевых рамок
     */
    @Bean
    fun nonPersonFilter(
        database: Db,
        personService: PersonService,
    ): NonPersonFilter = NonPersonFilter(database, personService)

    /**
     * Собирает сборку приёмника рамок.
     *
     * @param faceStore хранилище лиц
     * @param personService сервис персон
     * @param nonPersonFilter отбрасывание рамок, которые лицом не являются
     * @return сборка приёмника рамок для серии
     */
    @Bean
    fun faceSinkFactory(
        faceStore: FaceStore,
        personService: PersonService,
        nonPersonFilter: NonPersonFilter,
    ): FaceSinkFactory = FaceSinkFactory(faceStore, personService, nonPersonFilter)

    /**
     * Собирает хранилище эмбеддингов лиц.
     *
     * @param database доступ к базе
     * @return хранилище эмбеддингов
     */
    @Bean
    fun faceEmbeddingStore(database: Db): FaceEmbeddingStore = FaceEmbeddingStore(database)

    /**
     * Собирает пересчёт принадлежности лиц планам.
     *
     * @param database доступ к базе
     * @return пересчёт принадлежности лиц планам
     */
    @Bean
    fun facePlanBinding(database: Db): FacePlanBinding = FacePlanBinding(database)

    /**
     * Собирает кластеризацию лиц на холодном старте.
     *
     * Кластеризация — чистая функция от векторов и настроек сериала, поэтому
     * бином является без состояния: настройки приходят аргументом, и смена
     * порога замером М-08 не требует ни правки кода, ни перезапуска.
     *
     * @return кластеризация лиц
     */
    @Bean
    fun clustering(): Clustering = Clustering()

    /**
     * Собирает эндпоинты лиц, кластеров и персон.
     *
     * @param faceStore хранилище лиц
     * @param embeddingStore хранилище эмбеддингов
     * @param clustering кластеризация лиц
     * @param personService сервис персон
     * @param episodeStore хранилище серий
     * @param movieStore хранилище сериалов
     * @param settingsStore настройки сериала
     * @return контроллер домена персонажей
     */
    @Bean
    fun charactersController(
        faceStore: FaceStore,
        embeddingStore: FaceEmbeddingStore,
        clustering: Clustering,
        personService: PersonService,
        episodeStore: EpisodeStore,
        movieStore: MovieStore,
        settingsStore: MovieSettingsStore,
    ): CharactersController =
        CharactersController(
            faces = faceStore,
            embeddings = embeddingStore,
            clustering = clustering,
            persons = personService,
            episodeStore = episodeStore,
            movies = movieStore,
            settingsStore = settingsStore,
            embeddingModelKey = env(ENV_FACE_EMBEDDING_MODEL_KEY, DEFAULT_FACE_EMBEDDING_MODEL_KEY),
        )

    companion object {
        /** Имя переменной окружения с путём к программе детектора лиц. */
        const val ENV_FACE_DETECTOR_PATH: String = "SYP_FACE_DETECTOR_PATH"

        /** Имя переменной окружения с путём к файлу модели детектора. */
        const val ENV_FACE_MODEL_PATH: String = "SYP_FACE_MODEL_PATH"

        /**
         * Имя переменной окружения с видом модели детектора.
         *
         * Вид модели — то, как программа готовит кадр и разбирает выход
         * сети. Он приходит из конфигурации развёртывания вместе с путём к
         * файлу веса: смена модели не должна требовать правки кода и
         * пересборки образа (ADR-0010, ограничение 2).
         */
        const val ENV_FACE_MODEL_KIND: String = "SYP_FACE_MODEL_KIND"

        /** Имя переменной окружения с провайдером вычислений. */
        const val ENV_FACE_PROVIDER: String = "SYP_FACE_PROVIDER"

        /** Имя переменной окружения с шириной входа сети детектора. */
        const val ENV_FACE_INPUT_WIDTH: String = "SYP_FACE_INPUT_WIDTH"

        /** Имя переменной окружения с высотой входа сети детектора. */
        const val ENV_FACE_INPUT_HEIGHT: String = "SYP_FACE_INPUT_HEIGHT"

        /** Имя переменной окружения с порогом уверенности детектора. */
        const val ENV_FACE_SCORE_THRESHOLD: String = "SYP_FACE_SCORE_THRESHOLD"

        /** Имя переменной окружения с порогом перекрытия рамок. */
        const val ENV_FACE_NMS_THRESHOLD: String = "SYP_FACE_NMS_THRESHOLD"

        /**
         * Имя переменной окружения с ключом модели эмбеддингов.
         *
         * Ключ обязателен: векторы разных моделей несравнимы, и чтение без
         * него вернуло бы смесь векторов разных моделей под видом одной
         * (Р-09). Модель эмбеддингов выбирается замером М-02, задача T076;
         * до её закрытия в образе эмбеддингов нет.
         */
        const val ENV_FACE_EMBEDDING_MODEL_KEY: String = "SYP_FACE_EMBEDDING_MODEL_KEY"

        /**
         * Ключ модели эмбеддингов по умолчанию.
         *
         * Значение прямо говорит, что векторов в базе не лежит: без модели
         * эмбеддингов кластеры пусты, и оператор видит «лица без кластеров»
         * вместо «детектор ещё не умеет считать похожесть».
         */
        const val DEFAULT_FACE_EMBEDDING_MODEL_KEY: String = "none-yet"

        /**
         * Путь к программе детектора из окружения развёртывания.
         *
         * Пустое значение означает «детектора нет»: собирается заглушка, и
         * прогон анализа честно называет её заглушкой.
         *
         * @return путь к программе либо `null`
         */
        fun detectorProgram(): String? = env(ENV_FACE_DETECTOR_PATH, "").ifBlank { null }

        /**
         * Путь к модели детектора из окружения развёртывания.
         *
         * @return путь к модели либо `null`
         */
        fun detectorModel(): String? = env(ENV_FACE_MODEL_PATH, "").ifBlank { null }

        /**
         * Читает переменную окружения со значением по умолчанию.
         *
         * @param name имя переменной
         * @param default значение по умолчанию
         * @return значение переменной либо значение по умолчанию
         */
        private fun env(
            name: String,
            default: String,
        ): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
