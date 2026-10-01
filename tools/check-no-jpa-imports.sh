#!/usr/bin/env bash
# SYP — guard R-07: запрет библиотек отображения объектов.
#
# constitution III: персистентность — только сырой JDBC. Запрещены
# spring-boot-starter-data-jpa, jakarta.persistence, javax.persistence,
# Hibernate и ddl-auto.
#
# Проверяются три вещи:
#   1. сборочные файлы не подключают отображение объектов;
#   2. исходники не импортируют пакеты персистентности;
#   3. конфигурация не включает автогенерацию схемы.
#
# Использование: bash tools/check-no-jpa-imports.sh [корень]
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "${ROOT}" || exit 1

# Куда смотрим: каталоги модулей и корневые сборочные файлы.
SEARCH_PATHS=(syp-core syp-admin-app syp-public-app)
BUILD_FILES=(build.gradle.kts settings.gradle.kts gradle.properties)

errors=0

report() {
    printf 'НАРУШЕНИЕ R-07: %s\n' "$1" >&2
    errors=$((errors + 1))
}

# --- 1. Зависимости и плагины отображения объектов -------------------------
for file in "${BUILD_FILES[@]}"; do
    [[ -f "${file}" ]] || continue
    if grep -nE 'spring-boot-starter-data-jpa|org\.hibernate|io\.exposed|org\.jooq' "${file}"; then
        report "в ${file} подключена библиотека отображения объектов"
    fi
done

for dir in "${SEARCH_PATHS[@]}"; do
    [[ -d "${dir}" ]] || continue
    file="${dir}/build.gradle.kts"
    [[ -f "${file}" ]] || continue
    if grep -nE '^\s*(api|implementation|compileOnly|runtimeOnly)\(".*(data-jpa|hibernate)' "${file}"; then
        report "в ${file} подключена зависимость отображения объектов"
    fi
done

# --- 2. Импорты пакетов персистентности ------------------------------------
existing_paths=()
for dir in "${SEARCH_PATHS[@]}"; do
    [[ -d "${dir}" ]] && existing_paths+=("${dir}")
done

if [[ ${#existing_paths[@]} -gt 0 ]]; then
    if grep -rnE 'import[[:space:]]+(jakarta|javax)\.persistence' "${existing_paths[@]}"; then
        report "в исходниках есть импорт jakarta.persistence или javax.persistence"
    fi
    if grep -rnE 'org\.hibernate\.' "${existing_paths[@]}" --include='*.kt' --include='*.java'; then
        report "в исходниках есть обращение к org.hibernate"
    fi
fi

# --- 3. Автогенерация схемы -------------------------------------------------
if grep -rnE 'ddl-auto|hibernate\.hbm2ddl' "${existing_paths[@]}" --include='*.yml' --include='*.yaml' --include='*.properties' 2>/dev/null; then
    report "в конфигурации включена автогенерация схемы (ddl-auto)"
fi

# --- Итог -------------------------------------------------------------------
if [[ ${errors} -gt 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений R-07 — %d\n' "${errors}" >&2
    exit 1
fi
printf 'OK: библиотеки отображения объектов не используются (constitution III)\n'
exit 0
