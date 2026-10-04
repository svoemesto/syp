package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Save
import ru.svoemesto.syp.core.db.Table
import java.sql.Connection

/**
 * Происхождение границы.
 *
 * Значение задаёт цвет оверлея в интерфейсе и отвечает на вопрос, кому
 * принадлежит граница: алгоритму, оператору или это отменённое решение
 * алгоритма (FR-015, FR-016). Отмена — **отдельное** значение, а не
 * отсутствие строки: исчезнувшая граница выглядела бы так же, как граница,
 * которую оператор принял, и сравнить было бы не с чем.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class BoundaryOrigin {
    /** Граница получена алгоритмом. */
    AUTO,

    /** Границу поставил или поправил оператор. */
    OPERATOR,

    /** Решение алгоритма отменено оператором. */
    CANCELLED,
    ;

    companion object {
        /**
         * Разбирает происхождение из строки базы.
         *
         * @param value значение столбца `origin`
         * @return происхождение
         * @throws IllegalArgumentException если значения нет в наборе
         */
        fun parse(value: String): BoundaryOrigin =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестное происхождение границы: «$value»")
    }
}

/**
 * Размер плана.
 *
 * Справочник перенесён из старого проекта как есть: десять ступеней от `ECU`
 * до `XLS` и нулевая `NONE` для плана без лиц (ADR-0003).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class ShotSize {
    /** Размер не определён: в плане нет лиц. */
    NONE,

    /** Очень крупный план. */
    ECU,

    /** Большой крупный план. */
    BCU,

    /** Крупный план. */
    CU,

    /** Средний крупный план. */
    MCU,

    /** Средний план. */
    MS,

    /** Средний общий план. */
    MLS,

    /** Общий план. */
    LS,

    /** Общий план с верхними точками съёмки. */
    VLS,

    /** Очень общий план. */
    XLS,
    ;

    companion object {
        /**
         * Разбирает размер из строки базы.
         *
         * @param value значение столбца `size`
         * @return размер плана
         * @throws IllegalArgumentException если значения нет в наборе
         */
        fun parse(value: String): ShotSize =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестный размер плана: «$value»")
    }
}

/**
 * Происхождение размера плана.
 *
 * Отдельно от происхождения границы: оператор может поправить размер, не
 * трогая границу, и наоборот (FR-043).
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class SizeOrigin {
    /** Размер вычислен автоматически по самому крупному лицу плана. */
    AUTO,

    /** Размер исправлен оператором. */
    OPERATOR,
    ;

    companion object {
        /**
         * Разбирает происхождение размера из строки базы.
         *
         * @param value значение столбца `size_origin`
         * @return происхождение размера
         * @throws IllegalArgumentException если значения нет в наборе
         */
        fun parse(value: String): SizeOrigin =
            entries.firstOrNull { it.name == value }
                ?: throw IllegalArgumentException("Неизвестное происхождение размера: «$value»")
    }
}

/**
 * Рабочего сцена эпизода.
 *
 * Рабочий сцена — текущее состояние разметки, а сырая граница прогона
 * остаётся в своей таблице и ручными правками не меняется (FR-093).
 *
 * Связь сцена ↔ план **не хранится**: она вычисляется по диапазонам кадров,
 * и хранить её списком идентификаторов запрещено (ADR-0007). Хранение связи
 * означало бы второе место, где живёт истина о принадлежности плана сцене, и
 * расхождение этих мест ловилось бы только при чтении.
 *
 * @property id идентификатор сцены; `null`, пока не записана
 * @property videofileId эпизод-владелец
 * @property firstFrame первый кадр сцены, нумерация с нуля
 * @property lastFrame последний кадр сцены
 * @property locationId место действия, если назначено вручную; `null`, если
 *   не назначено (FR-052)
 * @property origin происхождение границы
 * @property runId прогон, которым сцена получена; `null`, если граница
 *   создана вручную
 * @property isStale результат помечен устаревшим
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Scene(
    val id: Long? = null,
    val videofileId: Long,
    val firstFrame: Int,
    val lastFrame: Int,
    val title: String? = null,
    val locationId: Long? = null,
    val origin: BoundaryOrigin = BoundaryOrigin.AUTO,
    val runId: Long? = null,
    val isStale: Boolean = false,
    val recordHash: String? = null,
) {
    init {
        require(firstFrame >= 0) { "Первый кадр сцены не может быть отрицательным, задано $firstFrame" }
        require(lastFrame >= firstFrame) {
            "Последний кадр сцены $lastFrame раньше первого $firstFrame: диапазон вывернут наизнанку"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами сцены
     */
    fun toTable(): Table =
        Table(
            StructureService.SCENE_TABLE,
            StructureService.SCENE_COLUMNS,
            {
                listOf(
                    videofileId,
                    firstFrame,
                    lastFrame,
                    title,
                    locationId,
                    origin.name,
                    runId,
                    isStale,
                )
            },
            recordHash,
        )

    companion object {
        /** Столбцы сцены в порядке чтения из базы. */
        val READ_COLUMNS: String =
            "id, id_videofile, first_frame, last_frame, title, location_id, origin, run_id, is_stale, recordhash"
    }
}

