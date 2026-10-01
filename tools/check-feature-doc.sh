#!/usr/bin/env bash
# SYP — guard R-32 (FR-006, FR-009, constitution VI): документированность.
#
# Требует, чтобы:
#   1. каждый публичный класс, интерфейс, объект и перечисление в модулях SYP
#      имели KDoc-описание непосредственно над объявлением;
#   2. KDoc публичного API ссылался на per-feature документ
#      docs/features/<slug>.md через @see;
#   3. per-feature документ фичи существовал.
#
# Проверка сознательно грубая: она ищет объявление верхнего уровня без
# закрывающего `*/` в нескольких строках над ним. Полноценная проверка всех
# ссылок @see делается отдельно, когда появится полный перечень публичного
# API (поздняя фаза среза).
#
# Использование: bash tools/check-feature-doc.sh [корень]
# Код возврата: 0 — нарушений нет, 1 — нарушения найдены.

set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "${ROOT}" || exit 1

FEATURES_DIR="docs/features"
errors=0

if [[ ! -f "${FEATURES_DIR}/first-vertical-slice.md" ]]; then
    printf 'НАРУШЕНИЕ R-32: нет per-feature документа %s\n' \
        "${FEATURES_DIR}/first-vertical-slice.md" >&2
    errors=$((errors + 1))
fi

SEARCH_PATHS=(syp-core syp-admin-app syp-public-app)
existing_paths=()
for dir in "${SEARCH_PATHS[@]}"; do
    [[ -d "${dir}" ]] && existing_paths+=("${dir}")
done

if [[ ${#existing_paths[@]} -eq 0 ]]; then
    printf 'НАРУШЕНИЕ R-32: нет ни одного модуля SYP в репозитории\n' >&2
    exit 1
fi

# Ссылка @see на per-feature документ должна встречаться: значит правило
# не забыто и действительно применяется к коду.
if ! grep -rqE '@see .*docs/features/' "${existing_paths[@]}" --include='*.kt'; then
    printf 'НАРУШЕНИЕ R-32: ни одной ссылки @see на docs/features в модулях\n' >&2
    errors=$((errors + 1))
fi

# У каждого объявления верхнего уровня должен быть KDoc-блок над ним.
missing=$(python3 - "$@" <<'PY'
import pathlib
import sys

roots = [pathlib.Path(p) for p in ("syp-core", "syp-admin-app", "syp-public-app")]
files = []
for root in roots:
    if root.is_dir():
        files.extend(
            p for p in root.rglob("*.kt")
            if "build" not in p.parts and not p.name.endswith(".g.kt")
        )

declaration = (
    "class ", "interface ", "object ", "enum class ",
    "data class ", "annotation class ", "fun ", "typealias ",
)
missing = []
for path in sorted(files):
    lines = path.read_text(encoding="utf-8").splitlines()
    for index, line in enumerate(lines):
        # Объявление верхнего уровня стоит в нулевом отступе. Свойства
        # конструктора в проверку не входят: они описываются тегами @property
        # в KDoc класса-владельца, отдельный блок над каждым из них был бы
        # дублированием.
        if not line.startswith(declaration):
            continue
        window = lines[max(0, index - 12):index]
        if not any("*/" in w for w in window):
            missing.append(f"{path}:{index + 1}: нет KDoc над «{line[:60]}»")

for item in missing:
    print(item, file=sys.stderr)
print(len(missing))
PY
)
if [[ "${missing}" != "0" ]]; then
    printf 'НАРУШЕНИЕ R-32: объявлений без KDoc — %s (constitution VI.1)\n' "${missing}" >&2
    errors=$((errors + 1))
fi

if [[ ${errors} -gt 0 ]]; then
    printf 'ПРОВАЛЕНО: нарушений R-32 — %d (constitution VI)\n' "${errors}" >&2
    exit 1
fi
printf 'OK: публичные API документированы, per-feature документ есть\n'
exit 0
