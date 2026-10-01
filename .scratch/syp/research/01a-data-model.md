# 01a — Модель данных ivfx4: извлечение для SYP

**Источник**: `/home/nsa/ivfx4/legacy-analysis/01-data-model.md` (718 строк, прочитан полностью).
**Что это**: разбор `models/`, `modelsext/`, `repos/`, `enums/` (~3918 строк) старого проекта ivfx4.
**Границы**: только модель данных. Пайплайн/видео — задача второго субагента. Код из `/home/nsa/ivfx4`
не переносится; ниже только знания.
**Ссылки**: `[01:строки N–M]` — строки в исходном отчёте.

## 0. Резюме для SYP (короткое)

ivfx4 — настольное JavaFX-приложение (Kotlin + Spring Boot + **Spring Data JPA/Hibernate**, MySQL/Postgres,
`ddl-auto=update`, SQL-миграций в репозитории нет) [01:строки 5–7]. В нём есть ровно те абстракции,
которые нужны SYP: файл → кадры → **план (Shot, диапазон кадров)** → **сцена (Scene, диапазон кадров)**,
**лицо в кадре (Face, bbox + персонаж)** → персонаж, плюс **сохраняемые запросы-фильтры** и EAV-свойства.

Три вещи, которые SYP **не наследует**: JPA (запрещён), Cdf-слой (компьютер-зависимые пути),
`ddl-auto` (SYP — нумерованные SQL-миграции). Два вычисляемых по диапазонам кадров отношения
(сцена→планы, персонаж→план через лица) — это и есть ядро модели, и оно **переносится как есть**.

---

## A. Сущности и поля

Все 18 сущностей объявлены одинаково: `@Component @Entity @Table(name = "tbl_…") @Transactional`;
ID везде `Long` + `GenerationType.IDENTITY` [01:строки 19–30].

| Сущность / таблица | Поле (Kotlin) | Тип | Колонка | Смысл |
|---|---|---|---|---|
| **Project** `tbl_projects` [01:32–65] | `id` | Long | `id` | IDENTITY |
| | `order` | Int | `order_project` | порядок проекта, 0 |
| | `name` / `shortName` | String | `name` / `short_name` | имя проекта, "" |
| | `lossLessCodec` | String | `lossless_codec` | `"RAW"` [01:40] |
| | `lossLessContainer` | String | `lossless_container` | `"MKV"` [01:41] |
| | `container` | String | `container` | `"MP4"` [01:42] |
| | `videoCodec` / `audioCodec` | String | `video_codec` / `audio_codec` | `"X264"` / `"AAC"` [01:43–44] |
| | `width` / `height` | Int | `width` / `height` | 1920 / 1080 [01:45] |
| | `fps` | **Double** | `fps` | **23.976** [01:46] |
| | `videoBitrate` | Int | `video_bitrate` | 10 000 000 [01:47] |
| | `audioBitrate` | Int | `audio_bitrate` | 320 000 [01:48] |
| | `audioFrequency` | Int | `audio_frequency` | 48 000 [01:49] |
| | `folder` | String | — | **не-персистентное**: проекция на `tbl_projects_cdf.folder` для текущей машины [01:62–65] |
| **ProjectCdf** `tbl_projects_cdf` [01:67–74] | `id` | Long | `id` | |
| | `project` | Project | `project_id` | `@ManyToOne(LAZY)`, `lateinit` |
| | `computerId` | Int | `computer_id` | 0 |
| | `folder` | String | `folder` | путь к корневой папке проекта, "" |
| **File** `tbl_files` [01:76–92] | `id` | Long | `id` | |
| | `project` | Project | `project_id` | `@ManyToOne(LAZY) lateinit` |
| | `order` | Int | `order_file` | **порядок файла внутри проекта**, 0; `compareTo` = разность `order` [01:78] |
| | `name` / `shortName` | String | `name` / `short_name` | |
| | `path` | String | — | не-персистентное: проекция на `tbl_files_cdf.path` текущей машины [01:91–92] |
| **FileCdf** `tbl_files_cdf` [01:94–97] | `id` / `file` / `computerId` / `path` | | `id` / `file_id` / `computer_id` / `path` | путь к видеофайлу на конкретной машине |
| **Frame** `tbl_frames` [01:99–120] | `id` / `file` | Long / File | `id` / `file_id` | |
| | `frameNumber` | Int | `frame_number` | номер кадра **в файле** [01:105] |
| | `isIFrame` | Boolean | `is_iframe` | ключевой кадр |
| | `isFind` | Boolean | `is_find` | найден детектором сцены |
| | `isManualAdd` | Boolean | `is_manual_add` | добавлен вручную |
| | `isManualCancel` | Boolean | `is_manual_cancel` | снят вручную |
| | `isFinalFind` | Boolean | `is_final_find` | финальная граница [01:106–110] |
| | `simScoreNext1/2/3`, `simScorePrev1/2/3` | Double | `sim_score_next_1..3`, `sim_score_prev_1..3` | «сходство» с соседями (детектор) [01:111] |
| | `diffNext1/2`, `diffPrev1/2` | Double | `diff_next_1/2`, `diff_prev_1/2` | разности [01:112] |
| | — | — | — | **колонки с путём к картинке нет**: путь вычисляется как `<folderFramesSmall>/…/<shortName>_frame_%06d.jpg`; картинки лежат в `Frames_Small/Medium/Full` [01:114–117] |
| **Shot** `tbl_shots` [01:122–137] | `id` / `file` | Long / File | `id` / `file_id` | `lateinit` |
| | `typePerson` | `ShotTypePerson` (int) | `shot_type_person` | NONE [01:130] |
| | `firstFrameNumber` | Int | `first_frame_number` | 0 [01:131] |
| | `lastFrameNumber` | Int | `last_frame_number` | 0 [01:132] |
| | `nearestIFrame` | Int | `nearest_i_frame` | 0 [01:133] |
| | `typeSize` | — | — | **поля нет**; `ShotTypeSize` только импортирован [01:135–136, 556–558] |
| **Scene** `tbl_scenes` [01:139–147] | `id` / `file` | Long / File | `id` / `file_id` | |
| | `parentId` | Long | `parent_id` | default 0; **никогда не заполняется** [01:143, 680–681] |
| | `name` | String | `name` | имя сцены |
| | `firstFrameNumber` / `lastFrameNumber` | Int | те же | границы в кадрах [01:144] |
| | `compareTo` | — | — | `firstFrameNumber - other.firstFrameNumber` [01:141] |
| **Event** `tbl_events` [01:149–154, 501–510] | те же 6 полей, что у Scene | | `tbl_events` | «событие» — драматургическая единица; обычно создаётся из сцены, имя переиспользуется [01:153–154] |
| **Track** `tbl_files_tracks` [01:156–167, 484–499] | `id` / `file` | Long / File | `id` / `file_id` | |
| | `order` | Int | `order_file_track` | |
| | `name` | String | `name` | при импорте = `type` [01:488–489] |
| | `type` | String | `type` | строка `@type` из MediaInfo: `"Video"`, `"Audio"`, `"Text"`; своего enum'а нет [01:491] |
| | `use` | Boolean | `use_track` | «использовать при финальной сборке», default true [01:492] |
| | — | — | — | **всё остальное о дорожке (битрейт, разрешение, каналы, язык, SampleRate) — в `tbl_properties` как EAV** с `parent_class = "Track"`, `parent_id = track.id` [01:494–498] |
| **Face** `tbl_faces` [01:169–193] | `id` / `file` | Long / File | `id` / `file_id` | |
| | `person` | Person | `person_id` | **не nullable**, `lateinit`: у лица всегда есть «персонаж-владелец» [01:177] |
| | `faceNumberInFrame` | Int | `face_number_in_frame` | какое лицо по счёту в кадре [01:178] |
| | `frameNumber` | Int | `frame_number` | денормализовано: дублирует связь с Frame, т.к. Frame в БД может отсутствовать [01:179] |
| | `personRecognizedName` | String | `person_recognized_name` | что выдал распознаватель [01:180] |
| | `recognizeProbability` | Double | `recognize_probability` | уверенность [01:181] |
| | `startX`, `startY`, `endX`, `endY` | Int | `start_x`, `start_y`, `end_x`, `end_y` | **bounding box, 4 целых** [01:182] |
| | `isExample` | Boolean | `is_example` | эталон для обучения [01:183] |
| | `isManual` | Boolean | `is_manual` | размечено вручную [01:184] |
| | `vectorText` | String | `vector` (`@Lob`) | эмбеддинг **строкой** `"a\|b\|c"`; виртуальное `vector: DoubleArray` разворачивает split по `\|` [01:185, 187–193] |
| | `compareTo` | — | — | трёхуровневая: `file.order`, `frameNumber`, `faceNumberInFrame` [01:171] |
| **Person** `tbl_persons` [01:195–215] | `id` / `project` | Long / Project | `id` / `project_id` | |
| | `personType` | `PersonType` (int) | `person_type` | default `PERSON` [01:204] |
| | `name` | String | `name` | `compareTo` по имени [01:197] |
| | `nameInRecognizer` | String | `name_in_recognizer` | имя в распознавателе лиц [01:206] |
| | `fileIdForPreview` | Long | `file_id_for_preview` | откуда брать аватар [01:207] |
| | `frameNumberForPreview` | Int | `frame_number_for_preview` | |
| | `faceNumberForPreview` | Int | `face_number_for_preview` | 0 = брать кадр целиком, ≠0 = вырезать лицо [01:209] |
| | `uuid` | String | `uuid` | инициализируется `UUID.randomUUID().toString()`; имя файла аватара `${uuid}.small.jpg` [01:210, 705–709] |
| **Property** `tbl_properties` | см. раздел D | | | полиморфный EAV |
| **PropertyCdf** `tbl_properties_cdf` | см. раздел D/E | | | Property + `computerId` |
| **ShotTmpCdf** `tbl_shots_tmp_cdf` | `id`, `computerId`, `shotId` | | `id`, `computer_id`, `shot_id` | временная таблица; **связи на Shot нет** [01:237–241] |
| **ShotTmp2Cdf** `tbl_shots_tmp2_cdf` | + `fileId`, `projectId` | | `id`, `computer_id`, `shot_id`, `file_id`, `project_id` | материализованный результат фильтра; без связей [01:243–248] |
| **Filter** `tbl_filters` | `id`, `project`, `name`, `order` (`order_filter`), `isAnd` (`is_and`, default true) | | | раздел C [01:250–254] |
| **FilterGroup** `tbl_filters_groups` | `id`, `filter` (`filter_id`), `name`, `order` (`order_filter_group`), `isAnd` (`is_and`) | | | [01:256–259] |
| **FilterCondition** `tbl_filters_conditions` | `id`, `filterGroup` (`filter_group_id`), `name`, `order` (`order_filter_condition`), `objectId`, `objectName`, `objectValue`, `objectClass`, `isIncluded` (`is_included`, default true), `subjectClass` | | | [01:261–266] |

