#!/bin/bash
# Проверка очереди перед перезапуском контейнера (Hard Gate AGENTS.md).
#
# Правило требует убедиться, что заданий в работе нет, и только потом
# перезапускать. Проверка живёт здесь, а не в памяти оператора: за всё время
# проекта правило проверялось запросом к несуществующему столбцу `status`, он
# падал, а «пустой вывод» читался как «очередь пуста». Столбец называется
# `state`, и пустой результат — это ошибка проверки, а не пустая очередь.
#
# Использование: bash tools/check-queue-empty.sh [контейнер]
# Код возврата: 0 — очередь пуста, 1 — есть задания в работе, 2 — проверка
# невозможна (нет контейнера или базы).
set -u
DB="${1:-syp-db}"
export DOCKER_CONFIG="${DOCKER_CONFIG:-/home/nsa/syp/.docker}"

if ! docker ps --format '{{.Names}}' | grep -qx "$DB"; then
    echo "ПРОВАЛ: контейнер $DB не запущен — состояние очереди неизвестно"
    exit 2
fi

# Сначала сам факт связи: пустой вывод из-за неверного столбца нельзя
# отличить от пустой таблицы, поэтому запрос проверяется на двух этапах.
if ! docker exec "$DB" psql -U syp -d syp -tAc "select 1 from job limit 1" >/dev/null 2>&1; then
    echo "ПРОВАЛ: таблица job недоступна — состояние очереди неизвестно"
    exit 2
fi

active=$(docker exec "$DB" psql -U syp -d syp -tAc \
    "select count(*) from job where state in ('CREATING','WORKING','WAITING')" 2>/dev/null)

if [ -z "$active" ]; then
    echo "ПРОВАЛ: запрос к очереди вернул пусто — это ошибка проверки, а не пустая очередь"
    exit 2
fi

by_state=$(docker exec "$DB" psql -U syp -d syp -tAc \
    "select state, count(*) from job group by state order by state" 2>/dev/null)

if [ "$active" != "0" ]; then
    echo "ПРОВАЛ: в очереди ${active} заданий в работе"
    echo "${by_state}" | sed 's/^/  /'
    exit 1
fi

echo "OK: очередь пуста, заданий в работе нет"
[ -n "$by_state" ] && echo "${by_state}" | sed 's/^/  /'
exit 0
