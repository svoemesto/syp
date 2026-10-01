package ru.svoemesto.syp.core.contract

/**
 * Описание одного проблемного объекта.
 *
 * Ошибка почти всегда относится не ко всей системе, а к конкретному объекту:
 * серии, сцене, плану, персонажу. Список таких объектов позволяет показать
 * оператору, что именно не так, а не «запрос отклонён».
 *
 * @property kind вид объекта: серия, сцена, план, персона
 * @property identifier идентификатор объекта в базе
 * @property detail что именно не так с объектом
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ErrorItem(
    val kind: String,
    val identifier: String,
    val detail: String,
)

/**
 * Тело ответа с ошибкой.
 *
 * Форма одинакова для обоих бэкендов: всегда присутствует машинный код,
 * всегда присутствует текст на русском, список проблемных объектов
 * присутствует там, где он есть. Ответ без текста не считается ошибкой,
 * объяснимой человеку, — поэтому текст обязателен (constitution I, FR-092).
 *
 * @property code машинный код ошибки
 * @property message текст ошибки на русском
 * @property items проблемные объекты, если они есть
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
data class ErrorBody(
    val code: String,
    val message: String,
    val items: List<ErrorItem> = emptyList(),
) {
    /** Код ответа HTTP, соответствующий ошибке. */
    val status: Int
        get() =
            ErrorCode.byName(code)?.httpStatus
                ?: ErrorCode.INTERNAL_ERROR.httpStatus

    companion object {
        /**
         * Собирает тело ошибки по её коду.
         *
         * @param code код ошибки
         * @param detail уточнение к тексту по умолчанию: путь, идентификатор,
         *   вывод внешней программы. На русском, без технического жаргона
         * @param items проблемные объекты
         * @return готовое тело ответа
         */
        fun of(
            code: ErrorCode,
            detail: String? = null,
            items: List<ErrorItem> = emptyList(),
        ): ErrorBody =
            ErrorBody(
                code = code.name,
                message =
                    detail?.takeIf { it.isNotBlank() }?.let { "«$it»." }
                        ?: code.message,
                items = items,
            )
    }
}

/**
 * Исключение с кодом ошибки.
 *
 * Доменный код отделяется от инфраструктурной ошибки так же, как [ErrorBody]
 * от пустого успеха: вызывающий знает, каким кодом ответить клиенту.
 *
 * @property code код ошибки
 * @property items проблемные объекты
 * @see <a href="../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class DomainException(
    val code: ErrorCode,
    val detail: String? = null,
    val items: List<ErrorItem> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(detail ?: code.message, cause) {
    /** Тело ответа, соответствующее исключению. */
    fun toBody(): ErrorBody = ErrorBody.of(code, detail, items)
}
