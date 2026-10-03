package ru.svoemesto.syp.admin.properties

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode

/**
 * Эндпоинты произвольных свойств.
 *
 * Свойства одинаковы для всех владельцев, и владелец задаётся парой «вид и
 * номер» прямо в адресе. Отдельные адреса на каждую сущность здесь не нужны:
 * механизм один и тот же, а размножать его — значит размножить и поломки.
 *
 * Владелец указывается видом, а не именем класса: строкой связь рвётся молча при
 * переносе кода, и миграция 24 это показала.
 *
 * @property store хранилище свойств
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class PropertyController(
    private val store: PropertyStore,
) {
    /**
     * Отдаёт свойства владельца.
     *
     * @param kind вид владельца: проект, видеофайл, дорожка, план, сцена, персона
     * @param ownerId номер владельца
     * @return свойства по порядку показа
     * @throws DomainException с кодом `BAD_REQUEST`, если вид неизвестен
     */
    @GetMapping("/api/properties/{kind}/{ownerId}")
    fun list(
        @PathVariable kind: String,
        @PathVariable ownerId: Long,
    ): List<PropertyView> = kindOf(kind).let { resolved -> store.list(resolved, ownerId).map { it.toView() } }

    /**
     * Записывает свойство владельца.
     *
     * Повторная запись с тем же ключом заменяет значение: оператор вводит ключ
     * заново при опечатке в значении, и второй строки с тем же ключом быть не
     * должно.
     *
     * @param kind вид владельца
     * @param ownerId номер владельца
     * @param request ключ и значение
     * @return записанное свойство
     * @throws DomainException с кодом `BAD_REQUEST`, если вид неизвестен или ключ пуст
     * @throws ru.svoemesto.syp.core.db.DbException если владельца не существует
     */
    @PostMapping("/api/properties/{kind}/{ownerId}")
    fun put(
        @PathVariable kind: String,
        @PathVariable ownerId: Long,
        @RequestBody request: PropertyRequest,
    ): PropertyView {
        val key = request.key.trim()
        if (key.isEmpty()) {
            throw DomainException(ErrorCode.BAD_REQUEST, "ключ свойства не может быть пустым")
        }
        val stored = store.put(kindOf(kind), ownerId, key, request.value)
        return PropertyView(id = stored, key = key, value = request.value, ordinal = 0)
    }

    /**
     * Удаляет свойство владельца.
     *
     * @param kind вид владельца
     * @param ownerId номер владельца
     * @param key ключ
     * @return `204`, если удалено
     * @throws DomainException с кодом `BAD_REQUEST`, если вид неизвестен
     */
    @DeleteMapping("/api/properties/{kind}/{ownerId}")
    fun delete(
        @PathVariable kind: String,
        @PathVariable ownerId: Long,
        @RequestParam key: String,
    ): org.springframework.http.ResponseEntity<Void> {
        store.delete(kindOf(kind), ownerId, key)
        return org.springframework.http.ResponseEntity
            .noContent()
            .build()
    }

    /**
     * Разбирает вид владельца, объясняя отказ своими словами.
     *
     * @param kind значение из адреса
     * @return вид владельца
     * @throws DomainException с кодом `BAD_REQUEST`, если вида нет
     */
    private fun kindOf(kind: String): PropertyOwnerKind =
        PropertyOwnerKind.entries.firstOrNull { it.value == kind.uppercase() }
            ?: throw DomainException(
                ErrorCode.BAD_REQUEST,
                "вид владельца свойства «$kind» неизвестен: допустимы " +
                    PropertyOwnerKind.entries.joinToString(", ") { it.value },
            )
}

/**
 * Запрос «записать свойство».
 *
 * @property key ключ; вводит оператор
 * @property value значение; вводит оператор
 */
data class PropertyRequest(
    val key: String,
    val value: String,
)

/**
 * Свойство в ответе.
 *
 * @property id идентификатор записи
 * @property key ключ
 * @property value значение
 * @property ordinal порядок показа
 */
data class PropertyView(
    val id: Long,
    val key: String,
    val value: String,
    val ordinal: Int,
)

/** Свойство из хранения в ответ: те же поля, без внутренних имён. */
private fun StoredProperty.toView(): PropertyView = PropertyView(id = id, key = key, value = value, ordinal = ordinal)
