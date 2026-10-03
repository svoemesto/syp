#!/usr/bin/env bash
# SYP — guard: изоляция рабочих копий субагентов (constitution VII.3).
#
# Параллельные задачи ведутся в отдельных рабочих копиях `git worktree`.
# Два субагента в одном каталоге кончаются часами на переносе чужих изменений
# (прецедент Karaoke Pass 379).
#
# Каталог рабочей копии узнаётся по файлу `.git`: в основной копии это каталог,
# в рабочей копии — файл со строкой `gitdir: ...`.
#
# Использование:
#   bash tools/check-subagent-isolation.sh                 проверить текущее дерево
#   bash tools/check-subagent-isolation.sh <каталог>...    проверить указанные
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

errors=0

check_tree() {
    local dir="$1"
    local marker="${dir}/.git"

    if [[ ! -e "${marker}" ]]; then
        printf 'НЕ РЕПОЗИТОРИЙ: %s\n' "${dir}" >&2
        errors=$((errors + 1))
        return
    fi

    if [[ -d "${marker}" ]]; then
        # Основная копия. Правило VII.3 запрещает вести в ней параллельные
        # задачи, а не запрещает её существование: основная копия — место
        # основной работы, и требовать от неё чужого нельзя.
        #
        # Требуется одно: основная копия не занята порцией. Порция — это
        # ветка с номером, та же нумерация, что и у рабочих копий.
        local main_branch
        main_branch="$(git -C "${dir}" branch --show-current 2>/dev/null || echo '')"
        if [[ "${main_branch}" =~ ^[0-9]+- ]]; then
            printf 'НАРУШЕНИЕ VII.3: %s — основная копия занята порцией (%s)\n' \
                "${dir}" "${main_branch}" >&2
            printf '  Параллельные задачи ведутся в отдельных рабочих копиях.\n' >&2
            errors=$((errors + 1))
        fi
        return
    fi

    # Рабочая копия: имя ветки обязано начинаться с номера NNN.
    local branch
    branch="$(git -C "${dir}" branch --show-current 2>/dev/null || echo '')"
    if [[ -z "${branch}" ]]; then
        printf 'НЕ ОПРЕДЕЛЕНА ВЕТКА: %s\n' "${dir}" >&2
        errors=$((errors + 1))
        return
    fi
    if [[ ! "${branch}" =~ ^[0-9]{3}-[a-z0-9-]+$ ]]; then
        printf 'НАРУШЕНИЕ VII.5: ветка "%s" не соответствует имени NNN-<slug>\n' "${branch}" >&2
        errors=$((errors + 1))
    fi
}

if [[ $# -gt 0 ]]; then
    for argument in "$@"; do
        check_tree "${argument}"
    done
else
    # Основная копия и все рабочие копии проекта.
    #
    # Раньше без аргументов проверялся только корень: пятьдесят рабочих копий
    # не смотрелись вовсе, а вывод был «ОК». Проверка, которая не проверяет,
    # хуже отсутствующей — она уверяет, что всё в порядке, не проверив ничего.
    check_tree "${ROOT}"
    while IFS= read -r dir; do
        [[ -z "${dir}" ]] && continue
        check_tree "${dir}"
    done < <(find "${ROOT}/.worktrees" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort)
fi

if [[ ${errors} -ne 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений изоляции — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: работа ведётся в отдельной рабочей копии с веткой NNN-<slug>\n'
exit 0
