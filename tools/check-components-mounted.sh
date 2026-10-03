#!/usr/bin/env bash
# Проверяет, что каждый компонент действительно подключён.
#
# Компонент можно написать, отладить, покрыть комментарием — и не подключить:
# сборка это не считает ошибкой, линтер тоже, тестов на фронтенде нет. Так
# шапка с прогресс-мером заданий пролежала неподключённой: владелец просил
# «прогрессометр в шапке», порция была написана и слита, а на экране её не
# было. Признак один и тот же — вещи выглядят сделанными, а сделаны они
# только в файле.
#
# Проверка статическая: компонент считается подключённым, если его имя
# импортируется где-то в исходниках, кроме его собственного файла.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ADMIN="${ROOT}/syp-admin-web/src"
PUBLIC="${ROOT}/syp-public-web/src"

status=0

check_tree() {
    local tree="$1"
    local label="$2"
    [[ -d "${tree}" ]] || return 0

    local component
    while IFS= read -r component; do
        local name
        name="$(basename "${component}" .vue)"
        # сам себя компонент не подключает: ищем импорт в остальных файлах
        local uses
        uses="$(
            grep -rlE "import [A-Za-z]*${name} from |import \{[^}]*${name}[^}]*\} from " "${tree}" \
                --include='*.vue' --include='*.ts' 2>/dev/null | grep -v "^${component}$" | wc -l
        )" || true
        if [[ "${uses}" -eq 0 ]]; then
            printf '%s\n' "ПРОВАЛ: ${label} компонент «${name}» нигде не подключён — ${component#"${ROOT}/"}"
            printf '%s\n' "       он написан и виден в коде, но в сборку не попадает"
            status=1
        fi
    done < <(find "${tree}" -name '*.vue' -type f)
}

check_tree "${ADMIN}" "админка"
check_tree "${PUBLIC}" "публичная часть"

if [[ ${status} -eq 0 ]]; then
    printf '%s\n' "ОК: каждый компонент подключён хотя бы в одном месте"
fi
exit "${status}"
