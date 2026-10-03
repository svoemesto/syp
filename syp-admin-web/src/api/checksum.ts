// Клиент сверки целостности исходника.
//
// Соответствует разделу 3.3 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Сценарий сборки
// выдаётся публичной частью и только при актуальной сумме, поэтому админка
// показывает состояние подсчёта, а не «есть сумма — да, нет — нет».

import { request } from './http'

/** Состояние записи справочника сумм. */
export type ChecksumState = 'CREATING' | 'WORKING' | 'DONE' | 'ERROR'

/** Состояние суммы видеофайла в ответе. */
export interface ChecksumView {
  /** Идентификатор видеофайла. */
  videofileId: number
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
  /** Сколько записей пересчётов у видеофайла всего. */
  historyCount: number
  /** Можно ли поставить пересчёт прямо сейчас. */
  canRecalculate: boolean
}

/** Ответ на постановку пересчёта. */
export interface ChecksumEnqueuedView {
  /** Идентификатор поставленного задания. */
  jobId: number
  /** Видеофайл, для которой считается сумма. */
  videofileId: number
  /** Состояние задания на момент постановки. */
  state: string
  /** Зачем поставлен пересчёт. */
  reason: string
}

/**
 * Читает состояние суммы видеофайла.
 *
 * Отказ `CHECKSUM_NOT_READY` означает, что сумму не считали ни разу: это не
 * ошибка экрана, а его обычное состояние, и интерфейс показывает «поставьте
 * пересчёт» вместо пустой страницы.
 *
 * @param videofileId идентификатор видеофайла
 * @returns состояние суммы видеофайла
 */
export function readChecksum(videofileId: number): Promise<ChecksumView> {
  return request<ChecksumView>('GET', `/videofiles/${videofileId}/checksum`)
}

/**
 * Ставит пересчёт суммы видеофайла.
 *
 * Работа идёт заданием: чтение 5,6 ГБ не должно держать соединение
 * интерфейса (constitution IV.1, FR-003).
 *
 * @param videofileId идентификатор видеофайла
 * @returns поставленное задание
 */
export function startChecksum(videofileId: number): Promise<ChecksumEnqueuedView> {
  return request<ChecksumEnqueuedView>('POST', `/videofiles/${videofileId}/checksum`)
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
