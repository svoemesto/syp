// Состояние экрана структуры серии.
//
// Экран отвечает на два вопроса оператора: «что система нашла» и «что из
// этого человек принял». Поэтому в состоянии лежат **два** слоя: рабочая
// структура с её планами и отдельно сырой результат автоматики. Смешивать их
// в одну таблицу нельзя — тогда исчезла бы сама возможность сравнить, и
// расхождение накапливалось бы незаметно (FR-093).
//
// На экране лежат строки из `api/view-model.ts`, а не ответы бэкенда: поля
// ответа читаются в одном месте (см. `api/view-model.ts`).

import { ref } from 'vue'
import { readRawBoundaries, readStructure, startAnalysis } from '../api/structure'
import { ApiError } from '../api/http'
import {
  type RawBoundaryRow,
  type StructureRow,
  toRawBoundaryRow,
  toStructureRow,
} from '../api/view-model'

/** Рабочая структура серии на экране. */
const structure = ref<StructureRow | null>(null)

/** Сырые границы автоматики последнего прогона на экране. */
const raw = ref<RawBoundaryRow[]>([])

/** Идёт ли обращение к бэкенду. */
const loading = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

/** Показывается ли сырой результат автоматики. */
const rawVisible = ref(false)

/** Номер последнего прогона структуры либо `null`. */
const rawRunId = ref<number | null>(null)

/** Сколько границ автоматики у прогона всего. */
const rawTotal = ref(0)

/** Помечен ли результат устаревшим. */
const isStale = ref(false)

/** Машинный код устаревания. */
const staleCode = ref('')

/** Чем именно результат устарел. */
const staleReason = ref<string | null>(null)

/** Страница сцен, с которой начат просмотр. */
const offset = ref(0)

/** Размер страницы сцен. */
const pageSize = 200

/**
 * Состояние экрана структуры и действия над ним.
 *
 * @returns реактивное состояние и функции экрана
 */
export function useStructureStore() {
  /**
   * Перечитывает оба слоя структуры серии.
   *
   * @param seriesId идентификатор серии
   * @returns `true`, если структура прочитана
   */
  async function reload(seriesId: number): Promise<boolean> {
    loading.value = true
    try {
      const dto = await readStructure(seriesId, offset.value, pageSize)
      const row = toStructureRow(dto)
      structure.value = row
      isStale.value = row.isStale
      staleCode.value = row.staleCode
      staleReason.value = row.staleReason
      const boundaries = await readRawBoundaries(seriesId, undefined, 0, pageSize)
      raw.value = boundaries.boundaries.map((item) =>
        toRawBoundaryRow(item.level, item.firstFrame, item.lastFrame),
      )
      rawRunId.value = boundaries.runId
      rawTotal.value = boundaries.total
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Переходит на следующую страницу сцен.
   *
   * @param seriesId идентификатор серии
   */
  async function nextPage(seriesId: number): Promise<void> {
    const total = structure.value?.scenesTotal ?? 0
    if (offset.value + pageSize >= total) {
      return
    }
    offset.value += pageSize
    await reload(seriesId)
  }

  /**
   * Возвращается на предыдущую страницу сцен.
   *
   * @param seriesId идентификатор серии
   */
  async function previousPage(seriesId: number): Promise<void> {
    if (offset.value === 0) {
      return
    }
    offset.value = Math.max(0, offset.value - pageSize)
    await reload(seriesId)
  }

  /**
   * Ставит анализ структуры заново.
   *
   * @param seriesId идентификатор серии
   * @returns `true`, если задание поставлено
   */
  async function analyse(seriesId: number): Promise<boolean> {
    loading.value = true
    try {
      await startAnalysis(seriesId)
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Показывает или прячет сырой результат автоматики.
   */
  function toggleRaw(): void {
    rawVisible.value = !rawVisible.value
  }

  /**
   * Сбрасывает текст ошибки.
   */
  function clearError(): void {
    error.value = ''
    errorCode.value = ''
  }

  /**
   * Запоминает отказ: текст для человека, код для интерфейса.
   *
   * @param failure отказ клиента
   */
  function remember(failure: unknown): void {
    if (failure instanceof ApiError) {
      errorCode.value = failure.code
      error.value = failure.message
      return
    }
    errorCode.value = ''
    error.value = failure instanceof Error ? failure.message : String(failure)
  }

  return {
    structure,
    raw,
    rawRunId,
    rawTotal,
    loading,
    error,
    errorCode,
    rawVisible,
    isStale,
    staleCode,
    staleReason,
    reload,
    nextPage,
    previousPage,
    analyse,
    toggleRaw,
    clearError,
  }
}