Итого таблиц: 18 [01:713–718]: `tbl_projects`, `tbl_projects_cdf`, `tbl_files`, `tbl_files_cdf`,
`tbl_files_tracks`, `tbl_frames`, `tbl_faces`, `tbl_shots`, `tbl_scenes`, `tbl_events`, `tbl_persons`,
`tbl_properties`, `tbl_properties_cdf`, `tbl_shots_tmp_cdf`, `tbl_shots_tmp2_cdf`, `tbl_filters`,
`tbl_filters_groups`, `tbl_filters_conditions`.

### A.1. Enum-ы, которые переносимы как справочники

| Enum | Значения | Персистится? |
|---|---|---|
| `ShotTypePerson(order, description, comment, pathToPicture)` | `NONE`(0), `SGN`(1, один), `OTS`(2, через плечо), `TWO`(3, двое), `GRP`(4, трое+), `MASS`(5, очень много) [01:529–536] | да, `shot_type_person` int [01:527] |
| `ShotTypeSize` | `NONE`, `ECU`, `BCU`, `CU`, `MCU`, `MS`, `MLS`, `LS`, `VLS`, `XLS` [01:543–554] | **нет ни в одной таблице** [01:541] |
| `PersonType(order, description, comment)` | `PERSON`(0), `NONPERSON`(1, аспект лица > 4), `EXTRAS`(2, не используется), `UNDEFINDED`(3, «неопознанный») [01:562–567] | да, `person_type` int [01:560] |
| `Folders(propertyCdfKey, folderName, forProject, forFile)` | 15 папок: `LOSSLESS`, `PREVIEW`, `SHOTS`, `FAVORITES`, `FRAMES_SMALL/MEDIUM/FULL`, `FACES_FULL/PREVIEW`, `PERSONS`, `SHOTS_COMPRESSED_WITH_AUDIO`, `SHOTS_LOSSLESS_WITH_AUDIO`, `SHOTS_LOSSLESS_WITHOUT_AUDIO`, `CONCAT`, `FILTERS` [01:571–587] | нет, но задаёт ключи в `tbl_properties_cdf` [01:569] |
| `ReorderTypes` | `MOVE_UP`, `MOVE_DOWN`, `MOVE_TO_FIRST`, `MOVE_TO_LAST` [01:596] | нет |
| 5 enum'ов кодеков | `VideoContainers`, `LosslessContainers`, `VideoCodecs`, `LosslessVideoCodecs`, `AudioCodecs` [01:603–620] | нет, хранятся **строками** в `tbl_projects` [01:603] |

`PersonType` даёт готовый образец «персонажа-заглушки»: `UNDEFINDED` и `NONPERSON` — служебные персоны,
на которые ссылаются нераспознанные и нелицные лица [01:212–215].

### A.2. Слой `modelsext` — вычисляемые поля (не таблицы)

