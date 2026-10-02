#!/usr/bin/env python3
"""Переименование имён в документации по карте владельца (RENAME-MAP.md).

Документация описывает текущее состояние, поэтому в ней должны стоять те же
имена, что и в коде: имена таблиц, имена в JSON и имена переменных пути.

Что намеренно не меняется:
  - сегменты путей /api/serials и /api/series — их в карте нет, а смена
    ломает контракт; переименовываются только имена переменных пути;
  - русские термины «сериал» и «серия» — карта переименовывает сущности
    в коде, язык описания она не трогает;
  - названия файлов документации (components/series-import.md,
    docs/serial/) — это имена разделов, а не сущности.
"""

import re
import sys
from pathlib import Path
import importlib.util

spec = importlib.util.spec_from_file_location("rt", str(Path(__file__).with_name("rename-tool.py")))
rt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rt)

# Имена, которые в документации не переименовываются: пути и названия файлов.
KEEP = [
    ("/api/serials", "\x01a\x01"),
    ("/api/series", "\x01b\x01"),
    ("docs/serial/", "\x01c\x01"),
    ("[serial/]", "\x01d\x01"),
    ("series-import", "\x01e\x01"),
    ("serial-registry", "\x01f\x01"),
    ("series-params-parity", "\x01g\x01"),
    ("serial-settings", "\x01h\x01"),
]

# Имена таблиц в документации пишутся в обратных кавычках либо после слов
# «таблица», «в», «из», «связь с». Подстановка по одному слову вслепую снесла
# бы упоминания вида задания и ключа настройки, поэтому якорит список.
TABLE_ANCHOR = re.compile(
    r"`(" + "|".join(sorted(rt.TABLE_MAP, key=len, reverse=True)) + r")`"
)
TABLE_KEYWORDS = re.compile(
    r"\b(таблиц\w*|relation|таблица)\s+("
    + "|".join(sorted(rt.TABLE_MAP, key=len, reverse=True))
    + r")\b"
)

EXTRA_RULES = [
    (re.compile(r"\bINCOMPATIBLE_SERIES\b"), "INCOMPATIBLE_EPISODE"),
    (re.compile(r"\bSERIES_NOT_ANALYZED\b"), "EPISODE_NOT_ANALYZED"),
    (re.compile(r"\bSUBJECT_SERIES\b"), "SUBJECT_EPISODE"),
    (re.compile(r"\bserial_name\b"), "movie_name"),
]


def transform(text: str) -> str:
    for plain, mask in KEEP:
        text = text.replace(plain, mask)
    for pattern, repl in rt.COLUMN_RULES:
        text = pattern.sub(repl, text)
    for pattern, repl in EXTRA_RULES:
        text = pattern.sub(repl, text)
    text = TABLE_ANCHOR.sub(lambda m: "`" + rt.TABLE_MAP[m.group(1)] + "`", text)
    text = TABLE_KEYWORDS.sub(lambda m: m.group(1) + " " + rt.TABLE_MAP[m.group(2)], text)
    for plain, mask in KEEP:
        text = text.replace(mask, plain)
    return text


def main(paths):
    changed = 0
    for name in paths:
        p = Path(name)
        original = p.read_text(encoding="utf-8")
        updated = transform(original)
        if updated != original:
            p.write_text(updated, encoding="utf-8")
            changed += 1
            print(name)
    print("изменено файлов: %d" % changed, file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1:])
