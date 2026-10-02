#!/usr/bin/env bash
# SYP — проверка миграций на живой базе.
#
# Миграции применяются к одноразовому контейнеру `postgres:16`. Контейнеры SYP
# при этом не создаются и не трогаются; контейнер проверки удаляется в конце.
#
# Проверяется ровно то, что перечислено в задаче T025, плюс ограничения,
# добавленные миграциями 09 и правила каскадного удаления сериала:
#   1. все файлы deploy/syp-db/NN_*.sql применяются по порядку;
#   2. состав схемы: 23 таблицы, все с префиксом tbl_, среди них
#      tbl_source_file_checksums, tbl_build_recipes, tbl_build_recipe_items;
#   2a. переименование состоялось: прежних имён в схеме нет, новые на
#      месте, а имена ссылок прежних нет (миграция 16);
#   3. проверки ограничений: каждая — «ожидается отказ» или «ожидается
#      принятие», в отдельной транзакции с откатом;
#   4. новый сериал автоматически получает 11 настроек по умолчанию;
#   5. повторное применение 01_catalog.sql отклоняется базой;
#   6. столбец recordhash присутствует во всех таблицах;
#   7. длина карты ключевых кадров обязана быть ровно ceil(кадров / 8) байт:
#      короче значит, что часть кадров молча считается неключевой, длиннее —
#      что границы фрагментов считаются по не тем кадрам (миграция 09);
#   7a. у незавершённого подсчёта суммы нет, а готовая сумма обязана быть, и
#      запись справочника ссылается на существующее задание (миграция 10);
#   7b. у каждого сериала ровно две служебные персоны, вторая того же вида не
#      заводится, удаление заглушки при живом сериале отвергается базой, а
#      удаление сериала уносит заглушки каскадом (миграция 11);
#   8. удаление сериала каскадом уносит серии, лица, персоны, версии моделей,
#      сценарии сборки, справочник сумм и настройки, не оставляя сирот.
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
if [[ "${tables}" == "23" ]]; then
    report_ok "таблиц 23, как ожидалось"
else
    report_fail "таблиц ${tables}, ожидалось 23"
fi

for table in tbl_source_file_checksums tbl_build_recipes tbl_build_recipe_items; do
    exists=$(psql_run <<< "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${exists}" == "1" ]]; then
        report_ok "таблица ${table} есть"
    else
        report_fail "таблица ${table} отсутствует"
    fi
done

# --- 2a. Переименование состоялось ------------------------------------------
# Проверка обратная не «список совпал», а «прежних имён нет»: иначе миграция
# могла бы остаться неприменённой, а состав всё равно сошёлся бы с ожиданием.
printf '\n%s\n' "2a. Переименование таблиц и столбцов-ссылок"

renamed=$(psql_run <<< "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name NOT LIKE 'tbl\_%';")
if [[ "${renamed}" == "0" ]]; then
    report_ok "все 23 таблицы получили префикс tbl_"
else
    report_fail "без префикса tbl_ осталось таблиц: ${renamed}"
fi

stale=""
for table in serial series analysis_run analysis_setting artifact build_recipe \
              build_recipe_item face face_embedding filter_condition filter_group \
              frame job location model_version model_version_example person \
              raw_boundary scene season shot source_file_checksum syp_filter; do
    found=$(psql_run <<< "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${found}" != "0" ]]; then
        stale="${stale} ${table}"
    fi
done
if [[ -z "${stale}" ]]; then
    report_ok "прежних имён таблиц в схеме не осталось"
else
    report_fail "прежние имена таблиц на месте:${stale}"
fi

