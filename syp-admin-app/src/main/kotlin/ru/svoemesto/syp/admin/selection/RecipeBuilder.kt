package ru.svoemesto.syp.admin.selection

import ru.svoemesto.syp.admin.catalog.SerialSetting
import ru.svoemesto.syp.admin.catalog.SerialSettingsStore
import ru.svoemesto.syp.admin.catalog.Series
import ru.svoemesto.syp.admin.catalog.SeriesStore
import ru.svoemesto.syp.admin.integrity.ChecksumRegistry
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.contract.ErrorItem
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.recipe.BuildRecipe
import ru.svoemesto.syp.core.recipe.BuildRecipeItem
import ru.svoemesto.syp.core.recipe.RecipeCatalog
import ru.svoemesto.syp.core.recipe.RecipeCompatibility
import ru.svoemesto.syp.core.recipe.RecipeDocument
import ru.svoemesto.syp.core.recipe.RecipeFormat
import ru.svoemesto.syp.core.recipe.RecipeFragmentPlan
import ru.svoemesto.syp.core.recipe.RecipePaths
import ru.svoemesto.syp.core.recipe.RecipeSigner
import ru.svoemesto.syp.core.recipe.RecipeSlice
import ru.svoemesto.syp.core.recipe.RecipeState
import ru.svoemesto.syp.core.recipe.RecipeStore
import ru.svoemesto.syp.core.recipe.SeriesParameters
import ru.svoemesto.syp.core.signing.Signer
import ru.svoemesto.syp.core.storage.ArtifactKind
import ru.svoemesto.syp.core.storage.ArtifactRegistry
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Генератор сценария сборки.
 *
 * Превращает набор выбранных сцен в **исполняемый сценарий сборки**: список
 * фрагментов с относительными путями и границами по номерам кадров, плюс
 * снимок данных сцены для локального показа. Видеофайла он не производит и
 * даже поля, куда его положить, не имеет (ADR-0009, FR-088).
 *
 * **Генератор живёт в админском бэкенде**, а не в общем модуле: он читает
 * базу, а `syp-core` состояния не содержит — ни базы, ни очереди, ни файловой
 * системы (ADR-0011, последствие 2). Формат, канонизация, подпись, проверка
 * совместимости и расчёт границ — наоборот, в общем модуле, и здесь они
 * вызываются, а не переписываются.
 *
 * Выдаёт админка потому, что закрытый ключ подписи живёт в её контейнере:
 * подписывать сценарий там, где его отдают, было бы нечем (ADR-0014, решение
 * владельца 2026-10-03).
 *
 * Порядок проверок задан контрактом
 * [`admin-api.md`](../../../../../../../../../specs/001-first-vertical-slice/contracts/admin-api.md)
 * § 10.1 и идёт в порядке возрастания стоимости отказа: пустой список, разбор
 * серий, совместимость, наличие эталонных сумм. Ни одна проверка не требует
 * прохода по видео.
 *
 * @property db доступ к базе сырым JDBC
 * @property seriesStore хранилище серий
 * @property checksums справочник эталонных сумм исходников
 * @property settingsStore настройки сериала
 * @property recipes хранилище сценариев
 * @property catalog каталог сценариев: помечает устаревшими
 * @property artifacts реестр артефактов с каноническими байтами
 * @property signer подписант ключом админского контейнера
 * @property clock источник момента выдачи
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class RecipeBuilder(
    private val db: Db,
    private val seriesStore: SeriesStore,
    private val checksums: ChecksumRegistry,
    private val settingsStore: SerialSettingsStore,
    private val recipes: RecipeStore,
    private val catalog: RecipeCatalog,
    private val artifacts: ArtifactRegistry,
    private val signer: Signer,
    private val clock: Clock,
) {
    /**
     * Выдаёт сценарий по выбранным сценам.
     *
     * @param recipeName название сценария
     * @param sceneIds идентификаторы выбранных сцен в порядке следования
     * @return записанный и подписанный сценарий в состоянии `DONE`
     * @throws DomainException если выбор пуст, сцена нет, серия не разобрана,
     *   серии несовместимы или у серии нет актуальной суммы
     * @throws ru.svoemesto.syp.core.db.DbException если запись не состоялась
     */
    fun issue(
        recipeName: String,
        sceneIds: List<Long>,
    ): BuildRecipe {
        if (sceneIds.isEmpty()) {
            throw DomainException(
                ErrorCode.EMPTY_SELECTION,
                "сценарий не выдан: в запросе нет ни одной сцены. Подборка без сцен пуста",
            )
        }
        val scenes = loadScenes(sceneIds)
        val seriesIds = scenes.values.map { it.seriesId }.distinct()
        val series = seriesIds.associateWith { readSeries(it) }
        val serialId = requireSingleSerial(series)
        val serial = readSerial(serialId)
        val ordered = sceneIds.map { scenes.getValue(it) }

        val settings = settingsStore.read(serialId)
        val schemaVersion = settings.integer(SerialSetting.RECIPE_SCHEMA_VERSION)
        val audioTrackCount = settings.integer(SerialSetting.RECIPE_AUDIO_TRACK_COUNT)

        requireAnalyzed(series.keys.toList())
        requireCompatible(series)
        val digests = requireChecksums(series.keys.toList())

        val items = buildItems(ordered, series, digests, serial.sourceRoot)
        val (expectedDurationMs, expectedFrameCount) = RecipeFragmentPlan.expectedTotals(slicesOf(items, series))

        // Помечаются сценарии прежней версии формата **до** создания нового:
        // к моменту, когда пользователь увидит список, актуальность уже
        // известна (FR-090, ADR-0014).
        catalog.markStaleOnSchemaChange(serialId, schemaVersion)

        val createdAt = now()
        val stored =
            recipes.insert(
                BuildRecipe(
                    serialId = serialId,
                    name = recipeName,
                    schemaVersion = schemaVersion,
                    state = RecipeState.CREATING,
                    itemCount = items.size,
                    expectedDurationMs = expectedDurationMs,
                    expectedFrameCount = expectedFrameCount,
                    createdAt = createdAt,
                ),
                items.mapIndexed { index, item -> item.copy(recipeId = 0L, ordinal = index + 1) },
            )
        return sign(store = stored, items = items, serialName = serial.name, audioTrackCount = audioTrackCount)
    }

    /**
     * Подписывает сценарий, кладёт канонические байты в артефакт и переводит
     * запись в `DONE`.
     *
     * Порядок обязателен: сначала подпись над каноническими байтами, потом
     * артефакт, и только после перехода артефакта в `READY` — состояние
     * `DONE`. Иначе запись ссылалась бы на файл, которого ещё нет, то есть на
     * подпись под байтами, которых пользователь не получит (FR-091).
     */
    private fun sign(
        store: BuildRecipe,
        items: List<BuildRecipeItem>,
        serialName: String,
        audioTrackCount: Int,
    ): BuildRecipe {
        val recipeId =
            store.id
                ?: throw IllegalStateException(
                    "Сценарий записан, но без идентификатора: подписать нечего",
                )
        val document =
            RecipeDocument(
                schemaVersion = store.schemaVersion,
                serialId = store.serialId,
                serialName = serialName,
                recipeId = recipeId,
                recipeName = store.name,
                signingKeyId = signer.signingKeyId,
                createdAt = store.createdAt,
                audioTrackCount = audioTrackCount,
                expectedDurationMs = store.expectedDurationMs!!,
                expectedFrameCount = store.expectedFrameCount!!,
                items = items.mapIndexed { index, item -> item.copy(recipeId = recipeId, ordinal = index + 1).toDocumentItem() },
            )
        val (bytes, signed) = RecipeSigner.issue(signer, document)
        val objectKey = "recipes/${store.serialId}/$recipeId${RecipeFormat.FILE_SUFFIX}"
        return try {
            val artifact =
                artifacts.begin(
                    jobId = null,
                    kind = ArtifactKind.RECIPE,
                    finalKey = objectKey,
                    contentType = RecipeFormat.CONTENT_TYPE,
                )
            artifacts.writeTemporary(artifact, bytes.inputStream())
            val ready = artifacts.markReady(artifact, signed.contentSha256, bytes.size.toLong())
            val finished =
                store.copy(
                    state = RecipeState.DONE,
                    artifactId = ready.id,
                    contentSha256 = signed.contentSha256,
                    signature = signed.signature,
                    signingKeyId = signed.signingKeyId,
                    finishedAt = now(),
                    errorText = null,
                )
            recipes.save(finished)
            // Чтение после сохранения, а не возврат собранного вручную объекта:
            // так в ответ попадает ровно то, что лежит в базе, включая хеш строки.
            recipes.find(recipeId) ?: finished
        } catch (failure: Exception) {
            // Запись без подписи и без артефакта — невыдающийся мусор. Она
            // переводится в ERROR с текстом, а не остаётся висеть в CREATING
            // навсегда: по FR-092 сбой обязан быть виден.
            runCatching {
                recipes.save(store.copy(state = RecipeState.ERROR, errorText = failure.message ?: "выдача не удалась"))
            }
            throw failure
        }
    }

    /** Читает выбранные сцены и требует, чтобы все они нашлись и были актуальны. */
    private fun loadScenes(sceneIds: List<Long>): Map<Long, SelectedScene> {
        val placeholders = sceneIds.joinToString(", ") { "?" }
        val rows =
            db.select(
                "SELECT id, series_id, first_frame, last_frame, location_id, is_stale " +
                    "FROM $SCENE_TABLE WHERE id IN ($placeholders)",
                ::readSelectedScene,
                *sceneIds.map { it as Any? }.toTypedArray(),
            )
        val found = rows.associateBy { it.id }
        val missing = sceneIds.filterNot { found.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw DomainException(
                ErrorCode.NOT_FOUND,
                "сценарий не выдан: сцен ${missing.joinToString(", ")} не найдены",
                missing.map { ErrorItem("scene", it.toString(), "нет такой сцены") },
            )
        }
        val stale = found.values.filter { it.isStale }
        if (stale.isNotEmpty()) {
            throw DomainException(
                ErrorCode.STALE_RESULT,
                "сценарий не выдан: сцены ${stale.joinToString(", ") { it.id.toString() }} помечены " +
                    "устаревшими. Правки и ручная доводка в них на месте, пересчёт запускает оператор (FR-090)",
                stale.map { ErrorItem("scene", it.id.toString(), "структура устарела") },
            )
        }
        // Порядок берётся из запроса, а не из выборки: сценарий — рецепт с
        // порядком следования, и порядок базы на него не влияет.
        return sceneIds.distinct().associateWith { found.getValue(it) }
    }

    /** Требует, чтобы все выбранные серии принадлежали одному сериалу. */
    private fun requireSingleSerial(series: Map<Long, Series>): Long {
        val serialIds = series.values.map { it.serialId }.distinct()
        return serialIds.firstOrNull()
            ?: throw DomainException(
                ErrorCode.BAD_REQUEST,
                "сценарий не выдан: сцены принадлежат разным сериалам. Подборка собирается " +
                    "внутри одного дерева каталогов (FR-089a)",
            )
    }

    /** Читает сериал вместе с корнем каталога. */
    private fun readSerial(serialId: Long): SerialInfo =
        db.selectOne(
            "SELECT name, source_root FROM $SERIAL_TABLE WHERE id = ?",
            { row: Row -> SerialInfo(row.string("name"), row.string("source_root")) },
            serialId,
        ) ?: throw DomainException(ErrorCode.NOT_FOUND, "сериал $serialId не найден")

    /** Читает серию подборки. */
    private fun readSeries(seriesId: Long): Series =
        seriesStore.find(seriesId)
            ?: throw DomainException(
                ErrorCode.NOT_FOUND,
                "серия $seriesId не найдена",
                listOf(ErrorItem("series", seriesId.toString(), "нет такой серии")),
            )

    /** Требует, чтобы у каждой серии были сцены с размеченными границами. */
    private fun requireAnalyzed(seriesIds: List<Long>) {
        val unanalyzed =
            seriesIds.filter {
                db.selectOne(
                    "SELECT count(*) AS total FROM $SCENE_TABLE WHERE series_id = ? AND is_stale = FALSE",
                    { row: Row -> row.int("total") },
                    it,
                ) ?: 0 == 0
            }
        if (unanalyzed.isEmpty()) {
            return
        }
        throw DomainException(
            ErrorCode.SERIES_NOT_ANALYZED,
            "сценарий не выдан: у серий ${unanalyzed.joinToString(", ")} нет сцен с размеченными " +
                "границами. Поставьте анализ серии и дождитесь его окончания",
            unanalyzed.map { ErrorItem("series", it.toString(), "нет размеченных сцен") },
        )
    }

    /** Требует, чтобы серии подборки совпадали по параметрам склейки. */
    private fun requireCompatible(series: Map<Long, Series>) {
        val report = RecipeCompatibility.check(series.values.map { it.toParameters() })
        if (report.isCompatible) {
            return
        }
        throw DomainException(
            ErrorCode.INCOMPATIBLE_SERIES,
            "сценарий не выдан: серии несовместимы для склейки без перекодирования. " +
                "Различаются признаки ${report.differingAttributes.joinToString(", ")}",
            report.incompatible.map { incompatible ->
                ErrorItem(
                    "series",
                    incompatible.parameters.seriesId.toString(),
                    "серия «${incompatible.parameters.name}» отличается: " +
                        incompatible.differingAttributes.joinToString(", "),
                )
            },
        )
    }

    /**
     * Требует актуальной эталонной суммы у каждой серии подборки.
     *
     * Молча выдать сценарий серии без суммы нельзя: пользователь узнал бы об
     * этом через час работы на своей машине, а не до неё (ADR-0009,
     * последствие 4).
     */
    private fun requireChecksums(seriesIds: List<Long>): Map<Long, String> {
        val digests = mutableMapOf<Long, String>()
        val missing = mutableListOf<Long>()
        seriesIds.forEach { seriesId ->
            val entry = checksums.current(seriesId)
            if (entry == null || !entry.isUsable || entry.digest == null) {
                missing += seriesId
            } else {
                digests[seriesId] = entry.digest
            }
        }
        if (missing.isNotEmpty()) {
            throw DomainException(
                ErrorCode.CHECKSUM_NOT_READY,
                "сценарий не выдан: у серий ${missing.joinToString(", ")} нет актуальной суммы " +
                    "исходника. Сумма считается один раз; поставьте пересчёт и дождитесь его " +
                    "окончания (FR-089)",
                missing.map { ErrorItem("series", it.toString(), "актуальной суммы нет") },
            )
        }
        return digests
    }

    /**
     * Собирает фрагменты сценария.
     *
     * Значения сцены кладутся **снимками строк**, а не ссылками: правка
     * справочника после выдачи не должна «слепить» уже скачанный сценарий, а
     * локальный показ работает без сервера (FR-089d).
     */
    private fun buildItems(
        scenes: List<SelectedScene>,
        series: Map<Long, Series>,
        digests: Map<Long, String>,
        sourceRoot: String,
    ): List<BuildRecipeItem> {
        val items =
            scenes.mapIndexed { index, scene ->
                val entry = series.getValue(scene.seriesId)
                val relativePath =
                    entry.relativePath(sourceRoot)
                        ?: throw DomainException(
                            ErrorCode.SOURCE_UNREADABLE,
                            "сценарий не выдан: файл серии «${entry.name}» лежит вне корня каталога " +
                                "сериала. Относительный путь выдумывать нельзя (FR-089a)",
                            listOf(ErrorItem("series", scene.seriesId.toString(), "путь вне корня сериала")),
                        )
                val cut =
                    RecipeFragmentPlan.cutBoundaries(
                        firstFrame = scene.firstFrame,
                        lastFrame = scene.lastFrame,
                        keyframes = entry.keyframeMap,
                    )
                BuildRecipeItem(
                    recipeId = 0L,
                    ordinal = index + 1,
                    sceneId = scene.id,
                    seriesId = scene.seriesId,
                    seriesName = entry.name,
                    relativePath = relativePath,
                    sourceSha256 = digests.getValue(scene.seriesId),
                    firstFrame = scene.firstFrame,
                    lastFrame = scene.lastFrame,
                    cutFirstFrame = cut.cutFirstFrame,
                    cutLastFrame = cut.cutLastFrame,
                    sceneTitle = sceneTitle(scene),
                    locationName = locationName(scene),
                    personNames = personNames(scene),
                )
            }
        // Путь проверяется ещё раз при записи фрагмента: ограничение базы и
        // проверка генератора должны говорить одно и то же (FR-089a).
        items.forEach { RecipePaths.requireInsideSerialTree(it.relativePath) }
        return items
    }

    /**
     * Название сцены-снимок.
     *
     * В модели данных 2.7 у сцены **нет** поля названия, и в таблице `scene`
     * его нет тоже: назвать сцену сейчас нечем. Поле снимка при этом
     * существует и пишется — пустым. Это расхождение записано в `tasks.md`
     * (задача T130) и ждёт решения владельца: либо столбец `scene.title`,
     * либо подтверждение, что название не нужно.
     */
    private fun sceneTitle(scene: SelectedScene): String? =
        db.selectOne(
            "SELECT title FROM $SCENE_TABLE WHERE id = ?",
            { row: Row -> row.stringOrNull("title") },
            scene.id,
        )

    /** Место действия-снимок: название локации на момент выдачи, а не ссылка. */
    private fun locationName(scene: SelectedScene): String? {
        val locationId = scene.locationId ?: return null
        return db.selectOne(
            "SELECT name FROM $LOCATION_TABLE WHERE id = ?",
            { row: Row -> row.string("name") },
            locationId,
        )
    }

    /**
     * Имена персонажей сцены-снимок, по алфавиту.
     *
     * Берутся лица, попавшие в диапазон кадров сцены, у которых персона — не
     * служебная заглушка. «Не лицо» и «распознано, имя не подтверждено» — это
     * служебные персоны (Р-12), и выводить их в сценарий как персонажей сцены
     * нельзя: локальный показ показал бы зрителю «Не лицо» как героя.
     */
    private fun personNames(scene: SelectedScene): List<String> =
        db.select(
            "SELECT DISTINCT p.name AS name FROM $FACE_TABLE f " +
                "JOIN $PERSON_TABLE p ON p.id = f.person_id " +
                "WHERE f.series_id = ? AND f.frame_number >= ? AND f.frame_number <= ? " +
                "AND p.kind = 'PERSON' ORDER BY p.name",
            { row: Row -> row.string("name") },
            scene.seriesId,
            scene.firstFrame,
            scene.lastFrame,
        )

    /** Фрагменты с точки зрения расчётных величин. */
    private fun slicesOf(
        items: List<BuildRecipeItem>,
        series: Map<Long, Series>,
    ): List<RecipeSlice> =
        items.map { item ->
            val entry = series.getValue(item.seriesId)
            RecipeSlice(
                frames = item.cutLastFrame - item.cutFirstFrame,
                timeBaseNum = entry.timeBaseNum,
                timeBaseDen = entry.timeBaseDen,
            )
        }

    /** Момент выдачи: UTC, потому что в канонические байты он пишется в UTC. */
    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        /** Имя таблицы сцен. */
        const val SCENE_TABLE: String = "scene"

        /** Имя таблицы мест действия. */
        const val LOCATION_TABLE: String = "location"

        /** Имя таблицы сериалов. */
        const val SERIAL_TABLE: String = "serial"

        /** Имя таблицы лиц. */
        const val FACE_TABLE: String = "face"

        /** Имя таблицы персон. */
        const val PERSON_TABLE: String = "person"
    }
}

