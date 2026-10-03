# SYP Project Guidelines (Claude Code)

> **Версия**: 0.9.0 | **2026-10-03**
> Только ссылки. **Single source of truth**: `AGENTS.md` v0.9.0.

## Все правила — в `AGENTS.md`

| Что | Где в `AGENTS.md` |
|---|---|
| Язык общения — только русский | § Tier-0 |
| Knowledge-first pre-flight (MUST #0) | § Tier-1 MUST #0 |
| Hard Gate: документация прежде поиска | § Tier-1 «Living docs SSoT» |
| Шесть контейнеров SYP | § Tier-1 «Контейнеры» |
| Очередь перед перезапуском контейнера | § Tier-1 «Очередь перед перезапуском» |
| Build / Deploy / Containers | § Tier-1 «Build / Deploy / Containers» |
| Трекер OpenProject: claim → отчёт → review → close | § Tier-1 «Трекер: OpenProject» |
| Git: имя ветки `NNN-<slug>`, номер резервируется | § Tier-1 «Git — CI-gate для master» |
| Subagent workspace isolation | § Tier-1 «Subagent workspace isolation» |
| Living docs SSoT и раскладка `docs/` | § Tier-1 «Living docs SSoT» |
| Стек и запреты (JPA запрещён, сырой JDBC) | § Tier-1 «Стек и запреты» |
| Секреты и git-гигиена | § Tier-1 «Secrets & git hygiene» |
| Диагностика через `docker logs` | § Tier-1 «Диагностика через docker logs» |
| Процесс wayfinder → исполнение | § Tier-1 «Процесс: wayfinder → исполнение» |
| Обязательная проверка после изменения | § Tier-2 |
| Каталог guards | § Tier-2 |
| Точные пути, порты и эндпоинты развёртывания | § Tier-1 Hard Gate «Build / Deploy / Containers», подраздел «Precise paths» |
| Непреложные принципы | `constitution.md` |

## Рекомендации (не дублируют `AGENTS.md`)

1. **При старте сессии**: прочитать `docs/STATE-OF-PLAY.md` — память проекта
   между сессиями. Ответ «Продолжай» без этого = потеря контекста.
2. **Перед поиском по коду**: прочитать `docs/README.md` — карта L1, из неё
   понять домен. Это жёсткий гейт, не рекомендация.
3. **Перед правкой кода фичи**: обновить `docs/features/<slug>.md`.
4. **Перед коммитом**: `bash tools/check-no-secrets.sh` должен пройти, а
   `git ls-files | grep -iE '\.env$|\.key$|\.pem$|\.p12$|\.pfx$'` — быть пуст;
   номер ветки берётся скриптом, а не вручную.
5. **При отладке**: сначала `docker logs`, потом гипотезы.
6. **Если поиск по `docs/` ничего не нашёл**: зафиксировать в `spec.md` явно
   «Searched: … → no relevant docs».
7. **Линтер документации** обязателен к запуску перед коммитом:
   `python3 docs/scripts/lint-docs.py`.

## Где искать что (TL;DR)

| Что | Где |
|---|---|
| Память проекта, ответы владельца, следующий шаг | `docs/STATE-OF-PLAY.md` |
| Карта документации L1 | `docs/README.md` |
| Непреложные принципы | `constitution.md` |
| Глоссарий домена | `docs/domains/<домен>/domain.md` |
| Решения (почему так) | `docs/adr/` |
| Спека первого среза | `specs/001-first-vertical-slice/spec.md` |
| Знания о старом проекте (читать, код не тащить) | `/home/nsa/ivfx4/legacy-analysis/` |
| Задачи и тикеты | OpenProject, проект `syp` (id 4), через `tools/tracker.sh` |
| Исходное видео | `/disks/HDD_16Tb_Clouds/GOT` |
| Плейбук от обрывов вложенных агентов | `docs/howto/subagent-drops/playbook.md` |
| Guards проекта | `tools/check-*`; полный список — § Tier-2 «Каталог guards» в `AGENTS.md` |
| Единственная точка сборки и запуска | `deploy/do.sh` |
| Порты развёртывания | `deploy/.env.example` и § Tier-1 «Precise paths» в `AGENTS.md` |

## Ловушки среды (проверены на практике)

- В песочнице агента все диски показываются `ro`, включая корень, в который
  при этом писать можно. **Выводы о правах по `mounts` делать нельзя** —
  проверять контейнером на хосте.
- `ffmpeg` в песочнице обрывается `signal 9` на длинных операциях: замеры
  перекодирования из агентской среды **недействительны**.
- **GPU видна из среды агента** (проверено 2026-10-02: `nvidia-smi` работает,
  RTX 4060 Ti, драйвер 580.178.04, CUDA 13.0, `/dev/nvidia0` на месте). Ранее
  здесь стояло обратное — запись была неверной. Но **среды исполнения нет**:
  `nvcc`, `torch` и `onnxruntime` не установлены. То есть карта есть, рантайма
  нет — это и есть предмет замера М-01.
- **GPU на машине не свободна**: при замерах производительности это искажает
  цифры, и замер нужно помечать как проведённый на загруженной карте.