# Факты об окружении SYP — сняты агентом 2026-10-01

> Назначение: фактическая база для карты решений. Каждая строка — проверена
> командой; перепроверяется владельцем по той же команде.
> Источники команд — в колонке «Как проверено».

## 1. Трекер

| Факт | Как проверено |
|---|---|
| OpenProject поднят, отвечает за 31 мс | `./tools/tracker.sh healthcheck` |
| Проект `syp`, **id 4**, создан 2026-10-01 | `./tools/tracker.sh list-projects` |
| Задача **#219 «Проект SYP»** — статус `New`, assignee `ai agent`, type `Task` | `./tools/tracker.sh list-issues --project-id 4 --status open` |
| CLI проекта: `/home/nsa/syp/tools/tracker.sh` v0.1.0-syp (порт из Karaoke, provenance — `PROVENANCE-tracker-cli.txt`) | `head -50 tools/tracker.sh` |

## 2. Железо и GPU

| Факт | Как проверено |
|---|---|
| CPU: **36 ядер**, RAM **62 ГБ** | `nproc`, `free -g` |
| GPU в моём sandbox **не виден**: `nvidia-smi` → «couldn't communicate with the NVIDIA driver», `/dev/nvidia*` отсутствуют | `nvidia-smi`, `ls /dev/nvidia*` |
| Причина: окружение агента — **bwrap-песочница** (`/proc/1/comm` = `bwrap`), device-ноды фильтруются | `[ -f /.dockerenv ]`, `cat /proc/1/comm` |
| **Железо GPU есть**: NVIDIA GeForce **RTX 4060 Ti** (PCI 65:00.0, AD106) | `lspci \| grep -i nvidia` |
| Драйвер **загружен** в ядре: 580.178.04, модули `nvidia_uvm`, `nvidia_drm`, `nvidia_modeset` | `cat /proc/driver/nvidia/version`, `lsmod \| grep nvidia` |
| `nvidia-container-toolkit` установлен на хосте: `/usr/bin/nvidia-container-runtime`, `nvidia-container-cli`, `nvidia-ctk` | `which` |
| Docker **зарегистрировал runtime `nvidia`**: `Runtimes: io.containerd.runc.v2 nvidia runc` | `docker info` |
| **GPU работает из контейнеров**: `whisper-asr` (cuda) Up 5 hours (healthy) | `docker ps` |
| Параметры GPU: **16380 MiB** VRAM, из них 2594 MiB занято соседним проектом (whisper) | `docker exec whisper-asr nvidia-smi --query-gpu=...` |

**Вывод для карты**: GPU для SYP **доступна** (16 ГБ VRAM, ~13.7 ГБ свободно).
Мой sandbox её не видит — это ограничение среды агента, не хоста.
Контейнеры SYP смогут получить GPU через runtime `nvidia`.
Прецедент в Karaoke: `deploy/docker-compose-app.gpu.yml` + `docker.sock` в volumes
(songcompose Pass 401 — после обновления драйвера контейнер пересоздался и GPU отвалился).

## 3. Видео

| Факт | Как проверено |
|---|---|
| Есть реальный тестовый файл: `/home/nsa/Downloads/GOT-Goblin-S1E01.mp4` | `ls -la /home/nsa/Downloads/` |
| Параметры: **848×480**, h264, **23.976 fps** (24000/1001), aac, длительность **3697.24 с** (61 мин 37 с) | `ffprobe` |
| Размер: 337 502 864 байт (~322 МБ), битрейт 730 kbps | `ffprobe` |
| **Число кадров ≈ 88 700** (3697.24 × 23.976) | расчёт |
| Полный сезон (10 серий) ≈ **887 000 кадров** | расчёт |

**Вывод для карты**: «каждый кадр проанализирован» — это 88 700 кадров на серию
и ~0.9 млн на сезон. Это главное инженерное ограничение проекта, а не деталь реализации.

