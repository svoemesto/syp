// Состояние конвейера обработки видеофайла.
//
// Экран операций показывает по файлу семнадцать колонок: порядковый номер,
// имя и пятнадцать индикаторов «сделано или нет». Пятнадцать операций
// вынесены в чекбоксы и повторяют порядок колонок — в старом проекте это
// один и тот же список.
//
// Значение индикатора не выдумывается: оно выводится из того, что сервер
// действительно знает. Четыре состояния, а не два, потому что «не сделано»
// и «делать нечем» — это разные вещи, и сводить их к одному «нет» нельзя:
// оператор решит, что у него сломался конвейер.

import { readClusters, readFaces } from './characters'
import { readFrames, readPreviewUrl, readStructure } from './structure'

/**
 * Состояние одной колонки-индикатора.
 *
 * - `yes` — результат есть;
 * - `no` — результата нет, операцию можно повторить;
 * - `absent` — подсистемы в проекте нет, повтор не поможет;
 * - `unknown` — сервер не смог ответить, и догадываться нельзя.
 */
export type PipelineState = 'yes' | 'no' | 'absent' | 'unknown'

/** Одна колонка-индикатор. */
export interface Indicator {
  /** Буквенный код колонки, как в старом проекте. */
  readonly code: string
  /** Что означает колонка по-русски. */
  readonly title: string
}

/**
 * Семнадцать колонок таблицы файлов.
 *
 * Первые две — номер и имя; остальные пятнадцать — индикаторы, по одному на
 * каждую операцию конвейера.
 */
export const INDICATORS: readonly Indicator[] = [
  { code: '#', title: '#' },
  { code: 'Файл', title: 'Файл' },
  { code: 'PW', title: 'PW' },
  { code: 'LL', title: 'LL' },
  { code: 'FS', title: 'FS' },
  { code: 'FM', title: 'FM' },
  { code: 'FF', title: 'FF' },
  { code: 'AF', title: 'AF' },
  { code: 'CS', title: 'CS' },
  { code: 'DF', title: 'DF' },
  { code: 'CF', title: 'CF' },
  { code: 'CFP', title: 'CFP' },
  { code: 'RF', title: 'RF' },
  { code: 'SCA', title: 'SCA' },
  { code: 'SLA', title: 'SLA' },
  { code: 'SLN', title: 'SLN' },
  { code: 'CC', title: 'CC' },
]

/**
 * Подсистемы, которых в проекте нет.
 *
 * Список назван прямо, а не оставлен пустым: иначе «нет» в колонке читалось
 * бы как «ещё не сделано», и оператор ждал бы результата, которого не
 * существует. У каждой колонки причина одна и та же — соответствующей
 * подсистемы в проекте нет, это известно из состава бэкенда, а не из
 * неудачного запроса.
 */
const NO_SUBSYSTEM: Readonly<Record<string, string>> = {
  LL: 'копии lossless в проекте нет',
  FS: 'кадров трёх размеров в проекте нет',
  FM: 'кадров трёх размеров в проекте нет',
  FF: 'кадров трёх размеров в проекте нет',
  CF: 'вырезания лиц в файлы в проекте нет',
  CFP: 'no separate face previews in the project: the preview is taken from the frame sheet',
  SCA: 'нарезки видео планов в проекте нет',
  SLA: 'нарезки видео планов в проекте нет',
  SLN: 'нарезки видео планов в проекте нет',
  CC: 'the project has no video concatenation',
}

/** Состояние конвейера по одному видеофайлу. */
export interface PipelineRow {
  /** Видеофайл. */
  readonly videofileId: number
  /** Порядковый номер файла, как его отдаёт сервер. */
  readonly ordinal: number
  /** Имя файла. */
  readonly name: string
  /** Состояние по каждой колонке-индикатору. */
  readonly states: Readonly<Record<string, PipelineState>>
  /** Чем обосновано состояние, отличное от «сделано». */
  readonly reasons: Readonly<Record<string, string>>
  /** Сколько значащих кадров у файла; `null`, если сервер не ответил. */
  readonly framesTotal: number | null
}

/** Одна операция конвейера с чекбоксом. */
export interface Operation {
  /** Код индикатора, которому операция соответствует. */
  readonly code: string
  /** Подпись чекбокса. */
  readonly title: string
  /** Чем операция кончается сейчас. */
  readonly effect: string
  /** Запускает ли операция the job сервера. */
  readonly runs: boolean
}

/**
 * Пятнадцать операций в порядке конвейера.
 *
 * Подписи сохранены по старому проекту, включая требования «нужен LL!!!»:
 * оператор привык к ним, а требования проверяются на самом деле — иначе
 * нажатие на «нарезать видео» давало бы отказ после долгой работы.
 */
