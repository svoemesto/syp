package ru.svoemesto.syp.admin.characters

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.admin.catalog.MovieStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode

/**
 * Персона в ответе.
 *
 * @property id идентификатор персоны
 * @property name отображаемое имя
 * @property kind вид: именованная или служебная заглушка
 * @property isService служебная ли это персона
 * @property recognizerKey ключ класса в модели; пуст у служебных
 */
data class PersonView(
    val id: Long,
    val name: String,
    val kind: String,
    val isService: Boolean,
    val recognizerKey: String?,
)

/**
 * Персоны сериала в ответе.
 *
 * @property movieId сериал
 * @property persons персоны: сначала служебные, затем именованные по имени
 */
data class PersonsView(
    val movieId: Long,
    val persons: List<PersonView>,
)

/**
 * Лицо в ответе.
 *
 * Время не приходит: номер кадра — единственный источник правды, а клиент
 * пересчитывает время от `time_base` серии (ADR-0001).
 *
 * @property id идентификатор лица
 * @property frameNumber номер кадра
 * @property faceIndex порядковый номер лица в кадре
 * @property x1 левая граница рамки
 * @property y1 верхняя граница рамки
 * @property x2 правая граница рамки
 * @property y2 нижняя граница рамки
 * @property shotId план, которому принадлежит лицо, либо `null`
 * @property personId персона лица; непустая всегда (Р-12)
 * @property personName отображаемое имя персоны
 * @property personKind вид персоны
 * @property origin происхождение рамки: `AUTO` или `OPERATOR`
 * @property isExample помечено ли лицо эталоном для обучения
 * @property detectConfidence уверенность детектора либо `null`
 */
data class FaceView(
    val id: Long,
    val frameNumber: Int,
    val faceIndex: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val shotId: Long?,
    val personId: Long,
    val personName: String,
    val personKind: String,
    val origin: String,
    val isExample: Boolean,
    val detectConfidence: Double?,
)

/**
 * Лица серии в ответе.
 *
 * Список пагинируется **всегда**: на серии лиц десятки тысяч, а полный
 * ответ занял бы мегабайты и положил бы вкладку оператора
 * (`admin-api.md` § 1.5).
 *
 * @property episodeId серия
 * @property movieId сериал-владелец: по нему клиент читает справочник персон
 * @property frameWidth ширина кадра серии: по ней клиент кладёт рамку на миниатюру
 * @property frameHeight высота кадра серии
 * @property facesTotal сколько лиц у серии всего
 * @property offset смещение выборки
 * @property limit размер выборки
 * @property faces лица выборки
 */
data class FacesView(
    val episodeId: Long,
    val movieId: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val facesTotal: Int,
    val offset: Int,
    val limit: Int,
    val faces: List<FaceView>,
)

/**
 * Кластер похожих лиц в ответе.
 *
 * @property id ключ кластера; его же принимает `POST /api/clusters/{id}/person`
 * @property size сколько лиц в кластере
 * @property faceIds идентификаторы лиц кластера
 * @property thumbnailFaceId лицо, рамка которого показывается картинкой
 */
data class FaceClusterView(
    val id: String,
    val size: Int,
    val faceIds: List<Long>,
    val thumbnailFaceId: Long,
)

/**
 * Кластеры серии в ответе.
 *
 * Отдаются **без имени**: кластер, которому оператор дал имя, стал персоной
 * и в списке кластеров безымянных не показывается (FR-031).
 *
 * @property episodeId серия
 * @property frameWidth ширина кадра серии: по ней клиент кладёт рамку на миниатюру
 * @property frameHeight высота кадра серии
 * @property embeddingModelKey ключ модели эмбеддингов, которой получены векторы
 * @property clustersTotal сколько кластеров без имени у серии
 * @property clusters кластеры по убыванию числа лиц
 */
data class FaceClustersView(
    val episodeId: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val embeddingModelKey: String,
    val clustersTotal: Int,
    val clusters: List<FaceClusterView>,
)