## 3b. Видеоархив «Игры Престолов» (путь указан владельцем)

| Факт | Как проверено |
|---|---|
| Путь: `/disks/HDD_16Tb_Clouds/GOT` | указано владельцем 2026-10-01 |
| Структура: 8 каталогов `GOT.S01` … `GOT.S08` (сериал обрывается на 8 сезоне) | `ls` |
| Файлов: **73** `.mkv`, по 10 в S01–S06, 7 в S07, 6 в S08 | `find -iname '*.mkv' \| wc -l` |
| Суммарный объём: **360,0 ГБ**, средний файл 4,93 ГБ | `find -printf '%s'` + awk |
| Именование: `GOT.S01E01.BDRip.1080p.mkv` — сезон+серия+метка в имени файла | `ls GOT.S01` |
| Параметры (ffprobe, 72 из 73 файлов): **1920×1080**, **h264**, **24000/1001 fps** (23.976) — во всех одинаковые | `bench/probe-73-episodes.tsv` |
| Суммарная длительность: **69,1 часа**; средняя серия **57,6 мин** | awk по probe-TSV |
| **Суммарно кадров: 5,96 млн**; средняя серия **82 834 кадра** | расчёт |
| Субтитров/сторонних файлов в архиве нет | `find ! -iname '*.mkv'` → пусто |

**Проекции хранения** (расчёт от 5,96 млн кадров):
| Что храним | Объём на весь сериал |
|---|---|
| Все кадры 1080p как JPEG (q2, ~150 КБ) | **~874 ГБ** |
| Все кадры 135×75 (как база анализа в ivfx4) | **~17,5 ГБ** |
| Один кадр 1080p raw | 3,1 МБ |

**Вывод для карты**: «каждый кадр проанализирован» для сериала = **5,96 млн детектов лиц**.
Это не деталь реализации, а главный инженерный бюджет проекта. Диск (16 ТБ)
874 ГБ вмещает, но решение «хранить все кадры» — архитектурное и обратимое плохо.
Разрешение источника (1080p) в **6,5 раз** больше тестового файла из Downloads (480p).

## 4. Инструменты

| Факт | Как проверено |
|---|---|
| ffmpeg / ffprobe **6.1.1-3ubuntu5** установлены в системе | `ffmpeg -version` |
| Python3 есть | `which python3` |
| node **v25.7.0**, npm **11.10.1** в среде агента (в проекте Karaoke используется node 22 в контейнерах `node:22-alpine`) | `node -v`, `npm -v` |

## 5. Свободные порты (занятые — чтобы НЕ выбрать)

Заняты: **7906** (karaoke-webvue3), **7907** (karaoke-public), **7980**, **7981**,
**9001** (karaoke-storage MinIO), **9088**, **8080** (OpenProject), **8897/8898** (karaoke-app),
**80**, **3080** (DSH GUI), **11434** (Ollama), **5434/5435**, **6379**, **6432**.
Свободны в диапазоне 79xx: **7910–7999** (кроме 7980/7981).

**Вывод для карты**: порты SYP назначаются в диапазоне 7910–7999 (веб) и 9020–9099
(хранилище), фиксируются в `deploy/.env` и в `AGENTS.md` при bootstrap.

## 6. Состояние репозитория

| Факт | Как проверено |
|---|---|
| `/home/nsa/syp` был пуст: нет git, нет кода, нет `docs/`, нет gradle, нет `deploy/` | `ls -la /home/nsa/syp` |
| Создано за сессию: `AGENTS.md`, `tools/tracker.sh`, `tools/tracker-lib.sh`, `.env.local-tracker`, `.gitignore`, `docs/`, `logs/`, `.scratch/syp/research/` | `ls -la /home/nsa/syp` |
| **git-репозитория нет** — все правила git-gate из `AGENTS.md` пока невыполнимы | `git status` в syt → «not a git repository» |