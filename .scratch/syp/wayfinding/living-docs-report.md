## Living Documentation развёрнута — PR #3 открыт

**Ветка**: `003-bootstrap-living-docs` → [PR #3](https://github.com/svoemesto/syp/pull/3)

### Что сделано

1. **Каркас `docs/`**: system, domains, adr, epics, guidelines, templates,
   public, howto, features, scripts. Шаблоны и линтер — побайтовая копия из
   скилла `bootstrap-living-docs`.
2. **Шесть доменов** с глоссарием из решений карты #221: catalog, analysis,
   characters, annotation, selection, showcase.
3. **Восемь ADR** из решений владельца — раньше они были только в карте и
   спеке, в репозитории не зафиксированы нигде.
4. **`docs/system/`**: контекст системы и шесть контейнеров с разграничением
   админки и публичной части. `docs/BACKLOG.md`, индекс плейбуков.
5. **`AGENTS.md` 0.2.0 → 0.3.0** (semver bump): Hard Gate «документация прежде
   поиска», State Mutation Lifecycle, протокол инициализации субагентов,
   симметрия инструкций, правило имени ветки.
6. **`CLAUDE.md`** — тонкий указатель со ссылками на секции `AGENTS.md`,
   по конвенции Karaoke (там он тоже указатель, а не копия).
7. **`tools/reserve-branch-number.sh`** — перенос из Karaoke, sha256 совпадает.

### Правило именования веток — как в Karaoke

Вы указали, что ветка должна начинаться с номера. Посмотрел, как это устроено
в Karaoke: номер не выдумывается, а **резервируется скриптом** — атомарный push
уникального lightweight-тега `refs/tags/seq/NNN` в origin даёт
сравнение-и-обмен без сервера и базы. Скрипт перенесён побайтово.

Переименованы обе существовавшие ветки по правилу:

| Было | Стало | Тикет трекера |
|---|---|---|
| `governance-constitution` | `002-governance-constitution` | — |
| `bootstrap-living-docs` | `003-bootstrap-living-docs` | #227 |

Теги `seq/002` и `seq/003` в origin.

### Что показал линтер

`python3 docs/scripts/lint-docs.py` — проходит без ошибок. По ходу линтер
поймал нарушение моей конвенции: я создал README в `docs/epics/`, но линтер
требует, чтобы файлы эпиков были упомянуты в доменной документации. Посмотрел
живые проекты (`recce`, `transport`) — там в `epics/` только файлы эпиков, без
README. README убран.

### Про `CLAUDE.md` — нужен ваш ответ

Скилл `bootstrap-living-docs` требует **идентичности** `CLAUDE.md` и
`AGENTS.md`. Но в Karaoke на практике `CLAUDE.md` — **тонкий указатель** со
ссылками на секции `AGENTS.md`, а не копия. Я сделал как в Karaoke и указал
это в PR. Если хотите полную копию — скажите, поменяю.

### Статус блокеров для плана

| Блокер | Статус |
|---|---|
| `constitution.md` | написан, PR #2 ждёт одобрения |
| git-репозиторий | создан, `master` защищён |
| living docs | развёрнуты, PR #3 |
| `tools/check-*.sh` `[bootstrap]` | отсутствуют, правила проверяются вручную |
