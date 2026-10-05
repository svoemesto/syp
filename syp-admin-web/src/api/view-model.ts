// Представление ответов бэкенда для экранов.
//
// Зачем файл существует. Поля ответов сейчас называются так, как их назвал
// бэкенд: `serialId`, `seriesId`, `sourceRoot`. Переименование `Serial` →
// `Project`, `Series` → `Videofile` идёт параллельно в ветке `037-rename`. Если бы
// экраны читали поля напрямую, правка растянулась бы на каждый шаблон.
//
// Здесь каждое поле ответа читается **один раз** и отдаётся экрану в виде,
// который говорит предметно: `projectTitle`, `videofileTitle`, `sourcePath`. После
// переименования меняются DTO и эти функции — экран не трогается.
//
// Правило файла: ни один компонент в `views/` и `components/` не обращается к
// полям DTO напрямую. Нарушение видно по grep-у `\.serialId` вне `api/`.
//
// Формат ответов — контракт `specs/001-first-vertical-slice/contracts/admin-api.md`.

import { formatBytes, formatDate, formatDuration, formatNumber } from '../format/values'
import type { ProjectView, VideofileView } from './catalog'
import type { ChecksumView } from './checksum'
import type {
  FaceClusterView,
  FaceView,
  FacesView,
  FaceClustersView,
  PersonView,
} from './characters'
import type { PreviewUrlView } from './structure'
import type {
  SceneBoundaryView,
  SceneView,
  ShotBoundaryView,
  ShotView,
  StructureView,
} from './structure'

/** Проект на экране: то, что о нём знает оператор. */
export interface SerialRow {
  /** Идентификатор проекта. */
  id: number
  /** Название проекта. */
  name: string
  /** Корень каталога проекта на машине администратора. */
  sourceRoot: string
  /** Сколько серий заведено. */
  videofileCount: number
  /** Дата создания либо прочерк. */
  createdAt: string
  /** Есть ли у проекта хотя бы одна серия. */
  hasSeries: boolean
}

/** Серия на экране: имя файла плюс измеренные системой параметры. */
export interface SeriesRow {
  /** Идентификатор серии. */
  id: number
  /** Проект-владелец. */
  serialId: number
  /** Порядковый номер серии в проекте. */
  ordinal: number
  /** Название серии. */
  name: string
  /** Путь относительно корня проекта, иначе абсолютный. */
  displayPath: string
  /** Число кадров с разделителем разрядов. */
  frameCount: string
  /** Разрешение кадра. */
  resolution: string
  /** Длительность по кадрам. */
  duration: string
  /** Размер файла. */
  size: string
  /** Частокадровая база как её отдал бэкенд. */
  frameRate: string
  /** Сколько ключевых кадров найдено. */
  keyframeCount: string
  /** Готова ли серия к работе. */
  ready: boolean
}

/** Состояние подсчёта суммы на экране. */
export interface ChecksumRow {
  /** Серия, для которой считается сумма. */
  seriesId: number
  /** Короткая формулировка состояния. */
  stateTitle: string
  /** Тон сообщения: `info`, `success`, `warning` или `danger`. */
  stateTone: 'info' | 'success' | 'warning' | 'danger'
  /** Идёт ли подсчёт прямо сейчас. */
  isRunning: boolean
  /** Алгоритм подсчёта. */
  algorithm: string
  /** Значение суммы с разбивкой на блоки либо прочерк. */
  digest: string
  /** Когда посчитано либо прочерк. */
  computedAt: string
  /** Устарела ли сумма. */
  isStale: boolean
  /** Пригодна ли сумма для сверки. */
  isUsable: boolean
  /** Размер файла при подсчёте. */
  byteSize: string
  /** Время изменения файла. */
  fileMtime: string
  /** Сколько записей пересчётов. */
  historyCount: string
  /** Текст ошибки подсчёта либо `null`. */
  errorText: string | null
}