/**
 * Ответ на постановку имени кластеру.
 *
 * @property personId созданная персона
 * @property name отображаемое имя
 * @property recognizerKey ключ класса в модели
 * @property facesAssigned сколько лиц переведено этой персоне
 */
data class ClusterNamedView(
    val personId: Long,
    val name: String,
    val recognizerKey: String,
    val facesAssigned: Int,
)

/**
 * Запрос «дать кластеру имя».
 *
 * @property name отображаемое имя персоны
 * @property recognizerKey ключ класса в модели; если не задан, берётся ключ
 *   кластера — идентичность персоны не зависит от отображаемого имени
 *   (ADR-0004, инвариант 5 домена персонажей)
 */
data class NameClusterRequest(
    val name: String,
    val recognizerKey: String? = null,
)

/**
 * Запрос «переименовать персону».
 *
 * @property name новое отображаемое имя
 */
data class RenamePersonRequest(
    val name: String,
)

/**
 * Эндпоинты лиц, кластеров и персон.
 *
 * Это чтение того, что нашла детекция, и те две операции, без которых экран
 * лиц бессмыслен: дать кластеру имя и убрать ошибочную персону.
 *
 * Обе операции меняют только **справочник имён**. Рамки, эмбеддинги и
 * результаты детекции не трогаются: переименование персоны не ломает модель
 * (ADR-0004), а удаление персоны переводит её лица в неопознанных, а не
 * стирает (FR-036, SC-006).
 *
 * @property faces хранилище лиц
 * @property embeddings хранилище эмбеддингов
 * @property clustering кластеризация на холодном старте
 * @property persons сервис персон
 * @property episodeStore хранилище серий
 * @property movies хранилище сериалов
 * @property settingsStore настройки сериала
 * @property embeddingModelKey ключ модели эмбеддингов из конфигурации развёртывания
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
@RequestMapping("/api")
class CharactersController(
    private val faces: FaceStore,
    private val embeddings: FaceEmbeddingStore,
    private val clustering: Clustering,
    private val persons: PersonService,
    private val episodeStore: EpisodeStore,
    private val movies: MovieStore,
    private val settingsStore: MovieSettingsStore,
    private val embeddingModelKey: String,
) {
    /**
     * Отдаёт лица серии.
     *
     * @param episodeId идентификатор серии
     * @param offset смещение выборки
     * @param limit размер выборки
     * @return страница лиц серии
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    @GetMapping("/episode/{episodeId}/faces")
    fun readFaces(
        @PathVariable episodeId: Long,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "200") limit: Int,
    ): FacesView {
        val episode = requireEpisode(episodeId)
        val start = offset.coerceAtLeast(0)
        val size = limit.coerceIn(1, MAX_PAGE)
        val page = faces.listByEpisode(episodeId, start, size)
        val byId = namedPersons(page.map { it.personId }.distinct())
        return FacesView(
            episodeId = episodeId,
            movieId = episode.movieId,
            frameWidth = episode.width,
            frameHeight = episode.height,
            facesTotal = faces.countByEpisode(episodeId),
            offset = start,
            limit = size,
            faces = page.map { face -> face.toView(byId[face.personId]) },
        )
    }

    /**
     * Отдаёт кластеры похожих лиц серии, у которых ещё нет имени.
     *
     * Кластеры **вычисляются** из эмбеддингов при чтении и не хранятся: они
     * производны от векторов, и второе место, где живёт истина о похожестве
     * лиц, разошлось бы с векторами при первом же изменении.
     *
     * Лица кластера, пока у него нет имени, принадлежат служебной персоне
     * «распознано, имя не подтверждено»: «нет персоны» выражается заглушкой,
     * а не пустой ссылкой (Р-12).
     *
     * @param episodeId идентификатор серии
     * @return кластеры серии
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    @GetMapping("/episode/{episodeId}/faces/clusters")
    fun readClusters(
        @PathVariable episodeId: Long,
    ): FaceClustersView {
        val episode = requireEpisode(episodeId)
        val namedKeys =
            persons.listByMovie(episode.movieId).mapNotNull { it.recognizerKey }.toSet()
        val unnamed = clustersOf(episodeId).filter { it.id !in namedKeys }
        return FaceClustersView(
            episodeId = episodeId,
            frameWidth = episode.width,
            frameHeight = episode.height,
            embeddingModelKey = embeddingModelKey,
            clustersTotal = unnamed.size,
            clusters =
                unnamed.map { cluster ->
                    FaceClusterView(
                        id = cluster.id,
                        size = cluster.size,
                        faceIds = cluster.faceIds,
                        thumbnailFaceId = cluster.faceIds.min(),
                    )
                },
        )
    }

    /**
     * Даёт кластеру имя: заводит персону и назначает её лицам кластера.
     *
     * Создание персоны и назначение лиц идут в двух транзакциях — так устроен
     * сервис персон. Чтобы промежуточного состояния не осталось, неудачное
     * назначение **откатывает создание**: персону без единого лица оператор
     * увидел бы в справочнике и удалил руками, не понимая, откуда она взялась.
     * Откат не отменяет исходной ошибки — она и есть причина отказа.
     *
     * @param clusterId ключ кластера из `GET /api/episode/{episodeId}/faces/clusters`
     * @param request имя персоны
     * @return созданная персона с числом назначенных лиц
     * @throws DomainException с кодом `NOT_FOUND`, если кластера нет; с кодом
     *   `CONFLICT`, если имя занято
     */
    @PostMapping("/clusters/{clusterId}/person")
    fun nameCluster(
        @PathVariable clusterId: String,
        @RequestBody request: NameClusterRequest,
    ): ClusterNamedView {
        val anchorId = minFaceIdOf(clusterId)
        val anchor =
            faces.find(anchorId)
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "Кластера «$clusterId» нет: он вычисляется из эмбеддингов, " +
                        "и ни одного его лица не осталось",
                )
        val cluster =
            clustersOf(anchor.episodeId).firstOrNull { it.id == clusterId }
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "Лицо $anchorId ещё есть, но кластера «$clusterId» уже нет: состав лиц " +
                        "серии изменился, перечитайте список кластеров",
                )
        val movieId = requireEpisode(anchor.episodeId).movieId
        val person =
            persons.create(
                movieId = movieId,
                name = request.name,
                recognizerKey = request.recognizerKey?.takeIf { it.isNotBlank() } ?: clusterId,
            )
        val personId = requireNotNull(person.id)
        val assigned =
            try {
                faces.assignPerson(personId, cluster.faceIds)
            } catch (failure: Throwable) {
                runCatching { persons.delete(personId) }
                throw failure
            }
        return ClusterNamedView(
            personId = personId,
            name = person.name,
            recognizerKey = person.recognizerKey!!,
            facesAssigned = assigned,
        )
    }

    /**
     * Отдаёт персон сериала.
     *
     * @param movieId идентификатор сериала
     * @return персоны сериала
     * @throws DomainException с кодом `NOT_FOUND`, если сериала нет
     */
    @GetMapping("/serials/{movieId}/persons")
    fun readPersons(
        @PathVariable movieId: Long,
    ): PersonsView {
        requireMovie(movieId)
        return PersonsView(
            movieId = movieId,
            persons = persons.listByMovie(movieId).map { it.toView() },
        )
    }

    /**
     * Переименовывает именованную персону.
     *
     * Переименование не ломает модель: модель знает ключ распознавателя, а
     * не имя (ADR-0004, инвариант 5 домена персонажей).
     *
     * @param personId идентификатор персоны
     * @param request новое имя
     * @return переименованная персона
     * @throws DomainException с кодом `NOT_FOUND`, если персоны нет; с кодом
     *   `CONFLICT`, если персона служебная или имя занято
     */
    @PatchMapping("/persons/{personId}")
    fun renamePerson(
        @PathVariable personId: Long,
        @RequestBody request: RenamePersonRequest,
    ): PersonView = persons.rename(personId, request.name).toView()

    /**
     * Удаляет именованную персону, переводя её лица в неопознанные.
     *
     * Лица при этом **не удаляются** — они переходят к служебной персоне
     * «распознано, имя не подтверждено» в той же транзакции (FR-036,
     * SC-006).
     *
     * @param personId идентификатор персоны
     * @return `204`, если персона удалена
     * @throws DomainException с кодом `NOT_FOUND`, если персоны нет; с кодом
     *   `CONFLICT`, если персона служебная
     */
    @DeleteMapping("/persons/{personId}")
    fun deletePerson(
        @PathVariable personId: Long,
    ): ResponseEntity<Unit> {
        persons.delete(personId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Кластеры серии по её эмбеддингам.
     *
     * @param episodeId серия
     * @return кластеры серии
     */
    private fun clustersOf(episodeId: Long): List<FaceCluster> =
        clustering.cluster(
            embeddings.listByEpisode(episodeId, embeddingModelKey).map { ClusterPoint(it.faceId, it.vector) },
            settingsStore.read(requireEpisode(episodeId).movieId),
        )

    /**
     * Читает персон по списку ссылок.
     *
     * @param personIds идентификаторы персон
     * @return отображение «идентификатор → персона»; недостающие в него не попадают
     */
    private fun namedPersons(personIds: List<Long>): Map<Long, Person> =
        personIds
            .mapNotNull { persons.find(it) }
            .associateBy { requireNotNull(it.id) }

    /**
     * Разбирает ключ кластера в идентификатор опорного лица.
     *
     * @param clusterId ключ кластера
     * @return идентификатор лица, по которому кластер опознаётся
     * @throws DomainException с кодом `BAD_REQUEST`, если ключ не разбирается
     */
    private fun minFaceIdOf(clusterId: String): Long =
        clusterId.removePrefix(CLUSTER_PREFIX).toLongOrNull()
            ?: throw DomainException(
                ErrorCode.BAD_REQUEST,
                "Ключ кластера «$clusterId» не разбирается: ожидается «$CLUSTER_PREFIX<идентификатор лица»",
            )

    /**
     * Требует серию.
     *
     * @param episodeId идентификатор серии
     * @return серия
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    private fun requireEpisode(episodeId: Long): Episode =
        episodeStore.find(episodeId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "Серия $episodeId не зарегистрирована")

    /**
     * Требует сериал.
     *
     * @param movieId идентификатор сериала
     * @throws DomainException с кодом `NOT_FOUND`, если сериала нет
     */
    private fun requireMovie(movieId: Long) {
        if (movies.find(movieId) == null) {
            throw DomainException(ErrorCode.NOT_FOUND, "Сериал $movieId не заведён")
        }
    }

    /**
     * Строит представление лица.
     *
     * @param person персона лица либо `null`, если ссылка не читается
     * @return представление лица
     */
    private fun Face.toView(person: Person?): FaceView =
        FaceView(
            id = id!!,
            frameNumber = frameNumber,
            faceIndex = faceIndex,
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            shotId = shotId,
            personId = personId,
            personName = person?.name ?: PersonService.UNRECOGNIZED_NAME,
            personKind = person?.kind?.name ?: PersonKind.UNRECOGNIZED.name,
            origin = origin.name,
            isExample = isExample,
            detectConfidence = detectConfidence,
        )

    /**
     * Строит представление персоны.
     *
     * @return представление персоны
     */
    private fun Person.toView(): PersonView =
        PersonView(
            id = id!!,
            name = name,
            kind = kind.name,
            isService = kind.isService,
            recognizerKey = recognizerKey,
        )

    companion object {
        /** Префикс ключа кластера. */
        const val CLUSTER_PREFIX: String = "cl-"

        /** Верхняя граница размера выборки. */
        const val MAX_PAGE: Int = 1000
    }
}
