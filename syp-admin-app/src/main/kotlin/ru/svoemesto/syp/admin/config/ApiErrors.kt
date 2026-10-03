package ru.svoemesto.syp.admin.config

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorBody
import ru.svoemesto.syp.core.contract.ErrorCode
import ru.svoemesto.syp.core.db.DbException
import java.sql.SQLException

/** Сколько текста базы возвращаем оператору: причина нужна целиком, но не вся. */
private const val REASON_LIMIT: Int = 300

/** Коды SQL, означающие нарушение целостности: внешний ключ и проверка. */
private val INTEGRITY_STATES = setOf("23503", "23514")

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
     * Отвечает на нарушение целостности со стороны клиента.
     *
     * Отдельный обработчик нужен потому, что коды `23503` (нарушение внешнего
     * ключа) и `23514` (нарушение проверки) — это ошибка запроса, а не поломка:
     * оператор указал несуществующего владельца, и ответ «внутренняя ошибка
     * сервера» отправляет его чинить то, что не сломано. База поднимает их сама,
     * и её коды разбираются здесь, а не молча уезжают в 500.
     *
     * @param failure нарушение целостности
     * @return тело ошибки с кодом `CONFLICT` и текстом базы
     */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun onIntegrityFailure(failure: DataIntegrityViolationException): ResponseEntity<ErrorBody> {
        val reason = failure.mostSpecificCause.message.orEmpty()
        logger.info("Нарушение целостности: $reason")
        return ResponseEntity
            .status(ErrorCode.CONFLICT.httpStatus)
            .body(ErrorBody.of(ErrorCode.CONFLICT, reason.take(REASON_LIMIT)))
    }

    /**
     * Отвечает на нарушение целостности со стороны клиента.
     *
     * Отдельный обработчик нужен потому, что коды `23503` (нарушение внешнего
     * ключа) и `23514` (нарушение проверки) — это ошибка запроса, а не поломка:
     * оператор указал несуществующего владельца, и ответ «внутренняя ошибка
     * сервера» отправляет его чинить то, что не сломано.
     *
     * Ловится именно `SQLException`, а не `DataIntegrityViolationException`:
     * персистентность на сыром JDBC, Spring транзакциями не управляет и коды
     * базы не переводит — исключение приходит из драйвера как есть, и разбирать
     * его надо здесь.
     *
     * @param failure нарушение целостности либо ошибка базы
     * @return тело ошибки с кодом `CONFLICT` либо `INTERNAL_ERROR`
     */
    @ExceptionHandler(SQLException::class)
    fun onIntegrityFailure(failure: SQLException): ResponseEntity<ErrorBody> {
        val state = failure.sqlState
        if (state !in INTEGRITY_STATES) {
            logger.error("Ошибка доступа к базе: ${failure.message}", failure)
            return ResponseEntity
                .status(ErrorCode.INTERNAL_ERROR.httpStatus)
                .body(ErrorBody.of(ErrorCode.INTERNAL_ERROR, "запись не удалась"))
        }
        val reason =
            failure.message
                .orEmpty()
                .substringAfter(": ")
                .take(REASON_LIMIT)
        logger.info("Нарушение целостности ({}): {}", state, reason)
        return ResponseEntity
            .status(ErrorCode.CONFLICT.httpStatus)
            .body(ErrorBody.of(ErrorCode.CONFLICT, reason))
    }

    /**
     * Отвечает на запрос по адресу, которого у API нет.
     *
     * Отдельный обработчик нужен, чтобы опечатка в адресе не выглядела как
     * падение сервера: адреса нет — это 404, а не 500. Иначе проверка живости
     * или оператор, перешедший по старой ссылке, видят «внутренняя ошибка
     * сервера» и начинают чинить то, что сломано вовсе не там.
     *
     * @param failure запрос по несуществующему адресу
     * @return тело ошибки с кодом `NOT_FOUND`
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun onAddressNotFound(failure: NoResourceFoundException): ResponseEntity<ErrorBody> {
        logger.info("Запрос по несуществующему адресу: ${failure.resourcePath}")
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(
                ErrorBody.of(
                    ErrorCode.NOT_FOUND,
                    "по адресу «${failure.resourcePath}» в API ничего нет",
                ),
            )
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