/** План сцены на экране. */
export interface ShotRow {
  /** Идентификатор плана. */
  id: number
  /** Первый кадр плана: по нему открывается лист превью. */
  firstFrame: number
  /** Последний кадр плана. */
  lastFrame: number
  /** Границы плана по кадрам. */
  frames: string
  /** Размер плана как строка шкалы. */
  size: string
  /** Размер плана словами: ступень десятиступенчатой шкалы (ADR-0003). */
  sizeTitle: string
  /** Происхождение размера плана словами. */
  sizeOriginTitle: string
  /** Происхождение границы машиночитаемо: значение для класса темы. */
  originClass: string
  /** Происхождение границы словами. */
  originTitle: string
  /** Помечен ли план устаревшим. */
  isStale: boolean
  /** Показывать ли рамку первого кадра плана. */
  hasThumb: boolean
}

/** Сцена с планами на экране. */
export interface SceneRow {
  /** Идентификатор сцены. */
  id: number
  /** Порядковый номер сцены в структуре видеофайла. */
  number: string
  /** Первый кадр сцены. */
  firstFrame: number
  /** Последний кадр сцены. */
  lastFrame: number
  /** Границы сцены по кадрам. */
  frames: string
  /** Название сцены либо пояснение, что оно не задано. */
  title: string
  /** Происхождение границы машиночитаемо: значение для класса темы. */
  originClass: string
  /** Происхождение границы словами. */
  originTitle: string
  /** Место действия либо пояснение, что оно не назначено. */
  location: string
  /** Планы сцены. */
  shots: ShotRow[]
  /** Сколько планов в сцене словами. */
  shotCount: string
  /** Помечена ли сцена устаревшей. */
  isStale: boolean
}

/** Сырая граница автоматики на экране. */
export interface RawBoundaryRow {
  /** Уровень границы словами. */
  level: string
  /** Границы по кадрам. */
  frames: string
}

/** Структура серии на экране. */
export interface StructureRow {
  /** Идентификатор серии. */
  seriesId: number
  /** Счётчики одной строкой: «сцен, планов, кадров». */
  summary: string
  /** Версия алгоритма либо прочерк. */
  algorithmVersion: string
  /** Устарел ли результат. */
  isStale: boolean
  /** Машинный код устаревания либо `null`. */
  staleCode: string
  /** Чем результат устарел либо `null`. */
  staleReason: string | null
  /** Всего сцен у серии. */
  scenesTotal: number
  /** Число кадров видеофайла: им ограничивается ввод номера кадра. */
  frameCount: number
  /** Смещение выборки. */
  offset: number
  /** Сценарии выборки. */
  scenes: SceneRow[]
  /** Границы, показанные на текущей странице. */
  visibleFrom: number
  /** Границы, показанные на текущей странице. */
  visibleTo: number
  /** Есть ли следующая страница сцен. */
  hasNextPage: boolean
}

/** Лист превью на экране: всё, что нужно компоненту показа. */
export interface PreviewSheetFrameRow {
  /** Номер листа, с нуля. */
  index: number
  /** Подпись листа: номер, кадры, размер ячейки. */
  caption: string
  /** Описание листа для программ чтения с экрана. */
  alt: string
  /** Адрес картинки листа. */
  url: string
  /** Ширина листа в пикселях. */
  sheetWidth: number
  /** Высота листа в пикселях. */
  sheetHeight: number
  /** Ячеек по горизонтали. */
  columns: number
  /** Ячеек по вертикали. */
  rows: number
  /** Первый кадр листа: с него считаются ячейки. */
  firstFrame: number
  /** Последний кадр листа: за ним ячеек на листе нет. */
  lastFrame: number
  /** Готово ли содержимое листа. */
  isReady: boolean
  /** Положение выделенной ячейки в процентах листа либо `null`. */
  highlight: { left: string; top: string; width: string; height: string } | null
}

/** Рамка первого кадра плана на экране. */
export interface ShotThumbRow {
  /** Адрес картинки листа, из которого вырезан кадр. */
  url: string
  /** Ширина рамки. */
  width: string
  /** Высота рамки. */
  height: string
  /** Размер листа: по нему рамка выравнивается внутри картинки. */
  backgroundSize: string
  /** Смещение рамки внутри листа. */
  backgroundPosition: string
}

