# SYP — исследование 01b: видео-пайплайн legacy-проекта ivfx4

Источник: `/home/nsa/ivfx4/legacy-analysis/02-video-pipeline.md` (1142 строки, HEAD `a3003b7`).
Ссылки в квадратных скобках — строки источника: `[02:строки 85–93]`.
Дополнительный контекст — `.scratch/syp/research/00-environment-facts.md` и измерения
на машине `nsa-i9`, переданные в задании (помечены «ИЗМЕРЕНО»).

Код из `/home/nsa/ivfx4` не переносится. Ниже — только факты и выводы.

---

## 0. Границы отчёта

**Что охвачено:** видео-пайплайн legacy — операции, пороги, I-кадры, нарезка,
склейка, идемпотентность, дефекты [02:строки 1–8].

**Что НЕ охвачено:** модель данных (это второй субагент), распознавание лиц
(в отчёте есть только точки входа — `DetectFaces`/`RecognizeFaces` [02:строки
61–64]), UI-разметка шотов/сцен, редактор фильтров.

**Ключевой контекст legacy:** JavaFX + Spring Boot Data JPA + H2, десктоп,
Windows-ориентированный [02:строки 6–8]. То есть JPA, `.exe` внутри JAR, `.cmd`,
Sikuli, `\`-разделители [02:строки 6, 574, 603].

**Главная архитектурная особенность legacy-пайплайна, определяющая всё остальное:**
видео **полностью распаковывается в JPEG-кадры в трёх разрешениях**
(135×75 / 720×400 / 1920×1080) [02:строки 56–58, 82–85], и все последующие
этапы — детекция границ, детекция лиц, превью — работают **по файлам на диске,
а не по видеопотоку**. Это и сила (любой кадр доступен за O(1)), и источник
всех дефектов (см. §7).

---

## A. Все операции

### A.1 Сводная таблица 17 операций

Порядок в таблице UI и в `doActions()` [02:строки 50–70].

| # | Операция | Класс | Внешние программы | Что создаёт | Таблицы |
|---|---|---|---|---|---|
| 1 | `CreatePreview` | CreatePreview | ffmpeg (bramp) | `Preview/<shortName>/<shortName>_preview.mp4` | — |
| 2 | `CreateLossless` | CreateLossless | ffmpeg | `Lossless/<shortName>/<shortName>_lossless.mkv` | `tbl_properties` (`@typeorder`) |
| 3 | `CreateFramesSmall` | CreateFramesSmall | ffmpeg | `Frames_Small/<shortName>/<shortName>_frame_%06d.jpg` (135×75, q1) | — |
| 4 | `CreateFramesMedium` | CreateFramesMedium | ffmpeg | `Frames_Medium/<shortName>/…_%06d.jpg` (720×400, q2) | — |
| 5 | `CreateFramesFull` | CreateFramesFull | ffmpeg | `Frames_Full/<shortName>/…_%06d.jpg` (1920×1080, q2) | — |
| 6 | `AnalyzeFrames` | AnalyzeFrames | ffprobe (сырой вывод) + Sikuli `Finder` | — | `tbl_frames` (чистый SQL INSERT + `frameRepo.saveAll`) |
| 7 | `CreateShots` | CreateShots | — | — | `tbl_shots` (`deleteAll` + `save` в цикле) |
| 8 | `DetectFaces` | DetectFaces | `py detect_faces_in_folder.py` (OpenFace) | `<Faces_Full>/<shortName>/*.jpg`, `Frames_Full/<shortName>/frames.json` | — |
| 9 | `CreateFaces` | CreateFaces | — | — | `tbl_faces` (`createOrUpdate`) |
| 10 | `CreateFacesPreview` | CreateFacesPreview | нет (Java2D/ImageIO) | `<Faces_Preview>/<shortName>/…_face_%02d.jpg` (75×75, expand 1.4) | — |
| 11 | `RecognizeFaces` | RecognizeFaces | `py recognize_faces.py` | правит `Faces_Full/…/faces.json` на месте | `tbl_faces` |
| 12 | `CreateShotsCompressedWithAudio` | — | ffmpeg, 1 процесс на шот | `Shots/<shortName>/…_shot_[…]-(f-l).{mp4\|mkv\|mxf}` | `tbl_properties` |
| 13 | `CreateShotsLosslessWithAudio` | — | ffmpeg | `Shots_LL_audioYES/<shortName>/…_audioON.mxf` | `tbl_properties` |
| 14 | `CreateShotsLosslessWithoutAudio` | — | ffmpeg | `Shots_LL_audioNO/<shortName>/…_audioOFF.mxf` | `tbl_properties` |
| 15 | `CreateConcat` | — | ffmpeg (`-f concat`) | `Concat/<shortName>.txt` (временно) + `Concat/<shortName>_concat.<ext>` | — |
| 16 | `CreateFilterResult` | — | ffmpeg (`-f concat`) | `Filters/<filter> [<f1>-<f2>].<ext>` + временный `.txt` | — |
| 17 | `doTrainFaceModel` | в `ProjectActionsFXController`, не в `projectactions` | `py train_model_json.py` | `<project>/embeddings.json`, `recognizer.pickle`, `le.pickle` | — |

Флаги готовности — 14 ленивых геттеров в `FileExt` (`hasPreview`, `hasLossless`,
`hasFramesSmall/Medium/Full`, `hasAnalyzedFrames`, `hasCreatedShots`, `hasDetectedFaces`,
`hasCreatedFaces`, `hasCreatedFacesPreview`, `hasRecognizedFaces`,
`hasShotsCompressedWithAudio`, `hasShotsLosslessWithAudio`,
`hasShotsLosslessWithoutAudio`, `hasConcat`) [02:строки 72].

`CreateFilterResult` — единственный, кто не наследует `Thread` по общей схеме
(сигнатура per-filter, а не per-file) [02:строки 43–44].

### A.2 Иерархия папок

Enum `Folders` — 15 значений, у каждого `propertyCdfKey`, `folderName`,
флаги `forProject`/`forFile` [02:строки 430–450]. Разрешение пути — двухшаговое
[02:строки 455–470]:

- **шаг 1, проект:** `<project.folder>/<FolderName>` либо override из БД;
- **шаг 2, файл:** `<projectExt.folderX>/<file.shortName>` либо override из БД.

`project.folder` берётся по `computerId == Main.ccid` [02:строки 460–464], где
`Main.ccid = hashCode()` от `manufacturer#processorID#identifier#logicalProcessorCount`
(OSHI) [02:строки 472]. То есть путь хранится **per-machine** — проект можно
открыть с сетевого диска и пути не «поедут».

Полная иерархия (дефолт, `project.folder = <корень>`) [02:строки 478–542]:

```
<project.folder>/
├─ Lossless/<shortName>/<shortName>_lossless.mkv        ← A.оп.2, источник ВСЕЙ нарезки
├─ Preview/<shortName>/<shortName>_preview.mp4         ← A.оп.1 (libx264/aac 720×400)
├─ Shots/<shortName>/                                  ← папки шотов пользователя
│  └─ <shortName>/<shortName>_shot_[h.mm.sss-h.mm.sss]-(first-last).{mp4|mkv|mxf}
│                                                       ← A.оп.12
├─ Favorites/<shortName>/
├─ Frames_Small/<shortName>/<shortName>_frame_%06d.jpg  ← A.оп.3 (135×75, q1)
├─ Frames_Medium/<shortName>/…_%06d.jpg                 ← A.оп.4 (720×400, q2)
├─ Frames_Full/<shortName>/                             ← A.оп.5 (1920×1080, q2)
│     ├─ <shortName>_frame_%06d.jpg
│     ├─ frames.json   ← A.оп.8 пишет, в скрипт НЕ передаёт [02:строки 351]
│     └─ faces.json    ← обмен с OpenFace
├─ Faces_Full/<shortName>/<shortName>_frame_000123_face_00.jpg  ← detect_faces_in_folder.py
├─ Faces_Preview/<shortName>/…_face_%02d.jpg            ← A.оп.10 (75×75)
├─ Persons/<personId>/…                                 ← только проект
├─ Shots_LL_audioYES/<shortName>/…_audioON.mxf          ← A.оп.13
├─ Shots_LL_audioNO/<shortName>/…_audioOFF.mxf          ← A.оп.14
├─ Concat/<shortName>.txt                               ← БЕЗ подпапки shortName!
│  └─ <shortName>_concat.{mp4|mkv|mxf}                  ← A.оп.15 (remux -c copy)
├─ Filters/"<filterName> [<short1>-<short2>].<ext>"     ← A.оп.16, только проект
├─ recognizer.pickle
├─ le.pickle
└─ embeddings.json
```

Особенности иерархии, критичные для переноса:

1. **`Shots` дублируется**: и `Folders.SHOTS`, и `Folders.SHOTS_COMPRESSED_WITH_AUDIO`
   имеют `folderName = "Shots"` [02:строки 438, 445, 546]. Пользовательские папки
   шотов и нарезанные видеофайлы физически делят каталог; разводятся только
   через `propertyCdf`.
2. **`Concat` без подпапки файла** [02:строки 548, 531–533, 1122]. Различение —
   по префиксу `<shortName>_` в именах.
3. **`Frames_Full` — «конверт» для JSON**: `frames.json` и `faces.json` лежат
   прямо в папке кадров [02:строки 506–510, 550].
4. **Оверрайды в `tbl_property_cdf`**, привязанные к
   `(parentClass, parentId, computerId, key)`; создаются лениво через
   `getOrCreate`; пустое значение = дефолт [02:строки 552].
5. **`mkdir()`, не `mkdirs()`** — папка создаётся лениво и в один уровень, только
   для своей папки [02:строки 554, 1123]. При первом запуске операции родительская
   папка должна существовать, иначе вложенный каталог молча не создаётся.

### A.3 Полные команды ffmpeg

Порядок аргументов восстановлен автором отчёта из байткода bramp 0.6.2
(`javap` по `ffmpeg-0.6.2.jar`) [02:строки 630–650]:

```
FFmpegBuilder.build():
  [ffmpeg] -y (при overrideOutputFiles) -v error        ← verbosity по умолчанию = ERROR
  [startOffset -ss] [format -f] [-re]
  [-progress tcp://localhost:PORT]                      ← автоматически, если передан ProgressListener
  <extra_args билдера>                                  ← ВСЕГДА перед -i
  -i <input>
  [-filter_complex] [-af builder.videoFilter]           ← БАГ bramp: -af
  [-vf builder.audioFilter]                             ← БАГ bramp: -vf
  <outputs>
AbstractFFmpegStreamBuilder.build(): -strict.. | -vn | -acodec .. -ac .. -ar .. -apre .. | -an
                                    | -spre.. | -sn | <output.extra_args> | <filename>
FFmpegOutputBuilder.build():          -crf | -b:v | -qscale:v | -vpre | -vf | -bsf:v
                                    | -sample_fmt | -b:a | -qscale:a | -bsf:a | -af
```

Уникальные команды (в отчёте заявлено «12 штук» [02:строки 652], фактически
перечислено 11 — небольшая неточность самого отчёта):

```bash
# (1) CreateFramesSmall — 135×75, qscale 1, БЕЗ video-фильтра
ffmpeg -y -v error -progress tcp://localhost:PORT -qscale:v 1 \
       -i <file> -vframes <N> -s 135x75 <Frames_Small>/…/<shortName>_frame_%06d.jpg

# (2) CreateFramesMedium — 720×400, qscale 2, scale+pad
ffmpeg -y -v error -progress tcp://localhost:PORT -qscale:v 2 \
       -i <file> -vframes <N> -s 720x400 -af "scale=720:H,pad=720:400:0:Y:black" <…_frame_%06d.jpg

# (3) CreateFramesFull — 1920×1080, qscale 2, scale+pad
ffmpeg -y -v error -progress tcp://localhost:PORT -qscale:v 2 \
       -i <file> -vframes <N> -s 1920x1080 -af "scale=W:1080,pad=1920:1080:X:0:black" <…_frame_%06d.jpg

# (4) CreatePreview — единственная «играбельная» операция, жёстко libx264/aac/500k
ffmpeg -y -v error -progress tcp://localhost:PORT \
       -i <file> -vcodec libx264 -s 720x400 -acodec aac -ar 48000 -ac 2 \
       -af "scale=720:H,pad=720:400:0:Y:black" -b:v 500000 -b:a 196608 \
       <Preview/…/<shortName>_preview.mp4

# (5) CreateLossless (MKV, DNX) — база для нарезки
ffmpeg -y -v error -progress tcp://localhost:PORT \
       -i <file> -vcodec dnxhd -s 1920x1080 -acodec pcm_s16le -ar 48000 \
       -map 0:v:0 -map 0:a:<typeOrder-1> \
       -b:v 36M -vf "scale=W:H,pad=1920:1080:X:Y:black" <Lossless/…/<shortName>_lossless.mkv

# (5a) CreateLossless (MKV, RAW)
ffmpeg … -vcodec rawvideo … -pix_fmt yuv420p -b:v 36M -vf … <…_lossless.mkv>

# (6) CreateShotsCompressedWithAudio — режет LOSSLESS, НЕ исходник
ffmpeg -y -v error -ss <start_sec> \
       -i <Lossless/…/_lossless.mkv> -vcodec libx264 -s 1920x1080 \
       -acodec aac -ar 48000 -map 0:v:0 -map 0:a:<typeOrder-1> \
       -vframes <framesToCode> -b:v <project.videoBitrate> -b:a <project.audioBitrate> \
       -vf "scale=W:H,pad=1920:1080:X:Y:black" <Shots/…/<shot>.mp4>

# (7) CreateShotsLosslessWithAudio — setVideoFilter ЗАКОММЕНТИРОВАН
ffmpeg -y -v error -ss <start_sec> \
       -i <Lossless/…/_lossless.mkv> -vcodec dnxhd -s 1920x1080 \
       -acodec pcm_s16le -ar 48000 \
       -map 0:v:0 -map 0:a:<typeOrder-1> -vframes <framesToCode> -b:v 36M <…_audioON.mxf>

# (8) CreateShotsLosslessWithoutAudio — БЕЗ -acodec/-ar, но -map 0:a: ОСТАЁТСЯ
ffmpeg -y -v error -ss <start_sec> \
       -i <Lossless/…/_lossless.mkv> -vcodec dnxhd -s 1920x1080 \
       -map 0:v:0 -map 0:a:<typeOrder-1> -vframes <framesToCode> -b:v 36M <…_audioOFF.mxf>

# (9) CreateConcat
ffmpeg -y -v error -f concat -safe 0 \
       -i <Concat/…/<shortName>.txt> \
       -map 0:v:0 -map 0:a:<typeOrder-1> -c copy <Concat/…/<shortName>_concat.<ext>

# (10) CreateFilterResult — идентична (9), вход/выход в Filters/
ffmpeg -y -v error -f concat -safe 0 \
       -i <Filters>/…/"<filter> [<f1>-<f2>].txt" \
       -map 0:v:0 -map 0:a:<typeOrder-1> -c copy <Filters>/…/"<filter> [<f1>-<f2>].<ext>"
```

Три механизма вызова внешних программ [02:строки 562–566]:

| Механизм | Где | Парсинг вывода |
|---|---|---|
| bramp `FFmpegWrapper` (ProcessBuilder внутри библиотеки) | 12 project actions | не парсится; прогресс через `-progress tcp://localhost:<port>` + `ProgressListener` |
| bramp `FFprobe` | `getListIFrames`, `getFFmpegProbeResult/getFps/getFramesCount` | JSON-десериализация внутри bramp |
| сырой `ProcessBuilder` | `IvfxUtils.executeExe`, `MediaInfo.executeMediaInfo`, `RunCmd.exec` | regex / Gson |

Прогресс ffmpeg: ffmpeg получает `-progress tcp://localhost:<ephemeral-port>`,
поднимается локальный сокет, ffmpeg шлёт блоки
`frame=…/fps=…/out_time_ns=…/speed=…`; приложение парсит их в `Progress` и зовёт
`ProgressListener.progress(...)` [02:строки 715]. Ручного парсинга stderr нет.

Формат прогресса в UI [02:строки 718–723]:
```kotlin
val percentage2 = progress.out_time_ns / duration_ns
String.format("[%.0f%%] status: %s, frame: %d, time: %s ms, fps: %.0f, speed: %.2fx", …)
```

Прогресса ffmpeg НЕТ в: `CreateShotsCompressedWithAudio` [02:строки 268],
`CreateShotsLossless*`, `CreateConcat`, `CreateFilterResult`, `CreateFaces`,
`CreateFacesPreview`, `DetectFaces`, `RecognizeFaces` [02:строки 724]. Там прогресс
считается по индексу цикла либо `ProgressBar.INDETERMINATE_PROGRESS`.

---

## B. Алгоритмы границ планов (шотов) и сцен

### B.1 Шоты: автоматически, но через сравнение КАЖДОГО кадра с КАЖДЫМ

Цепочка: `FrameController.createFrames` → `AnalyzeFrames` → `CreateShots`
[02:строки 848].

**Шаг A — строки кадров.** Чистый SQL `INSERT … SELECT` с CROSS JOIN шести таблиц
`SELECT 0…9` — генерирует числа 1…`framesCount+1` и вставляет в `tbl_frames`.
Никакого ffmpeg [02:строки 850].

**Шаг B — I-кадры.** `getListIFrames(mediaFile, fps)`, см. §C.

**Шаг C — simScore (Sikuli).** [02:строки 855–866]
```kotlin
Settings.MinSimilarity = 0.0
val f = Finder(currentFrameExt.pathToSmall)      // 135×75 JPEG
f.find(Pattern(frameNextExt.pathToSmall))
simScore = if (f.hasNext()) f.next().score else 0.0
```
Один `Finder` переиспользуется для трёх сравнений «вперёд» (i+1, i+2, i+3).
Для каждой пары записываются **оба** направления:
`simScoreNextN(i) = similarity(frame_i, frame_{i+N})` и
`simScorePrevN(i) = similarity(frame_{i-N}, frame_i)`.
Хранится в `tbl_frames`: `sim_score_next_1/2/3`, `sim_score_prev_1/2/3`.

**Шаг D — diff.** [02:строки 869–875]
```kotlin
diffNext1 = abs(simScoreNext1(i)   - simScoreNext1(i+1))
diffNext2 = abs(simScoreNext1(i+1) - simScoreNext1(i+2))
diffPrev1 = abs(simScorePrev1(i-1) - simScorePrev1(i))
diffPrev1 = abs(simScorePrev1(i-2) - simScorePrev1(i-1))   // ← БАГ: перезапись
```
`diffPrev2` **никогда не вычисляется** — колонка `diff_prev_2` в БД есть, но
всегда 0.0. Второе присваивание в `diffPrev1` затирает первое.

**Шаг E — детект переходов, пороги.** [02:строки 878–891]
```kotlin
val diff1 = 0.4    // Порог обнаружения перехода
val diff2 = 0.42   // Вторичный порог
if (frame.simScorePrev1 < diff1) {
    if (frame.diffPrev1 > diff2 || frame.diffPrev2 > diff2) { isFind = isFinalFind = true }
    else                                                { isFind = isFinalFind = false }
} else if (frame.diffPrev1 > diff2 && frame.diffPrev2 > diff2 && frame.simScoreNext1 > diff1) {
    isFind = isFinalFind = true
} else { isFind = isFinalFind = false }
```
Практический смысл с учётом `diffPrev2 ≡ 0.0` [02:строки 893–896]:

- **ветка 1 срабатывает:** `simScorePrev1 < 0.4` И `diffPrev1 > 0.42`;
- **ветка 2** (с `diffPrev2 > 0.42`) — **мёртвая, недостижима**;
- `simScoreNext1 > 0.4` из второй ветки тоже выпадает.

Дополнительно сбрасываются `isManualAdd`/`isManualCancel` в `false` — ручные
правки переживают только до повторного `AnalyzeFrames` [02:строки 897, 1113].

**Шаг F — сохранение.** `Main.frameRepo.saveAll(listFrames)` [02:строки 899].

**Шаг G — нарезка на шоты.** [02:строки 205–224, 901–907]
```
шот = [firstFrameNumber, (кадрПерехода − 1)]
firstFrameNumber := кадрПерехода
nearestIFrame     := последний I-кадр, встреченный до границы
```
Последний шот закрывается последним кадром файла
(`frame == listFrames.last()` → `lastFrameNumber = currentFrameNumber`) [02:строки 211–212].

`Shot` (`tbl_shots`): `file_id`, `shot_type_person`
(ShotTypePerson: NONE/SGN/OTS/TWO/GRP/MASS), `first_frame_number`,
`last_frame_number`, `nearest_i_frame` [02:строки 226].

`nearestIFrame` — «ключевой кадр», с которого начинается GOP, содержащий шот;
используется для точного seek при нарезке lossless-шотов [02:строки 905–907].

### B.2 Сцены: ручные, автодетекта нет

`SceneController` не имеет никакого `detect/createFromFrames` — только
`getOrCreate(file, firstFrameNumber, lastFrameNumber)` и
`createSceneExt(listShotsExt)` [02:строки 913].

Сцены создаются **вручную** в UI из выделенных пользователем шотов
[02:строки 915–928]:
```kotlin
for ((i, shotExt) in selectedShotsExtSorted.withIndex()) {
    if (i < selectedShotsExtSorted.size - 1) {
        if (shotExt.shot.lastFrameNumber + 1 == selectedShotsExtSorted[i+1].shot.firstFrameNumber) listShotsExt.add(shotExt)
        else break                          // ← разрыв в нумерации → сцена обрывается
    } else listShotsExt.add(shotExt)
}
```
То есть **сцена = максимальный непрерывный ряд шотов без разрыва в нумерации кадров**.
Имя по умолчанию `"Scene $firstFrameNumber-$lastFrameNumber"`, далее
предлагается пользователю в `TextInputDialog` [02:строки 926, 942].

`SceneController.getOrCreate` разрезает/удаляет пересекающиеся сцены
[02:строки 930–943]:
```kotlin
if (currentScene.first < first && currentScene.last > last) {
    // новая сцена [last+1 .. currentScene.last]; currentScene.last = first-1
} else if (currentScene.first < first && currentScene.last >= first) currentScene.last = first - 1
else if (currentScene.first <= last && currentScene.last > last)      currentScene.first = last + 1
else delete(currentScene)
```
Алгоритм разрезания на три случая (сцена целиком внутри / пересекает слева /
пересекает справа) — **переносимый как есть**, единственный по-настоящему
ценный алгоритм разметки в legacy.

`ShotExt.sceneExt` ищет сцену, **целиком содержащую** шот [02:строки 946–950]:
```kotlin
val sceneExt: SceneExt? get() = fileExt.scenesExt.firstOrNull {
    shot.firstFrameNumber >= it.scene.firstFrameNumber && shot.lastFrameNumber <= it.scene.lastFrameNumber }
```
Рендерится цветными полосами поверх превью кадров: `setOverlayIsBodyScene`
(оранжевая полоса слева, вся высота), `setOverlayIsStartScene` (полоска сверху),
`setOverlayIsEndScene` (снизу) [02:строки 951].

`Event` (`tbl_events`) — ручная сущность того же типа, что и `Scene`
(событие внутри шота, зелёная маркировка) [02:строки 953].

### B.3 Дискретизация кадров для детекции лиц

`FaceController.getFramesToRecognize`: шаг зависит от длины шота —
**3 / 5 / 10 / 20** кадров для шотов короче 15 / 30 / 60 / длиннее 60 секунд
[02:строки 349]. Порог присвоения персоне — `recognizeProbability > 0.3`, иначе
`UNDEFINDED`; при аспект-отношении лица `d > 4` → `NONPERSON` [02:строки 363].

---

## C. Определение I-кадров

Единственный инструмент — `ffprobe` с `-skip_frame nokey`, функция
`IvfxFFmpegUtils.getListIFrames`, используется **только** `AnalyzeFrames`
[02:строки 607–617, 852].

```kotlin
param.add("-skip_frame");   param.add("nokey");
param.add("-select_streams"); param.add("v");
param.add("-show_frames");  param.add(mediaFile);
executeExe(FFPROBE_PATH, param)
```

Команда [02:строки 615–617]:
```
ffprobe -skip_frame nokey -select_streams v -show_frames <mediaFile>
```

Парсинг — **хрупкий, жёстко завязан на CRLF** [02:строки 618–624]:
```kotlin
val regExp = "(?<=\\[FRAME\\]\\r\\n)[\\w|\\W]+?(?=\\[/FRAME\\]\\r\\n)")
// внутри блока ищем строку "pkt_pts=…" → list.add(getFrameNumberByDuration(findedResult, fps))
getFrameNumberByDuration(duration, fps) = round(duration / (1000/fps) + 1)
```
**На Linux (где ffprobe печатает `\n`) regex не сматчится → пустой список
I-кадров** [02:строки 624]. Следствие: `frame.isIFrame = listIFrames.contains(frameNumber)`
всегда `false` [02:строки 852] → `nearestIFrame` в шоте бессмыслен, поле «I-кадр»
в UI пустое.

Точность перевода PTS→номер кадра: `round(duration / (1000/fps) + 1)` — округление
смещено на +1 и опирается на строковый парсинг `pkt_pts=`, а не на
`best_effort_timestamp`. Для 23.976 fps `1000/fps = 41.7083…` мс; на длинных
роликах накопление округления даёт смещение на 1–2 кадра к концу.

Отдельно: bramp-`FFprobe.probe()` используется ещё в
`getFFmpegProbeResult/getFps/getFramesCount` [02:строки 626], и число кадров
берётся из нестандартного тега `NUMBER_OF_FRAMES-eng` [02:строки 96] —
на Linux-ffprobe 6.1.1 тег может отсутствовать, и тогда `setFrames(countFrames ?: 1)`
поставит `-vframes 1` [02:строки 102, 125] — то есть извлечётся **один** кадр
вместо всех. В отчёте это не отмечено как дефект; риск выявлен по коду.

`FFPLAY_PATH` объявлен, но нигде не используется [02:строки 572, 711].

---

## D. Нарезка и склейка

### D.1 Нарезка (shot cut)

Три нарезочных класса (`CreateShotsCompressedWithAudio`,
`…LosslessWithAudio`, `…LosslessWithoutAudio`) — один и тот же цикл; различаются
кодеком и аудио [02:строки 300–324, 682–698].

Порядок операций внутри цикла по `shotsExt` [02:строки 243–270]:

1. `if (!File(fileOutput).exists())` — **пропуск существующих**;
2. `firstFrame = shot.firstFrameNumber`, `lastFrame = shot.lastFrameNumber`,
   `framesToCode = lastFrame - firstFrame + 1`;
3. `start = (getDurationByFrameNumber(firstFrame - 1, frameRate).toDouble() / 1000).toString()`;
4. `-ss <start>` идёт **до** `-i` (входной seek), только если `firstFrame != 0`;
5. вход — **`Lossless/…/_lossless.mkv`, не исходник** [02:строки 238];
6. `-map 0:v:0` + по одному `-map 0:a:<typeOrder-1>` на каждую включённую
   аудиодорожку (`Track.type == "Audio" && Track.use`, `typeOrder` — property
   `@typeorder`, дефолт `"1"` → индекс 0) [02:строки 258];
7. `-vframes <framesToCode>`;
8. `-s <project.width>x<project.height>` + `-vf "scale=…,pad=…:black"`;
9. кодек/битрейт — из `tbl_projects` (в отличие от превью, жёстко зашитого на
   libx264/aac/500k) [02:строки 154, 261–266];
10. `FFmpegExecutor(ffmpeg).createJob(builder).run()` — **без ProgressListener**,
    результат (`job.run()`) не проверяется.

Перевод кадра в секунды [02:строки 276–282]:
```kotlin
fun getDurationByFrameNumber(frameNumber: Int, fps: Double): Int {
    val dur1fr = 1000 / fps
    var durDouble = (frameNumber - 0) * dur1fr
    if (durDouble < 0) durDouble = 0.0
    return floor(durDouble).toInt()
}
```

Имя файла шота [02:строки 284–290]:
```kotlin
val start = convertDurationToString(getDurationByFrameNumber(firstFrameNumber - 1, fps))  // "0:00:03.125"
val end   = convertDurationToString(getDurationByFrameNumber(lastFrameNumber, fps))
val filenameWithoutExt = "${shortName}_shot_[${start.replace(":",".")}-${end.replace(":",".")}]-(${firstFrameNumber}-${lastFrameNumber})"
```
Имя **несёт и секунды, и номера кадров** — очень удачно для SYP: кадровые номера
в имени делают границы проверяемыми без обращения к БД.

### D.2 ВНУТРЕННЕЕ ПРОТИВОРЕЧИЕ нарезки (выявлено по приведённому коду)

`-ss` ставится на таймстемп кадра `firstFrame − 1` [02:строки 249], а кодируется
`framesToCode = lastFrame − firstFrame + 1` кадров [02:строки 248]. Значит файл
шота содержит кадры **[firstFrame−1 … lastFrame]** — на **один кадр длиннее**
объявленного диапазона, с лишним ведущим кадром.

Расчёт субагента (не из отчёта): при ~100 шотах на серию и 23.976 fps набегает
≈ 100/23.976 ≈ 4.2 с дрейфа по всей серии, а на каждом стыке concat — смещение
на 1 кадр. Это наиболее правдоподобная причина «микро-расхождений на стыках»,
которые отчёт фиксирует со ссылкой на `-c copy` [02:строки 414, 739]. В отчёте
само расхождение **не разобрано**. Требует проверки на тестовом файле.

### D.3 Склейка (concat)

`CreateConcat` — все шоты файла [02:строки 386–412, 1006–1054].
`CreateFilterResult` — произвольный набор шотов (выборка фильтра) [02:строки
416–422, 1086–1103].

Шаг 1 — текстовый список (создаётся и **удаляется** в конце)
[02:строки 1008–1014]:
```
file '<abs>/Shots/<shortName>/<shortName>_shot_[0.00.04.167-0.00.07.125]-(1-73).mp4'
file '<abs>/Shots/<shortName>/<shortName>_shot_[0.00.07.125-0.00.11.667]-(74-193).mp4'
```
Шаг 2 — команда [02:строки 1043–1054]:
```
ffmpeg -y -v error -f concat -safe 0 \
       -i "<Concat>\<shortName>.txt" \
       -map 0:v:0 -map 0:a:<typeOrder-1> \
       -c copy \
       "<Concat>\<shortName>_concat.<mp4|mkv|mxf>"
```

Точные особенности:
- `-f concat -safe 0` идут на **вход** (до `-i`, из `FFmpegBuilder.extra_args`)
  [02:строки 1051]. `-safe 0` — потому что пути абсолютные.
- `-c copy` — **stream copy, без перекодирования**; сорца вырезается по длине шотов,
  а не по кадрам [02:строки 414, 739, 1053].
- Расширение выхода — из `project.container` (`VideoContainers`: MP4/MKV/MXF)
  [02:строки 1054].
- Прогресса нет (нет `ProgressListener` → нет `-progress tcp://`) [02:строки 1055].
- Кодировка `.txt` — платформенная (`FileWriter`) [02:строки 728].
- Различия `CreateFilterResult`: список из произвольных `shotsExt`; имя
  `"<filterName> [<firstShortName>-<lastShortName>].<ext>"`; `.txt` в каталоге
  `Filters/`; **аудио-`-map` берётся от ПЕРВОГО файла выборки**, независимо от
  того, какие файлы реально попали в список [02:строки 1057–1061].

### D.4 Код возврата подпроцесса

**Нигде не проверяется.** Ни один из 12 ffmpeg-вызовов не проверяет результат
`job.run()` [02:строки 268, 409, 1037–1038]. Исключение — `RunCmd.exec`, где
ненулевой код лишь печатается в stdout [02:строки 594–601]:
```kotlin
val r = p.waitFor()
if (r != 0) println(cmd + " cmd: output:\n" + baos)
return r
```
Возвращаемое значение `r` вызывающий код игнорирует [02:строки 346, 358–362].

В SYP это прямое нарушение существующего правила (ADR-0006, `redirectErrorStream(true)`
+ проверка кода возврата) — см. `AGENTS.md` проекта, Tier-1 Hard Gate «Стек и запреты».

---

## E. Идемпотентность

### E.1 Что уже сделано

1. **14 флагов готовности на файл** в `FileExt`, все — ленивые геттеры на
   `FileController.hasXxx(...)` [02:строки 72]. Отображаются как `✓`/`✗` в колонке
   `colFileExtCS` [02:строки 909].
2. **Пропуск по факту существования файла** при нарезке:
   `if (!File(fileOutput).exists()) { … }` [02:строки 245, 295]. Повторный запуск
   дёшево «ничего не делает».
3. **`checkReCreateIfExists`** — чекбокс в UI, учитывается при двойном проходе
   подсчёта `countActions` [02:строки 803]. Что именно он пересоздаёт — в отчёте
   **не описано**.
4. **`overrideOutputFiles(true)`** → глобальный `-y` во всех командах
   [02:строки 634]. Существующий выход перезаписывается без вопросов.
5. **Промежуточный `.txt` удаляется** после concat [02:строки 1039, 1060].
6. **`mkdir()` перед записью** своей папки [02:строки 90, 388, 554].

### E.2 Чего нет

- **Нет сверки флагов с диском.** Флаг `hasFramesFull = true` ставится сразу
  после `job.run()` [02:строки 108] — если ffmpeg упал, флаг всё равно `true`.
- **Нет проверки кода возврата** (см. §D.4) — прерванный ffmpeg даёт «успешный»
  has-флаг.
- **Нет атомарной записи**: пишется прямо в конечный путь, `-y` перетирает
  предыдущий результат [02:строки 634, 410]. Прерванный процесс оставляет
  половинный файл, который при следующем запуске будет считаться «существующим»
  и пропущен [02:строки 245].
- **Нет фиксации входных данных артефакта**: ни размера файла, ни mtime, ни
  хеша, ни версии кодека. Смена `project.width/height`, `videoBitrate`,
  `lossLessCodec` не инвалидирует ни один флаг.
- **`CreateShots` деструктивен**: `ShotController.deleteAll(file)` сносит все шоты
  по файлу, затем пересоздаёт в цикле [02:строки 207]. Перезапуск операции
  уничтожает ручную разметку, если она была внесена после.
- **`AnalyzeFrames` деструктивен по ручной разметке**: сбрасывает
  `isManualAdd`/`isManualCancel` в `false` [02:строки 897, 1113].
- **Папки создаются `mkdir`, не `mkdirs`** [02:строки 554, 1123] — при
  отсутствии родителя операция молча не создаёт вложенный каталог (ffmpeg затем
  падает, но код возврата не проверяется → флаг всё равно ставится).

---

## F. Все дефекты

### F.1 Сводный список (16 пунктов §8 отчёта) [02:строки 1107–1124]

| # | Дефект | Почему не проявлялся |
|---|---|---|
| 1 | `diffPrev2` никогда не вычисляется (`AnalyzeFrames.kt:148`); вторая ветка детекта переходов мертва [02:1109] | Порог `diff2 = 0.42` подобран так, что первая ветка покрывает почти всё; мёртвая ветка выглядит как «запасной вариант» |
| 2 | `CreateShotsLosslessWithoutAudio` всё равно мапит аудио (`-map 0:a:<n>` не закомментирован) [02:1110] | MXF-муксер сам назначает аудиокодек по умолчанию; наличие дорожки не заметно при ручной проверке одного шота |
| 3 | **БАГ bramp 0.6.2**: `FFmpegBuilder.setVideoFilter()` эмитит `-af`, `setAudioFilter()` → `-vf` [02:1111, 641–642] | Для `CreateFrames*/CreatePreview` это работало, потому что аудиодорожки нет — фильтр уходил «в никуда», но и не мешал; на `CreateLossless`/`CreateShotsCompressedWithAudio` фильтр ставится на `builderOutput.setVideoFilter()` → корректный `-vf` [02:1111] |
| 4 | `_lossless.mkv` захардкожен, даже если `project.lossLessContainer == MXF` [02:1112, 199] | Дефолт — MKV [02:1075], ветка MXF не использовалась; при MXF `dnxhd`+`pcm_s16le` в MKV не проходит — но никто не выбирал MXF |
| 5 | Ручные правки затираются `AnalyzeFrames` [02:1113, 897] | Ручную разметку обычно делали **после** автоматического анализа |
| 6 | `RunListThreads` последовательный, а не параллельный, несмотря на `List<Thread>` [02:1114, 778] | На 1 файле разницы не видно; на нескольких — просто «медленнее», но корректно |
| 7 | При прерывании `RunListThreads` флаг `flagIsDone` не выставляется → UI «зависает» [02:1115, 780] | Прерывание нажатием кнопки отмены — редкий сценарий |
| 8 | Project actions не проверяют `isInterrupted`; ffmpeg-процессы не убиваются (`job.process.destroy()` нигде нет) [02:1116, 810] | Кнопка отмены есть только в редакторе проекта; пользователи привыкли ждать |
| 9 | `CreateFilterResult(...).run()` на FX-треде блокирует UI [02:1117, 813, 1103] | Диалог с большим списком шотов всё равно требовал ожидания |
| 10 | `frames.json` мёртвый — `DetectFaces` его пишет, но в скрипт не передаёт [02:1118, 351] | Скрипту передаётся каталог, `frames.json` остаётся «на всякий случай» |
| 11 | `Folders.SHOTS` и `Folders.SHOTS_COMPRESSED_WITH_AUDIO` имеют одинаковый `folderName = "Shots"` [02:1119, 546] | Разводятся через `propertyCdf`; в дефолтной раскладке обе папки — одна и та же, но содержимое не конфликтует по именам |
| 12 | Хрупкий парсинг ffprobe: regex жёстко завязан на `\r\n` [02:1120, 618–624]; `out.substring(0, out.length - 2)` отбрасывает 2 символа вывода безусловно [02:1120, 583] | Разрабатывалось и проверялось только под Windows |
| 13 | `sourceIterable.count()` вызывается на каждой итерации во всех `LoadList*` [02:1121, 836] | На списках шотов/сцен десятки элементов — незаметно; на `tbl_frames` (82 834 на серию) — заметно, но операция идёт в фоне |
| 14 | `Folders.CONCAT` без подпапки `shortName` [02:1122, 548] | Префикс `<shortName>_` различает файлы |
| 15 | `mkdir()` вместо `mkdirs()` [02:1123, 554] | Папки верхнего уровня создавались вручную при создании проекта |
| 16 | Лейбл в UI говорит «175x35», код использует `135 × 75` [02:1124, 85] | Косметика |

### F.2 Дефекты, найденные внутри отчёта, вне сводного списка

| # | Дефект | Ссылка |
|---|---|---|
| 17 | `setVideoResolution(w,h)` вызывается, но `setVideoFilter` закомментирован в обоих lossless-классах: если lossless-файл не 1920×1080, ffmpeg масштабирует сам (без pad в чёрные поля) → геометрия кадра «поедет» относительно `CreateLossless` | [02:строки 310, 320, 324] |
| 18 | Каждый шот содержит на один кадр больше объявленного (см. §D.2) — выявлено по приведённому коду | [02:строки 248–249] |
| 19 | Число кадров берётся из тега `NUMBER_OF_FRAMES-eng`; при его отсутствии `setFrames(countFrames ?: 1)` даёт `-vframes 1` — извлекается один кадр вместо всех | [02:строки 96, 102, 125] |
| 20 | Заявлено «12 уникальных команд», перечислено 11 | [02:строки 652, 655–709] |
| 21 | `AudioCodecs.PMC` = `pcm_s16le` и `VideoCodecs.DNX` = `dnxhd` объявлены дважды, в двух енумах | [02:строки 196] |
| 22 | Дискретизация кадров для детекции лиц описана через `getFramesToRecognize`/`getFramesToDetectFaces` (3/5/10/20 по длине шота), но в §5.1 фаза детекции лиц не описана; связь «длина шота в секундах vs кадрах» не оговорена | [02:строки 349, 336–337] |

---

## G. Пригодность для SYP

### G.1 Переносится как есть (идея, не код)

| # | Что | Почему годится | Ссылка |
|---|---|---|---|
| 1 | **Шот в номерах кадров**, а не в секундах: `first_frame_number`, `last_frame_number`, `nearest_i_frame` | Точность до кадра — ровно то, что нужно SYP; секунды теряют точность | [02:строки 226, 901–907] |
| 2 | **Имя файла шота несёт и таймкоды, и номера кадров** `(74-193)` | Границы проверяемы без БД | [02:строки 288] |
| 3 | **Алгоритм разрезания пересекающихся сцен** (`getOrCreate`, три случая) | Единственный по-настоящему проверенный алгоритм разметки; переносится дословно как спецификация | [02:строки 930–943] |
| 4 | **Сцена = непрерывный ряд шотов без разрыва в нумерации** | Простое, проверяемое определение | [02:строки 918–923] |
| 5 | **Concat-demuxer: `-f concat -safe 0` + абсолютные пути + `-c copy`** | Рабочий, дешёвый способ склейки без перекодирования | [02:строки 1044–1048] |
| 6 | **Промежуточный lossless-мастер для точного seek** (намерение) | Идея «перекодировать один раз, дальше резать дёшево» верна | [02:строки 1067–1079] |
| 7 | **Прогресс через `-progress tcp://localhost:PORT`** | Работает на ffmpeg 6.1.1; пригоден как источник прогресса задания | [02:строки 634–636, 715] |
| 8 | **Двухуровневая модель папок проект→файл** | Проверенная, переносима; в SYP поверх неё — пути в БД/MinIO | [02:строки 430–474] |
| 9 | **Идемпотентность по факту существования** | Правильная идея, требует усиления | [02:строки 245] |
| 10 | **Дискретизация кадров по длине шота** (3/5/10/20) | Прямой аналог «разумного семплирования» для SYP | [02:строки 349] |
| 11 | **Разрешение проблемы «список всего подряд»**: `Filters/` — выгрузка произвольной выборки шотов в один файл | Это и есть требуемая в SYP «сборка выборок сцен в один видеофайл» | [02:строки 1086–1103] |

