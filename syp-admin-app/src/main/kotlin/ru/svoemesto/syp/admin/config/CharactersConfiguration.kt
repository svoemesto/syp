package ru.svoemesto.syp.admin.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.admin.analysis.AnalysisRunStore
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.characters.FaceDetector
import ru.svoemesto.syp.admin.characters.FaceScan
import ru.svoemesto.syp.admin.characters.FacesJob
import ru.svoemesto.syp.admin.characters.PersonService
import ru.svoemesto.syp.admin.characters.StubFaceDetector
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.media.FrameChannel

/**
 * Сборка домена персонажей: проход по кадрам, задание `FACES`, персоны.
 *
 * **Детектор в этой сборке — заглушка.** Настоящий детектор работает на
 * видеокарте и требует среды исполнения, которой на машине нет: карта есть,
 * `onnxruntime`, `torch` и `nvcc` не установлены. Это задача T063, и она не
 * выполняется подменой: заглушка объявляет себя заглушкой, а задание
 * сообщает об этом в тексте результата.
 *
 * Замена заглушки на настоящий детектор — замена одного бина `FaceDetector`;
 * ни задание, ни проход по кадрам, ни очередь при этом не меняются.
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
     * @return заглушка детектора до появления среды исполнения видеокарты
     */
    @Bean
    fun faceDetector(): FaceDetector = StubFaceDetector()

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
     * @param seriesStore хранилище серий
     * @param runStore хранилище прогонов анализа
     * @param scan проход по кадрам с детектором
     * @param detector детектор лиц: его ключ попадает в прогон анализа
     * @return исполнитель задания поиска лиц
     */
    @Bean
    fun facesJob(
        seriesStore: SeriesStore,
        runStore: AnalysisRunStore,
        scan: FaceScan,
        detector: FaceDetector,
    ): FacesJob =
        FacesJob(
            seriesStore = seriesStore,
            runStore = runStore,
            scan = scan,
            detectorKey = detector.key,
        )

    /**
     * Собирает сервис персон сериала.
     *
     * @param database доступ к базе
     * @return сервис персон со служебными заглушками
     */
    @Bean
    fun personService(database: Db): PersonService = PersonService(database)
}
