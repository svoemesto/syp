package ru.svoemesto.syp.admin.analysis

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db

/**
 * Что именно оказалось помечено устаревшим.
 *
 * Счётчики нужны не для красоты: без них оператор увидел бы «результат
 * устарел» и не смог бы понять масштаб — помечен один прогон или вся
 * структура серии (FR-090).
 *
 * @property runs число прогонов, помеченных устаревшими
 * @property scenes число рабочих сцен, помеченных устаревшими
 * @property shots число рабочих планов, помеченных устаревшими
 * @property episodeIds серии, у которых помечено устаревшим
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class StaleSummary(
    val runs: Int,
    val scenes: Int,
    val shots: Int,
    val episodeIds: List<Long>,
) {
    /** Помечено ли хоть что-нибудь. */
    val isEmpty: Boolean
        get() = runs == 0 && scenes == 0 && shots == 0
}

/**
 * Состояние актуальности результата серии.
 *
 * @property episodeId серия, о которой идёт речь
 * @property runId последний прогон заданного вида либо `null`
 * @property algorithmVersion версия алгоритма последнего прогона
 * @property paramsHash хеш входов последнего прогона
 * @property currentParamsHash хеш входов, которые действуют сейчас
 * @property isStale устарел ли результат
 * @property reason чем именно результат устарел; `null`, если он актуален
 * @property isAbsent результата ещё нет: ни одного прогона не было
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class StaleStatus(
    val episodeId: Long,
    val runId: Long?,
    val algorithmVersion: String?,
    val paramsHash: String?,
    val currentParamsHash: String,
    val isStale: Boolean,
    val reason: String?,
    val isAbsent: Boolean,
) {
    /**
     * Машинный код пометки устаревания.
     *
     * Код отдаётся в теле ответа чтения, а не поднимается до ошибки HTTP:
     * устаревший результат **показывается** — в нём есть ручные правки
     * оператора, и стирать их нельзя (FR-090, SC-006). Отказом код
     * становится там, где действие обязано опираться на актуальный результат,
     * — это делает [Staleness.requireCurrent].
     *
     * @return код `STALE_RESULT` либо `null`, если результат актуален
     */
    val staleResultCode: String?
        get() = if (isStale) ErrorCode.STALE_RESULT.name else null
}

