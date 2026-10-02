// Состояние экрана приёма.
//
// Хранилище без внешней библиотеки: набор полей здесь известен целиком и не
// меняется по ходу работы. Стор — обычная функция, возвращающая реактивные
// ссылки: это позволяет экрану подписаться на них и не тащить Pinia ради
// одного файла.
//
// В состоянии лежат **строки экрана** из `api/view-model.ts`, а не ответы
// бэкенда: обращения к полям `serialId` и `seriesId` не должны расходиться по
// шаблонам, иначе грядущее переименование затронет их все.

import { computed, ref } from 'vue'
import {
  type SerialDetailView,
  createSerial,
  listSerials,
  readSerial,
  registerSeries,
} from '../api/catalog'
import { ApiError } from '../api/http'
import { type SerialRow, type SeriesRow, toSerialRow, toSeriesRow } from '../api/view-model'

/** Сериалы на экране. */
const serials = ref<SerialRow[]>([])

/** Название раскрытого сериала — нужно заголовкам экранов. */
const currentName = ref('')

/** Серии раскрытого сериала на экране. */
const seriesRows = ref<SeriesRow[]>([])

/** Идентификатор раскрытого сериала. */
const currentId = ref<number | null>(null)

/**
 * Серия, выбранная для экранов суммы и структуры.
 *
 * Отдельное поле, а не вычисление из списка: разделы «суммы» и «структура»
 * живут в навигации всегда, а открыть их имеет смысл только для конкретной
 * серии. Пока серия не выбрана, раздел объясняет это и отправляет в приём.
 */
const selectedSeriesId = ref<number | null>(null)

/** Идёт ли обращение к бэкенду: показывается работа, а не пустой экран. */
const loading = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

/**
 * Разбирает отказ и кладёт его в состояние.
 *
 * Отказ без текста на экране — это «сервер молча отказал»: оператор должен
 * видеть причину, а не пустое состояние.
 *
 * @param failure отказ
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

/** Раскрыт ли сериал: от этого зависит, показывается ли блок серий. */
const hasCurrent = computed(() => currentId.value !== null)

/** Можно ли ставить новую серию: сериал должен быть выбран. */
const canRegisterSeries = computed(() => currentId.value !== null)

/** У выбранной серии есть хотя бы одна. */
const hasSeries = computed(() => seriesRows.value.length > 0)

/**
 * Состояние экрана приёма и действия над ним.
 *
 * @returns реактивное состояние и функции экрана
 */
export function useCatalogStore() {
  /**
   * Перечитывает список сериалов.
   *
   * @returns `true`, если список прочитан
   */
  async function reloadSerials(): Promise<boolean> {
    loading.value = true
    try {
      serials.value = (await listSerials()).map(toSerialRow)
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
   * Создаёт сериал с корнем каталога и раскрывает его.
   *
   * @param name название сериала
   * @param sourceRoot корневой каталог сериала на машине администратора
   * @returns `true`, если сериал создан и показан
   */
  async function addSerial(name: string, sourceRoot: string): Promise<boolean> {
    loading.value = true
    try {
      const created = await createSerial(name, sourceRoot)
      await openSerial(created.serial.id)
      await reloadSerials()
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Открывает сериал: серии и настройки.
   *
   * @param serialId идентификатор сериала
   * @returns `true`, если сериал прочитан
   */
  async function openSerial(serialId: number): Promise<boolean> {
    loading.value = true
    try {
      const detail: SerialDetailView = await readSerial(serialId)
      currentId.value = detail.serial.id
      currentName.value = detail.serial.name
      seriesRows.value = detail.series.map(toSeriesRow)
      // Открытый сериал снимает выбор серии: выбранная серия относилась бы к
      // прежнему сериалу, и разделы суммы и структуры показали бы чужое.
      selectedSeriesId.value = null
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
   * Регистрирует серию по пути к файлу.
   *
   * @param sourcePath абсолютный путь к файлу внутри корня сериала
   * @param name название серии; если не задано, берётся имя файла
   * @returns `true`, если серия зарегистрирована
   */
  async function addSeries(sourcePath: string, name?: string): Promise<boolean> {
    if (currentId.value === null) {
      errorCode.value = 'BAD_REQUEST'
      error.value = 'Сначала откройте сериал: серия заводится только в нём'
      return false
    }
    loading.value = true
    try {
      await registerSeries(currentId.value, sourcePath, name)
      await openSerial(currentId.value)
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Выбирает серию для разделов суммы и структуры.
   *
   * @param seriesId идентификатор серии
   */
  function selectSeries(seriesId: number): void {
    selectedSeriesId.value = seriesId
  }

  /** Снимает выбор серии: разделы суммы и структуры снова требуют выбора. */
  function clearSelection(): void {
    selectedSeriesId.value = null
  }

  /** Очищает текст ошибки: экран возвращается в спокойное состояние. */
  function clearError(): void {
    error.value = ''
    errorCode.value = ''
  }

  return {
    serials,
    currentName,
    series: seriesRows,
    currentId,
    selectedSeriesId,
    loading,
    error,
    errorCode,
    hasCurrent,
    hasSeries,
    canRegisterSeries,
    reloadSerials,
    addSerial,
    openSerial,
    addSeries,
    selectSeries,
    clearSelection,
    clearError,
  }
}