/** Навигация по листам превью на экране. */
export interface PreviewSheetNavRow {
  /** Номер листа, с нуля. */
  index: number
  /** Номер листа для оператора, с единицы. */
  number: string
  /** Сколько листов у видеофайла. */
  total: number
  /** Человекочитаемая строка «лист N из M». */
  position: string
  /** Кадры листа словами. */
  frames: string
  /** Есть ли предыдущий лист. */
  hasPrevious: boolean
  /** Есть ли следующий лист. */
  hasNext: boolean
  /** Готово ли содержимое листа. */
  isReady: boolean
  /** Размер листа словами либо прочерк, пока лист не готов. */
  byteSize: string
}

/** Ответ на правку границы сцены на экране. */
export interface SceneBoundaryRow {
  /** Что именно изменилось, словами. */
  title: string
  /** Сцены затронутого участка после операции. */
  scenes: SceneRow[]
  /** Сколько рабочих сцен у видеофайла после операции. */
  scenesTotal: number
}

/**
 * Форматирует происхождение границы.
 *
 * Класс темы и текст для оператора вычисляются здесь, а не в шаблоне: цвет
 * происхождения задан один раз в `theme/theme.css`, и подпись обязана
 * говорить о том же, что показывает цвет (FR-015, FR-016).
 *
 * @param origin происхождение границы из ответа бэкенда
 * @returns класс темы и текст для оператора
 */
function describeOrigin(origin: string): { originClass: string; originTitle: string } {
  switch (origin) {
    case 'AUTO':
      return { originClass: 'auto', originTitle: 'decided by the algorithm' }
    case 'OPERATOR':
      return { originClass: 'operator', originTitle: 'done by the operator' }
    case 'CANCELLED':
      return { originClass: 'cancelled', originTitle: 'cancelled by the operator' }
    default:
      return { originClass: 'auto', originTitle: origin }
  }
}

/**
 * Поясняет происхождение размера плана словами.
 *
 * @param sizeOrigin происхождение размера из ответа бэкенда
 * @returns текст для оператора
 */
function describeSizeOrigin(sizeOrigin: string): string {
  return sizeOrigin === 'OPERATOR' ? 'chosen by the operator' : 'computed automatically'
}

/**
 * Поясняет ступень шкалы размера плана словами.
 *
 * Шкала десятиступенчатая и выбрана не на глаз: она сверена с эталонной
 * шкалой старого проекта (ADR-0003). Поэтому на экране рядом со значением
 * стоит и расшифровка ступени — читать `MCU` без подписи умеет не всякий,
 * а решение о размере принимает человек.
 *
 * @param size ступень шкалы из ответа бэкенда
 * @returns текст для оператора
 */
export function describeShotSize(size: string): string {
  switch (size) {
    case 'NONE':
      return 'size is not determined: the shot has no faces'
    case 'ECU':
      return 'extreme close-up'
    case 'BCU':
      return 'big close-up'
    case 'CU':
      return 'close-up'
    case 'MCU':
      return 'medium close-up'
    case 'MS':
      return 'medium shot'
    case 'MLS':
      return 'medium wide shot'
    case 'LS':
      return 'wide shot'
    case 'VLS':
      return 'high-angle wide shot съёмки'
    case 'XLS':
      return 'extreme wide shot'
    default:
      return size
  }
}

/**
 * Поясняет уровень границы словами.
 *
 * @param level уровень границы из ответа бэкенда
 * @returns текст для оператора
 */
function describeLevel(level: string): string {
  return level === 'SHOT' ? 'план' : 'сцена'
}

/**
 * Приводит проект к строке экрана.
 *
 * @param dto проект из ответа бэкенда
 * @returns строка экрана
 */
export function toSerialRow(dto: ProjectView): SerialRow {
  return {
    id: dto.id,
    name: dto.name,
    sourceRoot: dto.sourceRoot,
    videofileCount: dto.videofileCount,
    createdAt: formatDate(dto.createdAt),
    hasSeries: dto.videofileCount > 0,
  }
}

