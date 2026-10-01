// Состояние экрана приёма.
//
// Хранилище без внешней библиотеки: набор полей здесь известен целиком и не
// меняется по ходу работы. Стор — обычная функция, возвращающая реактивные
// ссылки: это позволяет экрану подписаться на них и не тащить Pinia ради
// одного файла.

import { computed, ref } from 'vue'
import {
  type SerialDetailView,
  type SerialView,
  type SeriesView,
  createSerial,
  listSerials,
  readSerial,
  registerSeries,
} from '../api/catalog'
import { ApiError } from '../api/http'

/** Список сериалов. */
const serials = ref<SerialView[]>([])

/** Раскрытый сериал с его сериями и настройками. */
const current = ref<SerialDetailView | null>(null)

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

/** Серии раскрытого сериала; пустой список, если сериал не выбран. */
const series = computed<SeriesView[]>(() => current.value?.series ?? [])

/** Можно ли ставить новую серию: сериал должен быть выбран. */
const canRegisterSeries = computed(() => current.value !== null)

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
      serials.value = await listSerials()
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
      current.value = await readSerial(serialId)
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
    if (current.value === null) {
      errorCode.value = 'BAD_REQUEST'
      error.value = 'Сначала откройте сериал: серия заводится только в нём'
      return false
    }
    loading.value = true
    try {
      await registerSeries(current.value.serial.id, sourcePath, name)
      await openSerial(current.value.serial.id)
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /** Очищает текст ошибки: экран возвращается в спокойное состояние. */
  function clearError(): void {
    error.value = ''
    errorCode.value = ''
  }

  return {
    serials,
    current,
    series,
    loading,
    error,
    errorCode,
    canRegisterSeries,
    reloadSerials,
    addSerial,
    openSerial,
    addSeries,
    clearError,
  }
}
