#!/usr/bin/env bash
# SYP — единственная точка сборки, запуска и перезапуска контейнеров.
#
# Прямой `docker restart` запрещён (AGENTS.md § Tier-1 Hard Gate
# Build/Deploy/Containers, правило R-374): перезапуск мимо этого скрипта
# теряет зависимости между контейнерами и обходит согласие владельца.
# Guard `tools/check-container-restart.sh` проверяет это правило по исходникам.
#
# Перед перезапуском контейнера с заданиями действует отдельный порядок:
# убедиться, что активных заданий нет, остановить очередь, дождаться
# фактической остановки, и только затем перезапускать (Hard Gate «Очередь
# перед перезапуском»).
#
# Использование:
#   bash deploy/do.sh status
#   bash deploy/do.sh build_admin_app
#   bash deploy/do.sh start_admin_app | stop_admin_app | restart_admin_app
#   bash deploy/do.sh up | down
#   bash deploy/do.sh db_migrate
#   bash deploy/do.sh keys_generate

set -euo pipefail

# --- Инициализация ---------------------------------------------------------

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${DEPLOY_DIR}/.." && pwd)"
cd "${DEPLOY_DIR}"

# Docker только с DOCKER_CONFIG: в песочнице агента каталог ~/.docker
# доступен только для чтения (AGENTS.md, Hard Gate R-373).
export DOCKER_CONFIG="${DOCKER_CONFIG:-/home/nsa/syp/.docker}"

# Gradle только с GRADLE_USER_HOME по той же причине (Hard Gate R-372).
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/nsa/syp/.gradle}"

COMPOSE_FILE="${DEPLOY_DIR}/docker-compose.yml"
ENV_FILE="${DEPLOY_DIR}/.env"

# Имена шести контейнеров стека. Седьмого быть не должно: воркер на машине
# пользователя контейнером SYP не является.
CONTAINERS=(
    syp-db
    syp-storage
    syp-admin-app
    syp-admin-web
    syp-public-app
    syp-public-web
)

# --- Служебное -------------------------------------------------------------

# Печать предупреждения о секретах: наружу выводятся имена переменных,
# но не их значения (constitution VIII.6).
warn() {
    printf '%s\n' "ВНИМАНИЕ: $*" >&2
}

info() {
    printf '%s\n' "$*"
}

require_env_file() {
    if [[ ! -f "${ENV_FILE}" ]]; then
        warn "нет файла ${ENV_FILE}"
        warn "создайте его из примера: cp ${DEPLOY_DIR}/.env.example ${ENV_FILE}"
        warn "файл окружения вне системы контроля версий (constitution VIII.2)"
        return 1
    fi
    return 0
}

# Сборка бэкенда Gradle выполняется на машине администратора: образ
# снимается с уже собранного jar-файла, поэтому зависимости разрешаются
# один раз и в кэше Gradle, а не в каждой сборке образа.
build_backend() {
    local module="$1"
    info "сборка бэкенда: ${module}"
    cd "${ROOT_DIR}"
    ./gradlew --no-daemon "${module}:bootJar"
    cd "${DEPLOY_DIR}"
}

build_frontend() {
    local dir="$1"
    info "сборка фронтенда: ${dir}"
    # Фронтенд собирается только из своего каталога: в корне репозитория
    # файла package.json нет, npm run из корня не работает (Hard Gate R-375).
    cd "${ROOT_DIR}/${dir}"
    npm run build
    cd "${DEPLOY_DIR}"
}

