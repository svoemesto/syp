// Клиент структуры эпизода и превью кадров.
//
// Соответствует разделу 5 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах не
// приходит: номер кадра — единственный источник правды, а клиент пересчитывает
// время от `time_base` эпизода (ADR-0001). Клиенту не нужно знать и раскладку
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

/** Ответ о структуре эпизода. */
export interface StructureView {
  /** Эпизод. */
  episodeId: number
  /** Число кадров эпизода. */
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
  /** Сколько сцен у эпизода всего. */
  scenesTotal: number
  /** Сколько планов у эпизода всего. */
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
  /** Эпизод. */
  episodeId: number
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

/** Значимый кадр эпизода. */
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
  /** Ключевой ли кадр по карте эпизода. */
  isKeyframe: boolean
}

/** Страница значимых кадров. */
export interface FramesView {
  /** Эпизод. */
  episodeId: number
  /** Сколько значимых кадров у эпизода. */
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
  /** Эпизод. */
  episodeId: number
  /** Состояние задания на момент постановки. */
  state: string
  /** Порог границы сцены. */
  sceneThreshold: number
  /** Порог границы плана. */
  shotThreshold: number
  /** Хеш входов задания. */
  paramsHash: string
  /** Число кадров эпизода. */
  frameCount: number
  /** Сколько листов превью у эпизода будет. */
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
  /** Эпизод. */
  episodeId: number
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
 * Ставит эпизод на анализ структуры.
 *
 * Работа идёт заданием очереди и занимает минуты, поэтому ответ приходит
 * сразу: кнопка, ждущая окончания, в интерфейсе недопустима (FR-003).
 *
 * @param episodeId идентификатор эпизода
 * @returns поставленное задание
 */
export function startAnalysis(episodeId: number): Promise<AnalysisEnqueuedView> {
  return request<AnalysisEnqueuedView>('POST', `/episodes/${episodeId}/analysis`)
}

/**
 * Читает структуру эпизода.
 *
 * @param episodeId идентификатор эпизода
 * @param offset смещение выборки сцен
 * @param limit размер выборки сцен
 * @returns страница структуры
 */
export function readStructure(episodeId: number, offset = 0, limit = 200): Promise<StructureView> {
  return request<StructureView>(
    'GET',
    `/episodes/${episodeId}/structure?offset=${offset}&limit=${limit}`,
  )
}

/**
 * Читает сырые границы результата автоматики.
 *
 * @param episodeId идентификатор эпизода
 * @param level уровень границ: `SCENE`, `SHOT` или оба
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница сырых границ
 */
export function readRawBoundaries(
  episodeId: number,
  level?: string,
  offset = 0,
  limit = 200,
): Promise<RawBoundariesView> {
  const levelQuery = level === undefined ? '' : `&level=${level}`
  return request<RawBoundariesView>(
    'GET',
    `/episodes/${episodeId}/raw-boundaries?offset=${offset}&limit=${limit}${levelQuery}`,
  )
}

/**
 * Читает страницу значимых кадров эпизода.
 *
 * @param episodeId идентификатор эпизода
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница значимых кадров
 */
export function readFrames(episodeId: number, offset = 0, limit = 200): Promise<FramesView> {
  return request<FramesView>('GET', `/episodes/${episodeId}/frames?offset=${offset}&limit=${limit}`)
}

/**
 * Читает адрес листа превью и область кадрирования кадра.
 *
 * @param episodeId идентификатор эпизода
 * @param frame кадр, для которого нужен лист; без него берётся первый лист
 * @returns описание листа превью
 */
export function readPreviewUrl(episodeId: number, frame?: number): Promise<PreviewUrlView> {
  const frameQuery = frame === undefined ? '' : `&frame=${frame}`
  return request<PreviewUrlView>('GET', `/episodes/${episodeId}/preview-url?index=0${frameQuery}`)
}

/**
 * Адрес листа превью для прямой загрузки изображением.
 *
 * Отдельная функция вместо строки в шаблоне: адрес собирается в одном месте,
 * и переименование пути не потребует правок по экрану.
 *
 * @param episodeId идентификатор эпизода
 * @param index номер листа, с нуля
 * @returns адрес листа
 */
export function previewSheetUrl(episodeId: number, index: number): string {
  return `/api/episodes/${episodeId}/preview-sheets/${index}`
}
