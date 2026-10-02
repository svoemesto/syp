package ru.svoemesto.syp.admin.annotation

import ru.svoemesto.syp.admin.analysis.BoundaryOrigin
import ru.svoemesto.syp.admin.analysis.Shot
import ru.svoemesto.syp.admin.analysis.ShotSize
import ru.svoemesto.syp.admin.analysis.SizeOrigin
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.catalog.MovieSetting
import ru.svoemesto.syp.admin.catalog.MovieSettingsStore
import ru.svoemesto.syp.admin.characters.FacePlanBinding
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Save
import java.sql.Connection

/**
 * Границы плана одной строкой — для текстов отказа.
 *
 * @return границы плана
 */
private fun Shot.range(): String = "$firstFrame…$lastFrame"

/**
 * Вид операции над границей плана.
 *
 * Значение уходит в ответе по той же причине, что и у сцены: интерфейс
 * показывает оператору, что именно изменилось, а не перечитывает дерево и
 * надеется, что слова «сохранено» достаточно.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class ShotBoundaryAction {
    /** Граница между двумя планами сдвинута. */
    MOVE,

    /** План разделен на два. */
    SPLIT,

    /** Два соседних плана объединены в один. */
    MERGE,
}

/**
 * Результат операции над границей плана.
 *
 * Ответ содержит **изменённый участок**, а не всё дерево эпизода: у эпизода
 * сотни планов, а менялись два из них. Интерфейс не перезапрашивает структуру
 * целиком после каждой правки (`boundary-editing.md` § 7).
 *
 * @property episodeId эпизод
 * @property frame кадр, по которому выполнена операция: первый кадр второго
 *   плана после сдвига или разделения, первый кадр поглощённого плана при
 *   объединении
 * @property action вид операции
 * @property affected рабочие планы затронутого участка после операции, с
 *   пересчитанным размером
 * @property superseded строки планов, выведенные из рабочей структуры
 * @property shotIds идентификаторы строк, записанных операцией
 * @property facesRebound сколько строк лица переведено на новые планы
 * @property sizesRecomputed сколько планов получило пересчитанный размер
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ShotEditOutcome(
    val episodeId: Long,
    val frame: Int,
    val action: ShotBoundaryAction,
    val affected: List<Shot>,
    val superseded: List<Shot>,
    val shotIds: List<Long>,
    val facesRebound: Int,
    val sizesRecomputed: Int,
)

/**
 * Шкала размера плана: доля площади кадра → ступень (ADR-0003).
 *
 * Отдельный объект по трём причинам:
 *
 * 1. **Пороги живут в настройке фильма, а не в коде.** Их подбирают замером под
 *    ручную разметку, и смена порога после замера не должна требовать правки
 *    кода и пересборки.
 * 2. **Правило одно, а мест вызова будет два**: правка границы плана и
 *    пересчёт после прогона. Расхождение двух копий правила дало бы плану с
 *    одним размером в одном экране и с другим — в соседнем.
 * 3. **Проверяемость**: правило помещается в несколько строк и проверяется
 *    тестом без базы, тогда как вызов требует её.
 *
 * Ступеней девять, порогов — на одну меньше: ступень выбирается первым порогом,
 * который доля не превосходит, а всё, что меньше последнего порога, — `XLS`.
 * Пустой набор порогов отвергается: молчаливый откат к одной ступени означал
 * бы, что оператор смотрит на заведомо неверный размер и не знает об этом.
 *
 * @property thresholds пороги доли площади рамки лица в площади кадра, строго
 *   убывающие
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ShotSizeScale(
    private val thresholds: List<Double>,
) {
    init {
        require(thresholds.isNotEmpty()) {
            "Шкала размера плана без порогов: размер определить нечем"
        }
        require(thresholds.all { it > 0.0 && it <= 1.0 }) {
            "Пороги размера плана должны быть долями кадра от 0 до 1, задано $thresholds"
        }
        thresholds.zipWithNext { bigger, smaller ->
            require(bigger > smaller) {
                "Пороги размера плана должны убывать, а задано $bigger после $smaller: " +
                    "иначе ступени накладывались бы друг на друга"
            }
        }
    }

    /**
     * Размер плана по доле площади крупнейшего лица.
     *
     * @param frameShare доля площади кадра, которую занимает самое крупное лицо
     * @return ступень шкалы
     */
    fun sizeOf(frameShare: Double): ShotSize {
        val index = thresholds.indexOfFirst { frameShare < it }.let { if (it < 0) 0 else it }
        return SIZES[index.coerceAtMost(SIZES.lastIndex)]
    }

    private companion object {
        /** Ступени шкалы от самой крупной к самой мелкой. */
        val SIZES: List<ShotSize> =
            listOf(
                ShotSize.ECU,
                ShotSize.BCU,
                ShotSize.CU,
                ShotSize.MCU,
                ShotSize.MS,
                ShotSize.MLS,
                ShotSize.LS,
                ShotSize.VLS,
                ShotSize.XLS,
            )
    }
}

