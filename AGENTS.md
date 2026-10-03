# AGENTS.md — инструкции для агентов (проект SYP)

> **Версия**: 0.5.0 | **Last updated**: 2026-10-03
>
> Производный от `AGENTS.md` проекта Karaoke v3.4.1 (`/home/nsa/Karaoke/AGENTS.md`).
> Все принципы работы сохранены; конкретика переписана под SYP.
> **Источник задачи**: OpenProject, проект `syp` (id 4), задача **#219 «Проект SYP»**.
> **Репозиторий**: `/home/nsa/syp` (сама папка проекта — **это корень**, все пути
> в этом файле относительные).
>
> ## 🚦 Точка подхвата — прочитай ПЕРВЫМ
>
> Новая сессия в этом репозитории: **первое действие** — открыть
> **[`docs/STATE-OF-PLAY.md`](docs/STATE-OF-PLAY.md)**. Там записано: где
> остановились, ответы владельца, открытые вопросы, что делать следующим шагом.
> Память сессий не переносится — этот файл и трекер OpenProject **являются**
> памятью проекта. Ответ «Продолжай» без чтения этого файла — потеря контекста.
>
> ## Что ещё не существует на момент bootstrap
> (создаётся по мере закрытия карты решений, отмечено `[bootstrap]`):
> наполнение `docs/domains/` и `docs/adr/`.
> Правила, ссылающиеся на отсутствующее, **уже обязательны** — создание
> инфраструктуры обязано их обеспечить.
>
> **Уже создано (2026-10-03)**: каркас проекта — Gradle multi-module
> (`syp-core`, `syp-admin-app`, `syp-public-app`) с обёрткой `gradlew`,
> фронтенды `syp-admin-web` и `syp-public-web`, `deploy/do.sh`,
> `deploy/docker-compose.yml` с шестью сервисами, `deploy/.env.example`,
> каталог guards `tools/check-*`, pre-commit hook и CI
> (`.github/workflows/ci.yml`).

## Tier-0: Язык общения

**Rule**: Всё общение с пользователем — **ТОЛЬКО на русском языке**.
**Failure**: на иностранном — непрошенный перевод.
**Source**: этот файл (перенесено без изменений из Karaoke).

## Tier-1: MUST #0 — Knowledge-first pre-flight (NON-NEGOTIABLE)

**Rule**: Перед ЛЮБОЙ фичей / спекой / правкой — сначала living docs, потом код.
Без этого — СТОП.

**Protocol** (5 шагов):
1. `docs/README.md` (карта документов L1) — **полностью**.
2. `grep -r '<keyword>' docs/` — минимум **3 попытки**.
3. `docs/domains/<домен>/domain.md` + **все** `docs/domains/<домен>/components/*.md`.
4. **Все** ADR из `docs/adr/`.
5. Только после 1–4 — `codegraph_explore` / `grep` по `src/`.

**Дополнение SYP**: до bootstrap-знаний по самому проекту обязательным
источником знаний является **отчёт по старому проекту** —
`/home/nsa/ivfx4/legacy-analysis/` (`README.md` → `00-OVERVIEW.md` → `01`…`04`).
Это **знания**, а не код: тащить код из `/home/nsa/ivfx4` ЗАПРЕЩЕНО, читать —
можно. Любая находка оттуда, влияющая на модель, проходит через обсуждение
с владельцем и попадает в ADR.

**Failure**: нет результатов → `spec.md` явно «Searched: ... → no relevant docs»;
игнорирование → спека на `/speckit.clarify`.
**Precedent**: spec #339 в Karaoke (2026-09-09) — агент изобрёл форму кеша вместо
паттерна из `knowledge/domains/caching/`.
**Enforcement**: `tools/check-spec-knowledge-preflight.sh` + секция
«Knowledge References» MANDATORY в `spec.md`.

## Tier-1: Hard Gate — Machine-Specific Exceptions

**Rule**: У каждой машины своя матрица разрешений. Перед операцией с
контейнерами — проверить `hostname` и `whoami`.

