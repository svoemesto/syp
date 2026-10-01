#!/usr/bin/env python3
"""SYP — guard: изменение кода обязано затронуть living docs.

Hard Gate «Living docs SSoT»: при реализации сущности обновляются
`docs/domains/<домен>/domain.md`, создаётся или правится
`docs/features/<slug>.md` (FR-009), а при новом решении — ADR.

Карта кода хранится в `.ssot-map.yml`: в ней перечислены каталоги кода и
соответствующие им документы живой документации. Проверка сравнивает список
изменённых файлов с картой и требует, чтобы изменение кода сопровождалось
изменением документа.

Использование:
    python3 tools/check-ssot-impact.py                 взять файлы из git status
    python3 tools/check-ssot-impact.py файл ...        взять указанные файлы
    python3 tools/check-ssot-impact.py --init          создать карту по умолчанию

Код возврата: 0 — нарушений нет, 1 — нарушения найдены.
"""
from __future__ import annotations

import pathlib
import subprocess
import sys

try:
    import yaml
except ImportError:  # pragma: no cover - зависимость окружения
    yaml = None

ROOT = pathlib.Path(__file__).resolve().parent.parent
MAP_FILE = ROOT / ".ssot-map.yml"

DEFAULT_MAP = {
    "version": 1,
    "description": (
        "Карта кода на живую документацию проекта SYP. Для каждого каталога "
        "исходников указаны документы, которые обязаны меняться вместе с ним "
        "(Hard Gate «Living docs SSoT», FR-009)."
    ),
    "code_to_docs": {
        "syp-core/src/main/kotlin/ru/svoemesto/syp/core/db": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-core/src/main/kotlin/ru/svoemesto/syp/core/jobs": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-core/src/main/kotlin/ru/svoemesto/syp/core/media": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-core/src/main/kotlin/ru/svoemesto/syp/core/storage": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-core/src/main/kotlin/ru/svoemesto/syp/core/signing": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-core/src/main/kotlin/ru/svoemesto/syp/core/contract": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/catalog": [
            "docs/domains/catalog/domain.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/analysis": [
            "docs/domains/analysis/domain.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/characters": [
            "docs/domains/characters/domain.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/annotation": [
            "docs/domains/annotation/domain.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/selection": [
            "docs/domains/selection/domain.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/jobs": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-public-app/src/main/kotlin/ru/svoemesto/syp/public/config": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-admin-app/src/main/kotlin/ru/svoemesto/syp/admin/config": [
            "docs/features/first-vertical-slice.md",
        ],
        "syp-public-app/src/main/kotlin/ru/svoemesto/syp/public/showcase": [
            "docs/domains/showcase/domain.md",
        ],
        "syp-public-app/src/main/kotlin/ru/svoemesto/syp/public/recipe": [
            "docs/domains/selection/domain.md",
            "docs/features/first-vertical-slice.md",
        ],
        "deploy/syp-db": [
            "docs/features/first-vertical-slice.md",
        ],
    },
}


def load_map() -> dict:
    """Читает карту кода; при её отсутствии создаёт по умолчанию."""
    if not MAP_FILE.is_file():
        if yaml is None:
            print("НЕТ PyYAML и нет карты кода", file=sys.stderr)
            sys.exit(1)
        MAP_FILE.write_text(
            yaml.safe_dump(DEFAULT_MAP, allow_unicode=True, sort_keys=False),
            encoding="utf-8",
        )
        print(f"создана карта кода {MAP_FILE.relative_to(ROOT)}")
    text = MAP_FILE.read_text(encoding="utf-8")
    return yaml.safe_load(text) if yaml is not None else {}


def changed_files_from_git() -> list[str]:
    """Список файлов, изменённых в рабочей копии относительно HEAD."""
    result = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    files: list[str] = []
    for line in result.stdout.splitlines():
        if len(line) < 4:
            continue
        path = line[3:].strip().strip('"')
        if " -> " in path:
            path = path.split(" -> ")[-1]
        files.append(path)
    return files


def main(argv: list[str]) -> int:
    if argv and argv[0] == "--init":
        load_map()
        print("OK: карта кода готова")
        return 0

    mapping = load_map()
    code_to_docs = mapping.get("code_to_docs", {}) if isinstance(mapping, dict) else {}

    files = argv if argv else changed_files_from_git()
    if not files:
        print("OK: изменённых файлов нет")
        return 0

    touched_code: list[str] = []
    touched_docs: list[str] = []

    for path in files:
        relative = path.lstrip("./")
        if relative.startswith("docs/"):
            touched_docs.append(relative)
        if relative.endswith((".kt", ".sql", ".ts", ".vue")):
            touched_code.append(relative)

    if not touched_code:
        print("OK: изменений кода нет, проверять нечего")
        return 0

    problems: list[str] = []
    for path in touched_code:
        expected: list[str] = []
        for code_dir, docs in code_to_docs.items():
            if path.startswith(code_dir + "/"):
                expected.extend(docs)
                break
        if not expected:
            problems.append(f"{path}: каталог не найден в карте кода .ssot-map.yml")
            continue
        for document in expected:
            if document not in touched_docs:
                problems.append(f"{path}: изменение кода без изменения {document}")

    if problems:
        print(f"ПРОВАЛЕНО: нарушений карты кода — {len(problems)}", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print(f"OK: изменения кода ({len(touched_code)}) сопровождаются живыми документами")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
