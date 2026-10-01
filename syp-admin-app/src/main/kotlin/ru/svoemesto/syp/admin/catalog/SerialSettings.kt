package ru.svoemesto.syp.admin.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.IntNode
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.Db
import ru.svoemesto.syp.core.db.Row
import ru.svoemesto.syp.core.db.Table
import java.time.OffsetDateTime

/**
 * Настройки сериала: что можно менять без правки кода.
 *
 * Перечисление закрывает весь список настроек первого среза. Смысл его не в
 * том, чтобы перечислить ключи, а в том, чтобы **значение неизвестного ключа
 * нельзя было записать**: настройка, которой нет в перечислении, не попала бы
 * ни в один расчёт, и её смена молча ничего не меняла бы — худший вид
 * поломки (constitution, ADR-0003).
 *
 * @property key имя настройки: оно и есть ключ в таблице
 * @property kind какой величиной является значение
 * @property title назначение настройки человеческим языком
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
enum class SerialSetting(
    val key: String,
    val kind: Kind,
    val title: String,
) {
    /** Порог границы сцены: оценка кадра выше порога означает смену сцены. */
    SCENE_THRESHOLD("scene.threshold", Kind.NUMBER, "Порог границы сцены"),

    /** Порог границы плана: ниже порога сцены, внутри сцены. */
    SHOT_THRESHOLD("shot.threshold", Kind.NUMBER, "Порог границы плана"),

    /** Пороги доли площади рамки лица в площади кадра: от большей кругности к меньшей. */
    SHOT_SIZE_THRESHOLDS("shot.size.thresholds", Kind.NUMBER_LIST, "Пороги размера плана"),

    /** Рамка, вытянутая сильнее этой пропорции, лицом не считается. */
    FACE_NOT_PERSON_ASPECT("face.not_person_aspect", Kind.NUMBER, "Пропорция, выше которой рамка не лицо"),

    /** Порог уверенности детектора лиц: параметр задания, а не константа кода. */
    FACE_DETECT_THRESHOLD("face.detect_threshold", Kind.NUMBER, "Порог уверенности детектора лиц"),

    /** Число кластеров лиц на холодном старте. */
    CLUSTER_COUNT("cluster.count", Kind.INTEGER, "Число кластеров лиц"),

    /** Порог объединения кластеров при обучении. */
    CLUSTER_MERGE_THRESHOLD("cluster.merge_threshold", Kind.NUMBER, "Порог объединения кластеров"),

    /** Число столбцов листа превью. */
    PREVIEW_SHEET_COLS("preview.sheet.cols", Kind.INTEGER, "Столбцов в листе превью"),

    /** Число строк листа превью. */
    PREVIEW_SHEET_ROWS("preview.sheet.rows", Kind.INTEGER, "Строк в листе превью"),

    /** Версия формата сценария сборки: попадает в сценарий и в подпись. */
    RECIPE_SCHEMA_VERSION("recipe.schema_version", Kind.INTEGER, "Версия формата сценария"),

    /** Число аудиодорожек в готовом файле: одна, перекодирования нет. */
    RECIPE_AUDIO_TRACK_COUNT("recipe.audio_track_count", Kind.INTEGER, "Аудиодорожек в готовом файле"),
    ;

    /** Тип значения настройки. */
    enum class Kind {
        /** Дробное число. */
        NUMBER,

        /** Целое число. */
        INTEGER,

        /** Список дробных чисел. */
        NUMBER_LIST,
    }

    companion object {
        /**
         * Находит настройку по имени ключа.
         *
         * @param key имя настройки
         * @return настройка или `null`, если такой настройки нет
         */
        fun byKey(key: String): SerialSetting? = entries.firstOrNull { it.key == key }

        /** Сколько настроек получает новый сериал. */
        const val DEFAULT_COUNT: Int = 11
    }
}

