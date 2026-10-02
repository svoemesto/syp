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
 * @property seriesIds серии, у которых помечено устаревшим
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class StaleSummary(
    val runs: Int,
    val scenes: Int,
    val shots: Int,
    val seriesIds: List<Long>,
) {
    /** Помечено ли хоть что-нибудь. */
    val isEmpty: Boolean
        get() = runs == 0 && scenes == 0 && shots == 0
}

/**
 * Состояние актуальности результата серии.
 *
 * @property seriesId серия, о которой идёт речь
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
    val seriesId: Long,
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
     * @param seriesId серия
     * @param kind вид прогона
     * @param currentParamsHash хеш входов, действующих сейчас
     * @return что помечено
     * @throws ru.svoemesto.syp.core.db.DbException если обновление не удалось
     */
    fun markStaleExcept(
        seriesId: Long,
        kind: AnalysisKind,
        currentParamsHash: String,
    ): StaleSummary {
        val runs = runStore.markStaleExcept(seriesId, kind, currentParamsHash)
        if (runs == 0) {
            return StaleSummary(0, 0, 0, emptyList())
        }
        return db.useTransaction { connection ->
            val scenes = markStructure(connection, seriesId, listOf(kind))
            StaleSummary(
                runs = runs,
                scenes = scenes.first,
                shots = scenes.second,
                seriesIds = listOf(seriesId),
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
     * @param serialId сериал, настройки которого изменились
     * @param currentParamsHash хеш входов, действующих сейчас, по этому сериалу
     * @return что помечено
     * @throws ru.svoemesto.syp.core.db.DbException если обновление не удалось
     */
    fun markStaleForSerial(
        serialId: Long,
        currentParamsHash: String,
    ): StaleSummary {
        val seriesIds =
            db.select(
                "SELECT id FROM series WHERE serial_id = ? ORDER BY id",
                { it.long("id") },
                serialId,
            )
        var runs = 0
        var scenes = 0
        var shots = 0
        val marked = mutableSetOf<Long>()
        val runSql =
            "UPDATE analysis_run SET is_stale = TRUE " +
                "WHERE series_id = ? AND params_hash <> ?"
        db.useTransaction { connection ->
            seriesIds.forEach { seriesId ->
                runs +=
                    connection
                        .prepareStatement(runSql)
                        .use { statement ->
                            statement.setLong(1, seriesId)
                            statement.setString(2, currentParamsHash)
                            statement.executeUpdate()
                        }
                val structure = markStructure(connection, seriesId, AnalysisKind.entries)
                scenes += structure.first
                shots += structure.second
                if (structure.first > 0 || structure.second > 0) {
                    marked.add(seriesId)
                }
            }
        }
        return StaleSummary(runs = runs, scenes = scenes, shots = shots, seriesIds = marked.toList())
    }

    /**
     * Читает состояние актуальности результата серии.
     *
     * @param seriesId серия
     * @param kind вид прогона
     * @param currentParamsHash хеш входов, действующих сейчас
     * @return состояние актуальности
     */
    fun status(
        seriesId: Long,
        kind: AnalysisKind,
        currentParamsHash: String,
    ): StaleStatus {
        val run =
            runStore.latest(seriesId, kind)
                ?: return StaleStatus(
                    seriesId = seriesId,
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
            seriesId = seriesId,
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
                ?: "результат серии ${status.seriesId} помечен устаревшим. " +
                "Правка легла бы на границы, полученные при других настройках, — пересчитайте",
        )
    }

    /**
     * Помечает устаревшими сцены и планы серии по устаревшим прогонам.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param seriesId серия
     * @param kinds виды прогонов, по которым смотрим устаревание
     * @return число помеченных сцен и планов
     */
    private fun markStructure(
        connection: java.sql.Connection,
        seriesId: Long,
        kinds: List<AnalysisKind>,
    ): Pair<Int, Int> {
        val kindsList = kinds.joinToString(", ") { "'" + it.name + "'" }
        val condition =
            "run_id IN (SELECT id FROM analysis_run WHERE series_id = ? " +
                "AND kind IN ($kindsList) AND is_stale)"
        val sceneSql =
            "UPDATE ${StructureService.SCENE_TABLE} SET is_stale = TRUE " +
                "WHERE series_id = ? AND $condition"
        val shotSql =
            "UPDATE ${StructureService.SHOT_TABLE} SET is_stale = TRUE " +
                "WHERE series_id = ? AND $condition"
        val scenes =
            connection
                .prepareStatement(sceneSql)
                .use { statement ->
                    statement.setLong(1, seriesId)
                    statement.setLong(2, seriesId)
                    statement.executeUpdate()
                }
        val shots =
            connection
                .prepareStatement(shotSql)
                .use { statement ->
                    statement.setLong(1, seriesId)
                    statement.setLong(2, seriesId)
                    statement.executeUpdate()
                }
        return scenes to shots
    }
}
