// Представление ответов публичного API для экранов.
//
// Правило файла то же, что в админке (`syp-admin-web/src/api/view-model.ts`):
// ни один компонент в `views/` и `components/` не обращается к полям ответа
// напрямую. Переименование `Serial` → `Movie` и `Series` → `Episode` идёт
// параллельно в ветке `037-rename`, и когда придёт, правка будет здесь, а не в
// каждом шаблоне.
//
// Формат ответов — контракт `specs/001-first-vertical-slice/contracts/public-api.md`.

import { formatDay, formatMilliseconds, formatNumber } from '../format/values'
import type {
  RecipeCompositionView,
  RecipeItemView,
  RecipeSignatureView,
  RecipeSummaryView,
  SerialView,
  VerificationKeyView,
} from './recipes'

/** Сериал на экране выбора. */
export interface SerialOption {
  /** Идентификатор сериала. */
  id: number
  /** Название сериала. */
  name: string
  /** Сколько серий заведено. */
  seriesCount: string
}

/** Сценарий в списке на экране. */
export interface RecipeCard {
  /** Идентификатор сценария. */
  id: number
  /** Название сценария. */
  name: string
  /** Состояние сценария словами. */
  stateTitle: string
  /** Тон состояния. */
  stateTone: 'success' | 'info' | 'warning' | 'secondary'
  /** Готов ли сценарий к выдаче. */
  isReady: boolean
  /** Помечен ли сценарий устаревшим. */
  isStale: boolean
  /** Чем сценарий устарел либо `null`. */
  staleReason: string | null
  /** Число фрагментов словами. */
  itemCount: string
  /** Ожидаемая длительность словами. */
  duration: string
}

/** Фрагмент сценария на экране состава. */
export interface RecipeItemCard {
  /** Порядковый номер фрагмента. */
  ordinal: string
  /** Название сцены либо пояснение, что названия нет. */
  title: string
  /** Место действия либо прочерк. */
  location: string
  /** Персоны сцены одной строкой либо прочерк. */
  persons: string
  /** Серия-источник и путь к файлу. */
  source: string
  /** Расчётные границы фрагмента. */
  plannedFrames: string
  /** Фактические границы нарезки. */
  cutFrames: string
  /** Расхождение расчётных и фактических границ либо `null`. */
  roundingNote: string | null
}

/** Состав сценария на экране. */
export interface RecipeDetailCard {
  /** Идентификатор сценария. */
  id: number
  /** Название сценария. */
  name: string
  /** Состояние сценария словами. */
  stateTitle: string
  /** Тон состояния. */
  stateTone: 'success' | 'info' | 'warning' | 'secondary'
  /** Готов ли сценарий к выдаче. */
  isReady: boolean
  /** Помечен ли сценарий устаревшим. */
  isStale: boolean
  /** Чем сценарий устарел либо `null`. */
  staleReason: string | null
  /** Идентификатор ключа подписи либо прочерк. */
  signingKeyId: string
  /** Сумма содержимого файла либо прочерк. */
  contentSha256: string
  /** Ожидаемая длительность словами. */
  duration: string
  /** Ожидаемое число кадров словами. */
  frameCount: string
  /** Фрагменты сценария. */
  items: RecipeItemCard[]
}

/** Подпись и ключ проверки на экране. */
export interface SignatureCard {
  /** Идентификатор ключа подписи. */
  signingKeyId: string
  /** Алгоритм подписи. */
  algorithm: string
  /** Сумма содержимого файла с разбивкой на блоки. */
  contentSha256: string
  /** Значение подписи с разбивкой на блоки. */
  signature: string
  /** Открытый ключ в PEM — как его читает `openssl` на машине пользователя. */
  publicKeyPem: string
  /** С какого момента ключ доверенный. */
  notBefore: string
}

/**
 * Поясняет состояние сценария словами.
 *
 * Состояние приходит машинным кодом, а читает его пользователь. Показывать
 * код без пояснения означало бы заставить его знать внутренний алфавит.
 *
 * @param state состояние сценария из ответа бэкенда
 * @returns текст и тон сообщения
 */
function describeState(state: string): { stateTitle: string; stateTone: RecipeCard['stateTone'] } {
  switch (state) {
    case 'DONE':
      return { stateTitle: 'подписан и готов', stateTone: 'success' }
    case 'CREATING':
      return { stateTitle: 'создаётся', stateTone: 'info' }
    case 'WORKING':
      return { stateTitle: 'собирается', stateTone: 'info' }
    case 'ERROR':
      return { stateTitle: 'создание не удалось', stateTone: 'warning' }
    default:
      return { stateTitle: state, stateTone: 'secondary' }
  }
}

/**
 * Приводит сериал к строке выбора.
 *
 * @param dto сериал из ответа бэкенда
 * @returns строка экрана
 */
export function toSerialOption(dto: SerialView): SerialOption {
  return {
    id: dto.id,
    name: dto.name,
    seriesCount: formatNumber(dto.seriesCount),
  }
}

/**
 * Приводит сценарий списка к строке экрана.
 *
 * @param dto сценарий из ответа бэкенда
 * @returns строка экрана
 */