/**
 * Настройки сериала, прочитанные из базы.
 *
 * Значения хранятся в базе, а не в коде: пороги подбираются замером, и смена
 * порога после замера не должна требовать правки кода и пересборки (ADR-0003).
 * Ни одна величина здесь не имеет значения по умолчанию **в коде** — подстановка
 * означала бы вторую правду о настройках, которая разъедется с базой при первом
 * же изменении.
 *
 * @property values значения по именам настроек
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SerialSettings(
    val values: Map<String, JsonNode>,
) {
    /**
     * Значение настройки.
     *
     * @param setting настройка
     * @return значение
     * @throws DomainException если настройка отсутствует: у сериала обязаны
     *   быть все настройки, и отсутствие — это дефект данных, а не «дефолт»
     */
    fun node(setting: SerialSetting): JsonNode =
        values[setting.key]
            ?: throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "у сериала нет настройки «${setting.key}»: значения по умолчанию создаёт " +
                    "триггер базы, и их отсутствие означает расхождение данных",
            )

    /**
     * Дробное значение настройки.
     *
     * @param setting настройка
     * @return значение
     */
    fun number(setting: SerialSetting): Double = node(setting).asDouble()

    /**
     * Целое значение настройки.
     *
     * @param setting настройка
     * @return значение
     */
    fun integer(setting: SerialSetting): Int = node(setting).asInt()

    /**
     * Список дробных значений настройки.
     *
     * @param setting настройка
     * @return список значений в том же порядке, что и в базе
     */
    fun numbers(setting: SerialSetting): List<Double> = node(setting).map { it.asDouble() }

    /**
     * Проверяет, что присутствуют все настройки перечисления.
     *
     * @param serialId сериал, у которого проверяются настройки
     * @throws DomainException если какой-то настройки нет
     */
    fun requireComplete(serialId: Long) {
        val missing = SerialSetting.entries.map { it.key }.filterNot { values.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw DomainException(
                ErrorCode.INTERNAL_ERROR,
                "у сериала $serialId нет настроек: ${missing.joinToString(", ")}. " +
                    "Значения по умолчанию создаёт триггер базы при заведении сериала",
            )
        }
    }
}

