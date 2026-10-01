#!/usr/bin/env bash
# SYP — pre-commit hook.
#
# Что проверяется перед каждым коммитом:
#   1. нет секретов в индексируемых файлах (constitution VIII.3);
#   2. нет библиотек отображения объектов (constitution III, R-07);
#   3. домен не оперирует видеофайлами (ADR-0009, R-11);
#   4. публичные API документированы (constitution VI.1, R-32);
#   5. контейнеры управляются только через deploy/do.sh (R-374);
#   6. оба фронтенда собираются из своих каталогов (R-375);
#   7. у публичного бэкенда нет исполнителя заданий (FR-085);
#   8. документация проходит линтер;
#   9. спецификация связана с задачей трекера (constitution IX.1).
#
# Проверки 2–7 пропускаются, если в коммите нет исходников: коммит
# документации не должен падать из-за кода, который не менялся.
#
# Установка: .git/hooks/pre-commit, либо core.hooksPath в настройках репозитория.

set -uo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "${ROOT}" || exit 1

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/nsa/syp/.gradle}"
export DOCKER_CONFIG="${DOCKER_CONFIG:-/home/nsa/syp/.docker}"

errors=0
fail() {
    printf 'ПРОВАЛЕНО: %s\n' "$1" >&2
    errors=$((errors + 1))
}

staged="$(git diff --cached --name-only --diff-filter=ACMR)"
if [[ -z "${staged}" ]]; then
    printf 'нечего коммитить: индекс пуст\n' >&2
    exit 1
fi

# 1. Секреты. Проверяются и индекс, и дерево: файл мог быть удалён из
#    индекса, но остаться в истории ранее (constitution VIII.1).
leaks="$(git ls-files | grep -iE '\.env$|do\.env$|\.key$|\.pem$|\.p12$|\.pfx$' || true)"
if [[ -n "${leaks}" ]]; then
    fail "в репозитории отслеживаются файлы, похожие на секреты:"
    printf '%s\n' "${leaks}" >&2
fi

if echo "${staged}" | grep -qiE '\.env$|do\.env$|\.key$|\.pem$|\.p12$|\.pfx$'; then
    fail "в коммит попадает файл, похожий на секрет"
fi

if echo "${staged}" | grep -qiE 'BEGIN [A-Z ]*PRIVATE KEY'; then
    fail "в коммит попадает закрытый ключ"
fi

# Исходники в этом коммите?
has_code=0
if echo "${staged}" | grep -qE '\.(kt|gradle\.kts)$'; then
    has_code=1
fi

if [[ ${has_code} -eq 1 ]]; then
    bash tools/check-no-jpa-imports.sh || fail "R-07: отображение объектов запрещено"
    bash tools/check-no-mp4-mentions.sh || fail "R-11: домен оперирует сценами, не файлами"
    bash tools/check-feature-doc.sh || fail "R-32: публичные API не документированы"
    bash tools/check-no-job-worker.sh || fail "FR-085: у публичного бэкенда нет исполнителя заданий"
fi

if echo "${staged}" | grep -qE '\.(sh|yml)$'; then
    bash tools/check-container-restart.sh || fail "R-374: прямой docker restart запрещён"
    bash tools/check-gradle-user-home.sh --scan || fail "R-372: gradle только с GRADLE_USER_HOME"
    bash tools/check-docker-config.sh --scan || fail "R-373: docker только с DOCKER_CONFIG"
fi

if echo "${staged}" | grep -qE '^syp-(admin|public)-web/'; then
    bash tools/check-frontend-build.sh || fail "R-375: сборка фронтенда идёт из его каталога"
fi

# 8. Линтер документации.
if echo "${staged}" | grep -qE '^docs/.*\.md$'; then
    python3 docs/scripts/lint-docs.py || fail "линтер документации"
fi

# 9. Связь спецификации с задачей трекера.
if echo "${staged}" | grep -qE '^specs/.*/spec\.md$'; then
    python3 tools/check-spec-issue-link.py || fail "в спецификации не указан номер задачи трекера"
fi

if [[ ${errors} -ne 0 ]]; then
    printf '\nКоммит отменён: проверок не пройдено — %d\n' "${errors}" >&2
    printf 'Обход не предусмотрен: правила обязательны (constitution VI, VII, VIII).\n' >&2
    exit 1
fi

printf 'pre-commit: все проверки пройдены\n'
exit 0