`models/` — чистый JPA, `modelsext/` — DTO-витрины для UI без Persistence [01:634]. Каждый `*Ext`
лениво кэширует вычисленное и умеет сбрасывать кеш при переименовании файла/смене пути [01:635–637].

| Ext | Что добавляет |
|---|---|
| `ProjectExt` | 15 вычисленных папок проекта [01:641] |
| `FileExt` | `fps`, `framesCount`, 13 папок, 15 флагов готовности, списки `framesExt/shotsExt/scenesExt/eventsExt/facesExt`, `framesWithFaces: MutableSet<Int>` [01:642] |
| `ShotExt` | `start/end/duration` (через ffmpeg), **вычисляемые `sceneExt`/`eventsExt`**, 3 пути к вырезанным видео, `personsExt`, 6 превью-меток, иконка типа плана [01:643] |
| `SceneExt` / `EventExt` | **`shotsExt` (из `fileExt.shotsExt` по границам)**, `personsExt`, `start/end/duration`, переименование через `ContextMenu` [01:644] |
| `FrameExt` | `pathToSmall/Medium/Full` (`%06d`), `biSmall/Medium/Full`, стабы `blank_frame_*.jpg`, `facesExt()` [01:645] |
| `FaceExt` | вычисляемые пути к кадру/лицу (`%06d`, `%02d`), экспорт в JSON, ленивая генерация превью с оверлеями (зелёный треугольник = `isExample`, красный = `isManual`) [01:646] |
| `PersonExt` | пути по `uuid`, генерация аватара из кадра или вырезанного лица, стабы `blank_person_*.jpg` [01:647] |
| `MatrixFrame/MatrixPageFrames/MatrixFace/MatrixPageFaces` | разбивка кадров/лиц на «страницы-сетки»; перенос строки по `Frame.isFinalFind` (конец сцены → новая строка) [01:649] |

Вывод для SYP: `*Ext` — это view-model слой, а не данные. В SYP его роль играют API-ответы Spring
(`ShotExt` → `ShotDto` и т.п.); переносить класс `Ext` как есть не нужно.

---

## B. Связи

### B.1. Объявленные (FK) связи

```
tbl_projects ──1:N──► tbl_files, tbl_persons, tbl_filters, tbl_projects_cdf
tbl_files    ──1:N──► tbl_files_tracks, tbl_frames, tbl_faces, tbl_files_cdf, tbl_shots
tbl_files    ──1:N──► tbl_scenes, tbl_events   (file_id есть, но mappedBy в модели НЕ объявлен)
tbl_persons  ──1:N──► tbl_faces
tbl_filters  ──1:N──► tbl_filters_groups ──1:N──► tbl_filters_conditions
tbl_shots    ◄──(без FK)── tbl_shots_tmp_cdf.shot_id
tbl_shots    ◄──(без FK)── tbl_shots_tmp2_cdf.shot_id
tbl_properties / tbl_properties_cdf — полиморфны: (parent_class, parent_id) без FK
```
[01:268–279]

Все `@OneToMany` объявлены с `cascade = [CascadeType.REMOVE]` и `fetch = LAZY` + `@Fetch(SUBSELECT)`;
ни `PERSIST`, ни `MERGE` [01:281, 51–60, 88–89, 203, 254, 259].
`@ManyToMany`, `@OneToOne`, `@ElementCollection`, `@Inheritance` в проекте **нет нигде** (проверено grep'ом
по `src/main`); связь Scene↔Shot и Event↔Shot когда-то была `@ManyToMany` через `@JoinTable` и join-сущности
`SceneShot`/`EventShot`, от неё остались только импорты [01:282–284, 664–669].
`SUBSELECT` на всех дочерних коллекциях — N+1 на графе проект→файлы→шоты вызывается массово [01:286–287].

### B.2. Вычисляемые по диапазонам кадров — ядро модели

Это главное, что переносится в SYP.

| Связь | Формула | Где |
|---|---|---|
| **Сцена → планы** | `tsh.first_frame_number >= tsc.first_frame_number AND tsh.last_frame_number <= tsc.last_frame_number` при равенстве `file_id`; план должен **целиком** лежать внутри сцены | `ShotRepo.getShotsForScenes(sceneId)` [01:145–147] |
| **Событие → планы** | ровно та же формула с `tbl_events` | `ShotRepo.getShotsForEvents(eventId)` [01:512–514] |
| **План → персонажи** | **JOIN `tbl_faces` по диапазону кадров**: лицо принадлежит плану, если `shot.first ≤ face.frameNumber ≤ shot.last` и тот же `file_id` | `PersonRepo.findByShotId`, `FaceRepo.findByShotIdAndPersonId` [01:136–137]; SQL-пример [01:432–440] |
| **План → сцена/событие (обратно)** | вычисляется на UI-стороне: `ShotExt.sceneExt`/`eventsExt`; на каждый `ShotExt.sceneExt` делается отдельный нативный запрос, кеша нет | [01:643, 697–699] |
| **Сцена/событие → персонажи** | `SceneExt`/`EventExt.personsExt` — фильтрация `fileExt.facesExt` по границам | [01:644] |
| **Лицо → кадр** | денормализация: у `Face` есть свой `frameNumber`, а не только `frame_id` | [01:179] |
| **План → кадры** | `tbl_frames`, JOIN по диапазону `first_frame_number … last_frame_number` | [01:435–437] |
| **Дорожки → файл** | `Track.file_id`; файла на диске дорожка не имеет, работа идёт через ffmpeg по номеру дорожки | [01:499] |

Ключевая конвенция, которую SYP стоит унаследовать дословно: **связь «родитель содержит ребёнка»
не хранится, а вычисляется пересечением интервалов кадров в пределах одного файла**; принадлежность
требует, чтобы интервал ребёнка лежал **целиком** внутри интервала родителя [01:145–147].

Следствия, зафиксированные в отчёте:
* События могут **пересекаться** (границы задаются независимо), один `Shot` может попасть в несколько
  событий [01:515–517].
* Чтобы это работало в SQL, для сцен/событий используется трёхуровневый подзапрос
  «шоты внутри → события/сцены по этим шотам → шоты внутри», возвращающий и «вложенные» случаи
  [01:516–519].
* Обратная связь «сцены/события по шоту» не кешируется — на каждую связку запрос [01:697–699].
  Для SYP это конкретный повод считать связь один раз и кэшировать (см. раздел H, вопрос 4).

### B.3. Сортировка

`Shot.compareTo = (file.order − other.file.order) * 1 000 000 + (firstFrameNumber − other.firstFrameNumber)` [01:124] —
глобальный порядок планов по всему проекту, сшитый из `File.order` и номера кадра.
`Scene.compareTo` — только по `firstFrameNumber` (в пределах файла) [01:141];
`Face.compareTo` — `file.order`, `frameNumber`, `faceNumberInFrame` [01:171];
`File.compareTo` — по `order` [01:78]; `Person` — по `name` [01:197].

