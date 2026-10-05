package ru.svoemesto.syp.admin.catalog

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.admin.analysis.SceneDetector
import ru.svoemesto.syp.admin.analysis.Staleness
import ru.svoemesto.syp.admin.integrity.ChecksumEnqueuer
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import java.time.Instant

/**
 * Тело запроса создания фильма.
 *
 * @property name название фильма
 * @property sourceRoot корень каталога фильма на машине администратора.
 *   Обязателен: без него не вычислить относительный путь к файлу эпизода,
 *   который попадёт в сценарий сборки (FR-089a)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CreateProjectRequest(
    val name: String,
    val sourceRoot: String,
)

/**
 * Тело запроса регистрации эпизода.
 *
 * @property sourcePath путь к исходному видеофайлу внутри корня фильма
 * @property name название эпизода; если не задано, берётся имя файла
 * @property seasonNumber номер сезона; не задан — у фильма, у которого
 *   сезонов нет
 * @property videofileOrdinal номер эпизода внутри сезона; 0 — у фильма
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RegisterVideofileRequest(
    val sourcePath: String,
    val name: String? = null,
    val seasonNumber: Int? = null,
    val videofileOrdinal: Int = 0,
)

/**
 * Описание фильма в ответе.
 *
 * Время отдаётся производной величиной — `durationSeconds` и `timeBase` — и
 * ни одним полем ответа не является вторым источником правды: все они
 * вычислены от номера кадра и частокадровой базы (ADR-0001).
 *
 * @property id идентификатор эпизода
 * @property projectId фильм-владелец
 * @property ordinal порядковый номер в фильме
 * @property name название эпизода
 * @property sourcePath абсолютный путь к файлу
 * @property relativePath путь относительно корня фильма: он и попадёт в
 *   сценарий сборки
 * @property byteSize размер файла в байтах
 * @property fileMtime время изменения файла
 * @property frameCount число кадров
 * @property timeBaseNum числитель длительности кадра в секундах
 * @property timeBaseDen знаменатель длительности кадра в секундах
 * @property frameRate частокадровая база в виде `кадров/секунду`
 * @property width ширина кадра
 * @property height высота кадра
 * @property durationSeconds длительность эпизода, вычисленная по кадрам
 * @property videoCodec кодек видео
 * @property videoProfile профиль видео
 * @property pixelFormat формат пикселей
 * @property audioCodec кодек аудио
 * @property audioChannels число аудиоканалов
 * @property audioSampleRate частота дискретизации аудио
 * @property keyframeCount сколько ключевых кадров в эпизоде
 * @property keyframeMapBytes длина карты ключевых кадров в байтах
 * @property ready готова ли эпизод к работе: карта ключевых кадров посчитана
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */

data class VideofileView(
    val id: Long,
    val projectId: Long,
    val ordinal: Int,
    val name: String,
    val seasonNumber: Int?,
    val seasonOrdinal: Int?,
    val tracks: List<TrackView> = emptyList(),
    val videofileOrdinal: Int,
    val designation: String,
    val sourcePath: String,
    val relativePath: String?,
    val byteSize: Long,
    val fileMtime: Instant,
    val frameCount: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    val frameRate: String,
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val videoCodec: String,
    val videoProfile: String?,
    val pixelFormat: String,
    val audioCodec: String?,
    val audioChannels: Int?,
    val audioSampleRate: Int?,
    val keyframeCount: Int,
    val keyframeMapBytes: Int,
    val ready: Boolean,
)

/**
 * Дорожка видеофайла в ответе.
 *
 * @property index номер дорожки в файле
 * @property ordinal порядковый номер среди дорожек того же вида
 * @property codecType вид дорожки: видео, аудио, субтитры, данные, вложение
 * @property codecName название кодека
 */
data class TrackView(
    val index: Int,
    val ordinal: Int,
    val codecType: String,
    val codecName: String?,
)

/** Дорожка в ответе: ядро отдаёт то же самое плюс внутренние имена полей. */
internal fun ru.svoemesto.syp.core.media.MediaTrack.toView(): TrackView =
    TrackView(index = index, ordinal = ordinal, codecType = codecType, codecName = codecName)

