#!/usr/bin/env bash
# SYP — прогон тестов, требующих живой базы.
#
# Поднимает одноразовый контейнер `postgres:16` со свободным портом на
# localhost, применяет миграции `deploy/syp-db/NN_*.sql`, передаёт параметры
# подключения в окружение и запускает тесты Gradle. Контейнер удаляется при
# выходе.
#
# Контейнеры SYP при этом не создаются и не трогаются.
#
# Прогоняются модули с проверками, требующими живой базы: `syp-core`
# (контрактные тесты очереди) и `syp-admin-app` (домен каталога). Модуль без
# таких проверок в список не попадает, иначе прогон молчал бы о пропущенном.
#
# Использование:
#   bash tools/run-db-tests.sh            прогнать тесты обоих модулей
#   bash tools/run-db-tests.sh --keep     оставить контейнер для разбора
#
# Код возврата: 0 — тесты прошли, иначе — код Gradle.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}" || exit 1

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/nsa/syp/.gradle}"
export DOCKER_CONFIG="${DOCKER_CONFIG:-/home/nsa/syp/.docker}"

NAME="syp-testdb-$$"
DB_PASSWORD="syp-test-password"
KEEP=0
if [[ "${1:-}" == "--keep" ]]; then
    KEEP=1
    shift
fi

cleanup() {
    if [[ ${KEEP} -eq 0 ]]; then
        docker rm -f "${NAME}" >/dev/null 2>&1
    else
        printf '%s\n' "контейнер ${NAME} оставлен для разбора" >&2
    fi
}
trap cleanup EXIT

printf '%s\n' "=== SYP: тесты, требующие живой базы ==="
printf '%s\n' "контейнер: ${NAME} (postgres:16, одноразовый)"

# Порт публикуется на localhost со свободным номером: -p 127.0.0.1::5432
# просит docker выбрать свободный порт сам, что не занимает порты стека SYP.
if ! docker run -d --rm --name "${NAME}" -p 127.0.0.1::5432 \
    -e POSTGRES_PASSWORD="${DB_PASSWORD}" -e POSTGRES_DB=syp postgres:16 >/dev/null 2>&1; then
    printf '%s\n' "НЕ УДАЛОСЬ ЗАПУСТИТЬ КОНТЕЙНЕР postgres:16" >&2
    exit 1
fi

for _ in $(seq 1 60); do
    docker exec "${NAME}" pg_isready -U postgres -d syp >/dev/null 2>&1 && break
    sleep 1
done

PORT_NUMBER="$(docker port "${NAME}" 5432/tcp 2>/dev/null | head -1)"
PORT_NUMBER="${PORT_NUMBER##*:}"
if [[ -z "${PORT_NUMBER}" ]]; then
    printf '%s\n' "НЕ УДАЛОСЬ ОПРЕДЕЛИТЬ ПОРТ КОНТЕЙНЕРА" >&2
    exit 1
fi

printf '%s\n' "применяю миграции"
for file in deploy/syp-db/[0-9][0-9]_*.sql; do
    if ! docker exec -i "${NAME}" psql -U postgres -d syp -v ON_ERROR_STOP=1 -q -f - < "${file}"; then
        printf '%s\n' "ОШИБКА применения $(basename "${file}")" >&2
        exit 1
    fi
    printf '  %s\n' "$(basename "${file}")"
done

export SYP_TEST_DB_URL="jdbc:postgresql://127.0.0.1:${PORT_NUMBER}/syp"
export SYP_TEST_DB_USER="postgres"
export SYP_TEST_DB_PASSWORD="${DB_PASSWORD}"

printf '%s\n' "подключение: ${SYP_TEST_DB_URL}"
printf '%s\n' "запускаю тесты"

# Модули перечислены явно: молчаливый пропуск модуля с проверками против базы
# выглядел бы как «проверок нет», а не как «модуль не прогнан».
MODULES=(:syp-core:test :syp-admin-app:test)
./gradlew "${MODULES[@]}" --rerun-tasks "$@"
status=$?

printf '%s\n' "============================================================"
if [[ ${status} -eq 0 ]]; then
    printf '%s\n' "ТЕСТЫ ПРОЙДЕНЫ"
else
    printf '%s\n' "ТЕСТЫ ПРОВАЛЕНЫ, код ${status}"
fi
exit ${status}
