package ru.svoemesto.syp.admin.characters

import org.springframework.http.HttpStatus
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
import ru.svoemesto.syp.admin.analysis.DetectionResult
import ru.svoemesto.syp.admin.catalog.ProjectSettingsStore
import ru.svoemesto.syp.admin.catalog.ProjectStore
import ru.svoemesto.syp.admin.catalog.Videofile
import ru.svoemesto.syp.admin.catalog.VideofileStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobSubject

/**
 * Запрос «записать кадр персоны».
 *
 * @property videofileId видеофайл, которому принадлежит кадр
 * @property frameNumber номер кадра
 */
data class SetPhotoRequest(
    val videofileId: Long,
    val frameNumber: Long,
)

/**
 * Ключ распознавателя для персон, заведённых руками.
 *
 * Именованная персона обязана принадлежать какому-то распознавателю — иначе
 * непонятно, кто её узнаёт. Обученной модели пока нет, а оператор называет
 * людей сам, поэтому такие персоны помечаются ключом «ручная»: они не
 * опознаны моделью, их назвал человек. Когда модель появится, она обучится
 * в том числе на этих именах.
 */
private const val MANUAL_RECOGNIZER_KEY: String = "manual"

/**
 * Запрос «назначить лица персоне».
 *
 * @property personId персона-получатель
 * @property faceIds лица, которые к ней переходят
 */
data class AssignFacesRequest(
    val personId: Long,
    val faceIds: List<Long>,
)

/**
 * Ответ на назначение лиц.
 *
 * @property facesAssigned сколько лиц переехало
 * @property personId персона-получатель
 */
data class FacesAssignedView(
    val facesAssigned: Int,
    val personId: Long,
)

/**
/**
 * Персона в ответе.
 *
 * @property id идентификатор персоны
 * @property name отображаемое имя
 * @property kind вид: именованная или служебная заглушка
 * @property isService служебная ли это персона
 * @property recognizerKey ключ класса в модели; пуст у служебных
 */

 * Запрос «завести персону».
 *
 * @property name имя персоны; вводит оператор
 */

data class CreatePersonRequest(
    val name: String,
)

data class PersonView(
    val id: Long,
    val name: String,
    val kind: String,
    val isService: Boolean,
    val recognizerKey: String?,
    /** Кадр, на котором персона видна: фото, выбранное оператором. */
    val photoVideofileId: Long? = null,
    /** Номер кадра фото. */
    val photoFrameNumber: Long? = null,
)

/**
 * Персоны фильма в ответе.
 *
 * @property projectId фильм
 * @property persons персоны: сначала служебные, затем именованные по имени
 */
data class PersonsView(
    val projectId: Long,
    val persons: List<PersonView>,
)

/**
 * Лицо в ответе.
 *
 * Время не приходит: номер кадра — единственный источник правды, а клиент
 * пересчитывает время от `time_base` эпизода (ADR-0001).
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
 * Лица эпизода в ответе.
 *
 * Список пагинируется **всегда**: на эпизоде лиц десятки тысяч, а полный
 * ответ занял бы мегабайты и положил бы вкладку оператора
 * (`admin-api.md` § 1.5).
 *
 * @property videofileId эпизод
 * @property projectId фильм-владелец: по нему клиент читает справочник персон
 * @property frameWidth ширина кадра эпизода: по ней клиент кладёт рамку на миниатюру
 * @property frameHeight высота кадра эпизода
 * @property facesTotal сколько лиц у эпизода всего
 * @property offset смещение выборки
 * @property limit размер выборки
 * @property faces лица выборки
 */