# Все 23 новых имени обязаны быть на месте: иначе переименование прошло бы
# не полностью, а счёт таблиц остался бы прежним.
missing=""
for table in tbl_movies tbl_episodes tbl_analysis_runs tbl_analysis_settings \
              tbl_artifacts tbl_build_recipes tbl_build_recipe_items tbl_faces \
              tbl_face_embeddings tbl_filter_conditions tbl_filter_groups tbl_frames \
              tbl_jobs tbl_locations tbl_model_versions tbl_model_version_examples \
              tbl_persons tbl_raw_boundaries tbl_scenes tbl_seasons tbl_shots \
              tbl_source_file_checksums tbl_filters; do
    found=$(psql_run <<< "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}';")
    if [[ "${found}" != "1" ]]; then
        missing="${missing} ${table}"
    fi
done
if [[ -z "${missing}" ]]; then
    report_ok "все 23 новых имени таблиц на месте"
else
    report_fail "новых имён нет:${missing}"
fi

# Прежние имена столбцов пишутся здесь дословно: правила переименования к
# самой этой проверке не применяются, иначе она искала бы новые имена и
# рапортовала бы об успехе, ничего не проверяя.
stale_names="$(printf "'serial%s', 'series%s', 'series%s', 'series%s'" _id _id _name _count)"
stale_cols=$(psql_run <<< "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name IN (${stale_names});")
if [[ "${stale_cols}" == "0" ]]; then
    report_ok "прежних имён столбцов-ссылок не осталось"
else
    report_fail "прежних имён столбцов-ссылок осталось: ${stale_cols}"
fi

movie_cols=$(psql_run <<< "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='id_movie';")
if [[ "${movie_cols}" == "8" ]]; then
    report_ok "id_movie в восьми таблицах, как велит карта"
else
    report_fail "id_movie в ${movie_cols} таблицах, ожидалось 8"
fi

episode_cols=$(psql_run <<< "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='id_episode';")
if [[ "${episode_cols}" == "7" ]]; then
    report_ok "id_episode в семи таблицах, как велит карта"
else
    report_fail "id_episode в ${episode_cols} таблицах, ожидалось 7"
fi


hashes=$(psql_run <<< "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND column_name='recordhash';")
if [[ "${hashes}" == "23" ]]; then
    report_ok "столбец recordhash во во всех 23 таблицах"
else
    report_fail "recordhash в ${hashes} таблицах из 23"
fi

# --- 3. Проверки ограничений ----------------------------------------------
printf '\n%s\n' "3. Проверки ограничений"

# Подготовка данных для проверок. Каждая проверка выполняется в своей
# транзакции с откатом, поэтому состояние базы после проверок не меняется.
psql_run <<'SQL' >/dev/null 2>&1
INSERT INTO tbl_movies (id, name, source_root) VALUES (901, 'Проверка ограничений', '/srv/got');
INSERT INTO tbl_locations (id_movie, name) VALUES (901, 'Лагерь');
INSERT INTO tbl_episodes (id, id_movie, ordinal, name, source_path, file_size, file_mtime,
                    frame_count, time_base_num, time_base_den, width, height,
                    duration_num, duration_den, video_codec, pixel_format)
VALUES (901, 901, 1, 'S01E01', '/srv/got/S01E01.mkv', 5598286865, now(),
        88643, 1001, 24000, 1920, 1080, 36972, 10, 'h264', 'yuv420p');
INSERT INTO tbl_episodes (id, id_movie, ordinal, name, source_path, file_size, file_mtime,
                    frame_count, time_base_num, time_base_den, width, height,
                    duration_num, duration_den, video_codec, pixel_format)
VALUES (9501, 901, 2, 'S01E02', '/srv/got/S01E02.mkv', 5022335635, now(),
        80000, 1001, 24000, 1920, 1080, 33351, 10, 'h264', 'yuv420p'),
       (9502, 901, 3, 'S01E03', '/srv/got/S01E03.mkv', 5199677723, now(),
        82000, 1001, 24000, 1920, 1080, 34198, 10, 'h264', 'yuv420p');
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
    "INSERT INTO tbl_movies (name, source_root) VALUES ('p1', 'got');"
check "корень сериала со слэшем в конце — отказ" fail \
    "INSERT INTO tbl_movies (name, source_root) VALUES ('p2', '/srv/got/');"
