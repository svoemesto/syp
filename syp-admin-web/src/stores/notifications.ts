// Состояние подписки на уведомления.
//
// Подписка одна на приложение, а не по экрану: оператор ставит задание на
// одном экране, смотрит на другом и должен видеть его ход в обоих случаях.
// Отдельная подписка на каждый экран означала бы столько потоков, сколько
// открытых вкладок, и ни одного из них не закрыть при переходе.
//
// Здесь три вещи: состояние соединения (видно в шапке), последний ход задания
// и очередь (видно в шапке), и уведомления, которые показывает `App.vue`.
// Показ остаётся в компоненте, потому что тосты `bootstrap-vue-next`
// берут контроллер из контекста компонента, а не из модуля.

import { ref, shallowRef } from 'vue'
import {
  openSubscription,
  SseEventType,
  type ErrorPayload,
  type JobProgressPayload,
  type JobStatePayload,
  type ChecksumChangedPayload,
  type QueueStatePayload,
  type RecipeReadyPayload,
  type Subscription,
  type SubscriptionPhase,
} from '../api/events'
import {
  toChecksumNotice,
  toErrorNotice,
  toJobNotice,
  toJobProgressView,
  toJobStateView,
  toQueueStateView,
  toRecipeNotice,
  type JobProgressView,
  type NoticeView,
  type NoticeTone,
  type QueueStateView,
} from '../api/notification-view'

/** Состояние соединения с потоком событий. */
const phase = ref<SubscriptionPhase>('connecting')

/** Сколько попыток переподключения сделано подряд; `0` — соединение живо. */
const attempt = ref(0)

/** Последняя известная сводка по очереди; `null`, пока события не приходили. */
const queue = shallowRef<QueueStateView | null>(null)

/** Ход последнего задания, о котором приходило событие. */
const lastJob = shallowRef<JobProgressView | null>(null)

/** Последнее уведомление; экран показывает его и забывает. */
const notice = shallowRef<NoticeView | null>(null)

/**
 * Счётчик изменений суммы по сериям.
 *
 * Экран суммы следит за счётчиком своей серии и перечитывает состояние, когда
 * он растёт: пересчёт занимает минуты, а перечитывать по таймеру — значит
 * спрашивать впустую.
 */
const checksumRevisions = ref<Record<number, number>>({})

/** Открытая подписка; `null`, пока подписка не открыта. */
let subscription: Subscription | null = null

/** Порядковый номер уведомления: два одинаковых текста должны быть видны оба. */
let noticeId = 0

/**
 * Кладёт уведомление в состояние.
 *
 * @param tone тон сообщения
 * @param title заголовок
 * @param text текст сообщения
 */
function publish(tone: NoticeTone, title: string, text: string): void {
  noticeId += 1
  notice.value = { id: noticeId, tone, title, text }
}

/**
 * Разбор события по имени типа.
 *
 * Тело приходит из сети, и доверять его форме нельзя: неизвестное поле или
 * отсутствующее обязательное приводят к игнорированию события, а не к падению.
 *
 * @param name имя события
 * @param payload разобранное тело
 */
function handle(name: string, payload: unknown): void {
  switch (name) {
    case SseEventType.JOB_PROGRESS: {
      lastJob.value = toJobProgressView(payload as JobProgressPayload)
      return
    }
    case SseEventType.JOB_STATE: {
      const dto = payload as JobStatePayload
      lastJob.value = toJobStateView(dto)
      const message = toJobNotice(dto)
      if (message !== null) {
        publish(message.tone, message.title, message.text)
      }
      return
    }
    case SseEventType.QUEUE_STATE: {
      queue.value = toQueueStateView(payload as QueueStatePayload)
      return
    }
    case SseEventType.CHECKSUM_CHANGED: {
      const dto = payload as ChecksumChangedPayload
      checksumRevisions.value = {
        ...checksumRevisions.value,
        [dto.seriesId]: (checksumRevisions.value[dto.seriesId] ?? 0) + 1,
      }
      const message = toChecksumNotice(dto)
      publish(message.tone, message.title, message.text)
      return
    }
    case SseEventType.RECIPE_READY: {
      const message = toRecipeNotice(payload as RecipeReadyPayload)
      publish(message.tone, message.title, message.text)
      return
    }
    case SseEventType.ERROR: {
      const message = toErrorNotice(payload as ErrorPayload)
      publish(message.tone, message.title, message.text)
      return
    }
    default:
      return
  }
}

/**
 * Открывает подписку на уведомления.
 *
 * Повторный вызов ничего не делает: вторая подписка получила бы каждое
 * событие дважды, и оператор увидел бы двойные сообщения.
 */
export function startNotifications(): void {
  if (subscription !== null) {
    return
  }
  subscription = openSubscription({
    onEvent: handle,
    onState: (next, tries) => {
      phase.value = next
      attempt.value = tries
      if (next === 'stopped') {
        publish(
          'warning',
          'Связь с бэкендом потеряна',
          'Поток уведомлений закрыт. Ход заданий с этого момента не виден: ' +
            'состояние очереди смотрите на экране или нажмите «подключиться снова».',
        )
      }
    },
  })
}

/**
 * Подключается заново после остановки.
 *
 * Повтор делает оператор, а не клиент: бесконечный молчаливый повтор выглядит
 * как живой интерфейс с застывшими данными.
 */
export function retryNotifications(): void {
  subscription?.retryNow()
}

/**
 * Подпись состояния соединения для шапки.
 *
 * @returns текст состояния
 */
export function connectionLabel(): string {
  switch (phase.value) {
    case 'connected':
      return 'уведомления включены'
    case 'connecting':
      return 'подключение к уведомлениям'
    case 'reconnecting':
      return `переподключение, попытка ${attempt.value}`
    case 'stopped':
      return 'уведомления отключены'
  }
}

/**
 * Класс оформления состояния соединения.
 *
 * @returns класс Bootstrap для индикатора
 */
export function connectionTone(): string {
  switch (phase.value) {
    case 'connected':
      return 'text-bg-success'
    case 'connecting':
    case 'reconnecting':
      return 'text-bg-warning'
    case 'stopped':
      return 'text-bg-danger'
  }
}

/**
 * Заметное ли состояние соединения.
 *
 * @returns `true`, если состояние нужно выделять
 */
export function connectionIsAttention(): boolean {
  return phase.value !== 'connected'
}

/**
 * Счётчик изменений суммы указанной серии.
 *
 * @param seriesId идентификатор серии
 * @returns номер изменения, `0`, если изменений не было
 */
export function checksumRevision(seriesId: number): number {
  return checksumRevisions.value[seriesId] ?? 0
}

/** Заметка для показа: заголовок и текст последнего уведомления. */
export function currentNotice(): NoticeView | null {
  return notice.value
}

/** Ход последнего задания для показа в шапке. */
export function currentJob(): JobProgressView | null {
  return lastJob.value
}

/** Сводка по очереди для показа в шапке. */
export function currentQueue(): QueueStateView | null {
  return queue.value
}