/**
 * Приводит серию к строке экрана.
 *
 * Параметры файла показываются в том виде, в котором система их измерила:
 * путь, разрешение, длительность и размер приходят из разных мест, и
 * собирать их в шаблоне значит знать про формат больше, чем должен знать экран.
 *
 * @param dto серия из ответа бэкенда
 * @returns строка экрана
 */
export function toSeriesRow(dto: VideofileView): SeriesRow {
  return {
    id: dto.id,
    serialId: dto.projectId,
    ordinal: dto.ordinal,
    name: dto.name,
    displayPath: dto.relativePath ?? dto.sourcePath,
    frameCount: formatNumber(dto.frameCount),
    resolution: `${dto.width}×${dto.height}`,
    duration: formatDuration(dto.durationSeconds),
    size: formatBytes(dto.byteSize, 'байт', 0),
    frameRate: dto.frameRate,
    keyframeCount: formatNumber(dto.keyframeCount),
    ready: dto.ready,
  }
}

/**
 * Приводит состояние суммы к строке экрана.
 *
 * Формулировка состояния — здесь, а не в шаблоне: оператор читает текст, а
 * машинный код нужен интерфейсу, и смешивать их в разметке нельзя.
 *
 * @param dto состояние суммы из ответа бэкенда
 * @returns строка экрана
 */
export function toChecksumRow(dto: ChecksumView): ChecksumRow {
  const running = dto.state === 'CREATING' || dto.state === 'WORKING'
  const tone: ChecksumRow['stateTone'] = running
    ? 'info'
    : dto.state === 'ERROR'
      ? 'danger'
      : dto.isStale
        ? 'warning'
        : 'success'
  let stateTitle: string
  switch (dto.state) {
    case 'CREATING':
      stateTitle = 'Задание поставлено, чтение ещё не началось'
      break
    case 'WORKING':
      stateTitle = 'Идёт чтение файла и подсчёт'
      break
    case 'ERROR':
      stateTitle = 'Подсчёт не удался'
      break
    case 'DONE':
      stateTitle = dto.isStale ? 'Сумма устарела: файл изменился после подсчёта' : 'Сумма актуальна'
      break
    default:
      stateTitle = dto.state
  }
  return {
    seriesId: dto.videofileId,
    stateTitle,
    stateTone: tone,
    isRunning: running,
    algorithm: dto.algorithm,
    digest: dto.digest === null ? '—' : dto.digest.replace(/(.{16})/g, '$1 ').trim(),
    computedAt: formatDate(dto.computedAt),
    isStale: dto.isStale,
    isUsable: dto.isUsable,
    byteSize: formatBytes(dto.byteSize),
    fileMtime: formatDate(dto.fileMtime),
    historyCount: formatNumber(dto.historyCount),
    errorText: dto.errorText,
  }
}

/**
 * Приводит план к строке экрана.
 *
 * Размер показывается и ступенью шкалы, и словами: десять ступеней шкалы
 * размера сверены с эталонной старого проекта, и решение принимает человек
 * (ADR-0003). Одно значение без расшифровки заставляет держать шкалу в голове.
 *
 * Поле `hasThumb` выставляется экраном: рамку первого кадра плана показывают
 * у планов выбранной сцены, а не у всех восьмисот — иначе один лист превью
 * скачивался бы сотни раз.
 *
 * @param dto план из ответа бэкенда
 * @returns строка экрана
 */
export function toShotRow(dto: ShotView, hasThumb = false): ShotRow {
  const origin = describeOrigin(dto.origin)
  return {
    id: dto.id,
    firstFrame: dto.firstFrame,
    lastFrame: dto.lastFrame,
    frames: `${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}`,
    size: dto.size,
    sizeTitle: describeShotSize(dto.size),
    sizeOriginTitle: describeSizeOrigin(dto.sizeOrigin),
    originClass: origin.originClass,
    originTitle: origin.originTitle,
    isStale: dto.isStale,
    hasThumb,
  }
}

/**
 * Приводит сцену к строке экрана.
 *
 * @param dto сцена из ответа бэкенда
 * @param number порядковый номер сцены в структуре видеофайла
 * @returns строка экрана
 */