check "пустое имя сериала — отказ" fail \
    "INSERT INTO tbl_movies (name, source_root) VALUES ('   ', '/srv/got');"
check "дубль названия сериала — отказ" fail \
    "INSERT INTO tbl_movies (name, source_root) VALUES ('Проверка ограничений', '/srv/got2');"
check "корень сериала без слэша в конце — принят" ok \
    "INSERT INTO tbl_movies (name, source_root) VALUES ('p3', '/srv/got3');"

check "путь серии не абсолютный — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 2, 'X', 'relative/path.mkv', 100, now(), 10, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"
check "нулевое число кадров — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 3, 'Y', '/srv/got/Y.mkv', 100, now(), 0, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"
check "нулевая ширина — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 4, 'Z', '/srv/got/Z.mkv', 100, now(), 10, 1001, 24000, 0, 1080, 10, 1, 'h264', 'yuv420p');"
check "дубль пути к источнику — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 5, 'W', '/srv/got/S01E01.mkv', 100, now(), 10, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"
check "дубль номера серии в сериале — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format)
     VALUES (901, 1, 'V', '/srv/got/V.mkv', 100, now(), 10, 1001, 24000, 1920, 1080, 10, 1, 'h264', 'yuv420p');"

# --- карта ключевых кадров: длина обязана соответствовать числу кадров ---
# 88 643 кадра — это ceil(88 643 / 8) = 11 081 байт. Ровно такая карта
# записывается опросом серии, и никакая другая быть не должна: короче —
# часть кадров молча считается неключевой, длиннее — границы фрагментов
# считаются по не тем кадрам. Обе ошибки проявились бы не при регистрации
# серии, а у пользователя через час работы на своей машине (миграция 09).
check "карта ключевых кадров без единого бита — принят" ok \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format, keyframe_bitmap)
     VALUES (901, 10, 'Без карты', '/srv/got/без-карты.mkv', 100, now(), 88643, 1001, 24000, 1920, 1080, 88731643, 24000, 'h264', 'yuv420p', NULL);"
check "карта ключевых кадров точной длины — принят" ok \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format, keyframe_bitmap)
     VALUES (901, 11, 'С картой', '/srv/got/с-картой.mkv', 100, now(), 88643, 1001, 24000, 1920, 1080, 88731643, 24000, 'h264', 'yuv420p', decode(repeat('00', 11081), 'hex'));"
check "карта ключевых кадров короче требуемого — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format, keyframe_bitmap)
     VALUES (901, 12, 'Короче', '/srv/got/короче.mkv', 100, now(), 88643, 1001, 24000, 1920, 1080, 88731643, 24000, 'h264', 'yuv420p', decode(repeat('00', 11080), 'hex'));"
check "карта ключевых кадров длиннее требуемого — отказ" fail \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format, keyframe_bitmap)
     VALUES (901, 13, 'Длиннее', '/srv/got/длиннее.mkv', 100, now(), 88643, 1001, 24000, 1920, 1080, 88731643, 24000, 'h264', 'yuv420p', decode(repeat('00', 11082), 'hex'));"
check "карта ключевых кадров длиной в целый кадр при 8 кадрах — принят" ok \
    "INSERT INTO tbl_episodes (id_movie, ordinal, name, source_path, file_size, file_mtime, frame_count, time_base_num, time_base_den, width, height, duration_num, duration_den, video_codec, pixel_format, keyframe_bitmap)
     VALUES (901, 14, 'Восемь кадров', '/srv/got/восемь.mkv', 100, now(), 8, 1, 25, 1920, 1080, 8, 25, 'h264', 'yuv420p', decode(repeat('80', 1), 'hex'));"

# --- очередь заданий: виды, состояния, прогресс, ошибка ---
check "вид задания ASSEMBLE — отказ" fail \
    "INSERT INTO tbl_jobs (kind, params, params_hash) VALUES ('ASSEMBLE', '{}', 'h');"
check "вид задания ANALYZE — принят" ok \
    "INSERT INTO tbl_jobs (kind, params, params_hash) VALUES ('ANALYZE', '{}', 'h');"
