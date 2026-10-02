package ru.svoemesto.syp.admin.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorBody
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.DbException

/**
 * Превращение доменных отказов в ответы HTTP.
 *
 * Без этого отказ доходил бы до клиента текстом стека и кодом 500, и
 * интерфейс не мог бы отличить «файл эпизода недоступен» от «сервер упал».
 * Форма ответа одна для обоих бэкендов: машинный код, текст на русском и,
 * где есть, перечень проблемных объектов.
 *
 * **Ошибка доступа к базе** (`DbException`) — не то же, что отказ по правилам:
 * первая означает, что запись не состоялась, и она попадает в журнал сервера;
 * вторая — ожидаемый ответ с текстом для оператора (FR-092). Поэтому у них
 * разные коды и разное отношение: текст ошибки базы в ответ клиенту не
 * попадает, он может содержать детали соединения.
 *
 * @see <a href="../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestControllerAdvice
class ApiErrors {
    /**
     * Отвечает на отказ по правилам предметной области.
     *
     * @param failure отказ с кодом и текстом
     * @return тело ошибки с кодом, соответствующим отказу
     */
    @ExceptionHandler(DomainException::class)
    fun onDomainFailure(failure: DomainException): ResponseEntity<ErrorBody> {
        val body = failure.toBody()
        return ResponseEntity.status(body.status).body(body)
    }

    /**
     * Отвечает на ошибку доступа к базе.
     *
     * @param failure ошибка доступа к базе
     * @return тело ошибки с кодом `INTERNAL_ERROR`; подробности уходят в журнал
     */
    @ExceptionHandler(DbException::class)
    fun onDatabaseFailure(failure: DbException): ResponseEntity<ErrorBody> {
        logger.error("Ошибка доступа к базе: ${failure.message}", failure)
        return ResponseEntity
            .status(ErrorCode.INTERNAL_ERROR.httpStatus)
            .body(ErrorBody.of(ErrorCode.INTERNAL_ERROR, "запись не удалась, обратитесь к журналу сервера"))
    }

    /**
     * Отвечает на непредвиденный сбой.
     *
     * @param failure сбой
     * @return тело ошибки с кодом `INTERNAL_ERROR`
     */
    @ExceptionHandler(Exception::class)
    fun onUnexpectedFailure(failure: Exception): ResponseEntity<ErrorBody> {
        logger.error("Непредвиденный сбой при обработке запроса", failure)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorBody.of(ErrorCode.INTERNAL_ERROR, "внутренняя ошибка сервера, подробности в журнале"))
    }

    private companion object {
        /** Журнал сервера: сюда уходит то, чего клиенту знать не нужно. */
        val logger = LoggerFactory.getLogger("ru.svoemesto.syp.admin.config.ApiErrors")
    }
}