export function toSceneRow(dto: SceneView, number: number): SceneRow {
  const origin = describeOrigin(dto.origin)
  return {
    id: dto.id,
    number: formatNumber(number),
    firstFrame: dto.firstFrame,
    lastFrame: dto.lastFrame,
    frames: `${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}`,
    title: dto.title ?? '',
    originClass: origin.originClass,
    originTitle: origin.originTitle,
    location: dto.location?.name ?? 'not assigned',
    shots: dto.shots.map((shot) => toShotRow(shot)),
    shotCount: `${dto.shots.length} ${shotWord(dto.shots.length)}`,
    isStale: dto.isStale,
  }
}

/**
 * Склоняет слово «план» по числу.
 *
 * @param count число планов
 * @returns слово в нужной форме
 */
function shotWord(count: number): string {
  const mod100 = Math.abs(count) % 100
  const mod10 = mod100 % 10
  if (mod100 >= 11 && mod100 <= 14) {
    return 'планов'
  }
  if (mod10 === 1) {
    return 'план'
  }
  if (mod10 >= 2 && mod10 <= 4) {
    return 'плана'
  }
  return 'планов'
}

/**
 * Приводит сырую границу автоматики к строке экрана.
 *
 * @param level уровень границы
 * @param firstFrame первый кадр участка
 * @param lastFrame последний кадр участка
 * @returns строка экрана
 */
export function toRawBoundaryRow(
  level: string,
  firstFrame: number,
  lastFrame: number,
): RawBoundaryRow {
  return {
    level: describeLevel(level),
    frames: `${formatNumber(firstFrame)}…${formatNumber(lastFrame)}`,
  }
}

/**
 * Приводит структуру серии к строке экрана.
 *
 * @param dto структура из ответа бэкенда
 * @returns строка экрана
 */
export function toStructureRow(dto: StructureView): StructureRow {
  const scenes = dto.scenes.map((scene, position) => toSceneRow(scene, dto.offset + position + 1))
  return {
    seriesId: dto.videofileId,
    summary:
      `scenes: ${formatNumber(dto.scenesTotal)}, shots: ${formatNumber(dto.shotsTotal)}, ` +
      `frames: ${formatNumber(dto.frameCount)}`,
    algorithmVersion: dto.algorithmVersion ?? '—',
    isStale: dto.isStale,
    staleCode: dto.staleResultCode ?? '',
    staleReason: dto.staleReason,
    scenesTotal: dto.scenesTotal,
    frameCount: dto.frameCount,
    offset: dto.offset,
    scenes,
    visibleFrom: dto.offset + 1,
    visibleTo: dto.offset + scenes.length,
    hasNextPage: dto.offset + dto.limit < dto.scenesTotal,
  }
}

/**
 * Приводит навигацию по листам превью к строке экрана.
 *
 * Номер листа оператор считает с единицы, а сервер — с нуля. Обе величины
 * остаются в строке: с нуля нужен адрес, с единицы — то, что видит человек.
 * Число листов приходит с сервера: оно вычисляется из числа кадров видеофайла и
 * раскладки, а раскладка принадлежит серверу.
 *
 * @param dto лист из ответа бэкенда
 * @returns строка экрана
 */
export function toPreviewSheetNavRow(dto: PreviewUrlView): PreviewSheetNavRow {
  const sheets = dto.sheetCount
  return {
    index: dto.index,
    number: formatNumber(dto.index + 1),
    total: sheets,
    position: `лист ${formatNumber(dto.index + 1)} из ${formatNumber(sheets)}`,
    frames: `кадры ${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}`,
    hasPrevious: dto.index > 0,
    hasNext: dto.index + 1 < sheets,
    isReady: dto.isReady,
    byteSize: dto.byteSize === null ? '—' : formatBytes(dto.byteSize),
  }
}

/**
 * Приводит ответ на правку границы сцены к строке экрана.
 *
 * @param dto ответ из ответа бэкенда
 * @returns строка экрана
 */
