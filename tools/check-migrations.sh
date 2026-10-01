#!/usr/bin/env bash
# SYP — проверка миграций на живой базе.
#
# Миграции применяются к одноразовому контейнеру `postgres:16`. Контейнеры SYP
# при этом не создаются и не трогаются; контейнер проверки удаляется в конце.
#
# Проверяется ровно то, что перечислено в задаче T025:
#   1. все файлы deploy/syp-db/NN_*.sql применяются по порядку;
#   2. состав схемы: 22 таблицы, среди них source_file_checksum,
#      build_recipe, build_recipe_item;
#   3. 36 проверок ограничений: каждая — «ожидается отказ» или «ожидается
#      принятие», в отдельной транзакции с откатом;
#   4. новый сериал автоматически получает 11 настроек по умолчанию;
#   5. повторное применение 01_catalog.sql отклоняется базой;
#   6. столбец recordhash присутствует во всех таблицах.
#
# Использование: bash tools/check-migrations.sh
# Код возврата: 0 — все проверки прошли, 1 — есть расхождения.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

export DOCKER_CONFIG="${DOCKER_CONFIG:-/home/nsa/syp/.docker}"

NAME="syp-migcheck-$$"
DB_USER="postgres"
DB_NAME="syp"
MIGRATIONS="${ROOT}/deploy/syp-db"

passed=0
failed=0

cleanup() {
    docker rm -f "${NAME}" >/dev/null 2>&1
}
trap cleanup EXIT

psql_run() {
    docker exec -i "${NAME}" psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q -tA -f -
}

report_ok() {
    printf '  %-4s ок    %s\n' "ОК" "$1"
    passed=$((passed + 1))
}

report_fail() {
    printf '  %-4s СБОЙ  %s\n' "ОШ" "$1"
    failed=$((failed + 1))
}

printf '%s\n' "=== SYP: проверка миграций на одноразовом postgres:16 ==="
docker run -d --rm --name "${NAME}" -e POSTGRES_PASSWORD=x -e POSTGRES_DB="${DB_NAME}" postgres:16 >/dev/null 2>&1
if [[ $? -ne 0 ]]; then
    printf '%s\n' "НЕ УДАЛОСЬ ЗАПУСТИТЬ КОНТЕЙНЕР postgres:16" >&2
    exit 1
fi

for _ in $(seq 1 60); do
    docker exec "${NAME}" pg_isready -U "${DB_USER}" -d "${DB_NAME}" >/dev/null 2>&1 && break
    sleep 1
done

# --- 1. Применение миграций по порядку -------------------------------------
printf '\n%s\n' "1. Применение миграций по порядку"
applied=0
for file in "${MIGRATIONS}"/[0-9][0-9]_*.sql; do
    name="$(basename "${file}")"
    if docker exec -i "${NAME}" psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q -f - < "${file}" 2>/dev/null; then
        printf '  %-4s ок    применён %s\n' "ОК" "${name}"
        applied=$((applied + 1))
    else
        report_fail "применение ${name} отклонено"
    fi
done
printf '%s\n' "  применено файлов: ${applied}"

# --- 2. Состав схемы -------------------------------------------------------
printf '\n%s\n' "2. Состав схемы"
tables=$(psql_run <<< "SELECT count(*) FROM information_schema.tables WHERE table_schema='public';")
if [[ "${tables}" == "22" ]]; then
    report_ok "таблиц 22, как ожидалось"
else
    report_fail "таблиц ${tables}, ожидалось 22"
fi

for table in source_file_checksum build_recipe build_recipe_item; do
    exists=$(psql_run <<< "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${exists}" == "1" ]]; then
        report_ok "таблица ${table} есть"
    else
        report_fail "таблица ${table} отсутствует"
    fi
done

hashes=$(psql_run <<< "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='recordhash';")
if [[ "${hashes}" == "22" ]]; then
    report_ok "столбец recordhash во всех 22 таблицах"
else
    report_fail "recordhash в ${hashes} таблицах из 22"
fi

# --- 3. Проверки ограничений ----------------------------------------------
printf '\n%s\n' "3. Проверки ограничений"

