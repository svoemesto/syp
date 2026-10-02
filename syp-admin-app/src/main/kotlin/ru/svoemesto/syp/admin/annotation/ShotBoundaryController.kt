package ru.svoemesto.syp.admin.annotation

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.analysis.LocationView
import ru.svoemesto.syp.admin.analysis.SceneView
import ru.svoemesto.syp.admin.analysis.ShotView
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.admin.analysis.toView
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.LocationStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode

/**
 * Тело запроса на сдвиг границы плана.
 *
 * Устроено так же, как тело сдвига границы сцены, и по той же причине: одного
 * кадра недостаточно, «сдвинуть границу на кадр 180» не говорит, какую из
 * сотен границ планов оператор имеет в виду.
 *
 * @property fromFrame кадр, на котором граница стоит сейчас: он же первый кадр
 *   второго из двух планов
 * @property toFrame кадр, на который границу ставят
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ShotMoveRequest(
    val fromFrame: Int,
    val toFrame: Int,
)

/**
 * Ответ на правку границы плана.
 *
 * Ответ несёт **изменённый участок** с пересчитанными размерами планов, а не
 * ссылку «перечитайте всё»: перечитывать весь эпизод из-за двух планов —
 * это megabytes ради двух строк, а оператор после правки должен видеть
 * результат немедленно. Размер приезжает вместе с границами именно потому,
 * что пересчитывается в той же операции: иначе интерфейсу пришлось бы
 * угадывать, какой размер стал правильным (`boundary-editing.md` § 7).
 *
 * @property episodeId эпизод
 * @property frame кадр, по которому выполнена операция
 * @property action вид операции: `MOVE`, `SPLIT` или `MERGE`
 * @property actionTitle вид операции словами для оператора
 * @property shots рабочие планы затронутого участка после операции
 * @property supersededShotIds строки, выведенные из рабочей структуры
 * @property scenes сцены, в которые легли затронутые планы: без них интерфейс
 *   не знает, где показывать правку
 * @property facesRebound сколько строк лица переведено на новые планы
 * @property sizesRecomputed сколько планов получило пересчитанный размер
 * @property shotsTotal сколько планов у эпизода после операции
 * @property frameCount число кадров эпизода
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ShotBoundaryView(
    val episodeId: Long,
    val frame: Int,
    val action: String,
    val actionTitle: String,
    val shots: List<ShotView>,
    val supersededShotIds: List<Long>,
    val scenes: List<SceneView>,
    val facesRebound: Int,
    val sizesRecomputed: Int,
    val shotsTotal: Int,
    val frameCount: Int,
)

/**
 * Эндпоинты доводки границы плана.
 *
 * Живут отдельным контроллером, а не рядом с чтением структуры, по решению,
 * записанному в описании [SceneBoundaryController]: чтение результата и
 * правка — разные операции, и смешивать их в одном контроллере означало бы,
 * что операция правки может случайно изменить то, что оператор в этот момент
 * смотрит.
 *
 * Все три операции **автосохраняющиеся**: отдельной кнопки «сохранить» нет, а
 * ответ приходит сразу после операции (`boundary-editing.md` § 6).
 *
 * @property editing доводка границы плана
 * @property episodeStore хранилище эпизодов
 * @property structure чтение рабочей структуры: из неё берутся сцены и
 *   счётчики в ответе
 * @property locations справочник мест действия фильма
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class ShotBoundaryController(
    private val editing: ShotBoundaryEditing,
    private val episodeStore: EpisodeStore,
    private val structure: StructureService,
    private val locations: LocationStore,
) {
    /**
     * Сдвигает границу между двумя соседними планами.
     *
     * @param episodeId идентификатор эпизода
     * @param request кадр, на котором граница стоит, и кадр, на который её
     *   ставят
     * @return изменённый участок структуры с пересчитанными размерами
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода или такой
     *   границы нет, и с кодом `CONFLICT`, если двигать некуда либо новый кадр
     *   не является границей плана или сцены
     */
    @PostMapping("/api/episodes/{episodeId}/shots/boundary/move")
    fun moveShotBoundary(
        @PathVariable episodeId: Long,
        @RequestBody request: ShotMoveRequest,
    ): ShotBoundaryView =
        view(
            episodeId,
            editing.moveShotBoundary(episodeId, request.fromFrame, request.toFrame),
        )

    /**
     * Разделяет план на два по номеру кадра.
     *
     * @param episodeId идентификатор эпизода
     * @param frame кадр: первый кадр второго из получившихся планов
     * @return изменённый участок структуры с пересчитанными размерами
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет, и с кодом
     *   `CONFLICT`, если разделять нечего
     */
    @PostMapping("/api/episodes/{episodeId}/shots/{frame}/split")
    fun splitShot(
        @PathVariable episodeId: Long,
        @PathVariable frame: Int,
    ): ShotBoundaryView = view(episodeId, editing.splitShot(episodeId, frame))

    /**
     * Объединяет план, начинающийся в указанном кадре, с предыдущим.
     *
     * @param episodeId идентификатор эпизода
     * @param frame кадр: первый кадр поглощаемого плана
     * @return изменённый участок структуры с пересчитанными размерами
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет, и с кодом
     *   `CONFLICT`, если объединять нечего
     */
    @PostMapping("/api/episodes/{episodeId}/shots/{frame}/merge")
    fun mergeShots(
        @PathVariable episodeId: Long,
        @PathVariable frame: Int,
    ): ShotBoundaryView = view(episodeId, editing.mergeShots(episodeId, frame))

    /**
     * Приводит результат операции к ответу.
     *
     * @param episodeId эпизод
     * @param outcome результат операции
     * @return ответ с изменённым участком и сценами, в которые легли планы
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    private fun view(
        episodeId: Long,
        outcome: ShotEditOutcome,
    ): ShotBoundaryView {
        val episode = requireEpisode(episodeId)
        val allScenes = structure.listScenes(episodeId).filter { !it.isStale }
        val shots = structure.listShots(episodeId)
        val scenes =
            allScenes.filter { scene ->
                outcome.affected.any { it.lastFrame >= scene.firstFrame && it.firstFrame <= scene.lastFrame }
            }
        val placeNames = locationsOf(episode, scenes.mapNotNull { it.locationId })
        return ShotBoundaryView(
            episodeId = episodeId,
            frame = outcome.frame,
            action = outcome.action.name,
            actionTitle = actionTitle(outcome.action),
            shots = outcome.affected.map { it.toView() },
            supersededShotIds = outcome.superseded.mapNotNull { it.id },
            scenes =
                scenes.map { scene ->
                    val inside = shots.filter { it.lastFrame >= scene.firstFrame && it.firstFrame <= scene.lastFrame }
                    scene.toView(inside, placeNames)
                },
            facesRebound = outcome.facesRebound,
            sizesRecomputed = outcome.sizesRecomputed,
            shotsTotal = shots.count { !it.isStale },
            frameCount = episode.frameCount,
        )
    }

    /**
     * Название вида операции словами.
     *
     * @param action вид операции
     * @return текст для оператора
     */
    private fun actionTitle(action: ShotBoundaryAction): String =
        when (action) {
            ShotBoundaryAction.MOVE -> "граница плана сдвинута"
            ShotBoundaryAction.SPLIT -> "план разделен на два"
            ShotBoundaryAction.MERGE -> "два плана объединены в один"
        }

    /**
     * Справочник мест действия по идентификаторам.
     *
     * У плана места действия нет: оно принадлежит сцене, и в ответе едет вместе
     * со сценами, в которые легли затронутые планы. Без него интерфейс не
     * смог бы подписать сцену.
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