/**
 * Рабочий план эпизода.
 *
 * План лежит в сцене целиком: `first >= scene.first AND last <= scene.last`
 * при равном эпизоде (ADR-0007). Частичное пересечение не допускается: план,
 * наполовину лежащий в сцене, не имеет смысла ни в интерфейсе, ни в
 * сценарии сборки.
 *
 * Столбцы размера и происхождения границы независимы: оператор может
 * исправить размер, не трогая границу, и наоборот.
 *
 * @property id идентификатор плана; `null`, пока не записан
 * @property videofileId эпизод-владелец
 * @property firstFrame первый кадр плана, нумерация с нуля
 * @property lastFrame последний кадр плана
 * @property size размер плана; `NONE` у плана без лиц
 * @property sizeOrigin происхождение размера
 * @property origin происхождение границы
 * @property runId прогон, которым план получен; `null`, если создан вручную
 * @property isStale результат помечен устаревшим
 * @property recordHash хеш значений строки, прочитанный при загрузке
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class Shot(
    val id: Long? = null,
    val videofileId: Long,
    val firstFrame: Int,
    val lastFrame: Int,
    val size: ShotSize = ShotSize.NONE,
    val sizeOrigin: SizeOrigin = SizeOrigin.AUTO,
    val origin: BoundaryOrigin = BoundaryOrigin.AUTO,
    val runId: Long? = null,
    val isStale: Boolean = false,
    val recordHash: String? = null,
) {
    init {
        require(firstFrame >= 0) { "Первый кадр плана не может быть отрицательным, задано $firstFrame" }
        require(lastFrame >= firstFrame) {
            "Последний кадр плана $lastFrame раньше первого $firstFrame: диапазон вывернут наизнанку"
        }
    }

    /**
     * Описание строки для сохранения по различию значений.
     *
     * @return таблица с записываемыми столбцами плана
     */
    fun toTable(): Table =
        Table(
            StructureService.SHOT_TABLE,
            StructureService.SHOT_COLUMNS,
            {
                listOf(
                    videofileId,
                    firstFrame,
                    lastFrame,
                    size.name,
                    sizeOrigin.name,
                    origin.name,
                    runId,
                    isStale,
                )
            },
            recordHash,
        )

    companion object {
        /** Столбцы плана в порядке чтения из базы. */
        val READ_COLUMNS: String =
            "id, id_videofile, first_frame, last_frame, size, size_origin, origin, run_id, " +
                "is_stale, recordhash"
    }
}