/**
 * Сцена, выбранная для сценария сборки.
 *
 * Отдельный тип от рабочей сцены админки: здесь нужен **снимок на момент
 * выдачи**, и добавлять к нему признаки устаревания разметки незачем — они
 * проверяются до выборки, а не после.
 *
 * @property id идентификатор сцены
 * @property seriesId серия-владелец
 * @property firstFrame первый кадр сцены
 * @property lastFrame последний кадр сцены
 * @property locationId место действия либо `null`
 * @property isStale помечена ли сцена устаревшей
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class SelectedScene(
    val id: Long,
    val seriesId: Long,
    val firstFrame: Int,
    val lastFrame: Int,
    val locationId: Long?,
    val isStale: Boolean,
)

/** Сериал, как он нужен генератору сценария: имя и корень каталога. */
private data class SerialInfo(
    val name: String,
    val sourceRoot: String,
)

/** Читает выбранную сцену из строки выборки. */
private fun readSelectedScene(row: Row): SelectedScene =
    SelectedScene(
        id = row.long("id"),
        seriesId = row.long("series_id"),
        firstFrame = row.int("first_frame"),
        lastFrame = row.int("last_frame"),
        locationId = row.longOrNull("location_id"),
        isStale = row.booleanOrNull("is_stale") == true,
    )

/** Параметры серии для проверки совместимости: снимок, снятый при регистрации. */
private fun Series.toParameters(): SeriesParameters =
    SeriesParameters(
        seriesId = id!!,
        name = name,
        width = width,
        height = height,
        videoCodec = videoCodec,
        videoProfile = videoProfile,
        pixelFormat = pixelFormat,
        timeBaseNum = timeBaseNum,
        timeBaseDen = timeBaseDen,
        audioCodec = audioCodec,
        audioChannels = audioChannels,
        audioSampleRate = audioSampleRate,
    )
