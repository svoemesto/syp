#!/usr/bin/env bash
# Проверка размера задания для вложенного агента.
#
# Зачем: провайдер обрывает длинные сессии, и после 4,8 КБ задания обрывы пошли
# подряд. Проверка отказывает заранее, а не после потерянной порции работы.
#
# Проверка доказывает ровно одно: файл задания не больше предела. Смысл предела —
# из наблюдения, а не из замера; при новом наблюдении меняется LIMIT_BYTES, а
# не логика.
#
# Использование:
#   bash tools/check-subagent-brief-size.sh [файл ...]
# Без файлов проверяется бриф по умолчанию.
set -uo pipefail

LIMIT_BYTES="${SYP_SUBAGENT_BRIEF_LIMIT_BYTES:-4915}" # 4,8 КБ
DEFAULT_BRIEF="${SYP_SUBAGENT_BRIEF:-$PWD/.scratch/syp/SUBAGENT-BRIEF.md}"

targets=("$@")
if [ ${#targets[@]} -eq 0 ]; then
    targets=("$DEFAULT_BRIEF")
fi

status=0
for path in "${targets[@]}"; do
    if [ ! -f "$path" ]; then
        # Отсутствующий бриф — не нарушение: его может не быть.
        printf 'OK: задания для вложенного агента нет (%s)\n' "$path"
        continue
    fi
    size=$(wc -c < "$path" | tr -d ' ')
    if [ "$size" -gt "$LIMIT_BYTES" ]; then
        printf 'ОШИБКА: задание для вложенного агента %s байт, предел %s — после него идут обрывы\n' \
            "$size" "$LIMIT_BYTES"
        printf '  файл: %s\n' "$path"
        printf '  Сократить: правила без длинных цитат, без истории правок, без повторов.\n'
        status=1
    else
        printf 'OK: задание %s байт, предел %s (%s)\n' "$size" "$LIMIT_BYTES" "$path"
    fi
done

exit "$status"
