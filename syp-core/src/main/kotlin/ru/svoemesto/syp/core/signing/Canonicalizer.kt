package ru.svoemesto.syp.core.signing

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Канонизация объекта сценария перед подписью.
 *
 * Подпись существует только вместе с определением «что именно подписано».
 * Без канонизации повторная выдача при тех же данных дала бы разные байты
 * (порядок полей при разборе JSON не гарантирован), и проверка подписи стала
 * бы лотереей (research.md Т-23, ADR-0011).
 *
 * Правила канонизации, все обязательные:
 *
 * 1. **Порядок полей фиксирован** объявлением типа, а не порядком в карте.
 * 2. **Результат — JSON**: строковые значения заключены в кавычки и
 *    экранированы, без них воркер не разобрал бы файл сценария.
 * 3. **Перевод строки — только LF**, BOM не пишется.
 * 4. **Числа приведены к одному виду**: целые без дробной части, дробные —
 *    через десятичную запятую без хвостовых нулей.
 *
 * Кодировка — UTF-8 без BOM.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object Canonicalizer {
    /**
     * Приводит значение к каноническому виду и возвращает его байты.
     *
     * Возвращается **JSON**, а не «что-то похожее на JSON»: сценарий читает
     * воркер на машине пользователя обычным разбором JSON, и текст без кавычек
     * вокруг строк он не прочитает вовсе. Подписывать имеет смысл тот текст,
     * который получатель действительно разберёт.
     *
     * @param value значение любого поддерживаемого типа
     * @return канонические байты в UTF-8 без BOM
     * @throws CanonicalizationException если тип значения не поддерживается
     */
    fun canonicalBytes(value: Any?): ByteArray =
        when (value) {
            null -> "null".toByteArray(StandardCharsets.UTF_8)
            is Boolean -> value.toString().toByteArray(StandardCharsets.UTF_8)
            is String -> quoted(canonicalString(value)).toByteArray(StandardCharsets.UTF_8)
            is Int, is Long, is Short, is Byte -> value.toString().toByteArray(StandardCharsets.UTF_8)
            is BigDecimal -> canonicalNumber(value).toByteArray(StandardCharsets.UTF_8)
            is Double -> canonicalNumber(BigDecimal.valueOf(value)).toByteArray(StandardCharsets.UTF_8)
            is Float -> canonicalNumber(BigDecimal.valueOf(value.toDouble())).toByteArray(StandardCharsets.UTF_8)
            is ByteArray -> value
            is List<*> -> canonicalList(value)
            is Map<*, *> -> canonicalMap(value)
            is CanonicalObject -> canonicalObject(value)
            else -> throw CanonicalizationException(
                "Тип ${value::class.qualifiedName} не приводится к каноническому виду. " +
                    "Подписываться над произвольным объектом нельзя: правило «что подписано» " +
                    "должно быть определено точно",
            )
        }

    /**
     * Заключает канонизированную строку в кавычки JSON.
     *
     * @param value канонизированная строка без кавычек-обрамления
     * @return строка в виде элемента JSON
     */
    fun quoted(value: String): String = "\"" + value + "\""

    /**
     * Канонизирует строку.
     *
     * Строка приводится к виду, пригодному для JSON: перевод строки — только
     * `LF`, BOM выбрасывается, а кавычка, обратный слэш и управляющие символы
     * экранируются. Без экранирования название сцены с кавычкой дало бы файл,
     * который воркер не разберёт, а подпись была бы подписью над текстом,
     * который не является сценарием.
     *
     * @param value исходная строка
     * @return каноническая строка без BOM и кавычек-обрамления
     */
    fun canonicalString(value: String): String {
        val normalized = value.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.none { it == '"' || it == '\\' || it < ' ' }) return normalized
        val out = StringBuilder(normalized.length + 8)
        normalized.forEach { character ->
            when {
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\n' -> out.append("\\n")
                character == '\t' -> out.append("\\t")
                character == '\b' -> out.append("\\b")
                character == '\u000C' -> out.append("\\f")
                character < ' ' -> out.append(String.format("\\u%04x", character.code))
                else -> out.append(character)
            }
        }
        return out.toString()
    }

    /**
     * Канонизирует объект с фиксированным порядком полей.
     *
     * @param object объект с объявленным порядком полей
     * @return канонические байты
     * @throws CanonicalizationException если объявлено поле без значения
     */
    fun canonicalObject(`object`: CanonicalObject): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("{\n".toByteArray(StandardCharsets.UTF_8))
        `object`.fields.forEachIndexed { index, (name, value) ->
            out.write("  ".toByteArray(StandardCharsets.UTF_8))
            out.write(quoted(canonicalString(name)).toByteArray(StandardCharsets.UTF_8))
            out.write(": ".toByteArray(StandardCharsets.UTF_8))
            out.write(canonicalBytes(value))
            if (index != `object`.fields.size - 1) {
                out.write(",".toByteArray(StandardCharsets.UTF_8))
            }
            out.write("\n".toByteArray(StandardCharsets.UTF_8))
        }
        out.write("}".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * Канонизирует список.
     *
     * Порядок элементов сохраняется: он и есть содержание списка.
     *
     * @param values элементы списка
     * @return канонические байты
     */
    private fun canonicalList(values: List<*>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("[".toByteArray(StandardCharsets.UTF_8))
        values.forEachIndexed { index, value ->
            if (index > 0) {
                out.write(", ".toByteArray(StandardCharsets.UTF_8))
            }
            out.write(canonicalBytes(value))
        }
        out.write("]".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * Канонизирует отображение.
     *
     * @param values отображение; ключи приводятся к строкам
     * @return канонические байты
     * @throws CanonicalizationException если в отображении есть ключ не из строки
     */
    private fun canonicalMap(values: Map<*, *>): ByteArray {
        val sorted =
            values.entries
                .map { entry ->
                    val key =
                        entry.key ?: throw CanonicalizationException(
                            "Ключ отображения не может быть пустым",
                        )
                    key.toString() to entry.value
                }.sortedBy { it.first }
        val out = ByteArrayOutputStream()
        out.write("{".toByteArray(StandardCharsets.UTF_8))
        sorted.forEachIndexed { index, (key, value) ->
            if (index > 0) {
                out.write(", ".toByteArray(StandardCharsets.UTF_8))
            }
            out.write(quoted(canonicalString(key)).toByteArray(StandardCharsets.UTF_8))
            out.write(": ".toByteArray(StandardCharsets.UTF_8))
            out.write(canonicalBytes(value))
        }
        out.write("}".toByteArray(StandardCharsets.UTF_8))
        return out.toByteArray()
    }

    /**
     * Приводит число к одному виду: целые без дробной части, дробные — без
     * хвостовых нулей.
     *
     * @param value число
     * @return каноническая запись числа
     */
    fun canonicalNumber(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    /**
     * Считает SHA-256 канонических байтов.
     *
     * Сумма содержимого идёт в сценарий **вместе** с подписью: подпись без
     * суммы нечего проверять, сумма без подписи не защищает (модель данных,
     * инвариант 1 раздела 2.20).
     *
     * @param canonicalBytes канонические байты
     * @return 64 шестнадцатеричных символа в нижнем регистре
     */
    fun checksum(canonicalBytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(canonicalBytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * Объект с объявленным порядком полей.
 *
 * Порядок задаётся при объявлении и не зависит от порядка в карте: иначе
 * канонизация была бы недетерминированной, а значит и подпись недетерминированной.
 *
 * @property fields пары «имя поля — значение» в объявленном порядке
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class CanonicalObject(
    val fields: List<Pair<String, Any?>>,
) {
    init {
        val names = fields.map { it.first }
        require(names.size == names.toSet().size) {
            "В каноническом объекте повторяются имена полей: $names"
        }
    }

    companion object {
        /**
         * Собирает объект из пар в указанном порядке.
         *
         * @param fields пары «имя поля — значение»
         * @return объект с фиксированным порядком полей
         */
        fun of(vararg fields: Pair<String, Any?>): CanonicalObject = CanonicalObject(fields.toList())
    }
}

/**
 * Канонизация невозможна.
 *
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class CanonicalizationException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
