#!/usr/bin/env bash
# Проверяет, что пути контроллеров не дублируют общий префикс.
#
# У контроллера может быть общий префикс `@RequestMapping("/api")`, и тогда
# путь метода должен быть относительным. С абсолютным `/api/...` эндпоинт
# молча уезжает на `/api/api/...`: класс компилируется, тесты на него не
# смотрят, а по объявленному адресу отвечает 405. Так потерян был эндпоинт
# постановки задания поиска лиц: он был, запустить детекцию было нечем.
#
# Правило проверяется статически, потому что проверять надо сам факт
# объявления, а не поведение: до запроса по правильному адресу ошибка
# не видна никому.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
status=0

while IFS= read -r file; do
    prefix="$(grep -oE '@RequestMapping\("[^"]*"\)' "${file}" | head -1 | grep -oE '"[^"]*"' | tr -d '"' || true)"
    if [[ -z "${prefix}" ]]; then
        continue
    fi
    # Ведущий слеш префикса отбрасывается: `/api` и `api` — один адрес.
    bare="${prefix#/}"
    offenders="$(grep -nE "@(Get|Post|Put|Delete|Patch)Mapping\(\"/${bare}" "${file}" || true)"
    if [[ -n "${offenders}" ]]; then
        printf '%s\n' "ПРОВАЛ: ${file#"${ROOT}/"} — общий префикс ${prefix} и путь метода начинается с него же:"
        printf '%s\n' "${offenders}" | sed 's/^/    /'
        printf '%s\n' "    эндпоинт отвечает по адресу ${prefix}${prefix}/… и молча недоступен по объявленному"
        status=1
    fi
done < <(find "${ROOT}/syp-admin-app/src/main" "${ROOT}/syp-public-app/src/main" -name '*Controller.kt' 2>/dev/null)

if [[ ${status} -eq 0 ]]; then
    printf '%s\n' "ОК: пути контроллеров не дублируют общий префикс"
fi
exit "${status}"
