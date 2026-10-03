// Состояние экрана приёма.
//
// Хранилище без внешней библиотеки: набор полей здесь известен целиком и не
// меняется по ходу работы. Стор — обычная функция, возвращающая реактивные
// ссылки: это позволяет экрану подписаться на них и не тащить Pinia ради
// одного файла.

import { computed, ref } from 'vue'
import {
  type ProjectDetailView,
  type ProjectView,
  type VideofileView,
  createProject,
  listProjects,
  readProject,
  registerVideofile,
} from '../api/catalog'
import { ApiError } from '../api/http'

/** Список фильмов. */
const projects = ref<ProjectView[]>([])

/** Раскрытый фильм с его эпизодами и настройками. */
const current = ref<ProjectDetailView | null>(null)

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

/** Эпизода раскрытого фильма; пустой список, если фильм не выбран. */
const videofile = computed<VideofileView[]>(() => current.value?.videofile ?? [])

/** Можно ли ставить новый эпизод: фильм должен быть выбран. */
const canRegisterVideofile = computed(() => current.value !== null)

/**
 * Состояние экрана приёма и действия над ним.
 *
 * @returns реактивное состояние и функции экрана
 */
export function useCatalogStore() {
  /**
   * Перечитывает список фильмов.
   *
   * @returns `true`, если список прочитан
   */
  async function reloadProjects(): Promise<boolean> {
    loading.value = true
    try {
      projects.value = await listProjects()
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
   * Создаёт фильм с корнем каталога и раскрывает его.
   *
   * @param name название фильма
   * @param sourceRoot корневой каталог фильма на машине администратора
   * @returns `true`, если фильм создан и показан
   */
  async function addProject(name: string, sourceRoot: string): Promise<boolean> {
    loading.value = true
    try {
      const created = await createProject(name, sourceRoot)
      await openProject(created.project.id)
      await reloadProjects()
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Открывает фильм: эпизода и настройки.
   *
   * @param projectId идентификатор фильма
   * @returns `true`, если фильм прочитан
   */
  async function openProject(projectId: number): Promise<boolean> {
    loading.value = true
    try {
      current.value = await readProject(projectId)
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
   * Регистрирует эпизод по пути к файлу.
   *
   * @param sourcePath абсолютный путь к файлу внутри корня фильма
   * @param name название эпизода; если не задано, берётся имя файла
   * @returns `true`, если эпизод зарегистрирована
   */
  async function addVideofile(sourcePath: string, name?: string): Promise<boolean> {
    if (current.value === null) {
      errorCode.value = 'BAD_REQUEST'
      error.value = 'Сначала откройте фильм: эпизод заводится только в нём'
      return false
    }
    loading.value = true
    try {
      await registerVideofile(current.value.project.id, sourcePath, name)
      await openProject(current.value.project.id)
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
    projects,
    current,
    videofile,
    loading,
    error,
    errorCode,
    canRegisterVideofile,
    reloadProjects,
    addProject,
    openProject,
    addVideofile,
    clearError,
  }
}