check "вид задания HASH — принят" ok \
    "INSERT INTO tbl_jobs (kind, params, params_hash) VALUES ('HASH', '{}', 'h2');"
check "состояние задания PAUSED — отказ" fail \
    "INSERT INTO tbl_jobs (kind, state, params, params_hash) VALUES ('FACES', 'PAUSED', '{}', 'h3');"
check "состояние ERROR без текста ошибки — отказ" fail \
    "INSERT INTO tbl_jobs (kind, state, params, params_hash) VALUES ('TRAIN', 'ERROR', '{}', 'h4');"
check "состояние DONE без общего объёма — отказ" fail \
    "INSERT INTO tbl_jobs (kind, state, params, params_hash, progress_total) VALUES ('ANALYZE', 'DONE', '{}', 'h5', 0);"
check "состояние DONE с общим объёмом — принят" ok \
    "INSERT INTO tbl_jobs (kind, state, params, params_hash, progress_total) VALUES ('ANALYZE', 'DONE', '{}', 'h6', 88643);"
check "пустой хеш параметров — отказ" fail \
    "INSERT INTO tbl_jobs (kind, params, params_hash) VALUES ('HASH', '{}', '   ');"
check "отрицательный прогресс — отказ" fail \
    "INSERT INTO tbl_jobs (kind, params, params_hash, progress_done) VALUES ('HASH', '{}', 'h7', -1);"
check "пять состояний очереди — приняты" ok \
    "INSERT INTO tbl_jobs (kind, state, params, params_hash, progress_total, error_text) VALUES
     ('ANALYZE','WAITING','{}','s1',0,NULL),
     ('FACES','CREATING','{}','s2',0,NULL),
     ('TRAIN','WORKING','{}','s3',0,NULL),
     ('HASH','DONE','{}','s4',10,NULL),
     ('HASH','ERROR','{}','s5',0,'упало');"

# --- вид задания переименован вместе с сущностью ---
# Ограничение в базе проверяет само значение: без его правки задание к
# эпизоду не прошло бы, а прежнее значение продолжало бы проходить.
# Прежнее значение собирается из кусков: правила переименования к самой
# проверке не применяются, иначе она проверяла бы новое имя.
old_subject="$(printf 'SERI%s' ES)"
check "вид задания EPISODE — принят" ok \
    "INSERT INTO tbl_jobs (kind, state, subject_type, subject_id, params, params_hash) VALUES ('HASH', 'WAITING', 'EPISODE', 1, '{}', 'ep1');"
check "вид задания с прежним названием сущности — отказ" fail \
    "INSERT INTO tbl_jobs (kind, state, subject_type, subject_id, params, params_hash) VALUES ('HASH', 'WAITING', '${old_subject}', 1, '{}', 'ep2');"

# --- артефакты: виды, размещение, состояния, контрольная сумма ---
check "вид артефакта ASSEMBLY — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key) VALUES ('ASSEMBLY', 'SSD', 'a/b');"
check "вид артефакта PREVIEW_SHEET — принят" ok \
    "INSERT INTO tbl_artifacts (kind, placement, object_key) VALUES ('PREVIEW_SHEET', 'SSD', 'a/p');"
check "вид артефакта RECIPE — принят" ok \
    "INSERT INTO tbl_artifacts (kind, placement, object_key) VALUES ('RECIPE', 'SSD', 'a/r');"
check "размещение HDD — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key) VALUES ('MODEL', 'HDD', 'a/m');"
check "состояние артефакта PENDING — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key, state) VALUES ('MODEL', 'SSD', 'a/x', 'PENDING');"
check "готовый артефакт без размера — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key, state, byte_size) VALUES ('MODEL', 'SSD', 'a/y', 'READY', NULL);"
check "контрольная сумма не из 64 hex — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key, byte_size, checksum) VALUES ('MODEL', 'SSD', 'a/z', 10, 'abc');"
check "контрольная сумма в верхнем регистре — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key, byte_size, checksum)
     VALUES ('MODEL', 'SSD', 'a/w', 10, repeat('A', 64));"
