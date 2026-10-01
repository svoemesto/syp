#!/usr/bin/env bash
# SYP — guard окружения R-372: Gradle только с GRADLE_USER_HOME.
#
# В песочнице агента файловая система показывается доступной только для
# чтения, включая каталог кэша Gradle по умолчанию. Сборка без явного
# GRADLE_USER_HOME падает с ошибкой записи.
#
# Использование:
#   bash tools/check-gradle-user-home.sh            проверить значение переменной
#   bash tools/check-gradle-user-home.sh --scan     найти вызовы gradle без неё
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

EXPECTED="/home/nsa/syp/.gradle"
errors=0

if [[ "${1:-}" == "--scan" ]]; then
    # Проверка по файлам, а не по строкам: если файл где-либо задаёт
    # GRADLE_USER_HOME (например, export в начале скрипта), вызовы gradle
    # в нём допустимы. Построчная проверка давала бы ложные срабатывания:
    # переменная ставится на одной строке, вызов — на тридцатой.
    #
    # Исключения: сам guard и документы, в которых правило описано,
    # а не нарушено.
    while IFS= read -r file; do
        case "${file}" in
            ./tools/check-gradle-user-home.sh|./constitution.md|./AGENTS.md|./CLAUDE.md)
                continue
                ;;
        esac
        if ! grep -q 'GRADLE_USER_HOME=' "${file}"; then
            printf 'НАРУШЕНИЕ R-372: %s вызывает gradle, но не задаёт GRADLE_USER_HOME=%s\n' \
                "${file}" "${EXPECTED}" >&2
            errors=1
        fi
    done < <(grep -rlE '^[[:space:]]*[^#[:space:]].*\./gradlew' \
        --include='*.sh' --include='*.yml' . 2>/dev/null)
else
    if [[ -z "${GRADLE_USER_HOME:-}" ]]; then
        printf 'НАРУШЕНИЕ R-372: GRADLE_USER_HOME не задан, ожидается %s\n' "${EXPECTED}" >&2
        errors=1
    elif [[ "${GRADLE_USER_HOME}" != "${EXPECTED}" ]]; then
        printf 'НАРУШЕНИЕ R-372: GRADLE_USER_HOME=%s, ожидается %s\n' \
            "${GRADLE_USER_HOME}" "${EXPECTED}" >&2
        errors=1
    fi
fi

if [[ ${errors} -ne 0 ]]; then
    exit 1
fi
printf 'OK: GRADLE_USER_HOME=%s\n' "${GRADLE_USER_HOME:-проверен скриптом}"
exit 0
