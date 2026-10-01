#!/usr/bin/env bash
# SYP — сверка посчитанной суммы исходника с внешней (задача T045, замер М-11).
#
# Поднимает одноразовый контейнер `postgres:16`, применяет миграции и
# запускает проверку `ChecksumParityTest` на настоящем файле серии: система
# считает сумму заданием `HASH` через воркер, а результат сверяется с выводом
# `sha256sum` — той самой программой, которой воспользуется воркер на машине
# пользователя.
#
# Контейнеры SYP при этом не создаются и не трогаются. Контейнер базы
# одноразовый и удаляется при выходе.
#
# Использование:
#   bash tools/run-checksum-parity.sh                       файл серии по умолчанию
#   bash tools/run-checksum-parity.sh /path/to/series.mkv   свой файл
#
# Код возврата: 0 — суммы совпали, иначе — код Gradle.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/nsa/syp/.gradle}"
export DOCKER_CONFIG="${DOCKER_CONFIG:-/home/nsa/syp/.docker}"

SERIES="${1:-/disks/HDD_16Tb_Clouds/GOT/GOT.S01/GOT.S01E01.BDRip.1080p.mkv}"

if [[ ! -f "${SERIES}" ]]; then
    printf 'ФАЙЛА СЕРИИ НЕТ: %s\n' "${SERIES}" >&2
    printf 'Укажите путь к файлу первым аргументом\n' >&2
    exit 1
fi

for program in ffprobe sha256sum; do
    if ! command -v "${program}" >/dev/null 2>&1; then
        printf 'ПРОГРАММА НЕ НАЙДЕНА: %s нужна для сверки\n' "${program}" >&2
        exit 1
    fi
done

NAME="syp-parity-$$"
DB_PASSWORD="syp-parity-password"

cleanup() {
    docker rm -f "${NAME}" >/dev/null 2>&1
}
trap cleanup EXIT

printf '%s\n' "=== SYP: сверка суммы исходника с внешней ==="
printf 'файл серии: %s\n' "${SERIES}"
printf 'размер: %s байт\n' "$(stat -c '%s' "${SERIES}")"
printf 'контейнер: %s (postgres:16, одноразовый)\n' "${NAME}"

if ! docker run -d --rm --name "${NAME}" -p 127.0.0.1::5432 \
    -e POSTGRES_PASSWORD="${DB_PASSWORD}" -e POSTGRES_DB=syp postgres:16 >/dev/null 2>&1; then
    printf '%s\n' "НЕ УДАЛОСЬ ЗАПУСТИТЬ КОНТЕЙНЕР postgres:16" >&2
    exit 1
fi

# Готовность — «запрос проходит», и подряд два раза: первый успех может
# прийти на середине старта сервера.
ready=0
for _ in $(seq 1 90); do
    if docker exec "${NAME}" psql -U postgres -d syp -tA -c 'SELECT 1' >/dev/null 2>&1 &&
        docker exec "${NAME}" psql -U postgres -d syp -tA -c 'SELECT 1' >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 1
done
if [[ ${ready} -ne 1 ]]; then
    printf '%s\n' "БАЗА НЕ ПОДНЯЛАСЬ за 90 секунд" >&2
    exit 1
fi

PORT_NUMBER="$(docker port "${NAME}" 5432/tcp 2>/dev/null | head -1)"
PORT_NUMBER="${PORT_NUMBER##*:}"
if [[ -z "${PORT_NUMBER}" ]]; then
    printf '%s\n' "НЕ УДАЛОСЬ ОПРЕДЕЛИТЬ ПОРТ КОНТЕЙНЕРА" >&2
    exit 1
fi

printf '%s\n' "применяю миграции"
for file in deploy/syp-db/[0-9][0-9]_*.sql; do
    if ! docker exec -i "${NAME}" psql -U postgres -d syp -v ON_ERROR_STOP=1 -q -f - < "${file}"; then
        printf 'ОШИБКА применения %s\n' "$(basename "${file}")" >&2
        exit 1
    fi
done

export SYP_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${PORT_NUMBER}/syp"
export SYP_TEST_DB_USER="postgres"
export SYP_TEST_DB_PASSWORD="${DB_PASSWORD}"
export SYP_SOURCE_SERIES="${SERIES}"

printf '%s\n' "запускаю сверку (три прогона подсчёта)"
./gradlew :syp-admin-app:test --tests '*ChecksumParityTest*' --rerun-tasks -i
status=$?

printf '%s\n' "============================================================"
if [[ ${status} -eq 0 ]]; then
    printf '%s\n' "СУММА СОВПАЛА С SHA256SUM"
else
    printf '%s\n' "СУММА НЕ СОВПАЛА, код ${status}"
fi
exit ${status}