/**
 * Участок эпизода по номерам кадров.
 *
 * @property firstFrame первый кадр участка
 * @property lastFrame последний кадр участка
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class FrameRange(
    val firstFrame: Int,
    val lastFrame: Int,
)

/**
 * Разбор границ на сцены и планы.
 *
 * Сырые границы прогона — это **точки**, а не участки: детектор отдаёт номера
 * кадров, в которых он увидел смену. Рабочая структура — это участки: от
 * одной границы до следующей. Перевод делается здесь, единственный раз, и
 * он проверяет главное свойство структуры: **сцены покрывают эпизод без
 * разрывов и перекрытий**.
 *
 * Проверка выполняется до записи. Структура с дырой выглядит на экране
 * нормально — просто часть эпизода не показана, — и заметить её можно только
 * сравнением с числом кадров (FR-011).
 *
 * @property detection результат детекции
 * @property frameCount число кадров эпизода
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class StructureBuilder(
    private val detection: DetectionResult,
    private val frameCount: Int,
) {
    init {
        require(frameCount > 0) { "Число кадров эпизода должно быть положительным, задано $frameCount" }
    }

    /**
     * Разбирает детекцию на участки сцен.
     *
     * @return участки в порядке следования, покрывающие эпизод целиком
     */
    fun sceneSections(): List<FrameRange> = sections(detection.sceneBoundaries)

    /**
     * Разбирает детекцию на участки планов.
     *
     * @return участки в порядке следования, покрывающие эпизод целиком
     */
    fun shotSections(): List<FrameRange> = sections(detection.shotBoundaries)

    /**
     * Разбирает точки границ в участки.
     *
     * Первый участок начинается с кадра 0: эпизод покрыта целиком, и первая
     * граница — это начало второй сцены, а не первая сцена.
     *
     * @param boundaries номера кадров-границ по возрастанию
     * @return участки в порядке следования
     */
    private fun sections(boundaries: List<Int>): List<FrameRange> {
        val starts = listOf(0) + boundaries.filter { it > 0 }
        val distinct = starts.distinct().sorted()
        val ranges =
            distinct.mapIndexed { index, first ->
                val last = distinct.getOrElse(index + 1) { frameCount } - 1
                FrameRange(first, last)
            }
        require(ranges.all { it.firstFrame >= 0 && it.lastFrame >= it.firstFrame }) {
            "Границы разобрались в пустой участок: $ranges"
        }
        require(ranges.first().firstFrame == 0 && ranges.last().lastFrame == frameCount - 1) {
            "Участки не покрывают эпизод целиком: с ${ranges.first().firstFrame} по " +
                "${ranges.last().lastFrame} при ${frameCount - 1} кадрах эпизода"
        }
        ranges.forEachIndexed { index, range ->
            val next = ranges.getOrNull(index + 1)
            require(next == null || range.lastFrame + 1 == next.firstFrame) {
                "Между участками есть разрыв или перекрытие: ${range.lastFrame} и ${next?.firstFrame}"
            }
        }
        return ranges
    }
}

