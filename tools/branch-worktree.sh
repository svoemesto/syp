#!/usr/bin/env bash
#
# branch-worktree.sh — подготовка рабочей копии для порции работы одной
# командой.
#
# Зачем: подготовка ветки и рабочей копии делалась вручную, в несколько
# команд, и обрывалась — причём не там, где субагент ждёт обрыва.
#
#   fatal: a branch named '061-minio-partsize' already exists
#   fatal: «.worktrees/065-http-date-tests» уже существует
#
# Обе ошибки приходят уже после того, как номер потрачен, и выглядят как
# «не повезло», а не как «сделай иначе». За сессию это случилось семь раз,
# и каждый раз порция начиналась заново: субагент тратил ход на диагностику
# сообщения, которое сам же и вызвал неверной последовательностью команд.
#
# Что делает: резервирует номер, создаёт ветку, если её нет, создаёт рабочую
# копию, если её нет, и переносит на свежий master. Повторный вызов с тем же
# слагом ничего не ломает — приводит рабочую копию в то же состояние.
#
# Применение:
#   bash tools/branch-worktree.sh мой-порок           # номер подберётся
#   bash tools/branch-worktree.sh мой-порок 061       # номер задан
#
# Печатает путь к рабочей копии — с него и продолжают работу.

set -euo pipefail

# Корень проекта — общее дерево, а не рабочая копия.
#
# Скрипт можно вызвать и из рабочей копии субагента, и тогда `dirname` укажет
# на неё, а рабочие копии положит внутрь рабочей копии. Общий каталог git
# указывает на основное дерево: `.../syp/.git`, а у рабочей копии — на
# `.../syp/.git/worktrees/<имя>`.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && git rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
if [[ -n "${ROOT}" ]]; then
    ROOT="$(dirname "${ROOT}")"
else
    ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
fi

usage() {
    printf '%s\n' "Применение: bash tools/branch-worktree.sh <слаг> [номер]"
    exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage

slug="$1"
number="${2:-}"

if [[ -z "${number}" ]]; then
    number="$(cd "${ROOT}" && bash tools/reserve-branch-number.sh "${slug}" | tail -n 1 | tr -cd '0-9')"
fi

if [[ -z "${number}" ]]; then
    printf 'НЕ УДАЛОСЬ зарезервировать номер для слага «%s».\n' "${slug}" >&2
    exit 1
fi

readonly BRANCH="${number}-${slug}"
readonly WORKTREE="${ROOT}/.worktrees/${BRANCH}"

cd "${ROOT}"

if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
    printf '%s\n' "ветка ${BRANCH} уже есть — переиспользуем"
else
    git branch "${BRANCH}" master
    printf '%s\n' "ветка ${BRANCH} создана"
fi

if [[ -d "${WORKTREE}" ]]; then
    printf '%s\n' "рабочая копия уже есть — переиспользуем"
else
    git worktree add -q "${WORKTREE}" "${BRANCH}"
    printf '%s\n' "рабочая копия создана"
fi

git -C "${WORKTREE}" fetch -q origin
git -C "${WORKTREE}" reset -q --hard origin/master

printf '%s\n' "готово: ${WORKTREE}"
