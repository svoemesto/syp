package ru.svoemesto.syp.admin.analysis

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.catalog.Location
import ru.svoemesto.syp.admin.catalog.LocationStore
import ru.svoemesto.syp.admin.catalog.SerialSetting
import ru.svoemesto.syp.admin.catalog.SerialSettings
import ru.svoemesto.syp.admin.catalog.SerialSettingsStore
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.images.PreviewLayout
import ru.svoemesto.syp.core.images.PreviewSheet
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobQueue
import ru.svoemesto.syp.core.jobs.JobState
import ru.svoemesto.syp.core.jobs.JobSubject
import ru.svoemesto.syp.core.storage.ArtifactKind
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import ru.svoemesto.syp.core.storage.ObjectStorage

/**
 * Место действия в ответе.
 *
 * @property id идентификатор локации
 * @property name отображаемое имя локации
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class LocationView(
    val id: Long,
    val name: String,
)

/**
 * План в ответе структуры серии.
 *
 * Поля совпадают с примером контракта
 * [`admin-api.md`](../../../../../specs/001-first-vertical-slice/contracts/admin-api.md)
 * § 5.1: границы по номерам кадров, происхождение границы и размера, признак
 * устаревания. Время не приходит: номер кадра — единственный источник правды,
 * а клиент пересчитывает время от `time_base` серии (ADR-0001).
 *
 * @property id идентификатор плана
 * @property firstFrame первый кадр плана
 * @property lastFrame последний кадр плана
 * @property size размер плана
 * @property sizeOrigin происхождение размера
 * @property origin происхождение границы
 * @property isStale результат помечен устаревшим
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ShotView(
    val id: Long,
    val firstFrame: Int,
    val lastFrame: Int,
    val size: String,
    val sizeOrigin: String,
    val origin: String,
    val isStale: Boolean,
)

/**
 * Сцена в ответе структуры серии.
 *
 * @property id идентификатор сцены
 * @property firstFrame первый кадр сцены
 * @property lastFrame последний кадр сцены
 * @property origin происхождение границы
 * @property location место действия либо `null`, если не назначено
 * @property isStale результат помечен устаревшим
 * @property shots планы, лежащие в сцене целиком: связь вычисляется по
 *   диапазонам кадров и не хранится (ADR-0007)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SceneView(
    val id: Long,
    val firstFrame: Int,
    val lastFrame: Int,
    val title: String?,
    val origin: String,
    val location: LocationView?,
    val isStale: Boolean,
    val shots: List<ShotView>,
)

/**
 * Ответ о структуре серии.
 *
 * На верхнем уровне лежит состояние актуальности: `staleResultCode` равен
 * `STALE_RESULT`, когда результат получен при других входах. Ответ при этом
 * **успешный** — устаревшая структура показывается, а не прячется: в ней
 * есть ручные правки оператора, которые смена порога не отменяет (FR-090,
 * SC-006).
 *
 * @property seriesId серия
 * @property frameCount число кадров серии
 * @property isStale устарел ли результат
 * @property staleResultCode машинный код устаревания либо `null`
 * @property staleReason чем именно результат устарел
 * @property runId последний прогон структуры либо `null`
 * @property algorithmVersion версия алгоритма прогона
 * @property paramsHash хеш входов прогона
 * @property scenesTotal сколько сцен у серии всего
 * @property shotsTotal сколько планов у серии всего
 * @property offset смещение выборки сцен
 * @property limit размер выборки сцен
 * @property scenes сцены выборки с их планами
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class StructureView(
    val seriesId: Long,
    val frameCount: Int,
    val isStale: Boolean,
    val staleResultCode: String?,
    val staleReason: String?,
    val runId: Long?,
    val algorithmVersion: String?,
    val paramsHash: String?,
    val scenesTotal: Int,
    val shotsTotal: Int,
    val offset: Int,
    val limit: Int,
    val scenes: List<SceneView>,
)

/**
 * Сырая граница в ответе.
 *
 * Сырой результат показывается **отдельно** от рабочей структуры: сравнить
 * «что предложила машина» и «что сделал человек» можно только рядом
 * (FR-093).
 *
 * @property runId прогон, которым граница получена
 * @property level уровень: граница сцены или граница плана
 * @property firstFrame первый кадр участка
 * @property lastFrame последний кадр участка
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RawBoundaryView(
    val runId: Long,
    val level: String,
    val firstFrame: Int,
    val lastFrame: Int,
)

/**
 * Ответ с сырыми границами последнего прогона.
 *
 * @property seriesId серия
 * @property runId последний прогон структуры либо `null`
 * @property offset смещение выборки
 * @property limit размер выборки
 * @property total сколько границ всего
 * @property level уровень запрошенных границ либо `null`, если оба
 * @property boundaries границы выборки
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RawBoundariesView(
    val seriesId: Long,
    val runId: Long?,
    val offset: Int,
    val limit: Int,
    val total: Int,
    val level: String?,
    val boundaries: List<RawBoundaryView>,
)

/**
 * Значимый кадр в ответе.
 *
 * @property frameNumber номер кадра
 * @property isSceneBoundary начинается ли здесь новая сцена
 * @property isShotBoundary начинается ли здесь новый план
 * @property faceCount сколько лиц найдено в кадре
 * @property sizeHint подсказка смены крупности либо `null`
 * @property isKeyframe ключевой ли кадр по карте серии
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FrameView(
    val frameNumber: Int,
    val isSceneBoundary: Boolean,
    val isShotBoundary: Boolean,
    val faceCount: Int,
    val sizeHint: String?,
    val isKeyframe: Boolean,
)

/**
 * Страница значимых кадров.
 *
 * Списки кадров и лиц пагинируются **всегда**: на серии их десятки тысяч, а
 * полный ответ занял бы мегабайты и положил бы вкладку оператора
 * (`admin-api.md` § 1.5).
 *
 * @property seriesId серия
 * @property total сколько значимых кадров у серии
 * @property offset смещение выборки
 * @property limit размер выборки
 * @property frames кадры выборки
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FramesView(
    val seriesId: Long,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val frames: List<FrameView>,
)

/**
 * Признаки кадра в ответе по диапазону.
 *
 * @property frameNumber номер кадра
 * @property isKeyframe ключевой ли кадр
 * @property isSceneBoundary начинается ли здесь новая сцена
 * @property isShotBoundary начинается ли здесь новый план
 * @property faceCount сколько лиц найдено в кадре
 * @property sizeHint подсказка смены крупности либо `null`
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FrameFlagsView(
    val frameNumber: Int,
    val isKeyframe: Boolean,
    val isSceneBoundary: Boolean,
    val isShotBoundary: Boolean,
    val faceCount: Int,
    val sizeHint: String?,
)

/**
 * Ответ с признаками кадров диапазона.
 *
 * @property seriesId серия
 * @property fromFrame первый кадр диапазона
 * @property toFrame последний кадр диапазона
 * @property flags признаки кадров диапазона по возрастанию номера
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FrameFlagsListView(
    val seriesId: Long,
    val fromFrame: Int,
    val toFrame: Int,
    val flags: List<FrameFlagsView>,
)

/**
 * Область кадрирования кадра на листе превью.
 *
 * @property row строка листа, с нуля
 * @property column столбец листа, с нуля
 * @property x левый край области в пикселях листа
 * @property y верхний край области в пикселях листа
 * @property width ширина области в пикселях
 * @property height высота области в пикселях
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CellCropView(
    val row: Int,
    val column: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Ответ об адресе листа превью.
 *
 * Клиенту не нужно знать раскладку листа: сервер отдаёт готовый адрес и, если
 * спросили про конкретный кадр, область его кадрирования на листе
 * (`admin-api.md` § 5.2, FR-022).
 *
 * @property seriesId серия
 * @property index номер листа, с нуля
 * @property firstFrame первый кадр листа
 * @property lastFrame последний кадр листа
 * @property frameNumbers сколько кадров на листе
 * @property columns ячеек по горизонтали
 * @property rows ячеек по вертикали
 * @property cellWidth ширина ячейки, пикселей
 * @property cellHeight высота ячейки, пикселей
 * @property sheetWidth ширина листа, пикселей
 * @property sheetHeight высота листа, пикселей
 * @property isReady лист зарегистрирован в состоянии `READY`
 * @property byteSize размер листа в байтах
 * @property contentType тип содержимого
 * @property url адрес листа для прямой загрузки
 * @property frame запрошенный кадр либо `null`
 * @property crop область кадрирования запрошенного кадра либо `null`
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class PreviewUrlView(
    val seriesId: Long,
    val index: Int,
    val firstFrame: Int,
    val lastFrame: Int,
    val frameNumbers: Int,
    val columns: Int,
    val rows: Int,
    val cellWidth: Int,
    val cellHeight: Int,
    val sheetWidth: Int,
    val sheetHeight: Int,
    val isReady: Boolean,
    val byteSize: Long?,
    val contentType: String,
    val url: String,
    val frame: Int?,
    val crop: CellCropView?,
)

/**
 * Ответ на постановку анализа структуры.
 *
 * Ответ — `202`: работа принята в очередь, а не выполнена. Кнопка, ждащая
 * окончания разбора серии, нарушала бы constitution IV.1 (FR-003).
 *
 * @property jobId идентификатор поставленного задания
 * @property seriesId серия
 * @property state состояние задания на момент постановки
 * @property sceneThreshold порог границы сцены
 * @property shotThreshold порог границы плана
 * @property paramsHash хеш входов задания
 * @property frameCount число кадров серии
 * @property previewSheetCount сколько листов превью у серии будет
 * @property alreadyCompleted выполнялась ли работа с такими входами раньше
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class AnalysisEnqueuedView(
    val jobId: Long,
    val seriesId: Long,
    val state: String,
    val sceneThreshold: Double,
    val shotThreshold: Double,
    val paramsHash: String,
    val frameCount: Int,
    val previewSheetCount: Int,
    val alreadyCompleted: Boolean,
)

/**
 * Постановщик анализа структуры серии.
 *
 * **Повторная постановка означает пересчёт.** Правило о пропуске задания с
 * тем же хешем параметров (Р-10) здесь не действует: кнопка анализа означает
 * требование человека разобрать серию заново — например, после правки
 * границ вручную. Ответ при этом прямо говорит, была ли такая работа
 * выполнена раньше, чтобы интерфейс не показывал лишний час ожидания как
 * неизвестность.
 *
 * @property queue очередь заданий
 * @property settingsStore настройки сериала: из них берутся пороги
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class AnalysisEnqueuer(
    private val queue: JobQueue,
    private val settingsStore: SerialSettingsStore,
) {
    /**
     * Ставит анализ структуры серии.
     *
     * @param series серия
     * @return ответ на постановку
     * @throws DomainException если у сериала нет настроек: значения по
     *   умолчанию создаёт триггер базы, и их отсутствие — дефект данных
     */
    fun enqueue(series: Series): AnalysisEnqueuedView {
        val settings = settingsStore.read(series.serialId)
        val sceneThreshold = settings.number(SerialSetting.SCENE_THRESHOLD)
        val shotThreshold = settings.number(SerialSetting.SHOT_THRESHOLD)
        val paramsHash = SceneDetector.paramsHashOf(settings)
        val layout = previewLayoutOf(settings)
        val jobId =
            queue.enqueue(
                kind = JobKind.ANALYZE,
                subject = JobSubject.series(series.id!!),
                paramsJson =
                    "{\"sceneThreshold\":$sceneThreshold,\"shotThreshold\":$shotThreshold," +
                        "\"previewColumns\":${layout.columns},\"previewRows\":${layout.rows}}",
                paramsHash = paramsHash,
                algorithmVersion = DetectionResult.ALGORITHM_VERSION,
            )
        return AnalysisEnqueuedView(
            jobId = jobId,
            seriesId = series.id,
            state = JobState.WAITING.name,
            sceneThreshold = sceneThreshold,
            shotThreshold = shotThreshold,
            paramsHash = paramsHash,
            frameCount = series.frameCount,
            previewSheetCount = PreviewSheet.sheetCount(series.frameCount, layout),
            alreadyCompleted =
                queue.hasCompletedWithReadyArtifact(JobKind.ANALYZE, paramsHash),
        )
    }

    /**
     * Раскладка листа превью по настройкам сериала.
     *
     * @param settings настройки сериала
     * @return раскладка листа
     */
    private fun previewLayoutOf(settings: ru.svoemesto.syp.admin.catalog.SerialSettings): PreviewLayout =
        PreviewLayout(
            columns = settings.integer(SerialSetting.PREVIEW_SHEET_COLS),
            rows = settings.integer(SerialSetting.PREVIEW_SHEET_ROWS),
        )
}

