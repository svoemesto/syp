#!/usr/bin/env bash
# SYP — guard: Knowledge-first pre-flight выполнен (constitution II, MUST #0).
#
# Перед правкой спеки агент обязан:
#   1. прочитать docs/README.md — карту документов L1;
#   2. сделать не менее трёх поисков по docs/ с разными ключами;
#   3. прочитать домен и его компоненты;
#   4. прочитать все ADR.
#
# Проверка идёт по журналу pre-flight в корне репозитория
# (`.knowledge-preflight.log`): каждая выполненная строка протокола
# записывается одной командой. Файл вне системы контроля версий.
#
# Использование:
#   bash tools/check-spec-knowledge-preflight.sh              проверить журнал
#   bash tools/check-spec-knowledge-preflight.sh --record ... записать строку
#   bash tools/check-spec-knowledge-preflight.sh --reset       очистить журнал
#
# Код возврата: 0 — протокол выполнен, 1 — нет.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

LOG=".knowledge-preflight.log"
MIN_SEARCHES=3

case "${1:-}" in
    --reset)
        rm -f "${LOG}"
        printf 'OK: журнал pre-flight очищен\n'
        exit 0
        ;;
    --record)
        shift
        printf '%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$*" >> "${LOG}"
        printf 'записано в %s: %s\n' "${LOG}" "$*"
        exit 0
        ;;
esac

if [[ ! -f "${LOG}" ]]; then
    printf 'ПРОВАЛЕНО: нет журнала Knowledge-first pre-flight (%s)\n' "${LOG}" >&2
    printf '  Шаги протокола: docs/README.md, не менее %d поисков по docs/,\n' "${MIN_SEARCHES}" >&2
    printf '  домен с компонентами, все ADR из docs/adr/ (constitution II.1)\n' >&2
    exit 1
fi

readme_reads=$(grep -cE 'read[[:space:]]+docs/README\.md' "${LOG}" 2>/dev/null || true)
searches=$(grep -cE 'grep[[:space:]].*docs/' "${LOG}" 2>/dev/null || true)
domain_reads=$(grep -cE 'read[[:space:]]+docs/domains/' "${LOG}" 2>/dev/null || true)
adr_reads=$(grep -cE 'read[[:space:]]+docs/adr/' "${LOG}" 2>/dev/null || true)

errors=0
if [[ "${readme_reads}" -lt 1 ]]; then
    printf 'ПРОВАЛЕНО: docs/README.md не прочитан\n' >&2
    errors=$((errors + 1))
fi
if [[ "${searches}" -lt ${MIN_SEARCHES} ]]; then
    printf 'ПРОВАЛЕНО: поисков по docs/ — %d, требуется не менее %d\n' "${searches}" "${MIN_SEARCHES}" >&2
    errors=$((errors + 1))
fi
if [[ "${domain_reads}" -lt 1 ]]; then
    printf 'ПРОВАЛЕНО: домен с компонентами не прочитан\n' >&2
    errors=$((errors + 1))
fi
if [[ "${adr_reads}" -lt 1 ]]; then
    printf 'ПРОВАЛЕНО: ADR из docs/adr/ не прочитаны\n' >&2
    errors=$((errors + 1))
fi

if [[ ${errors} -ne 0 ]]; then
    printf 'ПРОВАЛЕНО: Knowledge-first pre-flight не выполнен (constitution II)\n' >&2
    exit 1
fi

printf 'OK: протокол Knowledge-first выполнен: README %d, поисков %d, домен %d, ADR %d\n' \
    "${readme_reads}" "${searches}" "${domain_reads}" "${adr_reads}"
exit 0
