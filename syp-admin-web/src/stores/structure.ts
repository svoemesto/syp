// Состояние экрана структуры серии.
//
// Экран отвечает на два вопроса оператора: «что система нашла» и «что из
// этого человек принял». Поэтому в состоянии лежат **два** слоя: рабочая
// структура с её планами и отдельно сырой результат автоматики. Смешивать их
// в одну таблицу нельзя — тогда исчезла бы сама возможность сравнить, и
// расхождение накапливалось бы незаметно (FR-093).

import { computed, ref } from 'vue'
import {
  type RawBoundariesView,
  type StructureView,
  readRawBoundaries,
  readStructure,
  startAnalysis,
} from '../api/structure'
import { ApiError } from '../api/http'

/** Рабочая структура серии. */
const structure = ref<StructureView | null>(null)

/** Сырой результат автоматики последнего прогона. */
const raw = ref<RawBoundariesView | null>(null)

/** Идёт ли обращение к бэкенду. */
const loading = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

/** Показывается ли сырой результат автоматики. */
const rawVisible = ref(false)

/** Страница сцен, с которой начат просмотр. */
const offset = ref(0)

/** Размер страницы сцен. */
const pageSize = 200

/**
 * Помечен ли результат устаревшим.
 *
 * Отдельное вычисление, а не чтение поля: экран показывает пометку в шапке и
 * у каждой границы, и обе надписи должны говорить об одном и том же.
 *
 * @returns `true`, если результат устарел
 */
const isStale = computed(() => structure.value?.isStale === true)

/**
 * Показывается ли результат устаревшим.
 *
 * @returns `true`, если у результата есть машинный код устаревания
 */
const staleCode = computed(() => structure.value?.staleResultCode ?? '')

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
      structure.value = await readStructure(seriesId, offset.value, pageSize)
      raw.value = await readRawBoundaries(seriesId, undefined, 0, pageSize)
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
    loading,
    error,
    errorCode,
    rawVisible,
    isStale,
    staleCode,
    reload,
    nextPage,
    previousPage,
    analyse,
    toggleRaw,
    clearError,
  }
}
