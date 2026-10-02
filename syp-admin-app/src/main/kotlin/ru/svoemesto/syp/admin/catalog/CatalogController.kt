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
 * Тело запроса создания сериала.
 *
 * @property name название сериала
 * @property sourceRoot корень каталога сериала на машине администратора.
 *   Обязателен: без него не вычислить относительный путь к файлу серии,
 *   который попадёт в сценарий сборки (FR-089a)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CreateMovieRequest(
    val name: String,
    val sourceRoot: String,
)

/**
 * Тело запроса регистрации серии.
 *
 * @property sourcePath путь к исходному видеофайлу внутри корня сериала
 * @property name название серии; если не задано, берётся имя файла
 * @property seasonId сезон-владелец; не задан — у фильма, у которого
 *   сезонов нет
 * @property episodeOrdinal номер эпизода внутри сезона; 0 — у фильма
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RegisterEpisodeRequest(
    val sourcePath: String,
    val name: String? = null,
    val seasonId: Long? = null,
    val episodeOrdinal: Int = 0,
)

/**
 * Описание сериала в ответе.
 *
 * Время отдаётся производной величиной — `durationSeconds` и `timeBase` — и
 * ни одним полем ответа не является вторым источником правды: все они
 * вычислены от номера кадра и частокадровой базы (ADR-0001).
 *
 * @property id идентификатор серии
 * @property movieId сериал-владелец
 * @property ordinal порядковый номер в сериале
 * @property name название серии
 * @property sourcePath абсолютный путь к файлу
 * @property relativePath путь относительно корня сериала: он и попадёт в
 *   сценарий сборки
 * @property byteSize размер файла в байтах
 * @property fileMtime время изменения файла
 * @property frameCount число кадров
 * @property timeBaseNum числитель длительности кадра в секундах
 * @property timeBaseDen знаменатель длительности кадра в секундах
 * @property frameRate частокадровая база в виде `кадров/секунду`
 * @property width ширина кадра
 * @property height высота кадра
 * @property durationSeconds длительность серии, вычисленная по кадрам
 * @property videoCodec кодек видео
 * @property videoProfile профиль видео
 * @property pixelFormat формат пикселей
 * @property audioCodec кодек аудио
 * @property audioChannels число аудиоканалов
 * @property audioSampleRate частота дискретизации аудио
 * @property keyframeCount сколько ключевых кадров в серии
 * @property keyframeMapBytes длина карты ключевых кадров в байтах
 * @property ready готова ли серия к работе: карта ключевых кадров посчитана
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class EpisodeView(
    val id: Long,
    val movieId: Long,
    val ordinal: Int,
    val name: String,
    val seasonId: Long?,
    val seasonOrdinal: Int?,
    val episodeOrdinal: Int,
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
 * Описание сериала в ответе.
 *
 * @property id идентификатор сериала
 * @property name название сериала
 * @property sourceRoot корень каталога сериала
 * @property createdAt дата создания
 * @property episodeCount сколько серий заведено
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class MovieView(
    val id: Long,
    val name: String,
    val sourceRoot: String,
    val createdAt: Instant?,
    val episodeCount: Int,
)

/**
 * Ответ на создание сериала.
 *
 * Настройки идут вместе с сериалом: они создаются автоматически, и оператор
 * правит их сразу же. Отдельный запрос за ними был бы лишним обращением: у
 * только что созданного сериала настроек не может не быть.
 *
 * @property movie созданный сериал
 * @property settings значения настроек по умолчанию
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class CreatedMovieView(
    val movie: MovieView,
    val settings: List<SettingView>,
)

/**
 * Ответ на чтение сериала.
 *
 * @property movie сериал
 * @property episode серии сериала
 * @property settings настройки сериала
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class MovieDetailView(
    val movie: MovieView,
    val episode: List<EpisodeView>,
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
 * Эндпоинты приёма сериала и серии.
 *
 * Здесь только приём: создать сериал, завести серию, прочитать параметры,
 * снять с учёта и настроить пороги. Всё, что требует чтения всего файла
 * целиком, живёт в заданиях очереди, а не в HTTP-запросе: иначе одна
 * регистрация серии занимала бы соединение интерфейса на минуты.
 *
 * Ответы содержат те же поля, что и контракт
 * [`admin-api.md`](../../../../../specs/001-first-vertical-slice/contracts/admin-api.md),
 * раздел 3. Коды ошибок общие с публичной частью и приходят из
 * `ErrorCode`: интерфейс принимает решение по коду, человек читает текст.
 *
 * @property movies хранилище сериалов
 * @property episodeStore хранилище серий
 * @property settingsStore хранилище настроек
 * @property registration регистрация серии с проверкой пути
 * @property checksums постановщик подсчёта суммы: при регистрации серии
 *   подсчёт ставится автоматически, без актуальной суммы сценарий отдать
 *   нельзя (FR-089)
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class CatalogController(
    private val movies: MovieStore,
    private val episodeStore: EpisodeStore,
    private val settingsStore: MovieSettingsStore,
    private val registration: EpisodeRegistration,
    private val checksums: ChecksumEnqueuer? = null,
    private val staleness: Staleness? = null,
) {
    /**
     * Перечисляет сериалы с числом серий каждого.
     *
     * @return список сериалов
     */
    @GetMapping("/api/movies")
    fun listMovies(): List<MovieView> = movies.listWithEpisodeCount().map { it.movie.toView(it.episodeCount) }

    /**
     * Создаёт сериал.
     *
     * Ответ содержит значения настроек по умолчанию: они создаются триггером
     * базы, и оператор правит их сразу (ADR-0003).
     *
     * @param request название и корень каталога
     * @return созданный сериал с настройками, код `201`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `CONFLICT`, если название занято
     */
    @PostMapping("/api/movies")
    fun createMovie(
        @RequestBody request: CreateMovieRequest,
    ): ResponseEntity<CreatedMovieView> {
        val movie = movies.create(request.name, request.sourceRoot)
        val body = CreatedMovieView(movie.toView(0), settingsView(movie.id!!))
        return ResponseEntity.status(HttpStatus.CREATED).body(body)
    }

    /**
     * Читает сериал, его серии и настройки.
     *
     * @param movieId идентификатор сериала
     * @return сериал с сериями и настройками
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если сериала нет
     */
    @GetMapping("/api/movies/{movieId}")
    fun readMovie(
        @PathVariable movieId: Long,
    ): MovieDetailView {
        val movie = requireMovie(movieId)
        return MovieDetailView(
            movie = movie.toView(episodeStore.countByMovie(movieId)),
            episode = episodeStore.listByMovie(movieId).map { it.toView(movie) },
            settings = settingsView(movieId),
        )
    }

    /**
     * Удаляет сериал вместе со всеми производными данными.
     *
     * Файлы архива при этом не трогаются: они принадлежат не системе.
     * Операция необратима и подтверждается оператором.
     *
     * @param movieId идентификатор сериала
     * @return пустой ответ, код `204`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если сериала нет
     */
    @DeleteMapping("/api/movies/{movieId}")
    fun deleteMovie(
        @PathVariable movieId: Long,
    ): ResponseEntity<Void> {
        requireMovie(movieId)
        movies.delete(movieId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Перечисляет серии сериала.
     *
     * @param movieId идентификатор сериала
     * @return серии в порядке порядковых номеров
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если сериала нет
     */
    @GetMapping("/api/movies/{movieId}/episodes")
    fun listEpisode(
        @PathVariable movieId: Long,
    ): List<EpisodeView> {
        val movie = requireMovie(movieId)
        return episodeStore.listByMovie(movieId).map { it.toView(movie) }
    }

    /**
     * Регистрирует серию в сериале.
     *
     * Путь обязан лежать внутри корня каталога сериала, а файл обязан
     * существовать и читаться: иначе ответ — ошибка `SOURCE_UNREADABLE`, а не
     * «успех с пустым результатом» (FR-092). Параметры снимаются с самого
     * файла, оператором не вводятся (FR-002).
     *
     * @param movieId идентификатор сериала
     * @param request путь к файлу и, по желанию, название серии
     * @return зарегистрированная серия с определёнными параметрами, код `201`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `SOURCE_UNREADABLE`, если путь вне корня или файл недоступен
     */
    @PostMapping("/api/movies/{movieId}/episodes")
    fun registerEpisode(
        @PathVariable movieId: Long,
        @RequestBody request: RegisterEpisodeRequest,
    ): ResponseEntity<EpisodeView> {
        val movie = requireMovie(movieId)
        val registered =
            registration.register(
                movieId,
                request.sourcePath,
                request.name,
                request.seasonId,
                request.episodeOrdinal,
            )
        // Подсчёт суммы ставится сразу: он считается заданием и идёт в фоне,
        // а ждать его в этом запросе нельзя — это нарушало бы constitution
        // IV.1 (FR-003). Отказ постановки не отменяет регистрацию: серия уже
        // заведена, а пересчёт можно поставить кнопкой.
        runCatching { checksums?.enqueueAutomatic(registered) }
        return ResponseEntity.status(HttpStatus.CREATED).body(registered.toView(movie))
    }

    /**
     * Читает параметры серии и состояние готовности.
     *
     * @param episodeId идентификатор серии
     * @return параметры серии
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если серии нет
     */
    @GetMapping("/api/episodes/{episodeId}")
    fun readEpisode(
        @PathVariable episodeId: Long,
    ): EpisodeView {
        val episode = requireEpisode(episodeId)
        return episode.toView(requireMovie(episode.movieId))
    }

    /**
     * Снимает серию с учёта.
     *
     * Файл источника не трогается — он лежит в архиве и принадлежит не
     * системе. Удаляются записи о серии и производные от них данные.
     *
     * @param episodeId идентификатор серии
     * @return пустой ответ, код `204`
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если серии нет
     */
    @DeleteMapping("/api/episodes/{episodeId}")
    fun deleteEpisode(
        @PathVariable episodeId: Long,
    ): ResponseEntity<Void> {
        requireEpisode(episodeId)
        episodeStore.delete(episodeId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Читает настройки анализа и выдачи сценария.
     *
     * @param movieId идентификатор сериала
     * @return настройки сериала
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `NOT_FOUND`, если сериала нет
     */
    @GetMapping("/api/movies/{movieId}/settings")
    fun readSettings(
        @PathVariable movieId: Long,
    ): List<SettingView> {
        requireMovie(movieId)
        return settingsView(movieId)
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
     * @param movieId идентификатор сериала
     * @param changes новые значения по именам настроек
     * @return настройки после изменения и список действительно изменившихся
     * @throws ru.svoemesto.syp.core.contract.DomainException с кодом
     *   `BAD_REQUEST`, если ключ неизвестен или значение не подходит
     */
    @PutMapping("/api/movies/{movieId}/settings")
    fun updateSettings(
        @PathVariable movieId: Long,
        @RequestBody changes: Map<String, JsonNode>,
    ): SettingsUpdateView {
        requireMovie(movieId)
        val changed = settingsStore.update(movieId, changes)
        if (changed.isNotEmpty()) {
            staleness?.markStaleForMovie(movieId, SceneDetector.paramsHashOf(settingsStore.read(movieId)))
        }
        return SettingsUpdateView(settingsView(movieId), changed)
    }

    /**
     * Читает сериал или отказывает.
     *
     * Отказ `NOT_FOUND`, а не пустой список: пустой ответ на «сериала нет»
     * выглядел бы как «у сериала нет серий», и интерфейс показал бы пустую
     * страницу вместо того, чтобы сказать, что сериал не заведён.
     *
     * @param movieId идентификатор сериала
     * @return сериал
     * @throws DomainException с кодом `NOT_FOUND`, если сериала нет
     */
    private fun requireMovie(movieId: Long): Movie =
        movies.find(movieId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "сериал $movieId не заведён")

    /**
     * Читает серию или отказывает.
     *
     * @param episodeId идентификатор серии
     * @return серия
     * @throws DomainException с кодом `NOT_FOUND`, если серии нет
     */
    private fun requireEpisode(episodeId: Long): Episode =
        episodeStore.find(episodeId)
            ?: throw DomainException(ErrorCode.NOT_FOUND, "серия $episodeId не зарегистрирована")

    /** Собирает список настроек сериала для ответа. */
    private fun settingsView(movieId: Long): List<SettingView> {
        val read = settingsStore.read(movieId)
        return MovieSetting.entries.map { setting ->
            SettingView(
                key = setting.key,
                title = setting.title,
                kind = setting.kind.name,
                value = read.node(setting),
                updatedAt = settingsStore.updatedAt(movieId, setting)?.toInstant(),
            )
        }
    }
}

/**
 * Описание сериала для ответа.
 *
 * @param episodeCount сколько серий заведено
 * @return описание сериала
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun Movie.toView(episodeCount: Int): MovieView =
    MovieView(
        id = id!!,
        name = name,
        sourceRoot = sourceRoot,
        createdAt = createdAt?.toInstant(),
        episodeCount = episodeCount,
    )

/**
 * Описание серии для ответа.
 *
 * @param movie сериал-владелец: из него берётся корень для относительного пути
 * @return описание серии
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
internal fun Episode.toView(
    movie: Movie,
    seasonOrdinal: Int? = null,
): EpisodeView =
    EpisodeView(
        id = id!!,
        movieId = movieId,
        ordinal = ordinal,
        name = name,
        seasonId = seasonId,
        seasonOrdinal = seasonOrdinal,
        episodeOrdinal = episodeOrdinal,
        designation = "S%02dE%02d".format(seasonOrdinal ?: 0, episodeOrdinal),
        sourcePath = sourcePath,
        relativePath = relativePath(movie.sourceRoot),
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
private fun Episode.frameRateNumerator(): Long = timeBaseDen.toLong() / gcdOf(timeBaseNum, timeBaseDen)

/** Знаменатель частоты кадров: числитель длительности кадра. */
private fun Episode.frameRateDenominator(): Long = timeBaseNum.toLong() / gcdOf(timeBaseNum, timeBaseDen)

/** НОД двух чисел. */
private fun gcdOf(
    first: Int,
    second: Int,
): Int = if (second == 0) first else gcdOf(second, first % second)
