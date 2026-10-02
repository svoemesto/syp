// Клиент публичной части: сериалы и сценарии сборки.
//
// Соответствует разделам 3 и 4 контракта
// `specs/001-first-vertical-slice/contracts/public-api.md`.
//
// Здесь нет и не будет создания сценария: генерация и подпись выполняются в
// админском бэкенде, где живёт закрытый ключ (ADR-0014). Публичная часть
// читает уже подписанное и хранимое.

import { request } from './http'

/** Сериал с размеченными данными. */
export interface SerialView {
  /** Идентификатор сериала. */
  id: number
  /** Название сериала. */
  name: string
  /** Сколько серий заведено. */
  seriesCount: number
}

/** Сценарий в списке. */
export interface RecipeSummaryView {
  /** Идентификатор сценария. */
  id: number
  /** Идентификатор сериала-владельца. */
  serialId?: number
  /** Название сценария. */
  name: string
  /** Состояние сценария. */
  state: string
  /** Версия формата сценария. */
  schemaVersion?: number
  /** Помечен ли сценарий устаревшим. */
  isStale?: boolean
  /** Чем сценарий устарел. */
  staleReason?: string | null
  /** Идентификатор ключа подписи. */
  signingKeyId?: string | null
  /** Сколько фрагментов в сценарии. */
  itemCount: number
  /** Ожидаемая длительность в миллисекундах. */
  expectedDurationMs?: number | null
}

/** Один фрагмент сценария. */
export interface RecipeItemView {
  /** Порядковый номер фрагмента. */
  ordinal: number
  /** Серия-источник. */
  seriesId?: number
  /** Название серии. */
  seriesName?: string
  /** Относительный путь к файлу внутри копии сериала. */
  relativePath?: string
  /** Сумма источника. */
  sourceSha256?: string | null
  /** Первый кадр фрагмента по расчёту. */
  firstFrame?: number
  /** Последний кадр фрагмента по расчёту. */
  lastFrame?: number
  /** Первый кадр фактической нарезки. */
  cutFirstFrame?: number | null
  /** Последний кадр фактической нарезки. */
  cutLastFrame?: number | null
  /** Название сцены; может быть пустым. */
  title?: string | null
  /** Место действия сцены. */
  location?: string | null
  /** Персоны сцены. */
  persons?: string[]
}

/** Состав сценария. */
export interface RecipeCompositionView {
  /** Идентификатор сценария. */
  recipeId: number
  /** Название сценария. */
  name?: string
  /** Состояние сценария. */
  state: string
  /** Версия формата сценария. */
  schemaVersion?: number
  /** Помечен ли сценарий устаревшим. */
  isStale?: boolean
  /** Чем сценарий устарел. */
  staleReason?: string | null
  /** Идентификатор ключа подписи. */
  signingKeyId?: string | null
  /** Сумма содержимого файла сценария. */
  contentSha256?: string | null
  /** Сколько фрагментов. */
  itemCount: number
  /** Ожидаемая длительность в миллисекундах. */
  expectedDurationMs?: number | null
  /** Ожидаемое число кадров. */
  expectedFrameCount?: number | null
  /** Фрагменты сценария. */
  items: RecipeItemView[]
}

/** Подпись сценария и сумма содержимого. */
export interface RecipeSignatureView {
  /** Идентификатор сценария. */
  recipeId: number
  /** Сумма содержимого файла сценария. */
  contentSha256: string
  /** Алгоритм подписи. */
  algorithm: string
  /** Идентификатор ключа подписи. */
  signingKeyId: string
  /** Значение подписи. */
  signature: string
}

/** Открытый ключ проверки подписи. */
export interface VerificationKeyView {
  /** Идентификатор ключа. */
  keyId: string
  /** Алгоритм подписи. */
  algorithm: string
  /** Открытый ключ в PEM: его читает `openssl` на машине пользователя. */
  publicKeyPem: string
  /** С какого момента ключ доверенный. */
  notBefore?: string | null
}

/**
 * Перечисляет сериалы с размеченными данными.
 *
 * @returns сериалы, у которых есть размеченные сцены
 */
export function listSerials(): Promise<SerialView[]> {
  return request<SerialView[]>('GET', '/serials')
}

/**
 * Перечисляет сценарии сериала, свежие сверху.
 *
 * @param serialId сериал
 * @returns сценарии сериала
 */
export function listRecipes(serialId: number): Promise<RecipeSummaryView[]> {
  return request<RecipeSummaryView[]>('GET', `/recipes?serialId=${serialId}`)
}

/**
 * Читает состав сценария: что войдёт в подборку.
 *
 * @param recipeId идентификатор сценария
 * @returns состав сценария с обеими парами границ фрагментов
 */
export function readRecipe(recipeId: number): Promise<RecipeCompositionView> {
  return request<RecipeCompositionView>('GET', `/recipes/${recipeId}`)
}

/**
 * Читает подпись сценария отдельно от файла.
 *
 * Подпись хранится вне подписываемого файла намеренно: иначе пришлось бы
 * подписывать «файл с местом для подписи», и определение «что подписано»
 * размылось бы (ADR-0014).
 *
 * @param recipeId идентификатор сценария
 * @returns подпись, сумма содержимого и идентификатор ключа
 */
export function readSignature(recipeId: number): Promise<RecipeSignatureView> {
  return request<RecipeSignatureView>('GET', `/recipes/${recipeId}/signature`)
}

/**
 * Читает открытый ключ проверки подписи.
 *
 * @returns открытый ключ с идентификатором и моментом начала его действия
 */
export function readVerificationKey(): Promise<VerificationKeyView> {
  return request<VerificationKeyView>('GET', '/recipes/verification-key')
}

/**
 * Адрес файла сценария для сохранения браузером.
 *
 * Отдельная функция вместо строки в шаблоне: адрес собирается в одном месте,
 * и переименование пути не потребует правок по экрану. Отдаётся только
 * артефакт в состоянии `READY`; незавершённый сценарий придёт отказом с кодом
 * `CONFLICT` (FR-091).
 *
 * @param recipeId идентификатор сценария
 * @returns адрес файла сценария
 */
export function recipeFileUrl(recipeId: number): string {
  return `/api/recipes/${recipeId}/file`
}