### G.2 Переписывается полностью

| # | Что | Почему | Ссылка |
|---|---|---|---|
| 1 | **Детекция границ шотов через Sikuli similarity** | Windows-only, O(n) сравнений, мёртвая ветка `diffPrev2`; заменяется на ffmpeg-фильтр сцен | [02:строки 855–896, 624] |
| 2 | **Извлечение ВСЕХ кадров в 3 разрешения** | 82 834 файла на серию, 5.96 млн на архив; SYP нужен другой режим | [02:строки 56–58, 82–85] |
| 3 | **Определение I-кадров regex'ом по CRLF** | Мёртв на Linux; заменяется на структурный вывод ffprobe | [02:строки 618–624] |
| 4 | **Оркестрация: `RunListThreads` на голых `Thread` + `sleep(100)`** | Нет пула, нет отмены, нет кодов возврата; в SYP — очередь заданий + worker-пул | [02:строки 747–780, 810] |
| 5 | **Обращения к ffmpeg без проверки кода возврата** | Прямое нарушение правил SYP | [02:строки 268, 409, 1037] |
| 6 | **Прогресс по индексу цикла / `INDETERMINATE_PROGRESS`** | В SYP прогресс — состояние задания в БД | [02:строки 724] |
| 7 | **`-map 0:a:<typeOrder-1>` из property `@typeorder`** | Косвенный, per-track приоритет; в SYP — явная таблица дорожек | [02:строки 258, 1030–1034] |
| 8 | **Ручные сцены/события как основной способ разметки** | Требование SYP — «описывать каждую сцену» автоматически | [02:строки 911–928] |
| 9 | **`propertyCdf` per-machine** | OSHI-зависимость; в SYP пути в конфигурации/БД | [02:строки 460–472] |