export function toRecipeCard(dto: RecipeSummaryView): RecipeCard {
  const state = describeState(dto.state)
  const isStale = dto.isStale === true
  return {
    id: dto.id,
    name: dto.name,
    stateTitle: isStale ? 'подписан, но помечен устаревшим' : state.stateTitle,
    stateTone: isStale ? 'warning' : state.stateTone,
    isReady: dto.state === 'DONE' && !isStale,
    isStale,
    staleReason: dto.staleReason ?? null,
    itemCount: `${formatNumber(dto.itemCount)} ${countWord(dto.itemCount)}`,
    duration: formatMilliseconds(dto.expectedDurationMs ?? null),
  }
}

/**
 * Склоняет существительное по числу.
 *
 * @param count число
 * @returns слово в нужной форме
 */
function countWord(count: number): string {
  const mod100 = Math.abs(count) % 100
  const mod10 = mod100 % 10
  if (mod100 >= 11 && mod100 <= 14) {
    return 'фрагментов'
  }
  if (mod10 === 1) {
    return 'фрагмент'
  }
  if (mod10 >= 2 && mod10 <= 4) {
    return 'фрагмента'
  }
  return 'фрагментов'
}

/**
 * Приводит фрагмент сценария к строке экрана.
 *
 * Показываются **обе** пары границ — расчётная и фактическая. Их расхождение
 * это ровно то округление, которое ADR-0006 разрешает, и решение «устраивает
 * меня округление или нет» принимается до работы, а не после неё (FR-082,
 * FR-083).
 *
 * @param dto фрагмент из ответа бэкенда
 * @returns строка экрана
 */
export function toRecipeItemCard(dto: RecipeItemView): RecipeItemCard {
  const plannedFirst = dto.firstFrame ?? null
  const plannedLast = dto.lastFrame ?? null
  const cutFirst = dto.cutFirstFrame ?? null
  const cutLast = dto.cutLastFrame ?? null
  const planned =
    plannedFirst === null || plannedLast === null
      ? '—'
      : `${formatNumber(plannedFirst)}…${formatNumber(plannedLast)}`
  const cut =
    cutFirst === null || cutLast === null
      ? '—'
      : `${formatNumber(cutFirst)}…${formatNumber(cutLast)}`
  let rounding: string | null = null
  if (plannedFirst !== null && cutFirst !== null && plannedFirst !== cutFirst) {
    rounding = `нарезка сдвинута на ${formatNumber(cutFirst - plannedFirst)} кадр(ов) — округление до ближайшего ключевого кадра`
  }
  return {
    ordinal: formatNumber(dto.ordinal),
    title:
      dto.title === null || dto.title === undefined || dto.title === ''
        ? 'без названия'
        : dto.title,
    location: dto.location ?? '—',
    persons: dto.persons === undefined || dto.persons.length === 0 ? '—' : dto.persons.join(', '),
    source: `${dto.seriesName ?? 'серия не названа'} — ${dto.relativePath ?? 'путь не указан'}`,
    plannedFrames: planned,
    cutFrames: cut,
    roundingNote: rounding,
  }
}

/**
 * Приводит состав сценария к строке экрана.
 *
 * @param dto состав сценария из ответа бэкенда
 * @returns строка экрана
 */
export function toRecipeDetailCard(dto: RecipeCompositionView): RecipeDetailCard {
  const state = describeState(dto.state)
  const isStale = dto.isStale === true
  return {
    id: dto.recipeId,
    name: dto.name ?? `Сценарий №${dto.recipeId}`,
    stateTitle: isStale ? 'подписан, но помечен устаревшим' : state.stateTitle,
    stateTone: isStale ? 'warning' : state.stateTone,
    isReady: dto.state === 'DONE' && !isStale,
    isStale,
    staleReason: dto.staleReason ?? null,
    signingKeyId: dto.signingKeyId ?? '—',
    contentSha256: dto.contentSha256 ?? '—',
    duration: formatMilliseconds(dto.expectedDurationMs ?? null),
    frameCount:
      dto.expectedFrameCount === null || dto.expectedFrameCount === undefined
        ? '—'
        : formatNumber(dto.expectedFrameCount),
    items: (dto.items ?? []).map(toRecipeItemCard),
  }
}

/**
 * Собирает на экране подпись сценария и открытый ключ проверки.
 *
 * Подпись, сумма содержимого и идентификатор ключа приходят и уходят вместе
 * (FR-089c), поэтому и показываются вместе: отдельно подпись без ключа
 * бесполезна.
 *
 * @param signature подпись сценария
 * @param key открытый ключ проверки
 * @returns строка экрана
 */
export function toSignatureCard(
  signature: RecipeSignatureView,
  key: VerificationKeyView,
): SignatureCard {
  return {
    signingKeyId: signature.signingKeyId,
    algorithm: signature.algorithm,
    contentSha256: signature.contentSha256.replace(/(.{16})/g, '$1 ').trim(),
    signature: signature.signature.replace(/(.{16})/g, '$1 ').trim(),
    publicKeyPem: key.publicKeyPem,
    notBefore: formatDay(key.notBefore),
  }
}