export const OPERATIONS: readonly Operation[] = [
  {
    code: 'PW',
    title: '[PW] Create preview',
    effect: 'part of the job Structure analysis: there is no separate job',
    runs: false,
  },
  {
    code: 'LL',
    title: '[LL] Create lossless',
    effect: 'no subsystem: copying the source is not performed in the project',
    runs: false,
  },
  {
    code: 'FS',
    title: '[FS] Create frames small',
    effect: 'no subsystem: the three frame sizes are not created in the project',
    runs: false,
  },
  {
    code: 'FM',
    title: '[FM] Create frames medium',
    effect: 'no subsystem: the three frame sizes are not created in the project',
    runs: false,
  },
  {
    code: 'FF',
    title: '[FF] Create frames full',
    effect: 'no subsystem: the three frame sizes are not created in the project',
    runs: false,
  },
  {
    code: 'AF',
    title: '[AF] Analyze frames',
    effect: 'the job Structure analysis: preview, frames, shot and scene boundaries',
    runs: true,
  },
  {
    code: 'CS',
    title: '[CS] Create shots',
    effect: 'part of the job Structure analysis: there is no separate job',
    runs: false,
  },
  {
    code: 'DF',
    title: '[DF] Detect faces',
    effect: 'the job Faces: detection, vector extraction and preview',
    runs: true,
  },
  {
    code: 'CF',
    title: '[CF] Create faces',
    effect: 'no subsystem: faces are kept in the database, not cut out as separate files',
    runs: false,
  },
  {
    code: 'CFP',
    title: '[CFP] Create faces preview',
    effect: 'part of the job Faces: the preview is taken from the frame sheet',
    runs: false,
  },
  {
    code: 'RF',
    title: '[RF] Recognize faces',
    effect: 'no subsystem: the project has no trained model',
    runs: false,
  },
  {
    code: 'SCA',
    title: '[SCA] Create shots video files (compressed, with audio) - need LL!!!',
    effect: 'no subsystem: the project has no video cutting',
    runs: false,
  },
  {
    code: 'SLA',
    title: '[SLA] Create shots video files (lossless mxf, with audio) - need LL!!!',
    effect: 'no subsystem: the project has no video cutting',
    runs: false,
  },
  {
    code: 'SLN',
    title: '[SLN] Create shots video files (lossless mxf, without audio) - need LL!!!',
    effect: 'no subsystem: the project has no video cutting',
    runs: false,
  },
  {
    code: 'CC',
    title: '[CC] Create concatinated video file - need SLA!!!',
    effect: 'no subsystem: the project has no video concatenation',
    runs: false,
  },
]

/** Пометка значения индикатора в ячейке. */
export const STATE_MARK: Readonly<Record<PipelineState, string>> = {
  yes: 'да',
  no: 'нет',
  absent: '—',
  unknown: '?',
}

/** Подпись состояния словами — для подсказки под ячейкой. */
export const STATE_TITLE: Readonly<Record<PipelineState, string>> = {
  yes: 'сделано',
  no: 'не сделано',
  absent: 'подсистемы в проекте нет',
  unknown: 'сервер не ответил: состояние неизвестно',
}

/**
 * Собирает состояние конвейера по видеофайлу из настоящих ответов сервера.
 *
 * Каждый индикатор выводится своим запросом, и отказ одного запроса не
 * обнуляет остальные: вместо молчаливых нулей в колонке появляется «?».
 *
 * @param videofileId видеофайл
 * @param ordinal порядковый номер файла
 * @param name имя файла
 * @returns строка состояния конвейера
 */
export async function readPipeline(
  videofileId: number,
  ordinal: number,
  name: string,
): Promise<PipelineRow> {
  const states: Record<string, PipelineState> = {}
  const reasons: Record<string, string> = { ...NO_SUBSYSTEM }
  Object.keys(NO_SUBSYSTEM).forEach((code) => {
    states[code] = 'absent'
  })

  const [preview, structure, frames, faces, clusters] = await Promise.all([
    readPreviewUrl(videofileId).catch(() => null),
    readStructure(videofileId, 0, 1).catch(() => null),
    readFrames(videofileId, 0, 1).catch(() => null),
    readFaces(videofileId, 0, 1).catch(() => null),
    readClusters(videofileId).catch(() => null),
  ])

  states.PW = preview === null ? 'unknown' : preview.isReady ? 'yes' : 'no'
  states.AF = structure === null ? 'unknown' : structure.frameCount > 0 ? 'yes' : 'no'
  states.CS = structure === null ? 'unknown' : structure.scenesTotal > 0 ? 'yes' : 'no'
  states.DF = faces === null ? 'unknown' : faces.facesTotal > 0 ? 'yes' : 'no'
  states.RF = clusters === null ? 'unknown' : clusters.facesNamed > 0 ? 'yes' : 'no'
  // Число кадров показывается в подсказке индикатора «проанализировано»:
  // без него «да» не отвечает на вопрос «а много ли их». Само число в ячейку
  // не влезает, поэтому при нуле кадров причина называется прямо.
  if (frames === null && structure !== null && structure.frameCount === 0) {
    reasons.AF = 'кадров нет: анализ не на чем было выполнять'
  }
  return { videofileId, ordinal, name, states, reasons, framesTotal: frames?.total ?? null }
}
