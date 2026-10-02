package ru.svoemesto.syp.admin.annotation

import ru.svoemesto.syp.admin.analysis.BoundaryOrigin
import ru.svoemesto.syp.admin.analysis.Scene
import ru.svoemesto.syp.admin.analysis.Shot
import ru.svoemesto.syp.admin.analysis.StructureService
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Save
import java.sql.Connection

/**
 * Границы участка одной строкой — для текстов отказа.
 *
 * Отдельная функция, а не метод класса: ею пользуются и операции, и вложенное
 * состояние чтения, а метод внешнего класса из вложенного не виден.
 *
 * @return границы участка
 */
private fun Scene.range(): String = "$firstFrame…$lastFrame"

/**
 * Границы плана одной строкой — для текстов отказа.
 *
 * @return границы плана
 */
private fun Shot.range(): String = "$firstFrame…$lastFrame"

/**
 * Вид операции над границей сцены.
 *
 * Значение уходит в ответе: интерфейс показывает оператору, что именно
 * изменилось, а не просто перечитывает дерево и надеется, что слова
 * «сохранено» достаточно.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class SceneBoundaryAction {
    /** Граница между двумя сценами сдвинута. */
    MOVE,

    /** Сцена разделена на две. */
    SPLIT,

    /** Две соседние сцены объединены в одну. */
    MERGE,
}