---

## C. Модель фильтров

### C.1. Структура: три уровня

```
Filter (project_id, name, order, isAnd)
  └─ FilterGroup (filter_id, name, order, isAnd)                       — «скобки»
       └─ FilterCondition (filter_group_id, name, order,
            objectClass, subjectClass, objectId,
            objectName, objectValue, isIncluded)                        — «условие»
```
[01:371–377]

`isAnd` на **каждом** уровне выбирает, как комбинировать **своих детей**:
`true` → `&&` (пересечение, реализовано через `retainAll`), `false` → `||` (объединение, `addAll`) [01:379–380].

### C.2. Поля `FilterCondition` [01:382–393]

* `objectClass` — **что** проверяем; значения строятся из `simpleName` + (для свойств) `" Property"`:
  `"Person"`, `"Person Property"`, `"Shot Property"`, `"Scene Property"`, `"Event Property"` [01:384–386].
* `subjectClass` — **где** ищем: `"Shot"`, `"Scene"`, `"Event"`, `"File"` [01:387].
* `objectId` — id персоны (для `objectClass = "Person"`); для `* Property` значим `objectName`
  (= `property_key`) и `objectValue` (= `property_value`), а `objectId` формально не значим [01:388–390].
* `isIncluded` — `true` → включать найденное, `false` → исключать (инверсия множества) [01:391].
* `name` — человекочитаемая формула, собирается в UI как
  `"$objectClass «$objectName»${" = «$value»"} is[ NOT]included in $subjectClass"` [01:392–393].

### C.3. Реально реализованные пары [01:396–408]

| objectClass | subjectClass | вызываемый метод `ShotRepo` |
|---|---|---|
| `Person` | `Shot` | `getShotsIdsForShotsTmpAndPerson(ccid, objectId)` |
| `Person` | `Scene` | `getShotsIdsForScenesTmpAndPerson(ccid, objectId)` |
| `Person` | `Event` | `getShotsIdsForEventsTmpAndPerson(ccid, objectId)` |
| `Person Property` | `Shot/Scene/Event` | `…ForShotsTmpAndPersonProperty(ccid, objectName, objectValue)` |
| `Shot Property` | `Shot/Scene/Event` | `…ForShotsTmpAndShotProperty(ccid, objectName, objectValue)` |
| `Scene Property` | `Scene` | `getShotsIdsForScenesTmpAndSceneProperty(ccid, …)` |
| `Event Property` | `Event` | `getShotsIdsForEventsTmpAndEventProperty(ccid, …)` |

Отсутствует: `Shot Property` × `File`, `Scene Property` × `Shot/Event`, `Event Property` × `Shot/Scene`,
а также `objectClass = "File"` / `"Shot"` целиком — UI их не предлагает [01:410–412].

### C.4. Как выполняется поиск [01:414–427]

**Не** Specification и **не** Criteria API. Смешанная схема:
1. Каждое условие → один нативный `@Query` в `ShotRepo` (обычно `select distinct tsh.id`) → `Set<Long>`.
2. Комбинирование — **в памяти** в `modelsext`: `FilterConditionExt` → `FilterGroupExt` → `FilterExt`,
   каждый уровень рекурсивно сводит множества (`retainAll` для AND, `addAll` для OR).
   Три параллельных API: `shotsIds(): Set<Long>`, `shots(): Set<Shot>` (**только** для
   `objectClass == "Person"`), `shotsExt(...)` (тоже только для `Person`) [01:418–424, 685–687].
3. Отдельный scratch-проход: `tbl_shots_tmp_cdf` ограничивает выборку файлами, выбранными в UI
   (`FilterEditFXController.doFilter()` чистит таблицу, затем `addAllByFileId(ccid, fileId)`) [01:425–427].

Полный путь исполнения [01:464–469]: `doFilter()` → заполнение `tbl_shots_tmp_cdf` →
`FilterController.getFilterExt(...)` → `FilterExt.shotsIds()` (нативные запросы + свёртка в памяти) →
`ShotController.convertSetShotsIdsToListShotsExt(ids, projectExt)` (материализует ids в `tbl_shots_tmp2_cdf`
нативным `INSERT … SELECT`, затем `findByComputerId` + `shotRepo.findByIds`) → `tblShots.items`.

Примеры запросов — по персонажу [01:432–440] и по свойству сцены [01:442–458]; вторым запросом видно
трёхуровневую вложенность «шоты внутри сцены со свойством, а сцена внутри выбранных файлов».

Свойства в фильтре ищутся **только** по `tbl_properties`, никогда по `tbl_properties_cdf` — то есть
фильтр работает лишь с общими, не машинозависимыми свойствами [01:460–462].

Порядок элементов переупорядочивается через `ReorderTypes` (`MOVE_UP/DOWN/TO_FIRST/TO_LAST`) —
перенумерация `order` сдвигами ±1 по соседям; в `FilterConditionController` `MOVE_DOWN` означает
«вниз по списку» (увеличить `order`), `MOVE_UP` — уменьшить [01:475–478, 596–601].

**Вывод для SYP**: механизм — «сохраняемый запрос → id'ы планов → сборка в видеофайл» [01:12–13],
это ровно второй пользовательский сценарий SYP («собирать выборки сцен в один видеофайл»). Переносится
как идея (декларативное описание выборки + исполнение), но не как реализация: ручные нативные запросы
на каждую пару классов [01:396–408] и три параллельных API с разной полнотой [01:685–687] — это ровно
та причина, по которой комбинаций всегда не хватает.

---

## D. EAV-свойства

`tbl_properties` [01:217–229]:

| Поле | Тип | Колонка | Смысл |
|---|---|---|---|
| `id` | Long | `id` | |
| `order` | Int | `order_property` | |
| `parentClass` | String | `parent_class` | **строка** класса-владельца: `"Person"`, `"Shot"`, `"Scene"`, `"Event"`, `"Track"`, `"File"`, `"FilterCondition"` [01:223] |
| `parentId` | Long | `parent_id` | **без FK** [01:224] |
| `key` | String | `property_key` | |
| `value` | String | `property_value` (`@Lob`) | |

Полиморфный EAV без ссылки на таблицу-владельца; из-за этого удаление не каскадное, а явное в
контроллерах: `PropertyController.deleteAll(entity::class.java.simpleName, entity.id)` [01:228–229, 350–352].

`tbl_properties_cdf` — то же самое + `computerId`, участвующий во **всех** finder-методах
(`findByParentClassAndParentIdAndComputerIdAnd…`, `getKeys(parentClass, computerId)`) [01:231–235].

**Как применяются**:
* `parent_class` = строка, никогда не проверяется по справочнику — любое имя класса проходит;
  это же имя используется при удалении [01:223, 229].
* Свойства присутствуют минимум у семи классов: `Person`, `Shot`, `Scene`, `Event`, `Track`, `File`,
  `FilterCondition` [01:223]. Фактически подтверждённое применение: MediaInfo-развёртка для `Track`
  [01:494–498] и произвольные пользовательские свойства плана/сцены/персонажа в фильтрах [01:460–462].
