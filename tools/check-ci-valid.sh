#!/usr/bin/env bash
# Проверка того, что файл CI разбирается как YAML.
#
# Зачем: файл `.github/workflows/ci.yml` был сломан — хвост задания заканчивался
# списком с неверным отступом и висящим продолжением команды. GitHub Actions такой
# файл не исполняет, а молча: ни одна проверка в CI не запускалась, и merge
# проходил при «зелёном» гейте, которого фактически не было. Проверка держит
# файл разбираемым и требует, чтобы в каждом задании был хотя бы один шаг.
#
# Проверка доказывает две вещи, и обе на файле репозитория:
#   1. файл разбирается как YAML;
#   2. каждое задание описано и имеет шаги.
set -uo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
file="$root/.github/workflows/ci.yml"

if [ ! -f "$file" ]; then
    echo "ОШИБКА: нет файла $file"
    exit 1
fi

python3 - "$file" <<'PY'
import sys

import yaml

path = sys.argv[1]
try:
    with open(path, encoding="utf-8") as handle:
        document = yaml.safe_load(handle)
except yaml.YAMLError as failure:
    problem = getattr(failure, "problem", str(failure))
    mark = getattr(failure, "problem_mark", None)
    where = f"строка {mark.line + 1}, столбец {mark.column + 1}" if mark else "неизвестно место"
    sys.exit(f"ОШИБКА: CI не разбирается как YAML ({where}): {problem}")

if not isinstance(document, dict) or "jobs" not in document:
    sys.exit("ОШИБКА: в CI нет раздела jobs — проверять нечего")

jobs = document["jobs"]
if not isinstance(jobs, dict) or not jobs:
    sys.exit("ОШИБКА: в CI нет ни одного задания")

empty = [name for name, job in jobs.items() if not (job or {}).get("steps")]
if empty:
    sys.exit(f"ОШИБКА: задания без шагов: {', '.join(sorted(empty))} — они ничего не проверяют")

print(f"OK: CI разбирается, заданий {len(jobs)}: {', '.join(sorted(jobs))}")
PY