### G.3 Windows-only / нерабочее на Linux — переносить нельзя

| # | Что | Ссылка |
|---|---|---|
| 1 | **ffmpeg/ffprobe/ffplay, зашитые в JAR как `.exe`** — `getResource("ffmpeg-shared/bin/ffmpeg.exe")` [02:568–574]. Порт «ffmpeg внутри приложения» невоспроизводим на Linux | [02:строки 568–574] |
| 2 | **`RunCmd`: запись текста в `ivfx*.cmd` и `ProcessBuilder(<путь к .cmd>)`** — работает только через Windows-семантику `ShellExecute` [02:587–603, 380]. Весь OpenFace-пайплайн (`DetectFaces`, `RecognizeFaces`, `doTrainFaceModel`) на Linux не запускается | [02:строки 380, 587–603] |
| 3 | **Sikuli `Finder`/`Pattern`/`Settings.MinSimilarity`** — GUI-автоматизация изображением, Windows-only; в SYP заменяется детектором сцен | [02:строки 855–860] |
| 4 | **Парсер ffprobe с `(?<=\[FRAME\]\r\n)`** — на Linux список I-кадров пуст, `isIFrame` всегда false | [02:строки 618–624] |
| 5 | **`MediaInfo_CLI/MediaInfo.exe` + `out.substring(0, out.length - 2)`** — Windows-исполняемый файл + безусловное отбрасывание 2 символов вывода | [02:строки 576–585, 1120] |
| 6 | **`.replace("/", "\\")` в команде Python** [02:строки 345] и **backslash-пути** в именах concat/filters [02:строки 289, 420, 1019] | [02:строки 289, 345, 420, 1019] |
| 7 | **Аргумент MediaInfo перед файлом** (`param` до `media`) [02:строки 578] — порядок аргументов Windows-CLI | [02:строки 578] |
| 8 | **Платформенная кодировка `FileWriter` для concat-`.txt`** — на Linux без BOM/разное поведение | [02:строки 728, 1013] |
| 9 | **JavaFX-тред для длительных операций** (`CreateFilterResult(...).run()` на FX) | [02:строки 813, 1103] |