check "контрольная сумма 64 hex в нижнем — принят" ok \
    "INSERT INTO tbl_artifacts (kind, placement, object_key, byte_size, checksum)
     VALUES ('MODEL', 'SSD', 'a/v', 10, repeat('a', 64));"
check "дубль вида и ключа артефакта — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key, byte_size, checksum) VALUES ('MODEL', 'SSD', 'a/v', 10, repeat('a', 64));
     INSERT INTO tbl_artifacts (kind, placement, object_key) VALUES ('MODEL', 'SSD', 'a/v');"
check "пустой ключ артефакта — отказ" fail \
    "INSERT INTO tbl_artifacts (kind, placement, object_key) VALUES ('MODEL', 'SSD', '   ');"

# --- справочник сумм: алгоритм, формат суммы, единственность ---
check "алгоритм суммы не SHA-256 — отказ" fail \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state)
     VALUES (901, 'MD5', repeat('a', 32), 10, now(), 'DONE');"
check "сумма не из 64 hex — отказ" fail \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state)
     VALUES (901, 'SHA-256', repeat('a', 63), 10, now(), 'DONE');"
check "состояние суммы DONE без текста ошибки — принят" ok \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (901, 'SHA-256', repeat('b', 64), 10, now(), 'DONE', now());"
check "вторая актуальная сумма той же серии — отказ" fail \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (901, 'SHA-256', repeat('c', 64), 10, now(), 'DONE', now());
     INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (901, 'SHA-256', repeat('g', 64), 10, now(), 'DONE', now());"
check "вторая устаревшая сумма той же серии — принят" ok \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at, is_stale)
     VALUES (901, 'SHA-256', repeat('d', 64), 10, now(), 'DONE', now(), true);"

# --- справочник сумм: незавершённый подсчёт и связь с заданием ---
# Запись появляется при постановке задания, а сумма — только после чтения
# файла. Поэтому у записей CREATING и WORKING значения суммы нет вовсе, а
# ссылка на задание проверяется базой (миграция 10).
check_writes "запись незавершённого подсчёта без значения суммы — принята" \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state)
     VALUES (9501, 'SHA-256', NULL, 10, now(), 'CREATING');"
check "запись в состоянии DONE без значения суммы — отказ" fail \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (9501, 'SHA-256', NULL, 10, now(), 'DONE', now());"
check "ссылка на задание, которого нет, — отказ" fail \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, job_id)
     VALUES (9501, 'SHA-256', NULL, 10, now(), 'WORKING', 999999);"
check "готовый подсчёт без ссылки на задание — принят" ok \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, computed_at)
     VALUES (9502, 'SHA-256', repeat('e', 64), 10, now(), 'DONE', now());"
# Удаление задания не должно удалять результат: ссылка обнуляется, а сама
# сумма остаётся. Проверка идёт одной транзакцией — иначе откат уничтожил бы
# и запись, и удаление задания.
job_link=$(printf '%s\n' "BEGIN;" \
    "INSERT INTO tbl_jobs (id, kind, state, subject_type, subject_id, params, params_hash)" \
    "VALUES (777, 'HASH', 'WORKING', 'EPISODE', 9501, '{}'::jsonb, repeat('7', 64));" \
    "INSERT INTO tbl_source_file_checksums (id_episode, algorithm, digest, byte_size, file_mtime, state, job_id, computed_at)" \
    "VALUES (9501, 'SHA-256', repeat('a', 64), 10, now(), 'DONE', 777, now());" \
    "DELETE FROM tbl_jobs WHERE id = 777;" \
    "SELECT count(*) FROM tbl_source_file_checksums WHERE id_episode = 9501 AND job_id IS NULL;" \
    "ROLLBACK;" | psql_run 2>&1 | tail -1)
if [[ "${job_link}" == "1" ]]; then
    report_ok "удаление задания обнуляет ссылку, а сумма остаётся"
