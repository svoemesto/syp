#!/usr/bin/env bash
# SYP — guard окружения R-373: Docker только с DOCKER_CONFIG.
#
# В песочнице агента каталог ~/.docker доступен только для чтения, включая
# журнал активности сборки. Сборка образа без явного DOCKER_CONFIG падает.
#
# Использование:
#   bash tools/check-docker-config.sh             проверить значение переменной
#   bash tools/check-docker-config.sh --scan      найти вызовы docker без неё
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

EXPECTED="/home/nsa/syp/.docker"
errors=0

if [[ "${1:-}" == "--scan" ]]; then
    # Проверка по файлам: переменная ставится один раз при инициализации
    # скрипта, а вызовов docker в нём может быть много. Построчная проверка
    # давала бы ложные срабатывания.
    while IFS= read -r file; do
        case "${file}" in
            ./tools/check-docker-config.sh|./tools/check-container-restart.sh)
                continue
                ;;
        esac
        if ! grep -q 'DOCKER_CONFIG=' "${file}"; then
            printf 'НАРУШЕНИЕ R-373: %s вызывает docker, но не задаёт DOCKER_CONFIG=%s\n' \
                "${file}" "${EXPECTED}" >&2
            errors=1
        fi
    done < <(grep -rlE '(^|[^_[:alnum:]])docker (build|compose|pull|buildx)' \
        --include='*.sh' --include='*.yml' . 2>/dev/null)
else
    if [[ -z "${DOCKER_CONFIG:-}" ]]; then
        printf 'НАРУШЕНИЕ R-373: DOCKER_CONFIG не задан, ожидается %s\n' "${EXPECTED}" >&2
        errors=1
    elif [[ "${DOCKER_CONFIG}" != "${EXPECTED}" ]]; then
        printf 'НАРУШЕНИЕ R-373: DOCKER_CONFIG=%s, ожидается %s\n' \
            "${DOCKER_CONFIG}" "${EXPECTED}" >&2
        errors=1
    fi
fi

if [[ ${errors} -ne 0 ]]; then
    exit 1
fi
printf 'OK: DOCKER_CONFIG=%s\n' "${DOCKER_CONFIG:-проверен скриптом}"
exit 0
