#!/usr/bin/env python3
"""Проверка tasks.md: формат, пути, покрытие фаз, FR и SC."""
import re, sys, pathlib, collections

ROOT = pathlib.Path(__file__).resolve().parent.parent
FEATURE = sys.argv[1] if len(sys.argv) > 1 else "specs/001-first-vertical-slice"
TASKS = ROOT / FEATURE / "tasks.md"
text = TASKS.read_text(encoding="utf-8")
lines = text.splitlines()

errors, warnings = [], []

# ---------- 1. Разбор задач ----------
task_re = re.compile(r"^- \[ \] (T\d{3})(?: \[P\])?(?: \[US\d\])? (.+)$")
tasks = []          # (id, raw_desc, block_lines)
cur = None
for i, ln in enumerate(lines):
    m = task_re.match(ln)
    if m:
        cur = {"id": m.group(1), "desc": m.group(2), "line": i + 1,
               "files": [], "depends": "", "check": ""}
        tasks.append(cur)
        continue
    if cur is not None:
        s = ln.strip()
        if s.startswith("- Файлы:"):
            cur["files_raw"] = s[len("- Файлы:"):].strip()
        elif s.startswith("- Зависит от:"):
            cur["depends"] = s[len("- Зависит от:"):].strip()
        elif s.startswith("- Проверка:"):
            cur["check"] = s[len("- Проверка:"):].strip()
        elif ln.startswith("- [ ]") or ln.startswith("# "):
            cur = None

print(f"Задач разобрано: {len(tasks)}")

# формат
for t in tasks:
    if not t["files_raw"]:
        errors.append(f"{t['id']}: нет строки «Файлы»")
    if not t["check"]:
        errors.append(f"{t['id']}: нет строки «Проверка» (задача без проверки)")
    if "depends" not in t:
        errors.append(f"{t['id']}: нет строки «Зависит от»")

# последовательность ID
ids = [t["id"] for t in tasks]
expected = [f"T{i:03d}" for i in range(1, len(tasks) + 1)]
if ids != expected:
    for a, b in zip(ids, expected):
        if a != b:
            errors.append(f"нарушена последовательность: ожидалось {b}, найдено {a}")
            break

# дубликаты
dups = [k for k, v in collections.Counter(ids).items() if v > 1]
if dups:
    errors.append(f"дубликаты ID: {dups}")

# ---------- 2. Пути ----------
path_re = re.compile(r"`([^`]+)`")
# созданные пути = все пути из строк «Файлы»
created = set()
for t in tasks:
    for p in path_re.findall(t.get("files_raw", "")):
        created.add(p.strip())

# все пути, упомянутые где-либо в документе (Файлы + Зависит от + Проверка + таблицы)
all_mentioned = collections.Counter()
for ln in lines:
    for p in path_re.findall(ln):
        all_mentioned[p.strip()] += 1

ROOTS = ("specs/", "docs/", "deploy/", "tools/", "syp-core/", "syp-admin-app/",
         "syp-public-app/", "syp-admin-web/", "syp-public-web/", "syp-worker/",
         ".github/", ".scratch/", ".git/", "gradle/wrapper/")
ROOT_FILES = ("AGENTS.md", "CLAUDE.md", "constitution.md", "settings.gradle.kts",
              "build.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat",
              ".gitignore", ".env.local-tracker.example")


def is_repo_path(p):
    """Путь репозитория: начинается с известного корня или известного файла."""
    if p.startswith(("http", "/", "~", ".")) and not p.startswith(("./", ".scratch/", ".github/", ".git/")):
        return False
    if p.startswith(ROOT_FILES) or p.startswith(ROOTS):
        return True
    return False


def normalize(p):
    return re.sub(r"[,:;]$", "", p.strip())


missing, exists_count, created_count = [], 0, 0
for p in sorted(all_mentioned):
    # многословный backticked-токен (команда с аргументами) — проверяем по словам
    parts = [normalize(x) for x in p.split()] if (" " in p and "://" not in p) else [normalize(p)]
    for part in parts:
        if not is_repo_path(part):
            continue
        base = part.split("{")[0].rstrip("/")
        target = ROOT / (base if base else part)
        if target.exists():
            exists_count += 1
            continue
        if part in created or any(c.startswith(part.rstrip("/")) for c in created):
            created_count += 1
            continue
        missing.append(part)

