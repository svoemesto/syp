// Подписка интерфейса на поток событий SSE.
//
// Клиент — `event-source-polyfill`, а не нативный `EventSource`: тот не умеет
// ни задавать таймаут сердчебия, ни работать с потоком, который переживает
// переподключение. Значения взяты из админки Karaoke и подписаны здесь.
//
// Идентификатор вкладки генерирует **клиент** и передаёт в адресе: сервер
// различает вкладки по нему. Значение приходит от клиента и потому
// недоверенное — сервер проверяет его форму и подставляет свой, если форма не
// прошла. Здесь важно другое: идентификатор хранится в `sessionStorage`, а не
// в `localStorage`, — при перезагрузке страницы это та же вкладка, а при
// открытии второй вкладки браузер даёт другое значение.
//
// Формат событий — контракт
// `specs/001-first-vertical-slice/contracts/admin-api.md`, раздел 4.

import {
  EventSourcePolyfill,
  type EventListener as PolyfillEventListener,
} from 'event-source-polyfill'

/** Типы уведомлений SYP: их имена приходят в потоке как имена событий. */
export const SseEventType = {
  /** Ход задания: сделано из ожидаемого. */
  JOB_PROGRESS: 'jobProgress',
  /** Смена состояния задания. */
  JOB_STATE: 'jobState',
  /** Состояние очереди: работает, ждёт, не удалось, завершено. */
  QUEUE_STATE: 'queueState',
  /** Изменилось состояние суммы исходника серии. */
  CHECKSUM_CHANGED: 'checksumChanged',
  /** Сценарий выдан, подписан и готов. */
  RECIPE_READY: 'recipeReady',
  /** Отказ операции вне очереди. */
  ERROR: 'error',
} as const

/** Один из типов уведомлений. */
export type SseEventName = (typeof SseEventType)[keyof typeof SseEventType]

/** Тело события «ход задания». */
export interface JobProgressPayload {
  /** Идентификатор задания. */
  jobId: number
  /** Вид задания. */
  kind: string
  /** Сделано единиц работы. */
  done: number
  /** Ожидается единиц работы; `0`, пока объём неизвестен. */
  total: number
  /** Пояснение для оператора. */
  note: string
}

/** Тело события «смена состояния задания». */
export interface JobStatePayload {
  /** Идентификатор задания. */
  jobId: number
  /** Вид задания. */
  kind: string
  /** Состояние после изменения. */
  state: string
  /** Вид предмета работы либо `null`. */
  subjectType: string | null
  /** Идентификатор предмета работы либо `null`. */
  subjectId: number | null
  /** Сделано единиц работы. */
  done: number
  /** Ожидается единиц работы. */
  total: number
  /** Текст ошибки при состоянии `ERROR`, иначе `null`. */
  errorText: string | null
}

/** Тело события «состояние очереди». */
export interface QueueStatePayload {
  /** Заданий взято в работу. */
  working: number
  /** Заданий ждёт. */
  waiting: number
  /** Заданий завершилось ошибкой. */
  failed: number
  /** Заданий завершилось успешно. */
  done: number
}

/** Тело события «изменилась сумма исходника». */
export interface ChecksumChangedPayload {
  /** Серия. */
  seriesId: number
  /** Состояние подсчёта. */
  state: string
  /** Посчитанная сумма либо `null`. */
  digest: string | null
  /** Помечена ли сумма устаревшей. */
  isStale: boolean
  /** Текст ошибки при состоянии `ERROR`, иначе `null`. */
  errorText: string | null
}

/** Тело события «сценарий готов». */
export interface RecipeReadyPayload {
  /** Идентификатор сценария. */
  recipeId: number
  /** Название сценария. */
  name: string
  /** Число фрагментов. */
  itemCount: number
  /** Расчётная длительность подборки либо `null`. */
  expectedDurationMs: number | null
  /** Расчётное число кадров либо `null`. */
  expectedFrameCount: number | null
  /** Идентификатор ключа подписи; не сам ключ. */
  signingKeyId: string
}

/** Тело события «ошибка». */
export interface ErrorPayload {
  /** Машинный код отказа. */
  code: string
  /** Текст отказа на русском. */
  message: string
  /** Что делал оператор, когда отказ случился. */
  operation: string
}

