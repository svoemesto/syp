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

# Ожидание готовности: `pg_isready` отвечает и на той стадии, когда сервер ещё
# только запускается и уже сворачивает предыдущую инициализацию. Поэтому
# готовым считается не «порт отвечает», а «запрос проходит» — и подряд два
# раза: первый успех может прийти на середине старта.
ready=0
for _ in $(seq 1 90); do
    if docker exec "${NAME}" psql -U postgres -d syp -tA -c 'SELECT 1' >/dev/null 2>&1; then
        if docker exec "${NAME}" psql -U postgres -d syp -tA -c 'SELECT 1' >/dev/null 2>&1; then
            ready=1
            break
        fi
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

# ---------------------------------------------------------------------------
# Две базы, а не одна.
#
# Модули тестируются параллельно (`org.gradle.parallel=true` в
# gradle.properties), и при общей базе они мешают друг другу: контрактные
# тесты очереди очищают таблицу заданий перед каждым случаем, а проверки
# домена каталога и справочника сумм к этому моменту уже создали свои
# задания — и получают отказ по внешнему ключу на задании, которого уже нет.
# Это не «медленнее», а неверно: чужой тест не должен ломать свой.
#
# База `syp` достаётся админскому модулю, `syp_core` — общему. Миграции
# применяются в обе: схема у них одна и та же.
# ---------------------------------------------------------------------------
CORE_DB="syp_core"

printf '%s\n' "применяю миграции"
# Ошибка применения повторяется один раз: на холодном старте одноразовый
# контейнер может оборвать соединение, и без повтора прогон падал бы на
# собственном окружении, а не на коде.
for database in syp "${CORE_DB}"; do
    docker exec "${NAME}" psql -U postgres -d postgres -q -v ON_ERROR_STOP=1 \
        -c "CREATE DATABASE ${database}" >/dev/null 2>&1
    for file in deploy/syp-db/[0-9][0-9]_*.sql; do
        applied=0
        for attempt in 1 2; do
            if docker exec -i "${NAME}" psql -U postgres -d "${database}" \
                -v ON_ERROR_STOP=1 -q -f - < "${file}"; then
                applied=1
                break
            fi
            printf '  %s: попытка %s не удалась, жду и повторяю\n' "${database}" "${attempt}" >&2
            sleep 3
        done
        if [[ ${applied} -ne 1 ]]; then
            printf '%s\n' "ОШИБКА применения $(basename "${file}") в базе ${database}" >&2
            exit 1
        fi
    done
    printf '  база %s: миграции применены\n' "${database}"
done

export SYP_TEST_DB_USER="postgres"
export SYP_TEST_DB_PASSWORD="${DB_PASSWORD}"
ADMIN_URL="jdbc:postgresql://127.0.0.1:${PORT_NUMBER}/syp"
CORE_URL="jdbc:postgresql://127.0.0.1:${PORT_NUMBER}/${CORE_DB}"

printf '%s\n' "подключения:"
printf '%s\n' "  syp-core     ${CORE_URL}"
printf '%s\n' "  syp-admin-app ${ADMIN_URL}"
printf '%s\n' "запускаю тесты модулей по очереди: у каждого своя база"

# Модули перечислены явно: молчаливый пропуск модуля с проверками против базы
# выглядел бы как «проверок нет», а не как «модуль не прогнан». Каждый прогон
# получает свою базу — общей они пользоваться не могут (см. выше).
status=0

printf '%s\n' "--- syp-core ---"
SYP_TEST_DB_URL="${CORE_URL}" ./gradlew :syp-core:test --rerun-tasks "$@" || status=$?

printf '%s\n' "--- syp-admin-app ---"
SYP_TEST_DB_URL="${ADMIN_URL}" ./gradlew :syp-admin-app:test --rerun-tasks "$@" || status=$?

printf '%s\n' "============================================================"
if [[ ${status} -eq 0 ]]; then
    printf '%s\n' "ТЕСТЫ ПРОЙДЕНЫ"
else
    printf '%s\n' "ТЕСТЫ ПРОВАЛЕНЫ, код ${status}"
fi
exit ${status}
