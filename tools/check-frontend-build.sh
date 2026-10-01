#!/usr/bin/env bash
# SYP — guard R-375: фронтенд собирается только из своего каталога.
#
# Имя каталога фронтенда совпадает с именем контейнера (Hard Gate
# Build/Deploy/Containers): syp-admin-web и syp-public-web. В корне
# репозитория файла package.json быть не должно, иначе `npm run` из корня
# создаёт соблазн собрать не то.
#
# Использование:
#   bash tools/check-frontend-build.sh            проверить структуру
#   bash tools/check-frontend-build.sh <каталог>  проверить указанный каталог
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

FRONTENDS=(syp-admin-web syp-public-web)
errors=0

check_dir() {
    local dir="$1"
    if [[ ! -d "${dir}" ]]; then
        printf 'НЕТ: каталог фронтенда %s не создан\n' "${dir}" >&2
        errors=$((errors + 1))
        return
    fi
    if [[ ! -f "${dir}/package.json" ]]; then
        printf 'НЕТ: в %s нет package.json\n' "${dir}" >&2
        errors=$((errors + 1))
        return
    fi
    # Скрипт сборки обязан быть: без него фронтенд нечем собрать.
    if ! grep -q '"build"' "${dir}/package.json"; then
        printf 'НЕТ: в %s/package.json нет скрипта build\n' "${dir}" >&2
        errors=$((errors + 1))
    fi
}

if [[ $# -gt 0 ]]; then
    for argument in "$@"; do
        check_dir "${argument}"
    done
else
    # В корне репозитория package.json быть не должно.
    if [[ -f "package.json" ]]; then
        printf 'НАРУШЕНИЕ R-375: в корне репозитория есть package.json\n' >&2
        printf '  npm run из корня не должен работать: сборка идёт из каталога фронтенда\n' >&2
        errors=$((errors + 1))
    fi
    for dir in "${FRONTENDS[@]}"; do
        check_dir "${dir}"
    done
fi

if [[ ${errors} -ne 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений R-375 — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: сборка фронтенда идёт из каталогов %s\n' "${FRONTENDS[*]}"
exit 0