* MediaInfo разворачивается с рекурсивным спуском во вложенные объекты **с потерей иерархии** —
  ключи становятся плоскими [01:496–498].
* Фильтры используют `property_key` + `property_value` строго на равенство: `where tpsc.property_key = ?2
  and tpsc.property_value = ?3` [01:456]. Других операторов (LIKE, диапазон, NULL) в отчёте не описано.
* `Property`/`PropertyCdf` участвуют в том же `ReorderTypes` переупорядочивании, что и фильтры [01:475–478].
* Пути к рабочим папкам (`folder_preview` и остальные 14) тоже лежат в EAV, но в Cdf-таблице [01:305–308, 569].

**Вывод для SYP**: EAV в ivfx4 — это «хвост» для произвольных атрибутов и развёрнутых внешних структур.
Строка `parent_class` без FK и ручное удаление — цена, которую SYP платить не должен (см. развилку 4).

---

## E. Механизм `Cdf` — что это и почему он больше не нужен

**`Cdf` ≠ «copy for display»**. По коду это — **Computer-dependent (per-machine) данные** [01:291–293].
Доказательства:
1. Во всех `*Cdf`-сущностях есть `computerId: Int`, которого нет у обычных [01:296].
2. `Main.ccid = getCurrentComputerId()` → `hashCode()` строки
   `"<manufacturer>#<processorID>#<processorIdentifier>#<logicalProcessorCount>"` из `oshi.SystemInfo`
   [01:297–300].
3. `Project.folder` / `File.path` — не колонки, а «проекции» на строку `tbl_projects_cdf.folder` /
   `tbl_files_cdf.path` с фильтром `it.computerId == Main.ccid` [01:301–302].

Смысл: **проект/файл общие для всех машин, абсолютные пути — нет**; одна папка на разных ПК лежит
по разным путям, поэтому путь хранится по строке на компьютер [01:304–306]. Свойства — по той же
логике: ключи вида `folder_preview` имеют разные значения на разных машинах [01:306–308].

Отдельно по назначению: `ShotTmpCdf` и `ShotTmp2Cdf` тоже несут `computerId`, но смысл у них иной —
**временная рабочая область (scratch) одного пользователя на одной машине** для раскладки фильтра.
Их наполняют нативными `INSERT … SELECT` и целиком чистят `DELETE … WHERE computer_id = ?`
[01:310–315].

Сводная таблица 5 Cdf-типов [01:317–325]:

| Cdf-тип | Назначение | Ключ выбора строки |
|---|---|---|
| `ProjectCdf` | корневая папка проекта на конкретной машине | `project_id` + `computer_id` |
| `FileCdf` | путь к видеофайлу на конкретной машине | `file_id` + `computer_id` |
| `PropertyCdf` | машинозависимые свойства (в т.ч. все рабочие папки) | `parent_class` + `parent_id` + `computer_id` |
| `ShotTmpCdf` | scratch: шоты файлов, отобранных для фильтра | `shot_id` + `computer_id` |
| `ShotTmp2Cdf` | scratch: результат фильтра (shot/file/project id) | `shot_id` + `computer_id` |

**Почему SYP это не нужно**: SYP разворачивается на одной машине (`nsa-i9`), без публичного деплоя
(`AGENTS.md`, Hard Gate «Машины»). Медиа лежат в MinIO (`syp-storage`) — путь к объекту задаётся ключом,
а не локальным путём, поэтому машинозависимость исчезает как класс задач. Из трёх следствий:
* `tbl_projects_cdf`, `tbl_files_cdf`, `tbl_properties_cdf` — **не переносятся**;
* `computerId` в ivfx4 вычисляется из железа через `oshi` — это Windows-desktop-специфика, не переносима;
* `ShotTmpCdf`/`ShotTmp2Cdf` как «глобальные временные таблицы в общей БД» — анти-паттерн: два
  пользователя в одной БД мешали бы друг другу, очистка идёт по `computer_id`, то есть по факту
  «удали у всех на этой машине» [01:310–315]. В SYP материализацию выборки правильнее делать
  в памяти процесса или в собственной таблице результата задания, а не в общей БД.

---

## F. Что было незавершённым, заглушками и мёртвым

Прямых маркеров `TODO`/`FIXME`/`XXX`/`HACK` нет ни в одном из четырёх каталогов (проверено) [01:655–656].
Незавершённость выражена структурно.