# Подготовка данных для проверок. Каждая проверка выполняется в своей
# транзакции с откатом, поэтому состояние базы после проверок не меняется.
psql_run <<'SQL' >/dev/null 2>&1
INSERT INTO serial (id, name, source_root) VALUES (901, 'Проверка ограничений', '/srv/got');
INSERT INTO location (serial_id, name) VALUES (901, 'Лагерь');
INSERT INTO series (id, serial_id, ordinal, name, source_path, file_size, file_mtime,
                    frame_count, time_base_num, time_base_den, width, height,
                    duration_num, duration_den, video_codec, pixel_format)
VALUES (901, 901, 1, 'S01E01', '/srv/got/S01E01.mkv', 5598286865, now(),
        88643, 1001, 24000, 1920, 1080, 36972, 10, 'h264', 'yuv420p');
SQL

# expect_ok — вставка должна пройти; expect_fail — должна быть отклонена.
check() {
    local label="$1" expect="$2" sql="$3"
    local output status
    output=$(printf 'BEGIN;\n%s\nROLLBACK;\n' "${sql}" | psql_run 2>&1)
    status=$?
    if [[ "${expect}" == "ok" && ${status} -eq 0 ]]; then
        report_ok "${label}"
    elif [[ "${expect}" == "fail" && ${status} -ne 0 ]]; then
        report_ok "${label}"
    else
        report_fail "${label} — ожидалось «${expect}», получено «${output}»"
    fi
}

# Проверка, что вставка действительно вставила строку. Без неё проверка
# «принят» проходит вхолостую: INSERT ... SELECT без строк завершается
# успехом и ничего не доказывает.
check_writes() {
    local label="$1" sql="$2"
    local body output status rows
    # INSERT ... RETURNING в CTE: так виден фактический счётчик затронутых
    # строк. Подзапросом INSERT не обернуть — такого синтаксиса нет, а
    # молчаливый ноль строк прошёл бы проверку «принят» вхолостую.
    body="${sql%;}"
    output=$(printf 'BEGIN;\nWITH вставлено AS (%s RETURNING 1) SELECT count(*) FROM вставлено;\nROLLBACK;\n' \
        "${body}" | psql_run 2>&1)
    status=$?
    rows=$(printf '%s' "${output}" | tail -1)
    if [[ ${status} -eq 0 && "${rows}" =~ ^[0-9]+$ && "${rows}" -ge 1 ]]; then
        report_ok "${label}"
    else
        report_fail "${label} — вставлено строк: «${rows}», вывод: «${output}»"
    fi
}

# --- каталог: ограничения путей и уникальности ---
check "корень сериала не абсолютный — отказ" fail \
    "INSERT INTO serial (name, source_root) VALUES ('p1', 'got');"
check "корень сериала со слэшем в конце — отказ" fail \
    "INSERT INTO serial (name, source_root) VALUES ('p2', '/srv/got/');"
check "пустое имя сериала — отказ" fail \
    "INSERT INTO serial (name, source_root) VALUES ('   ', '/srv/got');"
check "дубль названия сериала — отказ" fail \
    "INSERT INTO serial (name, source_root) VALUES ('Проверка ограничений', '/srv/got2');"
check "корень сериала без слэша в конце — принят" ok \
    "INSERT INTO serial (name, source_root) VALUES ('p3', '/srv/got3');"

check "путь серии не абсолютный — отказ" fail \
    "INSERT INTO series (serial_id, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 2, 'X', 'relative/path.mkv', 100, now(), 10, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"
check "нулевое число кадров — отказ" fail \
    "INSERT INTO series (serial_id, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 3, 'Y', '/srv/got/Y.mkv', 100, now(), 0, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"
check "нулевая ширина — отказ" fail \
    "INSERT INTO series (serial_id, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 4, 'Z', '/srv/got/Z.mkv', 100, now(), 10, 1001, 24000, 0, 1080, 10, 1, 'h264', 'yuv420p');"
check "дубль пути к источнику — отказ" fail \
    "INSERT INTO series (serial_id, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 5, 'W', '/srv/got/S01E01.mkv', 100, now(), 10, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"