export function toSceneBoundaryRow(dto: SceneBoundaryView): SceneBoundaryRow {
  return {
    title: dto.actionTitle,
    scenes: dto.scenes.map((scene, position) => toSceneRow(scene, position + 1)),
    scenesTotal: dto.scenesTotal,
  }
}

/**
 * Приводит ответ на правку границы плана к строке экрана.
 *
 * Сцены получают номера по порядку в ответе, а не по порядку во всём
 * видеофайле: правка отвечает изменённым участком, и сквозная нумерация здесь
 * была бы выдуманной — сосчитать её без всей структуры нельзя.
 *
 * @param dto ответ из ответа бэкенда
 * @returns строка экрана
 */
export function toShotBoundaryRow(dto: ShotBoundaryView): ShotBoundaryRow {
  return {
    title: dto.actionTitle,
    shots: dto.shots.map((shot) => toShotRow(shot)),
    scenes: dto.scenes.map((scene, position) => toSceneRow(scene, position + 1)),
    sizesRecomputed: dto.sizesRecomputed,
    facesRebound: dto.facesRebound,
  }
}

/**
 * Ответ на правку границы плана в терминах экрана.
 *
 * Размер едет вместе с границей и словами: пересчёт выполнен в той же
 * операции, поэтому показывать оператору прежний размер значило бы показывать
 * то, чего уже нет.
 */
export interface ShotBoundaryRow {
  /** Что именно изменилось, словами. */
  title: string
  /** Планы затронутого участка после операции. */
  shots: ShotRow[]
  /** Сцены, в которые легли затронутые планы. */
  scenes: SceneRow[]
  /** Сколько планов получил пересчитанный размер, словами. */
  sizesRecomputed: number
  /** Сколько строк лица переведено на новые планы, словами. */
  facesRebound: number
}

/** Лицо на экране: то, что показывается оператору. */
export interface FaceRow extends FaceView {
  /** Номер кадра с разделителем разрядов. */
  frameLabel: string
  /** Подпись миниатюры для программ чтения с экрана. */
  caption: string
}

/** Группа лиц одной персоны на экране. */
export interface PersonFaceGroup {
  /** Идентификатор персоны. */
  personId: number
  /** Название персоны. */
  name: string
  /** Пояснение вида персоны словами. */
  kindTitle: string
  /** Лица персоны на текущей странице. */
  faces: FaceRow[]
}

/** Кластер похожих лиц на экране. */
export interface FaceClusterRow {
  /** Ключ кластера: его же принимает наименование. */
  id: string
  /** Сколько лиц в кластере словами. */
  size: string
}

/** Страница лиц серии на экране. */
export interface FacesRow {
  /** Серия. */
  seriesId: number
  /** Проект-владелец: по нему читается справочник персон. */
  serialId: number
  /** Ширина кадра серии. */
  frameWidth: number
  /** Высота кадра серии. */
  frameHeight: number
  /** Счётчики одной строкой. */
  summary: string
  /** Сколько лиц у проекта. */
  facesTotal: number
  /** Смещение выборки. */
  offset: number
  /** Лица выборки. */
  faces: FaceRow[]
  /** Есть ли предыдущая страница. */
  hasPreviousPage: boolean
  /** Есть ли следующая страница. */
  hasNextPage: boolean
}

/** Кластеры серии на экране. */
export interface FaceClustersRow {
  /** Сколько кластеров без имени словами. */
  total: string
  /** Ключ модели эмбеддингов либо прочерк. */
  modelKey: string
  /** Кластеры по убыванию числа лиц. */
  clusters: FaceClusterRow[]
}

/**
 * Поясняет вид персоны словами.
 *
 * Персона приходит машинным видом, а читает его оператор. Три служебные персоны
 * особенно важно назвать словами: они означают «детектор ошибся», и молчаливая
 * метка выглядела бы как потеря данных (FR-036).
 *
 * @param kind вид персоны из ответа бэкенда
 * @returns текст для оператора
 */