/**
 * Хранилище настроек сериала.
 *
 * Настройки лежат в столбце `jsonb` по одной строке на ключ. Запись идёт по
 * различию значений с `recordhash` (constitution III): переписывается только
 * то, что действительно изменилось. **Дата обновления в хеш не входит** — это
 * не свойство настройки, а следствие записи, и включение её в хеш означало бы,
 * что строка переписывается при каждом сохранении.
 *
 * Смена значения не трогает результаты анализа: их перевод в состояние
 * устаревших делает слой анализа, который знает, каким набором параметров они
 * получены ([SerialSettingsStore.update] отдаёт список изменённых ключей
 * именно для этого).
 *
 * @property db доступ к базе сырым JDBC
 * @property mapper разбор и запись значений JSON
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SerialSettingsStore(
    private val db: Db,
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    /**
     * Читает настройки сериала.
     *
     * @param serialId сериал
     * @param requireComplete требовать ли наличия всех настроек
     * @return настройки сериала
     * @throws DomainException если сериала нет либо настройки неполны
     */
    fun read(
        serialId: Long,
        requireComplete: Boolean = true,
    ): SerialSettings {
        requireSerial(serialId)
        val values =
            db
                .select(
                    "SELECT key, value FROM analysis_setting WHERE serial_id = ?",
                    { row -> row.string("key") to mapper.readTree(row.string("value")) },
                    serialId,
                ).toMap()
        val settings = SerialSettings(values)
        if (requireComplete) {
            settings.requireComplete(serialId)
        }
        return settings
    }

    /**
     * Изменяет настройки сериала.
     *
     * Значения проверяются по смыслу, а не только по типу: порог
     * распознавания вне интервала (0; 1] или пороги размера плана, идущие не
     * по убыванию, сделали бы последующий анализ бессмысленным, а заметить
     * это можно было бы только через сорванные границы сцен.
     *
     * @param serialId сериал
     * @param changes новые значения по именам настроек
     * @return имена настроек, значение которых действительно изменилось
     * @throws DomainException с кодом `BAD_REQUEST`, если ключ неизвестен или
     *   значение не проходит проверку
     */
    fun update(
        serialId: Long,
        changes: Map<String, JsonNode>,
    ): List<String> {
        requireSerial(serialId)
        if (changes.isEmpty()) {
            return emptyList()
        }
        val checked = changes.map { (key, value) -> key to validate(key, value) }
        return db.useTransaction { connection ->
            checked
                .filter { (key, value) -> put(connection, serialId, key, value) }
                .map { (key, _) -> key }
        }
    }

    /**
     * Дата последней записи значения настройки.
     *
     * Отдельная величина, читаемая при надобности: держать дату внутри значения
     * означало бы хранить её в jsonb вместе с числом, и тогда подпись значения
     * зависела бы от того, когда его переписали.
     *
     * @param serialId сериал
     * @param setting настройка
     * @return дата записи или `null`, если настройки нет
     */
    fun updatedAt(
        serialId: Long,
        setting: SerialSetting,
    ): OffsetDateTime? =
        db.selectOne(
            "SELECT updated_at FROM analysis_setting WHERE serial_id = ? AND key = ?",
            { row: Row ->
                (row.raw("updated_at") as? java.sql.Timestamp)
                    ?.toInstant()
                    ?.atOffset(java.time.ZoneOffset.UTC)
            },
            serialId,
            setting.key,
        )

    /**
     * Значение настройки по умолчанию из базы, без приведения типов.
     *
     * Нужно там, где требуется исходный текст значения: подпись сценария
     * канонизирует числа без дробной части и ведущих нулей, и приводить их к
     * типу заранее значит потерять исходную запись.
     *
     * @param serialId сериал
     * @param setting настройка
     * @return значение как оно лежит в базе
     * @throws DomainException если настройки нет
     */
    fun rawValue(
        serialId: Long,
        setting: SerialSetting,
    ): String =
        db.selectOne(
            "SELECT value FROM analysis_setting WHERE serial_id = ? AND key = ?",
            { row: Row -> row.string("value") },
            serialId,
            setting.key,
        ) ?: throw DomainException(
            ErrorCode.INTERNAL_ERROR,
            "у сериала $serialId нет настройки «${setting.key}»",
        )

    /**
     * Записывает значение настройки, если оно изменилось.
     *
     * @param connection открытое соединение, транзакцией управляет вызывающий
     * @param serialId сериал
     * @param key имя настройки
     * @param value новое значение
     * @return `true`, если строка переписана
     */
    private fun put(
        connection: java.sql.Connection,
        serialId: Long,
        key: String,
        value: JsonNode,
    ): Boolean {
        val current =
            connection
                .prepareStatement("SELECT value, recordhash FROM analysis_setting WHERE serial_id = ? AND key = ?")
                .use { statement ->
                    statement.setLong(1, serialId)
                    statement.setString(2, key)
                    statement.executeQuery().use { resultSet ->
                        if (resultSet.next()) {
                            resultSet.getString(1) to resultSet.getString(2)
                        } else {
                            null
                        }
                    }
                }

        // Хеш считается по содержимому настройки, без даты обновления: дата —
        // следствие записи, а не свойство значения.
        val table = Table(TABLE, listOf("serial_id", "key", "value"), { listOf(serialId, key, value.toString()) }, current?.second)
        val hash = table.computeRecordHash()
        if (current?.second == hash) {
            return false
        }

        if (current == null) {
            connection
                .prepareStatement(
                    "INSERT INTO $TABLE (serial_id, key, value, updated_at, recordhash) " +
                        "VALUES (?, ?, ?::jsonb, now(), ?)",
                ).use { statement ->
                    statement.setLong(1, serialId)
                    statement.setString(2, key)
                    statement.setString(3, value.toString())
                    statement.setString(4, hash)
                    statement.executeUpdate()
                }
        } else {
            connection
                .prepareStatement(
                    "UPDATE $TABLE SET value = ?::jsonb, updated_at = now(), recordhash = ? " +
                        "WHERE serial_id = ? AND key = ?",
                ).use { statement ->
                    statement.setString(1, value.toString())
                    statement.setString(2, hash)
                    statement.setLong(3, serialId)
                    statement.setString(4, key)
                    statement.executeUpdate()
                }
        }
        return true
    }

    /**
     * Проверяет ключ и значение настройки.
     *
     * @param key имя настройки
     * @param value значение
     * @return каноническое значение: дробная величина приводится к числу с
     *   точностью, достаточной для подписи, целая — к целому
     * @throws DomainException с кодом `BAD_REQUEST`, если ключ неизвестен или
     *   значение не подходит
     */
    private fun validate(
        key: String,
        value: JsonNode?,
    ): JsonNode {
        val setting =
            SerialSetting.byKey(key)
                ?: throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "настройки «$key» нет. Доступны: ${SerialSetting.entries.joinToString(", ") { it.key }}",
                )
        val node =
            value
                ?: throw DomainException(ErrorCode.BAD_REQUEST, "у настройки «$key» пустое значение")
        if (node.isNull) {
            throw DomainException(ErrorCode.BAD_REQUEST, "у настройки «$key» значение null")
        }
        return when (setting.kind) {
            SerialSetting.Kind.NUMBER -> numberOf(setting, node)
            SerialSetting.Kind.INTEGER -> integerOf(setting, node)
            SerialSetting.Kind.NUMBER_LIST -> numberListOf(setting, node)
        }
    }

    /** Проверяет и приводит дробное значение. */
    private fun numberOf(
        setting: SerialSetting,
        node: JsonNode,
    ): JsonNode {
        val value = requireNumber(setting, node)
        val problem = problemOfNumber(setting, value)
        if (problem != null) {
            throw DomainException(ErrorCode.BAD_REQUEST, "настройка «${setting.key}»: $problem")
        }
        return normalized(value)
    }

    /** Проверяет и приводит целое значение. */
    private fun integerOf(
        setting: SerialSetting,
        node: JsonNode,
    ): JsonNode {
        if (!node.isIntegralNumber) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "настройка «${setting.key}» должна быть целым числом, задано «${node.asText()}»",
            )
        }
        val value = node.asLong()
        val problem = problemOfInteger(setting, value)
        if (problem != null) {
            throw DomainException(ErrorCode.BAD_REQUEST, "настройка «${setting.key}»: $problem")
        }
        return IntNode.valueOf(value.toInt())
    }

    /** Проверяет и приводит список дробных значений. */
    private fun numberListOf(
        setting: SerialSetting,
        node: JsonNode,
    ): JsonNode {
        if (!node.isArray) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "настройка «${setting.key}» должна быть списком чисел, задано «${node.asText()}»",
            )
        }
        val array = node as ArrayNode
        if (array.isEmpty) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "настройка «${setting.key}» не может быть пустым списком: без порогов размер плана не определить",
            )
        }
        val values = array.map { requireNumber(setting, it) }
        values.forEach { value ->
            if (value <= 0.0) {
                throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "настройка «${setting.key}»: порог $value должен быть положительным",
                )
            }
        }
        // Пороги идут от большей кругности к меньшей: иначе категории размера
        // плана накладывались бы друг на друга.
        values.zipWithNext { bigger, smaller ->
            if (bigger <= smaller) {
                throw DomainException(
                    ErrorCode.BAD_REQUEST,
                    "настройка «${setting.key}»: пороги должны убывать от большей кругности к меньшей, " +
                        "а задано $bigger после $smaller",
                )
            }
        }
        val result = mapper.createArrayNode()
        values.forEach { result.add(normalized(it)) }
        return result
    }

    /** Требует, чтобы значение было числом. */
    private fun requireNumber(
        setting: SerialSetting,
        node: JsonNode,
    ): Double {
        if (!node.isNumber) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "настройка «${setting.key}» должна быть числом, задано «${node.asText()}»",
            )
        }
        return node.asDouble()
    }

    /**
     * Приводит дробное значение к виду без хвостовых нулей.
     *
     * Канонизация сценария требует чисел без дробной части и ведущих нулей
     * (контракт рецепта 2.1). `0.50` и `0.5` — одно и то же значение, но разные
     * байты, поэтому запись приводится сразу.
     */
    private fun normalized(value: Double): JsonNode =
        if (value == Math.floor(value) && !value.isInfinite()) {
            IntNode.valueOf(value.toInt())
        } else {
            DoubleNode(value)
        }

    /** Смысловая проверка дробной настройки: возвращает текст проблемы либо `null`. */
    private fun problemOfNumber(
        setting: SerialSetting,
        value: Double,
    ): String? =
        when (setting) {
            SerialSetting.SCENE_THRESHOLD, SerialSetting.SHOT_THRESHOLD ->
                if (value <= 0.0) "порог должен быть положительным, задано $value" else null

            SerialSetting.FACE_NOT_PERSON_ASPECT ->
                if (value <= 1.0) {
                    "пропорция должна быть больше единицы, задано $value"
                } else {
                    null
                }

            SerialSetting.FACE_DETECT_THRESHOLD, SerialSetting.CLUSTER_MERGE_THRESHOLD ->
                if (value <= 0.0 || value > 1.0) {
                    "порог должен лежать в интервале (0; 1], задано $value"
                } else {
                    null
                }

            else -> null
        }

    /** Смысловая проверка целой настройки: возвращает текст проблемы либо `null`. */
    private fun problemOfInteger(
        setting: SerialSetting,
        value: Long,
    ): String? =
        when (setting) {
            SerialSetting.CLUSTER_COUNT,
            SerialSetting.PREVIEW_SHEET_COLS,
            SerialSetting.PREVIEW_SHEET_ROWS,
            SerialSetting.RECIPE_SCHEMA_VERSION,
            ->
                if (value <= 0) {
                    "значение должно быть положительным, задано $value"
                } else {
                    null
                }

            SerialSetting.RECIPE_AUDIO_TRACK_COUNT ->
                if (value < 0) {
                    "число аудиодорожек не может быть отрицательным, задано $value"
                } else {
                    null
                }

            else -> null
        }

    /** Проверяет, что сериал заведён. */
    private fun requireSerial(serialId: Long) {
        val exists =
            db.selectOne(
                "SELECT count(*) AS total FROM serial WHERE id = ?",
                { row: Row -> row.int("total") },
                serialId,
            ) ?: 0
        if (exists == 0) {
            throw DomainException(
                ErrorCode.NOT_FOUND,
                "сериал $serialId не заведён: настройки задаются только заведённому сериалу",
            )
        }
    }

    private companion object {
        /** Имя таблицы настроек. */
        const val TABLE: String = "analysis_setting"
    }
}