| Hostname | OS-user | rebuild контейнера SYP | restart контейнера SYP | Локальный контейнер | Интернет-деплой |
|---|---|---|---|---|---|
| `nsa-i9` / `nsa` (текущая) | `nsa` | по согласию | по согласию | по согласию | не планируется |
| `dev-pc` / `dev` | `dev` | ✅ без согласия | ✅ без согласия | ✅ без согласия | не планируется |

**Failure**: вне матрицы → спросить владельца **до** выполнения.
**Примечание SYP**: публичная часть на первом этапе **не публикуется в интернет** —
всё локально на этой машине. Прод-деплоя нет ⇒ его правила не переносятся; если
появится — этот раздел переписывается.

## Tier-1: Hard Gate — Контейнеры: 6 контейнеров SYP

| Контейнер | Аналог в Karaoke | Роль |
|---|---|---|
| `syp-db` | `karaoke-db` | PostgreSQL 16, база проекта |
| `syp-storage` | `karaoke-storage` | MinIO, объектное хранилище медиа/кадров |
| `syp-admin-app` | `karaoke-app` | бэкенд админки: разметка, анализ, очередь заданий |
| `syp-admin-web` | `karaoke-webvue3` | фронтенд админки (Vue 3 + nginx) |
| `syp-public-app` | `karaoke-web` | бэкенд публичной части: только «Show Your Picture» |
| `syp-public-web` | `karaoke-public` | фронтенд публичной части (Vue 3 + nginx) |

**Rule**: подготовка и настройка — **только в админке** (`syp-admin-*`).
Публичная часть (`syp-public-*`) — **только просмотр результата**, никаких
операций разметки. Смешивать ответственность двух фронтендов ЗАПРЕЩЕНО.

## Tier-1: Hard Gate — Очередь перед перезапуском (перенесено из Karaoke Pass 380)

**Rule**: согласие владельца на перезапуск контейнера с заданиями — это
согласие на перезапуск **после остановки очереди**, а не вместо неё.

**Почему**: контейнер может быть в середине задания анализа видео (нарезка
сцен, детекция лиц, сборка выборки). Поток исполнения живёт внутри контейнера,
и перезапуск его убивает; задание возвращается в очередь с потерянным
прогрессом и начинается заново — хотя само задание может длиться десятки минут.

**Protocol** (шаги строго по порядку):

1. **Проверить, что заданий в работе нет** — список заданий очереди, фильтр по
   статусам `CREATING` / `WORKING` (полный набор: `CREATING`, `WAITING`,
   `WORKING`, `DONE`, `ERROR`; аналог Karaoke — `GET /api/processesdigests`).
2. **Остановить очередь** — кнопка «Стоп» в шапке админки (аналог —
   `ProcessWorker.vue`; низкоуровневые эндпоинты очереди у Karaoke живут в
   `MainController` **без** префикса `/api` и nginx их не проксирует —
   обращаться напрямую к бэкенду, `:8898/process/stop`).
3. **Дождаться фактической остановки бэка**: повторять шаг 1, пока активные
   задания не кончатся. «Стоп» останавливает очередь, но уже взятое задание
   может дорабатывать.
4. **Только теперь** перезапускать контейнер.
5. **После перезапуска** очередь сама не поднимается — её надо включить кнопкой
   «Старт», если она была включена до перезапуска.

**Precise paths** (зафиксировано 2026-10-03 при bootstrap развёртывания,
задачи фазы 0 спеки первого среза):

| Что | Значение |
|---|---|
| Порты веба | из диапазона **7910–7999** |
| Порты хранилища | из диапазона **9020–9099** |
| `syp-db` (PostgreSQL 16, хост) | **7910** |
| `syp-admin-app` (бэкенд, хост) | **7911** |
| `syp-admin-web` (nginx, хост) | **7912** |
| `syp-public-app` (бэкенд, хост) | **7913** |
| `syp-public-web` (nginx, хост) | **7914** |
| `syp-storage` (MinIO API, хост) | **9020** |
| `syp-storage` (консоль MinIO, хост) | **9021** |
| Префикс HTTP API обоих бэкендов | `/api`, версионирования префиксом нет |
| Эндпоинт ключа подписи | `GET /api/recipes/verification-key` (публичный бэкенд) |
| Путь каталога миграций в контейнере | `/app/migrations` |
| Корневой каталог исходников | `/disks/HDD_16Tb_Clouds/GOT`, монтируется **только для чтения** |

