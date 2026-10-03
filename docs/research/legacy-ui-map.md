# Карта форм старого проекта ivfx4 (FXML)

Документ описывает все формы (FXML) старого desktop-проекта ivfx4, чтобы их можно
было перенести в браузер админки SYP. Форма здесь — отдельное окно со своей
разметкой; окна вложены друг в друга (модальные диалоги поверх основного).

## Источники

| Что | Путь | Как использовано |
|---|---|---|
| Формы | `/home/nsa/ivfx4/src/main/resources/com/svoemesto/ivfx/fxcontrollers/*.fxml` | первоисточник по структуре, fx:id, размерам, обработчикам |
| Отчёт по интерфейсу | `/home/nsa/ivfx4/legacy-analysis/04-ui-and-ux.md` (812 строк) | сверка сценариев и механик |
| Контроллеры | `/home/nsa/ivfx4/src/main/kotlin/com/svoemesto/ivfx/fxcontrollers/*.kt` | смысл действий: что делает обработчик кнопки |

Правило проекта SYP: из старого проекта **читать можно, код переносить нельзя**
(`AGENTS.md`, MUST #0). Этот документ — извлечённое знание о поведении, не
исходный код.

## Уточнение по числу форм

В задании указано 15 файлов FXML. Фактически в исходниках их **11**:

```
find /home/nsa/ivfx4 -name '*.fxml' -not -path '*/build/*'
  → 15 путей, из них 11 уникальных в src/main/resources/.../fxcontrollers/
  → остальные 4 — копии тех же файлов в target/classes/.../fxcontrollers/
     (database-edit, database-select, project-edit, project-select)
```

Цифра 15 — это 11 исходников плюс 4 файла сборочной копии. Ниже разобраны все
**11 уникальных форм**, они же 11 уникальных контроллеров.

## Соглашения по чтению

* Размеры приведены как в FXML: `prefWidth` × `prefHeight` корневого узла.
  Код окна размер **не задаёт**: во всех 11 контроллерах делается
  `mainStage = Stage()` + `mainStage.scene = Scene(root)` + `showAndWait()`
  без `sizeToScene()` и без присваивания ширины/высоты. Так что заявленный
  размер — это ровно то, что написано в FXML.
* Знак `⟰` (U+27F0), `⇧` (U+21E7), `⇩` (U+21E9), `⟱` (U+27F1) — реальные
  подписи кнопок в FXML, они приведены как есть.
* Кнопки добавления и удаления в старых FXML подписаны глифами **U+2795**
  (добавление) и **U+2716** (удаление), кнопка выбора папки — **U+1F4C2**.
  Эти символы здесь не воспроизводятся: линтер документации проекта
  (`docs/scripts/lint-docs.py`) считает их запрещёнными. Ниже они
  обозначены словами «плюс», «крестик», «папка» с указанием кода символа.
* «Дефект» — то, что подтверждено чтением либо FXML, либо тела обработчика.
  Догадки не приводятся.

## Сводка: 11 форм

| Форма | Строк FXML | fx:id | Таблиц | Столбцов | Кнопок | Модальность | Заголовок окна |
|---|---|---|---|---|---|---|---|
| `project-edit-view.fxml` | 771 | 98 | 7 | 14 | 36 | WINDOW_MODAL | «Проект: &lt;имя&gt;» или «Откройте или создайте проект.» |
| `project-select-view.fxml` | 95 | 10 | 1 | 2 | 6 | WINDOW_MODAL | «Выбор проекта.» |
| `project-actions-view.fxml` | 75 | 40 | 1 | 17 | 2 | **NONE** | не задан |
| `shots-edit-view.fxml` | 533 | 100 | 14 | 29 | 24 | WINDOW_MODAL | «Редактор планов. Файл: &lt;имя&gt;» |
| `frame-faces-edit-view.fxml` | 37 | 7 | 1 | 3 | 2 | APPLICATION_MODAL | не задан |
| `person-select-view.fxml` | 71 | 7 | 1 | 1 | 4 | APPLICATION_MODAL | не задан |
| `person-edit-view.fxml` | 171 | 19 | 2 | 3 | 9 | WINDOW_MODAL | не задан |
| `filter-edit-view.fxml` | 313 | 49 | 5 | 13 | 21 | WINDOW_MODAL | не задан |
| `filter-condition-create-view.fxml` | 56 | 18 | 0 | 0 | 3 | WINDOW_MODAL | не задан |
| `database-select-view.fxml` | 50 | 7 | 1 | 1 | 5 | WINDOW_MODAL | «Выбор базы данных» |
| `database-edit-view.fxml` | 145 | 8 | 0 | 0 | 2 | WINDOW_MODAL | «Редактирование базы данных» / «Добавление базы данных» |
| **Итого** | **2317** | **363** | **32** | **79** | **112** | | |

Шесть из 11 окон не имеют заголовка: код вызывает `setTitle` только в
`ProjectSelectFXController`, `DatabaseSelectFXController`,
`DatabaseEditFXController` и в двух `initialize()`
(`ProjectEditFXController.kt:466`, `ShotsEditFXController.kt:652`).

## Дерево экранов

```
project-edit-view.fxml                      главное окно, единственная точка входа
│
├─ Project ▸ Open...      → project-select-view.fxml
├─ Project ▸ Delete        → Alert, без отдельной формы
├─ Database ▸ Выбрать…    → database-select-view.fxml
│                          ├─ Редактировать базу данных → database-edit-view.fxml
│                          └─ Добавить новую базу данных → database-edit-view.fxml
├─ Actions ▸ Project Actions...  → project-actions-view.fxml   (Modality.NONE)
├─ Actions ▸ Edit shots...       → shots-edit-view.fxml
│   └─ ПКМ по кадру → Edit frame faces → frame-faces-edit-view.fxml
│       └─ Создать/выбрать персону → person-select-view.fxml
│           └─ Кнопка «плюс»      → person-edit-view.fxml
├─ Actions ▸ Edit filters...     → filter-edit-view.fxml
│   └─ Кнопка «плюс» у условий   → filter-condition-create-view.fxml
│       └─ Select Person          → person-select-view.fxml
└─ Actions ▸ Edit persons...     → person-edit-view.fxml
```

Только `project-actions-view.fxml` не модален (`Modality.NONE`) — оператор может
вернуться в главное окно, пока идёт пакетная обработка. Остальные десять либо
`WINDOW_MODAL` (блокируют родительское окно), либо `APPLICATION_MODAL`
(`person-select-view.fxml` и `frame-faces-edit-view.fxml` — блокируют всё
приложение).

---

# 1. project-edit-view.fxml — главное окно проекта

**Назначение.** Единственное окно, в котором живёт весь проект: параметры
проекта, список файлов, свойства, дорожки. Открывается при старте приложения
через `ProjectEditFXController.editProject(null, hostServices)`
(`ProjectEditFXController.kt:423`).

**Сценарий.** Шаг «запуск» и шаги 1–2 пользовательского пути: создание
проекта, добавление файлов, настройка параметров кодирования, выбор дорожек.
Далее форма служит точкой выхода ко всем остальным экранам через меню
`Actions` и `Database`.

**Размер.** `prefWidth=1502`, `prefHeight=1100`. Верхнеуровневый узел —
`AnchorPane` без `fx:id`.

## Дерево верхнего уровня

```
AnchorPane
├── MenuBar (без fx:id; anchored top, left, right, top=2)
└── AnchorPane fx:id="paneMain" (anchors: bottom/left/right=0, top=23)
    └── SplitPane dividerPositions=0.44137507673419274
        ├── AnchorPane (minWidth=720) — левая половина: проект и файлы
        │   └── VBox
        │       ├── HBox: fldProjectName, fldProjectShortName, fldProjectFolder + btnSelectProjectFolder
        │       ├── HBox: VBox «Video» (W/H/FPS/Bitrate/Codec) + VBox «Audio» (Bitrate/Frequncy/Codec)
        │       ├── HBox: cbProjectContainer, cbProjectLosslessCodec, cbProjectLosslessContainer
        │       ├── VBox: «Properties» → tblProjectProperties + fldProjectPropertyKey/Value
        │       ├── VBox: «Computer-Depened-Properties» → tblProjectPropertiesCdf + fldProjectPropertyCdfKey/Value
        │       ├── HBox: tblFiles + кнопки файлов
        │       ├── ProgressBar fx:id="pbFiles"
        │       └── Label fx:id="lblPbFiles" text="Label"
        └── AnchorPane fx:id="paneFile" — правая половина: выбранный файл
            └── VBox
                ├── HBox: fldFileName, fldFileShortName, fldFilePath + btnSelectFilePath
                └── HBox
                    ├── VBox (prefWidth=400): «Properties» tblFileProperties; «Computer-Depened-Properties» tblFilePropertiesCdf
                    └── VBox (prefWidth=400): btnGetFileTracksFromMediaInfo; «Tracks» tblTracks; «Track properties» tblTrackProperties
```

Делитель `0.441` означает: левая половина — 44 % ширины окна, правая — 56 %.

## Меню

| fx:id | Пункт | text | onAction |
|---|---|---|---|
| `menuProject` | `menuNewProject` | `New` | `#doMenuNewProject` |
| | `menuOpenProject` | `Open...` | `#doMenuOpen` |
| | `menuDeleteProject` | `Delete` | `#doMenuDeleteProject` |
| | `menuExit` | `Close` | `#doMenuExit` |
| `menuActions` | `menuProjectActions` | `Project Actions...` | `#doMenuProjectActions` |
| | `menuEditShots` | `Edit shots...` | `#doMenuEditShots` |
| | `menuEditFilters` | `Edit filters...` | `#doMenuEditFilters` |
| | `menuEditPersons` | `Edit persons...` | `#doMenuEditPersons` |
| `menuDatabase` | `menuSelectDatabase` | `Выбрать базу данных` | `#doSelectDatabase` |

Заголовок меню `Database` подменяется в `initialize()` на имя текущей БД
(`ProjectEditFXController.kt:467` и следом): он одновременно индикатор и вход в
выбор базы. Блокировка при отсутствии проекта — строки 467–471:
`menuDeleteProject`, `menuActions`, `menuEditFilters`, `menuEditPersons`
выключаются при `currentProjectExt == null`, `menuEditShots` — при
`currentFileExt == null`.

## Поля проекта

| fx:id | Тип | Подпись | Размеры |
|---|---|---|---|
| `fldProjectName` | TextField | `Name:` | prefWidth=10000, prefHeight=25 |
| `fldProjectShortName` | TextField | `Short:` | prefWidth=10000, prefHeight=25 |
| `fldProjectFolder` | TextField | `Folder:` | prefWidth=10000, prefHeight=25 |
| `btnSelectProjectFolder` | Button | `...` | по умолчанию |
| `fldProjectWidth` | TextField | `W (px):` | prefWidth=10000, Label minWidth=40 |
| `fldProjectHeight` | TextField | `H (px):` | prefWidth=10000, Label minWidth=40 |
| `fldProjectFps` | TextField | `FPS:` | prefWidth=10000 |
| `fldProjectVideoBitrate` | TextField | `Bitrate:` (Video) | prefWidth=10000 |
| `cbProjectVideoCodec` | ComboBox | `Codec:` (Video) | prefWidth=10000 |
| `fldProjectAudioBitrate` | TextField | `Bitrate:` (Audio), Label minWidth=60 | prefWidth=10000 |
| `fldProjectAudioFrequency` | TextField | `Frequncy:` (Audio) | prefWidth=10000 |
| `cbProjectAudioCodec` | ComboBox | `Codec:` (Audio) | prefWidth=10000 |
| `cbProjectContainer` | ComboBox | `Container:` | prefWidth=10000, Label minWidth=40 |
| `cbProjectLosslessCodec` | ComboBox | `Lossless Codec:` | prefWidth=10000 |
| `cbProjectLosslessContainer` | ComboBox | `Lossless Container:` | prefWidth=10000 |

## Таблица файлов

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblFiles` | TableView | файлы проекта | minWidth=250, prefHeight=10000 |
| `colFileOrder` | TableColumn `#` | порядок файла | prefWidth=40 |
| `colFileName` | TableColumn `Файл` | имя файла | prefWidth=190 |
| `btnFileMoveToFirst` | Button `⟰` | «В начало списка» | 46 × 46 |
| `btnFileMoveUp` | Button `⇧` | «На один уровень вверх» | 46 × 46, Arial 24 |
| `btnFileMoveDown` | Button `⇩` | «На один уровень вниз» | 46 × 46, Arial 24 |
| `btnFileMoveToLast` | Button `⟱` | «В конец списка» | 46 × 46 |
| `btnFileAdd` | Button «плюс» (U+2795) | «Добавить файл» | 46 × 46 |
| `btnFileAddFilesFromFolder` | Button «папка» (U+1F4C2) | «Добавить файлы из папки» | 46 × 46 |
| `btnFileDelete` | Button «крестик» (U+2716) | «Удалить файл из проекта» | 46 × 46 |
| `pbFiles` | ProgressBar | загрузка списка файлов | maxWidth=∞ |
| `lblPbFiles` | Label | текст прогресса | `text="Label"` в FXML |

Между кнопкой порядка и «плюсом» стоит `Separator`, и ещё один `Separator` —
между «папкой» и «крестиком»: группы действий визуально разделены.

## Четыре таблицы свойств

Все четыре устроены одинаково: `TableView` (minWidth=350, minHeight=300) с
колонками Key/Value, вертикальный ряд из шести кнопок, `TextField` для ключа и
`TextArea` для значения (`minHeight=60, prefHeight=200`).

| Назначение | TableView | Колонки | Поля | Кнопки (префикс) | Кнопка выбора папки |
|---|---|---|---|---|---|
| Свойства проекта | `tblProjectProperties` | `colProjectPropertyKey` (135), `colProjectPropertyValue` (200) | `fldProjectPropertyKey`, `fldProjectPropertyValue` | `btnProjectProperty*` | нет |
| Свойства проекта, зависящие от машины | `tblProjectPropertiesCdf` | `colProjectPropertyCdfKey` (135), `colProjectPropertyCdfValue` (200) | `fldProjectPropertyCdfKey`, `fldProjectPropertyCdfValue` (prefWidth=10000) | `btnProjectPropertyCdf*` | `btnBrowseProjectPropertyCdfValue` (46 × 46) |
| Свойства файла | `tblFileProperties` | `colFilePropertyKey` (135), `colFilePropertyValue` (200) | `fldFilePropertyKey`, `fldFilePropertyValue` | `btnFileProperty*` | нет |
| Свойства файла, зависящие от машины | `tblFilePropertiesCdf` | `colFilePropertyCdfKey` (135), `colFilePropertyCdfValue` (200) | `fldFilePropertyCdfKey`, `fldFilePropertyCdfValue` (prefWidth=10000) | `btnFilePropertyCdf*` | `btnBrowseFilePropertyCdfValue` (46 × 46) |

Суффиксы кнопок одинаковы в четырёх наборах: `MoveToFirst`, `MoveUp`,
`MoveDown`, `MoveToLast`, `Add` (U+2795), `Delete` (U+2716). Все 46 × 46.

## Правая половина: файл

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `fldFileName` | TextField | `Name:` | prefWidth=10000, prefHeight=25, Label minWidth=70 |
| `fldFileShortName` | TextField | `Short name:` | prefWidth=10000, prefHeight=25 |
| `fldFilePath` | TextField | `Path:` | prefWidth=10000, prefHeight=25 |
| `btnSelectFilePath` | Button | `...` | по умолчанию |
| `btnGetFileTracksFromMediaInfo` | Button | `Get tracks from MediaInfo` | maxWidth=∞ |
| `tblTracks` | TableView | дорожки файла | minHeight=300, prefWidth=10000 |
| `colTrackUse` | TableColumn `Use` | признак «брать в результат» | 135 |
| `colTrackType` | TableColumn `Type` | тип дорожки | 200 |
| `tblTrackProperties` | TableView | свойства дорожки | minHeight=300, prefWidth=10000 |
| `colTrackPropertyKey` | TableColumn `Key` | | 135 |
| `colTrackPropertyValue` | TableColumn `Value` | | 200 |

## Действия оператора

**Меню.** New, Open, Delete, Close, четыре пункта Actions, выбор базы.

**Кнопки файлов.** Порядок `⟰ ⇧ ⇩ ⟱`; добавление одного файла (диалог выбора
файла, заголовок «Добавить файл к проекту»); добавление всех файлов из папки
(диалог выбора папки); удаление файла (Alert «Удаление файла»); вставка пути
вручную в `fldFilePath` + `btnSelectFilePath` (диалог «Выберите файл»).

**Двойной клик (5 таблиц), `ProjectEditFXController.kt:891–944`:**

| Таблица | Условие | Действие |
|---|---|---|
| `tblFileProperties` | ключ начинается с `url_` | открыть ссылку через `hostServices.showDocument(value)` |
| `tblFilePropertiesCdf` | ключ начинается с `folder_` | открыть папку; если значение пустое, папка вычисляется через `FileController.getCdfFolder` |
| `tblProjectProperties` | ключ начинается с `url_` | открыть ссылку |
| `tblProjectPropertiesCdf` | ключ начинается с `folder_` | открыть папку, вычисленную через `ProjectController.getCdfFolder` |
| `tblTracks` | `track.type` не `General` и не `Video` | переключить `track.use` и сохранить |

**Перетаскивание:** не используется.

**Клавиатура:** обработчиков клавиш в форме нет.

## Данные

Семь таблиц: `tblFiles` (2 колонки), четыре таблицы свойств (по 2 колонки),
`tblTracks` (2 колонки), `tblTrackProperties` (2 колонки).

Тексты-заглушки при пустой таблице задаются в коде
(`ProjectEditFXController.kt:457–463`), в FXML их нет:

| Таблица | Placeholder |
|---|---|
| `tblFiles` | `Project not selected or don't have any files.` |
| `tblProjectProperties` | `Project not selected or don't have any properties.` |
| `tblFileProperties` | `File not selected or don't have any properties.` |
| `tblProjectPropertiesCdf` | `Project not selected or don't have any computer-depended properties.` |
| `tblFilePropertiesCdf` | `File not selected or don't have any computer-depended properties.` |
| `tblTracks` | `File not selected or don't have any tracks.` |
| `tblTrackProperties` | `Track not selected or don't have any properties..` |

Во всех таблицах стиль выделения задан явно:
`style="-fx-selection-bar: red; -fx-selection-bar-non-focused: red;"`.
Колонка Value во всех таблицах свойств и в свойствах дорожек имеет
`cellFactory` с переносом по словам: `text.wrappingWidthProperty().bind(col.widthProperty())`.

## Дефекты и особенности

1. **Тултипы кнопок свойств скопированы из шаблона списка файлов.** Кнопка
   «плюс» у `tblProjectProperties`, `tblProjectPropertiesCdf`,
   `tblFileProperties`, `tblFilePropertiesCdf` и `tblTrackProperties` имеет
   тултип «Добавить файл», кнопка «крестик» — «Удалить файл из проекта»
   (`project-edit-view.fxml:265, 274, 357, 366, 610, 619, 692, 701`).
2. **Опечатка в подписи:** `Frequncy:` вместо `Frequency:` (строка 162).
3. **Опечатка в заголовке блока:** `Computer-Depened-Properties` вместо
   `Computer-Dependent-Properties` — дважды, строки 290 и 635.
4. **Английская заглушка в русском UI:** `lblPbFiles` имеет `text="Label"`
   (строка 493) и видна до начала загрузки.
5. **Тултип кнопки выбора папки** у `btnBrowseProjectPropertyCdfValue` и
   `btnBrowseFilePropertyCdfValue` — «Добавить файлы из папки» (строки 381,
   726). Кнопка открывает выбор **одной** папки, тултип вводит в заблуждение.
6. **Смешение языков в интерфейсе:** подписи полей — английские
   (`Name:`, `Short:`, `Folder:`, `W (px):`, `FPS:`, `Bitrate:`, `Codec:`,
   `Container:`, `Key`, `Value`, `Tracks`, `Get tracks from MediaInfo`),
   пункты меню — английские (`New`, `Open...`, `Delete`, `Close`,
   `Project Actions...`, `Edit shots...`), при этом `menuSelectDatabase` —
   русский («Выбрать базу данных»), а тултипы кнопок порядка — русские.
7. **Автосохранение без кнопки.** Явной кнопки «Сохранить» в форме нет:
   поля сохраняются по потере фокуса и при закрытии окна.
8. **Заголовок окна меняет смысл при старте:** при пустой БД — «Откройте или
   создайте проект.», при выбранном — «Проект: <имя>».

---

# 2. project-select-view.fxml — выбор проекта

**Назначение.** Диалог выбора проекта из списка с переупорядочиванием.

**Сценарий.** Шаг «Project ▸ Open...».

**Размер.** `prefWidth=400`, `prefHeight=400`. Модальность `WINDOW_MODAL`.
Заголовок «Выбор проекта.» (`ProjectSelectFXController.kt:72`).

## Дерево верхнего уровня

```
AnchorPane
└── VBox (anchors 0/0/0/0, padding 5 со всех сторон)
    ├── Label fx:id="lblDb" text="DB", System Bold 10
    ├── HBox
    │   ├── TableView fx:id="tblProjects" prefWidth=350
    │   └── VBox minWidth=46 — четыре кнопки порядка
    ├── Button fx:id="btnOk" text="OK"
    └── Button fx:id="btnCancel" text="Отмена"
```

## Элементы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `lblDb` | Label | живой текст «БД: <имя текущей БД>» | maxWidth=∞, System Bold 10, нижний отступ 5 |
| `tblProjects` | TableView | список проектов | prefWidth=350, maxWidth/Height=∞ |
| `colOrder` | TableColumn `#` | порядок | maxWidth=40, prefWidth=40 |
| `colName` | TableColumn `Проект` | имя проекта | minWidth=282, maxWidth=∞ |
| `btnMoveToFirst` | Button `⟰` | «В начало списка» | 46 × 46, Arial 18 |
| `btnMoveUp` | Button `⇧` | «На один уровень вверх» | 46 × 46, Arial 24 |
| `btnMoveDown` | Button `⇩` | «На один уровень вниз» | 46 × 46, Arial 24 |
| `btnMoveToLast` | Button `⟱` | «В конец списка» | 46 × 46, Arial 18 |
| `btnOk` | Button | `OK` | maxWidth=∞, верхний отступ 5 |
| `btnCancel` | Button | `Отмена` | maxWidth=∞, верхний отступ 5 |

## Действия оператора

* Четыре кнопки порядка вызывают `doMoveToFirst` / `doMoveUp` / `doMoveDown` /
  `doMoveToLast`.
* `btnOk` → `doOk`: сохраняет выбор и закрывает окно
  (`ProjectSelectFXController.kt:164`).
* `btnCancel` → `doCancel`: возвращает `incomingProject`, то есть откатывает
  выбор (строка 129).
* **Двойной клик по строке** `tblProjects` — закрывает окно, то есть работает
  как OK (`ProjectSelectFXController.kt:119–125`).
* Перетаскивания и контекстных меню нет.

## Данные

Две колонки: `colOrder` (`#`, поле `order`), `colName` (`Проект`, поле `name`).
Стиль выделения — красный, как во всех формах проекта.

## Дефекты и особенности

1. **Подпись `DB` в FXML заменяется в коде** на «БД: <имя>»
   (`ProjectSelectFXController.kt` — присваивание `lblDb`). В самом FXML
   остаётся английское `DB`.
2. **Четыре кнопки порядка заблокированы при отсутствии выбранной строки** —
   блокировка ставится в коде, в FXML атрибутов `disable` нет.
3. Мелочь: у `btnMoveUp` тултип имеет вложенный `<font size="14.0"/>`, у
   остальных — нет (строки 55–59). На поведение не влияет.

---

# 3. project-actions-view.fxml — пакетная обработка

**Назначение.** Панель запуска конвейера обработки: оператор отмечает нужные
операции и файлы и нажимает «Do actions». Плюс обучение модели распознавания
лиц.

**Сценарий.** Шаг 3 — «Запуск обработки», между добавлением файлов и
просмотром результатов.

**Размер.** `prefWidth=1280`, `prefHeight=720`. Модальность **NONE** —
единственное немодальное окно приложения, оператор может вернуться в
главное окно. Заголовок не задан.

## Дерево верхнего уровня

```
AnchorPane
└── VBox (anchors 0/0/0/0)
    ├── HBox
    │   ├── VBox prefWidth=10000, prefHeight=10000
    │   │   ├── TableView fx:id="tblFilesExt" minWidth=800, prefHeight=10000
    │   │   └── Button fx:id="btnTrainFaceModel" text="Train face model"
    │   └── VBox prefWidth=10000, prefHeight=10000
    │       ├── CheckBox fx:id="checkReCreateIfExists" text="RECREATE IF EXISTS"
    │       ├── Button fx:id="btnDoActions" text="Do actions"
    │       └── 15 CheckBox — операции (см. ниже)
    ├── ProgressBar fx:id="pb1" progress=0.0
    ├── Label fx:id="lblPb1"
    ├── ProgressBar fx:id="pb2" progress=0.0
    └── Label fx:id="lblPb2"
```

## Таблица файлов: 17 колонок-индикаторов

`tblFilesExt` — `SelectionMode.MULTIPLE`
(`ProjectActionsFXController.kt:210`), minWidth=800, prefHeight=10000. Все
колонки-индикаторы имеют ширину 30, кроме двух первых.

| fx:id | text | Ширина | Поле | Смысл |
|---|---|---|---|---|
| `colFileExtOrder` | `#` | prefWidth=30 | `fileOrder` | порядок файла |
| `colFileExtName` | `Файл` | prefWidth=200 | `fileName` | имя |
| `colFileExtPW` | `PW` | 29 | `hasPreviewString` | превью создано |
| `colFileExtLL` | `LL` | 30 | `hasLosslessString` | lossless-копия создана |
| `colFileExtFS` | `FS` | 30 | `hasFramesSmallString` | кадры 175×35 |
| `colFileExtFM` | `FM` | 30 | `hasFramesMediumString` | кадры 720×400 |
| `colFileExtFF` | `FF` | 30 | `hasFramesFullString` | кадры 1920×1080 |
| `colFileExtAF` | `AF` | 30 | `hasAnalyzedFramesString` | кадры проанализированы, границы найдены |
| `colFileExtCS` | `CS` | 30 | `hasCreatedShotsString` | планы созданы |
| `colFileExtDF` | `DF` | 30 | `hasDetectedFacesString` | лица обнаружены |
| `colFileExtCF` | `CF` | 30 | `hasCreatedFacesString` | лица вырезаны в файлы |
| `colFileExtCFP` | `CFP` | 30 | `hasCreatedFacesPreviewString` | превью лиц созданы |
| `colFileExtRF` | `RF` | 30 | `hasRecognizedFacesString` | лица распознаны |
| `colFileExtSCA` | `SCA` | 30 | `hasShotsCompressedWithAudioString` | видео планов сжатое, со звуком |
| `colFileExtSLA` | `SLA` | 30 | `hasShotsLosslessWithAudioString` | видео планов lossless mxf, со звуком |
| `colFileExtSLN` | `SLN` | 30 | `hasShotsLosslessWithoutAudioString` | видео планов lossless mxf, без звука |
| `colFileExtCC` | `CC` | 30 | `hasConcatString` | сконкатенированный файл |

Это, по сути, матрица состояния конвейера: 15 буквенных кодов совпадают с
префиксами чекбоксов операций ниже.

## Чекбоксы операций

Порядок в FXML (сверху вниз) важен — он совпадает с порядком конвейера.

| fx:id | Текст в UI | Класс потока |
|---|---|---|
| `checkReCreateIfExists` | `RECREATE IF EXISTS` | глобальный переключатель, не операция |
| `btnDoActions` (Button) | `Do actions` | — |
| `checkCreatePreview` | `[PW] Create preview` | `CreatePreview` |
| `checkCreateLossless` | `[LL] Create lossless` | `CreateLossless` |
| `checkCreateFramesSmall` | `[FS] Create frames small` | `CreateFramesSmall` |
| `checkCreateFramesMedium` | `[FM] Create frames medium` | `CreateFramesMedium` |
| `checkCreateFramesFull` | `[FF] Create frames full` | `CreateFramesFull` |
| `checkAnalyzeFrames` | `[AF] Analyze frames` | `AnalyzeFrames` |
| `checkCreateShots` | `[CS] Create shots` | `CreateShots` |
| `checkDetectFaces` | `[DF] Detect faces` | `DetectFaces` |
| `checkCreateFaces` | `[CF] Create faces` | `CreateFaces` |
| `checkCreateFacesPreview` | `[CFP] Create faces preview` | `CreateFacesPreview` |
| `checkRecognizeFaces` | `[RF] Recognize faces` | `RecognizeFaces` |
| `checkCreateShotsCompressedWithAudio` | `[SCA] Create shots video files (compressed, with audio) - need LL!!!` | `CreateShotsCompressedWithAudio` |
| `checkCreateShotsLosslessWithAudio` | `[SLA] Create shots video files (lossless mxf, with audio) - need LL!!!` | `CreateShotsLosslessWithAudio` |
| `checkCreateShotsLosslessWithoutAudio` | `[SLN] Create shots video files (lossless mxf, without audio) - need LL!!!` | `CreateShotsLosslessWithoutAudio` |
| `checkCreateConcat` | `[CC] Create concatinated video file - need SLA!!!` | `CreateConcat` |
| `btnTrainFaceModel` (Button) | `Train face model` | вне списка — отдельная кнопка под таблицей |

Все 16 файлов классов потоков подтверждены в
`src/main/kotlin/com/svoemesto/ivfx/threads/projectactions/`.

## Действия оператора

* Выделить один или несколько файлов в `tblFilesExt` (мультивыбор).
* Отметить нужные чекбоксы операций.
* Нажать `btnDoActions`. Механика: первый проход считает пары «файл × операция»,
  для которых `флажок && (!файл.ужеСделано || чекбокс RECREATE IF EXISTS)`;
  второй проход создаёт по потоку на каждую подходящую пару и запускает их
  списком.
* Нажать `btnTrainFaceModel` — выгружает `embeddings.json` и запускает
  `train_model_json.py`.
* Ни двойных кликов, ни перетаскивания, ни контекстных меню на форме нет.

## Данные

Одна таблица на 17 колонок; из них 15 — строковые индикаторы «сделано/не
сделано» (значения вида «да/нет» приходят через `hasXxxString`), две — номер и
имя файла.

## Дефекты и особенности

1. **Предварительные условия записаны только в тексте чекбоксов**
   (`- need LL!!!`, `- need SLA!!!`) и **программно не проверяются**. Если
   оператор отметил `[SCA]` без `[LL]`, операция упадёт в потоке.
2. **Идемпотентность по умолчанию:** операция пропускается, если флаг «уже
   сделано» стоит и `RECREATE IF EXISTS` снят. Обратного предупреждения
   оператору нет.
3. **Два индикатора вместо одного** (`pb1`/`lblPb1` и `pb2`/`lblPb2`): в
   `initialize()` все четыре скрыты (`isVisible = false`, строки 232–235) и
   показываются по мере надобности, потому что внутри одного действия фаз-фая
   и питона используют разные индикаторы. Воспроизводить эту двойственность
   в браузере смысла нет — скорее один прогресс на действие.
4. **Весь интерфейс формы английский**, тогда как `project-edit-view.fxml`
   содержит русские подписи. Опечатка в тексте: `concatinated`.
5. **Окно без заголовка** и без кнопок «закрыть/применить»: форма работает как
   утилита, а не как диалог.
6. Мелочь реализации: FXML грузится через
   `ProjectEditFXController::class.java.getResource(...)`
   (`ProjectActionsFXController.kt:186`), а не через собственный класс. Работает
   только потому, что все контроллеры лежат в одном пакете.

---

# 4. shots-edit-view.fxml — редактор планов

**Назначение.** Главный рабочий экран оператора: ручная правка границ планов,
привязка лиц к персонам, создание сцен и событий. Самая большая форма — 533
строки, 100 fx:id, 14 таблиц, 29 столбцов, 24 кнопки.

**Сценарий.** Шаги 4–5 пользовательского пути: просмотр результатов анализа и
ручное редактирование. Открывается из `Actions ▸ Edit shots...` для выбранного
файла.

**Размер.** `prefWidth=2120`, `prefHeight=1200`. Модальность `WINDOW_MODAL`.
Заголовок «Редактор планов. Файл: &lt;имя файла&gt;»
(`ShotsEditFXController.kt:652`).

## Дерево верхнего уровня

```
AnchorPane
└── HBox (anchors 0/0/0/0)
    ├── VBox maxWidth=730 — ЛЕВАЯ ЧАСТЬ, всегда видна
    │   ├── HBox
    │   │   ├── VBox
    │   │   │   ├── TableView fx:id="tblShots" minWidth=440, prefWidth=440
    │   │   │   ├── ProgressBar fx:id="pbShots" prefWidth=200
    │   │   │   └── VBox: Label «Shot properties» + tblShotProperties + поля
    │   │   └── VBox minWidth=720
    │   │       ├── HBox: tblPersonsAllForShot + pbPersonsForShot | фильтры персон и лиц
    │   │       └── Label fx:id="lblFrameFull" 720×400
    │   └── Button fx:id="btnOK" text="OK"
    ├── Separator orientation=VERTICAL
    ├── VBox minWidth=920, prefWidth=5000 — ПРАВАЯ ЧАСТЬ
    │   ├── TabPane (без fx:id), 4 вкладки
    │   │   ├── Tab «Frames»   → paneFrames + tblPagesFrames
    │   │   ├── Tab «Persons»  → tblPersonsAllForFile + paneFaces + tblPagesFaces
    │   │   ├── Tab «Scenes»   → tblScenes + tblShotsForScenes + tblPersonsAllForScenes
    │   │   └── Tab «Events»   → tblEvents + tblShotsForEvents + tblPersonsAllForEvents
    │   ├── ProgressBar fx:id="pb" prefWidth=200
    │   └── Label fx:id="lblPb" text="Label"
    └── Separator orientation=VERTICAL
```

Левая часть ограничена 730 px, правая требует минимум 920 px. В сумме с
разделителями это и даёт заявленные 2120 px.

## Левая часть: планы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblShots` | TableView | планы файла, `SelectionMode.MULTIPLE` (строка 846) | minWidth=440, prefWidth=440, prefHeight=5000 |
| `colShotFrom` | TableColumn `FROM` | миниатюра первого кадра + подпись времени | 135 × 135 |
| `colShotTo` | TableColumn `TO` | миниатюра последнего кадра | 135 × 135 |
| `colShotType` | TableColumn `TYPE` | пиктограмма типа плана | 135 × 135 |
| `colButtonGetType` | TableColumn (без text) | кнопка выбора типа | 25 × 25 |
| `pbShots` | ProgressBar | загрузка списка планов | prefWidth=200 |
| `tblShotProperties` | TableView | свойства выбранного плана | minWidth=350, minHeight=300 |
| `colShotPropertyKey` | TableColumn `Key` | | 135 |
| `colShotPropertyValue` | TableColumn `Value` | | 200 |
| `fldShotPropertyKey` | TextField | ключ свойства | по умолчанию |
| `fldShotPropertyValue` | TextArea | значение свойства | minHeight=60, prefHeight=200 |
| `tblPersonsAllForShot` | TableView | персоны, попавшие в выбранный план | 175 × 175 |
| `colTblPersonsAllForShotName` | TableColumn `Name` | фото персоны 135×75 | 135 |
| `pbPersonsForShot` | ProgressBar | | maxWidth=∞ |
| `lblFrameFull` | Label | крупный кадр с прямоугольниками лиц | 720 × 400, чёрный фон |
| `contextMenuFrameFull` | ContextMenu | привязан к `lblFrameFull` | — |
| `btnOK` | Button | `OK` — закрыть окно | maxWidth=∞ |

Шесть кнопок свойств плана: `btnShotPropertyMoveToFirst`,
`btnShotPropertyMoveUp`, `btnShotPropertyMoveDown`, `btnShotPropertyMoveToLast`,
`btnShotPropertyAdd` (U+2795), `btnShotPropertyDelete` (U+2716), все 46 × 46.

## Левая часть: фильтры персон и лиц

| fx:id | Тип | Текст | Состояние по умолчанию |
|---|---|---|---|
| `rbPersonAll` | RadioButton | `All` | группа `grpPersons`, не выбран |
| `rbPersonFile` | RadioButton | `File` | группа `grpPersons`, **выбран** |
| `rbFaceAll` | RadioButton | `All` | группа `grpFaces`, не выбран |
| `rbFaceFile` | RadioButton | `File` | группа `grpFaces`, **выбран** |
| `cbFacesNotExample` | CheckBox | `Not example` | **выбран** |
| `cbFacesExample` | CheckBox | `Example` | **выбран** |
| `cbFacesNotManual` | CheckBox | `Not manual` | **выбран** |
| `cbFacesManual` | CheckBox | `Manual` | **выбран** |

Два неименованных `Label`: `Persons:` и `Faces:` (строки 149, 156).
Обработчики: `rbPerson*` → `#doSelectPersonsRb`, `rbFace*` → `#doSelectFacesRb`,
все четыре чекбокса → `#doSelectFacesCb`.

## Вкладка Frames

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `paneFrames` | Pane | холст матрицы миниатюр кадров | prefWidth=10000, prefHeight=10000, чёрный фон |
| `tblPagesFrames` | TableView | «страницы» кадров | minWidth=300, prefWidth=300, prefHeight=5000 |
| `colDurationStart` | TableColumn `Время: с` | начало диапазона страницы | prefWidth=75 |
| `colDurationEnd` | TableColumn `Время: по` | конец диапазона | prefWidth=75 |
| `colFrameStart` | TableColumn `Кадры: с` | первый номер кадра | prefWidth=65 |
| `colFrameEnd` | TableColumn `Кадры: по` | последний номер кадра | prefWidth=65 |
| `pbPagesFrames` | ProgressBar | | prefWidth=200 |

## Вкладка Persons

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblPersonsAllForFile` | TableView | все персоны файла, цель для drop | 175 × 175 |
| `colTblPersonsAllForFileName` | TableColumn `Name` | фото персоны | 135 |
| `pbPersonsForFile` | ProgressBar | | prefWidth=200 |
| `paneFaces` | Pane | холст матрицы миниатюр лиц | prefWidth=10000, prefHeight=10000, чёрный фон |
| `pbFaces` | ProgressBar | | prefWidth=200 |
| `tblPagesFaces` | TableView | «страницы» лиц | minWidth=60, prefWidth=200 |
| `colTblPagesFacesNumber` | TableColumn `#` | номер страницы | 40 |

## Вкладка Scenes

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblScenes` | TableView | сцены, `SelectionMode.MULTIPLE` (строка 945) | prefWidth=490, prefHeight=10000 |
| `colSceneName` | TableColumn `NAME` | имя сцены | 200 |
| `colSceneFrom` | TableColumn `FROM` | | 135 |
| `colSceneTo` | TableColumn `TO` | | 135 |
| `pbScenes` | ProgressBar | | maxWidth=∞ |
| `btnCreateNewSceneBySelectedShots` | Button | `Create new scene by selected shots` | maxWidth=∞ |
| `btnDeleteSelectedScenes` | Button | `Delete selected scenes` | maxWidth=∞ |
| `btnCreateEventBasedScene` | Button | `Create Event Based Scene` | maxWidth=∞ |
| `tblSceneProperties` | TableView | свойства сцены | minWidth=350, minHeight=300 |
| `colScenePropertyKey` / `colScenePropertyValue` | TableColumn | `Key` / `Value` | 135 / 200 |
| `fldScenePropertyKey` / `fldScenePropertyValue` | TextField / TextArea | | TextArea minHeight=60 |
| `tblShotsForScenes` | TableView | планы выбранных сцен | prefWidth=290, prefHeight=10000 |
| `colShotForSceneFrom` / `colShotForSceneTo` | TableColumn | `FROM` / `TO` | 135 / 135 |
| `pbShotsForScenes` | ProgressBar | | maxWidth=∞ |
| `tblPersonsAllForScenes` | TableView | персоны выбранных сцен | prefWidth=150, prefHeight=10000 |
| `colTblPersonsAllForSceneName` | TableColumn `Name` | | 135 |
| `pbPersonsForScenes` | ProgressBar | | maxWidth=∞ |

Шесть кнопок свойств сцены: `btnSceneProperty*` (MoveToFirst, MoveUp,
MoveDown, MoveToLast, Add, Delete), 46 × 46.

## Вкладка Events

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblEvents` | TableView | события, `SelectionMode.MULTIPLE` (строка 1116) | prefWidth=490, prefHeight=10000 |
| `colEventName` | TableColumn `NAME` | | 200 |
| `colEventFrom` / `colEventTo` | TableColumn | `FROM` / `TO` | 135 / 135 |
| `pbEvents` | ProgressBar | | maxWidth=∞ |
| `btnCreateNewEventBySelectedShots` | Button | `Create new event by selected shots` | maxWidth=∞ |
| `btnDeleteSelectedEvents` | Button | `Delete selected events` | maxWidth=∞ |
| `tblEventProperties` | TableView | свойства события | minWidth=350, minHeight=300 |
| `colEventPropertyKey` / `colEventPropertyValue` | TableColumn | `Key` / `Value` | 135 / 200 |
| `fldEventPropertyKey` / `fldEventPropertyValue` | TextField / TextArea | | TextArea minHeight=60 |
| `tblShotsForEvents` | TableView | планы выбранных событий | prefWidth=290, prefHeight=10000 |
| `colShotForEventFrom` / `colShotForEventTo` | TableColumn | `FROM` / `TO` | 135 / 135 |
| `pbShotsForScenes1` | ProgressBar | **см. дефект 3** | maxWidth=∞ |
| `tblPersonsAllForEvents` | TableView | персоны выбранных событий | prefWidth=150, prefHeight=10000 |
| `colTblPersonsAllForEventName` | TableColumn `Name` | | 135 |
| `pbPersonsForScenes1` | ProgressBar | **см. дефект 3** | maxWidth=∞ |

Шесть кнопок свойств события: `btnEventProperty*`, 46 × 46. Кнопки создания и
удаления событий — **две**, на сцене их **три**: вкладка Scenes не имеет
аналога `Create new event by selected shots` (её роль выполняет
`btnCreateEventBasedScene`).

## Действия оператора

### Планы (левая часть)

| Действие | Как |
|---|---|
| Выбрать план | ЛКМ по строке `tblShots` |
| Множественный выбор | мультивыбор включён на `tblShots` |
| **Переключить границу плана** | **двойной ЛКМ по миниатюре кадра в `paneFrames`** (`ShotsEditFXController.kt:2051`). Четыре случая: кадр `isFind` и не отменён → отмена; `isFind` и отменён → восстановление; не `isFind` → ручная отметка границы; ручная отметка → снятие |
| Выбрать тип плана | кнопка в колонке `colButtonGetType` открывает меню из 6 значений `ShotTypePerson` (NONE, SGN, OTS, TWO, GRP, MASS) пиктограммами (`onActionButtonGetShotType`, строка 2571) |
| Изменить свойства плана | выбрать строку, править `fldShotPropertyKey` / `fldShotPropertyValue`, сохраняется по потере фокуса |
| Добавить свойство | «плюс» открывает `ContextMenu` (не диалог) |
| Открыть полный кадр | `lblFrameFull` грузит кадр с наложенными прямоугольниками лиц |
| **ПКМ по кадру** | `Edit frame faces` → `frame-faces-edit-view.fxml` |

### Вкладка Frames

| Действие | Как |
|---|---|
| Листать кадры внутри плана | `Ctrl` + колесо мыши над `paneFrames` или `lblFrameFull` — шаг ±1 кадр |
| Листать страницы | колесо мыши без `Ctrl` над `paneFrames` — шаг ±1 страница |
| Перейти на страницу | ЛКМ по строке `tblPagesFrames` (обработчик на строке 791) |
| Выбрать кадр | ЛКМ по миниатюре: снимает выделение в `tblShots`, выбирает содержащий план, ставит красную рамку, грузит крупный кадр |
| Подсветка при наведении | жёлтая рамка + `toFront()`, миниатюра поднимается над соседями |

### Вкладка Persons

| Действие | Как |
|---|---|
| Выбрать персону | ЛКМ по строке `tblPersonsAllForFile` — перезагрузка `paneFaces` |
| Открыть персону | **двойной ЛКМ** по строке → `person-edit-view.fxml` (строка 1300) |
| Открыть персону плана | **двойной ЛКМ** по строке `tblPersonsAllForShot` → `person-edit-view.fxml` (строка 1378) |
| Выбрать лицо | ЛКМ по миниатюре; `Shift` — выделение диапазона от последней кликнутой |
| **ПКМ по миниатюре лица** | контекстное меню из 7 пунктов (см. ниже) |
| **Перетаскивание** | перетащить миниатюру лица на строку `tblPersonsAllForFile` — все перетащенные лица получают эту персону; если персоны ещё нет в списке файла, она добавляется. Формат обмена — строка `labelFace` в буфере обмена (`ShotsEditFXController.kt:1315, 1323, 2473`) |
| Фильтр лиц | `rbFaceAll`/`rbFaceFile` и четыре чекбокса типов |
| Создать лицо вручную | ПКМ по кадру → `Edit frame faces` → нарисовать прямоугольник → персону |
| Листать страницы лиц | колесо мыши над `paneFaces` |

Контекстное меню миниатюры лица (строки 2141–2424):

| Пункт | Действие |
|---|---|
| `UNDEFINDED` | все выделенные лица → персона типа `UNDEFINDED`; `face.personRecognizedName = ""` |
| `NONPERSON` | все выделенные лица → персона типа `NONPERSON` |
| `EXTRAS` | все выделенные лица → персона типа `EXTRAS` |
| `SELECT PERSON` | открыть `person-select-view.fxml` |
| `CREATE NEW PERSON` | диалог `Create new person` (header `Enter person name:`, content `Name:`), создать персону и сразу открыть `person-edit-view.fxml` |
| `Set as person picture` | записать в персону `fileIdForPreview` / `frameNumberForPreview` / `faceNumberForPreview`, удалить старые `small`/`medium`-превью |
| `Set as EXAMPLE` | пометить лицо эталоном (пункты 5–7 доступны не всем выделениям) |
| `Remove from EXAMPLE` | снять отметку эталона |

### Вкладки Scenes и Events

| Действие | Как |
|---|---|
| Создать сцену | выделить **непрерывный** набор планов в `tblShots` → `Create new scene by selected shots` → диалог `Rename scene` (header `Enter new scene name:`) |
| Создать событие | `Create new event by selected shots` → диалог `Rename event` |
| Удалить события | `Delete selected events` — удаляет все выделенные, пересчитывает превью затронутых планов |
| Удалить сцены | `Delete selected scenes` — **не работает**, см. дефект 1 |
| Свойства | шесть кнопок + `fld*PropertyKey` / `fld*PropertyValue`, сохранение по потере фокуса |

### Клавиатура (устанавливается в `onStart()`, строки 439–455)

| Клавиша | Действие | Работает ли |
|---|---|---|
| `CONTROL` | взводит `isPressedControl` для мультивыбора лиц | да |
| `SHIFT` | взводит `isPressedShift` для выделения диапазона лиц | да |
| `Z` | взводит `isPressedPlayBackward` | **нет** |
| `X` | взводит `isPressedPlayForward` | **нет** |

## Данные

14 таблиц. Колонки `FROM` и `TO` в `tblShots`, `tblShotsForScenes`,
`tblShotsForEvents` заполняются **не текстом, а картинкой**: `cellValueFactory`
ссылается на `labelFirst1` / `labelLast1` (планы), `labelFirst2` / `labelLast2`
(сцены), `labelFirst3` / `labelLast3` (события) — это `Label` с миниатюрой
кадра и подписью времени. Три отдельные копии на одну сущность сделаны
намеренно, чтобы JavaFX-нода не переезжала между таблицами.

`colShotType` ссылается на `labelType` (пиктограмма типа), `colButtonGetType` —
на `buttonGetType` (сама кнопка). Колонки `Name` персон ссылаются на
`labelSmall` (фото персоны 135×75 с подчёркнутым именем). Колонки `NAME` сцен
и событий ссылаются на `sceneNameLabel` / `eventNameLabel` — это уже текст.

Placeholder-состояния задаются в коде: `tblShots`, `tblPagesFrames`,
`tblScenes`, `tblEvents`, `tblShotsForScenes`, `tblPersonsAllForScenes` при
загрузке показывают `ProgressIndicator(-1.0)`, а после — тексты вида
«Shot not selected or don't have any persons.».

## Дефекты

1. **`Delete selected scenes` не работает.** Обработчик
   `doDeleteSelectedScenes` (`ShotsEditFXController.kt:2747`) — пустое тело.
   Кнопка есть, действие отсутствует. У событий аналогичная кнопка
   `Delete selected events` реализована (строка 2752) и удаляет все выделенные.
2. **`Create Event Based Scene` создаёт событие, а не сцену.** Обработчик
   `doCreateEventBasedScene` (строка 3155) вызывает
   `EventController.createEventExt(currentSceneExt)`. Название кнопки вводит в
   заблуждение.
3. **Два fx:id на вкладке Events не совпадают с полями контроллера.** В FXML
   индикаторы названы `pbShotsForScenes1` (строка 501) и
   `pbPersonsForScenes1` (строка 511), а контроллер объявляет поля
   `pbShotsForEvents` (строка 372) и `pbPersonsForEvents` (строка 381).
   Инжектится поле с именем из FXML, поле контроллера остаётся `null` —
   прогресс на вкладке Events не отображается.
4. **Клавиатурный видеоплеер — задел.** `Z` и `X` только взводят свойства
   `isPressedPlayBackward` / `isPressedPlayForward`, которые **нигде не
   читаются**. То же для `isPlayingForward` (строка 435) и `isWorking`
   (строка 432): присваиваются в 592, 594, 653, 1704 и больше нигде не
   используются.
5. **Английская заглушка в UI:** `lblPb` имеет `text="Label"` (строка 523).
6. **Смешение языков:** подписи вкладок (`Frames`, `Persons`, `Scenes`,
   `Events`), блоков (`Shot properties`, `Scene properties`, `Event
   properties`), кнопок (`Create new scene by selected shots`, `Delete
   selected scenes`, `Create Event Based Scene`, `Create new event by selected
   shots`, `Delete selected events`) и фильтров (`All`, `File`, `Not
   example`, `Example`, `Not manual`, `Manual`, `Name`, `Type`, `M`) —
   английские. Русские есть только в подписях колонок `tblPagesFrames`
   (`Время: с`, `Время: по`, `Кадры: с`, `Кадры: по`).
7. **Тултипы шести кнопок свойств** плана, сцены и события скопированы из
   шаблона списка файлов: «Добавить файл» и «Удалить файл из проекта».
8. **Внедрение во внутренности JavaFX.** Для «умного скролла» контроллер
   импортирует `com.sun.javafx.scene.control.skin.TableViewSkin` и
   `com.sun.javafx.scene.control.skin.VirtualFlow` (строки 3–4) и достаёт
   `children[1]` из скина таблицы (например, строки 784–787). Это внутреннее
   API: на стандартном JavaFX такой доступ закрыт, сборка требует
   Oracle JDK/OpenJFX. При переносе в браузер механику надо заменить, а не
   воспроизводить.
9. **Временная нода `ContextMenu` в FXML не используется.** В FXML объявлен
   `<ContextMenu fx:id="contextMenuFrameFull" />` внутри `lblFrameFull`
   (строки 175–177), но код создаёт новый `ContextMenu` с единственным пунктом
   `Edit frame faces` и присваивает `lblFrameFull.contextMenu`
   (строки 1444–1455). Объявление в FXML — мёртвое.
10. **Мелочь:** `paneMain`-подобного `fx:id` у корневого `HBox` нет, хотя у
    SplitPane в `project-edit-view.fxml` он есть; переносчику придётся решать
    самому, нужна ли ссылка на корневой контейнер.

---

# 5. frame-faces-edit-view.fxml — правка лиц в кадре

**Назначение.** Диалог одного кадра: показать все найденные лица в кадре,
создать новое лицо обводкой прямоугольника мышью.

**Сценарий.** Шаг 5г пользовательского пути: ручное создание лица. Открывается
из `shots-edit-view.fxml` по пункту контекстного меню `Edit frame faces`.

**Размер.** `prefWidth=2200`, `prefHeight=1200`. Модальность
`APPLICATION_MODAL` — блокирует всё приложение. Заголовок не задан.
Точка входа — `companion object fun editFrame(frameExt: FrameExt)`
(`FrameFacesEditFXController.kt:65`), то есть **статический метод**, а не
экземпляр контроллера.

## Дерево верхнего уровня

```
AnchorPane
└── VBox spacing=5, anchors 0/0/0/0
    ├── HBox prefHeight=100, prefWidth=200
    │   ├── VBox
    │   │   ├── TableView fx:id="tblFaces" prefWidth=215, prefHeight=10000
    │   │   └── Button fx:id="btnCreateNewFace" text="Create new face"
    │   └── Label fx:id="lblFrame" 1920 × 1080, чёрный фон
    └── Button fx:id="btnOk" text="ОК"
```

## Элементы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblFaces` | TableView | лица в кадре | minWidth=240, prefWidth=215, prefHeight=10000 |
| `colFace` | TableColumn `Face` | **само лицо** 75×75 | 75 |
| `colPerson` | TableColumn `Person` | **фото персоны**, не текст | 135 |
| `colIsManual` | TableColumn `M` | отметка «создано вручную»: значение `isManualText` — строка со знаком галочки (U+2713), если `face.isManual`, иначе пусто | 20 |
| `btnCreateNewFace` | Button | `Create new face` | maxWidth=∞ |
| `lblFrame` | Label | полный кадр 1920×1080 с прямоугольниками лиц | 1920 × 1080, чёрный фон, `alignment` задаётся в коде как CENTER |
| `btnOk` | Button | `ОК` | maxWidth=∞ |

Свойства колонок задаются в коде
(`FrameFacesEditFXController.kt:109–111`): `colFace` → `labelSmall`,
`colPerson` → `labelPersonSmall`, `colIsManual` → `isManualText`.

## Действия оператора

* **ЛКМ по строке `tblFaces`** — перерисовывает `lblFrame` с выделенным лицом
  (слушатель `selectedItemProperty`, строка 117).
* **Обводка мышью** — основной способ создать лицо:
  * `onMousePressed` — запоминает `startX`/`startY` и заново строит оверлей кадра;
  * `onMouseDragged` — рисует синий (`Color.BLUE`) прямоугольник через
    `drawRect`;
  * `onMouseReleased` — **автоматически** вызывает `onCreateNewFace(null)`
    (строки 133–139).
* `btnCreateNewFace` — тот же вызов, но вручную.
* `onCreateNewFace` открывает `person-select-view.fxml`; если персона выбрана,
  создаёт `Face` с `isManual = true`, `faceNumberInFrame = list.size + 1`,
  сохраняет и перегенерирует превью 75×75.
* `btnOk` — закрыть окно.
* Перетаскивания, двойных кликов и клавиатуры на форме нет.

## Данные

Одна таблица на три колонки: миниатюра лица, миниатюра персоны, признак
ручного создания.

## Дефекты

1. **Клик без перетаскивания создаёт вырожденное лицо.** `onMouseReleased`
   вызывает `onCreateNewFace` безусловно, без проверки, был ли Dragging. При
   простом клике `startX`/`startY` берутся из `onMousePressed`, а `endX`/`endY`
   остаются равными `0` (начальные значения, строки 87–90). В базу уходит
   прямоугольник от `(startX, startY)` до `(0, 0)`. Оператору достаточно
   случайно кликнуть по кадру, чтобы получить такое лицо. **Этот баг не
   переносить** — в браузере прямоугольник должен фиксироваться по завершении
   drag с проверкой минимального размера.
2. **Английская кнопка в русском UI:** `Create new face` (в форме
   `btnOk` подписан по-русски `ОК`).
3. **Окно без заголовка и без «Отмена»:** закрыть можно только `ОК` или
   крестиком окна; `onCreateNewFace` уже сохранила лицо в базу, отмены нет.
4. **Размер окна 2200×1200 жёстко задан и включает кадр 1920×1080.** На экране
   меньшего размера окно не поместится целиком.
5. Мелочь: метод `deepCopy` объявлен без модификатора `private` и не имеет
   `@FXML`; на загрузку формы не влияет.

---

# 6. person-select-view.fxml — выбор персоны

**Назначение.** Узкий диалог поиска и выбора персоны из проекта.

**Сценарий.** Вызывается из трёх мест: `CREATE NEW PERSON` в контекстном меню
лица, `SELECT PERSON` там же, `Create new face` в
`frame-faces-edit-view.fxml`, `Select Person` в
`filter-condition-create-view.fxml`.

**Размер.** `prefWidth=210`, `prefHeight=1000`. Модальность
`APPLICATION_MODAL`. Заголовок не задан.

## Дерево верхнего уровня

```
AnchorPane
└── VBox padding 5, anchors 0/0/0/0
    ├── TextField fx:id="fldFind"
    ├── HBox
    │   ├── TableView fx:id="tblPersons" prefWidth=150, prefHeight=10000
    │   └── VBox minWidth=46
    │       ├── Button fx:id="btnPersonAdd"  «плюс» (U+2795)
    │       └── Button fx:id="btnPersonDelete" «крестик» (U+2716)
    ├── Button fx:id="btnOk" text="OK"
    └── Button fx:id="btnCancel" text="Отмена"
```

## Элементы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `fldFind` | TextField | живой поиск по имени | по умолчанию, нижний отступ 5 |
| `tblPersons` | TableView | персоны проекта | prefWidth=150, prefHeight=10000 |
| `colPersonName` | TableColumn `PERSON` | **фото персоны 135×75 с подчёркнутым именем** | 135 |
| `btnPersonAdd` | Button | «плюс», тултип «Добавить файл» | 46 × 46, Arial 18 |
| `btnPersonDelete` | Button | «крестик», тултип «Удалить файл из проекта» | 46 × 46 |
| `btnOk` | Button | `OK` | maxWidth=∞ |
| `btnCancel` | Button | `Отмена` | maxWidth=∞ |

## Действия оператора

| Действие | Как |
|---|---|
| **Поиск** | ввод в `fldFind` — фильтрация на лету, сравнение `person.name.lowercase().contains(filter)`, автовыбор первого результата (`onKeyReleased`, строка 151) |
| Выбрать персону | ЛКМ по строке |
| Подтвердить | `btnOk`; добавляет персону в список недавних `listLastSelectedPersons` (максимум 11) |
| Отмена | `btnCancel` — возвращает `null` |
| **Двойной клик** | по строке `tblPersons` — работает как OK (строка 136) |
| **Создать персону** | «плюс» → `PersonController.create(project)` → сразу открывается `person-edit-view.fxml` → перезагрузка списка (`doPersonAdd`, строка 217) |
| Удалить персону | «крестик» — **не работает**, см. дефекты |
| Клавиатура | Enter в `fldFind` → фокус на таблицу и выбор первой строки; Enter в таблице → фокус на `btnOk`; Enter на `btnOk` → подтверждение (строки 175–200) |

## Данные

Одна колонка `PERSON` — это картинка, а не текст: `cellValueFactory` ссылается
на `labelSmall` персоны, то есть фото 135×75 с именем, подчёркнутым поверх
изображения.

## Дефекты

1. **Удаление персоны не работает.** `doPersonDelete`
   (`PersonSelectFXController.kt:223`) — пустое тело. Кнопка «крестик» ничего не
   делает.
2. **Тултипы кнопок персон скопированы из списка файлов:** «Добавить файл» и
   «Удалить файл из проекта» (`person-select-view.fxml:43, 51`).
3. **У поля поиска нет подсказки:** в FXML нет `promptText`, поле выглядит
   пустым; что искать, оператор должен догадаться.
4. **Подпись колонки `PERSON` — английская** в форме, где кнопки подписаны
   по-русски.
5. **Окно шириной 210 px** — при фото персоны 135 px плюс панель кнопок 46 px
   остаётся 24 px на рамку. Список персон показывает по одной колонке, имя
   поверх картинки.
6. Мелочь реализации: `doPersonAdd` (строка 217) создаёт персону **без**
   `hostServices`, поэтому открытие ссылок `url_*` из формы персоны внутри этого
   диалога не работает.

---

# 7. person-edit-view.fxml — карточка персоны

**Назначение.** Редактирование одной персоны: имя, крупное фото, произвольные
свойства.

**Сценарий.** Шаг 5ж пользовательского пути. Открывается из пункта меню
`Actions ▸ Edit persons...` (с `currentPersonExt = null` — в режиме списка),
двойным кликом по персоне в `tblPersonsAllForFile` или `tblPersonsAllForShot`,
а также после создания персоны.

**Размер.** `prefWidth=730`, `prefHeight=900`. **Внутреннее противоречие в
FXML:** `maxWidth=730` при `minWidth=950` — минимальная ширина сильнее
максимальной, фактическая ширина окна будет 950. Модальность `WINDOW_MODAL`.
Заголовок не задан.

## Дерево верхнего уровня

```
AnchorPane padding 5
└── HBox anchors 0/0/0/0
    ├── VBox padding 5 — ЛЕВАЯ ЧАСТЬ: список персон
    │   ├── TextField fx:id="fldFind"
    │   └── HBox
    │       ├── TableView fx:id="tblPersons" minWidth=155, prefHeight=10000
    │       └── VBox minWidth=46 — «плюс» и «крестик»
    └── VBox prefWidth=100, spacing=5 — ПРАВАЯ ЧАСТЬ: карточка
        ├── Label fx:id="lblMediumPreview" 720 × 400
        ├── HBox: Label «Name:» + TextField fx:id="fldName"
        ├── VBox: Label «Properties» + HBox(tblProperties + 6 кнопок) + fldPropertyKey + fldPropertyValue
        └── Button fx:id="btnOk" text="OK"
```

## Элементы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `fldFind` | TextField | поиск персоны | нижний отступ 5 |
| `tblPersons` | TableView | список персон проекта | minWidth=155, prefHeight=10000 |
| `colPersonName` | TableColumn `PERSON` | фото персоны | 135 |
| `btnPersonAdd` | Button | «плюс», тултип «Добавить файл» | 46 × 46 |
| `btnPersonDelete` | Button | «крестик», тултип «Удалить файл из проекта» | 46 × 46 |
| `lblMediumPreview` | Label | крупное фото персоны | 720 × 400, `contentDisplay="GRAPHIC_ONLY"`, чёрный фон, `textFill="#ddff00"` |
| `fldName` | TextField | **редактируемое имя персоны** | prefWidth=10000 |
| `tblProperties` | TableView | свойства персоны | minWidth=350, minHeight=300, prefWidth=10000 |
| `colPropertyKey` | TableColumn `Key` | | maxWidth=135, minWidth=300 |
| `colPropertyValue` | TableColumn `Value` | | maxWidth=200, minWidth=350 |
| `fldPropertyKey` | TextField | ключ свойства | по умолчанию |
| `fldPropertyValue` | TextArea | значение свойства | minHeight=60, prefHeight=200 |
| `btnPropertyMoveToFirst` | Button `⟰` | «В начало списка» | 46 × 46 |
| `btnPropertyMoveUp` | Button `⇧` | «На один уровень вверх» | 46 × 46, Arial 24 |
| `btnPropertyMoveDown` | Button `⇩` | «На один уровень вниз» | 46 × 46, Arial 24 |
| `btnPropertyMoveToLast` | Button `⟱` | «В конец списка» | 46 × 46 |
| `btnPropertyAdd` | Button | «плюс», тултип «Добавить файл» | 46 × 46 |
| `btnPropertyDelete` | Button | «крестик», тултип «Удалить файл из проекта» | 46 × 46 |
| `btnOk` | Button | `OK` | maxWidth=∞ |

## Действия оператора

* Поиск по `fldFind` (живой, `onKeyReleased`, строка 228; Enter — строка 252).
* ЛКМ по строке `tblPersons` — выбор персоны; обработчик сначала сохраняет
  текущие свойства и текущую персону, затем перезагружает свойства.
* Правка `fldName` — имя сохраняется по `doOk()` и при закрытии окна.
* Правка `fldPropertyKey` / `fldPropertyValue` — сохранение по потере фокуса.
* Шесть кнопок свойств: порядок и добавление/удаление. «Плюс» открывает
  `ContextMenu` (Alert-подтверждение с текстом «Имя и значение свойства будут
  сгенерированы автоматически.», заголовок «Добавление свойства персонажа»).
* **Двойной клик по строке `tblProperties`** — если ключ начинается с `url_`,
  открывает ссылку через `hostServices.showDocument` (строка 265).
* `btnOk` — сохраняет персону и закрывает.
* «Плюс» и «крестик» у списка персон — см. дефекты.
* Перетаскивания нет.

## Данные

Две таблицы: список персон (1 колонка, картинка) и свойства персоны (2 колонки,
Key/Value). Крупное фото — `Label` с `GRAPHIC_ONLY`.

## Дефекты

1. **Добавление персоны не работает.** `doPersonAdd`
   (`PersonEditFXController.kt:537`) — пустое тело. В отличие от
   `person-select-view.fxml`, где тот же «плюс» создаёт персону.
2. **Удаление персоны не работает.** `doPersonDelete`
   (`PersonEditFXController.kt:533`) — пустое тело.
3. **Противоречивые ограничения размера в FXML:** `maxWidth=730` и
   `minWidth=950` одновременно (строка 17). Фактическая ширина — 950 px, то
   есть заявленный `prefWidth` не действует.
4. **Колонка Key уже своей колонки Value:** у `colPropertyKey`
   `maxWidth=135` при `minWidth=300` (строка 85) — то же противоречие внутри
   колонки; `minWidth` сильнее, колонка не сжимается до 135 px.
5. **Мёртвые поля в контроллере:** `btnTagAdd` (строка 112) и `btnTagDelete`
   (строка 115) объявлены с аннотацией `@FXML`, но **в FXML отсутствуют**.
   Инжектится `null`; кода тегов в форме нет. Не переносить.
6. **Тултипы** «Добавить файл» и «Удалить файл из проекта» на кнопках персон и
   свойств скопированы из списка файлов.
7. **Английская подпись колонки `PERSON`** в русской форме.

---

# 8. filter-edit-view.fxml — редактор фильтров

**Назначение.** Построение условий отбора планов и экспорт результата в видео.

**Сценарий.** Шаг 6 пользовательского пути. Открывается из
`Actions ▸ Edit filters...`.

**Размер.** `prefWidth=1920`, `prefHeight=1080`. Модальность `WINDOW_MODAL`.
Заголовок не задан.

## Дерево верхнего уровня

```
AnchorPane
└── VBox anchors 0/0/0/0
    ├── HBox
    │   ├── VBox prefWidth=800 — ЛЕВАЯ ЧАСТЬ, три уровня вложенности
    │   │   ├── HBox
    │   │   │   ├── VBox: tblFilters + fldFilterName + rbFilterIsAnd/rbFilterIsOr
    │   │   │   └── VBox prefWidth=46 — 6 кнопок фильтра
    │   │   └── HBox
    │   │       ├── VBox: tblFiltersGroups + fldFilterGroupName + rbFilterGroupIsAnd/Or
    │   │       ├── VBox: tblFiltersConditions
    │   │       └── VBox prefWidth=46 — 6 кнопок условия
    │   ├── TableView fx:id="tblFiles" minWidth=250, prefWidth=200
    │   ├── Button fx:id="btnFilter" text=">>"
    │   ├── TableView fx:id="tblShots" minWidth=440, prefWidth=440, prefHeight=5000
    │   └── VBox: btnCreateVideo, btnCreateVideoForAllPersons
    ├── ProgressBar fx:id="pb" prefWidth=200
    └── Label fx:id="lblPb" text="Label"
```

Левая часть — 800 px, под ней три уровня: фильтры (таблица и поле идёт в
потолке), группы и условия стоят **в один ряд** во втором `HBox` — группы
слева, условия справа.

## Уровень 1: фильтры

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblFilters` | TableView | фильтры проекта | prefHeight=10000 |
| `colFilterOrder` | TableColumn `#` | порядок | maxWidth=40, prefWidth=40 |
| `colFilterName` | TableColumn `Filter` | имя фильтра | 660 |
| `colFilterIsAnd` | TableColumn `&amp;\|` | признак AND между группами | maxWidth=20, minWidth=30, prefWidth=30 |
| `fldFilterName` | TextField | имя, живое сохранение | prefWidth=10000 |
| `rbFilterIsAnd` | RadioButton | `AND`, группа `tgFilter` | minWidth=50 |
| `rbFilterIsOr` | RadioButton | `OR`, та же группа | minWidth=50 |
| `btnFilterMoveToFirst` / `MoveUp` / `MoveDown` / `MoveToLast` | Button `⟰ ⇧ ⇩ ⟱` | порядок фильтров | 46 × 46 |
| `btnFilterAdd` | Button | «плюс» (U+2795), тултип «Добавить файл» | 46 × 46 |
| `btnFilterDelete` | Button | «крестик» (U+2716) | 46 × 46 |

Между кнопкой `MoveToLast` и «плюсом» стоит `Separator`.

## Уровень 2: группы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblFiltersGroups` | TableView | группы выбранного фильтра | prefHeight=10000 |
| `colFilterGroupOrder` | TableColumn `#` | порядок | 40 |
| `colFilterGroupName` | TableColumn `Group` | имя группы | 260 |
| `colFilterGroupIsAnd` | TableColumn `&amp;\|` | признак AND между условиями | 30 |
| `fldFilterGroupName` | TextField | имя группы, живое сохранение | prefWidth=10000 |
| `rbFilterGroupIsAnd` / `rbFilterGroupIsOr` | RadioButton | `AND` / `OR`, группа `tgFilterGroup` | minWidth=50 |
| `btnFilterGroupMoveToFirst` / `MoveUp` / `MoveDown` / `MoveToLast` | Button | порядок групп | 46 × 46 |
| `btnFilterGroupAdd` | Button | «плюс» | 46 × 46 |
| `btnFilterGroupDelete` | Button | «крестик» | 46 × 46 |

## Уровень 3: условия

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblFiltersConditions` | TableView | условия выбранной группы | prefHeight=10000 |
| `colFilterConditionOrder` | TableColumn `#` | порядок | 40 |
| `colFilterConditionName` | TableColumn `Condition` | живая формулировка условия | 290 |
| `btnFilterConditionMoveToFirst` / `MoveUp` / `MoveDown` / `MoveToLast` | Button | порядок условий | 46 × 46 |
| `btnFilterConditionAdd` | Button | «плюс» → `filter-condition-create-view.fxml` | 46 × 46 |
| `btnFilterConditionDelete` | Button | «крестик» | 46 × 46 |

## Правая часть: применение фильтра и экспорт

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblFiles` | TableView | файлы проекта, `SelectionMode.MULTIPLE` (строка 273) | minWidth=250, prefWidth=200, prefHeight=10000 |
| `colFileOrder` | TableColumn `#` | порядок | 40 |
| `colFileName` | TableColumn `Файл` | имя | 190 |
| `btnFilter` | Button | `>>` — применить фильтр | maxWidth/Height=∞ |
| `tblShots` | TableView | результат отбора | minWidth=440, prefWidth=440, prefHeight=5000, maxWidth=440 |
| `colShotFileName` | TableColumn `FILE` | файл плана | 135 |
| `colShotFrom` | TableColumn `FROM` | **миниатюра первого кадра** | 135 |
| `colShotTo` | TableColumn `TO` | **миниатюра последнего кадра** | 135 |
| `btnCreateVideo` | Button | `Create Video File` | maxWidth=∞ |
| `btnCreateVideoForAllPersons` | Button | `Create Video File for all ended persons` | maxWidth=∞ |
| `pb` | ProgressBar | | prefWidth=200 |
| `lblPb` | Label | `text="Label"` | maxWidth=∞ |

## Действия оператора

1. «Плюс» у фильтров → создать фильтр.
2. Правка `fldFilterName` — живое сохранение (слушатель `textProperty`).
3. `AND` / `OR` на уровне фильтра — как объединяются **группы** внутри фильтра.
4. Выбрать фильтр → «плюс» у групп → создать группу.
5. Правка `fldFilterGroupName`; `AND` / `OR` — как объединяются **условия**
   внутри группы.
6. Выбрать группу → «плюс» у условий → `filter-condition-create-view.fxml`.
7. **Двойной клик по строке `tblFiltersConditions`** (`FilterEditFXController.kt:375`)
   — открывает ту же форму условия.
8. Выбрать файлы в `tblFiles` (мультивыбор) → `btnFilter` `>>` → вычисляется
   `shotsIds()` → результат в `tblShots`.
9. `btnCreateVideo` — собрать видеофайл отобранных планов.
10. `btnCreateVideoForAllPersons` — для каждой персоны с property `end` создать
    или переиспользовать фильтр `AllEventsPerson`; если файл уже существует,
    операция пропускается.
11. Порядок и удаление на всех трёх уровнях.
12. Перетаскивания и контекстных меню, кроме `ContextMenu` на кнопке
    добавления свойства (в этой форме свойств нет), нет.

## Данные

Пять таблиц, 13 столбцов. `colShotFrom` и `colShotTo` заполняются
`labelFirst1` / `labelLast1` — то есть картинками, как и в
`shots-edit-view.fxml`. Колонки Name всех трёх уровней имеют `cellFactory` с
переносом по словам.

## Дефекты

1. **Редактирование условия невозможно, хотя код для него написан.** Метод
   `createFilterCondition(projectExt, filterGroupExt, initFilterConditionExt = null)`
   (`FilterConditionCreateFXController.kt:106`) умеет открывать форму в режиме
   «Edit filter condition»: `initialize()` при `initFilterConditionExt != null`
   восстанавливает состояние и меняет заголовок. Но **оба вызова передают
   только два аргумента** — `FilterEditFXController.kt:379` (двойной клик) и
   `:461` (кнопка «плюс»). Третий аргумент всегда `null`, режим редактирования
   недостижим. Двойной клик по существующему условию открывает **пустую форму
   создания**, а не правку. При переносе: либо реализовать редактирование, либо
   убрать двойной клик, чтобы не вводить в заблуждение.
2. **Тултипы всех 18 кнопок «плюс» и «крестик»** скопированы из списка файлов:
   «Добавить файл» и «Удалить файл из проекта».
3. **Английская заглушка** `lblPb` имеет `text="Label"` (строка 309).
4. **Весь интерфейс английский**: `Filter`, `Group`, `Condition`, `AND`, `OR`,
   `File`, `Create Video File`, `Create Video File for all ended persons`.
   Опечатка в подписи колонки: `&amp;|` — символ `|` в XML требует экранирования
   только в атрибутах, здесь он экранирован верно, но сама подпись нечитаема.
5. **Ширина колонки `&|` противоречива:** `maxWidth=20` при `minWidth=30` и
   `prefWidth=30` — минимальная ширина сильнее максимальной, колонка не
   сжимается до 20 px.
6. Мелочь реализации: FXML грузится через
   `ShotsEditFXController::class.java.getResource(...)`
   (`FilterEditFXController.kt:232`) вместо собственного класса. Работает,
   потому что все контроллеры в одном пакете.

---

# 9. filter-condition-create-view.fxml — создание условия

**Назначение.** Диалог одного условия фильтра: что искать, включать или
исключать, в чём.

**Сценарий.** Шаг 6, вложен в `filter-edit-view.fxml`.

**Размер.** `prefWidth=200`, `prefHeight=340`. Модальность `WINDOW_MODAL`.
Заголовок не задан. Заголовок **внутри формы** — `lblHeader`:
`Create new filter condition` либо `Edit filter condition` (второй вариант
недостижим, см. дефект 1 в разделе 8).

## Дерево верхнего уровня

Плоский `VBox` из 18 элементов, три группы `ToggleGroup` и один `Separator`.

## Элементы

| fx:id | Тип | Текст | Состояние | Группа |
|---|---|---|---|---|
| `lblHeader` | Label | `Create new filter condition` | System Bold 12, alignment=CENTER | — |
| `rbPerson` | RadioButton | `Person` | **выбран** | `tgObject` |
| `rbPersonProperty` | RadioButton | `Person property` | | `tgObject` |
| `rbShotProperty` | RadioButton | `Shot property` | | `tgObject` |
| `rbSceneProperty` | RadioButton | `Scene property` | | `tgObject` |
| `rbEventProperty` | RadioButton | `Event property` | | `tgObject` |
| `btnSelectObject` | Button | `Select Person` | текст меняется по типу объекта | — |
| (без fx:id) | Separator | — | prefWidth=200 | — |
| `rbIsIncluded` | RadioButton | `is included` | **выбран** | `tgIncluded` |
| `rbIsNotIncluded` | RadioButton | `is NOT included` | | `tgIncluded` |
| (без fx:id) | Label | `in` | | — |
| `rbShot` | RadioButton | `Shot` | **выбран** | `tgSubjectClass` |
| `rbScene` | RadioButton | `Scene` | | `tgSubjectClass` |
| `rbEvent` | RadioButton | `Event` | | `tgSubjectClass` |
| `lblName` | Label | `NAME` | `textFill="RED"`, System Bold 12, `wrapText=true`, `minHeight=50` | — |
| `btnOk` | Button | `Create new filter condition` | текст дублирует `lblHeader` | — |
| `btnCancel` | Button | `Cancel` | | — |

## Действия оператора

| Действие | Обработчик | Следствие |
|---|---|---|
| Выбрать тип объекта | `rb*` → `#doChangeObjectClass` | сбрасывает выбранный объект; меняет текст `btnSelectObject`; при `Scene property` блокирует `rbShot` и выбирает `rbScene`; при `Event property` блокирует `rbScene` и выбирает `rbEvent` |
| Выбрать объект | `btnSelectObject` → `#doSelectObject` | для типа `Person` открывает `person-select-view.fxml`; для типов-свойств — выбор ключа и значения |
| Задать включённость | `rbIsIncluded` / `rbIsNotIncluded` → `#doChangeIsIncluded` | обновляет формулировку |
| Выбрать субъект | `rbShot` / `rbScene` / `rbEvent` → `#doChangeSubjectClass` | обновляет формулировку |
| Подтвердить | `btnOk` → `#doOk` | `btnOk` заблокирован, пока `currentObjectId == null` |
| Отмена | `btnCancel` → `#doCancel` | возвращает `null` |

Живая формулировка в `lblName` строится функцией `getCurrentName()`, например
`Person «Иван» is included in Shot` или
`Person property «Пол» = «мужской» is NOT included in Scene`.

## Данные

Таблиц нет. Форма целиком текстовая: три группы переключателей, одна кнопка
выбора объекта и строка-формулировка.

## Дефекты

1. **Кнопка `OK` дублирует заголовок окна** — и текст, и длина. Заголовок
   `Create new filter condition` на кнопке подтверждения вводит в заблуждение:
   кажется, что она тоже что-то создаёт.
2. **Кнопка отмены — `Cancel`**, английский, при русских подписях в других
   формах того же приложения.
3. **Место объекта не показывается, кроме текста кнопки.** После выбора персоны
   в форме нет отдельного поля, где видно, что выбрано: имя объекта появляется
   только в красной формулировке `lblName`. Выбранного ключа свойства в форме
   не видно вообще.
4. **Ширина 200 px обрезает длинные формулировки**; `lblName` имеет
   `wrapText=true` и `minHeight=50`, но ширина колонки текста ограничена
   окном.
5. В режиме редактирования (недостижимом) `initialize()` восстанавливает
   `objectClass` только для случая `Person`: `when(...)` содержит единственную
   ветку `Person::class.java.simpleName` (строки 147–151). Условия по свойствам
   в этом режиме восстановились бы неполно.
6. Мелочь реализации: FXML грузится через
   `ShotsEditFXController::class.java.getResource(...)`
   (`FilterConditionCreateFXController.kt:113`).

---

# 10. database-select-view.fxml — выбор базы данных

**Назначение.** Диалог выбора рабочей базы данных, её создание, правка и
удаление.

**Сценарий.** Пункт меню `Database ▸ Выбрать базу данных` в
`project-edit-view.fxml`. Также используется как альтернативная точка входа
(закомментированный `main()` в `H2db.kt`).

**Размер.** `prefWidth=300`, `prefHeight=400`. Модальность `WINDOW_MODAL`.
Заголовок «Выбор базы данных» (`DatabaseSelectFXController.kt:57`).

## Дерево верхнего уровня

```
AnchorPane
└── VBox padding 5, anchors 0/0/0/0
    ├── TableView fx:id="tblDatabases"
    ├── Button fx:id="btnSelectDb" text="OK"
    ├── Button fx:id="btnEditDb" text="Редактировать базу данных"
    ├── Button fx:id="btnCreateNewDb" text="Добавить новую базу данных"
    ├── Button fx:id="btnDeleteDb" text="Удалить выбранную базу данных"
    └── Button fx:id="btnCancel" text="Отмена"
```

## Элементы

| fx:id | Тип | Назначение | Размеры |
|---|---|---|---|
| `tblDatabases` | TableView | список баз | maxWidth/Height=∞ |
| `colDbName` | TableColumn `База данных` | имя базы | minWidth=275, maxWidth=∞ |
| `btnSelectDb` | Button | `OK` | maxWidth=∞, верхний отступ 5 |
| `btnEditDb` | Button | `Редактировать базу данных` → `database-edit-view.fxml` | maxWidth=∞ |
| `btnCreateNewDb` | Button | `Добавить новую базу данных` → `database-edit-view.fxml` | maxWidth=∞ |
| `btnDeleteDb` | Button | `Удалить выбранную базу данных` | maxWidth=∞ |
| `btnCancel` | Button | `Отмена` | maxWidth=∞ |

## Действия оператора

| Действие | Обработчик |
|---|---|
| **Двойной клик по строке** `tblDatabases` | закрывает окно, работает как OK (`DatabaseSelectFXController.kt:92–99`) |
| Подтвердить | `btnSelectDb` → `#doSelectDb` |
| Правка | `btnEditDb` → `#doEditDb` → `database-edit-view.fxml` с существующей базой |
| Создать | `btnCreateNewDb` → `#doCreateNewDb` → `database-edit-view.fxml` с пустой базой |
| Удалить | `btnDeleteDb` → `#doDeleteDb` |
| Отмена | `btnCancel` → `#doCancel`, возвращает `incomingDatabase` (откат) |

Перетаскивания, клавиатуры и контекстных меню нет.

## Данные

Одна таблица, одна колонка — имя базы данных. Стиль выделения красный.

## Дефекты и особенности

1. **Нет индикатора текущей БД.** В отличие от `project-select-view.fxml`, где
   есть `lblDb` с живым текстом «БД: <имя>», здесь оператор не видит, какая база
   активна, — только заголовок окна и запись в `h2db`.
2. **Удаление базы не спрашивает подтверждения в этой форме.** Обработчик
   `doDeleteDb` не открывает Alert; подтверждение есть только в
   `project-edit-view.fxml` для удаления проекта и файла. При переносе нужно
   добавить подтверждение.
3. **Смена базы требует перезапуска приложения.** Это делает не форма, а
   `ProjectEditFXController.doSelectDatabase()` (строка 1282): пишет
   `CURRENTDB_ID`, показывает Alert «Для вступления измженения в силу
   перезапустите приложение.» (опечатка «измженения» в тексте) и **закрывает
   главное окно**. Для веб-версии это ограничение снимается: переключение базы
   может происходить без перезапуска.
4. **Кнопка «Редактировать» доступна без выбранной строки** — блокировки в
   FXML нет.

---

# 11. database-edit-view.fxml — карточка базы данных

**Назначение.** Правка параметров подключения к базе: драйвер, URL, пользователь,
пароль.

**Сценарий.** Вложен в `database-select-view.fxml` (кнопки «Редактировать» и
«Добавить новую»).

**Размер.** `prefWidth=400`, `prefHeight=246`. Модальность `WINDOW_MODAL`.
Заголовок зависит от режима: «Редактирование базы данных» при
`h2database.id != null`, иначе «Добавление базы данных»
(`DatabaseEditFXController.kt:53`).

## Дерево верхнего уровня

```
AnchorPane
└── VBox prefWidth=400, padding 5, anchors 0/0/0/0
    ├── HBox: Label «ID:» minWidth=70 + TextField fx:id="fldId" prefWidth=10000
    ├── HBox: Label «Name:» minWidth=70 + TextField fx:id="fldName"
    ├── HBox: Label «Driver:» minWidth=70 + TextField fx:id="fldDriver"
    ├── HBox: Label «Url:» minWidth=70 + TextField fx:id="fldUrl"
    ├── HBox: Label «User:» minWidth=70 + TextField fx:id="fldUser"
    ├── HBox: Label «Password:» minWidth=70 + TextField fx:id="fldPassword"
    ├── Button fx:id="btlOk" text="OK"
    └── Button fx:id="btnCancel" text="Cancel"
```

Все подписи — `alignment=CENTER_RIGHT`, `contentDisplay="RIGHT"`,
`textAlignment="RIGHT"`, `minWidth=70`, `padding top=3`, правый отступ 5.

## Элементы

| fx:id | Тип | Подпись | Размеры | Особенность |
|---|---|---|---|---|
| `fldId` | TextField | `ID:` | prefWidth=10000 | **задизейблен в коде** (`DatabaseEditFXController.kt:72`) |
| `fldName` | TextField | `Name:` | prefWidth=10000 | верхний отступ 5 |
| `fldDriver` | TextField | `Driver:` | prefWidth=10000 | верхний отступ 5 |
| `fldUrl` | TextField | `Url:` | prefWidth=10000 | верхний отступ 5 |
| `fldUser` | TextField | `User:` | prefWidth=10000 | верхний отступ 5 |
| `fldPassword` | TextField | `Password:` | prefWidth=10000 | **обычный TextField, не PasswordField** |
| `btlOk` | Button | `OK` | maxWidth=∞ | опечатка в fx:id: `btlOk`, а не `btnOk` |
| `btnCancel` | Button | `Cancel` | maxWidth=∞ | |

## Действия оператора

| Действие | Обработчик |
|---|---|
| Подтвердить | `btlOk` → `#doOk` → `saveH2database()` |
| Отмена | `btnCancel` → `#doCancel` |
| Правка полей | обычный ввод; `fldId` недоступен для правки |

Ни двойных кликов, ни перетаскивания, ни контекстных меню нет.

## Данные

Таблиц нет — шесть текстовых полей. Это единственная форма проекта без
`TableView`.

## Дефекты

1. **Пароль в открытом виде.** `fldPassword` — обычный `TextField`, а не
   `PasswordField`: символы видны на экране и в буфере обмена. При переносе
   в браузер это правится тривиально (`type="password"`), но старый пароль
   всё равно лежит в открытом виде в служебной базе `h2db`.
2. **Опечатка в fx:id:** `btlOk` вместо `btnOk` (`database-edit-view.fxml:132`).
   В коде поле названо `btlOk` тоже, так что работает; при переносе имя стоит
   исправить.
3. **Кнопка отмены — `Cancel`**, английский, при русском заголовке окна.
4. **Подписи полей английские** (`Name:`, `Driver:`, `Url:`, `User:`,
   `Password:`) в форме с русским заголовком.
5. **Нет кнопки «Проверить подключение»:** ошибку в URL или драйвере оператор
   увидит только после перезапуска приложения (см. дефект 3 в разделе 10).

---

# Сквозные механики, которые надо сохранить при переносе

## 1. Единый механизм свойств «ключ-значение»

Одни и те же шесть кнопок (`⟰ ⇧ ⇩ ⟱` + «плюс» + «крестик») и пара
`TextField` (ключ) + `TextArea` (значение) повторяются в семи местах:

| Сущность | Форма | Таблица | Префикс кнопок |
|---|---|---|---|
| Свойства проекта | `project-edit-view.fxml` | `tblProjectProperties` | `btnProjectProperty*` |
| Свойства проекта (CDF) | `project-edit-view.fxml` | `tblProjectPropertiesCdf` | `btnProjectPropertyCdf*` |
| Свойства файла | `project-edit-view.fxml` | `tblFileProperties` | `btnFileProperty*` |
| Свойства файла (CDF) | `project-edit-view.fxml` | `tblFilePropertiesCdf` | `btnFilePropertyCdf*` |
| Свойства дорожки | `project-edit-view.fxml` | `tblTrackProperties` | кнопок нет, только просмотр |
| Свойства плана | `shots-edit-view.fxml` | `tblShotProperties` | `btnShotProperty*` |
| Свойства сцены | `shots-edit-view.fxml` | `tblSceneProperties` | `btnSceneProperty*` |
| Свойства события | `shots-edit-view.fxml` | `tblEventProperties` | `btnEventProperty*` |
| Свойства персоны | `person-edit-view.fxml` | `tblProperties` | `btnProperty*` |

При переносе это **один компонент** с параметром «сущность», а не девять
реализаций. Кнопка «плюс» везде открывает `ContextMenu` (не диалог) с
вариантами: создать автоименованное свойство, выбрать конкретный ключ из
предложенных, добавить весь набор ключей сразу.

## 2. Явной кнопки «Сохранить» нет нигде

Сохранение происходит по потере фокуса поля, при смене выделения и при закрытии
окна. Это сквозное поведение: при переносе его надо сделать явным (кнопка
сохранения или автосохранение с индикатором), иначе оператор потеряет данные,
не понимая почему.

## 3. Семантика цветов на миниатюрах

Это самая важная для переноса часть: оператор читает результат по цветам.

| Цвет | Что означает |
|---|---|
| красный | найдено алгоритмом (граница плана) |
| зелёный | сделано оператором (ручная граница, эталонное лицо) |
| оранжевый | отменено оператором |
| голубой | I-кадр |
| синий треугольник | в кадре есть лица |
| красный треугольник | лицо создано вручную |
| оранжевая полоса слева | план внутри сцены |
| зелёная полоса справа | план внутри события |

Рамки миниатюр: `#0f0f0f` обычная, `YELLOW` при наведении, `RED` выбранная,
`ORANGE` выбранная при наведении. Выделение строк во **всех** таблицах всех
форм задано как `-fx-selection-bar: red`.

## 4. Матрица — не таблица

`paneFrames` и `paneFaces` — это `Pane` без встроенной разметки, в который
контроллер вручную расставляет переиспользуемые `Label`:
`translateX = 10 + column × 137`, `translateY = 10 + row × 77` для кадров
(135 × 75), и квадрат 75 × 75 для лиц. Размер страницы вычисляется из
размеров панели, при изменении размеров окна матрица пересобирается, а
выбранный кадр и страница восстанавливаются. В браузере это естественно
ложится на CSS Grid или канвас, но логику «страниц» и «дубликатов на стыке»
нужно перенести.

## 5. Умный скролл между таблицами

Выделение строки в `tblShots`, `tblPagesFrames`, `tblScenes`, `tblEvents`
вызывает подкрутку связанных таблиц. Реализовано через рефлексию в
`TableViewSkin.children[1]` (`VirtualFlow`) и сравнение `firstVisibleCell` /
`lastVisibleCell` с `selectedIndex` — например, `tblShotsSmartScroll()`,
`tblPagesFramesSmartScroll()`. **Внутреннее API JavaFX, при переносе не
воспроизводится**: в вебе это обычная программная прокрутка к нужной строке.

## 6. Клавиатура, которая задумана, но не сделана

`Z` и `X` на `shots-edit-view.fxml` заведены как «playback», но свойства
`isPressedPlayBackward` / `isPressedPlayForward` нигде не читаются: видеоплеера
в приложении нет. `Ctrl`+колесо и колесо реализованы и работают (шаг кадра
внутри плана, шаг страницы). При переносе `Z`/`X` либо реализуют как
перемотку, либо не переносят.

---

# Что было сломано или не работало

Сводка. Всё перечисленное подтверждено чтением FXML и тел обработчиков.

| № | Что | Где | Следствие | Переносить ли |
|---|---|---|---|---|
| 1 | Пустое тело `doDeleteSelectedScenes` | `ShotsEditFXController.kt:2747` | кнопка `Delete selected scenes` ничего не делает | нет — реализовать удаление или убрать кнопку |
| 2 | Пустое тело `doPersonDelete` | `PersonSelectFXController.kt:223`, `PersonEditFXController.kt:533` | удаление персоны невозможно | нет — реализовать или убрать |
| 3 | Пустое тело `doPersonAdd` | `PersonEditFXController.kt:537` | «плюс» у списка персон в карточке персоны ничего не делает | нет |
| 4 | `btnTagAdd`, `btnTagDelete` объявлены с `@FXML`, но нет в FXML | `PersonEditFXController.kt:112, 115` | поля всегда `null`, кода тегов нет | нет |
| 5 | fx:id `pbShotsForScenes1`, `pbPersonsForScenes1` не совпадают с полями `pbShotsForEvents`, `pbPersonsForEvents` | `shots-edit-view.fxml:501, 511` | прогресс на вкладке Events не отображается | нет — naming должен совпадать |
| 6 | `createFilterCondition` третьим аргументом всегда получает `null` | `FilterEditFXController.kt:379, 461` | режим «Edit filter condition» недостижим; двойной клик по условию открывает пустую форму создания | нет — либо реализовать, либо убрать двойной клик |
| 7 | `onMouseReleased` безусловно вызывает `onCreateNewFace` | `FrameFacesEditFXController.kt:133-139` | простой клик по кадру создаёт вырожденное лицо с координатами до `(0, 0)` | нет — обязательна проверка drag и минимального размера |
| 8 | `Create Event Based Scene` создаёт событие | `ShotsEditFXController.kt:3155` | название кнопки вводит в заблуждение | нет — переименовать или убрать |
| 9 | Клавиши `Z` и `X` только взводят нечитаемые свойства | `ShotsEditFXController.kt:432-437, 444-452` | видеоплеера нет; `isPlayingForward`, `isWorking` тоже мертвы | нет |
| 10 | Импорт `com.sun.javafx.scene.control.skin.TableViewSkin` / `VirtualFlow` | `ShotsEditFXController.kt:3-4, 784-787` и далее | внутреннее API, требует Oracle JDK/OpenJFX | нет — заменить на свою прокрутку |
| 11 | `fldPassword` — обычный `TextField` | `database-edit-view.fxml:122` | пароль виден на экране | нет — сделать `type="password"` |
| 12 | Удаление базы без подтверждения | `DatabaseSelectFXController.doDeleteDb` | риск потерять базу | нет — добавить подтверждение |
| 13 | `onDeleteSelectedEvents` удаляет без Alert, в отличие от удаления сцены | `ShotsEditFXController.kt:2752` | непоследовательность | решить при переносе |
| 14 | Тултипы «Добавить файл» / «Удалить файл из проекта» на кнопках свойств и персон | почти все FXML: `project-edit-view.fxml:265, 274, 357, 366, 610, 619, 692, 701`; `person-select-view.fxml:43, 51`; `person-edit-view.fxml:48, 56, 140, 148`; `filter-edit-view.fxml:102, 110, 195, 203, 269, 277`; `shots-edit-view.fxml:109, 117, 339, 347, 468, 476` | подсказка врёт | нет — переписать |
| 15 | Противоречивые размеры: `maxWidth=730` при `minWidth=950` | `person-edit-view.fxml:17` | заявленная ширина 730 не действует, фактическая 950 | нет |
| 16 | `maxWidth=135` при `minWidth=300` у `colPropertyKey` | `person-edit-view.fxml:85` | колонка не сжимается | нет |
| 17 | `maxWidth=20` при `minWidth=30` у колонок `&\|` | `filter-edit-view.fxml:38, 131` | колонка не сжимается | нет |
| 18 | Опечатка `Frequncy:` | `project-edit-view.fxml:162` | неверная подпись | нет — `Frequency` |
| 19 | Опечатка `Computer-Depened-Properties` | `project-edit-view.fxml:290, 635` | неверная подпись | нет |
| 20 | Опечатка `btlOk` вместо `btnOk` | `database-edit-view.fxml:132` | имя не соответствует шаблону | нет |
| 21 | `lblPbFiles`, `lblPb` с `text="Label"` | `project-edit-view.fxml:493`; `filter-edit-view.fxml:309`; `shots-edit-view.fxml:523` | английская заглушка в русском UI | нет |
| 22 | Подпись `DB` в FXML заменяется в коде | `project-select-view.fxml:21` | в разметке осталось `DB` вместо «БД» | нет |
| 23 | `ContextMenu` в FXML не используется, код создаёт новый | `shots-edit-view.fxml:175-177` против `:1444-1455` | мёртвая разметка | нет |
| 24 | Шесть окон без заголовка | `project-actions`, `shots-edit` (кроме `initialize`), `frame-faces-edit`, `person-select`, `person-edit`, `filter-edit`, `filter-condition-create` | окна не опознаются в списке задач | нет — задать заголовки |
| 25 | Отсутствие `promptText` у поля поиска | `person-select-view.fxml:21`, `person-edit-view.fxml:26` | поле выглядит пустым | нет — добавить подсказку |
| 26 | Смешение языков в одном экране | `shots-edit-view.fxml` (английские вкладки и кнопки, русские подписи колонок), `project-actions-view.fxml` (полностью английский), `filter-edit-view.fxml` (полностью английский), `project-edit-view.fxml` (английские поля, русские тултипы) | оператор переключает язык мысленно | нет — один язык |
| 27 | Длинные тексты в кнопках чекбоксов: `- need LL!!!`, `- need SLA!!!` | `project-actions-view.fxml:60-63` | предупреждение в тексте, а не проверка | нет — вынести в валидацию |
| 28 | Три копии `labelFirst` / `labelLast` на одну сущность | `shots-edit-view.fxml:40, 41, 269, 270, 367, 368, 400, 401, 497, 498` | костыль ради JavaFX-нод | нет — в вебе можно один источник |
| 29 | Блокировка кнопок порядка в `project-select-view.fxml` только в коде | `ProjectSelectFXController` | в разметке не видно, что кнопки заблокированы | учитывать при переносе |
| 30 | Кнопка `Редактировать базу данных` доступна без выбранной строки | `database-select-view.fxml:27` | можно открыть карточку пустой базы | нет — блокировать |

---

# Сводная таблица: что переносим в первую очередь

Приоритет определён тремя критериями: входит ли экран в первый сквозной срез
(приём каталога, разметка, выборка, показ результата), сколько в нём
непереносимой механики, и насколько он велик.

| Приоритет | Форма | Почему в этой очереди | Что именно переносить в первом срезе | Оценка объёма |
|---|---|---|---|---|
| 1 | `project-edit-view.fxml` | без него нельзя принять ни фильм, ни файл; это единственная точка входа приложения | список файлов проекта, кнопки `⟰ ⇧ ⇩ ⟱`, добавление файла и файлов из папки, удаление файла, поля `fldProjectName` / `fldProjectShortName` / `fldProjectFolder` / W / H / FPS, таблица `tblTracks` с переключением `Use` | большая: 98 fx:id, 7 таблиц; переносить по частям, таблицы свойств — вторым шагом |
| 2 | `project-actions-view.fxml` | это пуск анализа; без него нечего размечать. Малая форма с самым большим отношением «польза / строк» | таблица `tblFilesExt` с 17 колонками-индикаторами, 15 чекбоксов операций, `checkReCreateIfExists`, `btnDoActions`, два индикатора прогресса. `btnTrainFaceModel` — позже, он отдельная история с Python | малая: 75 строк, 40 fx:id, одна таблица |
| 3 | `shots-edit-view.fxml` | ядро домена «разметка оператором»; без него ручная правка невозможна. Но самая большая форма — 533 строки | вкладка **Frames**: `paneFrames` как CSS Grid, `tblPagesFrames`, `lblFrameFull`, двойной клик по миниатюре (переключение границы), колесо и `Ctrl`+колесо. Затем `tblShots` с колонкой типа плана. Вкладки Persons, Scenes, Events — вторым-третьим шагом | очень большая: 100 fx:id, 14 таблиц, 4 вкладки. Только после 1 и 2 |
| 4 | `person-select-view.fxml` | нужен везде, где выбирают персону: правка лица, условие фильтра. Узкая, простая, легко переносится | `fldFind` с живым поиском, `tblPersons` с фото 135×75, `btnOk` / `btnCancel`, двойной клик как OK, кнопка создания персоны | малая: 71 строка, 7 fx:id |
| 5 | `frame-faces-edit-view.fxml` | ручное создание лица — операция, без которой разметка лиц неполна | `lblFrame` как канвас с оверлеями, рисование прямоугольника мышью, `tblFaces` (лицо / фото персоны / признак ручного), `btnCreateNewFace`, `btnOk` | малая: 37 строк; **но обязательно исправить дефект 7** |
| 6 | `person-edit-view.fxml` | карточка персоны: имя, фото, свойства | `lblMediumPreview` 720×400, `fldName`, `tblProperties` + `fldPropertyKey` / `fldPropertyValue` + 6 кнопок, `btnOk`. Список персон слева — во вторую очередь | средняя: 171 строка; **не переносить `btnTagAdd` / `btnTagDelete`** (дефект 4) |
| 7 | `filter-edit-view.fxml` | домен «фильтры и сценарий»; в первом срезе нужен только просмотр результата, полный редактор — позже | минимум: `tblFiles` + `btnFilter` `>>` + `tblShots` (просмотр того, что отобрал фильтр) | большая: 313 строк, три уровня вложенности; редактор условий — третьим шагом |
| 8 | `filter-condition-create-view.fxml` | вложен в предыдущую форму, отдельно в первый срез не нужен | переносить вместе с редактором условий; **не переносить недостижимый режим редактирования** (дефект 6) | малая: 56 строк |
| 9 | `project-select-view.fxml` | сценарий «открыть проект»; в вебе это скорее список на главной странице, отдельное окно не нужно | `tblProjects` с порядком, `OK` / `Отмена`, двойной клик. Форма отдельным окном, вероятно, не переносится вовсе | малая: 95 строк |
| 10 | `database-select-view.fxml` | в SYP база одна (Postgres 16 в контейнере `syp-db`), выбора баз нет | переносить **только если** в SYP появится несколько баз. В текущей архитектуре — не переносится | малая: 50 строк |
| 11 | `database-edit-view.fxml` | то же: параметры подключения задаются переменными окружения, а не формой | **не переносится.** Прямое следствие правила проекта «секреты — только через env» | малая: 145 строк |

## Отдельно: что переносить нельзя ни при каких условиях

| Элемент | Почему |
|---|---|
| Рефлексия в `TableViewSkin` / `VirtualFlow` (умный скролл) | внутреннее API JavaFX; в вебе заменяется собственной прокруткой к строке |
| Клавиатурный видеоплеер (`Z` / `X`, `isPlayingForward`, `isWorking`) | кода нет, только заготовка |
| `btnTagAdd` / `btnTagDelete` | в FXML нет, кода тегов нет |
| Три копии `labelFirst` / `labelLast` | костыль под особенность JavaFX-нод |
| `ContextMenu`, объявленный в `shots-edit-view.fxml` | не используется, код создаёт свой |
| Клавиатура-видеоплеер как «поведение» | нереализованное обещание |
| Выбор и правка базы данных (`database-select`, `database-edit`) | в SYP одна база, параметры — через env |
| `onMouseReleased` без проверки drag в `frame-faces-edit-view.fxml` | создаёт вырожденные лица |
| Опечатки в подписях и fx:id | смысловой мусор, не переносится |
| Тултипы «Добавить файл» / «Удалить файл из проекта» на несвязанных кнопках | вводят в заблуждение |
