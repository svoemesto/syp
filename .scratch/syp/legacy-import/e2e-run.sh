#!/bin/bash
# Сквозной прогон первого вертикального среза на настоящем файле.
# Инструмент лида, в git не входит: .scratch/syp/legacy-import/e2e-run.sh
#
# Что проверяет: система, поднятая впервые, на настоящих данных архива.
# До этого всё проверялось на синтетике, а бэкенд не поднимался ни разу.
set -u
API=http://127.0.0.1:7911/api
export DOCKER_CONFIG=/home/nsa/syp/.docker

say() { printf '\n=== %s ===\n' "$*"; }
field() { python3 -c "import json,sys;d=json.load(sys.stdin);print(d.get('$1', d))" 2>/dev/null; }

say "0. Исходное состояние"
curl -s "$API/movies" | head -c 120; echo

say "1. Создать сериал (если уже есть — берём существующий)"
SER=$(curl -s "$API/movies" | python3 -c "
import json,sys
try:
    d=json.load(sys.stdin); items=d if isinstance(d,list) else d.get('items',[])
    print(items[0]['id'] if items else '')
except Exception: print('')
")
if [ -z "$SER" ]; then
  R=$(curl -s -X POST "$API/movies" -H 'Content-Type: application/json' \
      -d '{"name":"Игра престолов","sourceRoot":"/sources"}')
  echo "ответ: $(echo "$R" | head -c 200)"
  SER=$(echo "$R" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
else
  echo "сериал уже есть: id=$SER"
fi
[ -n "$SER" ] || { echo "СТОП: не удалось получить сериал"; exit 1; }
echo "serialId=$SER"

say "2. Зарегистрировать серию S01E01"
SR=$(curl -s -X POST "$API/movies/$SER/episodes" -H 'Content-Type: application/json' \
     -d '{"sourcePath":"/sources/GOT.S01/GOT.S01E01.BDRip.1080p.mkv"}')
echo "ответ: $(echo "$SR" | head -c 300)"
SID=$(echo "$SR" | python3 -c "import json,sys;print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
[ -n "$SID" ] || { echo "СТОП: серия не создана"; exit 1; }
echo "seriesId=$SID"

say "3. Параметры серии, определённые сервером"
curl -s "$API/episodes/$SID" | head -c 600; echo

say "4. Сумма исходника (HASH) — сверка с эталоном"
curl -s -X POST "$API/episodes/$SID/checksum" -H 'Content-Type: application/json' -d '{}' | head -c 200; echo
echo "ожидание 60 с (файл 5,6 ГиБ, замер субагента — около 32 с)…"
sleep 60
curl -s "$API/episodes/$SID" | head -c 700; echo

say "5. Анализ структуры"
curl -s -X POST "$API/episodes/$SID/analysis" -H 'Content-Type: application/json' -d '{}' | head -c 200; echo
echo "ожидание 240 с (замер субагента: 152 с на детекцию границ)…"
sleep 240

say "6. Результат"
curl -s "$API/episodes/$SID" | head -c 900; echo

say "7. Сцены и листы превью"
curl -s "$API/episodes/$SID/scenes" 2>&1 | head -c 300; echo
curl -s "$API/episodes/$SID/preview-sheets/count" 2>&1 | head -c 200; echo

say "8. Задания в очереди"
docker exec syp-db psql -U syp -d syp -tAc \
  "select kind,status,left(coalesce(note,''),60) from job order by id desc limit 8" 2>&1 | head -10

say "9. Ошибки в логах бэкенда"
docker logs syp-admin-app --since 10m 2>&1 | grep -cE 'ERROR|Exception' | sed 's/^/строк с ошибками: /'