/**
 * Помечает результат устаревшим при смене версии алгоритма или порогов.
 *
 * Три правила, из-за которых класс существует отдельно:
 *
 * 1. **Помечается, а не удаляется.** Автоматический пересчёт уничтожил бы
 *    ручные правки оператора: они накапливаются месяцами, а пересчёт
 *    запускается после смены порога (FR-090, SC-006).
 * 2. **Помечается по входам, а не по дате.** Сравниваются хеши параметров
 *    прогона и текущие. Прогон, сделанный при других порогах, устарел даже
 *    если сделан вчера; прогон, сделанный при тех же порогах, актуален даже
 *    если старый.
 * 3. **Ручная правка устареванием не считается.** Сцена и план, созданные
 *    оператором, не ссылаются на прогон: ставить им признак устаревания
 *    нечем и незачем — их решение человека, а не результат автоматики.
 *
 * @property db доступ к базе сырым JDBC
 * @property runStore хранилище прогонов: через него читается последний прогон
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class Staleness(
    private val db: Db,
    private val runStore: AnalysisRunStore,
) {
    /**
     * Помечает устаревшим прогоны серии, сделанные при других входах.
     *
     * Заодно помечаются рабочие сцены и планы, полученные этими прогонами:
     * иначе интерфейс показал бы структуру со снятым признаком устаревания,
     * а прогон, из которого она получена, остался бы помеченным — два
     * разных ответа на один вопрос.
     *
     * @param episodeId серия
     * @param kind вид прогона
     * @param currentParamsHash хеш входов, действующих сейчас
     * @return что помечено
     * @throws ru.svoemesto.syp.core.db.DbException если обновление не удалось
     */
    fun markStaleExcept(
        episodeId: Long,
        kind: AnalysisKind,
        currentParamsHash: String,
    ): StaleSummary {
        val runs = runStore.markStaleExcept(episodeId, kind, currentParamsHash)
        if (runs == 0) {
            return StaleSummary(0, 0, 0, emptyList())
        }
        return db.useTransaction { connection ->
            val scenes = markStructure(connection, episodeId, listOf(kind))
            StaleSummary(
                runs = runs,
                scenes = scenes.first,
                shots = scenes.second,
                episodeIds = listOf(episodeId),
            )
        }
    }

    /**
     * Помечает устаревшим результаты всех серий сериала при смене настройки.
     *
     * Настройки принадлежат сериалу, а не серии, поэтому смена порога
     * затрагивает структуру **всех** его серий: исключение «эта серия
     * разбиралась позже» сделала бы устарелость выборочной, а вопрос
     * «актуален ли мой результат» — неоднозначным.
     *
     * @param movieId сериал, настройки которого изменились
     * @param currentParamsHash хеш входов, действующих сейчас, по этому сериалу
     * @return что помечено
     * @throws ru.svoemesto.syp.core.db.DbException если обновление не удалось
     */
    fun markStaleForMovie(
        movieId: Long,
        currentParamsHash: String,
    ): StaleSummary {
        val episodeIds =
            db.select(
                "SELECT id FROM tbl_episodes WHERE id_movie = ? ORDER BY id",
                { it.long("id") },
                movieId,
            )
        var runs = 0
        var scenes = 0
        var shots = 0
        val marked = mutableSetOf<Long>()
        val runSql =
            "UPDATE tbl_analysis_runs SET is_stale = TRUE " +
                "WHERE id_episode = ? AND params_hash <> ?"
        db.useTransaction { connection ->
            episodeIds.forEach { episodeId ->
                runs +=
                    connection
                        .prepareStatement(runSql)
                        .use { statement ->
                            statement.setLong(1, episodeId)
                            statement.setString(2, currentParamsHash)
                            statement.executeUpdate()
                        }
                val structure = markStructure(connection, episodeId, AnalysisKind.entries)
                scenes += structure.first
                shots += structure.second
                if (structure.first > 0 || structure.second > 0) {
                    marked.add(episodeId)
                }
            }
        }
        return StaleSummary(runs = runs, scenes = scenes, shots = shots, episodeIds = marked.toList())
    }

    /**
     * Читает состояние актуальности результата серии.
     *
     * @param episodeId серия
     * @param kind вид прогона
     * @param currentParamsHash хеш входов, действующих сейчас
     * @return состояние актуальности
     */
    fun status(
        episodeId: Long,
        kind: AnalysisKind,
        currentParamsHash: String,
    ): StaleStatus {
        val run =
            runStore.latest(episodeId, kind)
                ?: return StaleStatus(
                    episodeId = episodeId,
                    runId = null,
                    algorithmVersion = null,
                    paramsHash = null,
                    currentParamsHash = currentParamsHash,
                    isStale = false,
                    reason = null,
                    isAbsent = true,
                )
        val otherInputs = run.paramsHash != currentParamsHash
        val isStale = otherInputs || run.isStale
        val reason =
            when {
                !isStale -> null
                otherInputs ->
                    "структура получена при других настройках: прогон считался с хешем входов " +
                        "${run.paramsHash}, а действующие входы дают $currentParamsHash. " +
                        "Результат сохранён и ручные правки на месте, пересчёт запускает оператор"
                else -> "структура помечена устаревшей после смены версии алгоритма или порогов"
            }
        return StaleStatus(
            episodeId = episodeId,
            runId = run.id,
            algorithmVersion = run.algorithmVersion,
            paramsHash = run.paramsHash,
            currentParamsHash = currentParamsHash,
            isStale = isStale,
            reason = reason,
            isAbsent = false,
        )
    }

    /**
     * Требует актуального результата.
     *
     * Вызывается там, где действие обязано опираться на текущие границы:
     * без этой проверки правка легла бы на структуру, полученную при других
     * порогах, и её пришлось бы переигрывать вручную.
     *
     * @param status состояние актуальности
     * @throws DomainException с кодом `STALE_RESULT`, если результат устарел
     */
    fun requireCurrent(status: StaleStatus) {
        if (status.isAbsent || !status.isStale) {
            return
        }
        throw DomainException(
            ErrorCode.STALE_RESULT,
            status.reason
                ?: "результат серии ${status.episodeId} помечен устаревшим. " +
                "Правка легла бы на границы, полученные при других настройках, — пересчитайте",
        )
    }

    /**
     * Помечает устаревшими сцены и планы серии по устаревшим прогонам.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param episodeId серия
     * @param kinds виды прогонов, по которым смотрим устаревание
     * @return число помеченных сцен и планов
     */
    private fun markStructure(
        connection: java.sql.Connection,
        episodeId: Long,
        kinds: List<AnalysisKind>,
    ): Pair<Int, Int> {
        val kindsList = kinds.joinToString(", ") { "'" + it.name + "'" }
        val condition =
            "run_id IN (SELECT id FROM tbl_analysis_runs WHERE id_episode = ? " +
                "AND kind IN ($kindsList) AND is_stale)"
        val sceneSql =
            "UPDATE ${StructureService.SCENE_TABLE} SET is_stale = TRUE " +
                "WHERE id_episode = ? AND $condition"
        val shotSql =
            "UPDATE ${StructureService.SHOT_TABLE} SET is_stale = TRUE " +
                "WHERE id_episode = ? AND $condition"
        val scenes =
            connection
                .prepareStatement(sceneSql)
                .use { statement ->
                    statement.setLong(1, episodeId)
                    statement.setLong(2, episodeId)
                    statement.executeUpdate()
                }
        val shots =
            connection
                .prepareStatement(shotSql)
                .use { statement ->
                    statement.setLong(1, episodeId)
                    statement.setLong(2, episodeId)
                    statement.executeUpdate()
                }
        return scenes to shots
    }
}