export function describePersonKind(kind: string): string {
  switch (kind) {
    case 'PERSON':
      return 'именованная персона'
    case 'UNRECOGNIZED':
      return 'лицо найдено, имя не подтверждено оператором'
    case 'NONPERSON':
      return 'рамка оказалась не лицом'
    default:
      return kind
  }
}

/**
 * Приводит лицо к строке экрана.
 *
 * Само лицо отдаётся компоненту миниатюр как есть: рамку надо рисовать по
 * пикселям кадра, и приводить её к процентам здесь означало бы продублировать
 * раскладку миниатюры в двух местах.
 *
 * @param dto лицо из ответа бэкенда
 * @returns строка экрана
 */
export function toFaceRow(dto: FaceView): FaceRow {
  const frame = formatNumber(dto.frameNumber)
  return {
    ...dto,
    frameLabel: frame,
    caption: `Лицо на кадре ${frame}, ${dto.personName}`,
  }
}

/**
 * Приводит страницу лиц серии к строке экрана.
 *
 * @param dto страница лиц из ответа бэкенда
 * @returns строка экрана
 */
export function toFacesRow(dto: FacesView): FacesRow {
  return {
    seriesId: dto.videofileId,
    serialId: dto.projectId,
    frameWidth: dto.frameWidth,
    frameHeight: dto.frameHeight,
    summary: `лиц найдено: ${formatNumber(dto.facesTotal)}, разрешение кадра: ${dto.frameWidth}×${dto.frameHeight}`,
    facesTotal: dto.facesTotal,
    offset: dto.offset,
    faces: dto.faces.map(toFaceRow),
    hasPreviousPage: dto.offset > 0,
    hasNextPage: dto.offset + dto.limit < dto.facesTotal,
  }
}

/**
 * Приводит кластер к строке экрана.
 *
 * @param dto кластер из ответа бэкенда
 * @returns строка экрана
 */
export function toFaceClusterRow(dto: FaceClusterView): FaceClusterRow {
  return {
    id: dto.id,
    size: `${formatNumber(dto.size)} ${faceWord(dto.size)}`,
  }
}

/**
 * Склоняет слово «лицо» по числу.
 *
 * @param count число лиц
 * @returns слово в нужной форме
 */
function faceWord(count: number): string {
  const mod100 = Math.abs(count) % 100
  const mod10 = mod100 % 10
  if (mod100 >= 11 && mod100 <= 14) {
    return 'лиц'
  }
  if (mod10 === 1) {
    return 'лицо'
  }
  if (mod10 >= 2 && mod10 <= 4) {
    return 'лица'
  }
  return 'лиц'
}

/**
 * Приводит кластеры серии к строке экрана.
 *
 * @param dto кластеры из ответа бэкенда
 * @returns строка экрана
 */
export function toFaceClustersRow(dto: FaceClustersView): FaceClustersRow {
  return {
    total: formatNumber(dto.clustersTotal),
    modelKey: dto.embeddingModelKey === '' ? '—' : dto.embeddingModelKey,
    clusters: dto.clusters.map(toFaceClusterRow),
  }
}

/** Персона проекта на экране правки. */
export interface PersonRow {
  /** Идентификатор персоны. */
  id: number
  /** Название персоны. */
  name: string
  /** Ключ класса в модели либо прочерк. */
  recognizerKey: string
}

/**
 * Приводит персону к строке экрана.
 *
 * @param dto персона из ответа бэкенда
 * @returns строка экрана
 */
export function toPersonRow(dto: PersonView): PersonRow {
  return {
    id: dto.id,
    name: dto.name,
    recognizerKey: dto.recognizerKey ?? '—',
  }
}

/**
 * Группирует лица по персонам для показа.
 *
 * Группировка живёт здесь, а не в экране, потому что читает поля ответа:
 * `personId`, `personName`, `personKind`. Экран получает готовые группы и не
 * знает, что такое персона в ответе бэкенда.
 *
 * @param faces лица текущей страницы
 * @returns группы по персонам, от большей к меньшей
 */
