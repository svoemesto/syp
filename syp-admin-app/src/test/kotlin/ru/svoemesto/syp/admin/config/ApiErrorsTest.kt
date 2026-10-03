package ru.svoemesto.syp.admin.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.resource.NoResourceFoundException
import ru.svoemesto.syp.core.contract.ErrorBody
import ru.svoemesto.syp.core.contract.ErrorCode

/**
 * Ответы при сбое: что клиент получает при каждом виде отказа.
 *
 * **Что эта проверка доказывает и чего не доказывает.** Она доказывает тело
 * отказа: какой код и какой текст уходят клиенту. Она НЕ доказывает, что
 * Spring отправляет запрос по несуществующему адресу именно в этот обработчик:
 * прямой вызов метода молчал бы и при снятой аннотации — так и вышло, когда
 * проверка появилась, и это отдельный случай «проверка проходит, ничего не
 * проверяя». Сквозную проверку маршрутизации даёт живой запрос в
 * `tools/check-api-addresses.sh` на стенде.
 *
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class ApiErrorsTest {
    /**
     * Адреса, которого нет, — это 404, а не 500.
     *
     * Иначе опечатка в адресе не отличима от падения сервера: и то и другое
     * приходит как «внутренняя ошибка сервера», и чинить начинают не то.
     */
    @Test
    fun `несуществующий адрес отвечает 404, а не 500`() {
        val response = ApiErrors().onAddressNotFound(NoResourceFoundException(HttpMethod.GET, "/api/нет-такого"))

        assertEquals(404, response.statusCode.value())
        val body = response.body!!
        assertEquals(ErrorCode.NOT_FOUND.name, body.code)
        assertTrue(
            body.message.contains("/api/нет-такого"),
            "текст отказа обязан называть адрес: иначе непонятно, куда смотреть. Получено: ${body.message}",
        )
    }

    /**
     * Прочие сбои остаются 500 — правило не должно съедать настоящие поломки.
     */
    @Test
    fun `прочий сбой остаётся 500`() {
        val response = ApiErrors().onUnexpectedFailure(IllegalStateException("сбой"))

        assertEquals(500, response.statusCode.value())
        assertEquals(ErrorCode.INTERNAL_ERROR.name, response.body!!.code)
    }

    /**
     * Отказ домена отвечает своим кодом, а не становится 500.
     */
    @Test
    fun `отказ домена отвечает своим кодом`() {
        val response =
            ApiErrors().onDomainFailure(
                ru.svoemesto.syp.core.contract
                    .DomainException(ErrorCode.NOT_FOUND, "эпизод не заведён"),
            )

        assertEquals(404, response.statusCode.value())
        assertTrue(response.body is ErrorBody)
    }
}