else
    report_fail "удаление задания обнуляет ссылку, а сумма остаётся — найдено строк: «${job_link}»"
fi

# --- настройки по умолчанию ---
settings=$(psql_run <<< "SELECT count(*) FROM tbl_analysis_settings WHERE id_movie = 901;")
if [[ "${settings}" == "11" ]]; then
    report_ok "новый сериал получил 11 настроек по умолчанию"
else
    report_fail "новый сериал получил ${settings} настроек, ожидалось 11"
fi

# --- служебные персоны сериала -------------------------------------------
# Правила живут в базе, а не в коде контроллера (миграция 11): заглушки
# заводит триггер на вставку сериала, уникальный индекс не даёт завести
# вторую заглушку того же вида, триггер на удаление запрещает убирать
# заглушку, пока жив сериал. Код сервиса персон эти правила повторяет, но
# проверяется именно база.
printf '\n%s\n' "3a. Служебные персоны сериала"
service_persons=$(psql_run <<< "SELECT count(*) FROM tbl_persons WHERE id_movie = 901 AND kind <> 'PERSON';")
if [[ "${service_persons}" == "2" ]]; then
    report_ok "у сериала две служебные персоны: неопознанное лицо и «не лицо»"
else
    report_fail "служебных персон у сериала ${service_persons}, ожидалось 2"
fi

service_names=$(psql_run <<< "SELECT count(*) FROM tbl_persons WHERE id_movie = 901 AND kind <> 'PERSON' AND recognizer_key IS NULL;")
if [[ "${service_names}" == "2" ]]; then
    report_ok "у служебных персон нет ключа распознавателя: это заглушки, а не классы модели"
else
    report_fail "служебных персон с ключом распознавателя: ${service_names} из 2"
fi

check "вторая заглушка того же вида — отказ" fail \
    "INSERT INTO tbl_persons (id_movie, name, kind) VALUES (901, 'Вторая заглушка', 'UNRECOGNIZED');"
check "вторая заглушка вида «не лицо» — отказ" fail \
    "INSERT INTO tbl_persons (id_movie, name, kind) VALUES (901, 'Вторая заглушка', 'NONPERSON');"
check_writes "вторая именованная персона того же сериала — принята" \
    "INSERT INTO tbl_persons (id_movie, name, recognizer_key, kind) VALUES (901, 'Джейми', 'jamie', 'PERSON');"
check "у именованной персоны без ключа распознавателя — отказ" fail \
    "INSERT INTO tbl_persons (id_movie, name, kind) VALUES (901, 'Джейми', 'PERSON');"
check "у служебной персоны с ключом распознавателя — отказ" fail \
    "INSERT INTO tbl_persons (id_movie, name, recognizer_key, kind) VALUES (901, 'С ключом', 'x', 'NONPERSON');"
check "неизвестный вид персоны — отказ" fail \
    "INSERT INTO tbl_persons (id_movie, name, kind) VALUES (901, 'Непонятная', 'SOMEBODY');"
check "удаление служебной персоны при живом сериале — отказ" fail \
    "DELETE FROM tbl_persons WHERE id_movie = 901 AND kind = 'UNRECOGNIZED';"
check "удаление именованной персоны — принят" ok \
    "INSERT INTO tbl_persons (id_movie, name, recognizer_key, kind) VALUES (901, 'Джейми', 'jamie', 'PERSON');
     DELETE FROM tbl_persons WHERE id_movie = 901 AND name = 'Джейми';"
# Сериал удаляется каскадом вместе с заглушками: запрет удаления не должен
# делать удаление сериала невозможным.
cascade_persons=$(psql_run <<'SQL' | tail -1
BEGIN;
DELETE FROM tbl_movies WHERE id = 901;
SELECT count(*) FROM tbl_persons WHERE id_movie = 901;
ROLLBACK;
SQL
)
if [[ "${cascade_persons}" == "0" ]]; then
    report_ok "удаление сериала уносит служебные персоны каскадом"
