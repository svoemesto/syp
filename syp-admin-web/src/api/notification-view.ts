// Представление уведомлений SSE для экрана.
//
// Отдельный файл, а не функции в `stores/notifications.ts`, по тому же правилу,
// что и остальные ответы бэкенда: поля события читаются здесь один раз, а экран
// получает слова и классы оформления, а не машинные значения. Экран не должен
// знать, что состояние называется `WORKING`, и уж тем более — сам решать, что
// этому цвет.
//
// Формат событий — контракт
// `specs/001-first-vertical-slice/contracts/admin-api.md`, раздел 4.

import type {
  ChecksumChangedPayload,
  ErrorPayload,
  JobProgressPayload,
  JobStatePayload,
  QueueStatePayload,
  RecipeReadyPayload,
} from './events'
import { formatNumber } from '../format/values'

/** Тон сообщения: соответствует смыслу, а не оформлению Bootstrap. */
export type NoticeTone = 'success' | 'info' | 'warning' | 'danger'

/** Ход задания на экране. */
export interface JobProgressView {
  /** Идентификатор задания. */
  jobId: number
  /** Вид задания словами. */
  kindTitle: string
  /** Состояние задания словами. */
  stateTitle: string
  /** Сделано единиц работы с разделителем разрядов. */
  done: string
  /** Ожидается единиц работы с разделителем разрядов; `null`, пока неизвестно. */
  total: string | null
  /** Доля выполнения в процентах; `null`, пока объём работы неизвестен. */
  percent: number | null
  /** Пояснение для оператора. */
  note: string
}

/** Состояние очереди на экране. */
export interface QueueStateView {
  /** Заданий работает. */
  working: number
  /** Заданий ждёт. */
  waiting: number
  /** Заданий завершилось ошибкой. */
  failed: number
  /** Заданий завершилось успешно. */
  done: number
  /** Сводка одной строкой. */
  summary: string
}

/** Уведомление, готовое к показу. */
export interface NoticeView {
  /** Порядковый номер: тот же текст дважды показать можно, и это не сбой. */
  id: number
  /** Тон сообщения. */
  tone: NoticeTone
  /** Заголовок сообщения. */
  title: string
  /** Текст сообщения. */
  text: string
}

/** Названия видов заданий словами. */
const KIND_TITLES: Record<string, string> = {
  ANALYZE: 'анализ структуры',
  FACES: 'поиск лиц',
  TRAIN: 'обучение модели',
  HASH: 'подсчёт суммы',
}

/** Названия состояний задания словами. */
const STATE_TITLES: Record<string, string> = {
  WAITING: 'ждёт в очереди',
  CREATING: 'взято воркером',
  WORKING: 'идёт работа',
  DONE: 'завершено',
  ERROR: 'ошибка',
}

/**
 * Название вида задания словами.
 *
 * @param kind вид задания из события
 * @returns название словами либо исходное значение: неизвестный вид задания
 *   должен быть виден как есть, а не исчезнуть в общем слове
 */
export function jobKindTitle(kind: string): string {
  return KIND_TITLES[kind] ?? kind
}

/**
 * Название состояния задания словами.
 *
 * @param state состояние из события
 * @returns название словами либо исходное значение
 */
export function jobStateTitle(state: string): string {
  return STATE_TITLES[state] ?? state
}

/**
 * Строит строку хода задания.
 *
 * Доля выполнения не выдумывается, пока объём работы неизвестен: у задания
 * до конца работы `total` равен нулю, и делить на него нельзя. Экран в этом
 * случае показывает счётчик и пояснение, а не «0 %».
 *
 * @param dto событие о ходе задания
 * @param state последнее известное состояние задания
 * @returns строка хода для экрана
 */
export function toJobProgressView(dto: JobProgressPayload, state = 'WORKING'): JobProgressView {
  const known = dto.total > 0
  return {
    jobId: dto.jobId,
    kindTitle: jobKindTitle(dto.kind),
    stateTitle: jobStateTitle(state),
    done: formatNumber(dto.done),
    total: known ? formatNumber(dto.total) : null,
    percent: known ? Math.round((dto.done / dto.total) * 100) : null,
    note: dto.note,
  }
}

