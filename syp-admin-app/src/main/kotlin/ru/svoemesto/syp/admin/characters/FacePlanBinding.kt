package ru.svoemesto.syp.admin.characters

import ru.svoemesto.syp.core.db.Db
import java.sql.Connection

/**
 * Пересчёт принадлежности лиц планам.
 *
 * Связь лица с планом **не хранится как список**: она вычисляется по
 * диапазонам кадров, и то же самое верно для сцены и плана (ADR-0007). Но у
 * лица столбец `shot_id` в базе есть, и вот почему:
 *
 * - по нему строится основной путь фильтра «все планы этой персоны», а
 *   фильтр выполняется на больших выборках, где пересчёт диапазонов на каждый
 *   кадр дорог;
 * - без него «кто в плане» считалось бы каждый раз заново, и две разные
 *   точки чтения дали бы разный ответ.
 *
 * Из этого следует единственное правило класса: **столбец `shot_id` — это
 * копия вычисленного значения, а не самостоятельный факт**. Как только
 * границы плана меняются, копия перестаёт соответствовать оригиналу, и
 * пересчёт обязателен. Проверять это надо не «по памяти» вызывающего, а
 * одним местом в коде — здесь.
 *
 * Пересчёт выполняется **в той же транзакции**, в которой менялись границы
 * плана. Отдельной транзакцией он оставил бы окно, в котором лицо числится
 * в плане, которого уже нет, — а интерфейс оператора за это окно показал бы
 * фильтр с несуществующим планом.
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class FacePlanBinding(
    private val db: Db,
) {
    /**
     * Пересчитывает принадлежность к планам для всех лиц эпизода.
     *
     * Метод правильный в обе стороны: и привязывает лица к новым планам, и
     * **отвязывает** от исчезнувших. Отвязка обязательна — иначе `shot_id`
     * продолжал бы указывать на план, удалённый вместе с границей, и лицо
     * числилось бы в плане, которого нет.
     *
     * Лицо, номер кадра которого вне диапазонов всех планов эпизода, остаётся
     * без плана — и **только** в этом случае пустое значение допустимо
     * (FR-034). Так бывает, когда структура эпизода ещё не построена: лица
     * нашли, а планов ещё нет.
     *
     * @param episodeId эпизод
     * @return сколько строк лица изменилось
     * @throws ru.svoemesto.syp.core.db.DbException если пересчёт не удался
     */
    fun rebindEpisode(episodeId: Long): Int = db.useTransaction { connection -> rebindInConnection(connection, episodeId) }

    /**
     * Пересчитывает принадлежность к планам в уже открытой транзакции.
     *
     * Именно этот метод зовётся из операций с границами плана: пересчёт
     * обязан попасть в ту же транзакцию, иначе между изменением границы и
     * пересчётом появится окно с неверной привязкой (FR-034).
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param episodeId эпизод
     * @return сколько строк лица изменилось
     * @throws ru.svoemesto.syp.core.db.DbException если пересчёт не удался
     */
    fun rebindInConnection(
        connection: Connection,
        episodeId: Long,
    ): Int {
        val shots = ShotsByFrame.read(connection, episodeId)
        return rebind(connection, episodeId, shots)
    }

    /**
     * План, которому принадлежит кадр.
     *
     * Правило диапазонов — то же, что и у сцены: кадр принадлежит плану,
     * когда номер кадра лежит между `first_frame` и `last_frame` включительно.
     * Планы эпизода не пересекаются, поэтому план для кадра не может быть
     * неоднозначен; найденные пересечения — расхождение данных, и они
     * отвергаются, а не разрешаются выбором первого.
     *
     * @param shots планы эпизода по возрастанию первого кадра
     * @param frameNumber номер кадра
     * @return идентификатор плана либо `null`, если кадр вне всех диапазонов
     * @throws ru.svoemesto.syp.core.db.DbException если кадр попал в два плана
     */
    fun shotOf(
        shots: List<ShotRange>,
        frameNumber: Int,
    ): Long? {
        val hits = shots.filter { frameNumber >= it.firstFrame && frameNumber <= it.lastFrame }
        require(hits.size <= 1) {
            "Кадр $frameNumber попал в ${hits.size} планов (${hits.map { it.id }}): " +
                "планы эпизода пересекаются, принадлежность лица неоднозначна"
        }
        return hits.firstOrNull()?.id
    }

    /**
     * Пересчитывает принадлежность по уже прочитанным планам.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод
     * @param shots планы эпизода
     * @return сколько строк лица изменилось
     */
    private fun rebind(
        connection: Connection,
        episodeId: Long,
        shots: List<ShotRange>,
    ): Int {
        // Первый проход — отвязка: все лица эпизода теряют прежний план.
        // Делается всегда, даже если планы не изменились: иначе пересчёт
        // был бы «добавить новое к старому», и лицо, ушедшее из диапазона
        // плана, осталось бы в нём навсегда.
        var changed =
            connection
                .prepareStatement(
                    "UPDATE ${FaceStore.TABLE} SET shot_id = NULL " +
                        "WHERE id_episode = ? AND shot_id IS NOT NULL",
                ).use { statement ->
                    statement.setLong(1, episodeId)
                    statement.executeUpdate()
                }

        // Второй проход — привязка по диапазонам. Планы эпизода не
        // пересекаются (ADR-0007), поэтому каждое лицо получает ровно один
        // план, а второе обновление того же лица невозможно.
        connection
            .prepareStatement(
                "UPDATE ${FaceStore.TABLE} SET shot_id = ? " +
                    "WHERE id_episode = ? AND frame_number >= ? AND frame_number <= ?",
            ).use { statement ->
                shots.forEach { shot ->
                    statement.setLong(1, shot.id)
                    statement.setLong(2, episodeId)
                    statement.setInt(3, shot.firstFrame)
                    statement.setInt(4, shot.lastFrame)
                    changed += statement.executeUpdate()
                }
            }
        return changed
    }
}

