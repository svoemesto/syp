// Клиент состояния заданий очереди.
//
// Прогресс задания виден оператору в шапке, поэтому клиент должен отдавать
// готовое к показу состояние, а не сырые поля задания: «считает сумму» — это
// то, что нужно видеть, а не «JobKind.HASH в состоянии WORKING».
//
// Соответствует разделу 5 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`.
import { request } from './http'

/** Состояние задания в терминах домена. */
export type JobState = 'CREATING' | 'WAITING' | 'WORKING' | 'DONE' | 'ERROR'

/** Состояния, при которых задание ещё не закончилось. */
export const ACTIVE_JOB_STATES: JobState[] = ['CREATING', 'WAITING', 'WORKING']

/** Задание очереди в том виде, в каком его видит интерфейс. */
export interface JobView {
  /** Номер задания. */
  id: number
  /** Вид задания. */
  kind: string
  /** Состояние задания. */
  state: JobState
  /** Вид объекта задания. */
  subjectType: string | null
  /** Номер объекта задания. */
  subjectId: number | null
  /** Сколько сделано. */
  progressDone: number
  /** Сколько всего, если известно заранее. */
  progressTotal: number | null
  /** Итог заранее неизвестен: полоса прогресса не имеет смысла. */
  progressUnknown: boolean
  /** Пояснение хода работы, например «найдено 817 сцен». */
  progressNote: string
  /** Процент выполнения, если известен. */
  progressPercent: number | null
  /** Текст ошибки, если задание упало. */
  errorText: string | null
  /** Момент постановки. */
  createdAt: string | null
  /** Момент начала работы. */
  startedAt: string | null
  /** Момент завершения. */
  finishedAt: string | null
}

/**
 * Строка состояния для показа оператору.
 *
 * Порядок проверок важен: сначала само состояние, потом ошибка, и только потом
 * прогресс. Иначе упавшее задание с частичным счётчиком выглядело бы как
 * идущее.
 */
export function jobHeadline(job: JobView): string {
  if (job.state === 'ERROR') {
    return 'Задание упало'
  }
  if (job.state === 'DONE') {
    return 'Задание выполнено'
  }
  if (job.state === 'WAITING') {
    return 'Задание ждёт очереди'
  }
  return jobTitle(job.kind)
}

/** Короткая строка прогресса: счётчик кадров либо пометка, что итог неизвестен. */
export function jobProgressText(job: JobView): string {
  if (job.progressTotal === null) {
    return job.progressNote !== '' ? job.progressNote : 'ход работы'
  }
  const total = job.progressTotal
  return `${job.progressDone.toLocaleString('ru-RU')} / ${total.toLocaleString('ru-RU')}`
}

/** Название вида задания в понятном оператору виде. */
export function jobTitle(kind: string): string {
  switch (kind) {
    case 'HASH':
      return 'Считает сумму серии'
    case 'ANALYZE':
      return 'Разбирает структуру серии'
    case 'FACES':
      return 'Ищет лица в серии'
    default:
      return kind
  }
}

/**
 * Загружает задания очереди.
 *
 * @param states состояния для фильтра; по умолчанию — все незавершённые
 * @returns задания в порядке постановки
 */
export async function fetchJobs(states?: JobState[]): Promise<JobView[]> {
  const query = states && states.length > 0 ? `?states=${states.join('&states=')}` : ''
  return request<JobView[]>('GET', `/jobs${query}`)
}
