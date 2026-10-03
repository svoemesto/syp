package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.time.OffsetDateTime

/**
 * Эпизод — один видеофайл фильма.
 *
 * Параметры эпизода — **единственный** источник сведений о совместимости при
 * сборке (FR-087): сравниваются разрешение, кодек, профиль, формат пикселей,
 * частокадровая база и параметры звука. Ни одна из этих величин не вводится
 * оператором: они сняты с файла опросом.
 *
 * Время не хранится. [timeBaseNum] и [timeBaseDen] — длительность кадра в
 * секундах, а [durationNum] и [durationDen] вычислены из неё и [frameCount].
 * Номер кадра остаётся единственным источником правды для всех границ
 * (ADR-0001).
 *
 * @property id идентификатор; `null`, пока эпизод не записана
 * @property projectId фильм-владелец: один эпизод принадлежит ровно одному
 * @property ordinal порядковый номер эпизода в фильме, уникален в его пределах
 * @property name название эпизода
 * @property sourcePath абсолютный путь к исходному видеофайлу, уникален
 * @property byteSize размер файла в байтах
 * @property fileMtime время изменения файла: по нему устаревает посчитанная
 *   сумма и обнаруживается подмена источника (FR-090)
 * @property frameCount число кадров эпизода
 * @property timeBaseNum числитель длительности кадра в секундах
 * @property timeBaseDen знаменатель длительности кадра в секундах
 * @property width ширина кадра в пикселях
 * @property height высота кадра в пикселях
 * @property durationNum числитель длительности эпизода в секундах
 * @property durationDen знаменатель длительности эпизода в секундах
 * @property videoCodec кодек видео
 * @property videoProfile профиль видео; может быть пустым
 * @property pixelFormat формат пикселей
 * @property audioCodec кодек аудио; `null` у эпизода без звука
 * @property audioChannels число аудиоканалов; `null` у эпизода без звука
 * @property audioSampleRate частота дискретизации; `null` у эпизода без звука
 * @property keyframeMap карта ключевых кадров; `null`, пока карта не посчитана
 * @property previewSheetCount сколько листов превью уже создано
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Videofile(
    val id: Long? = null,
    val projectId: Long,
    val ordinal: Int,
    val name: String,
    val seasonNumber: Int? = null,
    val videofileOrdinal: Int = 0,
    val sourcePath: String,
    val byteSize: Long,
    val fileMtime: OffsetDateTime,
    val frameCount: Int,
    val timeBaseNum: Int,
    val timeBaseDen: Int,
    val width: Int,
    val height: Int,
    val durationNum: Long,
    val durationDen: Long,
    val videoCodec: String,
    val videoProfile: String?,
    val pixelFormat: String,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val keyframeMap: KeyframeMap? = null,
    val previewSheetCount: Int = 0,
    val recordHash: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Название эпизода обязательно" }
        require(sourcePath.startsWith("/")) {
            "Путь к файлу эпизода обязан быть абсолютным, задано «$sourcePath»: " +
                "иначе относительный путь в сценарии был бы выдуман (FR-089a)"
        }
        require(frameCount > 0) { "Число кадров эпизода должно быть положительным, задано $frameCount" }
        require(timeBaseNum > 0 && timeBaseDen > 0) {
            "Частокадрового база эпизода должна быть положительной, задано $timeBaseNum/$timeBaseDen"
        }
        require(durationNum > 0 && durationDen > 0) {
            "Длительность эпизода должна быть положительной, задано $durationNum/$durationDen"
        }
        require(width > 0 && height > 0) { "Разрешение эпизода должно быть положительным, задано $width на $height" }
        require(ordinal >= 0) { "Порядковый номер эпизода не может быть отрицательным, задано $ordinal" }
        require(byteSize > 0) { "Размер файла эпизода должен быть положительным, задано $byteSize" }
    }

    /** Длительность кадра в секундах. */
    fun frameDurationSeconds(): Double = timeBaseNum.toDouble() / timeBaseDen

    /** Длительность эпизода в секундах, вычисленная по кадрам. */
    fun durationSeconds(): Double = durationNum.toDouble() / durationDen

    /**
     * Относительный путь к файлу эпизода от корня фильма.
     *
     * Именно этот путь попадает в сценарий сборки: у пользователя своя копия
     * дерева под своим корнем, и путь должен быть одинаков при любом корне
     * (FR-089a). Относительный путь не выдумывается: он вычисляется из
     * проверенного соотношения путей при регистрации эпизода.
     *
     * @param sourceRoot корень каталога фильма
     * @return путь без ведущего слэша либо `null`, если файл лежит вне корня
     */
    fun relativePath(sourceRoot: String): String? {
        val root = sourceRoot.trimEnd('/')
        if (!sourcePath.startsWith("$root/")) return null
        return sourcePath.removePrefix("$root/")
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами эпизода
     */
    fun toTable(): Table =
        Table(
            NAME,
            COLUMNS,
            {
                listOf(
                    projectId,
                    ordinal,
                    name,
                    seasonNumber,
                    videofileOrdinal,
                    sourcePath,
                    byteSize,
                    fileMtime,
                    frameCount,
                    timeBaseNum,
                    timeBaseDen,
                    width,
                    height,
                    durationNum,
                    durationDen,
                    videoCodec,
                    videoProfile,
                    pixelFormat,
                    audioCodec,
                    audioChannels,
                    audioSampleRate,
                    keyframeMap?.toByteArray(),
                    previewSheetCount,
                )
            },
            recordHash,
        )

    companion object {
        /** Имя таблицы эпизодов. */
        const val NAME: String = "tbl_videofiles"

        /** Записываемые столбцы эпизода в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "id_project",
                "ordinal",
                "name",
                "season_number",
                "videofile_ordinal",
                "source_path",
                "file_size",
                "file_mtime",
                "frame_count",
                "time_base_num",
                "time_base_den",
                "width",
                "height",
                "duration_num",
                "duration_den",
                "video_codec",
                "video_profile",
                "pixel_format",
                "audio_codec",
                "audio_channels",
                "audio_sample_rate",
                "keyframe_bitmap",
                "preview_sheet_count",
            )

        /** Столбцы эпизода в порядке чтения из базы. */
        val READ_COLUMNS: String =
            (
                "id, id_project, ordinal, name, season_number, videofile_ordinal, source_path, file_size, file_mtime, " +
                    "frame_count, time_base_num, time_base_den, width, height, " +
                    "duration_num, duration_den, video_codec, video_profile, pixel_format, " +
                    "audio_codec, audio_channels, audio_sample_rate, keyframe_bitmap, " +
                    "preview_sheet_count, recordhash"
            )

        /**
         * Собирает эпизод из определённых опросом параметров файла.
         *
         * @param projectId фильм-владелец
         * @param ordinal порядковый номер в фильме
         * @param name название эпизода
         * @param seasonNumber сезон-владелец; не задан — у фильма
         * @param videofileOrdinal номер эпизода внутри сезона; 0 — у фильма
         * @param sourcePath абсолютный путь к файлу
         * @param parameters параметры, снятые с файла опросом
         * @return готовая к записи эпизод
         */
        fun of(
            projectId: Long,
            ordinal: Int,
            name: String,
            seasonNumber: Int?,
            videofileOrdinal: Int,
            sourcePath: String,
            parameters: SourceParameters,
        ): Videofile =
            Videofile(
                projectId = projectId,
                ordinal = ordinal,
                name = name,
                seasonNumber = seasonNumber,
                videofileOrdinal = videofileOrdinal,
                sourcePath = sourcePath,
                byteSize = parameters.byteSize,
                fileMtime = parameters.fileMtime.atOffset(java.time.ZoneOffset.UTC),
                frameCount = parameters.frameCount,
                timeBaseNum = parameters.timeBaseNum,
                timeBaseDen = parameters.timeBaseDen,
                width = parameters.width,
                height = parameters.height,
                durationNum = parameters.durationNum,
                durationDen = parameters.durationDen,
                videoCodec = parameters.videoCodec,
                videoProfile = parameters.videoProfile,
                pixelFormat = parameters.pixelFormat,
                audioCodec = parameters.audioCodec,
                audioChannels = parameters.audioChannels,
                audioSampleRate = parameters.audioSampleRate,
                keyframeMap = parameters.keyframes,
            )
    }
}

