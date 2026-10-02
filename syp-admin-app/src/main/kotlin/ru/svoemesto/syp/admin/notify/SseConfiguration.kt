package ru.svoemesto.syp.admin.notify

import java.time.Duration

/**
 * Настройки развёртывания подсистемы нотификаций.
 *
 * Значения читаются из окружения, а не заданы кодом: их правят при
 * обслуживании, и пересборка образа не должна быть частью правки настройки
 * (constitution VIII.5, значения по умолчанию у методов `@Bean` недопустимы —
 * Spring ищет фабричный метод без аргументов).
 *
 * Значение по умолчанию совпадает со значением в `deploy/.env.example`, и
 * обеих мест они должны совпадать: иначе развёртывание поведёт себя не так,
 * как показывает файл примера.
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
object SseConfiguration {
    /** Имя переменной окружения с интервалом сердцебия. */
    const val ENV_HEARTBEAT_SECONDS: String = "SYP_SSE_HEARTBEAT_SECONDS"

    /** Интервал сердцебия по умолчанию, секунды. */
    const val DEFAULT_HEARTBEAT_SECONDS: Long = 15

    /**
     * Интервал сердцебия из окружения.
     *
     * Недопустимое значение — ноль, отрицательное или не число — молча
     * заменяется значением по умолчанию, а не приводит к отказу развёртывания:
     * сердцебие с интервалом в ноль секунд отправляло бы комментарии без пауз
     * и съедало бы канал.
     *
     * Клиентский таймаут обязан остаться **больше** полученного значения,
     * иначе живой поток успеет признаться мёртвым (ADR-0017).
     *
     * @return интервал сердцебия
     */
    fun heartbeatSeconds(): Long =
        System.getenv(ENV_HEARTBEAT_SECONDS)
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_HEARTBEAT_SECONDS

    /**
     * Интервал сердцебия как длительность.
     *
     * @return интервал сердцебия
     */
    fun heartbeatInterval(): Duration = Duration.ofSeconds(heartbeatSeconds())
}
