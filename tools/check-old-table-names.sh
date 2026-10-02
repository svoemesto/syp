#!/usr/bin/env bash
# Запрет старых имён таблиц в коде.
#
# После переименования 23 таблиц (миграция 16) имена изменились, а прямые SQL
# строки переименование не задевает: переименовывать их приходится вручную, и
# забыть можно где угодно. Так и вышло: `QueueStateReader` обращался к таблице
# `job`, которая после миграции называется `tbl_jobs`, — на живой базе это
# давало 500 при подписке на уведомления.
#
# Проверка ловит именно прямые обращения в SQL: имена таблиц в Kotlin-свойствах
# берутся из констант, и они переименованы вместе с миграцией.
set -uo pipefail

cd "$(dirname "$0")/.." || exit 2

# Старые имена таблиц, переименованных миграцией 16.
STALE='serial|series|scene|shot|job|face|frame|artifact|person|location|season|build_recipe|build_recipe_item|analysis_run|analysis_setting|filter_group|filter_condition|raw_boundary|model_version|model_version_example|source_file_checksum|syp_filter'

# Применённые миграции сохраняют старые имена по правилу append-only, и
# документация их цитирует — это законные упоминания. Тесты тоже: там старые
# имена встречаются как данные, например когда проверяется отклонение вредного
# значения вкладки вида «подписка; DROP TABLE job». Проверка ловит SQL,
# который исполнится, а не строку, которую проверяют.
EXCLUDE=(
  --exclude-dir=.git
  --exclude-dir=build
  --exclude-dir=node_modules
  --exclude-dir=.worktrees
  --exclude-dir=deploy/syp-db
  --exclude-dir=test
  --exclude=*Test.kt
  --exclude-dir=docs
  --exclude-dir=.scratch
  --exclude=*.md
)

found=0
while IFS= read -r hit; do
  file=${hit%%:*}
  rest=${hit#*:}
  line=${rest%%:*}
  text=${rest#*:}
  printf '  ПРОВАЛ %s:%s %s\n' "$file" "$line" "$text"
  found=$((found + 1))
done < <(
  grep -rnE "(FROM|JOIN|INTO|UPDATE|TABLE)[[:space:]]+($STALE)\b" \
    --include='*.kt' --include='*.sql' syp-core syp-admin-app syp-public-app \
    "${EXCLUDE[@]}" 2>/dev/null |
    grep -vE 'CREATE[[:space:]]+TABLE' || true
)

if [ "$found" -gt 0 ]; then
  echo "============================================================"
  echo "ПРОВАЛЕНО: $found — старые имена таблиц в SQL"
  exit 1
fi

echo "============================================================"
echo "ПРОЙДЕНО: старых имён таблиц в SQL нет"
exit 0