### G.4 Сопоставление legacy-подхода с измерениями на этой машине

Измерено на `nsa-i9` (передано в задании, в отчёте legacy НЕ измерено):

| Величина | Значение | Комментарий |
|---|---|---|
| Декодирование всей серии 1080p | 157 с | ≈ 527 кадр/с |
| Детекция границ `scdet` t=8 | 162 с | практически равна декодированию — границы сцен не «стоят» отдельного прохода |
| Извлечение кадров 1080p JPEG | ~462 кадр/с | 82 834 кадра серии ≈ 179 с на серию только в Full |
| Stream-copy нарезка 30 с | 730 кадров вместо 719 | граница округляется до ключевого кадра |
| Перекодирование | дорого (цифра спорная) | |

Выводы сопоставления (расчёт субагента):

1. **Декодирование (`scdet` 162 с) ≈ декодирование без анализа (157 с).** То есть
   встроенный детектор сцен ffmpeg стоит практически ноль дополнительно. Legacy
   подход (извлечь все кадры → сравнить соседние через Sikuli) стоит кратно
   дороже: только Full-извлечение ≈ 179 с на серию, и это **до** сравнений.
2. **Совокупный объём legacy-подхода на архиве:** 5.96 млн кадров × 3 разрешения
   ≈ 17.9 млн JPEG-файлов. Число файлов (не байты) — главное ограничение.
   Извлечение в 462 кадр/с однопоточно дало бы ≈ 3.6 ч чистого времени на
   один проход Full по всему архиву; на 36 ядрах это ≈ 10–15 мин — то есть
   **вычислительно извлечение кадров не невозможно**, проблема в хранении и в
   том, что SYP всё равно не нужны все кадры.
