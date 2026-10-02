#!/bin/bash
# Проверка, что приложение работает целиком: шесть контейнеров стека,
# база, хранилище, ключ подписи, основной путь данных.
#
# Инструмент лида, в git не входит:
#   .scratch/syp/legacy-import/stack-health.sh
set -u
export DOCKER_CONFIG=/home/nsa/syp/.docker
ROOT=/home/nsa/syp
ENV_FILE="$ROOT/deploy/.env"

ok=0; bad=0; warn=0
row() { printf '%-34s %s\n' "$1" "$2"; }
pass() { row "$1" "OK"; ok=$((ok+1)); }
fail() { row "$1" "ПРОВАЛ: $2"; bad=$((bad+1)); }
soft() { row "$1" "ВНИМАНИЕ: $2"; warn=$((warn+1)); }

echo "=== Контейнеры ==="
declare -A EXPECT=(
  [syp-db]="healthy"
  [syp-storage]="healthy"
  [syp-admin-app]="Up"
  [syp-admin-web]="Up"
  [syp-public-app]="Up"
  [syp-public-web]="Up"
)
for c in syp-db syp-storage syp-admin-app syp-admin-web syp-public-app syp-public-web; do
  st=$(docker ps --filter "name=^${c}$" --format '{{.Status}}')
  if [ -z "$st" ]; then fail "$c" "не запущен";
  elif [ "${EXPECT[$c]}" = "healthy" ] && [[ "$st" != *healthy* ]]; then fail "$c" "запущен, но не healthy: $st";
  else pass "$c" "$st"; fi
done

echo
echo "=== База данных ==="
if docker exec syp-db psql -U syp -d syp -tAc "select 1 from serial limit 1" >/dev/null 2>&1; then
  t=$(docker exec syp-db psql -U syp -d syp -tAc "select count(*) from pg_tables where schemaname='public'" 2>/dev/null)
  pass "таблицы применены" "$t шт."
else
  fail "миграции" "таблица serial недоступна"
fi

echo
echo "=== Хранилище ==="
if [ -f "$ENV_FILE" ]; then
  U=$(grep '^SYP_STORAGE_USER=' "$ENV_FILE" | cut -d= -f2-)
  P=$(grep '^SYP_STORAGE_PASSWORD=' "$ENV_FILE" | cut -d= -f2-)
  B=$(grep '^SYP_STORAGE_BUCKET=' "$ENV_FILE" | cut -d= -f2-)
  if docker exec -e MC_HOST_l="http://$U:$P@127.0.0.1:9000" syp-storage mc ls "l/$B" >/dev/null 2>&1; then
    pass "бакет $B" "доступен"
  else
    fail "бакет $B" "недоступен или пароль не совпадает с контейнером"
  fi
else
  soft "хранилище" "нет $ENV_FILE"
fi

echo
echo "=== Эндпоинты ==="
adm_admin=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:7911/api/serials)
[ "$adm_admin" = "200" ] && pass "админка: /api/serials" "200" || fail "админка: /api/serials" "HTTP $adm_admin"
pub_key=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:7913/api/recipes/verification-key)
[ "$pub_key" = "200" ] && pass "публичная: ключ проверки" "200" || fail "публичная: ключ проверки" "HTTP $pub_key"
adm_web=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:7912/)
[ "$adm_web" = "200" ] && pass "админка: веб" "200" || fail "админка: веб" "HTTP $adm_web"
pub_web=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 http://127.0.0.1:7914/)
[ "$pub_web" = "200" ] && pass "публичная: веб" "200" || fail "публичная: веб" "HTTP $pub_web"

echo
echo "=== Очередь ==="
bash "$ROOT/tools/check-queue-empty.sh" >/dev/null 2>&1 && pass "очередь" "пуста" || soft "очередь" "есть задания или состояние неизвестно"

echo
echo "=== ИТОГ: хорошо $ok, провалено $bad, внимание $warn ==="
[ "$bad" -eq 0 ]