/**
 * Строит строку состояния задания.
 *
 * @param dto событие о смене состояния
 * @returns строка состояния для экрана
 */
export function toJobStateView(dto: JobStatePayload): JobProgressView {
  const known = dto.total > 0
  return {
    jobId: dto.jobId,
    kindTitle: jobKindTitle(dto.kind),
    stateTitle: jobStateTitle(dto.state),
    done: formatNumber(dto.done),
    total: known ? formatNumber(dto.total) : null,
    percent: known ? Math.round((dto.done / dto.total) * 100) : null,
    note: dto.errorText ?? '',
  }
}

/**
 * Строит сводку по очереди заданий.
 *
 * @param dto событие о состоянии очереди
 * @returns сводка для экрана
 */
export function toQueueStateView(dto: QueueStatePayload): QueueStateView {
  return {
    working: dto.working,
    waiting: dto.waiting,
    failed: dto.failed,
    done: dto.done,
    summary:
      dto.working === 0 && dto.waiting === 0
        ? 'очередь пуста'
        : `работает ${dto.working}, ждёт ${dto.waiting}` +
          (dto.failed > 0 ? `, ошибок ${dto.failed}` : ''),
  }
}

/**
 * Строит уведомление о завершении задания.
 *
 * @param dto событие о смене состояния
 * @returns уведомление либо `null`, если состояние не требует сообщения
 */
export function toJobNotice(dto: JobStatePayload): NoticeView | null {
  const kindTitle = jobKindTitle(dto.kind)
  if (dto.state === 'DONE') {
    return {
      id: dto.jobId * 100 + 1,
      tone: 'success',
      title: 'Задание завершено',
      text: `${kindTitle}: работа выполнена`,
    }
  }
  if (dto.state === 'ERROR') {
    return {
      id: dto.jobId * 100 + 2,
      tone: 'danger',
      title: 'Задание не выполнено',
      text: `${kindTitle}: ${dto.errorText ?? 'причина не сообщена'}`,
    }
  }
  return null
}

/**
 * Строит уведомление об изменении суммы исходника.
 *
 * @param dto событие об изменении суммы
 * @returns уведомление
 */
export function toChecksumNotice(dto: ChecksumChangedPayload): NoticeView {
  if (dto.state === 'DONE' && !dto.isStale) {
    return {
      id: dto.videofileId * 100 + 3,
      tone: 'success',
      title: 'Сумма посчитана',
      text: `Эпизод №${dto.videofileId}: сумма актуальна, сценарий сборки можно выдавать`,
    }
  }
  if (dto.state === 'ERROR') {
    return {
      id: dto.videofileId * 100 + 4,
      tone: 'danger',
      title: 'Сумма не посчитана',
      text: `Эпизод №${dto.videofileId}: ${dto.errorText ?? 'причина не сообщена'}`,
    }
  }
  return {
    id: dto.videofileId * 100 + 5,
    tone: 'info',
    title: 'Сумма считается',
    text: `Эпизод №${dto.videofileId}: подсчёт идёт`,
  }
}

/**
 * Строит уведомление о готовом сценарии сборки.
 *
 * @param dto событие о готовности сценария
 * @returns уведомление
 */
export function toRecipeNotice(dto: RecipeReadyPayload): NoticeView {
  return {
    id: dto.recipeId * 100 + 6,
    tone: 'success',
    title: 'Сценарий готов',
    text: `«${dto.name}»: фрагментов ${dto.itemCount}, ключ ${dto.signingKeyId}`,
  }
}

/**
 * Строит уведомление об отказе вне очереди.
 *
 * @param dto событие об ошибке
 * @returns уведомление
 */
export function toErrorNotice(dto: ErrorPayload): NoticeView {
  return {
    id: dto.code.length * 1000 + dto.message.length,
    tone: 'danger',
    title: `Отказ: ${dto.operation}`,
    text: `${dto.message} (код ${dto.code})`,
  }
}
