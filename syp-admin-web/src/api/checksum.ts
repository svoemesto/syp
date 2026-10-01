// Клиент сверки целостности исходника.
//
// Соответствует разделу 3.3 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Сценарий сборки
// выдаётся публичной частью и только при актуальной сумме, поэтому админка
// показывает состояние подсчёта, а не «есть сумма — да, нет — нет».

import { request } from './http'

/** Состояние записи справочника сумм. */
export type ChecksumState = 'CREATING' | 'WORKING' | 'DONE' | 'ERROR'

/** Состояние суммы серии в ответе. */
export interface ChecksumView {
  /** Идентификатор серии. */
  seriesId: number
  /** Состояние подсчёта. */
  state: ChecksumState
  /** Алгоритм подсчёта; в модели только `SHA-256`. */
  algorithm: string
  /** Значение суммы; пусто, пока подсчёт не завершён. */
  digest: string | null
  /** Размер файла, на котором считали. */
  byteSize: number
  /** Время изменения файла, на котором считали. */
  fileMtime: string
  /** Когда сумма посчитана. */
  computedAt: string | null
  /** Файл изменился после подсчёта: сумма устарела. */
  isStale: boolean
  /** Пригодна ли сумма для сверки на машине пользователя. */
  isUsable: boolean
  /** Текст ошибки при сбое подсчёта. */
  errorText: string | null
  /** Задание, считающее или посчитавшее сумму. */
  jobId: number | null
  /** Сколько записей пересчётов у серии всего. */
  historyCount: number
  /** Можно ли поставить пересчёт прямо сейчас. */
  canRecalculate: boolean
}

/** Ответ на постановку пересчёта. */
export interface ChecksumEnqueuedView {
  /** Идентификатор поставленного задания. */
  jobId: number
  /** Серия, для которой считается сумма. */
  seriesId: number
  /** Состояние задания на момент постановки. */
  state: string
  /** Зачем поставлен пересчёт. */
  reason: string
}

/**
 * Читает состояние суммы серии.
 *
 * Отказ `CHECKSUM_NOT_READY` означает, что сумму не считали ни разу: это не
 * ошибка экрана, а его обычное состояние, и интерфейс показывает «поставьте
 * пересчёт» вместо пустой страницы.
 *
 * @param seriesId идентификатор серии
 * @returns состояние суммы серии
 */
export function readChecksum(seriesId: number): Promise<ChecksumView> {
  return request<ChecksumView>('GET', `/series/${seriesId}/checksum`)
}

/**
 * Ставит пересчёт суммы серии.
 *
 * Работа идёт заданием: чтение 5,6 ГБ не должно держать соединение
 * интерфейса (constitution IV.1, FR-003).
 *
 * @param seriesId идентификатор серии
 * @returns поставленное задание
 */
export function startChecksum(seriesId: number): Promise<ChecksumEnqueuedView> {
  return request<ChecksumEnqueuedView>('POST', `/series/${seriesId}/checksum`)
}

/**
 * Форматирует значение суммы для показа: 64 символа не помещаются в строку
 * таблицы, а оператору нужно узнать сумму целиком, чтобы сверить её на
 * своей машине.
 *
 * @param digest значение суммы либо `null`
 * @returns сумма с разбивкой на блоки по 16 символов
 */
export function formatDigest(digest: string | null): string {
  if (digest === null) {
    return '—'
  }
  return digest.replace(/(.{16})/g, '$1 ').trim()
}
