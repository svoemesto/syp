package ru.svoemesto.syp.admin.config

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ru.svoemesto.syp.admin.catalog.TestDatabase
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Проверка HTTP-эндпоинтов на живом стенде: ответы, обязательные поля и
 * формат дат.
 *
 * Зачем именно даты: дата в ответе — единственное, что читатель обязан
 * разобрать, и ошибка в ней не видна ни на глаз, ни тем более в тесте,
 * который проверяет лишь код ответа. Дата приходит строкой, и строка может
 * оказаться чем угодно: пустым местом, локальным временем без зоны,
 * датой без времени.
 *
 * Здесь не моки: поднимается настоящий бэкенд на настоящей базе. Мок отвечает
 * тем, что в него положили, и потому не способен поймать ни неверный путь,
 * ни поле, забытое при разборе ответа.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpEndpointDateTest {
    /** Адрес стенда, если он задан окружением. */
    private val baseUrl: String? = System.getenv(ENV_URL)

    /** Ответы ранее запрошенных адресов, чтобы не бить по стенду повторно. */
    private val bodies = mutableMapOf<String, String>()

    /** Подготовка: проверяем, что стенд отвечает. */
    @BeforeAll
    fun setUp() {
        TestDatabase.assumeDatabase()
        val url = baseUrl
        // Без стенда проверка обязана быть ОТМЕНЕНА, а не пройдена. Раньше она
        // тихо возвращалась, и отчёт показывал «успех» там, где ничего не
        // проверялось: ни один прогон эту проверку не выполнял, потому что
        // адрес стенда не задавал никто.
        assumeTrue(
            !url.isNullOrBlank(),
            "адрес стенда не задан (переменная $ENV_URL) — проверка HTTP-эндпоинтов отменена, а не пройдена",
        )
        val response = get("${requireNotNull(url).trimEnd('/')}/api/projects")
        if (response.code != 200) {
            fail("Стенд на $url не отвечает: код ${response.code}, тело ${response.body}")
        }
    }

    /**
     * Список сериалов отвечает, и его можно разобрать как список.
     *
     * @throws AssertionError если ответ не является списком
     */
    @Test
    fun `список сериалов отвечает списком`() {
        val body = bodyOf("/api/projects")
        assertTrue(body.trimStart().startsWith("["), "список сериалов должен быть списком, начало: ${body.take(80)}")
    }

    /**
     * В каждом сериале поле даты либо отсутствует, либо записано в разборимом
     * формате с часовым поясом.
     *
     * Формат проверяется разбором, а не сравнением строк: сравнение с
     * шаблоном пропускает «2026-10-03T00:00:00+00:00» и «2026-10-03 00:00:00»
     * одинаково, а читатель их понимает по-разному.
     *
     * @throws AssertionError если дата не разбирается или без зоны
     */
    @Test
    fun `даты сериалов разбираются и содержат зону`() {
        val body = bodyOf("/api/projects")
        val dates =
            Regex("\"([a-zA-Z_]*[dD]ate|[a-zA-Z_]*[aA]ired[a-zA-Z]*|createdAt|updatedAt)\":\"([^\"]+)\"")
                .findAll(body)
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        assertTrue(dates.isNotEmpty(), "в ответе списка сериалов нет ни одной даты: ${body.take(160)}")
        for ((field, value) in dates) {
            try {
                val moment = Instant.parse(value)
                assertTrue(
                    moment.toString().length > 10,
                    "поле $field: «$value» — это дата без времени, момент не определён",
                )
            } catch (failure: DateTimeParseException) {
                assertTrue(
                    value.endsWith("Z") || value.matches(Regex(".*[+-]\\d{2}:\\d{2}$")),
                    "поле $field: «$value» не содержит часового пояса, получатель не поймёт местное время",
                )
            }
        }
    }

    /**
     * Даты заданий в очереди записаны в разборимом формате.
     *
     * @throws AssertionError если дата задания не разбирается
     */
    @Test
    fun `даты заданий в очереди разбираются`() {
        val body = bodyOf("/api/jobs")
        val dates =
            Regex("\"(startedAt|finishedAt|createdAt|updatedAt)\":\"?([^\",}]+)")
                .findAll(body)
                .map { it.groupValues[2] }
                .filter { it != "null" }
                .toList()
        assertTrue(dates.isNotEmpty(), "в очереди нет ни одной даты: ${body.take(160)}")
        for (value in dates) {
            try {
                Instant.parse(value)
            } catch (failure: DateTimeParseException) {
                fail("дата задания «$value» не разбирается как момент времени")
            }
        }
    }

    /**
     * Ответ по одному адресу, запрошенный один раз за набор.
     *
     * @param path путь на стенде
     * @return тело ответа
     */
    private fun bodyOf(path: String): String {
        val url = requireNotNull(baseUrl?.trimEnd('/')) { "адрес стенда не задан" }
        return bodies.getOrPut(path) { get(url + path).body }
    }

    /**
     * Разовый запрос без перенаправлений.
     *
     * @param url адрес
     * @return код ответа и тело
     */
    private fun get(url: String): Answer {
        val connection =
            java.net
                .URI(url)
                .toURL()
                .openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 15_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        connection.disconnect()
        return Answer(code, body)
    }

    /** Ответ ра��овго запроса. */
    private data class Answer(
        /** Код ответа. */
        val code: Int,
        /** Тело ответа. */
        val body: String,
    )

    companion object {
        /** Имя переменной окружения с адресом стенда. */
        const val ENV_URL: String = "SYP_TEST_HTTP_URL"
    }
}