/**
 * Результат операции над границей сцены.
 *
 * Ответ содержит **изменённый участок**, а не всё дерево эпизода: у эпизода
 * сотни сцен, а менялись две (или одна из двух). Интерфейс не перезапрашивает
 * структуру целиком после каждой правки (`boundary-editing.md` § 7).
 *
 * @property episodeId эпизод
 * @property frame кадр, по которому выполнена операция: первый кадр второй
 *   сцены после сдвига или разделения, первый кадр поглощённой сцены при
 *   объединении
 * @property action вид операции
 * @property affected рабочие сцены затронутого участка после операции
 * @property superseded строки, выведенные из рабочей структуры
 * @property sceneIds идентификаторы строк, записанных операцией
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SceneEditOutcome(
    val episodeId: Long,
    val frame: Int,
    val action: SceneBoundaryAction,
    val affected: List<Scene>,
    val superseded: List<Scene>,
    val sceneIds: List<Long>,
)

/**
 * Доводка границы сцены оператором.
 *
 * Три операции — сдвиг, разделение и объединение соседних сцен — и ни одной
 * больше. Работы с границами планов здесь нет сознательно: правка плана меняет
 * принадлежность лиц планам, а правка сцены не меняет ничего, кроме самой
 * сцены, и обходится одной транзакцией без сопутствующего пересчёта.
 *
 * Шесть правил, из-за которых класс написан так, а не иначе:
 *
 * 1. **Граница сцены обязана попадать на границу плана.** План лежит в сцене
 *    целиком (ADR-0007), поэтому граница сцены, разрезавшая план пополам,
 *    оставила бы в базе состояние, которого быть не может. Проверка идёт
 *    **до** записи, и отказ объясняет, между какими планами граница должна
 *    встать.
 * 2. **Прежняя строка не удаляется, а выводится из рабочей структуры.** Так же,
 *    как это делает запись результата прогона: решение человека и предложение
 *    машины остаются рядом, иначе сравнивать было бы не с чем (FR-093).
 *    Выведенная строка помечается устаревшей, а не стирается.
 * 3. **Новая граница принадлежит оператору.** В сцены, которых коснулась
 *    правка, пишется `origin = OPERATOR` и пустая ссылка на прогон: у решения
 *    человека нет породившего его прогона, и устаревать ему не от чего
 *    (`analysis-run.md` § 11).
 * 4. **Название и место действия остаются у части, содержащей первый кадр
 *    исходной сцены.** Они назначаются вручную, и переносить их на новую
 *    сцену молча значило бы приписать оператору решение, которого он не
 *    принимал. Вторая часть остаётся без названия и без места действия.
 * 5. **Планы не трогаются.** Принадлежность лиц планам — копия вычисленного
 *    значения, и она перестаёт соответствовать оригиналу только при смене
 *    границ **плана**. Сдвиг границы сцены диапазоны планов не меняет, поэтому
 *    пересчёт `shot_id` здесь не выполняется: выполнять его не по чему, а
 *    лишний пересчёт на 88 643 строках стоил бы оператору секунд ожидания
 *    без единой изменившейся величины.
 * 6. **Всё в одной транзакции.** Проверки, записи и чтение результата
 *    фиксируются одним изменением.
 *
 * @property db доступ к базе сырым JDBC
 * @property structure чтение рабочих сцен и планов
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class BoundaryEditing(
    private val db: Db,
    private val structure: StructureService,
) {
    /**
     * Сдвигает границу между двумя соседними сценами.
     *
     * Границы задаются номерами кадров, а не идентификаторами строк
     * (ADR-0001). Граница между двумя сценами стоит на кадре, которым
     * начинается вторая из них, поэтому `fromFrame` — это кадр, который
     * оператор видит в таблице как «граница сцен», а `toFrame` — куда он её
     * ставит.
     *
     * Граница может встать в любое место между первым кадром первой сцены и
     * последним кадром второй: сцена, отдающая кадры, вправе уйти вперёд, но
     * не может накрыть начало третьей — для этого сцены объединяют.
     *
     * @param episodeId идентификатор эпизода
     * @param fromFrame кадр, на котором граница стоит сейчас
     * @param toFrame кадр, на который её ставят
     * @return результат операции
     * @throws DomainException если такой границы нет, двигать некуда или новый
     *   кадр не является границей плана
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun moveSceneBoundary(
        episodeId: Long,
        fromFrame: Int,
        toFrame: Int,
    ): SceneEditOutcome =
        db.useTransaction { connection ->
            val state = readState(connection, episodeId)
            val first = state.sceneEndingAt(fromFrame - 1, fromFrame)
            val second = state.sceneStartingAt(fromFrame, episodeId)
            if (toFrame == fromFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "граница сцен уже стоит на кадре $toFrame: двигать некуда",
                )
            }
            if (toFrame <= first.firstFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "границу нельзя сдвинуть на кадр $toFrame: первая сцена кончилась бы " +
                        "кадром ${toFrame - 1}, а начинается с ${first.firstFrame} — " +
                        "её собственные кадры остались бы вне сцен",
                )
            }
            if (toFrame > second.lastFrame) {
                throw DomainException(
                    ErrorCode.BOUNDARY_CONFLICT,
                    "границу нельзя сдвинуть на кадр $toFrame: вторая сцена кончается кадром " +
                        "${second.lastFrame}, и первая кончилась бы кадром ${toFrame - 1} — " +
                        "накрыла бы начало следующей. Чтобы отдать кадры второй сцене, их " +
                        "объединяют",
                )
            }
            state.requirePlanBoundary(toFrame)
            val rebuilt = listOf(first.rebuilt(lastFrame = toFrame - 1), second.rebuilt(firstFrame = toFrame))
            val moved = rebuilt.map { updateScene(connection, it) }
            val affected = readState(connection, episodeId).working.filter { it.id in moved }
            SceneEditOutcome(
                episodeId = episodeId,
                frame = toFrame,
                action = SceneBoundaryAction.MOVE,
                affected = affected,
                superseded = emptyList(),
                sceneIds = moved,
            )
        }

    /**
     * Разделяет сцену на две по номеру кадра.
     *
     * @param episodeId идентификатор эпизода
     * @param frame первый кадр второй из получившихся сцен
     * @return результат операции
     * @throws DomainException если сцены с таким кадром нет, кадр стоит на её
     *   первом месте или он не является границей плана
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun splitScene(
        episodeId: Long,
        frame: Int,
    ): SceneEditOutcome =
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
                    "кадр $frame — первый кадр сцены, а не внутренняя граница: разделить " +
                        "сцену ${source.range()} нечем",
                )
            }
            state.requirePlanBoundary(frame)
            updateScene(connection, source.copy(isStale = true))
            insertScene(connection, source.rebuilt(lastFrame = frame - 1).asNew())
            insertScene(connection, source.rebuilt(firstFrame = frame, title = null, locationId = null).asNew())
            val affected = readState(connection, episodeId).working.inRange(source)
            SceneEditOutcome(
                episodeId = episodeId,
                frame = frame,
                action = SceneBoundaryAction.SPLIT,
                affected = affected,
                superseded = listOf(source.copy(isStale = true)),
                sceneIds = affected.mapNotNull { it.id },
            )
        }

    /**
     * Объединяет сцену, начинающуюся в указанном кадре, с предыдущей.
     *
     * @param episodeId идентификатор эпизода
     * @param frame первый кадр поглощаемой сцены
     * @return результат операции
     * @throws DomainException если такой сцены нет или перед ней нет другой
     * @throws ru.svoemesto.syp.core.db.DbException если запись не удалась
     */
    fun mergeScenes(
        episodeId: Long,
        frame: Int,
    ): SceneEditOutcome =
        db.useTransaction { connection ->
            val state = readState(connection, episodeId)
            val absorbed = state.sceneStartingAt(frame, episodeId)
            val keeper = state.sceneEndingAt(frame - 1, frame)
            val merged = keeper.rebuilt(lastFrame = absorbed.lastFrame)
            val mergedId = updateScene(connection, merged)
            updateScene(connection, absorbed.copy(isStale = true))
            val affected = readState(connection, episodeId).working.filter { it.id == mergedId }
            SceneEditOutcome(
                episodeId = episodeId,
                frame = frame,
                action = SceneBoundaryAction.MERGE,
                affected = affected,
                superseded = listOf(absorbed.copy(isStale = true)),
                sceneIds = listOf(mergedId),
            )
        }

    /**
     * Состояние рабочей структуры, прочитанное в текущей транзакции.
     *
     * @property episodeId эпизод
     * @property working сцены, не выведенные из работы пометкой устаревания
     * @property shots планы эпизода: по ним проверяется, что граница сцены
     *   не разрезает план
     * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
     */
    private class EditState(
        val episodeId: Long,
        val working: List<Scene>,
        val shots: List<Shot>,
    ) {
        /**
         * Сцена, заканчивающаяся указанным кадром.
         *
         * @param lastFrame последний кадр искомой сцены
         * @param frame кадр, о котором идёт речь: попадает в текст отказа
         * @return сцена
         * @throws DomainException если такой сцены нет
         */
        fun sceneEndingAt(
            lastFrame: Int,
            frame: Int,
        ): Scene =
            working.firstOrNull { it.lastFrame == lastFrame }
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "сцены, заканчивающейся кадром $lastFrame (граница на кадре $frame), " +
                        "в рабочей структуре эпизода $episodeId нет",
                )

        /**
         * Сцена, начинающаяся с указанного кадра.
         *
         * @param firstFrame первый кадр искомой сцены
         * @param episodeId эпизод: попадает в текст отказа
         * @return сцена
         * @throws DomainException если такой сцены нет
         */
        fun sceneStartingAt(
            firstFrame: Int,
            episodeId: Long,
        ): Scene =
            working.firstOrNull { it.firstFrame == firstFrame }
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "сцены, начинающейся с кадра $firstFrame, в рабочей структуре эпизода " +
                        "$episodeId нет",
                )

        /**
         * Проверяет, что кадр является границей плана.
         *
         * У эпизода без планов проверять нечего: сцена тогда единственная
         * граница, и разделить её не на что. Во всех остальных случаях кадр
         * обязан совпадать с началом плана и с концом предыдущего, иначе новая
         * сцена разрежет план, а план обязан лежать в сцене целиком
         * (ADR-0007).
         *
         * @param frame проверяемый кадр
         * @throws DomainException если планы есть, а кадр на их границе не стоит
         */
        fun requirePlanBoundary(frame: Int) {
            if (shots.isEmpty()) {
                return
            }
            val isBoundary = shots.any { it.firstFrame == frame } && shots.any { it.lastFrame == frame - 1 }
            if (isBoundary) {
                return
            }
            val inside = shots.firstOrNull { it.firstFrame <= frame && frame <= it.lastFrame }
            throw DomainException(
                ErrorCode.BOUNDARY_CONFLICT,
                if (inside == null) {
                    "кадра $frame нет ни в одном плане эпизода $episodeId: границу сцены " +
                        "негде поставить"
                } else {
                    "кадр $frame лежит внутри плана ${inside.range()}, а не на его границе: " +
                        "граница сцены не может разрезать план, план лежит в сцене целиком"
                },
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
    ): EditState =
        EditState(
            episodeId = episodeId,
            working = structure.listScenesIn(connection, episodeId).filter { !it.isStale },
            shots = structure.listShotsIn(connection, episodeId),
        )

    /**
     * Обновляет существующую сцену по различию значений.
     *
     * У сцены, полученной правкой, `recordHash` прежний, и сохранение
     * пересчитывает его: правило «записать, только если значения изменились»
     * работает и для ручной правки, а не только для прогона.
     *
     * @param connection открытое соединение
     * @param scene сцена к записи
     * @return идентификатор записанной строки
     * @throws IllegalStateException если у сцены нет идентификатора
     */
    private fun updateScene(
        connection: Connection,
        scene: Scene,
    ): Long {
        val id = scene.id
        if (id == null) {
            throw IllegalStateException("Сцена без идентификатора не обновляется: ${scene.range()}")
        }
        Save.saveIfChanged(connection, scene.toTable(), listOf("id"), listOf(id))
        return id
    }

    /**
     * Вставляет новую сцену вместе с её хешем.
     *
     * @param connection открытое соединение
     * @param scene вставляемая сцена
     */
    private fun insertScene(
        connection: Connection,
        scene: Scene,
    ) {
        Save.insertIfAbsent(connection, scene.toTable())
    }

    /**
     * Сцена, переписанная как решение оператора.
     *
     * Ссылка на прогон обнуляется: границу поставил человек, и породившего её
     * прогона нет. Устареть такой сцене не от чего, а оставить ссылку — значит
     * при смене порога пометить устаревшей границу, которую поставил человек.
     *
     * @param firstFrame первый кадр
     * @param lastFrame последний кадр
     * @param title название; по умолчанию прежнее
     * @param locationId место действия; по умолчанию прежнее
     * @return сцена с происхождением `OPERATOR`
     */
    private fun Scene.rebuilt(
        firstFrame: Int = this.firstFrame,
        lastFrame: Int = this.lastFrame,
        title: String? = this.title,
        locationId: Long? = this.locationId,
    ): Scene =
        copy(
            firstFrame = firstFrame,
            lastFrame = lastFrame,
            title = title,
            locationId = locationId,
            origin = BoundaryOrigin.OPERATOR,
            runId = null,
            isStale = false,
        )

    /**
     * Сцена как новая строка: без идентификатора и без прежнего хеша.
     *
     * @return сцена, готовая к вставке
     */
    private fun Scene.asNew(): Scene = copy(id = null, recordHash = null)

    /**
     * Сцены участка, заданного границами сцены.
     *
     * @param range сцена, границами которой задан участок
     * @return рабочие сцены, пересекающие участок
     */
    private fun List<Scene>.inRange(range: Scene): List<Scene> =
        filter { it.lastFrame >= range.firstFrame && it.firstFrame <= range.lastFrame }
}
