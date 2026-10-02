#!/usr/bin/env python3
"""Переименование сущностей SYP по карте владельца (RENAME-MAP.md).

serial -> Movie (tbl_movies), series -> Episode (tbl_episodes).
Имена таблиц получают префикс tbl_ и форму во множественном числе;
столбцы-ссылки serial_id -> id_movie, series_id -> id_episode.

Скрипт одноразовый: применяется к рабочему дереву, результат проверяется
сборкой и грепом, затем удаляется.
"""

import re
import sys
from pathlib import Path

# --- 1. Имена таблиц --------------------------------------------------------

# Карта владельца: .scratch/syp/wayfinding/RENAME-MAP.md
TABLE_MAP = {
    "serial": "tbl_movies",
    "series": "tbl_episodes",
    "analysis_run": "tbl_analysis_runs",
    "analysis_setting": "tbl_analysis_settings",
    "artifact": "tbl_artifacts",
    "build_recipe": "tbl_build_recipes",
    "build_recipe_item": "tbl_build_recipe_items",
    "face": "tbl_faces",
    "face_embedding": "tbl_face_embeddings",
    "filter_condition": "tbl_filter_conditions",
    "filter_group": "tbl_filter_groups",
    "frame": "tbl_frames",
    "job": "tbl_jobs",
    "location": "tbl_locations",
    "model_version": "tbl_model_versions",
    "model_version_example": "tbl_model_version_examples",
    "person": "tbl_persons",
    "raw_boundary": "tbl_raw_boundaries",
    "scene": "tbl_scenes",
    "season": "tbl_seasons",
    "shot": "tbl_shots",
    "source_file_checksum": "tbl_source_file_checksums",
    "syp_filter": "tbl_filters",
}
_TABLE_ALT = "|".join(sorted(TABLE_MAP, key=len, reverse=True))
TABLE_RE = re.compile(r"\b(" + _TABLE_ALT + r")\b")
LONE_TABLE = re.compile(r"^(" + _TABLE_ALT + r")$")

# Имя таблицы опознаётся только после SQL-ключевого слова. Без якоря `frame`
# в русском тексте («кадр $frame») и `scene` в ключе настройки
# («scene.threshold») попали бы под переименование, а это данные, а не таблицы.
SQL_ANCHOR = re.compile(
    r"\b(FROM|JOIN|INTO|UPDATE|TABLE|ON|REFERENCES|EXISTS)\s+(?=[A-Za-z_])",
    re.IGNORECASE,
)
# Признак того, что строковый литерал — это SQL, а не текст оператору.
SQL_LITERAL = re.compile(
    r"\b(SELECT|INSERT\s+INTO|UPDATE|DELETE\s+FROM|CREATE\s+TABLE"
    r"|ALTER\s+TABLE|ON\s+CONFLICT|FROM)\b",
    re.IGNORECASE,
)

# --- 2. Столбцы и имена в JSON ----------------------------------------------

# Написаны змейкой либо верблюжьим регистром и в других смыслах не
# встречаются, поэтому ищутся в любом сегменте.
COLUMN_RULES = [
    (re.compile(r"\bserial_id\b"), "id_movie"),
    (re.compile(r"\bseries_id\b"), "id_episode"),
    (re.compile(r"\bseries_name\b"), "episode_name"),
    (re.compile(r"\bseries_count\b"), "episode_count"),
    (re.compile(r"\bserialName\b"), "movieName"),
    (re.compile(r"\bseriesName\b"), "episodeName"),
    (re.compile(r"\bserialId\b"), "movieId"),
    (re.compile(r"\bseriesId\b"), "episodeId"),
    # Значение вида задания в job.subject_type.
    (re.compile(r"'SERIES'"), "'EPISODE'"),
    (re.compile(r'"SERIES"'), '"EPISODE"'),
    # Ключи и значения, названные по сущности, но не являющиеся столбцами.
    (re.compile(r"\bserial_name\b"), "movie_name"),
    (re.compile(r'"SERIAL_TREE"'), '"MOVIE_TREE"'),
    (re.compile(r"\bSYP_SOURCE_SERIES\b"), "SYP_SOURCE_EPISODE"),
    (re.compile(r"/tmp/syp-test-serial\b"), "/tmp/syp-test-movie"),
    (re.compile(r'"series\.mkv"'), '"episode.mkv"'),
    # Префикс ключа листа превью в хранилище: назван по сущности.
    (re.compile(r"series/"), "episode/"),
    (re.compile(r"series="), "episode="),
]

# --- 3. URL-пути API --------------------------------------------------------

# Сегменты коллекций в карту переименования не входят: `/api/serials` и
# `/api/series` остаются как есть. Имена переменных пути ({serialId})
# переименовываются — это имена переменных, а не части URL.
URL_MASK = [
    ("api/serials", "api/\x00S\x00"),
    ("api/series", "api/\x00E\x00"),
    ("/serials", "/\x00S\x00"),
    ("/series/", "/\x00E\x00/"),
    ("/series", "/\x00E\x00"),
]


