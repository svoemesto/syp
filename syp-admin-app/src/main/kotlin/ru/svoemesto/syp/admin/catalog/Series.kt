package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.time.OffsetDateTime

/**
 * Серия — один видеофайл сериала.
 *
 * Параметры серии — **единственный** источник сведений о совместимости при
 * сборке (FR-087): сравниваются разрешение, кодек, профиль, формат пикселей,
 * частокадровая база и параметры звука. Ни одна из этих величин не вводится
 * оператором: они сняты с файла опросом.
 *
 * Время не хранится. [timeBaseNum] и [timeBaseDen] — длительность кадра в
 * секундах, а [durationNum] и [durationDen] вычислены из неё и [frameCount].
 * Номер кадра остаётся единственным источником правды для всех границ
 * (ADR-0001).
 *
 * @property id идентификатор; `null`, пока серия не записана
 * @property serialId сериал-владелец: одна серия принадлежит ровно одному
 * @property ordinal порядковый номер серии в сериале, уникален в его пределах
 * @property name название серии
 * @property sourcePath абсолютный путь к исходному видеофайлу, уникален
 * @property byteSize размер файла в байтах
 * @property fileMtime время изменения файла: по нему устаревает посчитанная
 *   сумма и обнаруживается подмена источника (FR-090)
 * @property frameCount число кадров серии
 * @property timeBaseNum числитель длительности кадра в секундах
 * @property timeBaseDen знаменатель длительности кадра в секундах
 * @property width ширина кадра в пикселях
 * @property height высота кадра в пикселях
 * @property durationNum числитель длительности серии в секундах
 * @property durationDen знаменатель длительности серии в секундах
 * @property videoCodec кодек видео
 * @property videoProfile профиль видео; может быть пустым
 * @property pixelFormat формат пикселей
 * @property audioCodec кодек аудио; `null` у серии без звука
 * @property audioChannels число аудиоканалов; `null` у серии без звука
 * @property audioSampleRate частота дискретизации; `null` у серии без звука
 * @property keyframeMap карта ключевых кадров; `null`, пока карта не посчитана
 * @property previewSheetCount сколько листов превью уже создано
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Series(
    val id: Long? = null,
    val serialId: Long,
    val ordinal: Int,
    val name: String,
    val season: Int? = null,
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
        require(name.isNotBlank()) { "Название серии обязательно" }
        require(sourcePath.startsWith("/")) {
            "Путь к файлу серии обязан быть абсолютным, задано «$sourcePath»: " +
                "иначе относительный путь в сценарии был бы выдуман (FR-089a)"
        }
        require(frameCount > 0) { "Число кадров серии должно быть положительным, задано $frameCount" }
        require(timeBaseNum > 0 && timeBaseDen > 0) {
            "Частокадровая база серии должна быть положительной, задано $timeBaseNum/$timeBaseDen"
        }
        require(durationNum > 0 && durationDen > 0) {
            "Длительность серии должна быть положительной, задано $durationNum/$durationDen"
        }
        require(width > 0 && height > 0) { "Разрешение серии должно быть положительным, задано $width на $height" }
        require(ordinal >= 0) { "Порядковый номер серии не может быть отрицательным, задано $ordinal" }
        require(byteSize > 0) { "Размер файла серии должен быть положительным, задано $byteSize" }
    }

    /** Длительность кадра в секундах. */
    fun frameDurationSeconds(): Double = timeBaseNum.toDouble() / timeBaseDen

    /** Длительность серии в секундах, вычисленная по кадрам. */
    fun durationSeconds(): Double = durationNum.toDouble() / durationDen

    /**
     * Относительный путь к файлу серии от корня сериала.
     *
     * Именно этот путь попадает в сценарий сборки: у пользователя своя копия
     * дерева под своим корнем, и путь должен быть одинаков при любом корне
     * (FR-089a). Относительный путь не выдумывается: он вычисляется из
     * проверенного соотношения путей при регистрации серии.
     *
     * @param sourceRoot корень каталога сериала
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
     * @return таблица с записываемыми столбцами серии
     */
    fun toTable(): Table =
        Table(
            NAME,
            COLUMNS,
            {
                listOf(
                    serialId,
                    ordinal,
                    name,
                    season,
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
        /** Имя таблицы серий. */
        const val NAME: String = "series"

        /** Записываемые столбцы серии в порядке значений. */
        val COLUMNS: List<String> =
            listOf(
                "serial_id",
                "ordinal",
                "name",
                "season",
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

        /** Столбцы серии в порядке чтения из базы. */
        val READ_COLUMNS: String =
            (
                "id, serial_id, ordinal, name, season, source_path, file_size, file_mtime, " +
                    "frame_count, time_base_num, time_base_den, width, height, " +
                    "duration_num, duration_den, video_codec, video_profile, pixel_format, " +
                    "audio_codec, audio_channels, audio_sample_rate, keyframe_bitmap, " +
                    "preview_sheet_count, recordhash"
            )

        /**
         * Собирает серию из определённых опросом параметров файла.
         *
         * @param serialId сериал-владелец
         * @param ordinal порядковый номер в сериале
         * @param name название серии
         * @param season номер сезона; не задан — у серий вне сезонов
         * @param sourcePath абсолютный путь к файлу
         * @param parameters параметры, снятые с файла опросом
         * @return готовая к записи серия
         */
        fun of(
            serialId: Long,
            ordinal: Int,
            name: String,
            season: Int?,
            sourcePath: String,
            parameters: SourceParameters,
        ): Series =
            Series(
                serialId = serialId,
                ordinal = ordinal,
                name = name,
                season = season,
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
 * Хранилище серий.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SeriesStore(
    private val db: Db,
) {
    /**
     * Записывает серию и возвращает её с идентификатором и хешем.
     *
     * @param series серия для записи
     * @return записанная серия
     * @throws DomainException с кодом `CONFLICT`, если такой путь уже занят
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun insert(series: Series): Series {
        val duplicate = findBySourcePath(series.sourcePath)
        if (duplicate != null) {
            throw DomainException(
                ErrorCode.CONFLICT,
                "файл «${series.sourcePath}» уже зарегистрирован как серия «${duplicate.name}»: " +
                    "одна серия на один файл, второй раз завести его нельзя",
            )
        }
        return db.useTransaction { connection ->
            Save.insertIfAbsent(connection, series.toTable())
            val identifier = readIdentifier(connection, series)
            readOne(connection, identifier)
                ?: throw DomainException(
                    ErrorCode.INTERNAL_ERROR,
                    "серия записана, но сразу после записи не прочитана: это дефект, а не результат",
                )
        }
    }

    /**
     * Читает серию по идентификатору.
     *
     * @param seriesId идентификатор серии
     * @return серия или `null`, если её нет
     */
    fun find(seriesId: Long): Series? = db.selectOne("SELECT ${Series.READ_COLUMNS} FROM series WHERE id = ?", ::readRow, seriesId)

    /**
     * Перечисляет серии сериала.
     *
     * @param serialId идентификатор сериала
     * @return серии в порядке порядковых номеров
     */
    fun listBySerial(serialId: Long): List<Series> =
        db.select(
            "SELECT ${Series.READ_COLUMNS} FROM series WHERE serial_id = ? ORDER BY ordinal",
            ::readRow,
            serialId,
        )

    /**
     * Ищет серию по пути к файлу.
     *
     * @param sourcePath абсолютный путь к файлу
     * @return серия или `null`, если такой путь не заведён
     */
    fun findBySourcePath(sourcePath: String): Series? =
        db.selectOne("SELECT ${Series.READ_COLUMNS} FROM series WHERE source_path = ?", ::readRow, sourcePath)

    /**
     * Сохраняет изменения серии, если значения изменились.
     *
     * @param series серия с заполненным [Series.id]
     * @return `true`, если строка переписана
     * @throws DomainException если у серии нет идентификатора
     */
    fun save(series: Series): Boolean {
        val seriesId =
            series.id
                ?: throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "у серии «${series.name}» нет идентификатора: сохранять нечего",
                )
        return db.useTransaction { connection ->
            Save.saveIfChanged(connection, series.toTable(), listOf("id"), listOf(seriesId))
        }
    }

    /**
     * Снимает серию с учёта.
     *
     * Файл источника при этом **не трогается**: он лежит в архиве и принадлежит
     * не системе. Удаляются только записи о нём и производные от них данные.
     *
     * @param seriesId идентификатор серии
     * @return `true`, если серия была удалена
     */
    fun delete(seriesId: Long): Boolean = db.update("DELETE FROM series WHERE id = ?", seriesId) > 0

    /**
     * Считает серии сериала.
     *
     * @param serialId идентификатор сериала
     * @return число серий
     */
    fun countBySerial(serialId: Long): Int =
        db.selectOne(
            "SELECT count(*) AS total FROM series WHERE serial_id = ?",
            { it.int("total") },
            serialId,
        ) ?: 0

    /** Читает идентификатор только что записанной серии. */
    private fun readIdentifier(
        connection: java.sql.Connection,
        series: Series,
    ): Long =
        connection
            .prepareStatement("SELECT id FROM series WHERE source_path = ?")
            .use { statement ->
                statement.setString(1, series.sourcePath)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) {
                        resultSet.getLong(1)
                    } else {
                        throw DomainException(
                            ErrorCode.INTERNAL_ERROR,
                            "серия «${series.name}» записана, но идентификатор не прочитан",
                        )
                    }
                }
            }

    /** Читает серию по идентификатору в пределах открытого соединения. */
    private fun readOne(
        connection: java.sql.Connection,
        seriesId: Long,
    ): Series? =
        connection
            .prepareStatement("SELECT ${Series.READ_COLUMNS} FROM series WHERE id = ?")
            .use { statement ->
                statement.setLong(1, seriesId)
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next()) read(resultSet) else null
                }
            }

    /** Строит серию из готовой строки результата. */
    private fun read(resultSet: java.sql.ResultSet): Series =
        Series(
            id = resultSet.getLong("id"),
            serialId = resultSet.getLong("serial_id"),
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

    /** Строит серию из типизированной строки выборки. */
    private fun readRow(row: Row): Series =
        Series(
            id = row.long("id"),
            serialId = row.long("serial_id"),
            ordinal = row.int("ordinal"),
            name = row.string("name"),
            season = row.intOrNull("season"),
            sourcePath = row.string("source_path"),
            byteSize = row.long("file_size"),
            fileMtime =
                (row.raw("file_mtime") as? java.sql.Timestamp)
                    ?.toInstant()
                    ?.atOffset(java.time.ZoneOffset.UTC)
                    ?: throw DomainException(
                        ErrorCode.INTERNAL_ERROR,
                        "у серии не прочитано время изменения файла: в базе оно обязательно",
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