data class FacesView(
    val videofileId: Long,
    val projectId: Long,
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
 * Кластеры эпизода в ответе.
 *
 * Отдаются **без имени**: кластер, которому оператор дал имя, стал персоной
 * и в списке кластеров безымянных не показывается (FR-031).
 *
 * @property videofileId эпизод
 * @property frameWidth ширина кадра эпизода: по ней клиент кладёт рамку на миниатюру
 * @property frameHeight высота кадра эпизода
 * @property embeddingModelKey ключ модели эмбеддингов, которой получены векторы
 * @property clustersTotal сколько кластеров без имени у эпизода
 * @property facesNamed сколько лиц отнесено к именованным кластерам, то есть
 *   к персонам: без этого числа экран операций вынужден показывать «распознано
 *   неизвестно», хотя ответ уже посчитан вместе с кластерами
 * @property clusters кластеры по убыванию числа лиц
 */
data class FaceClustersView(
    val videofileId: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val embeddingModelKey: String,
    val clustersTotal: Int,
    val facesNamed: Int,
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
 * Запрос «пометить лица эталонами».
 *
 * @property faceIds идентификаторы лиц
 * @property isExample новое значение метки
 */
data class MarkExamplesRequest(
    val faceIds: List<Long>,
    val isExample: Boolean,
)

/**
 * Ответ на пометку эталонов.
 *
 * @property changed сколько лиц реально изменилось
 * @property isExample новое значение метки
 */
data class FaceExamplesMarkedView(
    val changed: Int,
    val isExample: Boolean,
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
 * @property videofileStore хранилище эпизодов
 * @property projects хранилище фильмов
 * @property settingsStore настройки фильма
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
    private val videofileStore: VideofileStore,
    private val projects: ProjectStore,
    private val settingsStore: ProjectSettingsStore,
    private val embeddingModelKey: String,
    private val queue: JobQueue,
) {
    /**
     * Ставит задание поиска лиц по эпизоду.
     *
     * Без этого эндпоинта лица было нечем запустить: задание живёт, а поставить
     * его было нечем — ни из интерфейса, ни по сети. Обнаружилось на сквозном
     * прогоне, когда детектор и очередь были готовы, а запустить не вышло.
     *
     * Повторная постановка того же задания не плодит дубликаты: очередь
     * отбрасывает совпадающее по параметрам задание эпизода, которое ещё не
     * закончено.
     *
     * @param videofileId идентификатор эпизода
     * @return номер задания и его состояние
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом `NOT_FOUND`,
     *   если эпизода нет
     */
    @PostMapping("/videofiles/{videofileId}/faces")
    fun startFaceScan(
        @PathVariable videofileId: Long,
    ): ResponseEntity<FaceScanEnqueuedView> {
        val videofile = requireVideofile(videofileId)
        val jobId =
            queue.enqueue(
                kind = JobKind.FACES,
                subject = JobSubject.videofile(videofile.id!!),
                paramsJson = """{"embeddingModelKey":"$embeddingModelKey"}""",
                paramsHash = embeddingModelKey,
                algorithmVersion = DetectionResult.ALGORITHM_VERSION,
            )
        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(FaceScanEnqueuedView(jobId = jobId, videofileId = videofile.id, embeddingModelKey = embeddingModelKey))
    }

    /**
     * Назначает лица персонам.
     *
     * Это ровно то, ради чего существует редактор лиц: оператор смотрит лица
     * персоны и переносит ошибочно попавшие к другой. Метод `assignPerson` в
     * хранилище был написан давно и наружу не выставлен — назначить лицо было
     * нечем, то есть исправить ошибку распознавания было нечем.
     *
     * @param videofileId идентификатор видеофайла
     * @param request номера персон и лиц
     * @return сколько лиц переехало
     * @throws DomainException с кодом `NOT_FOUND`, если видеофайла нет
     */
    @PatchMapping("/videofiles/{videofileId}/faces/person")
    fun assignFacesToPerson(
        @PathVariable videofileId: Long,
        @RequestBody request: AssignFacesRequest,
    ): FacesAssignedView {
        requireVideofile(videofileId)
        val changed = faces.assignPerson(request.personId, request.faceIds)
        return FacesAssignedView(facesAssigned = changed, personId = request.personId)
    }

    /**
     * Заводит персону по имени.
     *
     * Сценарий владельца: оператор даёт имя тому, кого назвал, и для нового
     * человека имя вводится здесь. Без этого персону можно было только назвать
     * из кластера, а кластеры строятся по векторам и сейчас пусты.
     *
     * @param videofileId идентификатор видеофайла
     * @param request имя персоны
     * @return созданная персона
     * @throws DomainException с кодом `BAD_REQUEST`, если имя пустое
     */
    @PostMapping("/videofiles/{videofileId}/persons")
    fun createPerson(
        @PathVariable videofileId: Long,
        @RequestBody request: CreatePersonRequest,
    ): PersonView {
        val videofile = requireVideofile(videofileId)
        val name = request.name.trim()
        if (name.isEmpty()) {
            throw DomainException(ErrorCode.BAD_REQUEST, "имя персоны не может быть пустым")
        }
        return persons.create(videofile.projectId, name, MANUAL_RECOGNIZER_KEY).toView()
    }

    /**
     * Записывает кадр, на котором персона видна.
     *
     * Фото персоны выбирает оператор из выделенных лиц, как в старом проекте: там
     * это пункт меню «Set as person picture», который писал в персону номер файла и
     * кадра. Модель распознавания для этого не нужна, поэтому фото работает уже
     * сейчас, пока группировки нет.
     *
     * @param personId персона
     * @param request видеофайл и номер кадра
     * @return обновлённая персона
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом `NOT_FOUND`,
     *   если персоны нет
     */
    @PatchMapping("/persons/{personId}/photo")
    fun setPersonPhoto(
        @PathVariable personId: Long,
        @RequestBody request: SetPhotoRequest,
    ): PersonView {
        persons.setPhoto(personId, request.videofileId, request.frameNumber)
        return persons.find(personId)?.toView()
            ?: throw DomainException(ErrorCode.NOT_FOUND, "персоны $personId нет")
    }

    /**
     * Отдаёт лица эпизода.
     *
     * @param videofileId идентификатор эпизода
     * @param offset смещение выборки
     * @param limit размер выборки
     * @return страница лиц эпизода
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    @GetMapping("/videofiles/{videofileId}/faces")
    fun readFaces(
        @PathVariable videofileId: Long,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "200") limit: Int,
    ): FacesView {
        val videofile = requireVideofile(videofileId)
        val start = offset.coerceAtLeast(0)
        val size = limit.coerceIn(1, MAX_PAGE)
        val page = faces.listByVideofile(videofileId, start, size)
        val byId = namedPersons(page.map { it.personId }.distinct())
        return FacesView(
            videofileId = videofileId,
            projectId = videofile.projectId,
            frameWidth = videofile.width,
            frameHeight = videofile.height,
            facesTotal = faces.countByVideofile(videofileId),
            offset = start,
            limit = size,
            faces = page.map { face -> face.toView(byId[face.personId]) },
        )
    }

    /**
     * Отдаёт кластеры похожих лиц эпизода, у которых ещё нет имени.
     *
     * Кластеры **вычисляются** из эмбеддингов при чтении и не хранятся: они
     * производны от векторов, и второе место, где живёт истина о похожестве
     * лиц, разошлось бы с векторами при первом же изменении.
     *
     * Лица кластера, пока у него нет имени, принадлежат служебной персоне
     * «распознано, имя не подтверждено»: «нет персоны» выражается заглушкой,
     * а не пустой ссылкой (Р-12).
     *
     * @param videofileId идентификатор эпизода
     * @return кластеры эпизода
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    @GetMapping("/videofiles/{videofileId}/faces/clusters")
    fun readClusters(
        @PathVariable videofileId: Long,
    ): FaceClustersView {
        val videofile = requireVideofile(videofileId)
        val namedKeys =
            persons.listByProject(videofile.projectId).mapNotNull { it.recognizerKey }.toSet()
        val all = clustersOf(videofileId)
        val unnamed = all.filter { it.id !in namedKeys }
        return FaceClustersView(
            videofileId = videofileId,
            frameWidth = videofile.width,
            frameHeight = videofile.height,
            embeddingModelKey = embeddingModelKey,
            clustersTotal = unnamed.size,
            facesNamed = all.filter { it.id in namedKeys }.sumOf { it.size },
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
     * @param clusterId ключ кластера из `GET /api/videofile/{videofileId}/faces/clusters`
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
            clustersOf(anchor.videofileId).firstOrNull { it.id == clusterId }
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "Лицо $anchorId ещё есть, но кластера «$clusterId» уже нет: состав лиц " +
                        "эпизода изменился, перечитайте список кластеров",
                )
        val projectId = requireVideofile(anchor.videofileId).projectId
        val person =
            persons.create(
                projectId = projectId,
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
     * Ставит или снимает метку эталона на лицах эпизода.
     *
     * Метку ставит оператор, а не алгоритм: эталон — это подтверждение
     * «этот человек известен», и проставленный автоматически эталон обучил бы
     * модель на её же предположении. Само обучение модели этими метками
     * пользуется и вынесено за пределы адреса намеренно: переобучение — это
     * отдельная долгая операция, а не правка одного лица.
     *
     * @param videofileId идентификатор эпизода
     * @param request лица и новое значение метки
     * @return сколько лиц изменилось
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     * @throws DomainException с кодом `BAD_REQUEST`, если лица не его
     * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
     */
    @PatchMapping("/videofiles/{videofileId}/faces/example")
    fun markFaceExamples(
        @PathVariable videofileId: Long,
        @RequestBody request: MarkExamplesRequest,
    ): FaceExamplesMarkedView {
        requireVideofile(videofileId)
        if (request.faceIds.isEmpty()) {
            throw DomainException(ErrorCode.BAD_REQUEST, "не выбрано ни одного лица: помечать нечего")
        }
        val mine = faces.listByIds(request.faceIds).filter { it.videofileId == videofileId }
        if (mine.size != request.faceIds.distinct().size) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "часть лиц принадлежит другому эпизоду: лица ${request.faceIds.distinct().size - mine.size} отклонены",
            )
        }
        val changed = faces.markExamples(mine.map { requireNotNull(it.id) }, request.isExample)
        return FaceExamplesMarkedView(changed = changed, isExample = request.isExample)
    }

    /**
     * Отдаёт персон фильма.
     *
     * @param projectId идентификатор фильма
     * @return персоны фильма
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     */
    @GetMapping("/projects/{projectId}/persons")
    fun readPersons(
        @PathVariable projectId: Long,
    ): PersonsView {
        requireProject(projectId)
        return PersonsView(
            projectId = projectId,
            persons = persons.listByProject(projectId).map { it.toView() },
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
     *   `CONFLICT`, если персона служебного
     */
    @DeleteMapping("/persons/{personId}")
    fun deletePerson(
        @PathVariable personId: Long,
    ): ResponseEntity<Unit> {
        persons.delete(personId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Кластеры эпизода по её эмбеддингам.
     *
     * @param videofileId эпизод
     * @return кластеры эпизода
     */
    private fun clustersOf(videofileId: Long): List<FaceCluster> =
        clustering.cluster(
            embeddings.listByVideofile(videofileId, embeddingModelKey).map { ClusterPoint(it.faceId, it.vector) },
            settingsStore.read(requireVideofile(videofileId).projectId),
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
     * Требует эпизод.
     *
     * @param videofileId идентификатор эпизода
     * @return эпизод
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    private fun requireVideofile(videofileId: Long): Videofile =
        videofileStore.find(videofileId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "Эпизод $videofileId не зарегистрирована")

    /**
     * Требует фильм.
     *
     * @param projectId идентификатор фильма
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     */
    private fun requireProject(projectId: Long) {
        if (projects.find(projectId) == null) {
            throw DomainException(ErrorCode.NOT_FOUND, "Фильм $projectId не заведён")
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
            photoVideofileId = photoVideofileId,
            photoFrameNumber = photoFrameNumber,
        )

    companion object {
        /** Префикс ключа кластера. */
        const val CLUSTER_PREFIX: String = "cl-"

        /** Верхняя граница размера выборки. */
        const val MAX_PAGE: Int = 1000
    }
}

/**
 * Ответ постановки задания поиска лиц.
 *
 * Отдаётся с кодом 202: задание принято, а не выполнено — его ход виден в
 * шапке по подписке на уведомления.
 *
 * @property jobId номер задания в очереди
 * @property videofileId эпизод, для которого задано задание
 * @property embeddingModelKey ключ модели эмбеддингов, которой считались вектора
 */
data class FaceScanEnqueuedView(
    val jobId: Long,
    val videofileId: Long?,
    val embeddingModelKey: String,
)
