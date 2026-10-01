# AGENTS.md — инструкции для агентов (проект SYP)

> **Версия**: 0.2.0 (bootstrap) | **Last updated**: 2026-10-01
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
> `constitution.md`, `docs/` (кроме STATE-OF-PLAY), `tools/check-*.sh`,
> `deploy/do.sh`, gradle-модули, фронтенды. Правила, ссылающиеся на них,
> **уже обязательны** — создание инфраструктуры обязано их обеспечить.

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
**Enforcement**: `tools/spec-knowledge-preflight.sh` `[bootstrap]` + секция
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

**Precise paths**: эндпоинты и порты SYP фиксируются на bootstrap и
записываются сюда при semver-bump этого файла (в Karaoke Pass 381 пришлось
исправлять неверный `/api/process/stop` → 404).

**Failure**: перезапуск при непустом списке активных заданий → потерянное или
«зависшее» задание.
**Примечание**: разрешение на конкретный перезапуск **не продлевается** на
следующие в той же сессии.

## Tier-1: Hard Gate — Build / Deploy / Containers

**Rule**: gradle с `GRADLE_USER_HOME`. Docker с `DOCKER_CONFIG`. Контейнеры —
только через `deploy/do.sh`. Frontend — через `cd <dir> && npm run`.

| Под-правило | Rule | Failure | Enforcement |
|---|---|---|---|
| **Gradle (R-372)** | `./gradlew ...` с `GRADLE_USER_HOME=/home/nsa/syp/.gradle` | read-only FS в DSH-sandbox | `check-gradle-user-home.sh` `[bootstrap]` |
| **Docker (R-373)** | `docker build` / `do.sh build_*` с `DOCKER_CONFIG=/home/nsa/syp/.docker` | read-only `~/.docker/buildx/activity/` | `check-docker-config.sh` `[bootstrap]` |
| **Containers (R-374)** | Только через `deploy/do.sh start_<c>` / `restart_<c>`. **Запрещено** `docker restart <c>`. | потеря зависимостей + обход согласия | `check-container-restart.sh` `[bootstrap]` |
| **Frontend (R-375)** | `cd <frontend-dir> && npm run`. В корне `package.json` **нет**. | `npm run` из корня → node_modules не найден | `check-frontend-build.sh` `[bootstrap]` |

**Примечание SYP**: `nginx:stable` (не `nginx:alpine` — compose использует
`/bin/bash -c`), `node:22-alpine` (не `node:latest`). База образов бэкенда —
`eclipse-temurin` (JRE, не JDK), как в Karaoke. `[bootstrap]` — точные теги
фиксируются решением карты.

## Tier-1: Hard Gate — Трекер: OpenProject

**Rule**: Слой задач и слой решений — **только OpenProject**, проект `syp`
(id 4), агент `ai-agent`. Локальные `issues_NNNN.md` в проекте **не ведутся**.

**Трекер-CLI**: `tools/tracker.sh` SYP `[bootstrap]` (адаптация
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
**Enforcement**: `tools/check-spec-issue-link.py` `[bootstrap]`.

## Tier-1: Hard Gate — Git — CI-gate для master ⛔

**Rule**: НИКОГДА `git commit` / `git push` напрямую в `master`. ТОЛЬКО через
feature-ветку + PR + CI.

**Protocol** (адаптация под SYP):
```bash
git checkout -b <NNN>-<slug> master && <правки>
git push -u origin <NNN>-<slug> && gh pr create --base master
gh pr checks && gh pr merge --merge   # БЕЗ --delete-branch
```
**Failure**: прямая правка master → merge conflict + потеря работы.
**Precedent**: Karaoke Pass 353.
**Enforcement**: 3 уровня (branch protection, pre-commit, CI lint).
**Примечание**: репозиторий SYP на момент bootstrap не создан — `git init`,
remote и branch protection — часть bootstrap-решения карты.

## Tier-1: Hard Gate — Subagent workspace isolation

**Rule**: Несколько субагентов для параллельных PR-веток MUST работать в
**отдельных `git worktree`**. НЕ в одном `cwd`.

```bash
git worktree add ../syp-<NNN>-<slug> -b "<NNN>-<slug>" master
```
**Failure**: два+ субагента в одном workspace → часы на rebase чужих PRов
(Karaoke Pass 379: 30 минут из-за race на `git checkout`).
**Enforcement**: `tools/check-subagent-isolation.sh` `[bootstrap]`.

## Tier-1: Hard Gate — Living docs SSoT

**Rule**: Изменения в коде требуют синхронного обновления документации по
карте кода (`.ssot-map.yml` `[bootstrap]`). Структурные проверки + cross-links
+ markdown style (NO EMOJI, обязательные заголовки). Новые нарушения → CI fail
(`--baseline FILE` для допустимых).

**Layout** (та же конвенция, что в Karaoke):
| Артефакт | Путь |
|---|---|
| **Память проекта между сессиями** | **`docs/STATE-OF-PLAY.md`** — читать первым |
| Карта документов L1 | `docs/README.md` |
| Глоссарий домена | `docs/domains/<домен>/domain.md` § «Ubiquitous Language \| Единый язык» |
| Компоненты домена | `docs/domains/<домен>/components/*.md` |
| Решения (ADR, append-only) | `docs/adr/ADR-NNNN-<slug>.md` |
| Бэклог | `docs/BACKLOG.md` |
| Per-feature документ (FR-009) | `docs/features/<slug>.md` |

**Enforcement**: `check-ssot-impact.py`, `check-knowledge-structure.sh`,
`lint-knowledge.py` `[bootstrap]`.

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
cd syp-admin-web  && npm run lint && cd ..     # каталоги фронтендов = имена контейнеров [bootstrap]
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

| Rule | Source | Tool |
|---|---|---|
| R-07 JPA запрет | constitution § II / этот файл | `check-no-jpa-imports.sh` |
| R-11 MP4/скачивание | architecture-conventions | `check-no-mp4-mentions.sh` |
| R-32 FR-009 per-feature | constitution § VI | `check-feature-doc.sh` |
| R-43 redirectErrorStream | constitution § IV | code review |
| R-44 ffmpeg vs melt | architecture-conventions | code review |
| Gradle / Docker / Containers / Frontend | этот файл (Tier-1) | соответствующие check-*.sh |
| Очередь перед рестартом | этот файл (Tier-1) | ручная проверка владельцем |
| Процесс wayfinder → исполнение | этот файл (Tier-1) | manual review |

Все перечисленные скрипты — `[bootstrap]`: создаются при bootstrap и
подключаются к pre-commit/CI. Отсутствие скрипта **не отменяет** правило —
правило проверяется вручную до его появления.

## Changelog

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