Значения по умолчанию заданы в `deploy/.env.example`; настоящие значения —
в `deploy/.env`, который вне системы контроля версий. Значения из таблицы
заняты на машине: 7906, 7907, 7980, 7981, 9001, 9088, 8080, 8897, 8898, 80,
3080 — они не используются. Список эндпоинтов домена появится вместе с
контрактами `specs/001-first-vertical-slice/contracts/`.

**Failure**: перезапуск при непустом списке активных заданий → потерянное или
«зависшее» задание.
**Примечание**: разрешение на конкретный перезапуск **не продлевается** на
следующие в той же сессии.

## Tier-1: Hard Gate — Build / Deploy / Containers

**Rule**: gradle с `GRADLE_USER_HOME`. Docker с `DOCKER_CONFIG`. Контейнеры —
только через `deploy/do.sh`. Frontend — через `cd <dir> && npm run`.

| Под-правило | Rule | Failure | Enforcement |
|---|---|---|---|
| **Gradle (R-372)** | `./gradlew ...` с `GRADLE_USER_HOME=/home/nsa/syp/.gradle` | read-only FS в DSH-sandbox | `tools/check-gradle-user-home.sh` |
| **Docker (R-373)** | `docker build` / `do.sh build_*` с `DOCKER_CONFIG=/home/nsa/syp/.docker` | read-only `~/.docker/buildx/activity/` | `tools/check-docker-config.sh` |
| **Containers (R-374)** | Только через `deploy/do.sh start_<c>` / `restart_<c>`. **Запрещено** `docker restart <c>`. | потеря зависимостей + обход согласия | `tools/check-container-restart.sh` |
| **Frontend (R-375)** | `cd <frontend-dir> && npm run`. В корне `package.json` **нет**. | `npm run` из корня → node_modules не найден | `tools/check-frontend-build.sh` |

**Примечание SYP**: `nginx:stable` (не `nginx:alpine` — compose использует
`/bin/bash -c`), `node:22-alpine` (не `node:latest`), `postgres:16`,
`elestio/minio:latest` (MinIO убрана из Docker Hub; внутри релиз
`RELEASE.2025-09-07T16-13-09Z`, digest зафиксирован — ADR-0012). База образов бэкенда —
`eclipse-temurin:21-jre-noble` (JRE, не JDK), как в Karaoke. Файлы образов:
`deploy/Dockerfile.backend`, `deploy/Dockerfile.frontend`.

## Tier-1: Hard Gate — Трекер: OpenProject

**Rule**: Слой задач и слой решений — **только OpenProject**, проект `syp`
(id 4), агент `ai-agent`. Локальные `issues_NNNN.md` в проекте **не ведутся**.

**Трекер-CLI**: `tools/tracker.sh` SYP (адаптация
`/home/nsa/Karaoke/tools/tracker.sh`; настройка — `docs/tracker-setup.md`
Karaoke, паттерн тот же). Прямой доступ к API OpenProject — только через CLI
своего проекта.

**Issue ID — обязательные 4 шага** (`report.md` — REQUIRED артефакт):

| Шаг | Команда | Когда |
|---|---|---|
| 1. **Claim** | `tracker.sh claim-issue <NNN>` | До первой строки кода |
| 2. **Add comment** | `tracker.sh add-comment <NNN> --file specs/<NNN>-<slug>/report.md` | После merge, до mark-review |
| 3. **Mark review** | `tracker.sh mark-review <NNN>` | После add-comment |
| 4. **Close** | `tracker.sh close-issue <NNN>` | После ревью владельца |

**Failure**: OpenProject #69 в Karaoke — без workflow отчёт писался задним числом.
**Enforcement**: `tools/check-spec-issue-link.py`.

## Tier-1: Hard Gate — Git — CI-gate для master ⛔

**Rule**: НИКОГДА `git commit` / `git push` напрямую в `master`. ТОЛЬКО через
feature-ветку + PR + CI.

