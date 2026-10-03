#!/usr/bin/env bash
# Проверка адресов админского API на живом стенде.
#
# Зачем: несуществующий адрес обязан отвечать 404, а не 500. Иначе опечатка в
# адресе не отличима от падения сервера — и то и другое приходит как «внутренняя
# ошибка сервера», и чинить начинают не то. Проверка на живом стенде, потому что
# поднять HTTP-слой без всей базы в тестах нельзя: веб-срез тянет все
# контроллеры, а полный контекст требует переменных развёртывания.
#
# Проверка доказывает ровно две вещи, обе на живом стенде:
#   1. настоящий адрес отвечает как прежде, а не 404;
#   2. несуществующий адрес отвечает 404 с кодом NOT_FOUND.
set -uo pipefail

BASE_URL="${SYP_ADMIN_API_URL:-http://127.0.0.1:7911}"
status() { curl -s -o /dev/null -w '%{http_code}' --max-time 20 "$1"; }

fail=0

# 1. Настоящий адрес жив.
real_status="$(status "$BASE_URL/api/jobs")"
case "$real_status" in
200 | 401 | 403)
    echo "OK: настоящий адрес /api/jobs отвечает $real_status"
    ;;
*)
    echo "ОШИБКА: настоящий адрес /api/jobs отвечает $real_status, а не 200/401/403"
    fail=1
    ;;
esac

# 2. Несуществующий адрес — 404 с кодом NOT_FOUND, а не 500.
missing_body="$(curl -s --max-time 20 "$BASE_URL/api/адреса-такого-нет")"
missing_status="$(status "$BASE_URL/api/адреса-такого-нет")"
if [ "$missing_status" = "404" ] && printf '%s' "$missing_body" | grep -q 'NOT_FOUND'; then
    echo "OK: несуществующий адрес отвечает 404 с кодом NOT_FOUND"
else
    echo "ОШИБКА: несуществующий адрес отвечает $missing_status, тело: $missing_body"
    fail=1
fi

# 3. То же самое на публичной части: воркер пользователя ходит туда же.
public_url="${SYP_PUBLIC_API_URL:-http://127.0.0.1:7913}"
public_real="$(status "$public_url/api/recipes/verification-key")"
public_missing_status="$(status "$public_url/api/адреса-такого-нет")"
public_missing_body="$(curl -s --max-time 20 "$public_url/api/адреса-такого-нет")"
if [ "$public_real" = "200" ] && [ "$public_missing_status" = "404" ] \
    && printf '%s' "$public_missing_body" | grep -q 'NOT_FOUND'; then
    echo "OK: публичная часть — настоящий адрес 200, несуществующий 404 с NOT_FOUND"
else
    echo "ОШИБКА: публичная часть — настоящий адрес $public_real, несуществующий $public_missing_status"
    fail=1
fi

exit "$fail"
