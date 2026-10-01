#!/usr/bin/env bash
# SYP — guard R-11: домен оперирует сценами и планами, а не файлами видео.
#
# Видеофайлы на сервере не хранятся, не собираются и не передаются: готовый
# файл остаётся на машине пользователя (ADR-0009, FR-088, FR-089e). Серверный
# код поэтому не должен ни ссылаться на путь готового видеофайла, ни собирать
# его, ни знать вида задания или артефакта сборки.
#
# Комментарии и KDoc не проверяются: они объясняют, чего быть не должно, и
# потому содержат запрещённые слова. Без исключения guard ловил бы сам себя.
#
# Проверяется только исполняемый код (`src/main`). Тестам положено создавать
# фикстуры с путями к файлам серий: имя файла в фикстуре не делает сервер
# хранилищем видео.
#
# Использование: bash tools/check-no-mp4-mentions.sh [корень]
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "${ROOT}" || exit 1

SEARCH_PATHS=(syp-core syp-admin-app syp-public-app)
errors=0

existing_paths=()
for dir in "${SEARCH_PATHS[@]}"; do
    [[ -d "${dir}" ]] && existing_paths+=("${dir}")
done

if [[ ${#existing_paths[@]} -eq 0 ]]; then
    printf 'OK: модулей для проверки нет\n'
    exit 0
fi

# Читаемые строки: без однострочных и блочных комментариев и KDoc.
readable_code() {
    local dir="$1"
    find "${dir}" -path '*/src/main/kotlin/*' -name '*.kt' -not -path '*/build/*' -print0 \
        | xargs -0 -r perl -0pe 's{/\*(?:[^*]|\*(?!/))*\*/}{}gs; s{//[^\n]*}{}g'
}

for dir in "${existing_paths[@]}"; do
    # Видеофайл как артефакт сервера: путь или имя с расширением видео.
    matches="$(readable_code "${dir}" | grep -nE '\.(mp4|mkv|avi|mov)\b' || true)"
    if [[ -n "${matches}" ]]; then
        printf 'НАРУШЕНИЕ R-11: серверный код ссылается на видеофайл\n' >&2
        printf '%s\n' "${matches}" | head -5 >&2
        errors=$((errors + 1))
    fi

    # Вид задания или артефакта сборки: на сервере ему нечем заниматься.
    matches="$(readable_code "${dir}" \
        | grep -nE '(^|[^A-Za-z0-9_])(ASSEMBLE|ASSEMBLY|CUT_FRAGMENT)([^A-Za-z0-9_]|$)' || true)"
    if [[ -n "${matches}" ]]; then
        printf 'НАРУШЕНИЕ R-11: серверный код знает вид задания или артефакта сборки (ADR-0009)\n' >&2
        printf '%s\n' "${matches}" | head -5 >&2
        errors=$((errors + 1))
    fi

    # Склейка видео на сервере.
    matches="$(readable_code "${dir}" | grep -nE '(ffmpeg|FFmpeg).{0,40}(concat|Concat)' || true)"
    if [[ -n "${matches}" ]]; then
        printf 'НАРУШЕНИЕ R-11: серверный код склеивает видео; сборка — на машине пользователя (ADR-0009)\n' >&2
        printf '%s\n' "${matches}" | head -5 >&2
        errors=$((errors + 1))
    fi
done

if [[ ${errors} -gt 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений R-11 — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: домен оперирует сценами и планами, не файлами видео (ADR-0009)\n'
exit 0
