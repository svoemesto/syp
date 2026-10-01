#!/usr/bin/env bash
# SYP — guard FR-085: у публичного бэкенда нет исполнителя заданий.
#
# Разделение видов заданий между бэкендами делает нарушение структурно
# невозможным, а не запрещённым соглашением: у админки есть воркер, который
# берёт ANALYZE, FACES, TRAIN, HASH, а у публичной части нет ни класса
# воркера, ни зависимости очереди заданий (research.md Т-20, ADR-0009).
#
# Использование:
#   bash tools/check-no-job-worker.sh            проверить модуль публичной части
#   bash tools/check-no-job-worker.sh <каталог>  проверить указанный каталог
#
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

errors=0

check_public_module() {
    local dir="$1"
    if [[ ! -d "${dir}" ]]; then
        printf 'НЕТ: каталог %s не создан\n' "${dir}" >&2
        errors=$((errors + 1))
        return
    fi

    # Никакого класса исполнителя заданий.
    if grep -rnE 'class .*(JobWorker|Worker)|@Scheduled|ExecutorService' "${dir}" --include='*.kt' 2>/dev/null; then
        printf 'НАРУШЕНИЕ FR-085: в %s найден исполнитель заданий\n' "${dir}" >&2
        errors=$((errors + 1))
    fi

    # Никакой зависимости от очереди заданий.
    local build_file="${dir}/build.gradle.kts"
    if [[ -f "${build_file}" ]] && \
       grep -nE 'starter-(amqp|activemq)|quartz|spring-batch' "${build_file}"; then
        printf 'НАРУШЕНИЕ FR-085: в %s подключена зависимость исполнителя заданий\n' "${dir}" >&2
        errors=$((errors + 1))
    fi

    # Никаких видов заданий в коде публичной части.
    if grep -rnE '"(ANALYZE|FACES|TRAIN|HASH)"' "${dir}" --include='*.kt' 2>/dev/null; then
        printf 'НАРУШЕНИЕ FR-085: в %s упомянуты виды заданий\n' "${dir}" >&2
        errors=$((errors + 1))
    fi
}

if [[ $# -gt 0 ]]; then
    for argument in "$@"; do
        check_public_module "${argument}"
    done
else
    check_public_module "syp-public-app"
fi

if [[ ${errors} -ne 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений FR-085 — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: у публичного бэкенда нет исполнителя заданий (FR-085, research.md Т-20)\n'
exit 0
