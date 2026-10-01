Map: #221
Type: infrastructure task
Status: OPEN
Blocked by: —

# Living Documentation: развернуть фреймворк SSoT в SYP

## Зачем

`AGENTS.md` SYP (Tier-1 Hard Gate «Living docs SSoT») требует живой
документации как единственного источника правды, но инфраструктуры под неё
не было: отсутствовали `docs/README.md`, `docs/domains/`, `docs/adr/`,
`docs/templates/`, линтер. Агент рисковал нарушить собственное правило
MUST #0 — и это не теория: в Karaoke прецедент spec #339, где агент изобрёл
форму кеша вместо существующего паттерна, потому что не прочитал домен.

## Что сделано

1. Установлены три скилла living docs: `bootstrap-living-docs`,
   `managing-living-docs`, `audit-living-docs` (копии из
   `agents-team-srv/.agents/skills`, источник не изменялся).
2. Развёрнута структура `docs/`: system, domains, adr, epics, guidelines,
   templates, public, howto, scripts, features.
3. Скопированы шаблоны документов и линтер `docs/scripts/lint-docs.py`.
4. В `AGENTS.md` внедрены четыре обязательных блока: Hard Gate (документация
   прежде поиска), State Mutation Lifecycle, симметрия `CLAUDE.md` ==
   `AGENTS.md`, протокол инициализации субагентов.
5. Создан `CLAUDE.md` как точная копия `AGENTS.md`.

## Что осталось

- Наполнить L1-карту `docs/README.md` доменами SYP (шаблон развёрнут, содержание пустое).
- Перенести решения карты #221 в `docs/adr/` (18 развилок — это готовый материал для ADR).
- Создать `docs/system/01-context.md` и `02-containers.md` (шесть контейнеров).
- Создать `docs/BACKLOG.md` — на него уже ссылается `AGENTS.md`.
- Синхронизировать `CLAUDE.md` после каждой правки `AGENTS.md`.
- Правило именования веток (`<NNN>-<slug>`) в конституцию — PR #1.
