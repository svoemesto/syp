package ru.svoemesto.syp.admin.annotation

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.analysis.LocationView
import ru.svoemesto.syp.admin.analysis.SceneView
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.admin.analysis.toView
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.LocationStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode

/**
 * Тело запроса на сдвиг границы сцены.
 *
 * Два кадра, а не один: границу надо назвать и там, где она стоит, и там, куда
 * её ставят. Одного кадра не хватает — «сдвинуть границу на кадр 180» не
 * говорит, какую из сотен границ эпизода оператор имеет в виду, а перебрать их
 * в цикле значило бы угадывать за него.
 *
 * Разделение и объединение кадра в пути не несут, потому что там кадр один и
 * он назван прямо в адресе, как и у остальных механик раздела 7 контракта.
 *
 * @property fromFrame кадр, на котором граница стоит сейчас: он же первый кадр
 *   второй из двух сцен
 * @property toFrame кадр, на который границу ставят
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SceneMoveRequest(
    val fromFrame: Int,
    val toFrame: Int,
)

/**
 * Ответ на правку границы сцены.
 *
 * Ответ несёт **изменённый участок**, а не ссылку «перечитайте всё»:
 * перечитывать весь эпизод из-за двух сцен — это megabytes ради двух строк, а
 * оператор после правки должен видеть результат немедленно
 * (`boundary-editing.md` § 7).
 *
 * @property episodeId эпизод
 * @property frame кадр, по которому выполнена операция
 * @property action вид операции словами: `MOVE`, `SPLIT` или `MERGE`
 * @property actionTitle вид операции словами для оператора
 * @property scenes рабочие сцены затронутого участка после операции
 * @property supersededSceneIds строки, выведенные из рабочей структуры
 * @property scenesTotal сколько рабочих сцен у эпизода после операции
 * @property shotsTotal сколько планов у эпизода
 * @property frameCount число кадров эпизода
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SceneBoundaryView(
    val episodeId: Long,
    val frame: Int,
    val action: String,
    val actionTitle: String,
    val scenes: List<SceneView>,
    val supersededSceneIds: List<Long>,
    val scenesTotal: Int,
    val shotsTotal: Int,
    val frameCount: Int,
)

/**
 * Эндпоинты доводки границы сцены.
 *
 * Живут отдельным контроллером, а не в `StructureController`, по решению,
 * записанному в его описании: чтение результата и правка — разные операции, и
 * смешивать их в одном контроллере означало бы, что операция правки может
 * случайно изменить то, что оператор в этот момент смотрит.
 *
 * Все три операции **автосохраняющиеся**: отдельной кнопки «сохранить» нет, а
 * ответ приходит сразу после операции (`boundary-editing.md` § 6).
 *
 * @property editing доводка границы
 * @property episodeStore хранилище эпизодов
 * @property structure чтение рабочей структуры: из неё берутся планы сцен и
 *   счётчики в ответе
 * @property locations справочник мест действия фильма
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class SceneBoundaryController(
    private val editing: BoundaryEditing,
    private val episodeStore: EpisodeStore,
    private val structure: StructureService,
    private val locations: LocationStore,
) {
    /**
     * Сдвигает границу между двумя соседними сценами.
     *
     * @param episodeId идентификатор эпизода
     * @param request кадр, на котором граница стоит, и кадр, на который её
     *   ставят
     * @return изменённый участок структуры
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода или такой
     *   границы нет, и с кодом `CONFLICT`, если двигать некуда
     */
    @PostMapping("/api/episodes/{episodeId}/scenes/boundary/move")
    fun moveSceneBoundary(
        @PathVariable episodeId: Long,
        @RequestBody request: SceneMoveRequest,
    ): SceneBoundaryView =
        view(
            episodeId,
            editing.moveSceneBoundary(episodeId, request.fromFrame, request.toFrame),
        )

    /**
     * Разделяет сцену на две по номеру кадра.
     *
     * @param episodeId идентификатор эпизода
     * @param frame кадр: первый кадр второй из получившихся сцен
     * @return изменённый участок структуры
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет, и с кодом
     *   `CONFLICT`, если разделять нечего
     */
    @PostMapping("/api/episodes/{episodeId}/scenes/{frame}/split")
    fun splitScene(
        @PathVariable episodeId: Long,
        @PathVariable frame: Int,
    ): SceneBoundaryView = view(episodeId, editing.splitScene(episodeId, frame))

    /**
     * Объединяет сцену, начинающуюся в указанном кадре, с предыдущей.
     *
     * @param episodeId идентификатор эпизода
     * @param frame кадр: первый кадр поглощаемой сцены
     * @return изменённый участок структуры
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет, и с кодом
     *   `CONFLICT`, если объединять нечего
     */
    @PostMapping("/api/episodes/{episodeId}/scenes/{frame}/merge")
    fun mergeScenes(
        @PathVariable episodeId: Long,
        @PathVariable frame: Int,
    ): SceneBoundaryView = view(episodeId, editing.mergeScenes(episodeId, frame))

    /**
     * Приводит результат операции к ответу.
     *
     * @param episodeId идентификатор эпизода
     * @param outcome результат операции
     * @return ответ с изменённым участком и планами
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    private fun view(
        episodeId: Long,
        outcome: SceneEditOutcome,
    ): SceneBoundaryView {
        val episode = requireEpisode(episodeId)
        val shots = structure.listShots(episodeId)
        val working = structure.listScenes(episodeId).filter { !it.isStale }
        val placeNames =
            locationsOf(episode, outcome.affected.mapNotNull { it.locationId } + outcome.superseded.mapNotNull { it.locationId })
        return SceneBoundaryView(
            episodeId = episodeId,
            frame = outcome.frame,
            action = outcome.action.name,
            actionTitle = actionTitle(outcome.action),
            scenes = outcome.affected.map { it.toView(structure.shotsInside(it, shots), placeNames) },
            supersededSceneIds = outcome.superseded.mapNotNull { it.id },
            scenesTotal = working.size,
            shotsTotal = shots.size,
            frameCount = episode.frameCount,
        )
    }

    /**
     * Название вида операции словами.
     *
     * @param action вид операции
     * @return текст для оператора
     */
    private fun actionTitle(action: SceneBoundaryAction): String =
        when (action) {
            SceneBoundaryAction.MOVE -> "граница сцены сдвинута"
            SceneBoundaryAction.SPLIT -> "сцена разделена на две"
            SceneBoundaryAction.MERGE -> "две сцены объединены в одну"
        }

    /**
     * Справочник мест действия по идентификаторам.
     *
     * @param episode эпизод: из него берётся фильм-владелец локаций
     * @param ids идентификаторы локаций, которые нужны в ответе
     * @return описания локаций по идентификаторам
     */
    private fun locationsOf(
        episode: Episode,
        ids: List<Long>,
    ): Map<Long, LocationView> {
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val wanted = ids.toSet()
        return locations
            .listByMovie(episode.movieId)
            .filter { it.id in wanted }
            .associate { it.id!! to it.toView() }
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
            ?: throw DomainException(ErrorCode.NOT_FOUND, "эпизод $episodeId не зарегистрирован")
}
