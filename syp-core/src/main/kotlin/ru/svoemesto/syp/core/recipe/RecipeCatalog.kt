package ru.svoemesto.syp.core.recipe

import ru.svoemesto.syp.core.db.Db

/**
 * Состояние актуальности сценария сборки.
 *
 * Показывается, а **не** превращается в отказ: устаревший сценарий по-прежнему
 * можно скачать и проверить подписью — просто он выдан при другой версии
 * формата, и новый воркер может его не понять. Запрещать скачивание значило бы
 * отнять у пользователя сценарий, за который он уже отработал (FR-090,
 * ADR-0014).
 *
 * @property isStale помечен ли сценарий устаревшим
 * @property reason чем именно устарел; `null`, если он актуален
 * @property staleResultCode машинный код пометки либо `null`
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class RecipeFreshness(
    val isStale: Boolean,
    val reason: String?,
) {
    /** Код `STALE_RESULT`, если сценарий помечен, иначе `null`. */
    val staleResultCode: String?
        get() = if (isStale) ru.svoemesto.syp.core.contract.ErrorCode.STALE_RESULT.name else null
}

/**
 * Каталог сценариев: чтение, актуальность и помечание устаревшими.
 *
 * Отдельный класс от [RecipeStore] по одной причине: хранилище пишет строку
 * целиком, а здесь меняется **один признак** сразу у многих строк. Считать
 * хеш каждой строки ради единственного булева столбца значило бы читать и
 * переписывать сценарии целиком — с их подписями и суммами, — там, где
 * меняется один столбец (constitution III, правило «запись по различию»).
 *
 * **Помечается, а не удаляется и не переписывается.** Сценарий, файл, подпись
 * и идентификатор ключа остаются на месте: ранее выданный сценарий обязан
 * оставаться проверяемым по своему ключу, пока тот в списке доверенных
 * (FR-090, ADR-0014).
 *
 * Помечание ставит админский бэкенд при выдаче: он единственный знает, какая
 * версия формата действует, и он же подписывает сценарии (ADR-0014).
 *
 * @property db доступ к базе сырым JDBC
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class RecipeCatalog(
    private val db: Db,
) {
    /**
     * Помечает устаревшими сценарии сериала, выданные при другой версии формата.
     *
     * Сравниваются версии, а не даты: сценарий, выданный сегодня при прежней
     * версии, так же устарел, как выданный в прошлом году. Версия, по которой
     * сценарий выдан, лежит в самой строке и не переписывается.
     *
     * @param serialId сериал
     * @param currentSchemaVersion версия формата, действующая сейчас
     * @return число помеченных сценариев
     * @throws ru.svoemesto.syp.core.db.DbException если обновление не удалось
     */
    fun markStaleOnSchemaChange(
        serialId: Long,
        currentSchemaVersion: Int,
    ): Int =
        db.update(
            "UPDATE $TABLE SET is_stale = TRUE " +
                "WHERE serial_id = ? AND is_stale = FALSE AND schema_version <> ?",
            serialId,
            currentSchemaVersion,
        )

    /**
     * Состояние актуальности сценария.
     *
     * @param recipe сценарий
     * @param currentSchemaVersion версия формата, действующая сейчас
     * @return актуален ли сценарий и, если нет, чем именно устарел
     */
    fun freshness(
        recipe: BuildRecipe,
        currentSchemaVersion: Int,
    ): RecipeFreshness {
        val outdated = recipe.schemaVersion != currentSchemaVersion
        val isStale = outdated || recipe.isStale
        val reason =
            when {
                !isStale -> null
                outdated ->
                    "сценарий выдан при версии формата ${recipe.schemaVersion}, а действует " +
                        "версия $currentSchemaVersion. Сценарий сохранён и по-прежнему " +
                        "проверяем по своему ключу; пересчёт запускает оператор (FR-090)"
                else -> "сценарий помечен устаревшим после смены версии формата"
            }
        return RecipeFreshness(isStale, reason)
    }

    companion object {
        /** Имя таблицы сценариев. */
        const val TABLE: String = RecipeStore.TABLE
    }
}