compose() {
    require_env_file || return 1
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

# --- Сборка ----------------------------------------------------------------

# Отказывает ли собирать устаревшее дерево: без этой проверки образ молча
# собирается из кода, отставшего от origin/master. Так потерян один запуск
# подряд: правка эндпоинта была слита, а образ собрался из рабочей копии
# на несколько коммитов старше, и эндпоинт по-прежнему не отвечал по
# объявленному адресу. Сборка выглядела исправной, а в образе её не было.
check_source_is_fresh() {
    local behind
    behind="$(git -C "${ROOT_DIR}" rev-list --count HEAD..origin/master 2>/dev/null || echo "")"
    if [[ -z "${behind}" ]]; then
        printf '%s\n' "не удалось сверить дерево с origin/master — проверка пропущена"
        return 0
    fi
    if [[ "${behind}" -gt 0 ]]; then
        printf '%s\n' "ОТКАЗ: дерево отстаёт от origin/master на ${behind} коммит(ов)." >&2
        printf '%s\n' "       Образ будет собран из устаревшего кода. Сначала:" >&2
        printf '%s\n' "         git fetch origin && git reset --hard origin/master" >&2
        printf '%s\n' "       Либо сознательно соберите из текущего состояния: SYP_ALLOW_STALE_BUILD=1" >&2
        exit 1
    fi
    local dirty
    dirty="$(git -C "${ROOT_DIR}" status --porcelain --untracked-files=no 2>/dev/null | wc -l)"
    if [[ "${dirty}" -gt 0 && "${SYP_ALLOW_STALE_BUILD:-0}" != "1" ]]; then
        printf '%s\n' "ВНИМАНИЕ: в дереве ${dirty} незакоммиченных изменений — в образ попадёт именно они." >&2
    fi
}


cmd_build_admin_app() {
    check_source_is_fresh
    build_backend syp-admin-app
    docker build --file "${DEPLOY_DIR}/Dockerfile.backend" \
        --build-arg SYP_MODULE=syp-admin-app \
        --tag svoemesto/syp-admin-app:local "${ROOT_DIR}"
}

cmd_build_public_app() {
    check_source_is_fresh
    build_backend syp-public-app
    docker build --file "${DEPLOY_DIR}/Dockerfile.backend" \
        --build-arg SYP_MODULE=syp-public-app \
        --tag svoemesto/syp-public-app:local "${ROOT_DIR}"
}

cmd_build_admin_web() {
    check_source_is_fresh
    build_frontend syp-admin-web
    # Значения подписки SSE идут в сборку аргументами: это параметры
    # развёртывания, и их правка не должна требовать правки исходников.
    docker build --file "${DEPLOY_DIR}/Dockerfile.frontend" \
        --build-arg VITE_SSE_MAX_RECONNECT_ATTEMPTS="${SYP_SSE_MAX_RECONNECT_ATTEMPTS:-5}" \
        --build-arg VITE_SSE_RECONNECT_BASE_MS="${SYP_SSE_RECONNECT_BASE_MS:-1000}" \
        --build-arg VITE_SSE_RECONNECT_MAX_MS="${SYP_SSE_RECONNECT_MAX_MS:-30000}" \
        --tag svoemesto/syp-admin-web:local "${ROOT_DIR}/syp-admin-web"
}

cmd_build_public_web() {
    check_source_is_fresh
    build_frontend syp-public-web
    docker build --file "${DEPLOY_DIR}/Dockerfile.frontend" \
        --tag svoemesto/syp-public-web:local "${ROOT_DIR}/syp-public-web"
}

cmd_build() {
    cmd_build_admin_app
    cmd_build_public_app
    cmd_build_admin_web
    cmd_build_public_web
}

# --- Запуск ----------------------------------------------------------------

cmd_start() {
    local name="$1"
    compose up -d "${name}"
    info "запущен контейнер ${name}"
}

cmd_stop() {
    local name="$1"
    compose stop "${name}"
    info "остановлен контейнер ${name}"
}

# Перезапуск через stop и start, а не через `docker restart`: только так
# сохраняются связи контейнеров и зависимости из compose-файла (R-374).
cmd_restart() {
    local name="$1"
    warn "перед перезапуском проверьте очередь: в работе не должно быть заданий"
    warn "CREATING, WORKING — см. Hard Gate «Очередь перед перезапуском»"
    compose stop "${name}"
    compose up -d "${name}"
    info "перезапущен контейнер ${name}"
}

cmd_up() {
    compose up -d
    info "подняты шесть контейнеров SYP"
}

cmd_down() {
    compose down
    info "остановлены контейнеры SYP"
}

cmd_status() {
    printf '%s\n' "--- контейнеры SYP ---"
    local found=0
    local name
    for name in "${CONTAINERS[@]}"; do
        if docker inspect -f '{{.State.Status}}' "${name}" >/dev/null 2>&1; then
            info "$(printf '%-16s %s' "${name}" "$(docker inspect -f '{{.State.Status}}' "${name}")")"
            found=$((found + 1))
        else
            info "$(printf '%-16s %s' "${name}" "нет")"
        fi
    done
    info "найдено контейнеров SYP: ${found} из 6"
    require_env_file || true
}

# --- База ------------------------------------------------------------------

# Миграции применяются вручную и строго по номеру. Автозапуск при старте
# контейнера запрещён: схемой управляют только файлы deploy/syp-db/NN_*.sql,
# добавление номерами (constitution III).
# Выполняет SQL и отбрасывает вывод: так ведутся служебные запросы учёта.
psql_q() {
    docker exec -i syp-db \
        psql -U "${SYP_DB_USER:-syp}" -d "${SYP_DB_NAME:-syp}" \
        -v ON_ERROR_STOP=1 -qAt -f - <<< "$1" 2>/dev/null
}

# Применяет файл миграции целиком; при ошибке возвращает ненулевой код, чтобы
# вызывающий не пометил файл применённым.
psql_apply() {
    docker exec -i syp-db \
        psql -U "${SYP_DB_USER:-syp}" -d "${SYP_DB_NAME:-syp}" \
        -v ON_ERROR_STOP=1 -q -f - < "$1"
}

cmd_db_migrate() {
    require_env_file || return 1
    local db="${SYP_DB_NAME:-syp}" user="${SYP_DB_USER:-syp}"
    local file name sum applied stored

    # Учёт появляется сам собой: до него миграции игрались с первого файла и
    # падали на первой же, обращавшейся к таблице, которую позже переименовали.
    psql_q "CREATE TABLE IF NOT EXISTS tbl_migration_ledger (
        id BIGSERIAL PRIMARY KEY,
        name TEXT NOT NULL UNIQUE,
        checksum TEXT NOT NULL,
        applied_at TIMESTAMPTZ NOT NULL DEFAULT now())"

    for file in "${DEPLOY_DIR}"/syp-db/[0-9][0-9]_*.sql; do
        [[ -e "${file}" ]] || continue
        name="$(basename "${file}")"
        sum="$(sha256sum "${file}" | cut -d' ' -f1)"
        stored="$(psql_q "SELECT coalesce(checksum, '') FROM tbl_migration_ledger WHERE name = '${name}'")"
        if [[ "${name}" == "23_migration_ledger.sql" ]]; then
            # Таблицу учёта не помечаем: ею только что создали, и при
            # следующем запуске файл всё равно должен пройти.
            info "применяю ${name}"
            psql_apply "${file}"
            continue
        fi
        if [[ -n "${stored}" ]]; then
            if [[ "${stored}" != "${sum}" ]]; then
                warn "ВНИМАНИЕ: ${name} уже применён, но файл изменился (сумма ${sum:0:12} против ${stored:0:12})"
                warn "  Миграции не переписывают: добавьте новый файл, этот оставьте как был."
                return 1
            fi
            info "уже применён: ${name}"
            continue
        fi
        info "применяю ${name}"
        psql_apply "${file}" || return 1
        psql_q "INSERT INTO tbl_migration_ledger (name, checksum)
                VALUES ('${name}', '${sum}') ON CONFLICT (name) DO NOTHING"
    done
    info "миграции применены"
}