/**
 * Создание рабочего структуры эпизода по результату прогона.
 *
 * Рабочие сцены и планы — **текущее состояние разметки**, а сырые границы
 * остаются в своей таблице: разделение нужно, чтобы ручная правка не
 * уничтожала то, что предложила машина, и чтобы интерфейс мог показать оба
 * слоя рядом (FR-093, constitution IV.4).
 *
 * **Запись идёт в одну транзакцию**: сырые границы, рабочие сцены и планы
 * фиксируются одним изменением. Частично записанный результат означал бы,
 * что система «знает» о границах, которых нет.
 *
 * @property db доступ к базе сырым JDBC
 * @property runStore хранилище прогонов
 * @property boundaryStore хранилище сырых границ
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class StructureService(
    private val db: Db,
    private val runStore: AnalysisRunStore,
    private val boundaryStore: RawBoundaryStore,
) {
    /**
     * Записывает результат прогона как рабочего структуру эпизода.
     *
     * @param runId идентификатор прогона
     * @param videofileId эпизод-владелец
     * @param detection результат детекции
     * @return число записанных сцен и планов
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun applyDetection(
        runId: Long,
        videofileId: Long,
        detection: DetectionResult,
    ): Pair<Int, Int> {
        val builder = StructureBuilder(detection, frameCountOf(videofileId))
        val scenes =
            builder.sceneSections().map { range ->
                Scene(
                    videofileId = videofileId,
                    firstFrame = range.firstFrame,
                    lastFrame = range.lastFrame,
                    origin = BoundaryOrigin.AUTO,
                    runId = runId,
                )
            }
        val shots =
            builder.shotSections().map { range ->
                Shot(
                    videofileId = videofileId,
                    firstFrame = range.firstFrame,
                    lastFrame = range.lastFrame,
                    size = ShotSize.NONE,
                    sizeOrigin = SizeOrigin.AUTO,
                    origin = BoundaryOrigin.AUTO,
                    runId = runId,
                )
            }
        return db.useTransaction { connection ->
            // Прежняя рабочая структура не удаляется, а помечается устаревшей:
            // удаление уничтожило бы ручные правки оператора, которые он делал
            // по старым границам (FR-090, SC-006).
            connection
                .prepareStatement("UPDATE $SCENE_TABLE SET is_stale = TRUE WHERE id_videofile = ?")
                .use { statement ->
                    statement.setLong(1, videofileId)
                    statement.executeUpdate()
                }
            connection
                .prepareStatement("UPDATE $SHOT_TABLE SET is_stale = TRUE WHERE id_videofile = ?")
                .use { statement ->
                    statement.setLong(1, videofileId)
                    statement.executeUpdate()
                }
            boundaryStore.appendAll(
                connection,
                detection.sceneBoundaries.map { frame ->
                    RawBoundary(
                        runId = runId,
                        level = BoundaryLevel.SCENE,
                        firstFrame = frame,
                        lastFrame = frame,
                    )
                },
            )
            boundaryStore.appendAll(
                connection,
                detection.shotBoundaries.map { frame ->
                    RawBoundary(
                        runId = runId,
                        level = BoundaryLevel.SHOT,
                        firstFrame = frame,
                        lastFrame = frame,
                    )
                },
            )
            scenes.forEach { scene ->
                Save.insertIfAbsent(connection, scene.toTable())
            }
            shots.forEach { shot ->
                Save.insertIfAbsent(connection, shot.toTable())
            }
            scenes.size to shots.size
        }
    }

    /**
     * Читает рабочие сцены эпизода по возрастанию первого кадра.
     *
     * Возвращаются **все** строки, включая помеченные устаревшими: помеченная
     * строка — это прежняя редакция структуры, и сравнить её с сырым
     * результатом автоматики оператор может только здесь (FR-093). Рабочей
     * структурой считаются строки с `isStale = FALSE`.
     *
     * @param videofileId идентификатор эпизода
     * @return сцены эпизода
     */
    fun listScenes(videofileId: Long): List<Scene> = db.use { listScenesIn(it, videofileId) }

    /**
     * Читает сцены эпизода в уже открытой транзакции.
     *
     * Отдельный метод нужен операциям правки границ: прочитать и записать надо
     * в одной транзакции, иначе между чтением и записью успела бы встать чужая
     * правка, а решение посчиталось бы по уже не тому состоянию.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param videofileId идентификатор эпизода
     * @return сцены эпизода по возрастанию первого кадра
     */
    fun listScenesIn(
        connection: Connection,
        videofileId: Long,
    ): List<Scene> =
        connection
            .prepareStatement("$SCENE_READ_SQL WHERE $WORKING_CONDITION ORDER BY first_frame")
            .use { statement ->
                statement.setLong(1, videofileId)
                statement.executeQuery().use { resultSet ->
                    val rows = mutableListOf<Scene>()
                    while (resultSet.next()) {
                        rows.add(readScene(Row(resultSet)))
                    }
                    rows
                }
            }

    /**
     * Читает рабочие планы эпизода по возрастанию первого кадра.
     *
     * @param videofileId идентификатор эпизода
     * @return планы эпизода
     */
    fun listShots(videofileId: Long): List<Shot> = db.use { listShotsIn(it, videofileId) }

    /**
     * Читает планы эпизода в уже открытой транзакции.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param videofileId идентификатор эпизода
     * @return планы эпизода по возрастанию первого кадра
     */
    fun listShotsIn(
        connection: Connection,
        videofileId: Long,
    ): List<Shot> =
        connection
            .prepareStatement("$SHOT_READ_SQL WHERE $WORKING_CONDITION ORDER BY first_frame")
            .use { statement ->
                statement.setLong(1, videofileId)
                statement.executeQuery().use { resultSet ->
                    val rows = mutableListOf<Shot>()
                    while (resultSet.next()) {
                        rows.add(readShot(Row(resultSet)))
                    }
                    rows
                }
            }

    /**
     * Планы сцены, вычисленные по диапазонам кадров.
     *
     * Связь не хранится, а вычисляется: план принадлежит сцене тогда и
     * только тогда, когда он лежит в её диапазоне целиком (ADR-0007). Возврат
     * вычисленного значения вместо сохранённого — это и есть следствие
     * запрета хранить связь.
     *
     * @param scene сцена
     * @param shots планы эпизода
     * @return планы, лежащие в сцене целиком
     */
    fun shotsInside(
        scene: Scene,
        shots: List<Shot>,
    ): List<Shot> = shots.filter { it.firstFrame >= scene.firstFrame && it.lastFrame <= scene.lastFrame }

    /** Число кадров эпизода: без него границы не в чем разобрать. */
    private fun frameCountOf(videofileId: Long): Int =
        db.selectOne(
            "SELECT frame_count FROM tbl_videofiles WHERE id = ?",
            { it.int("frame_count") },
            videofileId,
        ) ?: throw ru.svoemesto.syp.core.db
            .DbException("Эпизод $videofileId не найдена: не из чего собрать структуру")

    /** Строит сцену из типизированной строки выборки. */
    private fun readScene(row: Row): Scene =
        Scene(
            id = row.long("id"),
            videofileId = row.long("id_videofile"),
            firstFrame = row.int("first_frame"),
            lastFrame = row.int("last_frame"),
            title = row.stringOrNull("title"),
            locationId = row.longOrNull("location_id"),
            origin = BoundaryOrigin.parse(row.string("origin")),
            runId = row.longOrNull("run_id"),
            isStale = row.booleanOrNull("is_stale") == true,
            recordHash = row.stringOrNull("recordhash"),
        )

    /** Строит план из типизированной строки выборки. */
    private fun readShot(row: Row): Shot =
        Shot(
            id = row.long("id"),
            videofileId = row.long("id_videofile"),
            firstFrame = row.int("first_frame"),
            lastFrame = row.int("last_frame"),
            size = ShotSize.parse(row.string("size")),
            sizeOrigin = SizeOrigin.parse(row.string("size_origin")),
            origin = BoundaryOrigin.parse(row.string("origin")),
            runId = row.longOrNull("run_id"),
            isStale = row.booleanOrNull("is_stale") == true,
            recordHash = row.stringOrNull("recordhash"),
        )

    companion object {
        /** Имя таблицы рабочих сцен. */
        const val SCENE_TABLE: String = "tbl_scenes"

        /** Имя таблицы рабочих планов. */
        const val SHOT_TABLE: String = "tbl_shots"

        /** Записываемые столбцы сцены в порядке значений. */
        val SCENE_COLUMNS: List<String> =
            listOf(
                "id_videofile",
                "first_frame",
                "last_frame",
                "title",
                "location_id",
                "origin",
                "run_id",
                "is_stale",
            )

        /** Записываемые столбцы плана в порядке значений. */
        val SHOT_COLUMNS: List<String> =
            listOf(
                "id_videofile",
                "first_frame",
                "last_frame",
                "size",
                "size_origin",
                "origin",
                "run_id",
                "is_stale",
            )

        /** Текст запроса выборки сцены. */
        val SCENE_READ_SQL: String = "SELECT ${Scene.READ_COLUMNS} FROM $SCENE_TABLE"

        /** Текст запроса выборки плана. */
        val SHOT_READ_SQL: String = "SELECT ${Shot.READ_COLUMNS} FROM $SHOT_TABLE"

        /**
         * Условие отбора рабочей структуры.
         *
         * Каждый прогон анализа помечает прежнюю структуру устаревшей, а не
         * удаляет её: ручные правки оператора по старым границам должны были
         * сохраниться. Но читать их наряду с действующими нельзя — на стенде
         * после пяти прогонов один и тот же кадровый диапазон возвращался
         * пять раз, и список планов в редакторе показывал в пять раз больше
         * строк, чем планов на самом деле.
         */
        const val WORKING_CONDITION: String = "id_videofile = ? AND is_stale = FALSE"
    }
}
