package ru.svoemesto.syp.public.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.svoemesto.syp.core.signing.VerificationKey

/**
 * Сборка компонентов публичного бэкенда.
 *
 * Ключ проверки подписи собирается из переменных окружения. Закрытого ключа
 * у публичного бэкенда нет **и не должно быть**: сценарий приходит уже
 * подписанным, а подписывать что-либо самому здесь нечем (ADR-0011,
 * последствие 2).
 *
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@Configuration
class PublicConfiguration {
    /**
     * Собирает ключ проверки подписи из окружения.
     *
     * @return открытый ключ с идентификатором, которым подписываются сценарии
     * @throws IllegalStateException если переменные окружения не заданы: молча
     *   отдавать ключ по умолчанию означало бы проверять сценарии не тем ключом
     */
    @Bean
    fun verificationKey(): VerificationKey =
        VerificationKey.of(
            keyId = PublicPorts.requiredEnv(PublicPorts.ENV_SIGNING_KEY_ID),
            base64PublicKey = PublicPorts.requiredEnv(PublicPorts.ENV_SIGNING_PUBLIC_KEY),
        )
}