3. **Stream-copy нарезка округляется до ключевого кадра** (730 вместо 719).
   Legacy обходит это созданием lossless-интра-файла [02:строки 1067–1079] —
   обход рабочий, но дорогой (перекодирование всего файла). Альтернатива для
   SYP: `-ss` по кадру + перекодирование только выбранных сцен, либо
   `-copyts`/`-avoid_negative_ts` + `-c copy` с явным `-ss` от GOP-границы
   с корректировкой списка кадров.
4. **«Перекодирование дорого» — цифра спорная** (помечено в задании). Из
   измерений следует, что `scdet` ≈ декодирование, а декодирование 1080p
   ≈ 527 кадр/с. Значит, перекодирование в x264 будет медленнее декодирования
   (кодирование x264 1080p на 36 ядрах — отдельный вопрос), но не на порядки.
   В отчёте legacy цифр по перекодированию нет.

---

## H. Явные вопросы владельцу

1. **Дефект №18 (лишний кадр в начале каждого шота) — это баг или сознательная
   компенсация?** От него зависит, считать ли `-ss = frame(firstFrame−1)`
   ошибкой или нормой legacy. В отчёте не разобрано [02:строки 248–249].
   Проверяется одним тестом на `GOT.S01E01`.
2. **`nearestIFrame` — нужен ли SYP вообще?** В legacy он существует, чтобы
   делать точный seek по lossless-интра-файлу. Если SYP не делает lossless-мастер,
   поле можно не переносить [02:строки 226, 905–907].