/**
 * Описание фильма в ответе.
 *
 * @property id идентификатор фильма
 * @property name название фильма
 * @property sourceRoot корень каталога фильма
 * @property createdAt дата создания
 * @property videofileCount сколько эпизодов заведено
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ProjectView(
    val id: Long,
    val name: String,
    val sourceRoot: String,
    val createdAt: Instant?,
    val videofileCount: Int,
)

/**
 * Ответ на создание фильма.
 *
 * Настройки идут вместе с фильмом: они создаются автоматически, и оператор
 * правит их сразу же. Отдельный запрос за ними был бы лишним обращением: у
 * только что созданного фильма настроек не может не быть.
 *
 * @property project созданный фильм
 * @property settings значения настроек по умолчанию
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CreatedProjectView(
    val project: ProjectView,
    val settings: List<SettingView>,
)

/**
 * Ответ на чтение фильма.
 *
 * @property project фильм
 * @property videofile эпизода фильма
 * @property settings настройки фильма
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ProjectDetailView(
    val project: ProjectView,
    val videofile: List<VideofileView>,
    val settings: List<SettingView>,
)

/**
 * Одна настройка в ответе.
 *
 * @property key имя настройки
 * @property title назначение человеческим языком
 * @property kind тип значения: `NUMBER`, `INTEGER` или `NUMBER_LIST`
 * @property value значение
 * @property updatedAt когда значение записано в последний раз
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SettingView(
    val key: String,
    val title: String,
    val kind: String,
    val value: JsonNode,
    val updatedAt: Instant?,
)

/**
 * Ответ на изменение настроек.
 *
 * @property settings настройки после изменения
 * @property changedKeys какие настройки действительно изменились: запись того
 *   же значения изменением не считается
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SettingsUpdateView(
    val settings: List<SettingView>,
    val changedKeys: List<String>,
)

/**
 * Эндпоинты приёма фильма и эпизода.
 *
 * Здесь только приём: создать фильм, завести эпизод, прочитать параметры,
 * снять с учёта и настроить пороги. Всё, что требует чтения всего файла
 * целиком, живёт в заданиях очереди, а не в HTTP-запросе: иначе одного
 * регистрация эпизода занимала бы соединение интерфейса на минуты.
 *
 * Ответы содержат те же поля, что и контракт
 * [`admin-api.md`](../../../../../specs/001-first-vertical-slice/contracts/admin-api.md),
 * раздел 3. Коды ошибок общие с публичной частью и приходят из
 * `ErrorCode`: интерфейс принимает решение по коду, человек читает текст.
 *
 * @property projects хранилище фильмов
 * @property videofileStore хранилище эпизодов
 * @property settingsStore хранилище настроек
 * @property registration регистрация эпизода с проверкой пути
 * @property checksums постановщик подсчёта суммы: при регистрации эпизода
 *   подсчёт ставится автоматически, без актуальной суммы сценарий отдать
 *   нельзя (FR-089)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class CatalogController(
    private val projects: ProjectStore,
    private val videofileStore: VideofileStore,
    private val settingsStore: ProjectSettingsStore,
    private val registration: VideofileRegistration,
    private val probe: SourceProbe,
    private val tracks: TrackStore,
    private val checksums: ChecksumEnqueuer? = null,
    private val staleness: Staleness? = null,
) {
    /**
     * Перечисляет фильмы с числом эпизодов каждого.
     *
     * @return список фильмов
     */
    @GetMapping("/api/projects")
    fun listProjects(): List<ProjectView> = projects.listWithVideofileCount().map { it.project.toView(it.videofileCount) }

    /**
     * Создаёт фильм.
     *
     * Ответ содержит значения настроек по умолчанию: они создаются триггером
     * базы, и оператор правит их сразу (ADR-0003).
     *
     * @param request название и корень каталога
     * @return созданный фильм с настройками, код `201`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `CONFLICT`, если название занято
     */
    @PostMapping("/api/projects")
    fun createProject(
        @RequestBody request: CreateProjectRequest,
    ): ResponseEntity<CreatedProjectView> {
        val project = projects.create(request.name, request.sourceRoot)
        val body = CreatedProjectView(project.toView(0), settingsView(project.id!!))
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }

    /**
     * Читает фильм, его эпизодов и настройки.
     *
     * @param projectId идентификатор фильма
     * @return фильм с эпизодами и настройками
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если фильма нет
     */
    @GetMapping("/api/projects/{projectId}")
    fun readProject(
        @PathVariable projectId: Long,
    ): ProjectDetailView {
        val project = requireProject(projectId)
        return ProjectDetailView(
            project = project.toView(videofileStore.countByProject(projectId)),
            videofile = videofileStore.listByProject(projectId).map { it.toView(project) },
            settings = settingsView(projectId),
        )
    }

    /**
     * Удаляет фильм вместе со всеми производными данными.
     *
     * Файлы архива при этом не трогаются: они принадлежат не системе.
     * Операция необратима и подтверждается оператором.
     *
     * @param projectId идентификатор фильма
     * @return пустой ответ, код `204`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если фильма нет
     */
    @DeleteMapping("/api/projects/{projectId}")
    fun deleteProject(
        @PathVariable projectId: Long,
    ): ResponseEntity<Void> {
        requireProject(projectId)
        projects.delete(projectId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Перечисляет эпизоды фильма.
     *
     * @param projectId идентификатор фильма
     * @return эпизода в порядке порядковых номеров
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если фильма нет
     */
    @GetMapping("/api/projects/{projectId}/videofiles")
    fun listVideofile(
        @PathVariable projectId: Long,
    ): List<VideofileView> {
        val project = requireProject(projectId)
        return videofileStore.listByProject(projectId).map { it.toView(project) }
    }

    /**
     * Регистрирует эпизод в фильме.
     *
     * Путь обязан лежать внутри корня каталога фильма, а файл обязан
     * существовать и читаться: иначе ответ — ошибка `SOURCE_UNREADABLE`, а не
     * «успех с пустым результатом» (FR-092). Параметры снимаются с самого
     * файла, оператором не вводятся (FR-002).
     *
     * @param projectId идентификатор фильма
     * @param request путь к файлу и, по желанию, название эпизода
     * @return зарегистрированный эпизод с определёнными параметрами, код `201`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `SOURCE_UNREADABLE`, если путь вне корня или файл недоступен
     */
    @PostMapping("/api/projects/{projectId}/videofiles")
    fun registerVideofile(
        @PathVariable projectId: Long,
        @RequestBody request: RegisterVideofileRequest,
    ): ResponseEntity<VideofileView> {
        val project = requireProject(projectId)
        val registered =
            registration.register(
                projectId,
                request.sourcePath,
                request.name,
                request.seasonNumber,
                request.videofileOrdinal,
            )
        // Подсчёт суммы ставится сразу: он считается заданием и идёт в фоне,
        // а ждать его в этом запросе нельзя — это нарушало бы constitution
        // IV.1 (FR-003). Отказ постановки не отменяет регистрацию: эпизод уже
        // заведена, а пересчёт можно поставить кнопкой.
        runCatching { checksums?.enqueueAutomatic(registered) }
        return ResponseEntity.status(HttpStatus.CREATED).body(registered.toView(project))
    }

    /**
     * Читает параметры эпизода и состояние готовности.
     *
     * @param videofileId идентификатор эпизода
     * @return параметры эпизода
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если эпизода нет
     */
    @GetMapping("/api/videofiles/{videofileId}")
    fun readVideofile(
        @PathVariable videofileId: Long,
    ): VideofileView {
        val videofile = requireVideofile(videofileId)
        return videofile.toView(requireProject(videofile.projectId)).copy(
            tracks = tracks.list(videofileId).map { it.toView() },
        )
    }

    /**
     * Определяет дорожки видеофайла заново и записывает их.
     *
     * Нужно, когда файл на диске заменили: видеофайл тот же, а состав дорожек
     * другой. Заодно адрес служит проверкой, что определение вообще работает —
     * без него дорожки заведённых файлов не на чем проверить, они появились
     * раньше механизма.
     *
     * @param videofileId идентификатор видеофайла
     * @return дорожки после определения
     * @throws DomainException с кодом `NOT_FOUND`, если видеофайла нет
     * @throws TrackProbeFailed если зонд не ответил
     * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
     */
    @PostMapping("/api/videofiles/{videofileId}/tracks")
    fun redetectTracks(
        @PathVariable videofileId: Long,
    ): List<TrackView> {
        val videofile = requireVideofile(videofileId)
        val found =
            probe
                .probe(
                    java.nio.file.Path
                        .of(videofile.sourcePath),
                ).tracks
        tracks.replace(videofileId, found)
        return found.map { it.toView() }
    }

    /**
     * Снимает эпизод с учёта.
     *
     * Файл источника не трогается — он лежит в архиве и принадлежит не
     * системе. Удаляются записи о эпизоде и производные от них данные.
     *
     * @param videofileId идентификатор эпизода
     * @return пустой ответ, код `204`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если эпизода нет
     */
    @DeleteMapping("/api/videofiles/{videofileId}")
    fun deleteVideofile(
        @PathVariable videofileId: Long,
    ): ResponseEntity<Void> {
        requireVideofile(videofileId)
        videofileStore.delete(videofileId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Читает настройки анализа и выдачи сценария.
     *
     * @param projectId идентификатор фильма
     * @return настройки фильма
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если фильма нет
     */
    @GetMapping("/api/projects/{projectId}/settings")
    fun readSettings(
        @PathVariable projectId: Long,
    ): List<SettingView> {
        requireProject(projectId)
        return settingsView(projectId)
    }

    /**
     * Изменяет настройки анализа и выдачи сценария.
     *
     * Неизвестный ключ и значение не того смысла отклоняются с текстом:
     * настройка, которую никто не читает, выглядела бы как сработавшая
     * (ADR-0003, constitution).
     *
     * **Действительно изменившаяся настройка помечает результаты
     * устаревшими, но не удаляет их** (FR-090). Пересчёт автоматически не
     * запускается: он уничтожил бы ручные правки оператора, которые
     * накапливаются месяцами. Решение о пересчёте принимает человек.
     *
     * @param projectId идентификатор фильма
     * @param changes новые значения по именам настроек
     * @return настройки после изменения и список действительно изменившихся
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `BAD_REQUEST`, если ключ неизвестен или значение не подходит
     */
    @PutMapping("/api/projects/{projectId}/settings")
    fun updateSettings(
        @PathVariable projectId: Long,
        @RequestBody changes: Map<String, JsonNode>,
    ): SettingsUpdateView {
        requireProject(projectId)
        val changed = settingsStore.update(projectId, changes)
        if (changed.isNotEmpty()) {
            staleness?.markStaleForProject(projectId, SceneDetector.paramsHashOf(settingsStore.read(projectId)))
        }
        return SettingsUpdateView(settingsView(projectId), changed)
    }

    /**
     * Читает фильм или отказывает.
     *
     * Отказ `NOT_FOUND`, а не пустой список: пустой ответ на «фильма нет»
     * выглядел бы как «у фильма нет эпизодов», и интерфейс показал бы пустую
     * страницу вместо того, чтобы сказать, что фильм не заведён.
     *
     * @param projectId идентификатор фильма
     * @return фильм
     * @throws DomainException с кодом `NOT_FOUND`, если фильма нет
     */
    private fun requireProject(projectId: Long): Project =
        projects.find(projectId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "project $projectId is not registered")

    /**
     * Читает эпизод или отказывает.
     *
     * @param videofileId идентификатор эпизода
     * @return эпизод
     * @throws DomainException с кодом `NOT_FOUND`, если эпизода нет
     */
    private fun requireVideofile(videofileId: Long): Videofile =
        videofileStore.find(videofileId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "эпизод $videofileId не зарегистрирована")

    /** Собирает список настроек фильма для ответа. */
    private fun settingsView(projectId: Long): List<SettingView> {
        val read = settingsStore.read(projectId)
        return ProjectSetting.entries.map { setting ->
            SettingView(
                key = setting.key,
                title = setting.title,
                kind = setting.kind.name,
                value = read.node(setting),
                updatedAt = settingsStore.updatedAt(projectId, setting)?.toInstant(),
            )
        }
    }
}

/**
 * Описание фильма для ответа.
 *
 * @param videofileCount сколько эпизодов заведено
 * @return описание фильма
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun Project.toView(videofileCount: Int): ProjectView =
    ProjectView(
        id = id!!,
        name = name,
        sourceRoot = sourceRoot,
        createdAt = createdAt?.toInstant(),
        videofileCount = videofileCount,
    )

/**
 * Описание эпизода для ответа.
 *
 * @param project фильм-владелец: из него берётся корень для относительного пути
 * @return описание эпизода
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun Videofile.toView(
    project: Project,
    seasonOrdinal: Int? = null,
): VideofileView =
    VideofileView(
        id = id!!,
        projectId = projectId,
        ordinal = ordinal,
        name = name,
        seasonNumber = seasonNumber,
        seasonOrdinal = seasonOrdinal,
        videofileOrdinal = videofileOrdinal,
        designation = "S%02dE%02d".format(seasonOrdinal ?: 0, videofileOrdinal),
        sourcePath = sourcePath,
        relativePath = relativePath(project.sourceRoot),
        byteSize = byteSize,
        fileMtime = fileMtime.toInstant(),
        frameCount = frameCount,
        timeBaseNum = timeBaseNum,
        timeBaseDen = timeBaseDen,
        frameRate = "${frameRateNumerator()}/${frameRateDenominator()}",
        width = width,
        height = height,
        durationSeconds = durationSeconds(),
        videoCodec = videoCodec,
        videoProfile = videoProfile,
        pixelFormat = pixelFormat,
        audioCodec = audioCodec,
        audioChannels = audioChannels,
        audioSampleRate = audioSampleRate,
        keyframeCount = keyframeMap?.keyframeCount() ?: 0,
        keyframeMapBytes = keyframeMap?.byteLength ?: 0,
        ready = keyframeMap != null,
    )

/** Числитель частоты кадров: знаменатель длительности кадра. */
private fun Videofile.frameRateNumerator(): Long = timeBaseDen.toLong() / gcdOf(timeBaseNum, timeBaseDen)

/** Знаменатель частоты кадров: числитель длительности кадра. */
private fun Videofile.frameRateDenominator(): Long = timeBaseNum.toLong() / gcdOf(timeBaseNum, timeBaseDen)

/** НОД двух чисел. */
private fun gcdOf(
    first: Int,
    second: Int,
): Int = if (second == 0) first else gcdOf(second, first % second)