**Protocol** (адаптация под SYP):
```bash
N=$(./tools/reserve-branch-number.sh my-slug)   # номер НЕ выдумывается
git push -u origin "${N}-my-slug" && gh pr create --base master
gh pr checks && gh pr merge --merge   # БЕЗ --delete-branch
```
**Rule**: имя feature-ветки — **`NNN-<slug>`**. Номер резервируется скриптом
`tools/reserve-branch-number.sh` (перенесён из Karaoke 2026-10-02): он берёт
следующий свободный номер по трём источникам — ветки на `origin`, теги
`seq/NNN`, каталоги `specs/` — и атомарно резервирует его push уникального
lightweight-тега `refs/tags/seq/NNN` (git отклоняет, если тег уже есть).
Теги живут дольше веток, поэтому нумерация не сбрасывается после удаления
смерженной ветки.
**Failure**: прямая правка master → merge conflict + потеря работы; ветка без
номера → нарушение конвенции.
**Precedent**: Karaoke Pass 353 (прямая правка master).
**Enforcement**: 3 уровня (branch protection с 2026-10-02, pre-commit, CI lint).
**Состояние на 2026-10-02**: репозиторий создан —
`https://github.com/svoemesto/syp` (публичный, `svoemesto`), ветка `master`
защищена: push напрямую запрещён, требуется запрос на слияние и одно
одобрение. Спека задачи — в `specs/<NNN>-<slug>/spec.md`, её каталог тоже
участвует в нумерации.

## Tier-1: Hard Gate — Subagent workspace isolation

**Rule**: Несколько субагентов для параллельных PR-веток MUST работать в
**отдельных `git worktree`**. НЕ в одном `cwd`.

**Rule**: рабочие каталоги проекта — **внутри папки проекта**. Изоляция
субагентов — `.worktrees/<NNN>-<slug>`, рабочие данные — `.data/`. Оба
каталога в `.gitignore`. **Наружу от папки проекта не пишем ничего**: ни
временных выгрузок, ни копий данных, ни «удобных» каталогов рядом.

```bash
N=$(./tools/reserve-branch-number.sh <slug>)          # номер НЕ выдумывается
git worktree add .worktrees/${N}-<slug> -b "${N}-<slug>" master
```

**Почему правило дополнено (2026-10-02).** Правило из Karaoke требовало
изоляции, но **не указывало место**, и пример `../syp-…` выполнялся буквально:
четыре worktree и 1,4 ГБ выгруженных данных оказались в домашней папке.
Место выбирает тот, кто пишет, — и оно обязано быть внутри проекта.
**Failure**: два+ субагента в одном workspace → часы на rebase чужих PRов
(Karaoke Pass 379: 30 минут из-за race на `git checkout`).
**Enforcement**: `tools/check-subagent-isolation.sh`.

## Tier-1: Hard Gate — Living docs SSoT

### Hard Gate: Documentation First

- **Rule**: ЗАПРЕЩЕНО искать по коду и по `docs/` до чтения `docs/README.md`.
- **Протокол**: `Прочитать docs/README.md` → `Определить домен/компонент` →
  `Прочитать конкретный документ`.
- **Failure**: начать задачу с `grep`, `find` или любого поиска по `docs/`
  без чтения README — грубое нарушение протокола.