# --- Ключи подписи ---------------------------------------------------------

# Пара ключей Ed25519. Закрытый ключ печатается в stdout один раз и
# кладётся в deploy/.env оператором; в репозиторий он не попадает
# (constitution VIII).
cmd_keys_generate() {
    local dir key_id
    dir="$(mktemp -d)"
    key_id="${1:-key1}"
    openssl genpkey -algorithm ED25519 -out "${dir}/private.pem" 2>/dev/null
    openssl pkey -in "${dir}/private.pem" -pubout -out "${dir}/public.pem" 2>/dev/null
    info "идентификатор ключа: ${key_id}"
    info "закрытый ключ (base64, в SYP_SIGNING_PRIVATE_KEY):"
    base64 -w0 < "${dir}/private.pem"
    printf '\n'
    info "открытый ключ (base64, в SYP_SIGNING_PUBLIC_KEY):"
    base64 -w0 < "${dir}/public.pem"
    printf '\n'
    info "момент начала действия ключа (в SYP_SIGNING_KEY_NOT_BEFORE):"
    date -u +%Y-%m-%dT%H:%M:%SZ
    rm -rf "${dir}"
    warn "закрытый ключ показан один раз и нигде не сохранён"
}

# --- Диспетчер -------------------------------------------------------------

usage() {
    cat <<'USAGE'
Использование: bash deploy/do.sh <команда> [имя_контейнера]

Сборка:
  build_admin_app          собрать образ бэкенда админки
  build_public_app         собрать образ бэкенда публичной части
  build_admin_web          собрать образ фронтенда админки
  build_public_web         собрать образ фронтенда публичной части
  build                    собрать все четыре образа

Запуск (имя контейнера обязательно):
  start_<контейнер>        запустить контейнер
  stop_<контейнер>         остановить контейнер
  restart_<контейнер>      перезапустить контейнер через stop и start
  up                       поднять все шесть контейнеров
  down                     остановить все контейнеры
  status                   состояние шести контейнеров

База и ключи:
  db_migrate               применить миграции deploy/syp-db по номеру
  keys_generate [id]       создать пару ключей Ed25519, вывести в stdout

Контейнеры стека:
  syp-db syp-storage syp-admin-app syp-admin-web syp-public-app syp-public-web
USAGE
}

main() {
    local command="${1:-}"
    [[ $# -gt 0 ]] && shift || true

    case "${command}" in
        build_admin_app) cmd_build_admin_app ;;
        build_public_app) cmd_build_public_app ;;
        build_admin_web) cmd_build_admin_web ;;
        build_public_web) cmd_build_public_web ;;
        build) cmd_build ;;
        start_*)
            cmd_start "${command#start_}"
            ;;
        stop_*)
            cmd_stop "${command#stop_}"
            ;;
        restart_*)
            cmd_restart "${command#restart_}"
            ;;
        up) cmd_up ;;
        down) cmd_down ;;
        status) cmd_status ;;
        db_migrate) cmd_db_migrate ;;
        keys_generate) cmd_keys_generate "$@" ;;
        ""|-h|--help|help) usage ;;
        *)
            warn "неизвестная команда: ${command}"
            usage
            return 1
            ;;
    esac
}

main "$@"