/** Тела событий по типам. */
export interface SsePayloads {
  /** Ход задания. */
  jobProgress: JobProgressPayload
  /** Смена состояния задания. */
  jobState: JobStatePayload
  /** Состояние очереди. */
  queueState: QueueStatePayload
  /** Изменение суммы исходника. */
  checksumChanged: ChecksumChangedPayload
  /** Готовность сценария. */
  recipeReady: RecipeReadyPayload
  /** Отказ вне очереди. */
  error: ErrorPayload
}

/**
 * Таймаут сердчебия клиента.
 *
 * Он **больше** серверного сердцебия (15 секунд) и меньше умолчания полифилла.
 * Равным он быть не может: иначе живой поток успеет признаться мёртвым, и
 * клиент будет рвать исправно работающее соединение.
 */
export const HEARTBEAT_TIMEOUT_MS = 30000

/**
 * Сколько попыток переподключения подряд, прежде чем остановиться.
 *
 * Читается при сборке из `deploy/.env.example`: значения правятся при
 * обслуживании, и пересборка образа — единственный способ их изменить.
 * Значения по умолчанию совпадают с `VITE_SSE_*` в `deploy/Dockerfile.frontend`.
 */
export const MAX_RECONNECT_ATTEMPTS = positiveNumber(
  import.meta.env.VITE_SSE_MAX_RECONNECT_ATTEMPTS,
  5,
)

/** Первая пауза между попытками переподключения, мс. */
export const RECONNECT_BASE_MS = positiveNumber(import.meta.env.VITE_SSE_RECONNECT_BASE_MS, 1000)

/** Потолок паузы между попытками, мс. */
export const RECONNECT_MAX_MS = positiveNumber(import.meta.env.VITE_SSE_RECONNECT_MAX_MS, 30000)

/**
 * Читает положительное число из настройки сборки.
 *
 * Негодное значение заменяется значением по умолчанию, а не отвергает сборку:
 * предел в ноль попыток означал бы «не переподключаться никогда», а таймаут
 * сердцебия в ноль означал бы рвать живое соединение.
 *
 * @param raw значение из окружения сборки
 * @param fallback значение по умолчанию
 * @returns положительное число либо значение по умолчанию
 */
function positiveNumber(raw: string | undefined, fallback: number): number {
  const parsed = Number(raw)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}

/** Ключ идентификатора вкладки в `sessionStorage`. */
const TAB_ID_KEY = 'syp.tabId'

/** Минимальная длина идентификатора вкладки: столько же проверяет сервер. */
const TAB_ID_MIN_LENGTH = 8

/**
 * Идентификатор вкладки для адреса подписки.
 *
 * Генерируется один раз на вкладку и хранится в `sessionStorage`: перезагрузка
 * страницы — та же вкладка, а вторая вкладка получает своё значение. Ключ в
 * `localStorage` дал бы обеим вкладкам один идентификатор, и закрытие одной
 * гасило бы подписку другой.
 *
 * @returns идентификатор вкладки
 */
export function tabId(): string {
  const stored = window.sessionStorage.getItem(TAB_ID_KEY)
  if (stored !== null && stored.length >= TAB_ID_MIN_LENGTH) {
    return stored
  }
  const generated =
    typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID().replace(/-/g, '')
      : Math.random().toString(16).slice(2).padEnd(TAB_ID_MIN_LENGTH, '0')
  window.sessionStorage.setItem(TAB_ID_KEY, generated)
  return generated
}

/** Состояние подписки, как его видит интерфейс. */
export type SubscriptionPhase = 'connecting' | 'connected' | 'reconnecting' | 'stopped'

/**
 * Разбор тела события.
 *
 * Тело приходит строкой всегда, даже когда это JSON-объект. Плохое тело —
 * это повод пропустить событие, а не уронить подписку: иначе одно
 * непонятное сообщение отключало бы весь поток.
 *
 * @param raw тело события как строка
 * @returns разобранный объект либо `null`
 */
export function parsePayload(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return null
  }
}