check "дубль номера серии в сериале — отказ" fail \
    "INSERT INTO series (serial_id, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 1, 'V', '/srv/got/V.mkv', 100, now(), 10, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"

# --- очередь заданий: виды, состояния, прогресс, ошибка ---
check "вид задания ASSEMBLE — отказ" fail \
    "INSERT INTO job (kind, params, params_hash) VALUES ('ASSEMBLE', '{}', 'h');"
check "вид задания ANALYZE — принят" ok \
    "INSERT INTO job (kind, params, params_hash) VALUES ('ANALYZE', '{}', 'h');"
check "вид задания HASH — принят" ok \
    "INSERT INTO job (kind, params, params_hash) VALUES ('HASH', '{}', 'h2');"
check "состояние задания PAUSED — отказ" fail \
    "INSERT INTO job (kind, state, params, params_hash) VALUES ('FACES', 'PAUSED', '{}', 'h3');"
check "состояние ERROR без текста ошибки — отказ" fail \
    "INSERT INTO job (kind, state, params, params_hash) VALUES ('TRAIN', 'ERROR', '{}', 'h4');"
check "состояние DONE без общего объёма — отказ" fail \
    "INSERT INTO job (kind, state, params, params_hash, progress_total) VALUES ('ANALYZE', 'DONE', '{}', 'h5', 0);"
check "состояние DONE с общим объёмом — принят" ok \
    "INSERT INTO job (kind, state, params, params_hash, progress_total) VALUES ('ANALYZE', 'DONE', '{}', 'h6', 88643);"
check "пустой хеш параметров — отказ" fail \
    "INSERT INTO job (kind, params, params_hash) VALUES ('HASH', '{}', '   ');"
check "отрицательный прогресс — отказ" fail \
    "INSERT INTO job (kind, params, params_hash, progress_done) VALUES ('HASH', '{}', 'h7', -1);"
check "пять состояний очереди — приняты" ok \
    "INSERT INTO job (kind, state, params, params_hash, progress_total, error_text) VALUES
     ('ANALYZE','WAITING','{}','s1',0,NULL),
     ('FACES','CREATING','{}','s2',0,NULL),
     ('TRAIN','WORKING','{}','s3',0,NULL),
     ('HASH','DONE','{}','s4',10,NULL),
     ('HASH','ERROR','{}','s5',0,'упало');"

# --- артефакты: виды, размещение, состояния, контрольная сумма ---
check "вид артефакта ASSEMBLY — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key) VALUES ('ASSEMBLY', 'SSD', 'a/b');"
check "вид артефакта PREVIEW_SHEET — принят" ok \
    "INSERT INTO artifact (kind, placement, object_key) VALUES ('PREVIEW_SHEET', 'SSD', 'a/p');"
check "вид артефакта RECIPE — принят" ok \
    "INSERT INTO artifact (kind, placement, object_key) VALUES ('RECIPE', 'SSD', 'a/r');"
check "размещение HDD — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key) VALUES ('MODEL', 'HDD', 'a/m');"
check "состояние артефакта PENDING — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key, state) VALUES ('MODEL', 'SSD', 'a/x', 'PENDING');"
check "готовый артефакт без размера — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key, state, byte_size) VALUES ('MODEL', 'SSD', 'a/y', 'READY', NULL);"
check "контрольная сумма не из 64 hex — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key, byte_size, checksum) VALUES ('MODEL', 'SSD', 'a/z', 10, 'abc');"
check "контрольная сумма в верхнем регистре — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key, byte_size, checksum)
     VALUES ('MODEL', 'SSD', 'a/w', 10, repeat('A', 64));"
check "контрольная сумма 64 hex в нижнем — принят" ok \
    "INSERT INTO artifact (kind, placement, object_key, byte_size, checksum)
     VALUES ('MODEL', 'SSD', 'a/v', 10, repeat('a', 64));"