3. **Требуется ли SYP персистентная (подписанная) граница сцены, или достаточно
   `lastFrameNumber = transition − 1`?** В legacy граница «принадлежит» следующему
   шоту [02:строки 901–905]. Для SYP («точность до кадра») это решение влияет на
   всю модель данных.
4. **«Описывать каждую сцену» — автоматически (LLM/ML) или вручную?** В legacy
   сцены только ручные [02:строки 911–928]; автоматики нет.
5. **Персоны: продолжать OpenFace-подход или заменить?** В отчёте — пороги
   0.3 / `d > 4` / дискретизация 3-5-10-20 [02:строки 349, 363], но сам стек
   Windows-only [02:строки 587–603].
6. **Нужен ли lossless-мастер в SYP вообще** (с учётом, что stream-copy округляет
   до ключевого кадра, а перекодирование всей серии дорого)? [02:строки
   1067–1079] vs измерение 730/719.
7. **Какой контейнер на выходе сборки?** Legacy даёт выбор MP4/MKV/MXF из
   `project.container` [02:строки 1054] и сам ломается на MXF для lossless
   [02:строки 199, 1112].
8. **Аудио в собираемых выборках:** legacy мапит дорожки по `@typeorder` и для
   `CreateFilterResult` берёт карту от **первого** файла выборки [02:строки 1061] —
   то есть при разнородных файлах склейка может потерять/перепутать дорожки.
   Нужно ли SYP то же, или только первая дорожка?