/**
 * Доводка границы плана оператором.
 *
 * Три операции — сдвиг, разделение и объединение соседних планов — и ни одной
 * больше. В отличие от доводки сцены, правка плана меняет не одну строку:
 * диапазоны планов переезжают, а вместе с ними обязаны переехать размер плана
 * и принадлежность лиц. Все три пересчёта идут в одной транзакции с правкой.
 *
 * Семь правил, из-за которых класс написан так, а не иначе:
 *
 * 1. **Граница плана обязана попадать на границу соседнего плана либо на
 *    границу сцены.** Проверка идёт до записи, и отказ объясняет, между какими
 *    планами граница должна встать. Произвольный кадр внутри плана — это уже
 *    не сдвиг границы, а разрезание плана, и для него есть отдельная операция
 *    «разделить план». План лежит в сцене целиком (ADR-0007), поэтому граница,
 *    поставленная мимо границ сцен, оставляет структуру, где один план
 *    разрезает другой.
 * 2. **Пересчёт размера обязателен.** Размер плана вычисляется по самому
 *    крупному лицу (ADR-0003), и после смены диапазона прежнее значение
 *    описывает уже другой набор кадров. Перезапуск разбора для этого не
 *    требуется: ответ операции несёт пересчитанный размер.
 * 3. **Размер, заданный оператором вручную, пересчётом не затирается.** Ручная
 *    правка не теряется при повторном анализе — тем более её не должен терять
 *    пересчёт. Пересчитываются планы с автоматическим размером; у остальных
 *    размер остаётся тем, что выбрал человек.
 * 4. **Принадлежность лиц пересчитывается в той же транзакции.** Иначе между
 *    правкой границы и пересчётом осталось бы окно, в котором лицо числится в
 *    плане, ушедшем из работы, а интерфейс показывал бы фильтр по плану,
 *    которого нет (FR-034).
 * 5. **Прежняя строка не удаляется, а выводится из рабочей структуры** — так
 *    же, как при доводке сцены: предложение машины и решение человека
 *    остаются рядом (FR-093).
 * 6. **У затронутых планов `origin = OPERATOR`, ссылка на прогон обнуляется.**
 *    У решения человека нет породившего его прогона, и устаревать ему не от
 *    чего.
 * 7. **Всё в одной транзакции.** Проверки, записи, пересчёты и чтение
 *    результата фиксируются одним изменением.
 *
 * Шкала и площадь кадра — свойства **эпизода**, а не класса: пороги размера
 * лежат в настройках фильма, а площадь кадра — в самом эпизоде. Поэтому они
 * читаются в начале операции, а не задаются при создании: иначе класс пришлось
 * бы пересоздавать на каждый кадр, а поле «площадь кадра» стало бы его
 * состоянием, которое однажды забывают обновить.
 *
 * @property db доступ к базе сырым JDBC
 * @property structure чтение рабочих сцен и планов
 * @property binding пересчёт принадлежности лиц планам
 * @property episodes чтение эпизода: из него берутся фильм и площадь кадра
 * @property settings настройки фильма: из них берутся пороги размера плана
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ShotBoundaryEditing(
    private val db: Db,
    private val structure: StructureService,
    private val binding: FacePlanBinding,
    private val episodes: EpisodeStore,
    private val settings: MovieSettingsStore,
) {
    /**
     * Площадь кадра эпизода: переводит площадь рамки лица в долю кадра.
     *
     * @param episodeId идентификатор эпизода
     * @return площадь в квадратных пикселях
     * @throws DomainException если эпизода нет
     */
    private fun frameAreaOf(episodeId: Long): Int {
        val episode =
            episodes.find(episodeId)
                ?: throw DomainException(ErrorCode.NOT_FOUND, "эпизод $episodeId не зарегистрирован")
        return episode.width * episode.height
    }

    /**
     * Шкала размера плана фильма эпизода.
     *
     * @param episodeId идентификатор эпизода
     * @return шкала по порогам из настроек фильма
     * @throws DomainException если эпизода или настроек нет
     */
    private fun scaleOf(episodeId: Long): ShotSizeScale {
        val episode =
            episodes.find(episodeId)
                ?: throw DomainException(ErrorCode.NOT_FOUND, "эпизод $episodeId не зарегистрирован")
        return ShotSizeScale(settings.read(episode.movieId).numbers(MovieSetting.SHOT_SIZE_THRESHOLDS))
    }

    /**
     * Сдвигает границу между двумя соседними планами.
     *
     * Границы задаются номерами кадров, а не идентификаторами строк
     * (ADR-0001). Граница между двумя планами стоит на кадре, которым
     * начинается второй из них, поэтому `fromFrame` — кадр, который оператор
     * видит как «граница планов», а `toFrame` — куда он её ставит.
     *
     * @param episodeId идентификатор эпизода
     * @param fromFrame кадр, на котором граница стоит сейчас
     * @param toFrame кадр, на который её ставят
     * @return результат операции
     * @throws DomainException если такой границы нет, двигать некуда или новый
     *   кадр не является ни границей плана, ни границей сцены
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun moveShotBoundary(
        episodeId: Long,
        fromFrame: Int,
        toFrame: Int,
    ): ShotEditOutcome =
        db.useTransaction { connection ->
            val state = readState(connection, episodeId)
            val first = state.shotEndingAt(fromFrame - 1, fromFrame)
            val second = state.shotStartingAt(fromFrame, episodeId)
            if (toFrame == fromFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "граница плана уже стоит на кадре $toFrame: двигать некуда",
                )
            }
            if (toFrame <= first.firstFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "границу нельзя сдвинуть на кадр $toFrame: первый план кончился бы кадром " +
                        "${toFrame - 1}, а начинается с ${first.firstFrame} — его собственные кадры " +
                        "остались бы вне плана",
                )
            }
            if (toFrame > second.lastFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "границу нельзя сдвинуть на кадр $toFrame: второй план кончается кадром " +
                        "${second.lastFrame}, и первый накрыл бы начало следующего. Чтобы отдать " +
                        "кадры второму плану, их объединяют",
                )
            }
            state.requireBoundaryPlace(toFrame, first, second)
            val firstMoved = first.rebuilt(lastFrame = toFrame - 1)
            val secondMoved = second.rebuilt(firstFrame = toFrame)
            updateShot(connection, firstMoved)
            updateShot(connection, secondMoved)
            val settled = settle(connection, episodeId, listOf(firstMoved, secondMoved))
            ShotEditOutcome(
                episodeId = episodeId,
                frame = toFrame,
                action = ShotBoundaryAction.MOVE,
                affected = workingShots(connection, episodeId, firstMoved),
                superseded = emptyList(),
                shotIds = listOf(firstMoved.id!!, secondMoved.id!!),
                facesRebound = settled.facesRebound,
                sizesRecomputed = settled.sizesRecomputed,
            )
        }

    /**
     * Разделяет план на два по номеру кадра.
     *
     * Разделение границу не проверяет: новый кадр и так становится границей
     * плана, и именно эту операцию «сдвиг» отвергает. Кадр обязан быть
     * строго внутри исходного плана: кадр, равный его первому кадру,
     * границей не является, и разделить нечем.
     *
     * @param episodeId идентификатор эпизода
     * @param frame первый кадр второго из получившихся планов
     * @return результат операции
     * @throws DomainException если плана, содержащего кадр, нет или кадр стоит
     *   на его первом месте
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun splitShot(
        episodeId: Long,
        frame: Int,
    ): ShotEditOutcome =
        db.useTransaction { connection ->
            val state = readState(connection, episodeId)
            val source =
                state.working.firstOrNull { it.firstFrame <= frame && frame <= it.lastFrame }
                    ?: throw DomainException(
                        ErrorCode.NOT_FOUND,
                        "кадра $frame в рабочей структуре эпизода $episodeId нет: разделять нечего",
                    )
            if (frame == source.firstFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "кадр $frame — первый кадр плана, а не внутренняя граница: разделить план " +
                        "${source.range()} нечем",
                )
            }
            updateShot(connection, source.copy(isStale = true))
            val head = source.rebuilt(lastFrame = frame - 1).asNew()
            val tail = source.rebuilt(firstFrame = frame).asNew()
            insertShot(connection, head)
            insertShot(connection, tail)
            val settled = settle(connection, episodeId, listOf(head, tail))
            ShotEditOutcome(
                episodeId = episodeId,
                frame = frame,
                action = ShotBoundaryAction.SPLIT,
                affected = workingShots(connection, episodeId, source),
                superseded = listOf(source.copy(isStale = true)),
                shotIds = listOfNotNull(head.id, tail.id),
                facesRebound = settled.facesRebound,
                sizesRecomputed = settled.sizesRecomputed,
            )
        }

    /**
     * Объединяет план, начинающийся в указанном кадре, с предыдущим.
     *
     * @param episodeId идентификатор эпизода
     * @param frame первый кадр поглощаемого плана
     * @return результат операции
     * @throws DomainException если такого плана нет или перед ним нет другого
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun mergeShots(
        episodeId: Long,
        frame: Int,
    ): ShotEditOutcome =
        db.useTransaction { connection ->
            val state = readState(connection, episodeId)
            val absorbed = state.shotStartingAt(frame, episodeId)
            val keeper = state.shotEndingAt(frame - 1, frame)
            val merged = keeper.rebuilt(lastFrame = absorbed.lastFrame)
            updateShot(connection, merged)
            updateShot(connection, absorbed.copy(isStale = true))
            val settled = settle(connection, episodeId, listOf(merged))
            ShotEditOutcome(
                episodeId = episodeId,
                frame = frame,
                action = ShotBoundaryAction.MERGE,
                affected = workingShots(connection, episodeId, merged),
                superseded = listOf(absorbed.copy(isStale = true)),
                shotIds = listOf(merged.id!!),
                facesRebound = settled.facesRebound,
                sizesRecomputed = settled.sizesRecomputed,
            )
        }

    /**
     * Состояние рабочей структуры, прочитанное в текущей транзакции.
     *
     * @property episodeId эпизод
     * @property working планы, не выведенные из работы пометкой устаревания
     * @property shotStarts кадры, с которых начинается хоть какой-нибудь рабочий
     *   план: границей плана считается именно такая граница, а не любая строка
     *   базы — устаревшая строка принадлежит прежней редакции структуры
     * @property shotEnds кадры, которыми кончается хоть какой-нибудь рабочий
     *   план
     * @property sceneStarts первые кадры рабочих сцен
     * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
     */
    private class EditState(
        val episodeId: Long,
        val working: List<Shot>,
        val shotStarts: Set<Int>,
        val shotEnds: Set<Int>,
        val sceneStarts: Set<Int>,
    ) {
        /**
         * План, заканчивающийся указанным кадром.
         *
         * @param lastFrame последний кадр искомого плана
         * @param frame кадр, о котором идёт речь: попадает в текст отказа
         * @return план
         * @throws DomainException если такого плана нет
         */
        fun shotEndingAt(
            lastFrame: Int,
            frame: Int,
        ): Shot =
            working.firstOrNull { it.lastFrame == lastFrame }
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "плана, заканчивающегося кадром $lastFrame (граница на кадре $frame), " +
                        "в рабочей структуре эпизода $episodeId нет",
                )

        /**
         * План, начинающийся с указанного кадра.
         *
         * @param firstFrame первый кадр искомого плана
         * @param episodeId эпизод: попадает в текст отказа
         * @return план
         * @throws DomainException если такого плана нет
         */
        fun shotStartingAt(
            firstFrame: Int,
            episodeId: Long,
        ): Shot =
            working.firstOrNull { it.firstFrame == firstFrame }
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "плана, начинающегося с кадра $firstFrame, в рабочей структуре эпизода " +
                        "$episodeId нет",
                )

        /**
         * Проверяет, что границу можно поставить на указанный кадр.
         *
         * Кадр обязан быть либо границей плана, либо границей сцены. И то и
         * другое проверяется **до** записи, и отказ называет, между какими
         * планами граница должна встать: иначе оператор отправляется искать
         * причину сам, а она видна на экране — в виде границ планов и сцен.
         *
         * Проверяются только рабочие строки. Устаревшая строка принадлежит
         * прежней редакции структуры, и граница, вставленная по ней, была бы
         * согласована с тем, чего на экране нет.
         *
         * @param frame проверяемый кадр
         * @param first план, который коротчает
         * @param second план, который удлиняется
         * @throws DomainException если кадр не является ни границей плана, ни
         *   границей сцены
         */
        fun requireBoundaryPlace(
            frame: Int,
            first: Shot,
            second: Shot,
        ) {
            if (frame in shotStarts && frame - 1 in shotEnds || frame in sceneStarts) {
                return
            }
            throw DomainException(
                ErrorCode.BOUNDARY_CONFLICT,
                "границу плана нельзя поставить на кадр $frame: он не является ни границей " +
                    "плана, ни границей сцены. Между планами ${first.range()} и " +
                    "${second.range()} граница обязана встать на кадр, которым начинается другой " +
                    "план, либо на первый кадр сцены. Внутри плана границу не ставят — план " +
                    "разделяют отдельной операцией",
            )
        }
    }

    /**
     * Читает состояние рабочей структуры в открытой транзакции.
     *
     * @param connection открытое соединение
     * @param episodeId идентификатор эпизода
     * @return состояние
     */
    private fun readState(
        connection: Connection,
        episodeId: Long,
    ): EditState {
        val scenes = structure.listScenesIn(connection, episodeId).filter { !it.isStale }
        val working = structure.listShotsIn(connection, episodeId).filter { !it.isStale }
        return EditState(
            episodeId = episodeId,
            working = working,
            shotStarts = working.mapNotNull { it.firstFrame }.toSet(),
            shotEnds = working.mapNotNull { it.lastFrame }.toSet(),
            sceneStarts = scenes.mapNotNull { it.firstFrame }.toSet(),
        )
    }

    /**
     * Пересчитывает принадлежность лиц и размеры затронутых планов.
     *
     * Вызывается изнутри уже открытой транзакции операции: между изменением
     * границы и пересчётом не должно быть окна, в котором размер плана
     * описывает прежний набор кадров, а лицо числится в плане, которого уже
     * нет.
     *
     * Размер считается по рамкам лиц **нового** диапазона плана, а не по
     * столбцу принадлежности: между пересчётом принадлежности и пересчётом
     * размера порядок не должен был бы иметь значения.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод
     * @param shots планы, размер которых пересчитывается
     * @return сколько строк лица и планов перезаписано
     */
    private fun settle(
        connection: Connection,
        episodeId: Long,
        shots: List<Shot>,
    ): Settled {
        val facesRebound = binding.rebindInConnection(connection, episodeId)
        val scale = scaleOf(episodeId)
        val frameArea = frameAreaOf(episodeId)
        val sizesRecomputed =
            shots.count { shot ->
                if (shot.sizeOrigin == SizeOrigin.OPERATOR) {
                    // Размер выбрал человек: пересчёт его не затирает. Молчаливая
                    // перезапись уничтожила бы ручную правку, а ручная правка
                    // не теряется ни при повторном анализе, ни здесь.
                    return@count false
                }
                val computed =
                    largestFaceShare(connection, episodeId, shot, frameArea).let { share ->
                        if (share == null) ShotSize.NONE else scale.sizeOf(share)
                    }
                if (shot.size == computed) {
                    return@count false
                }
                updateShot(connection, shot.copy(size = computed, sizeOrigin = SizeOrigin.AUTO))
                true
            }
        return Settled(facesRebound, sizesRecomputed)
    }

    /**
     * Доля площади кадра у самого крупного лица плана.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод
     * @param shot план
     * @param frameArea площадь кадра эпизода в квадратных пикселях
     * @return доля площади кадра либо `null`, если в плане нет лиц: у плана без
     *   лиц размер `NONE` (ADR-0003)
     */
    private fun largestFaceShare(
        connection: Connection,
        episodeId: Long,
        shot: Shot,
        frameArea: Int,
    ): Double? =
        connection
            .prepareStatement(
                "SELECT max((x2 - x1)::double * (y2 - y1)::double) AS biggest FROM $FACE_TABLE " +
                    "WHERE id_episode = ? AND frame_number >= ? AND frame_number <= ?",
            ).use { statement ->
                statement.setLong(1, episodeId)
                statement.setInt(2, shot.firstFrame)
                statement.setInt(3, shot.lastFrame)
                statement.executeQuery().use { resultSet ->
                    if (!resultSet.next()) {
                        return null
                    }
                    val biggest = resultSet.getDouble("biggest")
                    if (resultSet.wasNull()) null else biggest / frameArea
                }
            }

    /**
     * Обновляет существующий план по различию значений.
     *
     * У плана, полученного правкой, `recordHash` прежний, и сохранение
     * пересчитывает его: правило «записать, только если значения изменились»
     * работает и для ручной правки, а не только для прогона.
     *
     * @param connection открытое соединение
     * @param shot план к записи
     * @throws IllegalStateException если у плана нет идентификатора
     */
    private fun updateShot(
        connection: Connection,
        shot: Shot,
    ) {
        val id = shot.id
        if (id == null) {
            throw IllegalStateException("План без идентификатора не обновляется: ${shot.range()}")
        }
        Save.saveIfChanged(connection, shot.toTable(), listOf("id"), listOf(id))
    }

    /**
     * Вставляет новый план вместе с его хешем.
     *
     * @param connection открытое соединение
     * @param shot вставляемый план
     */
    private fun insertShot(
        connection: Connection,
        shot: Shot,
    ) {
        Save.insertIfAbsent(connection, shot.toTable())
    }

    /**
     * Рабочие планы участка, заданного границами плана.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод
     * @param range план, границами которого задан участок
     * @return рабочие планы, пересекающие участок
     */
    private fun workingShots(
        connection: Connection,
        episodeId: Long,
        range: Shot,
    ): List<Shot> =
        structure
            .listShotsIn(connection, episodeId)
            .filter { !it.isStale && it.lastFrame >= range.firstFrame && it.firstFrame <= range.lastFrame }
            .sortedBy { it.firstFrame }

    /**
     * План, переписанный как решение оператора.
     *
     * Ссылка на прогон обнуляется: границу поставил человек, и породившего её
     * прогона нет. Устареть такому плану не от чего, а оставить ссылку —
     * значит при смене порога пометить устаревшим границу, которую поставил
     * человек.
     *
     * Размер и происхождение размера сохраняются: пересчёт решения оператора
     * не отменяет, а новый размер вычислится отдельным шагом операции.
     *
     * @param firstFrame первый кадр
     * @param lastFrame последний кадр
     * @return план с происхождением `OPERATOR`
     */
    private fun Shot.rebuilt(
        firstFrame: Int = this.firstFrame,
        lastFrame: Int = this.lastFrame,
    ): Shot =
        copy(
            firstFrame = firstFrame,
            lastFrame = lastFrame,
            origin = BoundaryOrigin.OPERATOR,
            runId = null,
            isStale = false,
        )

    /**
     * План как новая строка: без идентификатора и без прежнего хеша.
     *
     * @return план, готовый к вставке
     */
    private fun Shot.asNew(): Shot = copy(id = null, recordHash = null)

    /**
     * Итог пересчётов операции.
     *
     * @property facesRebound сколько строк лица переведено на новые планы
     * @property sizesRecomputed сколько планов получило пересчитанный размер
     */
    private data class Settled(
        val facesRebound: Int,
        val sizesRecomputed: Int,
    )

    private companion object {
        /** Имя таблицы лица. */
        const val FACE_TABLE: String = "tbl_faces"
    }
}