print(f"Путей, существующих в репозитории: {exists_count}")
print(f"Путей, создаваемых задачами: {created_count}")
print(f"Путей, не найденных: {len(missing)}")
for m in missing:
    errors.append(f"путь не существует и не создаётся ни одной задачей: {m}")

# ---------- 3. Зависимости на существующие задачи ----------
all_ids = set(ids)
for t in tasks:
    for dep in re.findall(r"T\d{3}", t.get("depends", "")):
        if dep not in all_ids:
            errors.append(f"{t['id']}: зависит от несуществующей {dep}")

# ---------- 4. Ссылки T*** во всём документе ----------
for p, n in all_mentioned.items():
    pass
for ln_no, ln in enumerate(lines, 1):
    for ref in re.findall(r"\bT\d{3}\b", ln):
        if ref not in all_ids:
            errors.append(f"строка {ln_no}: ссылка на несуществующую задачу {ref}")

# ---------- 5. Покрытие FR ----------
spec = (ROOT / FEATURE / "spec.md").read_text(encoding="utf-8")
req = spec.split("## Requirements")[1].split("### Key Entities")[0]
spec_fr = sorted(set(re.findall(r"\*\*(FR-\d{3}[a-z]?)\*\*", req)))
covered = collections.defaultdict(set)
for ln in lines:
    for fr in re.findall(r"FR-\d{3}[a-z]?", ln):
        for tid in re.findall(r"\bT\d{3}\b", ln):
            covered[fr].add(tid)
missing_fr = [f for f in spec_fr if not covered.get(f)]
print(f"FR в спецификации: {len(spec_fr)}; покрыто: {len(spec_fr) - len(missing_fr)}")
for f in missing_fr:
    errors.append(f"FR без задачи: {f}")

# ---------- 6. Покрытие SC ----------
spec_sc = sorted(set(re.findall(r"\*\*(SC-\d{3})\*\*", spec)))
covered_sc = collections.defaultdict(set)
for ln in lines:
    for sc in re.findall(r"SC-\d{3}", ln):
        for tid in re.findall(r"\bT\d{3}\b", ln):
            covered_sc[sc].add(tid)
missing_sc = [s for s in spec_sc if not covered_sc.get(s)]
print(f"SC в спецификации: {len(spec_sc)}; покрыто: {len(spec_sc) - len(missing_sc)}")
for s in missing_sc:
    errors.append(f"SC без задачи: {s}")

# ---------- 7. Покрытие фаз плана ----------
plan = (ROOT / FEATURE / "plan.md").read_text(encoding="utf-8")
plan_phases = re.findall(r"^### Фаза (\d+)\.", plan, re.M)
missing_phase = [p for p in plan_phases if f"Фаза {p}." not in text and f"| {p}." not in text]
print(f"Фаз в плане: {len(plan_phases)}; отражено в таблице покрытия: {len(plan_phases) - len(missing_phase)}")
for p in missing_phase:
    errors.append(f"фаза плана {p} не отражена в tasks.md")

# ---------- 8. Замеры М-01…М-13 ----------
for n in range(1, 14):
    m = f"М-{n:02d}"
    if m not in text:
        errors.append(f"замер {m} не упомянут")
if not re.search(r"М-13\s*\|", text):
    warnings.append("замер М-13 не найден в таблице замеров")

# ---------- 9. Сверка с эталоном ----------
for needle, label in [("71 005", "границы 71 005"), ("875", "875 планов"),
                      ("1028", "1028 плана"), ("88 643", "88 643 кадра"),
                      ("6 042 173", "архив 6 042 173"), ("82 769", "средняя серия")]:
    if needle not in text:
        errors.append(f"нет числа сверки с эталоном: {label}")
if "legacy-iGOT" not in text:
    errors.append("нет ссылки на каталог эталона legacy-iGOT")

# ---------- 10. Эмодзи / стиль ----------
emoji = re.compile(r"[\U00010000-\U0010ffff]|[✀-➿]|[☀-⛿]")
for ln_no, ln in enumerate(lines, 1):
    if emoji.search(ln):
        errors.append(f"строка {ln_no}: запрещённый символ")

# ---------- Итог ----------
print()
print("=" * 60)
if warnings:
    print("ПРЕДУПРЕЖДЕНИЯ:")
    for w in warnings:
        print("  -", w)
if errors:
    print(f"ОШИБОК: {len(errors)}")
    for e in errors[:60]:
        print("  -", e)
    sys.exit(1)
print("ПРОВЕРКА ПРОЙДЕНА: ошибок нет")