---

## Итог для карты решений

1. **Как определять границы сцен.** Варианты: (а) ffmpeg-фильтр
   `scdet`/`select=gt(scene,…)` — по измерению 162 с на серию, отдельного
   прохода почти не добавляет; (б) PySceneDetect-подобный внешний инструмент;
   (в) перенести Sikuli-подход — **не вариант**, он Windows-only и O(n).
   Это самое крупное решение пайплайна: от него зависит вся стоимость обработки.

2. **Хранить ли все кадры на диске (legacy) или поток/выборочное извлечение.**
   5.96 млн кадров × 3 разрешения = 17.9 млн файлов. Рекомендация: кадры —
   производный, воспроизводимый артефакт, а не источник правды; границы сцен —
   единственное, что хранится постоянно.

3. **Нужен ли lossless-мастер (перекодированный интра-файл) как база нарезки.**
   Даёт точный seek, но требует перекодирования всей серии. Альтернатива —
   перекодировать только выбранные сцены (их доля от кадров мала). Влияет на
   «перекодирование дорого» из измерений.

4. **Как резать: stream-copy с компенсацией GOP или перекодирование.**
   Измерение 730 кадров вместо 719 показывает, что stream-copy округляется до
   ключевого кадра. Развилка: (а) принять округление и хранить фактические
   границы; (б) `-ss` от GOP-границы + коррекция; (в) перекодировать сцену.

