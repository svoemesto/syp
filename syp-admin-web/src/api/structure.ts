// Клиент структуры серии и превью кадров.
//
// Соответствует разделу 5 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах не
// приходит: номер кадра — единственный источник правды, а клиент пересчитывает
// время от `time_base` серии (ADR-0001). Клиенту не нужно знать и раскладку
// листа превью: сервер отдаёт готовый адрес и область кадрирования кадра.

import { request } from './http'

/** Место действия сцены. */
export interface LocationView {
  /** Идентификатор локации. */
  id: number
  /** Отображаемое имя локации. */
  name: string
}

/** План внутри сцены. */
export interface ShotView {
  /** Идентификатор плана. */
  id: number
  /** Первый кадр плана, нумерация с нуля. */
  firstFrame: number
  /** Последний кадр плана. */
  lastFrame: number
  /** Размер плана: от `NONE` до `XLS`. */
  size: string
  /** Происхождение размера: `AUTO` или `OPERATOR`. */
  sizeOrigin: string
  /** Происхождение границы: `AUTO`, `OPERATOR` или `CANCELLED`. */
  origin: string
  /** Помечен ли результат устаревшим. */
  isStale: boolean
}

/** Сцена с планами. */
export interface SceneView {
  /** Идентификатор сцены. */
  id: number
  /** Первый кадр сцены. */
  firstFrame: number
  /** Последний кадр сцены. */
  lastFrame: number
  /** Происхождение границы. */
  origin: string
  /** Место действия либо `null`, если не назначено. */
  location: LocationView | null
  /** Помечен ли результат устаревшим. */
  isStale: boolean
  /** Планы, лежащие в сцене целиком. */
  shots: ShotView[]
}

/** Ответ о структуре серии. */
export interface StructureView {
  /** Серия. */
  seriesId: number
  /** Число кадров серии. */
  frameCount: number
  /** Устарел ли результат. */
  isStale: boolean
  /** Машинный код устаревания `STALE_RESULT` либо `null`. */
  staleResultCode: string | null
  /** Чем именно результат устарел. */
  staleReason: string | null
  /** Последний прогон структуры либо `null`. */
  runId: number | null
  /** Версия алгоритма прогона. */
  algorithmVersion: string | null
  /** Хеш входов прогона. */
  paramsHash: string | null
  /** Сколько сцен у серии всего. */
  scenesTotal: number
  /** Сколько планов у серии всего. */
  shotsTotal: number
  /** Смещение выборки сцен. */
  offset: number
  /** Размер выборки сцен. */
  limit: number
  /** Сцены выборки. */
  scenes: SceneView[]
}

/** Сырая граница результата автоматики. */
export interface RawBoundaryView {
  /** Прогон, которым граница получена. */
  runId: number
  /** Уровень: `SCENE` или `SHOT`. */
  level: string
  /** Первый кадр участка. */
  firstFrame: number
  /** Последний кадр участка. */
  lastFrame: number
}

/** Ответ с сырыми границами. */
export interface RawBoundariesView {
  /** Серия. */
  seriesId: number
  /** Прогон либо `null`, если прогона ещё не было. */
  runId: number | null
  /** Смещение выборки. */
  offset: number
  /** Размер выборки. */
  limit: number
  /** Сколько границ всего. */
  total: number
  /** Запрошенный уровень либо `null`, если оба. */
  level: string | null
  /** Границы выборки. */
  boundaries: RawBoundaryView[]
}

/** Значимый кадр серии. */
export interface FrameView {
  /** Номер кадра. */
  frameNumber: number
  /** Начинается ли здесь новая сцена. */
  isSceneBoundary: boolean
  /** Начинается ли здесь новый план. */
  isShotBoundary: boolean
  /** Сколько лиц найдено в кадре. */
  faceCount: number
  /** Подсказка смены крупности либо `null`. */
  sizeHint: string | null
  /** Ключевой ли кадр по карте серии. */
  isKeyframe: boolean
}

/** Страница значимых кадров. */
export interface FramesView {
  /** Серия. */
  seriesId: number
  /** Сколько значимых кадров у серии. */
  total: number
  /** Смещение выборки. */
  offset: number
  /** Размер выборки. */
  limit: number
  /** Кадры выборки. */
  frames: FrameView[]
}

/** Ответ на постановку анализа структуры. */
export interface AnalysisEnqueuedView {
  /** Поставленное задание. */
  jobId: number
  /** Серия. */
  seriesId: number
  /** Состояние задания на момент постановки. */
  state: string
  /** Порог границы сцены. */
  sceneThreshold: number
  /** Порог границы плана. */
  shotThreshold: number
  /** Хеш входов задания. */
  paramsHash: string
  /** Число кадров серии. */
  frameCount: number
  /** Сколько листов превью у серии будет. */
  previewSheetCount: number
  /** Выполнялась ли такая работа раньше. */
  alreadyCompleted: boolean
}