/**
 * Хранилище эпизодов.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class VideofileStore(
    private val db: Db,
) {
    /**
     * Записывает эпизод и возвращает её с идентификатором и хешем.
     *
     * @param videofile эпизод для записи
     * @return записанный эпизод
     * @throws DomainException с кодом `CONFLICT`, если такой путь уже занят
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun insert(videofile: Videofile): Videofile {
        val duplicate = findBySourcePath(videofile.sourcePath)
        if (duplicate != null) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "файл «${videofile.sourcePath}» уже зарегистрирован как эпизод «${duplicate.name}»: " +
                    "один эпизод на один файл, второй раз завести его нельзя",
            )
        }
        return db.useTransaction { connection ->
            Save.insertIfAbsent(connection, videofile.toTable())
            val identifier = readIdentifier(connection, videofile)
            readOne(connection, identifier)
                ?: throw DomainException(
                    ErrorCode.INTERNAL_ERROR,
                    "эпизод записана, но сразу после записи не прочитана: это дефект, а не результат",
                )
        }
    }

    /**
     * Читает эпизод по идентификатору.
     *
     * @param videofileId идентификатор эпизода
     * @return эпизод или `null`, если её нет
     */
    fun find(videofileId: Long): Videofile? =
        db.selectOne("SELECT ${Videofile.READ_COLUMNS} FROM tbl_videofiles WHERE id = ?", ::readRow, videofileId)

    /**
     * Перечисляет эпизоды фильма.
     *
     * @param projectId идентификатор фильма
     * @return эпизода в порядке порядковых номеров
     */
    fun listByProject(projectId: Long): List<Videofile> =
        db.select(
            "SELECT ${Videofile.READ_COLUMNS} FROM tbl_videofiles WHERE id_project = ? ORDER BY ordinal",
            ::readRow,
            projectId,
        )

    /**
     * Ищет эпизод по пути к файлу.
     *
     * @param sourcePath абсолютный путь к файлу
     * @return эпизод или `null`, если такой путь не заведён
     */
    fun findBySourcePath(sourcePath: String): Videofile? =
        db.selectOne("SELECT ${Videofile.READ_COLUMNS} FROM tbl_videofiles WHERE source_path = ?", ::readRow, sourcePath)

    /**
     * Сохраняет изменения эпизода, если значения изменились.
     *
     * @param videofile эпизод с заполненным [Videofile.id]
     * @return `true`, если строка переписана
     * @throws DomainException если у эпизода нет идентификатора
     */
    fun save(videofile: Videofile): Boolean {
        val videofileId =
            videofile.id
                ?: throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "у эпизода «${videofile.name}» нет идентификатора: сохранять нечего",
                )
        return db.useTransaction { connection ->
            Save.saveIfChanged(connection, videofile.toTable(), listOf("id"), listOf(videofileId))
        }
    }

    /**
     * Снимает эпизод с учёта.
     *
     * Файл источника при этом **не трогается**: он лежит в архиве и принадлежит
     * не системе. Удаляются только записи о нём и производные от них данные.
     *
     * @param videofileId идентификатор эпизода
     * @return `true`, если эпизод была удалена
     */
    fun delete(videofileId: Long): Boolean = db.update("DELETE FROM tbl_videofiles WHERE id = ?", videofileId) > 0

    /**
     * Считает эпизоды фильма.
     *
     * @param projectId идентификатор фильма
     * @return число эпизодов
     */
    fun countByProject(projectId: Long): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM tbl_videofiles WHERE id_project = ?",
            { it.int("total") },
            projectId,
        ) ?: 0

    /** Читает идентификатор только что записанного эпизода. */
    private fun readIdentifier(
        connection: java.sql.Connection,
        videofile: Videofile,
    ): Long =
        connection
            .prepareStatement("SELECT id FROM tbl_videofiles WHERE source_path = ?")
            .use { statement ->
                statement.setString(1, videofile.sourcePath)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getLong(1)
                    } else {
                        throw DomainException(
                            ErrorCode.INTERNAL_ERROR,
                            "эпизод «${videofile.name}» записана, но идентификатор не прочитан",
                        )
                    }
                }
            }

    /** Читает эпизод по идентификатору в пределах открытого соединения. */
    private fun readOne(
        connection: java.sql.Connection,
        videofileId: Long,
    ): Videofile? =
        connection
            .prepareStatement("SELECT ${Videofile.READ_COLUMNS} FROM tbl_videofiles WHERE id = ?")
            .use { statement ->
                statement.setLong(1, videofileId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) read(resultSet) else null
                }
            }

    /** Строит эпизод из готовый строки результата. */
    private fun read(resultSet: java.sql.ResultSet): Videofile =
        Videofile(
            id = resultSet.getLong("id"),
            projectId = resultSet.getLong("id_project"),
            ordinal = resultSet.getInt("ordinal"),
            name = resultSet.getString("name"),
            sourcePath = resultSet.getString("source_path"),
            byteSize = resultSet.getLong("file_size"),
            fileMtime = resultSet.getObject("file_mtime", OffsetDateTime::class.java),
            frameCount = resultSet.getInt("frame_count"),
            timeBaseNum = resultSet.getInt("time_base_num"),
            timeBaseDen = resultSet.getInt("time_base_den"),
            width = resultSet.getInt("width"),
            height = resultSet.getInt("height"),
            durationNum = resultSet.getLong("duration_num"),
            durationDen = resultSet.getLong("duration_den"),
            videoCodec = resultSet.getString("video_codec"),
            videoProfile = resultSet.getString("video_profile"),
            pixelFormat = resultSet.getString("pixel_format"),
            audioCodec = resultSet.getString("audio_codec"),
            audioChannels = resultSet.nullableInt("audio_channels"),
            audioSampleRate = resultSet.nullableInt("audio_sample_rate"),
            keyframeMap =
                resultSet.getBytes("keyframe_bitmap")?.let { bytes ->
                    KeyframeMap.parse(resultSet.getInt("frame_count"), bytes)
                },
            previewSheetCount = resultSet.getInt("preview_sheet_count"),
            recordHash = resultSet.getString("recordhash"),
        )

    /** Строит эпизод из типизированной строки выборки. */
    private fun readRow(row: Row): Videofile =
        Videofile(
            id = row.long("id"),
            projectId = row.long("id_project"),
            ordinal = row.int("ordinal"),
            name = row.string("name"),
            seasonNumber = row.intOrNull("season_number"),
            videofileOrdinal = row.int("videofile_ordinal"),
            sourcePath = row.string("source_path"),
            byteSize = row.long("file_size"),
            fileMtime =
                (row.raw("file_mtime") as? java.sql.Timestamp)
                    ?.toInstant()
                    ?.atOffset(java.time.ZoneOffset.UTC)
                    ?: throw DomainException(
                        ErrorCode.INTERNAL_ERROR,
                        "у эпизода не прочитано время изменения файла: в базе оно обязательно",
                    ),
            frameCount = row.int("frame_count"),
            timeBaseNum = row.int("time_base_num"),
            timeBaseDen = row.int("time_base_den"),
            width = row.int("width"),
            height = row.int("height"),
            durationNum = row.long("duration_num"),
            durationDen = row.long("duration_den"),
            videoCodec = row.string("video_codec"),
            videoProfile = row.stringOrNull("video_profile"),
            pixelFormat = row.string("pixel_format"),
            audioCodec = row.stringOrNull("audio_codec"),
            audioChannels = row.intOrNull("audio_channels"),
            audioSampleRate = row.intOrNull("audio_sample_rate"),
            keyframeMap = row.bytesOrNull("keyframe_bitmap")?.let { KeyframeMap.parse(row.int("frame_count"), it) },
            previewSheetCount = row.int("preview_sheet_count"),
            recordHash = row.stringOrNull("recordhash"),
        )
}

/**
 * Читает целое значение столбца, допуская отсутствие значения.
 *
 * Отдельная функция потому, что `getObject` с классом `Int` в Kotlin передаёт
 * драйверу **примитивный** тип, и PostgreSQL-драйвер такой запрос отклоняет
 * («conversion to int from int4 not supported»). Признак отсутствия значения
 * берётся из `wasNull`, как и положено.
 *
 * @param resultSet открытый курсор
 * @param column имя столбца
 * @return значение или `null`, если в столбце `NULL`
 */
private fun java.sql.ResultSet.nullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}