def apply_table_names(text):
    out = []
    pos = 0
    for match in SQL_ANCHOR.finditer(text):
        out.append(text[pos:match.end()])
        name = TABLE_RE.match(text[match.end():])
        if name:
            out.append(TABLE_MAP[name.group(1)])
            pos = match.end() + len(name.group(0))
        else:
            pos = match.end()
    out.append(text[pos:])
    return "".join(out)


def mask_urls(text):
    """Сегменты путей API и браузерных маршрутов переименованию не подлежат:
    они перечислены в контрактах, а карта их не трогает. На время прохода они
    прячутся за непечатаемыми метками и возвращаются как были."""
    for plain, mask in URL_MASK:
        text = text.replace(plain, mask)
    return text


def unmask_urls(text):
    for plain, mask in URL_MASK:
        text = text.replace(mask, plain)
    return text


def apply_literal_rules(text, sql):
    for pattern, repl in COLUMN_RULES:
        text = pattern.sub(repl, text)
    if sql:
        if SQL_LITERAL.search(text):
            text = apply_table_names(text)
        else:
            lone = LONE_TABLE.match(text.strip().strip(chr(34)))
            if lone:
                text = text.replace(lone.group(1), TABLE_MAP[lone.group(1)])
    return text


# --- 4. Переименование идентификаторов --------------------------------------

TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
# BIGSERIAL и SERIAL — типы PostgreSQL, а не сущности проекта.
KEEP_TOKENS = {"BIGSERIAL", "SERIAL"}
NAME_MAP = [
    ("Serial", "Movie"),
    ("Series", "Episode"),
    ("SERIAL", "MOVIE"),
    ("SERIES", "EPISODE"),
    ("serial", "movie"),
    ("series", "episode"),
]


def rename_identifiers(text):
    def repl(match):
        token = match.group(0)
        if token in KEEP_TOKENS:
            return token
        for old, new in NAME_MAP:
            token = token.replace(old, new)
        return token

    return TOKEN.sub(repl, text)


# --- 5. Разбор Kotlin: один проход, иначе кавычки в комментариях ломают ----
#
# Три последовательных прохода (сначала комментарии, потом строки) дают
# неверный результат: регулярное выражение строк не знает про комментарии и
# склеивает кавычку из комментария с кавычкой кода. Разбор поэтому один:
# регулярное выражение находит следующий токен, обработчики вызываются по
# типу найденного, позиция двигается только вперёд.

KT_TOKEN = re.compile(
    r'(?P<block>/\*(?:.|\n)*?\*/)'
    r"|(?P<line>//[^\n]*)"
    r'|(?P<string>"""(?:.|\n)*?""'
    r'|"(?:\\.|\$\{(?:[^{}]|\{[^{}]*\})*\}|[^"\\\n])*")',
    re.VERBOSE,
)
KT_BARE_DOLLAR = re.compile(r"\$[A-Za-z_][A-Za-z0-9_]*")


def _split_template(text):
    """Делит строку на части: текст, код в ${...} и код после одиночного $."""
    parts = []
    pos = 0
    i = 0
    while i < len(text):
        if text[i] != "$":
            i += 1
            continue
        if text.startswith("${", i):
            depth = 0
            j = i + 1
            while j < len(text):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            parts.append(("text", text[pos:i]))
            parts.append(("code", text[i:j + 1]))
            i = j + 1
            pos = i
            continue
        m = KT_BARE_DOLLAR.match(text, i)
        if m:
            parts.append(("text", text[pos:i]))
            parts.append(("code", m.group(0)))
            i = m.end()
            pos = i
            continue
        i += 1
    parts.append(("text", text[pos:]))
    return parts


def transform_string(text):
    """Шаблон строки: правила — по тексту, идентификаторы — по коду."""
    out = []
    for kind, chunk in _split_template(text):
        if kind == "text":
            out.append(apply_literal_rules(chunk, True))
        else:
            out.append(rename_identifiers(chunk))
    return "".join(out)


def transform_code(chunk):
    # В коде вне строк SQL нет: правила для имён таблиц не применяются.
    return rename_identifiers(apply_literal_rules(chunk, False))


def transform_kt(text):
    out = []
    pos = 0
    for match in KT_TOKEN.finditer(text):
        out.append(transform_code(text[pos:match.start()]))
        if match.lastgroup == "string":
            out.append(transform_string(match.group(0)))
        else:
            out.append(transform_code(match.group(0)))
        pos = match.end()
    out.append(transform_code(text[pos:]))
    return "".join(out)


def transform_plain(text):
    """TypeScript и Vue: правила для имён таблиц там не нужны, а имена в
    JSON совпадают с именами в коде. Маска путей держится через оба прохода."""
    return unmask_urls(rename_identifiers(apply_literal_rules(mask_urls(text), False)))


def main(paths):
    changed = []
    for path in paths:
        p = Path(path)
        original = p.read_text(encoding="utf-8")
        updated = transform_kt(original) if p.suffix == ".kt" else transform_plain(original)
        if updated != original:
            p.write_text(updated, encoding="utf-8")
            changed.append(path)
    print("изменено файлов: %d" % len(changed), file=sys.stderr)
    for path in changed:
        print(path)


if __name__ == "__main__":
    main(sys.argv[1:])
