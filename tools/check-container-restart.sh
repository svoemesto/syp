#!/usr/bin/env bash
# SYP — guard R-374: контейнеры только через deploy/do.sh.
#
# Прямой `docker restart` запрещён: он теряет зависимости между контейнерами
# и обходит согласие владельца. Перезапуск выполняется командами
# start_* и restart_* из deploy/do.sh.
#
# Использование:
#   bash tools/check-container-restart.sh            проверить исходники репозитория
#   bash tools/check-container-restart.sh <файл>     проверить указанный файл
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

errors=0

# Файл с текстом нарушения: настоящий вызов команды, а не упоминание
# в комментарии. Строки-комментарии начинаются с решётки.
check_file() {
    local file="$1"
    [[ -f "${file}" ]] || return 0
    if grep -nE '^[[:space:]]*[^#[:space:]]*docker[[:space:]]+restart' "${file}"; then
        printf 'НАРУШЕНИЕ R-374: прямой docker restart в %s\n' "${file}" >&2
        errors=1
    fi
    # Перезапуск допустим только через compose-команду do.sh.
    if grep -nE '^[[:space:]]*[^#[:space:]]*docker[[:space:]]+compose[[:space:]]+restart' "${file}"; then
        printf 'НАРУШЕНИЕ R-374: docker compose restart в %s; используйте stop и up\n' "${file}" >&2
        errors=1
    fi
}

if [[ $# -gt 0 ]]; then
    for argument in "$@"; do
        check_file "${argument}"
    done
else
    while IFS= read -r file; do
        check_file "${file}"
    done < <(find . \
        -name '*.sh' -not -path './.git/*' -not -path '*/build/*' \
        -not -name 'check-container-restart.sh' \
        -not -path './tools/tracker*.sh')
fi

if [[ ${errors} -ne 0 ]]; then
    exit 1
fi
printf 'OK: контейнеры управляются только через deploy/do.sh (R-374)\n'
exit 0