export function toPersonFaceGroups(faces: FaceRow[]): PersonFaceGroup[] {
  const groups = new Map<number, { name: string; kind: string; faces: FaceRow[] }>()
  for (const face of faces) {
    const group = groups.get(face.personId) ?? {
      name: face.personName,
      kind: face.personKind,
      faces: [],
    }
    group.faces.push(face)
    groups.set(face.personId, group)
  }
  return [...groups.entries()]
    .sort((a, b) => b[1].faces.length - a[1].faces.length)
    .map(([personId, group]) => ({
      personId,
      name: group.name,
      kindTitle: describePersonKind(group.kind),
      faces: group.faces,
    }))
}

/**
 * Приводит лист превью к строке экрана.
 *
 * Область выделенной ячейки пересчитывается в проценты здесь: компонент не
 * должен знать ни про пиксели листа, ни про то, как сервер считал область
 * кадрирования (FR-022).
 *
 * @param dto лист из ответа бэкенда
 * @param videofileId видеофайл-владелец листа: нужен для адреса картинки
 * @param highlightFrame кадр для подсветки либо `null`
 * @returns строка экрана
 */
export function toPreviewSheetFrameRow(
  dto: PreviewUrlView,
  videofileId: number,
  highlightFrame: number | null,
): PreviewSheetFrameRow {
  const crop = dto.crop
  return {
    index: dto.index,
    caption: toPreviewSheetCaption(dto),
    alt: toPreviewSheetAlt(dto),
    url: `/api/videofiles/${videofileId}/preview-sheets/${dto.index}`,
    sheetWidth: dto.sheetWidth,
    sheetHeight: dto.sheetHeight,
    columns: dto.columns,
    rows: dto.rows,
    firstFrame: dto.firstFrame,
    lastFrame: dto.lastFrame,
    isReady: dto.isReady,
    highlight:
      crop === null || highlightFrame === null || crop.x + crop.width > dto.sheetWidth
        ? null
        : {
            left: `${((crop.x / dto.sheetWidth) * 100).toFixed(4)}%`,
            top: `${((crop.y / dto.sheetHeight) * 100).toFixed(4)}%`,
            width: `${((crop.width / dto.sheetWidth) * 100).toFixed(4)}%`,
            height: `${((crop.height / dto.sheetHeight) * 100).toFixed(4)}%`,
          },
  }
}

/**
 * Приводит область кадрирования кадра к рамке плана.
 *
 * @param videofileId видеофайл-владелец листа
 * @param sheetIndex номер листа, на котором лежит кадр
 * @param sheetWidth ширина листа в пикселях
 * @param sheetHeight высота листа в пикселях
 * @param crop область кадрирования кадра
 * @returns строка экрана
 */
export function toShotThumbRow(
  videofileId: number,
  sheetIndex: number,
  sheetWidth: number,
  sheetHeight: number,
  crop: { x: number; y: number; width: number; height: number },
): ShotThumbRow {
  return {
    url: `/api/videofiles/${videofileId}/preview-sheets/${sheetIndex}`,
    width: `${crop.width}px`,
    height: `${crop.height}px`,
    backgroundSize: `${sheetWidth}px ${sheetHeight}px`,
    backgroundPosition: `-${crop.x}px -${crop.y}px`,
  }
}

/**
 * Описание листа превью одной строкой.
 *
 * Раскладка листа приходит с сервера, и собирать подпись из его полей на
 * экране — значит знать про формат больше, чем должен знать экран.
 *
 * @param dto лист превью из ответа бэкенда
 * @returns строка описания
 */
export function toPreviewSheetCaption(dto: PreviewUrlView): string {
  return (
    `Лист №${dto.index}: кадры ${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}, ` +
    `${formatNumber(dto.frameNumbers)} шт., ${dto.columns}×${dto.rows} ячеек по ` +
    `${dto.cellWidth}×${dto.cellHeight}`
  )
}

/**
 * Описание листа превью для `alt` у изображения.
 *
 * @param dto лист превью из ответа бэкенда
 * @returns текст для программ чтения с экрана
 */
export function toPreviewSheetAlt(dto: PreviewUrlView): string {
  return (
    `Лист превью №${dto.index}, кадры ` +
    `${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}`
  )
}