| # | Что | Факт | Для SYP |
|---|---|---|---|
| 1 | **Закомментированный блок в `Shot.kt` (строки 58–120, 63 строки)** | Закомментированы `@OneToMany var eventsShots: MutableSet<EventShot>` и вычисляемые `isBodyScene`, `isStartScene`, `isEndScene`, `isBodyEvent`, `isStartEvent`, `isEndEvent`. Логика переехала в UI-оверлеи на превью. Связь Scene↔Shot была **заменена на диапазоны кадров**, старый код оставлен [01:658–663] | Прямое подтверждение выбранной в ivfx4 конвенции; переносится только она |
| 2 | **Неиспользуемые импорты во всех «связевых» моделях** — следы удалённых `@ManyToMany`: `Shot.kt` (`ManyToMany`, `JoinTable`, `OneToMany`, `CascadeType`, `Fetch`, `FetchMode`), `Scene.kt`/`Event.kt`, `Filter.kt`/`FilterGroup.kt`, `FilterCondition.kt`; в `File.kt`/`Project.kt` — `org.hibernate.Hibernate` [01:664–669] | — |
| 3 | **`ShotTypeSize` — полностью не подключённый enum** | Импортирован в `Shot.kt`, поля `typeSize`/`shotTypeSize` нет, ни одного использования в UI; PNG-ресурсы `shot_type_size_*.png` при этом есть [01:135–136, 556–558, 670–671]. Рядом `PersonType.EXTRAS` тоже не используется [01:566, 671] | Справочник `ECU…XLS` **готов** — если SYP хотит «план» как тип по размеру, enum переносится дословно, нужна только колонка |
| 4 | **`ShotExt` / `SceneExt` / `EventExt`**: закомментированные дубли `_fileExt/_firstFrameExt/_lastFrameExt`; поле `buttonGetType: Button = Button()` — пустые кнопки без обработчика [01:673–675] | Не переносится |
| 5 | **`ShotTmp2Cdf` — вторая версия временной таблицы**: в `ShotRepo` нет ни одного запроса, читающего `tbl_shots_tmp2_cdf` (только `INSERT`/`DELETE` и `findByComputerId`); в `ShotController` закомментирована построчная загрузка в пользу пакетной [01:676–679] | — |
| 6 | **`Scene.parentId` и `Event.parentId`** — поля объявлены, default 0, но **нигде не заполняются и не читаются**; в `controllers` и `fxcontrollers` нет ни одного присваивания [01:143, 506–507, 680–681] | Иерархия сцен (сцена внутри сцены) **не реализована**. Для SYP — либо вводить сразу осмысленно, либо не вводить |
| 7 | **`FaceExtJson.frameId`** — при `frameId != 0` код ищет лицо по `faceRepo.findById(faceExtJson.frameId)` (**по `frameId`, а не `faceId`**) [01:682–684] | Копипаст-баг; в SYP при переносе формата импорта лиц это место надо учесть |
| 8 | **Асимметрия API фильтра**: `FilterConditionExt.shots()` и `shotsExt()` обрабатывают **только** `objectClass == "Person"` (остальные ветки — `else -> {}`), тогда как `shotsIds()` поддерживает все 5 типов [01:685–687] | Аргумент за один универсальный путь исполнения в SYP |
| 9 | **Пустые ветки `File::class.java.simpleName -> {}`** во всех `when(subjectClass)` в `FilterConditionExt` — заготовка под фильтр «файл целиком» [01:410–412, 688–689] | — |
| 10 | **Хрупкая загрузка ресурсов**: `getResource(...)!!.file.substring(1)` — **упадёт при запуске из JAR**, а не из распакованной папки; тот же приём в `FaceExtJson`/`PersonExt` для `blank_person_*.jpg` [01:538, 690–692] | Windows/JAR-специфика, не переносится |
| 11 | **Неиспользуемые импорты** в `FilterConditionRepo`/`FilterGroupRepo`/`PropertyRepo`/`PropertyCdfRepo`/`SceneRepo`/`EventRepo`/`PersonRepo`/`ShotTmp2CdfRepo` — остатки удалённых сигнатур [01:693–696] | — |
| 12 | **Дублирование `getSceneForShot`/`getEventForShot`, без кеша** — на каждый `ShotExt.sceneExt` отдельный нативный запрос [01:697–699] | Прямой довод считать связь один раз |
| 13 | **Обход ORM в `FrameExt.facesExt()`**: сырой JDBC `select * from tbl_faces where id = ?` вместо репозитория, «чтобы пережить прокси» [01:700–702] | — |
| 14 | **Отсутствие `@Version` (optimistic locking) и уникальных ограничений/индексов** ни на одной таблице; `@Column(nullable=false)` в `@Lob`-полях (`Face.vector`, `Property.value`) в сочетании с default `""`/`"0.0"` [01:703–705] | SYP: индексы и ограничения — обязательны с bootstrap (Postgres 16) |
| 15 | **`Person.uuid` инициализируется в поле** (`UUID.randomUUID()`) — «магическое» поведение: два несохранённых объекта получают разные uuid, опереться на `uuid` до `save()` нельзя [01:705–709] | Не переносится; в SYP uuid генерируется на вставке |

### F.1. Незакрытые каскады и «мины» удаления [01:329–363]

| Что | Факт |
|---|---|
| Объявленные каскады | `Project` → `files, persons, filters, cdfs`; `File` → `tracks, frames, faces, cdfs, shots`; `Person` → `faces`; `Filter` → `groups`; `FilterGroup` → `conditions`. Все — **только REMOVE** [01:331–339] |
| **Scene/Event не объявлены в `File`** | В `File.kt` нет полей `scenes`/`events`, хотя `file_id` в `tbl_scenes`/`tbl_events` есть. Связь односторонняя, JPA-каскада нет; удаление файла оставит сиротские сцены/события — чистят вручную нативными `DELETE` [01:345–349] |
| **Property/PropertyCdf** | Без FK — каскад невозможен; удаление вручную `PropertyController.deleteAll(parentClass, parentId)`, вызывается из `ShotController.delete`, `SceneController.delete`, `TrackController.delete`, `FilterConditionController.delete` и др. [01:350–352] |
| **ShotTmpCdf/ShotTmp2Cdf** | Без FK на `Shot` — при удалении шотов остаются висящие `shot_id` [01:353] |
| **Нативные `@Modifying @Query("DELETE …")` обходят Hibernate** | JPA-каскады при них не работают: `FileRepo.deleteAll(projectId)` удалит `tbl_files`, но не `tbl_frames`/`tbl_shots`/`tbl_faces`/`tbl_files_cdf`; вызывающий код обязан пройти по детям сам (в контроллерах сделано явно) [01:355–359] |
| **Двойная ссылка `Face` на две сущности** | `Face` под каскадом и файла, и персоны. Удаление персоны снесёт лица, которые одновременно принадлежат файлу; удаление файла снесёт лица «чужих» персон [01:361–363] |

### F.2. Прочие дефекты, попавшие в разбор

* Коллизия имён папок: `SHOTS` и `SHOTS_COMPRESSED_WITH_AUDIO` имеют одинаковый `folderName = "Shots"`,
  но разные ключи [01:591–593].
* Связка контейнер/кодек сделана **по строковому имени**, а не по enum-объекту:
  `VideoContainers.valueOf(project.container).extention`; у `LosslessContainers` и `VideoContainers`
  одинаковые `name`, но разные значения по умолчанию — `valueOf` по имени контейнера из lossless-поля
  может «подхватиться» не то расширение [01:622–628].
* Опечатки в enum'ах: `Extreame Close-Up` (вместо Extreme) [01:546], `PMC`/description `pcm_s16le`
  (должно быть PCM) [01:620].
* Несогласованность направления `ReorderTypes` [01:600–601] (см. C.4).
* `Face.vector` — `@Lob`-строка `"a|b|c"` с разбором через `split("\\|")` на каждое чтение [01:187–193].
* Схема БД генерится Hibernate'ом (`ddl-auto=update`, `generate-ddl=true`), **SQL-скриптов в
  репозитории нет** [01:6–7] — то есть истории схемы не существует.

---

## G. Пригодность для SYP

Сопоставление терминов: SYP-«Фильм/Сериал» ≈ ivfx4 `Project`; SYP-«серия = файл» ≈ ivfx4 `File`
[01:9–10]. Далее `Shot` = «план», `Scene` = «сцена» [01:10–11].

### G.1. Переносится как есть (с заменой слоя persistence)

