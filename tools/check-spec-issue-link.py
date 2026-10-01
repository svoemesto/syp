#!/usr/bin/env python3
"""SYP — guard: в спецификации указан номер задачи трекера.

Constitution IX.1 и Hard Gate «Трекер: OpenProject»: слой задач и слой
решений живут в трекере проекта `syp` (id 4), а не в локальных файлах.
Спека обязана называть задачу, по которой ведётся работа: без номера
невозможно ни отчитаться, ни закрыть задачу.

Проверяется, что в `spec.md` каждой фичи есть номер задачи вида `#<NNN>`
и что рядом упомянут проект `syp`.

Использование:
    python3 tools/check-spec-issue-link.py [каталог_фичи ...]

Код возврата: 0 — нарушений нет, 1 — нарушения найдены.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# Номер задачи трекера: решётка и не менее трёх цифр.
ISSUE = re.compile(r"#(\d{3,})")
# Указание проекта трекера рядом с номером задачи.
PROJECT_HINT = re.compile(r"OpenProject|\bsyp\b", re.IGNORECASE)


def check_feature(feature_dir: pathlib.Path) -> list[str]:
    """Возвращает список нарушений по каталогу фичи."""
    spec = feature_dir / "spec.md"
    if not spec.is_file():
        return [f"{feature_dir}: нет spec.md"]

    text = spec.read_text(encoding="utf-8")
    numbers = sorted({int(value) for value in ISSUE.findall(text)})

    problems: list[str] = []
    if not numbers:
        problems.append(f"{spec}: не указан номер задачи трекера вида #<NNN>")
        return problems

    # Указан проект: без этого номер может относиться к чужому трекеру.
    if not PROJECT_HINT.search(text):
        problems.append(f"{spec}: номер задачи есть, но не указан проект трекера")

    # Первое вхождение номера должно быть в шапке документа, а не в середине
    # текста: номер задачи — это вводная, а не сноска к требованию.
    first_position = text.find(ISSUE.search(text).group(0))
    head = text[:first_position]
    if "## " in head:
        problems.append(f"{spec}: номер задачи {ISSUE.search(text).group(0)} "
                        "найден не в шапке документа")

    return problems


def main(argv: list[str]) -> int:
    targets = [pathlib.Path(a) for a in argv] if argv else sorted(
        p for p in (ROOT / "specs").iterdir() if p.is_dir()
    )
    if not targets:
        print("НЕТ: каталог specs пуст, проверять нечего", file=sys.stderr)
        return 1

    problems: list[str] = []
    for feature in targets:
        problems.extend(check_feature(feature))

    if problems:
        print(f"ПРОВАЛЕНО: нарушений связи с трекером — {len(problems)}", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    print(f"OK: во всех {len(targets)} спецификациях указан номер задачи трекера")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
