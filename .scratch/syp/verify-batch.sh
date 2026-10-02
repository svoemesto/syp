#!/bin/bash
# Приёмка порции реализации — лид, по фактам с диска.
# Инструмент лида, не часть проекта: лежит в .scratch и в git не попадает.
#
# Использование: .scratch/syp/verify-batch.sh <каталог-worktree> <метка-порции>

WT="${1:?укажи каталог worktree}"
LABEL="${2:-порция}"
cd "${WT}" || exit 1

pass=0; fail=0
note() { printf '%s\n' "$*"; }
check() { # имя, команда...
  local name="$1"; shift
  if timeout 300 "$@" >/tmp/vb.log 2>&1; then
    note "  OK   ${name}"; pass=$((pass+1))
  else
    note "  ПРОВАЛ  ${name}"; fail=$((fail+1))
    tail -3 /tmp/vb.log | sed 's/^/         /'
  fi
}

note "=== Приёмка: ${LABEL} ==="
note "каталог: ${WT}"
note "ветка:   $(git rev-parse --abbrev-ref HEAD)"
note "коммитов относительно master: $(git rev-list --count origin/master..HEAD)"
note ""
note "--- сборка и стиль ---"
check "компиляция"            env GRADLE_USER_HOME=/home/nsa/syp/.gradle ./gradlew compileKotlin --parallel
check "ktlint"                env GRADLE_USER_HOME=/home/nsa/syp/.gradle ./gradlew ktlintCheck
check "тесты"                 env GRADLE_USER_HOME=/home/nsa/syp/.gradle ./gradlew test
note ""
note "--- документация и покрытие ---"
check "линтер документации"   python3 docs/scripts/lint-docs.py
check "покрытие задачами"     python3 tools/check-tasks-coverage.py
note ""
note "--- гигиена ---"
n=$(git ls-files | grep -icE '\.env$|do\.env$|\.key$|\.pem$|\.p12$|\.pfx$')
if [ "$n" -eq 0 ]; then note "  OK   секретов в индексе нет"; pass=$((pass+1))
else note "  ПРОВАЛ  секретов: $n"; fail=$((fail+1)); fi
j=$(grep -rl 'jakarta\.persistence\|javax\.persistence' --include='*.kt' syp-core syp-admin-app syp-public-app 2>/dev/null | wc -l)
if [ "$j" -eq 0 ]; then note "  OK   JPA не используется"; pass=$((pass+1))
else note "  ПРОВАЛ  JPA в $j файлах"; fail=$((fail+1)); fi
b=$(git status --short | grep -c '^?? .*build/')
if [ "$b" -eq 0 ]; then note "  OK   build/ не попадёт в репозиторий"; pass=$((pass+1))
else note "  ПРОВАЛ  build/ не отслеживается gitignore"; fail=$((fail+1)); fi
note ""
note "--- guards (с переменными окружения, иначе ложные срабатывания) ---"
for g in tools/check-*.sh; do
  [ -f "$g" ] || continue
  case "$g" in
    *gradle-user-home*|*docker-config*) check "$(basename "$g")" env GRADLE_USER_HOME=/home/nsa/syp/.gradle DOCKER_CONFIG=/home/nsa/syp/.docker bash "$g" ;;
    *) check "$(basename "$g")" bash "$g" ;;
  esac
done
note ""
note "=== ИТОГ: прошло ${pass}, провалено ${fail} ==="
[ "$fail" -eq 0 ] || exit 1
