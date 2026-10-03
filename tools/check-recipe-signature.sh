#!/usr/bin/env bash
# Сквозная проверка подписи сценария сторонним openssl.
#
# Зачем: подпись сценария — единственное, чем пользователь доказывает, что
# сценарий не подменён на его машине (ADR-0014, FR-089c). Пока это не проверено
# сторонней программой, утверждать, что подпись работает, нельзя: свою же
# проверку на своей же стороне легко написать так, что она всегда проходит.
#
# Проверка берёт всё с работающего стенда — открытый ключ, канонический файл
# сценария и подпись — и проверяет подпись программой, которой в проекте нет:
# openssl из системы. Две обязательные вещи:
#
#   1. подпись настоящего сценария подтверждается сторонней программой;
#   2. подделка и чужой ключ отвергаются.
#
# Второе важно не меньше первого. Проверка, которая принимает всё, не отличает
# работу подписи от её отсутствия.
set -uo pipefail

PUBLIC_URL="${SYP_PUBLIC_API_URL:-http://127.0.0.1:7913}"
RECIPE_ID="${1:-2}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if ! command -v openssl >/dev/null 2>&1; then
    echo "ОШИБКА: openssl не найден в системе — проверять нечем"
    exit 1
fi

echo "== сценарий $RECIPE_ID, ключ и файл с публичной части =="
curl -sf --max-time 30 "$PUBLIC_URL/api/recipes/verification-key" -o "$WORK/key.json" || {
    echo "ОШИБКА: не отдался открытый ключ ($PUBLIC_URL/api/recipes/verification-key)"
    exit 1
}
curl -sf --max-time 60 "$PUBLIC_URL/api/recipes/$RECIPE_ID/file" -o "$WORK/recipe.json" || {
    echo "ОШИБКА: не отдался файл сценария ($PUBLIC_URL/api/recipes/$RECIPE_ID/file)"
    exit 1
}
curl -sf --max-time 30 "$PUBLIC_URL/api/recipes/$RECIPE_ID/signature" -o "$WORK/signature.json" || {
    echo "ОШИБКА: не отдалась подпись ($PUBLIC_URL/api/recipes/$RECIPE_ID/signature)"
    exit 1
}

python3 - "$WORK" <<'PY'
import base64
import json
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
key = json.loads((work / "key.json").read_text(encoding="utf-8"))
signature = json.loads((work / "signature.json").read_text(encoding="utf-8"))

if key["keyId"] != signature["signingKeyId"]:
    sys.exit(
        f"ОШИБКА: подпись сделана ключом {signature['signingKeyId']}, "
        f"а публичный ключ — {key['keyId']}"
    )
raw = base64.b64decode(signature["signature"])
if len(raw) != 64:
    sys.exit(f"ОШИБКА: подпись Ed25519 должна быть 64 байта, получено {len(raw)}")
(work / "pub.pem").write_text(key["publicKeyPem"], encoding="utf-8")
(work / "sig.raw").write_bytes(raw)
declared = signature["contentSha256"]
actual = __import__("hashlib").sha256((work / "recipe.json").read_bytes()).hexdigest()
if declared != actual:
    sys.exit(f"ОШИБКА: сумма содержимого не совпала: заявлено {declared}, получено {actual}")
print(f"OK: ключ {key['keyId']}, подпись 64 байта, сумма содержимого совпала")
PY
[ $? -eq 0 ] || exit 1

echo "== подпись настоящего сценария, openssl =="
if openssl pkeyutl -verify -pubin -inkey "$WORK/pub.pem" -rawin \
    -in "$WORK/recipe.json" -sigfile "$WORK/sig.raw" >/dev/null 2>&1; then
    echo "OK: подпись подтверждена сторонней программой ($(openssl version))"
else
    echo "ОШИБКА: openssl не подтвердил подпись"
    exit 1
fi

echo "== подделка обязана отвергаться =="
python3 - "$WORK" <<'PY'
import pathlib
import sys

work = pathlib.Path(sys.argv[1])
raw = (work / "recipe.json").read_bytes()
marker = b'"firstFrame"'
at = raw.find(marker)
if at < 0:
    sys.exit("ОШИБКА: в файле сценария нет ни одного кадра — подделывать нечего")
cut = raw.find(b":", at) + 1
(work / "tampered.json").write_bytes(raw[:cut] + b"999999" + raw[raw.find(b",", at):])
PY
if openssl pkeyutl -verify -pubin -inkey "$WORK/pub.pem" -rawin \
    -in "$WORK/tampered.json" -sigfile "$WORK/sig.raw" >/dev/null 2>&1; then
    echo "ОШИБКА: подделанный файл принят как настоящий — проверка ничего не проверяет"
    exit 1
fi
echo "OK: подделанный файл отвергнут"

echo "== чужой ключ обязан отвергаться =="
openssl genpkey -algorithm ED25519 -out "$WORK/other.key" 2>/dev/null
openssl pkey -in "$WORK/other.key" -pubout -out "$WORK/other.pub" 2>/dev/null
if openssl pkeyutl -verify -pubin -inkey "$WORK/other.pub" -rawin \
    -in "$WORK/recipe.json" -sigfile "$WORK/sig.raw" >/dev/null 2>&1; then
    echo "ОШИБКА: подпись принята под чужим ключом — проверка ничего не проверяет"
    exit 1
fi
echo "OK: чужой ключ отвергнут"

echo "ИТОГ: подпись сценария проверяется сторонней программой и отвергает подделку"