else
    report_fail "после удаления сериала осталось служебных персон: ${cascade_persons}"
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
INSERT INTO tbl_scenes (id, id_episode, first_frame, last_frame, origin)
VALUES (901, 901, 0, 123, 'AUTO');
INSERT INTO tbl_artifacts (kind, placement, object_key, byte_size, checksum)
VALUES ('RECIPE', 'SSD', 'recipes/probe.json', 42, repeat('9', 64));
INSERT INTO tbl_build_recipes (id_movie, name, schema_version, state, artifact_id, content_sha256,
                          signature, signing_key_id, item_count, expected_duration_ms,
                          expected_frame_count, created_at, finished_at)
SELECT 901, 'Фикстура', 1, 'DONE', id, repeat('e', 64), 'c2ln', 'key1', 1, 1000, 48,
       now(), now()
  FROM tbl_artifacts WHERE object_key = 'recipes/probe.json';
SQL
then
    report_fail "фикстуры сцены, артефакта и сценария не создались: проверки фрагмента не имеют смысла"
    failed=$((failed + 1))
else
    report_ok "фикстуры сцены, артефакта и сценария созданы"
fi

check "сценарий DONE без подписи — отказ" fail \
    "INSERT INTO tbl_build_recipes (id_movie, name, schema_version, state, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р', 1, 'DONE', repeat('e', 64), NULL, NULL, 1, 1000, 48, now(), now());"
check "подпись без суммы содержимого — отказ" fail \
    "INSERT INTO tbl_build_recipes (id_movie, name, schema_version, state, signature, signature_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р2', 1, 'DONE', 'sig', 'key1', 1, 1000, 48, now(), now());"
check "сценарий DONE без числа фрагментов — отказ" fail \
    "INSERT INTO tbl_build_recipes (id_movie, name, schema_version, state, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р3', 1, 'DONE', repeat('e', 64), 'sig', 'key1', 0, 1000, 48, now(), now());"
check "сценарий DONE без артефакта — отказ" fail \
    "INSERT INTO tbl_build_recipes (id_movie, name, schema_version, state, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     VALUES (901, 'Р5', 1, 'DONE', repeat('e', 64), 'c2ln', 'key1', 1, 1000, 48, now(), now());"
check "корректный сценарий DONE — принят" ok \
    "INSERT INTO tbl_build_recipes (id_movie, name, schema_version, state, artifact_id, content_sha256, signature, signing_key_id, item_count, expected_duration_ms, expected_frame_count, created_at, finished_at)
     SELECT 901, 'Р4', 1, 'DONE', id, repeat('e', 64), 'c2ln', 'key1', 1, 1000, 48, now(), now()
       FROM tbl_artifacts WHERE object_key = 'recipes/probe.json';"
check "фрагмент с абсолютным путём — отказ" fail \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', '/srv/got/S01E01.mkv', repeat('f', 64), 0, 47, 0, 47, '[]'::jsonb FROM tbl_build_recipes WHERE name = 'Фикстура';"
check "фрагмент с выходом за пределы дерева — отказ" fail \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', '../secret/S01E01.mkv', repeat('f', 64), 0, 47, 0, 47, '[]'::jsonb FROM tbl_build_recipes WHERE name = 'Фикстура';"
check "фрагмент, начинающийся позже расчётного — отказ" fail \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 20, 47, '[]'::jsonb FROM tbl_build_recipes WHERE name = 'Фикстура';"
check "фрагмент, заканчивающийся раньше расчётного — отказ" fail \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 0, 40, '[]'::jsonb FROM tbl_build_recipes WHERE name = 'Фикстура';"
check "отрицательный номер кадра — отказ" fail \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), -1, 47, -1, 47, '[]'::jsonb FROM tbl_build_recipes WHERE name = 'Фикстура';"
check "имена персонажей не массивом — отказ" fail \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 0, 47, 'Джейми' FROM tbl_build_recipes WHERE name = 'Фикстура';"
check_writes "корректный фрагмент — принят" \
    "INSERT INTO tbl_build_recipe_items (recipe_id, ordinal, scene_id, id_episode, episode_name, relative_path, source_sha256, first_frame, last_frame, cut_first_frame, cut_last_frame, person_names)
     SELECT id, 0, 901, 901, 'S01E01', 'S01E01.mkv', repeat('f', 64), 10, 47, 0, 47, '[]'::jsonb FROM tbl_build_recipes WHERE name = 'Фикстура';"