/**
 * Диапазон плана в виде, достаточном для вычисления принадлежности лица.
 *
 * Отдельный тип, а не [ru.svoemesto.syp.admin.analysis.Shot], потому что
 * здесь нужен только идентификатор и границы: тянуть в пересчёт целую
 * сущность плана со столбцами размера и происхождения незачем.
 *
 * @property id идентификатор плана
 * @property firstFrame первый кадр плана
 * @property lastFrame последний кадр плана
 */
data class ShotRange(
    val id: Long,
    val firstFrame: Int,
    val lastFrame: Int,
)

/**
 * Чтение планов эпизода для пересчёта принадлежности лиц.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object ShotsByFrame {
    /**
     * Читает актуальные планы эпизода.
     *
     * Устаревшие планы не читаются: они помечены результатом прошлого
     * прохода и в фильтрах не участвуют. Привязка лица к устаревшему плану
     * была бы ссылкой на результат, который оператор уже не видит.
     *
     * @param connection открытое соединение
     * @param episodeId эпизод
     * @return планы по возрастанию первого кадра
     */
    fun read(
        connection: Connection,
        episodeId: Long,
    ): List<ShotRange> =
        connection
            .prepareStatement(
                "SELECT id, first_frame, last_frame FROM ${StructureTables.SHOT} " +
                    "WHERE id_episode = ? AND is_stale = FALSE ORDER BY first_frame",
            ).use { statement ->
                statement.setLong(1, episodeId)
                statement.executeQuery().use { resultSet ->
                    val rows = mutableListOf<ShotRange>()
                    while (resultSet.next()) {
                        rows.add(
                            ShotRange(
                                id = resultSet.getLong("id"),
                                firstFrame = resultSet.getInt("first_frame"),
                                lastFrame = resultSet.getInt("last_frame"),
                            ),
                        )
                    }
                    rows
                }
            }
}

/**
 * Имена таблиц структуры эпизода.
 *
 * Вынесены отдельно, чтобы домен персонажей не импортировал весь пакет
 * анализа: пересчёту принадлежности нужны только имена таблиц.
 */
internal object StructureTables {
    /** Имя таблицы рабочих планов. */
    const val SHOT: String = "tbl_shots"
}