- **Прецедент** (Karaoke spec #339): агент изобрёл форму кеша вместо
  существующего паттерна, потому что не прочитал домен.

### Living Documentation & Workflow

**Mandatory Context Retrieval**:
- **Rule**: перед любой задачей, планированием или исследованием кода ты
  **ОБЯЗАН** сначала прочитать `docs/README.md`.
- **Purpose**: `docs/README.md` — карта системы (C4 L1–L2); по ней определяются
  релевантные домены, ADR и guidelines, без сканирования файловой системы.

**State Mutation Lifecycle**:
- **Rule**: документация определяет **текущее состояние** системы, а не историю
  изменений.
- **Протокол**:
  1. **Ephemeral Work**: временные спеки и трекинг задач — вне `docs/`
     (рабочие заметки — `.scratch/`, задачи — трекер OpenProject).
  2. **Mutation**: при реализации обновить затронутые файлы `docs/` (домены,
     компоненты, ADR, плейбуки) под новое состояние.
  3. **Purge**: удалить временные артефакты. Merge — не «готово», пока живая
     документация не приведена в соответствие.

**Стиль правки — «текущее состояние», а не журнал изменений**:
- ✗ неверно: «добавлено поле `last_processed_at` в таблицу»;
- ✓ верно: в описании таблицы просто появляется поле.

### Subagent Initialization Protocol

- **Rule**: субагенты не наследуют инструкции проекта автоматически, поэтому
  главный агент **ОБЯЗАН** явно внедрить в промпт каждого субагента:
  1. **Hard Gate**: запрет искать по коду и `docs/` до чтения `docs/README.md`.
  2. **SSoT-Verified**: ответ начинается с `[SSoT-Verified]`, если живая
     документация была использована.
  3. **L3 Abstraction**: описывать требования и инварианты, а не дублировать
     код (никаких имён переменных и сигнатур классов в требованиях).
  4. **Protocol Sequence**: `Прочитать README` → `Определить домен` →
     `Прочитать документ` → `Реализовать/Проанализировать`.
  5. **Linking Protocol**: при правке документации сохранять цепочку
     L3 → L2 → L1.
  6. **Template Mandate**: новые файлы — по шаблонам `docs/templates/`,
     обязательные секции при правках сохраняются.

**Rule**: Изменения в коде требуют синхронного обновления документации по
карте кода (`.ssot-map.yml`, guard `tools/check-ssot-impact.py`).
Структурные проверки + cross-links
+ markdown style (NO EMOJI, обязательные заголовки). Новые нарушения → CI fail
(`--baseline FILE` для допустимых).

**Layout** (конвенция Living Documentation; в Karaoke тот же каркас живёт в
`knowledge/`, в SYP — в `docs/`, как предписывает этот файл):
| Артефакт | Путь |
|---|---|
| **Память проекта между сессиями** | **`docs/STATE-OF-PLAY.md`** — читать первым |
| Карта документов L1 | `docs/README.md` |
| Контекст системы L1–L2 | `docs/system/01-context.md`, `docs/system/02-containers.md` |
| Глоссарий домена | `docs/domains/<домен>/domain.md` § «Ubiquitous Language \| Единый язык» |
| Компоненты домена | `docs/domains/<домен>/components/*.md` |
| Решения (ADR, append-only) | `docs/adr/ADR-NNNN-<slug>.md` |
| Стратегические переходы | `docs/epics/*.md` |
| Стандартные паттерны | `docs/guidelines/*.md` |
| Плейбуки (how-to) | `docs/howto/<slug>/playbook.md`, индекс `docs/howto/README.md` |
| Публичные проекции | `docs/public/*.md` |
| Шаблоны документов | `docs/templates/*.md` |
| Линтер документации | `docs/scripts/lint-docs.py` |
| Бэклог | `docs/BACKLOG.md` |
| Per-feature документ (FR-009) | `docs/features/<slug>.md` |

**Enforcement**: `python3 docs/scripts/lint-docs.py` — линтер создан при
bootstrap 2026-10-02, обязателен к запуску; `tools/check-ssot-impact.py`
подключён в pre-commit и CI.

**Симметрия инструкций**: файлы `CLAUDE.md` и `AGENTS.md` описывают **одни и
те же** правила. В SYP `CLAUDE.md` — тонкий указатель со ссылками на секции
`AGENTS.md` (конвенция Karaoke); изменил один — синхронизируй другой.

## Tier-1: Hard Gate — Стек и запреты

**Rule (перенесено из Karaoke constitution II, ADR-0001, R-07)**:
- **Никакого JPA/Hibernate**: `spring-boot-starter-data-jpa`,
  `import jakarta.persistence`, `import javax.persistence` — запрещены.
- Персистентность — **сырой JDBC** по образцу `KaraokeDbTable`
  (reflection + diff-based save, `recordhash`).
- Миграции БД — нумерованные `deploy/syp-db/NN_*.sql`, **append-only**,
  Postgres 16. `ddl-auto` ЗАПРЕЩЁН.
- Долгие операции — через очередь заданий (аналог `KaraokeProcessWorker`),
  `ProcessBuilder.redirectErrorStream(true)` обязательно (ADR-0006).
- Секреты — **только через env**; хардкод запрещён.
- Публичные API — KDoc/JSDoc 100 % + `@see` на `docs/features/<slug>.md`
  (FR-006); ktlint/ESLint в CI (FR-007); per-feature документ обновляется в
  том же PR (FR-009).

**Failure**: JPA-импорт, `ddl-auto=update`, секрет в коде, `mp4`-упоминания там,
где домен оперирует сценами (R-11), рендер основного видео через MLT/melt
вместо ffmpeg (R-44).

## Tier-1: Hard Gate — Secrets & git hygiene

**Rule**: Секреты НЕ коммитить. `.gitignore` НЕ достаточно для tracked-файлов —
нужен `git rm --cached`.

**Pre-commit check**:
```bash
git ls-files | grep -iE '\.env$|do\.env$|\.key$|\.pem$|\.p12$|\.pfx$'
# MUST возвращать пусто.
```
**Failure (прецедент Karaoke, 2026-08-03)**: `deploy/.env` с паролями
Postgres/MinIO/Docker Hub трекался 3 года.
**Rule (VIII.4)**: при обнаружении утёкшего секрета — НЕМЕДЛЕННО сменить секрет,
потом `git rm --cached`, потом опционально `git filter-repo`.
**Rule (VIII.6)**: не печатать секреты в вывод `do.sh` и в логи.

## Tier-1: Hard Gate — Диагностика через docker logs

**Rule**: При отладке — **сначала** `docker logs <container>`, потом гипотезы.
**Failure**: потеря итераций на гипотезы, лог прямо указывал на причину
(Karaoke Pass 358).

## Tier-1: Hard Gate — Процесс: wayfinder → исполнение

**Rule**: Большая туманная задача — сначала wayfinder-карта решений (скилл
`wayfinder`, включается вручную). Карта закрыта → лид классифицирует остаток,
**владелец решает**: «большая» или «маленькая». Маленькая — лид **спрашивает
владельца**: сделать самому или через спеку. Большая — issue-задача work package
(`tracker.sh create-issue`) → `speckit-specify` → спека уходит владельцу →
**СТОП** → по слову владельца долгоживущий субагент ведёт
`speckit-plan → speckit-tasks → speckit-analyze → speckit-implement`, лид
ревьюит каждый шаг по фактам с диска → после merge штатные 4 шага
OpenProject-workflow. Лид обращается к владельцу только когда нужно менять
спеку: **спека — контракт**, правки FR/SC — только словом владельца.

**Раскладка wayfinding в OpenProject** (по образцу Karaoke):
| Понятие | В OpenProject |
|---|---|
| Карта усилия | Work package проекта `syp`, тема `[wayfinder] <усилие>` |
| Тело карты | описание work package (Destination / Notes / Decisions so far / Not yet specified / Out of scope) |
| Тикет-решение | отдельный work package: тема = **название вопроса**, первая строка `Map: #<ID карты>`, далее Type / Status / Blocked by / Question / Answer |
| `Blocked by` | строки `Blocked by: #N` в описании; relations не заводятся |
| OPEN / CLAIMED / RESOLVED | `New` (id 1) без assignee / `In progress` (id 7) + assignee / `Closed` (id 12) + комментарий-ответ |

**Лимит**: одна сессия разрешает **не больше одного** тикета; исключение —
`research`-тикеты, которые идут параллельно через субагентов.

**Инфраструктура команды — read-only**: `/home/nsa/agents-team-srv` для задач
SYP не изменяется; изменения делаются на копиях и ссылках (`~/.dsh/skills/`,
`~/.dsh/plugins/`, `~/.dsh/romario-team/`).

**Failure**: исполнение «в тумане» без карты и спеки; локальные issue вместо
OpenProject; правка `agents-team-srv`; спека как черновик.
**Source**: `~/.dsh/romario-team/wayfinding-operations.md` + `subagents.md`.

## Tier-2: Обязательная проверка после любого изменения

**Rule**: Vite-build ≠ Docker-образ. Все gradle с `GRADLE_USER_HOME`.

```bash
# 1. Backend compile — все модули бэкенда
GRADLE_USER_HOME=/home/nsa/syp/.gradle ./gradlew compileKotlin --parallel
# 2. Линтеры
GRADLE_USER_HOME=/home/nsa/syp/.gradle ./gradlew ktlintCheck
cd syp-admin-web  && npm run lint && cd ..     # каталоги фронтендов = имена контейнеров
cd syp-public-web && npm run lint && cd ..
# 3. Backend bootJar
GRADLE_USER_HOME=/home/nsa/syp/.gradle ./gradlew bootJar --parallel
# 4. Frontend Vite
cd syp-admin-web  && npm run build && npm run format:check && cd ..
cd syp-public-web && npm run build && npm run format:check && cd ..
# 5. Docker-образы
cd deploy && bash do.sh build_admin_app
cd deploy && bash do.sh build_public_app && cd ..
```
**Failure**: пропуск → сломанный production build.
**Примечание**: конкретные имена gradle-проектов и цели `do.sh`
фиксируются bootstrap-решением; форма команд — незыблема.

## Tier-2: Каталог guards

| Rule | Source | Tool | Состояние |
|---|---|---|---|
| R-07 JPA запрет | constitution § III / этот файл | `tools/check-no-jpa-imports.sh` | готов |
| R-11 видеофайлы на сервере | ADR-0009 | `tools/check-no-mp4-mentions.sh` | готов |
| R-32 FR-006 / FR-009 документированность | constitution § VI | `tools/check-feature-doc.sh` | готов |
| FR-085 нет исполнителя у публичной части | research.md Т-20 | `tools/check-no-job-worker.sh` | готов |
| R-43 redirectErrorStream | constitution § IV | code review | ручная проверка |
| R-44 ffmpeg против melt | architecture-conventions | code review | ручная проверка |
| R-372 Gradle | этот файл (Tier-1) | `tools/check-gradle-user-home.sh` | готов |
| R-373 Docker | этот файл (Tier-1) | `tools/check-docker-config.sh` | готов |
| R-374 Containers | этот файл (Tier-1) | `tools/check-container-restart.sh` | готов |
| R-375 Frontend | этот файл (Tier-1) | `tools/check-frontend-build.sh` | готов |
| Изоляция рабочих копий | constitution § VII.3 | `tools/check-subagent-isolation.sh` | готов |
| Номер задачи трекера | constitution § IX.1 | `tools/check-spec-issue-link.py` | готов |
| Карта кода и living docs | Hard Gate «Living docs SSoT» | `tools/check-ssot-impact.py` | готов |
| Knowledge-first pre-flight | constitution § II | `tools/check-spec-knowledge-preflight.sh` | готов |
| Покрытие задач требованиями | Hard Gate «Трекер» | `tools/check-tasks-coverage.py` | готов |
| Линтер документации | Hard Gate «Living docs SSoT» | `docs/scripts/lint-docs.py` | готов |
| Отсутствие секретов | constitution § VIII.3 | `tools/check-no-secrets.sh` | готов |
| Имена таблиц в SQL соответствуют схеме | миграция 16 | `tools/check-old-table-names.sh` | готов |
| Пути эндпоинтов не дублируют общий префикс | Spring `@RequestMapping` | `tools/check-no-duplicate-api-prefix.sh` | готов |
| Каждый компонент фронтенда подключён | Vue SFC | `tools/check-components-mounted.sh` | готов |

Все перечисленные скрипты подключены к pre-commit
(`tools/pre-commit.sh`) и к CI (`.github/workflows/ci.yml`). Правила,
проверяемые code review, автоматикой не ловятся: их проверяет человек.
Отсутствие проверки **не отменяет** правило.

- **0.5.0** (2026-10-03): правило «рабочие каталоги — внутри папки
  проекта». Изоляция субагентов переведена в `.worktrees/<NNN>-<slug>`,
  рабочие данные — в `.data/`, оба каталога в `.gitignore`. Причина: правило,
  перенесённое из Karaoke, требовало изоляции, но не указывало место, и
  пример `../syp-…` выполнялся буквально — четыре worktree и 1,4 ГБ данных
  оказались в домашней папке. Синхронизирован `CLAUDE.md`.
- **0.6.0** (2026-10-03): guard `tools/check-no-duplicate-api-prefix.sh`
  на пути эндпоинтов. Причина: у контроллера общий префикс `/api`, и путь
  метода с `/api` уезжал на `/api/api/…` — эндпоинт был, а по объявленному
  адресу отвечал 405, и запустить детекцию лиц было нечем. Semver: MINOR —
  раздел guards дополнен, ни одно правило не отменено.

## Changelog

- **0.4.0** (2026-10-03): зафиксированы точные пути и порты развёртывания
  (раздел «Precise paths»): веб 7910–7999, хранилище 9020–9099, контейнеры
  7910/7911/7912/7913/7914, MinIO 9020 и 9021; сняты отметки `[bootstrap]`
  с созданной инфраструктуры — каркаса Gradle, обоих фронтендов, `deploy/do.sh`,
  `deploy/docker-compose.yml`, `deploy/.env.example`, каталога guards `tools/check-*`,
  pre-commit hook и CI. Каталог guards переведён на таблицу с указанием
  состояния («готов» против «ручная проверка»). Создано 13 guard-скриптов,
  из них R-43 и R-44 остаются на code review: автоматикой они не ловятся.
  Semver: MINOR — раздел инструкций дополнен, ни одно правило не отменено.
- **0.3.0** (2026-10-02): развёрнут каркас Living Documentation в `docs/`
  (system, domains, adr, epics, guidelines, templates, public, howto, scripts,
  features) с линтером `docs/scripts/lint-docs.py`; в этот раздел внедрены
  обязательные блоки **Hard Gate: Documentation First**, **State Mutation
  Lifecycle**, **Subagent Initialization Protocol** и правило симметрии
  инструкций. Git-секция дополнена правилом имени ветки `NNN-<slug>` с
  атомарной резервацией номера скриптом `tools/reserve-branch-number.sh`
  (перенос из Karaoke). Шапка обновлена: репозиторий создан, `constitution.md`
  написан, `docs/` больше не в списке отсутствующего.
- **0.2.0** (2026-10-01): проект перенесён в `/home/nsa/syp` (решение владельца
  Q2=B); добавлен раздел «Точка подхвата» и файл `docs/STATE-OF-PLAY.md` —
  память проекта между сессиями. Ответы владельца: Q1=A (спека на первый
  сквозной вертикальный срез), Q3=GPU-first. Факты окружения и архива —
  `.scratch/syp/research/00-environment-facts.md`.
- **0.1.0** (2026-10-01): bootstrap SYP. Производный от Karaoke `AGENTS.md`
  v3.4.1. Сохранены все принципы: язык, MUST #0 knowledge-first, матрица
  машин, очередь перед резапуском, build/deploy/containers, трекер, git CI-gate,
  subagent isolation, living-docs SSoT, запрет JPA, секреты, docker logs,
  wayfinder → исполнение. Переписана конкретика: 6 контейнеров SYP, трекер —
  OpenProject проект `syp` (id 4), путь `/home/nsa/syp`, добавлен
  `/home/nsa/ivfx4/legacy-analysis/` как источник знаний (код оттуда не тащим).

## Как обновлять этот файл

Правки governance — только через governance-PR с явным **semver bump**.
**НЕ дублировать** детали — каждое правило живёт в ОДНОМ файле:
- Hard-gates и рабочие инструкции → этот файл.
- Непреложные принципы → `constitution.md`.
- Архитектура и build/docker-конвенции → `docs/guidelines/architecture-conventions.md`.
- Глоссарий домена → `docs/domains/<домен>/domain.md`.
- Решения (ADR) → `docs/adr/` (append-only).
- Знания о старом проекте → `/home/nsa/ivfx4/legacy-analysis/` (read-only).