/**
 * Место действия в ответе: снимок имени на момент чтения.
 *
 * @return описание локации
 */
internal fun Location.toView(): LocationView = LocationView(id!!, name)

/**
 * Эндпоинты структуры серии и превью кадров.
 *
 * Это чтение результата анализа и постановка самого анализа. Ручной доводки
 * границ здесь нет: она описана отдельными механиками и приходит вместе с
 * ними, а смешивать чтение и правку в одном контроллере означало бы, что
 * операция правки может случайно изменить то, что он смотрит.
 *
 * @property enqueuer постановщик анализа
 * @property seriesStore хранилище серий
 * @property runStore хранилище прогонов
 * @param structure сервис рабочей структуры
 * @property boundaryStore хранилище сырых границ
 * @property frameStore хранилище значимых кадров
 * @property staleness состояние актуальности результата
 * @property settingsStore настройки сериала
 * @property artifactRegistry реестр артефактов листов превью
 * @property storage объектное хранилище
 * @property locations справочник мест действия сериала
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class StructureController(
    private val enqueuer: AnalysisEnqueuer,
    private val seriesStore: SeriesStore,
    private val runStore: AnalysisRunStore,
    private val structure: StructureService,
    private val boundaryStore: RawBoundaryStore,
    private val frameStore: FrameSignificanceStore,
    private val staleness: Staleness,
    private val settingsStore: SerialSettingsStore,
    private val artifactRegistry: ArtifactRegistry,
    private val storage: ObjectStorage,
    private val locations: LocationStore,
) {
    /**
     * Ставит серию на анализ структуры.
     *
     * @param seriesId идентификатор серии
     * @return поставленное задание, код `202`
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    @PostMapping("/api/series/{seriesId}/analysis")
    fun startAnalysis(
        @PathVariable seriesId: Long,
    ): ResponseEntity<AnalysisEnqueuedView> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(enqueuer.enqueue(requireSeries(seriesId)))

    /**
     * Отдаёт структуру серии: сцены с планами.
     *
     * Планы внутри сцены вычисляются по диапазонам кадров, а не берутся из
     * сохранённой связи: такой связи в схеме нет и быть не должно (ADR-0007).
     *
     * @param seriesId идентификатор серии
     * @param offset смещение выборки сцен
     * @param limit размер выборки сцен
     * @return страница структуры со сведениями об актуальности
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    @GetMapping("/api/series/{seriesId}/structure")
    fun readStructure(
        @PathVariable seriesId: Long,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "200") limit: Int,
    ): StructureView {
        val series = requireSeries(seriesId)
        val status = currentStatus(series)
        val scenes = structure.listScenes(seriesId)
        val shots = structure.listShots(seriesId)
        val page = pageOf(scenes, offset, limit)
        val locations = locationsOf(series.serialId, page.mapNotNull { it.locationId })
        return StructureView(
            seriesId = seriesId,
            frameCount = series.frameCount,
            isStale = status.isStale,
            staleResultCode = status.staleResultCode,
            staleReason = status.reason,
            runId = status.runId,
            algorithmVersion = status.algorithmVersion,
            paramsHash = status.paramsHash,
            scenesTotal = scenes.size,
            shotsTotal = shots.size,
            offset = offset.coerceAtLeast(0),
            limit = checkedLimit(limit),
            scenes =
                page.map { scene ->
                    SceneView(
                        id = scene.id!!,
                        firstFrame = scene.firstFrame,
                        lastFrame = scene.lastFrame,
                        title = scene.title,
                        origin = scene.origin.name,
                        location = locations[scene.locationId],
                        isStale = scene.isStale,
                        shots =
                            structure
                                .shotsInside(scene, shots)
                                .map { shot ->
                                    ShotView(
                                        id = shot.id!!,
                                        firstFrame = shot.firstFrame,
                                        lastFrame = shot.lastFrame,
                                        size = shot.size.name,
                                        sizeOrigin = shot.sizeOrigin.name,
                                        origin = shot.origin.name,
                                        isStale = shot.isStale,
                                    )
                                },
                    )
                },
        )
    }

    /**
     * Отдаёт сырые границы последнего прогона автоматики.
     *
     * Показываются **отдельно** от рабочей структуры: иначе сравнить
     * предложение машины с решением человека нечем (FR-093).
     *
     * @param seriesId идентификатор серии
     * @param level уровень границ: сцена или план; оба, если не задан
     * @param offset смещение выборки
     * @param limit размер выборки
     * @return страница сырых границ
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет, или с кодом
     *   `BAD_REQUEST`, если уровень неизвестен
     */
    @GetMapping("/api/series/{seriesId}/raw-boundaries")
    fun readRawBoundaries(
        @PathVariable seriesId: Long,
        @RequestParam(required = false) level: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "200") limit: Int,
    ): RawBoundariesView {
        requireSeries(seriesId)
        val parsed = level?.let { name -> parseLevel(name) }
        val runId = runStore.latest(seriesId, AnalysisKind.STRUCTURE)?.id
        val all = rawBoundariesOf(runId, parsed)
        val page = all.drop(offset.coerceAtLeast(0)).take(checkedLimit(limit))
        return RawBoundariesView(
            seriesId = seriesId,
            runId = runId,
            offset = offset.coerceAtLeast(0),
            limit = checkedLimit(limit),
            total = all.size,
            level = parsed?.name,
            boundaries =
                page.map { boundary ->
                    RawBoundaryView(
                        runId = boundary.runId,
                        level = boundary.level.name,
                        firstFrame = boundary.firstFrame,
                        lastFrame = boundary.lastFrame,
                    )
                },
        )
    }

    /**
     * Отдаёт страницу значимых кадров серии.
     *
     * @param seriesId идентификатор серии
     * @param offset смещение выборки
     * @param limit размер выборки
     * @return страница значимых кадров
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    @GetMapping("/api/series/{seriesId}/frames")
    fun readFrames(
        @PathVariable seriesId: Long,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "200") limit: Int,
    ): FramesView {
        val series = requireSeries(seriesId)
        val total = frameStore.countBySeries(seriesId)
        val from = offset.coerceAtLeast(0)
        val frames = frameStore.listRange(seriesId, from, series.frameCount - 1, checkedLimit(limit))
        return FramesView(
            seriesId = seriesId,
            total = total,
            offset = from,
            limit = checkedLimit(limit),
            frames = frames.map { it.toView(series) },
        )
    }

    /**
     * Отдаёт признаки кадров диапазона.
     *
     * Признак «ключевой кадр» приходит из карты серии, а не из таблицы
     * кадров: полной таблицы на 88 643 строки не существует (Р-07).
     *
     * @param seriesId идентификатор серии
     * @param fromFrame первый кадр диапазона включительно
     * @param toFrame последний кадр диапазона включительно
     * @return признаки кадров диапазона
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет, или с кодом
     *   `BAD_REQUEST`, если диапазон вывернут наизнанку
     */
    @GetMapping("/api/series/{seriesId}/frames/flags")
    fun readFrameFlags(
        @PathVariable seriesId: Long,
        @RequestParam fromFrame: Int,
        @RequestParam toFrame: Int,
    ): FrameFlagsListView {
        val series = requireSeries(seriesId)
        if (fromFrame < 0 || toFrame < fromFrame) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "диапазон кадров $fromFrame…$toFrame вывернут наизнанку или уходит в отрицательные номера",
            )
        }
        val frames = frameStore.listRange(seriesId, fromFrame, minOf(toFrame, series.frameCount - 1))
        return FrameFlagsListView(
            seriesId = seriesId,
            fromFrame = fromFrame,
            toFrame = toFrame,
            flags = frames.map { it.toFlagsView(series) },
        )
    }

    /**
     * Отдаёт лист превью серии.
     *
     * Отдаётся **только** лист, зарегистрированный в состоянии `READY`:
     * незавершённый файл не считается готовым и показывать его незачем
     * (FR-091).
     *
     * @param seriesId идентификатор серии
     * @param index номер листа, с нуля
     * @return содержимое листа
     * @throws DomainException с кодом `NOT_FOUND`, если листа нет или он ещё
     *   не готов
     */
    @GetMapping("/api/series/{seriesId}/preview-sheets/{index}")
    fun readPreviewSheet(
        @PathVariable seriesId: Long,
        @PathVariable index: Int,
    ): ResponseEntity<ByteArray> {
        val sheet = requireSheet(seriesId, index)
        val artifact =
            artifactRegistry.findReady(ArtifactKind.PREVIEW_SHEET, sheet.finalKey())
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "лист превью №$index серии $seriesId ещё не готов: анализ не завершён " +
                        "или оборвался. Незавершённый лист не выдаётся (FR-091)",
                )
        val bytes = storage.get(artifact.objectKey).use { it.readBytes() }
        return ResponseEntity
            .ok()
            .header("Content-Type", artifact.contentType)
            .header("Content-Length", bytes.size.toString())
            .body(bytes)
    }

    /**
     * Отдаёт адрес листа превью и раскладку листа.
     *
     * @param seriesId идентификатор серии
     * @param index номер листа, с нуля; если не задан, берётся из `frame`
     * @param frame кадр, для которого нужен адрес листа и область кадрирования
     * @return описание листа
     * @throws DomainException с кодом `NOT_FOUND`, если серии или листа нет,
     *   либо с кодом `BAD_REQUEST`, если не задан ни номер листа, ни кадр
     */
    @GetMapping("/api/series/{seriesId}/preview-url")
    fun readPreviewUrl(
        @PathVariable seriesId: Long,
        @RequestParam(required = false) index: Int?,
        @RequestParam(required = false) frame: Int?,
    ): PreviewUrlView {
        val series = requireSeries(seriesId)
        val layout = previewLayoutOf(settingsStore.read(series.serialId))
        val resolvedIndex =
            when {
                index != null -> index
                frame != null -> {
                    if (frame < 0 || frame >= series.frameCount) {
                        throw DomainException(
                            ErrorCode.BAD_REQUEST,
                            "кадра $frame у серии из ${series.frameCount} кадров нет: " +
                                "спросить лист превью не о чем",
                        )
                    }
                    frame / layout.framesPerSheet
                }
                else ->
                    throw DomainException(
                        ErrorCode.BAD_REQUEST,
                        "нужен номер листа (index) или номер кадра (frame): по одному адресу " +
                            "без них лист не определить",
                    )
            }
        val sheet = requireSheet(seriesId, resolvedIndex, layout, series.frameCount)
        val artifact = artifactRegistry.findReady(ArtifactKind.PREVIEW_SHEET, sheet.finalKey())
        val position = frame?.let { sheet.positionOf(it) }
        return PreviewUrlView(
            seriesId = seriesId,
            index = sheet.index,
            firstFrame = sheet.firstFrame,
            lastFrame = sheet.lastFrame,
            frameNumbers = sheet.frameNumbersCount,
            columns = layout.columns,
            rows = layout.rows,
            cellWidth = layout.cellWidth,
            cellHeight = layout.cellHeight,
            sheetWidth = layout.sheetWidth,
            sheetHeight = layout.sheetHeight,
            isReady = artifact != null,
            byteSize = artifact?.byteSize,
            contentType = layout.contentType,
            url = "/api/series/$seriesId/preview-sheets/${sheet.index}",
            frame = frame,
            crop =
                position?.let {
                    CellCropView(
                        row = it.row,
                        column = it.column,
                        x = it.column * layout.cellWidth,
                        y = it.row * layout.cellHeight,
                        width = layout.cellWidth,
                        height = layout.cellHeight,
                    )
                },
        )
    }

    /**
     * Читает серию или отказывает.
     *
     * @param seriesId идентификатор серии
     * @return серия
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    private fun requireSeries(seriesId: Long): Series =
        seriesStore.find(seriesId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "серия $seriesId не зарегистрирована")

    /**
     * Сырые границы прогона указанного уровня либо обоих сразу.
     *
     * @param runId прогон; `null`, если прогона ещё не было
     * @param level уровень границ либо `null`, если нужны оба
     * @return границы в порядке следования
     */
    private fun rawBoundariesOf(
        runId: Long?,
        level: BoundaryLevel?,
    ): List<RawBoundary> {
        if (runId == null) {
            return emptyList()
        }
        if (level != null) {
            return boundaryStore.listByRun(runId, level)
        }
        return boundaryStore.listAllByRun(runId).values.flatten()
    }

    /**
     * Состояние актуальности структуры серии.
     *
     * @param series серия
     * @return состояние актуальности
     */
    private fun currentStatus(series: Series): StaleStatus =
        staleness.status(series.id!!, AnalysisKind.STRUCTURE, currentParamsHash(series))

    /**
     * Актуальный хеш входов детекции по настройкам сериала.
     *
     * @param series серия
     * @return 64 шестнадцатеричных символов в нижнем регистре
     */
    private fun currentParamsHash(series: Series): String = SceneDetector.paramsHashOf(settingsStore.read(series.serialId))

    /**
     * Лист серии по номеру.
     *
     * @param seriesId идентификатор серии
     * @param index номер листа, с нуля
     * @param layout раскладка листа
     * @param frameCount число кадров серии
     * @return лист
     * @throws DomainException с кодом `NOT_FOUND`, если серии или листа нет
     */
    private fun requireSheet(
        seriesId: Long,
        index: Int,
        layout: PreviewLayout = previewLayoutOf(settingsStore.read(requireSeries(seriesId).serialId)),
        frameCount: Int = requireSeries(seriesId).frameCount,
    ): PreviewSheet =
        runCatching { PreviewSheet.of(seriesId, index, frameCount, layout) }
            .getOrElse { failure ->
                throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "листа превью №$index у серии $seriesId нет: $failure",
                )
            }

    /**
     * Раскладка листа превью по настройкам сериала.
     *
     * @param settings настройки сериала
     * @return раскладка листа
     */
    private fun previewLayoutOf(settings: SerialSettings): PreviewLayout =
        PreviewLayout(
            columns = settings.integer(SerialSetting.PREVIEW_SHEET_COLS),
            rows = settings.integer(SerialSetting.PREVIEW_SHEET_ROWS),
        )

    /**
     * Проверяет размер выборки.
     *
     * @param limit запрошенный размер
     * @return размер в допустимых границах
     */
    private fun checkedLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    /**
     * Страница списка по смещению и размеру.
     *
     * @param items полный список
     * @param offset смещение
     * @param limit размер страницы
     * @return элементы страницы
     */
    private fun <T> pageOf(
        items: List<T>,
        offset: Int,
        limit: Int,
    ): List<T> = items.drop(offset.coerceAtLeast(0)).take(checkedLimit(limit))

    /**
     * Справочник мест действия по идентификаторам.
     *
     * Локаций у сериала десятки, а не тысячи, поэтому берётся весь справочник
     * сериала и фильтруется по нужным: запрос «по десяти идентификаторам» был
     * бы сложнее ради того же результата.
     *
     * @param serialId сериал-владелец локаций
     * @param ids идентификаторы локаций, которые нужны в ответе
     * @return описания локаций по идентификаторам
     */
    private fun locationsOf(
        serialId: Long,
        ids: List<Long>,
    ): Map<Long, LocationView> {
        if (ids.isEmpty()) {
            return emptyMap()
        }
        val wanted = ids.toSet()
        return locations
            .listBySerial(serialId)
            .filter { it.id in wanted }
            .associate { it.id!! to it.toView() }
    }

    /**
     * Разбирает название уровня границы.
     *
     * @param name название уровня
     * @return уровень
     * @throws DomainException с кодом `BAD_REQUEST`, если уровня нет в наборе
     */
    private fun parseLevel(name: String): BoundaryLevel =
        BoundaryLevel.entries.firstOrNull { it.name == name }
            ?: throw DomainException(
                ErrorCode.BAD_REQUEST,
                "уровня границы «$name» нет. Допустимы: ${BoundaryLevel.entries.joinToString()}",
            )

    /**
     * Кадр в ответе списка значимых кадров.
     *
     * @param series серия: из неё берётся карта ключевых кадров
     * @return описание кадра
     */
    private fun FrameSignificance.toView(series: Series): FrameView =
        FrameView(
            frameNumber = frameNumber,
            isSceneBoundary = isSceneBoundary,
            isShotBoundary = isShotBoundary,
            faceCount = faceCount,
            sizeHint = sizeHint?.name,
            isKeyframe = series.keyframeMap?.isKeyframe(frameNumber) == true,
        )

    /**
     * Кадр в ответе с признаками диапазона.
     *
     * @param series серия: из неё берётся карта ключевых кадров
     * @return признаки кадра
     */
    private fun FrameSignificance.toFlagsView(series: Series): FrameFlagsView =
        FrameFlagsView(
            frameNumber = frameNumber,
            isKeyframe = series.keyframeMap?.isKeyframe(frameNumber) == true,
            isSceneBoundary = isSceneBoundary,
            isShotBoundary = isShotBoundary,
            faceCount = faceCount,
            sizeHint = sizeHint?.name,
        )

    companion object {
        /** Максимальный размер выборки: по умолчанию 200, потолок 2000. */
        const val MAX_LIMIT: Int = 2000
    }
}
