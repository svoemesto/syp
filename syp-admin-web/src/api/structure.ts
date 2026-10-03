// Клиент структуры видеофайла и превью кадров.
//
// Соответствует разделу 5 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах не
// приходит: номер кадра — единственный источник правды, а клиент пересчитывает
// время от `time_base` видеофайла (ADR-0001). Клиенту не нужно знать и раскладку
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
  /** Название сцены либо `null`, если оператор её не назвал. */
  title: string | null
  /** Происхождение границы. */
  origin: string
  /** Место действия либо `null`, если не назначено. */
  location: LocationView | null
  /** Помечен ли результат устаревшим. */
  isStale: boolean
  /** Планы, лежащие в сцене целиком. */
  shots: ShotView[]
}

/** Ответ о структуре видеофайла. */
export interface StructureView {
  /** Видеофайл. */
  videofileId: number
  /** Число кадров видеофайла. */
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
  /** Сколько сцен у видеофайла всего. */
  scenesTotal: number
  /** Сколько планов у видеофайла всего. */
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
  /** Видеофайл. */
  videofileId: number
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

/** Значимый кадр видеофайла. */
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
  /** Ключевой ли кадр по карте видеофайла. */
  isKeyframe: boolean
}

/** Страница значимых кадров. */
export interface FramesView {
  /** Видеофайл. */
  videofileId: number
  /** Сколько значимых кадров у видеофайла. */
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
  /** Видеофайл. */
  videofileId: number
  /** Состояние задания на момент постановки. */
  state: string
  /** Порог границы сцены. */
  sceneThreshold: number
  /** Порог границы плана. */
  shotThreshold: number
  /** Хеш входов задания. */
  paramsHash: string
  /** Число кадров видеофайла. */
  frameCount: number
  /** Сколько листов превью у видеофайла будет. */
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
  /** Видеофайл. */
  videofileId: number
  /** Номер листа, с нуля. */
  index: number
  /** Первый кадр листа. */
  firstFrame: number
  /** Последний кадр листа. */
  lastFrame: number
  /** Сколько кадров на листе. */
  frameNumbers: number
  /** Сколько листов превью у видеофайла всего. */
  sheetCount: number
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
 * Ответ на правку границы сцены.
 *
 * Ответ несёт изменённый участок, а не ссылку «перечитайте всё»: перечитывать
 * весь видеофайл из-за двух сцен — это мегабайты ради двух строк, а оператор
 * после правки должен видеть результат немедленно.
 */
export interface SceneBoundaryView {
  /** Видеофайл. */
  videofileId: number
  /** Кадр, на котором теперь стоит граница. */
  frame: number
  /** Вид операции: `MOVE`, `SPLIT` или `MERGE`. */
  action: string
  /** Вид операции словами. */
  actionTitle: string
  /** Рабочие сцены затронутого участка после операции. */
  scenes: SceneView[]
  /** Строки, выведенные из рабочей структуры. */
  supersededSceneIds: number[]
  /** Сколько рабочих сцен у видеофайла после операции. */
  scenesTotal: number
  /** Сколько планов у видеофайла. */
  shotsTotal: number
  /** Число кадров видеофайла. */
  frameCount: number
}

/**
 * Ставит видеофайл на анализ структуры.
 *
 * Работа идёт заданием очереди и занимает минуты, поэтому ответ приходит
 * сразу: кнопка, ждущая окончания, в интерфейсе недопустима (FR-003).
 *
 * @param videofileId идентификатор видеофайла
 * @returns поставленное задание
 */
export function startAnalysis(videofileId: number): Promise<AnalysisEnqueuedView> {
  return request<AnalysisEnqueuedView>('POST', `/videofiles/${videofileId}/analysis`)
}

/**
 * Читает структуру видеофайла.
 *
 * @param videofileId идентификатор видеофайла
 * @param offset смещение выборки сцен
 * @param limit размер выборки сцен
 * @returns страница структуры
 */
export function readStructure(
  videofileId: number,
  offset = 0,
  limit = 200,
): Promise<StructureView> {
  return request<StructureView>(
    'GET',
    `/videofiles/${videofileId}/structure?offset=${offset}&limit=${limit}`,
  )
}

/**
 * Читает сырые границы результата автоматики.
 *
 * @param videofileId идентификатор видеофайла
 * @param level уровень границ: `SCENE`, `SHOT` или оба
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница сырых границ
 */
export function readRawBoundaries(
  videofileId: number,
  level?: string,
  offset = 0,
  limit = 200,
): Promise<RawBoundariesView> {
  const levelQuery = level === undefined ? '' : `&level=${level}`
  return request<RawBoundariesView>(
    'GET',
    `/videofiles/${videofileId}/raw-boundaries?offset=${offset}&limit=${limit}${levelQuery}`,
  )
}

/**
 * Читает страницу значимых кадров видеофайла.
 *
 * @param videofileId идентификатор видеофайла
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница значимых кадров
 */
export function readFrames(videofileId: number, offset = 0, limit = 200): Promise<FramesView> {
  return request<FramesView>(
    'GET',
    `/videofiles/${videofileId}/frames?offset=${offset}&limit=${limit}`,
  )
}

/**
 * Читает адрес листа превью и область кадрирования кадра.
 *
 * Лист задаётся **номером** либо **кадром** — как и на сервере: номер нужен
 * навигации по листам, а кадр — переходу к кадру. Ничего не подставлять по
 * умолчанию нельзя: молчаливый нулевой лист показывал бы первый вместо
 * запрошенного, а молчаливо заданный нулевой индекс при кадре с другого листа
 * увёл бы сервер не туда, и область кадрирования не пришла бы вовсе.
 *
 * @param videofileId идентификатор видеофайла
 * @param index номер листа, с нуля; без него лист определяется по кадру
 * @param frame кадр, для которого нужна область кадрирования
 * @returns описание листа превью
 */
export function readPreviewUrl(
  videofileId: number,
  index?: number,
  frame?: number,
): Promise<PreviewUrlView> {
  const parts: string[] = []
  if (index !== undefined) {
    parts.push(`index=${index}`)
  }
  if (frame !== undefined) {
    parts.push(`frame=${frame}`)
  }
  return request<PreviewUrlView>('GET', `/videofiles/${videofileId}/preview-url?${parts.join('&')}`)
}

/**
 * Сдвигает границу между двумя соседними сценами.
 *
 * Границы передаются номерами кадров, а не идентификаторами сцен: оператор
 * видит кадры, и требовать от него знания внутренних ключей незачем (ADR-0001).
 *
 * @param videofileId идентификатор видеофайла
 * @param fromFrame кадр, на котором граница стоит сейчас
 * @param toFrame кадр, на который её ставят
 * @returns изменённый участок структуры
 */
export function moveSceneBoundary(
  videofileId: number,
  fromFrame: number,
  toFrame: number,
): Promise<SceneBoundaryView> {
  return request<SceneBoundaryView>('POST', `/videofiles/${videofileId}/scenes/boundary/move`, {
    fromFrame,
    toFrame,
  })
}

/**
 * Разделяет сцену по номеру кадра.
 *
 * @param videofileId идентификатор видеофайла
 * @param frame первый кадр второй из получившихся сцен
 * @returns изменённый участок структуры
 */
export function splitScene(videofileId: number, frame: number): Promise<SceneBoundaryView> {
  return request<SceneBoundaryView>('POST', `/videofiles/${videofileId}/scenes/${frame}/split`)
}

/**
 * Объединяет сцену, начинающуюся с указанного кадра, с предыдущей.
 *
 * @param videofileId идентификатор видеофайла
 * @param frame первый кадр поглощаемой сцены
 * @returns изменённый участок структуры
 */
export function mergeScenes(videofileId: number, frame: number): Promise<SceneBoundaryView> {
  return request<SceneBoundaryView>('POST', `/videofiles/${videofileId}/scenes/${frame}/merge`)
}

/**
 * Ответ на правку границы плана.
 *
 * Планы едут с уже пересчитанным размером: пересчёт выполняется в той же
 * операции, что и правка границы, и перечитывать структуру ради размера —
 * значит показывать оператору устаревшее значение.
 *
 * @interface ShotBoundaryView
 */
export interface ShotBoundaryView {
  /** Видеофайл. */
  videofileId: number
  /** Кадр, по которому выполнена операция. */
  frame: number
  /** Вид операции: `MOVE`, `SPLIT` или `MERGE`. */
  action: string
  /** Вид операции словами для оператора. */
  actionTitle: string
  /** Рабочие планы затронутого участка после операции. */
  shots: ShotView[]
  /** Строки планов, выведенные из рабочей структуры. */
  supersededShotIds: number[]
  /** Сцены, в которые легли затронутые планы. */
  scenes: SceneView[]
  /** Сколько строк лица переведено на новые планы. */
  facesRebound: number
  /** Сколько планов получило пересчитанный размер. */
  sizesRecomputed: number
  /** Сколько планов у видеофайла после операции. */
  shotsTotal: number
  /** Число кадров видеофайла. */
  frameCount: number
}

/**
 * Сдвигает границу между двумя соседними планами.
 *
 * Границы передаются номерами кадров, а не идентификаторами планов: оператор
 * видит кадры, и требовать от него знания внутренних ключей незачем (ADR-0001).
 *
 * @param videofileId идентификатор видеофайла
 * @param fromFrame кадр, на котором граница стоит сейчас
 * @param toFrame кадр, на который её ставят
 * @returns изменённый участок структуры с пересчитанными размерами
 */
export function moveShotBoundary(
  videofileId: number,
  fromFrame: number,
  toFrame: number,
): Promise<ShotBoundaryView> {
  return request<ShotBoundaryView>('POST', `/videofiles/${videofileId}/shots/boundary/move`, {
    fromFrame,
    toFrame,
  })
}

/**
 * Разделяет план по номеру кадра.
 *
 * @param videofileId идентификатор видеофайла
 * @param frame первый кадр второго из получившихся планов
 * @returns изменённый участок структуры с пересчитанными размерами
 */
export function splitShot(videofileId: number, frame: number): Promise<ShotBoundaryView> {
  return request<ShotBoundaryView>('POST', `/videofiles/${videofileId}/shots/${frame}/split`)
}

/**
 * Объединяет план, начинающийся с указанного кадра, с предыдущим.
 *
 * @param videofileId идентификатор видеофайла
 * @param frame первый кадр поглощаемого плана
 * @returns изменённый участок структуры с пересчитанными размерами
 */
export function mergeShots(videofileId: number, frame: number): Promise<ShotBoundaryView> {
  return request<ShotBoundaryView>('POST', `/videofiles/${videofileId}/shots/${frame}/merge`)
}

/**
 * Адрес листа превью для прямой загрузки изображением.
 *
 * Отдельная функция вместо строки в шаблоне: адрес собирается в одном месте,
 * и переименование пути не потребует правок по экрану.
 *
 * @param videofileId идентификатор видеофайла
 * @param index номер листа, с нуля
 * @returns адрес листа
 */
export function previewSheetUrl(videofileId: number, index: number): string {
  return `/api/videofiles/${videofileId}/preview-sheets/${index}`
}