/** Область кадрирования кадра на листе превью. */
export interface CellCropView {
  /** Строка листа, с нуля. */
  row: number
  /** Столбец листа, с нуля. */
  column: number
  /** Левый край области в пикселях листа. */
  x: number
  /** Верхний край области в пикселях листа. */
  y: number
  /** Ширина области в пикселях. */
  width: number
  /** Высота области в пикселях. */
  height: number
}

/** Адрес листа превью и его раскладка. */
export interface PreviewUrlView {
  /** Серия. */
  seriesId: number
  /** Номер листа, с нуля. */
  index: number
  /** Первый кадр листа. */
  firstFrame: number
  /** Последний кадр листа. */
  lastFrame: number
  /** Сколько кадров на листе. */
  frameNumbers: number
  /** Ячеек по горизонтали. */
  columns: number
  /** Ячеек по вертикали. */
  rows: number
  /** Ширина ячейки, пикселей. */
  cellWidth: number
  /** Высота ячейки, пикселей. */
  cellHeight: number
  /** Ширина листа, пикселей. */
  sheetWidth: number
  /** Высота листа, пикселей. */
  sheetHeight: number
  /** Зарегистрирован ли лист в состоянии `READY`. */
  isReady: boolean
  /** Размер листа в байтах. */
  byteSize: number | null
  /** Тип содержимого. */
  contentType: string
  /** Адрес листа для прямой загрузки. */
  url: string
  /** Запрошенный кадр либо `null`. */
  frame: number | null
  /** Область кадрирования запрошенного кадра либо `null`. */
  crop: CellCropView | null
}

/**
 * Ставит серию на анализ структуры.
 *
 * Работа идёт заданием очереди и занимает минуты, поэтому ответ приходит
 * сразу: кнопка, ждущая окончания, в интерфейсе недопустима (FR-003).
 *
 * @param seriesId идентификатор серии
 * @returns поставленное задание
 */
export function startAnalysis(seriesId: number): Promise<AnalysisEnqueuedView> {
  return request<AnalysisEnqueuedView>('POST', `/series/${seriesId}/analysis`)
}

/**
 * Читает структуру серии.
 *
 * @param seriesId идентификатор серии
 * @param offset смещение выборки сцен
 * @param limit размер выборки сцен
 * @returns страница структуры
 */
export function readStructure(seriesId: number, offset = 0, limit = 200): Promise<StructureView> {
  return request<StructureView>(
    'GET',
    `/series/${seriesId}/structure?offset=${offset}&limit=${limit}`,
  )
}

/**
 * Читает сырые границы результата автоматики.
 *
 * @param seriesId идентификатор серии
 * @param level уровень границ: `SCENE`, `SHOT` или оба
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница сырых границ
 */
export function readRawBoundaries(
  seriesId: number,
  level?: string,
  offset = 0,
  limit = 200,
): Promise<RawBoundariesView> {
  const levelQuery = level === undefined ? '' : `&level=${level}`
  return request<RawBoundariesView>(
    'GET',
    `/series/${seriesId}/raw-boundaries?offset=${offset}&limit=${limit}${levelQuery}`,
  )
}

/**
 * Читает страницу значимых кадров серии.
 *
 * @param seriesId идентификатор серии
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница значимых кадров
 */
export function readFrames(seriesId: number, offset = 0, limit = 200): Promise<FramesView> {
  return request<FramesView>('GET', `/series/${seriesId}/frames?offset=${offset}&limit=${limit}`)
}

/**
 * Читает адрес листа превью и область кадрирования кадра.
 *
 * @param seriesId идентификатор серии
 * @param frame кадр, для которого нужен лист; без него берётся первый лист
 * @returns описание листа превью
 */
export function readPreviewUrl(seriesId: number, frame?: number): Promise<PreviewUrlView> {
  const frameQuery = frame === undefined ? '' : `&frame=${frame}`
  return request<PreviewUrlView>('GET', `/series/${seriesId}/preview-url?index=0${frameQuery}`)
}

/**
 * Адрес листа превью для прямой загрузки изображением.
 *
 * Отдельная функция вместо строки в шаблоне: адрес собирается в одном месте,
 * и переименование пути не потребует правок по экрану.
 *
 * @param seriesId идентификатор серии
 * @param index номер листа, с нуля
 * @returns адрес листа
 */
export function previewSheetUrl(seriesId: number, index: number): string {
  return `/api/series/${seriesId}/preview-sheets/${index}`
}
