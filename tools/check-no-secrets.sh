#!/usr/bin/env bash
# SYP — guard constitution VIII: секреты не коммитятся.
#
# Проверяются две вещи:
#   1. в индексируемых файлах нет ничего, похожего на файл с секретами;
#   2. в тексте исходников нет закрытого ключа подписи и типовых
#      присваиваний паролей литералами.
#
# Прецедент Karaoke (2026-08-03): `deploy/.env` с паролями Postgres, MinIO
# и Docker Hub протёк в публичный репозиторий и оставался там три года.
# `.gitignore` для уже отслеживаемых файлов не помогает — отслеживание
# снимается вручную.
#
# Использование:
#   bash tools/check-no-secrets.sh            проверить дерево и индекс
#   bash tools/check-no-secrets.sh <файл>...  проверить указанные файлы
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

errors=0

# Маски файлов, которые никогда не отслеживаются (constitution VIII.2).
NAME_PATTERN='\.env$|do\.env$|\.key$|\.pem$|\.p12$|\.pfx$|\.p8$'

# Признаки закрытого ключа в тексте.
CONTENT_PATTERN='BEGIN [A-Z ]*PRIVATE KEY'

# Присваивание пароля литералом: password = "..." и тому подобное.
ASSIGNMENT_PATTERN='(?i)(password|secret|private_?key|token)[[:space:]]*[:=][[:space:]]*"[^"]{6,}"'

check_text() {
    local file="$1"
    if grep -nE "$CONTENT_PATTERN" "${file}" >/dev/null 2>&1; then
        printf 'НАРУШЕНИЕ VIII.5: %s содержит закрытый ключ\n' "${file}" >&2
        grep -nE "$CONTENT_PATTERN" "${file}" | head -3 >&2
        errors=$((errors + 1))
    fi
    if grep -nE "$ASSIGNMENT_PATTERN" "${file}" >/dev/null 2>&1; then
        printf 'НАРУШЕНИЕ VIII.5: %s содержит присваивание секрета литералом\n' "${file}" >&2
        grep -nE "$ASSIGNMENT_PATTERN" "${file}" | head -3 >&2
        errors=$((errors + 1))
    fi
}

if [[ $# -gt 0 ]]; then
    for argument in "$@"; do
        [[ -f "${argument}" ]] || continue
        check_text "${argument}"
    done
else
    # 1. Отслеживаемые файлы с именем секрета.
    tracked="$(git ls-files | grep -iE "${NAME_PATTERN}" || true)"
    if [[ -n "${tracked}" ]]; then
        printf 'НАРУШЕНИЕ VIII.1: отслеживаются файлы, похожие на секреты:\n' >&2
        printf '%s\n' "${tracked}" >&2
        printf '  Для уже отслеживаемого файла нужен git rm --cached\n' >&2
        errors=$((errors + 1))
    fi

    # 2. Текст всех файлов, которые попадут в коммит.
    while IFS= read -r file; do
        [[ -f "${file}" ]] || continue
        check_text "${file}"
    done < <(git ls-files)
fi

if [[ ${errors} -ne 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений гигиены секретов — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: секретов в индексируемых файлах нет (constitution VIII)\n'
exit 0