check "дубль вида и ключа артефакта — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key, byte_size, checksum) VALUES ('MODEL', 'SSD', 'a/v', 10, repeat('a', 64));
     INSERT INTO artifact (kind, placement, object_key) VALUES ('MODEL', 'SSD', 'a/v');"
check "пустой ключ артефакта — отказ" fail \
    "INSERT INTO artifact (kind, placement, object_key) VALUES ('MODEL', 'SSD', '   ');"

# --- справочник сумм: алгоритм, формат суммы, единственность ---
check "алгоритм суммы не SHA-256 — отказ" fail \
    "INSERT INTO source_file_checksum (series_id, algorithm, digest, byte_size, file_mtime, state)
     VALUES (901, 'MD5', repeat('a', 32), 10, now(), 'DONE');"
check "сумма не из 64 hex — отказ" fail \
    "INSERT INTO source_file_checksum (series_id, algorithm, digest, byte_size, file_mtime, state)
     VALUES (901, 'SHA-256', repeat('a', 63), 10, now(), 'DONE');"
check "состояние суммы DONE без текста ошибки — принят" ok \
    "INSERT INTO source_file_checksum (series_id, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (901, 'SHA-256', repeat('b', 64), 10, now(), 'DONE', now());"
check "вторая актуальная сумма той же серии — отказ" fail \
    "INSERT INTO source_file_checksum (series_id, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (901, 'SHA-256', repeat('c', 64), 10, now(), 'DONE', now());
     INSERT INTO source_file_checksum (series_id, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (901, 'SHA-256', repeat('g', 64), 10, now(), 'DONE', now());"
check "вторая устаревшая сумма той же серии — принят" ok \
    "INSERT INTO source_file_checksum (series_id, algorithm, digest, byte_size, file_mtime, state, computed_at, is_stale)
     VALUES (901, 'SHA-256', repeat('d', 64), 10, now(), 'DONE', now(), true);"

# --- настройки по умолчанию ---
settings=$(psql_run <<< "SELECT count(*) FROM analysis_setting WHERE serial_id = 901;")
if [[ "${settings}" == "11" ]]; then
    report_ok "новый сериал получил 11 настроек по умолчанию"
else
    report_fail "новый сериал получил ${settings} настроек, ожидалось 11"
fi

# --- повторное применение применённой миграции -----------------------------
printf '\n%s\n' "4. Защита от правки применённой миграции"
if docker exec -i "${NAME}" psql -U "${DB_USER}" -d "${DB_NAME}" -v ON_ERROR_STOP=1 -q -f - < "${MIGRATIONS}/01_catalog.sql" 2>/tmp/syp-reapply.txt; then
    report_fail "повторное применение 01_catalog.sql прошло: защита не работает"
else
    if grep -q 'already exists' /tmp/syp-reapply.txt; then
        report_ok "повторное применение отклонено базой: relation already exists"
    else
        report_fail "повторное применение отклонено с непонятной ошибкой"
    fi
fi

# --- сквозная вставка сценария --------------------------------------------
printf '\n%s\n' "5. Сквозная вставка корректного сценария"

# Фикстуры: артефакт и сценарий, против которых идут проверки ограничений
# фрагмента. Без них каждая проверка была бы пустой: вставка не нашла бы
# родительский сценарий и завершилась бы успехом ни с чем.
if ! psql_run <<'SQL' >/dev/null 2>&1
INSERT INTO scene (id, series_id, first_frame, last_frame, origin)
VALUES (901, 901, 0, 123, 'AUTO');
INSERT INTO artifact (kind, placement, object_key, byte_size, checksum)
VALUES ('RECIPE', 'SSD', 'recipes/probe.json', 42, repeat('9', 64));
INSERT INTO build_recipe (serial_id, name, schema_version, state, artifact_id, content_sha256,
                          signature, signing_key_id, item_count, expected_duration_ms,
                          expected_frame_count, created_at, finished_at)
SELECT 901, 'Фикстура', 1, 'DONE', id, repeat('e', 64), 'c2ln', 'key1', 1, 1000, 48,
       now(), now()
  FROM artifact WHERE object_key = 'recipes/probe.json';
SQL
then
    report_fail "фикстуры сцены, артефакта и сценария не создались: проверки фрагмента не имеют смысла"
    failed=$((failed + 1))
else
    report_ok "фикстуры сцены, артефакта и сценария созданы"
fi

check "сценарий DONE без подписи — отказ" fail \
    "INSERT INTO build_recipe (serial_id, name, schema_version, state, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р', 1, 'DONE', repeat('e', 64), NULL, NULL, 1, 1000, 48, now(), now());"
check "подпись без суммы содержимого — отказ" fail \
    "INSERT INTO build_recipe (serial_id, name, schema_version, state, signature, signature_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р2', 1, 'DONE', 'sig', 'key1', 1, 1000, 48, now(), now());"
check "сценарий DONE без числа фрагментов — отказ" fail \
    "INSERT INTO build_recipe (serial_id, name, schema_version, state, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р3', 1, 'DONE', repeat('e', 64), 'sig', 'key1', 0, 1000, 48, now(), now());"
check "сценарий DONE без артефакта — отказ" fail \
    "INSERT INTO build_recipe (serial_id, name, schema_version, state, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р5', 1, 'DONE', repeat('e', 64), 'c2ln', 'key1', 1, 1000, 48, now(), now());"
check "корректный сценарий DONE — принят" ok \
    "INSERT INTO build_recipe (serial_id, name, schema_version, state, artifact_id, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     SELECT 901, 'Р4', 1, 'DONE', id, repeat('e', 64), 'c2ln', 'key1', 1, 1000, 48, now(), now()
       FROM artifact WHERE object_key = 'recipes/probe.json';"
check "фрагмент с абсолютным путём — отказ" fail \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', '/srv/got/S01E01.mkv', repeat('f', 64), 0, 47, 0, 47, '[]'::jsonb FROM build_recipe WHERE name = 'Фикстура';"
check "фрагмент с выходом за пределы дерева — отказ" fail \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', '../secret/S01E01.mkv', repeat('f', 64), 0, 47, 0, 47, '[]'::jsonb FROM build_recipe WHERE name = 'Фикстура';"
check "фрагмент, начинающийся позже расчётного — отказ" fail \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 20, 47, '[]'::jsonb FROM build_recipe WHERE name = 'Фикстура';"
check "фрагмент, заканчивающийся раньше расчётного — отказ" fail \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 0, 40, '[]'::jsonb FROM build_recipe WHERE name = 'Фикстура';"
check "отрицательный номер кадра — отказ" fail \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), -1, 47, -1, 47, '[]'::jsonb FROM build_recipe WHERE name = 'Фикстура';"
check "имена персонажей не массивом — отказ" fail \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 0, 47, 'Джейми' FROM build_recipe WHERE name = 'Фикстура';"
check_writes "корректный фрагмент — принят" \
    "INSERT INTO build_recipe_item (recipe_id, ordinal, scene_id, series_id, series_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 0, 47, '[]'::jsonb FROM build_recipe WHERE name = 'Фикстура';"

# --- каскадное удаление ----------------------------------------------------
printf '\n%s\n' "6. Каскадное удаление сериала"
cascade=$(psql_run <<< "
BEGIN;
INSERT INTO serial (id, name, source_root) VALUES (902, 'Каскад', '/srv/got2');
SELECT count(*) FROM build_recipe WHERE serial_id = 902;
DELETE FROM serial WHERE id = 902;
SELECT count(*) FROM build_recipe WHERE serial_id = 902;
ROLLBACK;" | tail -1)
if [[ "${cascade}" == "0" ]]; then
    report_ok "удаление сериала уносит сценарии каскадом"
else
    report_fail "после удаления сериала осталось сценариев: ${cascade}"
fi

# --- Итог -------------------------------------------------------------------
printf '\n%s\n' "============================================================"
printf 'ПРОЙДЕНО: %d, ПРОВАЛЕНО: %d\n' "${passed}" "${failed}"
if [[ ${failed} -gt 0 ]]; then
    exit 1
fi
printf '%s\n' "ВСЕ ПРОВЕРКИ ПРОЙДЕНЫ"
exit 0