| Элемент | Что именно берём | Почему |
|---|---|---|
| `Shot` как диапазон кадров | `file_id` + `firstFrameNumber` + `lastFrameNumber` (+ `nearestIFrame` по необходимости) | ровно та единица, на которой строится «план» в SYP [01:122–134] |
| `Scene` как диапазон кадров | `file_id` + `first`/`lastFrameNumber` + `name`; связь сцена→планы **вычисляется пересечением интервалов** | «находить переходы между сценами с точностью до кадра» [01:139–147] |
| Принадлежность «лицо → план» через диапазон кадров | JOIN `tbl_faces` по `first ≤ frameNumber ≤ last` и `file_id` | «в каждом кадре находить персонажей и их положение» [01:136–137, 432–440] |
| `Person` + `PersonType` | `person_id` на лице, `name`, `nameInRecognizer`, `personType`; служебные персоны `UNDEFINDED`/`NONPERSON` | готовый образец «персонажа-заглушки» вместо nullable-FK [01:195–215, 560–567] |
| `Face.bbox` | `startX, startY, endX, endY` | «их положение» [01:182] |
| `ShotTypePerson` | 6 значений + `pathToPicture` как UI-иконка | готовый справочник «кто в плане» [01:527–536] |
| `ShotTypeSize` | 10 значений ECU…XLS как **справочник**, если поле будет добавлено | конвенция уже заведена, не подключена [01:541–558] |
| `Frame` с признаками детектора | `isFind`, `isManualAdd`, `isManualCancel`, `isFinalFind`, `isIFrame` | следы ручной правки границ — нужны, если границы сцен редактируются руками [01:105–110] |
| `Shot.compareTo` через `file.order * 1e6 + firstFrameNumber` | как формула глобального порядка планов | [01:124] |
| `Filter`/`FilterGroup`/`FilterCondition` | **идея** сохраняемого запроса с 3 уровнями и `isAnd` на каждом | «собирать выборки сцен в один видеофайл» [01:12–13, 371–380] |
| `Track` | `type` (строка MediaInfo) + `use` | нужно, чтобы знать, какие дорожки тянуть при сборке [01:484–499] |
| `Project` — кодеки/контейнеры | `container`, `videoCodec`, `audioCodec`, `width/height`, **`fps: Double`** | параметры финальной сборки; `fps` обязателен для перевода кадров↔время [01:40–49] |

### G.2. Переписывается

| Элемент | Почему нельзя взять | Что делать в SYP |
|---|---|---|
| **Всё persistence** | JPA/Hibernate и `ddl-auto` [01:5–7]; SYP — сырой JDBC по образцу `KaraokeDbTable`, миграции нумерованными SQL [01:19–30, 281–284] | таблицы проектируются заново, но по той же картине из 18 → ~8 таблиц |
| **`Frame` — создание кадров** | `FrameRepo.createFrames` = нативный `INSERT … SELECT` с кросс-джойном шести таблиц цифр 0..9, генерирующий номера 1..N одной командой [01:119–120] | при SYP-объёмах (одна серия = десятки тысяч кадров, десятки серий) решение остаётся верным: массовая генерация кадров должна быть одной операцией, не «кадр за кадром» |
| **`Face.frameNumber` как денормализация** | «Frame в БД может отсутствовать» [01:179] | в SYP FK жёсткий, денормализация не нужна; но если кадры будут храниться не как строки, а как список найденных детектором, денормализация возвращается — это развилка |
| **Фильтры: исполнение** | ручной нативный запрос на каждую пару `objectClass × subjectClass`, 5 рабочих пар из ~15, пустые ветки `File`, три параллельных API [01:396–412, 685–687] | один универсальный исполнитель (см. развилку 6) |
| **Сортировка `Shot` через `file.order * 1e6`** | хрупко: магическое число и отсутствие уровня «сезон/серия-группа» | ввести явную иерархию Фильм → (сезон?) → серия и сортировать по ней [01:124] |
| **EAV как основное хранилище произвольных свойств** | `parent_class` строкой без FK + ручное удаление [01:223–229, 350–352] | см. развилку 5 |
| **`Face.vector` как `@Lob`-строка** | разбор `split("\\|")` на каждое чтение [01:187–193] | в SYP эмбеддинг, если нужен, — `bytea`/`vector` Postgres 16, отдельная таблица |
| **Кеширование связей** | «сцены по шоту» без кеша, запрос на каждую связку [01:697–699] | считать связь один раз при загрузке, отдавать из уже собранного графа |

### G.3. Не переносится (Windows-only / нерабочее / не нужно)

| Элемент | Причина |
|---|---|
| Всё `*Cdf` (5 сущностей) | машинозависимость; SYP одномашинный + MinIO (раздел E) [01:291–325] |
| `ShotTmpCdf` / `ShotTmp2Cdf` как общие временные таблицы | scratch в общей БД с очисткой по `computer_id`; `ShotTmp2Cdf` вдобавок никогда не читается запросами [01:310–315, 676–679] |
| `Main.ccid` через `oshi.SystemInfo` | Windows-desktop-специфика [01:297–300] |
| `getResource(...).file.substring(1)` | упадёт при запуске из JAR [01:538, 690–692] |
| Сырой JDBC-обход ORM в `FrameExt.facesExt()` | обход, чтобы «пережить прокси» [01:700–702] |
| `@ManyToMany` + `@JoinTable` + join-сущности `SceneShot`/`EventShot` | связь заменена на диапазоны кадров, остались только импорты [01:282–284, 658–663] |
| `Scene.parentId` / `Event.parentId` | объявлены, не заполняются, не читаются [01:680–681] |
| `Person.uuid` в инициализаторе поля | «магическое» поведение [01:705–709] |
| `Folders` как 15 персистентных папок | в SYP пути задаются в MinIO, локальных папок проекта нет [01:569–587] |
| `Event` как отдельная сущность | драматургическая единица поверх сцены; в задании SYP не заявлена (развилка 3) |
| Отсутствие `@Version`, индексов, уникальных ограничений | SYP: индексы обязательны с bootstrap [01:703–705] |
| Генерация схемы Hibernate'ом | SYP: `ddl-auto` запрещён, миграции append-only [01:6–7] |

### G.4. Порядок таблиц SYP по мотивам этого отчёта

Не проект, а вывод: из 18 таблиц уходят 5 Cdf [01:713–718] + `tbl_files_tracks` как отдельная сущность
(возможно, схлопывается в EAV/JSON) [01:156–167] + `tbl_events` (если Event не нужен) [01:149–154].
Остаётся ядро: проект/фильм, файл/серия, кадр, план, сцена, персонаж, лицо, свойства (+ тройка фильтров,
если фильтры переносятся).

---

## H. Явные вопросы владельцу, следующие из отчёта

1. **План и сцена — две сущности или одна?** В ivfx4 `Shot` (план, диапазон кадров) и `Scene` (сцена,
   диапазон кадров) — разные сущности, причём сцена состоит из планов **вычисляемо** [01:139–147].
   Для SYP «находить переходы между сценами с точностью до кадра» — детектор, вероятно, даёт границы
   сцен, а «план» = тип/ракурс внутри сцены. Оставляем две сущности и вычисляемую связь, или
   схлопываем в одну «Сцена» с полем «тип плана»?
2. **Где хранить границы: только номера кадров или время?** В ivfx4 границы — **только номера кадров**,
   `fps: Double` живёт на проекте [01:46, 144, 506–510]. Для SYP это сломается, если в одном фильме
   серии с разным fps/разрешением, а детектор работает в секундах/PTS. Нужен ли `time_base`/`duration`
   на уровне серии и хранение PTS на границах?
