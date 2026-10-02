// Представление ответов бэкенда для экранов.
//
// Зачем файл существует. Поля ответов сейчас называются так, как их назвал
// бэкенд: `serialId`, `seriesId`, `sourceRoot`. Переименование `Serial` →
// `Movie`, `Series` → `Episode` идёт параллельно в ветке `037-rename`. Если бы
// экраны читали поля напрямую, правка растянулась бы на каждый шаблон.
//
// Здесь каждое поле ответа читается **один раз** и отдаётся экрану в виде,
// который говорит предметно: `movieTitle`, `episodeTitle`, `sourcePath`. После
// переименования меняются DTO и эти функции — экран не трогается.
//
// Правило файла: ни один компонент в `views/` и `components/` не обращается к
// полям DTO напрямую. Нарушение видно по grep-у `\.serialId` вне `api/`.
//
// Формат ответов — контракт `specs/001-first-vertical-slice/contracts/admin-api.md`.

import { formatBytes, formatDate, formatDuration, formatNumber } from '../format/values'
import type { SerialView, SeriesView } from './catalog'
import type { ChecksumView } from './checksum'
import type { SceneView, ShotView, StructureView } from './structure'

/** Сериал на экране: то, что о нём знает оператор. */
export interface SerialRow {
  /** Идентификатор сериала. */
  id: number
  /** Название сериала. */
  name: string
  /** Корень каталога сериала на машине администратора. */
  sourceRoot: string
  /** Сколько серий заведено. */
  seriesCount: number
  /** Дата создания либо прочерк. */
  createdAt: string
  /** Есть ли у сериала хотя бы одна серия. */
  hasSeries: boolean
}

/** Серия на экране: имя файла плюс измеренные системой параметры. */
export interface SeriesRow {
  /** Идентификатор серии. */
  id: number
  /** Сериал-владелец. */
  serialId: number
  /** Порядковый номер серии в сериале. */
  ordinal: number
  /** Название серии. */
  name: string
  /** Путь относительно корня сериала, иначе абсолютный. */
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
  /** Границы плана по кадрам. */
  frames: string
  /** Размер плана как строка шкалы. */
  size: string
  /** Происхождение размера плана словами. */
  sizeTitle: string
  /** Происхождение границы машиночитаемо: значение для класса темы. */
  originClass: string
  /** Происхождение границы словами. */
  originTitle: string
  /** Помечен ли план устаревшим. */
  isStale: boolean
}

/** Сцена с планами на экране. */
export interface SceneRow {
  /** Идентификатор сцены. */
  id: number
  /** Границы сцены по кадрам. */
  frames: string
  /** Происхождение границы машиночитаемо: значение для класса темы. */
  originClass: string
  /** Происхождение границы словами. */
  originTitle: string
  /** Место действия либо пояснение, что оно не назначено. */
  location: string
  /** Планы сцены. */
  shots: ShotRow[]
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
      return { originClass: 'auto', originTitle: 'решение алгоритма' }
    case 'OPERATOR':
      return { originClass: 'operator', originTitle: 'сделано оператором' }
    case 'CANCELLED':
      return { originClass: 'cancelled', originTitle: 'отменено оператором' }
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
  return sizeOrigin === 'OPERATOR' ? 'выбрано оператором' : 'вычислено автоматически'
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
 * Приводит сериал к строке экрана.
 *
 * @param dto сериал из ответа бэкенда
 * @returns строка экрана
 */
export function toSerialRow(dto: SerialView): SerialRow {
  return {
    id: dto.id,
    name: dto.name,
    sourceRoot: dto.sourceRoot,
    seriesCount: dto.seriesCount,
    createdAt: formatDate(dto.createdAt),
    hasSeries: dto.seriesCount > 0,
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
export function toSeriesRow(dto: SeriesView): SeriesRow {
  return {
    id: dto.id,
    serialId: dto.serialId,
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
    seriesId: dto.seriesId,
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
 * @param dto план из ответа бэкенда
 * @returns строка экрана
 */
export function toShotRow(dto: ShotView): ShotRow {
  const origin = describeOrigin(dto.origin)
  return {
    id: dto.id,
    firstFrame: dto.firstFrame,
    frames: `${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}`,
    size: dto.size,
    sizeTitle: describeSizeOrigin(dto.sizeOrigin),
    originClass: origin.originClass,
    originTitle: origin.originTitle,
    isStale: dto.isStale,
  }
}

/**
 * Приводит сцену к строке экрана.
 *
 * @param dto сцена из ответа бэкенда
 * @returns строка экрана
 */
export function toSceneRow(dto: SceneView): SceneRow {
  const origin = describeOrigin(dto.origin)
  return {
    id: dto.id,
    frames: `${formatNumber(dto.firstFrame)}…${formatNumber(dto.lastFrame)}`,
    originClass: origin.originClass,
    originTitle: origin.originTitle,
    location: dto.location?.name ?? 'не назначено',
    shots: dto.shots.map(toShotRow),
    isStale: dto.isStale,
  }
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
  const scenes = dto.scenes.map(toSceneRow)
  return {
    seriesId: dto.seriesId,
    summary:
      `сцен: ${formatNumber(dto.scenesTotal)}, планов: ${formatNumber(dto.shotsTotal)}, ` +
      `кадров: ${formatNumber(dto.frameCount)}`,
    algorithmVersion: dto.algorithmVersion ?? '—',
    isStale: dto.isStale,
    staleCode: dto.staleResultCode ?? '',
    staleReason: dto.staleReason,
    scenesTotal: dto.scenesTotal,
    offset: dto.offset,
    scenes,
    visibleFrom: dto.offset + 1,
    visibleTo: dto.offset + scenes.length,
    hasNextPage: dto.offset + dto.limit < dto.scenesTotal,
  }
}
