package ru.svoemesto.syp.core.json

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * Единый разбор и запись JSON в проекте.
 *
 * Существует по конкретной причине, а не для удобства. Разбор и запись в
 * приложении и в тестах делались разными объектами: приложение регистрировало
 * модуль времени, тесты — нет. Из-за этого тесты проходили, а живой ответ с
 * датой отдавал 500: проверка проходила, ничего не проверяя. Разбирать такой
 * случай в каждом тесте заново — значит заменить одну ошибку на её повторение.
 *
 * Модули, которые обязаны быть зарегистрированы здесь:
 * - Kotlin — для разбора и записи классов данных с ненулевыми значениями по
 *   умолчанию;
 * - времени — без него `java.time.Instant`, `OffsetDateTime` и прочие типы
 *   времени не читаются и не пишутся, а ответ превращается в 500.
 *
 * @see docs/adr/ADR-0016-single-json-mapper.md
 */
object Json {
    /**
     * Создаёт разборщик с зарегистрированными модулями.
     *
     * @return готовый к работе разборщик
     */
    fun mapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
}