3. **Нужна ли `Event` (драматургическая единица)?** В ivfx4 это вторая, полностью параллельная Scene
   сущность, отличается только намерением, создаётся из сцены и может пересекаться с другими событиями
   [01:149–154, 515–517]. Для SYP в задании не заявлена: вводим третью сущность или не вводим?
4. **Связи кэшировать или считать на лету?** Связи сцена→план и лицо→план в ivfx4 вычисляемые, и
   обратная связь «планы по сцене» не кешируется — запрос на каждую связку [01:697–699]. SYP явно
   готовит «выборки сцен», где связность нужна постоянно: кэшировать в одном проходе при сборке DTO?
5. **EAV против типизированных колонок.** «Место действия» и «план» — это то, что пользователь
   будет вводить руками и по чему фильтровать. В ivfx4 это возможно только через EAV без FK
   [01:223–229] с ручным удалением [01:350–352] и фильтрацией строго на равенство [01:456].
   Что выбираем: типизированные колонки под горячие поля (место действия, тип плана, размер плана)
   плюс EAV на хвост, или EAV на всё?
6. **Сохраняемые фильтры в SYP — да/нет?** Модель трёх уровней с `isAnd` на каждом уровне и
   `isIncluded` — законченная и работающая [01:371–393], но с ручными запросами на каждую пару классов
   [01:396–408] и пустыми ветками [01:410–412]. Нужны ли SYP сохраняемые запросы как продукт, или
   достаточно «выбрал сцены → собрать видеофайл» без переиспользуемых фильтров?
7. **Неопознанные лица: заглушка-персонаж или nullable?** `Face.person` — **не-null `lateinit`**,
   нераспознанное и нелицное (аспект > 4) уходит в служебных персон `UNDEFINDED` / `NONPERSON`
   [01:177, 212–215, 560–567]. Плюс известная мина: `Face` под каскадом и файла, и персоны
   [01:361–363]. Оставляем заглушек (проще отчёты) или nullable `person_id` + `recognition_status`?
8. **Что показывать по персонажу в кадре помимо bbox?** В ivfx4 — 4 целых координаты, `faceNumberInFrame`,
   `personRecognizedName`, `recognizeProbability` [01:177–182]. Достаточно ли bbox для «их положение»,
   или нужен центр/масштаб (относительный размер лица в кадре)?
9. **Нужен ли эмбеддинг в БД?** `Face.vector` хранится строкой `"a|b|c"` [01:185, 187–193] и
   `isExample`/`isManual` помечают эталоны для обучения [01:183–184]. Если SYP планирует дообучение
   или кластеризацию лиц, хранить ли эмбеддинг в Postgres 16 (`vector`/`bytea`) и в какой таблице?
10. **Таблица `Frame` вообще нужна как строки?** В ivfx4 на кадр приходится ~15 полей признаков
    [01:105–112] и кадры создаются массовым `INSERT … SELECT` [01:119–120]. Для SYP (детектор сцен +
    детектор лиц на GPU) кадров будет много, а признаки нужны не для каждого: хранить ли все кадры
    файла или только «значимые» (внутри сцен, с лицами, с границами)?
11. **Дорожки файла (`Track`) — сущность или выгрузка MediaInfo?** В ivfx4 дорожка — строка с `type`
    и флагом `use`, а всё остальное — плоский EAV с потерей иерархии [01:484–498]. Для финальной
    сборки SYP достаточно ли «дорожки N, использовать да/нет»?
12. **Есть ли ручная правка границ?** В ivfx4 `Frame` хранит `isManualAdd`/`isManualCancel`/`isFinalFind`
    [01:107–110]. В SYP админка предполагает разметку — нужны ли ручные границы сцен, или границы
    только автоматические?

---

## Итог для карты решений

Развилки, требующие решения владельца. Порядок — по влиянию на модель данных.

1. **Сцена и план — две сущности или одна?** Ядро всей модели. Если две — наследуем вычисляемую связь
   «план целиком внутри сцены по интервалам кадров» [01:145–147]; если одна — схлопываем и фильтруем
   по типу плана. Влияет на все запросы и на «выборку сцен».
2. **Границы в кадрах или во времени.** Сейчас у ivfx4 только номера кадров и `fps: Double` на проекте
   [01:46, 144]. Серии с разным fps внутри одного фильма это ломает. Решение: `time_base` + PTS на
   границах + хранение номера кадра как производного.
3. **Типизированные колонки против EAV.** EAV даёт гибкость, но строковый `parent_class` без FK и
   ручное удаление [01:223–229, 350–352]. Для горячих полей (место действия, тип/размер плана)
   рекомендация — колонки; EAV оставить хвостом.
4. **Кешировать ли вычисляемые связи.** Связи сцена→план и лицо→план вычисляются всегда [01:136–147];
   в ivfx4 обратная связь не кешируется [01:697–699]. SYP со «сборкой выборок» выиграет от одного
   прохода.
5. **Неопознанные лица: заглушка-персонаж или nullable `person_id`.** В ivfx4 — заглушки
   `UNDEFINDED`/`NONPERSON` [01:212–215, 560–567] при известной каскадной мине [01:361–363].
   Влияет на отчёт «кто в сцене» и на удаление.
6. **Сохраняемые фильтры — продукт или нет.** Модель рабочая [01:371–393], но исполнение дырявое
   [01:396–412, 685–687]. Если да — нужен один универсальный исполнитель, а не N запросов.
7. **Таблица кадров: все кадры или только значимые.** [01:105–112, 119–120]. Влияет на объём БД
   и на скорость анализа.
8. **Вводить ли `Event` и иерархию сцен (`parentId`).** В ivfx4 оба — мёртвые [01:680–681, 506–507].
   Либо вводим осмысленно сразу, либо не вводим.
9. **Эмбеддинги лиц: хранить или нет.** [01:183–193]. Зависит от планов по распознаванию/дообучению.
10. **Что делать с `Track`.** Сущность или плоская выгрузка MediaInfo [01:484–499]. Влияет на
    финальную сборку видеофайла.

### Что переносится без вопросов

Иерархия фильм → серия; диапазоны кадров для плана и сцены; bbox лица; справочник типов плана
`ShotTypePerson` [01:527–536] и, при желании, `ShotTypeSize` [01:541–558]; признаки детектора и ручной
правки на кадре [01:105–112]; конвенция `personType` с заглушками [01:560–567]; глобальная формула
порядка планов [01:124]; параметры кодеков/контейнера проекта [01:40–49].

### Что не переносится ни при каких обстоятельствах

18 JPA-сущностей и `ddl-auto=update` [01:5–7, 19–30]; все 5 Cdf-сущностей и `oshi`-вычисление
`computerId` [01:291–325]; временные таблицы `ShotTmpCdf`/`ShotTmp2Cdf` [01:310–315]; `@ManyToMany` +
`SceneShot`/`EventShot` [01:282–284]; `getResource(...).file.substring(1)` [01:690–692]; сырой
JDBC-обход ORM [01:700–702]; `uuid` в инициализаторе поля [01:705–709].