/** Обработчики подписки. */
export interface SubscriptionHandlers {
  /**
   * Пришло событие известного типа.
   *
   * @param name имя события
   * @param payload разобранное тело
   */
  onEvent(name: SseEventName, payload: unknown): void
  /**
   * Состояние подписки изменилось.
   *
   * @param phase состояние подписки
   * @param attempt сколько попыток переподключения уже сделано
   */
  onState(phase: SubscriptionPhase, attempt: number): void
}

/** Открытая подписка. */
export interface Subscription {
  /** Закрывает подписку и отменяет ожидающее переподключение. */
  close(): void
  /** Начинает подключение заново после остановки. */
  retryNow(): void
}

/**
 * Открывает подписку на поток событий.
 *
 * Переподключение **ограничено и видно**. Молчаливый бесконечный повтор —
 * это интерфейс, который выглядит живым, а потока нет; на [MAX_RECONNECT_ATTEMPTS]
 * попытках подписка останавливается и говорит об этом словами, а повтор
 * делает оператор кнопкой.
 *
 * @param handlers обработчики событий и состояния
 * @returns открытая подписка
 */
export function openSubscription(handlers: SubscriptionHandlers): Subscription {
  let source: EventSourcePolyfill | null = null
  let retryTimer = 0
  let attempt = 0
  let closed = false

  /**
   * Разбирает тело и передаёт событие обработчику.
   *
   * Имя события в потоке приходит произвольной строкой, поэтому обработчик
   * объявлен **типом полифилла**, а не типом `lib.dom`: у полифилла своё
   * определение события, и смешивать их нельзя. Заодно это снимает требование
   * привести имя события к списку, который знает `lib.dom`, — список этот наш,
   * серверный.
   *
   * @param name имя события
   * @returns обработчик события для потока
   */
  function dispatch(name: SseEventName): PolyfillEventListener {
    const listener = (event: { data?: unknown }): void => {
      const payload = parsePayload(String(event.data ?? ''))
      if (payload === null) {
        return
      }
      handlers.onEvent(name, payload)
    }
    return listener as PolyfillEventListener
  }

  /** Закрывает текущий поток, не трогая состояние подписки. */
  function closeSource(): void {
    source?.close()
    source = null
  }

  /** Подключается заново, увеличивая счётчик попыток. */
  function open(): void {
    if (closed) {
      return
    }
    attempt += 1
    handlers.onState(attempt === 1 ? 'connecting' : 'reconnecting', attempt)
    closeSource()
    source = new EventSourcePolyfill(`/api/subscribe?tabId=${encodeURIComponent(tabId())}`, {
      heartbeatTimeout: HEARTBEAT_TIMEOUT_MS,
    })
    source.onopen = () => {
      attempt = 0
      handlers.onState('connected', 0)
    }
    source.addEventListener(SseEventType.JOB_PROGRESS, dispatch(SseEventType.JOB_PROGRESS))
    source.addEventListener(SseEventType.JOB_STATE, dispatch(SseEventType.JOB_STATE))
    source.addEventListener(SseEventType.QUEUE_STATE, dispatch(SseEventType.QUEUE_STATE))
    source.addEventListener(SseEventType.CHECKSUM_CHANGED, dispatch(SseEventType.CHECKSUM_CHANGED))
    source.addEventListener(SseEventType.RECIPE_READY, dispatch(SseEventType.RECIPE_READY))
    source.addEventListener(SseEventType.ERROR, dispatch(SseEventType.ERROR))
    source.onerror = () => scheduleReconnect()
  }

  /** Планирует следующую попытку либо останавливается, если попытки кончились. */
  function scheduleReconnect(): void {
    closeSource()
    if (closed) {
      return
    }
    if (attempt >= MAX_RECONNECT_ATTEMPTS) {
      handlers.onState('stopped', attempt)
      return
    }
    const delay = Math.min(RECONNECT_BASE_MS * 2 ** (attempt - 1), RECONNECT_MAX_MS)
    retryTimer = window.setTimeout(open, delay)
  }

  open()

  return {
    close(): void {
      closed = true
      window.clearTimeout(retryTimer)
      closeSource()
      handlers.onState('stopped', attempt)
    },
    retryNow(): void {
      window.clearTimeout(retryTimer)
      attempt = 0
      closed = false
      open()
    },
  }
}
