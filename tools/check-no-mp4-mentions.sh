#!/usr/bin/env bash
# SYP — guard R-11: домен оперирует сценами и планами, а не файлами mp4.
#
# Смысл запрета: пока решение о схеме «рецепт» не принято, архитектура требовала
# не упоминать `.mp4` там, где речь о сцене или плане (ADR-0009). Сейчас
# видеофайлы на сервере не хранятся, не собираются и не передаются: готовый
# файл остаётся на машине пользователя (FR-088, FR-089e).
#
# Проверяется, что в исходниках серверных модулей нет упоминаний mp4/MKV как
# формата серверного артефакта и нет попыток собрать видео на сервере.
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

# Сборка видео на сервере и хранение видеофайлов запрещены (ADR-0009).
# Пробел перед словом и граница слова не дают сработать на «mp4» внутри
# имён вроди tempDirectory.
if grep -rnE '\.(mp4|mkv)[[:space:]]*"|"(.*)\.(mp4|mkv)"' "${existing_paths[@]}" --include='*.kt'; then
    printf 'НАРУШЕНИЕ R-11: серверный код ссылается на видеофайл mp4/mkv\n' >&2
    errors=$((errors + 1))
fi

if grep -rnE '(ffmpeg|FFmpeg).{0,40}(concat|Concat)' "${existing_paths[@]}" --include='*.kt'; then
    printf 'НАРУШЕНИЕ R-11: серверный код склеивает видео; сборка — на машине пользователя (ADR-0009)\n' >&2
    errors=$((errors + 1))
fi

# Вид задания ASSEMBLE не существует: на сервере нечего собирать.
if grep -rnE '(^|[^A-Za-z0-9_])(ASSEMBLE|ASSEMBLY|CUT_FRAGMENT)([^A-Za-z0-9_]|$)' \
    "${existing_paths[@]}" --include='*.kt'; then
    printf 'НАРУШЕНИЕ R-11: в коде сервера есть вид задания или артефакта сборки (ADR-0009)\n' >&2
    errors=$((errors + 1))
fi

if [[ ${errors} -gt 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений R-11 — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: домен оперирует сценами и планами, не файлами (ADR-0009)\n'
exit 0
