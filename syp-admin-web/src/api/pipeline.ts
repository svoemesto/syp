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
  { code: '#', title: 'Порядок файла' },
  { code: 'Файл', title: 'Имя файла' },
  { code: 'PW', title: 'Превью создано' },
  { code: 'LL', title: 'Копия lossless создана' },
  { code: 'FS', title: 'Кадры 175×35 созданы' },
  { code: 'FM', title: 'Кадры 720×400 созданы' },
  { code: 'FF', title: 'Кадры 1920×1080 созданы' },
  { code: 'AF', title: 'Кадры проанализированы' },
  { code: 'CS', title: 'Планы созданы' },
  { code: 'DF', title: 'Лица обнаружены' },
  { code: 'CF', title: 'Лица вырезаны в файлы' },
  { code: 'CFP', title: 'Превью лиц созданы' },
  { code: 'RF', title: 'Лица распознаны' },
  { code: 'SCA', title: 'Видео планов сжатое, со звуком' },
  { code: 'SLA', title: 'Видео планов lossless, со звуком' },
  { code: 'SLN', title: 'Видео планов lossless, без звука' },
  { code: 'CC', title: 'Сконкатенированный файл' },
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
  CFP: 'отдельных превью лиц в проекте нет: превью берётся из листа кадра',
  SCA: 'нарезки видео планов в проекте нет',
  SLA: 'нарезки видео планов в проекте нет',
  SLN: 'нарезки видео планов в проекте нет',
  CC: 'склейки видео в проекте нет',
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
  /** Запускает ли операция задание сервера. */
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
    title: '[PW] Создать превью',
    effect: 'входит в задание «Анализ структуры»: отдельного задания нет',
    runs: false,
  },
  {
    code: 'LL',
    title: '[LL] Создать копию lossless',
    effect: 'подсистемы нет: копирование исходника в проекте не выполняется',
    runs: false,
  },
  {
    code: 'FS',
    title: '[FS] Создать кадры малые',
    effect: 'подсистемы нет: кадры трёх размеров в проекте не создаются',
    runs: false,
  },
  {
    code: 'FM',
    title: '[FM] Создать кадры средние',
    effect: 'подсистемы нет: кадры трёх размеров в проекте не создаются',
    runs: false,
  },
  {
    code: 'FF',
    title: '[FF] Создать кадры полные',
    effect: 'подсистемы нет: кадры трёх размеров в проекте не создаются',
    runs: false,
  },
  {
    code: 'AF',
    title: '[AF] Проанализировать кадры',
    effect: 'задание «Анализ структуры»: превью, кадры, границы планов и сцен',
    runs: true,
  },
  {
    code: 'CS',
    title: '[CS] Создать планы',
    effect: 'входит в задание «Анализ структуры»: отдельного задания нет',
    runs: false,
  },
  {
    code: 'DF',
    title: '[DF] Найти лица',
    effect: 'задание «Лица»: детекция, выделение векторов и превью',
    runs: true,
  },
  {
    code: 'CF',
    title: '[CF] Вырезать лица в файлы',
    effect: 'подсистемы нет: лица хранятся в базе, отдельными файлами не вырезаются',
    runs: false,
  },
  {
    code: 'CFP',
    title: '[CFP] Создать превью лиц',
    effect: 'входит в задание «Лица»: превью берётся из листа кадра',
    runs: false,
  },
  {
    code: 'RF',
    title: '[RF] Распознать лица',
    effect: 'подсистемы нет: обученной модели в проекте нет',
    runs: false,
  },
  {
    code: 'SCA',
    title: '[SCA] Создать видео планов (сжатое, со звуком) — нужен LL!!!',
    effect: 'подсистемы нет: нарезки видео в проекте нет',
    runs: false,
  },
  {
    code: 'SLA',
    title: '[SLA] Создать видео планов (lossless, со звуком) — нужен LL!!!',
    effect: 'подсистемы нет: нарезки видео в проекте нет',
    runs: false,
  },
  {
    code: 'SLN',
    title: '[SLN] Создать видео планов (lossless, без звука) — нужен LL!!!',
    effect: 'подсистемы нет: нарезки видео в проекте нет',
    runs: false,
  },
  {
    code: 'CC',
    title: '[CC] Создать склеенный видеофайл — нужен SLA!!!',
    effect: 'подсистемы нет: склейки видео в проекте нет',
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
