package ru.svoemesto.syp.public.recipe

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import ru.svoemesto.syp.core.signing.VerificationKey
import ru.svoemesto.syp.core.signing.VerificationKeyResponse

/**
 * Отдача открытого ключа проверки подписи сценария.
 *
 * Воркер на машине пользователя получает открытый ключ отсюда и проверяет им
 * сценарий **до** разбора и **до** нарезки (FR-089c). Закрытого ключа у
 * публичного бэкенда нет: он живёт только в контейнере админки (ADR-0011).
 * Отсутствие закрытого ключа обеспечено **построением**: класс ответа
 * [VerificationKeyResponse] содержит только идентификатор, имя алгоритма,
 * открытый ключ и момент начала его действия, поэтому отдать закрытую
 * половину здесь нечем, даже по ошибке.
 *
 * Ключ отдаётся **в виде PEM**: проверка на машине пользователя выполняется
 * командой `openssl`, и `openssl pkeyutl` читает файл открытого ключа именно
 * в этом виде. Отдав голый base64, пришлось бы собирать файл на стороне
 * пользователя — то есть отдавать не готовый ключ, а заготовку ключа.
 *
 * Ключ приходит **с идентификатором**: смена ключа даёт новый идентификатор, а
 * сценарии, подписанные прежним ключом, остаются проверяемыми по нему.
 * Именно поэтому идентификатор — часть контракта, а не служебная надпись
 * (FR-089c, T028).
 *
 * @property verificationKey ключ проверки, собираемый из конфигурации
 * @see <a href="../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
@RestController
class VerificationKeyEndpoint(
    private val verificationKey: VerificationKey,
) {
    /**
     * Отдаёт открытый ключ проверки подписи.
     *
     * Ответ содержит идентификатор ключа, имя алгоритма, сам открытый ключ в
     * виде PEM и момент, с которого ключ считается доверенным. Ни закрытого
     * ключа, ни подписи, ни способа их получить здесь нет по построению
     * (ADR-0011).
     *
     * @return описание открытого ключа проверки
     */
    @GetMapping("/api/recipes/verification-key")
    fun verificationKey(): VerificationKeyResponse = verificationKey.toResponse()
}
