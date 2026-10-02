// Состояние публичной части.
//
// Стор без внешней библиотеки, как в админке: набор полей известен целиком.
// Одно состояние на всё приложение, потому что частица сценария — это ресурс с
// адресом, а не состояние вкладки: перезагрузка страницы ничего не теряет, и
// список приходит по сериалу, а не по содержимому окна.

import { ref } from 'vue'
import {
  readRecipe,
  readSignature,
  readVerificationKey,
  listRecipes,
  listSerials,
} from '../api/recipes'
import { ApiError } from '../api/http'
import {
  type RecipeCard,
  type RecipeDetailCard,
  type SerialOption,
  type SignatureCard,
  toRecipeCard,
  toRecipeDetailCard,
  toSerialOption,
  toSignatureCard,
} from '../api/view-model'

/** Сериалы для выбора. */
const serials = ref<SerialOption[]>([])

/** Выбранный сериал. */
const serialId = ref<number | null>(null)

/** Название выбранного сериала для заголовка. */
const serialName = ref('')

/** Сценарии выбранного сериала. */
const recipes = ref<RecipeCard[]>([])

/** Состав открытого сценария. */
const detail = ref<RecipeDetailCard | null>(null)

/** Подпись и открытый ключ открытого сценария. */
const signature = ref<SignatureCard | null>(null)

/** Идёт ли обращение к бэкенду. */
const loading = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

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

/** Очищает текст ошибки. */
function clearError(): void {
  error.value = ''
  errorCode.value = ''
}

/**
 * Состояние публичной части и действия над ним.
 *
 * @returns реактивное состояние и функции экранов
 */
export function useShowcaseStore() {
  /**
   * Перечисляет сериалы для выбора.
   *
   * @returns `true`, если список прочитан
   */
  async function reloadSerials(): Promise<boolean> {
    loading.value = true
    try {
      const list = await listSerials()
      serials.value = list.map(toSerialOption)
      clearError()
      return true
    } catch (failure) {
      serials.value = []
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Выбирает сериал и читает его сценарии.
   *
   * @param id идентификатор сериала
   * @returns `true`, если сценарии прочитаны
   */
  async function selectSerial(id: number): Promise<boolean> {
    serialId.value = id
    serialName.value = serials.value.find((item) => item.id === id)?.name ?? `Сериал №${id}`
    loading.value = true
    try {
      recipes.value = (await listRecipes(id)).map(toRecipeCard)
      clearError()
      return true
    } catch (failure) {
      recipes.value = []
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Открывает сценарий: состав, подпись и открытый ключ.
   *
   * Подпись и ключ приходят вместе с составом: подпись без ключа проверить
   * нечем (FR-089c), и два отдельных экрана только мешали бы пользователю.
   *
   * @param recipeId идентификатор сценария
   * @returns `true`, если сценарий прочитан
   */
  async function openRecipe(recipeId: number): Promise<boolean> {
    loading.value = true
    detail.value = null
    signature.value = null
    try {
      const composition = await readRecipe(recipeId)
      detail.value = toRecipeDetailCard(composition)
      const [signatureDto, keyDto] = await Promise.all([
        readSignature(recipeId),
        readVerificationKey(),
      ])
      signature.value = toSignatureCard(signatureDto, keyDto)
      clearError()
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  return {
    serials,
    serialId,
    serialName,
    recipes,
    detail,
    signature,
    loading,
    error,
    errorCode,
    reloadSerials,
    selectSerial,
    openRecipe,
    clearError,
  }
}