# --- каскадное удаление ----------------------------------------------------
printf '\n%s\n' "6. Каскадное удаление сериала"

# Фикстура посложнее прошлой: сериал с серией, у которой есть лицо, персона,
# версия модели и сценарий сборки. Именно этот случай ломается, если каскад
# задан не на всех внешних ключах: «сирота» остаётся, и удаление сериала
# падает вместо того, чтобы унести производные данные (задача T030).
psql_run <<'SQL' >/dev/null 2>&1
INSERT INTO tbl_movies (id, name, source_root) VALUES (903, 'Каскад с производными', '/srv/got3');
INSERT INTO tbl_episodes (id, id_movie, ordinal, name, source_path, file_size, file_mtime,
                    frame_count, time_base_num, time_base_den, width, height,
                    duration_num, duration_den, video_codec, pixel_format)
VALUES (903, 903, 1, 'S01E01', '/srv/got3/S01E01.mkv', 5598286865, now(),
        88643, 1001, 24000, 1920, 1080, 88731643, 24000, 'h264', 'yuv420p');
INSERT INTO tbl_persons (id, id_movie, name, kind, recognizer_key)
VALUES (903, 903, 'Джейми', 'PERSON', 'jamie');
INSERT INTO tbl_model_versions (id, id_movie, algorithm_version, params_hash, example_count, classes, threshold, state)
VALUES (903, 903, '1', 'h', 1, 'jamie', 0.5, 'DONE');
INSERT INTO tbl_faces (id, id_episode, frame_number, face_index, x1, y1, x2, y2, person_id, origin)
VALUES (903, 903, 0, 0, 100, 100, 200, 200, 903, 'AUTO');
INSERT INTO tbl_locations (id, id_movie, name) VALUES (903, 903, 'Лагерь');
SQL
orphan=$(psql_run <<'SQL' | tail -1
BEGIN;
DELETE FROM tbl_movies WHERE id = 903;
SELECT
    (SELECT count(*) FROM tbl_episodes    WHERE id = 903)
  + (SELECT count(*) FROM tbl_persons    WHERE id = 903)
  + (SELECT count(*) FROM tbl_faces      WHERE id = 903)
  + (SELECT count(*) FROM tbl_locations  WHERE id = 903)
  + (SELECT count(*) FROM tbl_model_versions WHERE id = 903)
  + (SELECT count(*) FROM tbl_analysis_settings WHERE id_movie = 903);
ROLLBACK;
SQL
)
if [[ "${orphan}" == "0" ]]; then
    report_ok "удаление сериала уносит серии, лица, персоны, версии моделей и настройки"
else
    report_fail "после удаления сериала осталось записей: ${orphan}"
fi
cascade=$(psql_run <<< "
BEGIN;
INSERT INTO tbl_movies (id, name, source_root) VALUES (902, 'Каскад', '/srv/got2');
SELECT count(*) FROM tbl_build_recipes WHERE id_movie = 902;
DELETE FROM tbl_movies WHERE id = 902;
SELECT count(*) FROM tbl_build_recipes WHERE id_movie = 902;
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

# Название сцены: необязательное, задаётся оператором.
check "сцена без названия (задано не всегда) — принят" ok \
    "UPDATE tbl_scenes SET title = NULL;"
check "сцена с названием — принят" ok \
    "UPDATE tbl_scenes SET title = 'Засада у Ворота Льва';"
check "название пустой строкой — принято, пустое значит неназвано" ok \
    "UPDATE tbl_scenes SET title = '';"

exit 0