5. **Идемпотентность: форма.** Legacy — флаги в БД без сверки с диском и без
   проверки кода возврата. Для SYP: хеш входных параметров (разрешение, кодек,
   алгоритм детекции, версия) + атомарная запись через временный файл +
   обязательная проверка exit code. Отдельная развилка: **инвалидируются ли
   артефакты при смене настроек проекта** (в legacy — нет).

6. **Деструктивность переразбора.** Legacy: `CreateShots` делает `deleteAll` по
   файлу [02:строки 207], `AnalyzeFrames` сбрасывает ручные метки [02:строки 897].
   Развилка: SYP хранит «сырой» результат анализа отдельно от пользовательских
   правок (слой рекомендаций поверх) или перезаписывает всё.

7. **Очередь и параллелизм.** Legacy — последовательный `RunListThreads` на
   `sleep(100)` без пула и без отмены [02:строки 747–780, 810]. В SYP очередь
   заданий уже в стеке: развилка — **сколько параллельных заданий на 36 ядрах**
   и кто владеет GPU-очередью (в legacy не описано — в отчёте не описано).

8. **Склейка: `-c copy` concat-demuxer или перекодирование.** `-c copy` дёшево,
   но сечёт по длине, а не по кадрам [02:строки 414, 739, 1053]. Развилка:
   для «сборки выборок сцен» приемлема ли погрешность в 1 кадр на стыке.

9. **Аудио в сборках.** Legacy: `@typeorder` per-track, у `CreateFilterResult` —
   карта от первого файла [02:строки 1052, 1061]. Развилка: фиксированная
   одна дорожка (просто и надёжно) или полный мультитрек.

10. **Модель кадра/времени в БД.** Legacy мешает секунды (имена файлов, `-ss`) и
    номера кадров (`tbl_frames`, `first/lastFrameNumber`) [02:строки 226, 286–290].
    Развилка: **номер кадра — единственный источник правды**, время — производное.
    Тогда 23.976 fps требует аккуратного округления PTS→номер кадра, где legacy
    ошибался [02:строки 623].

---

*Исследование выполнено по `/home/nsa/ivfx4/legacy-analysis/02-video-pipeline.md`
(1142 строки, HEAD `a3003b7`). Код из `/home/nsa/ivfx4` не переносился.*
