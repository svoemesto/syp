// Состояние суммы исходника эпизода.
//
// Экран состояния суммы показывает не «есть сумма — да, нет — нет», а
// состояние подсчёта: считается, посчитана, устарела, ошибка. Причина в том,
// что сценарий сборки выдаётся только при актуальной сумме (FR-089), и
// оператор должен видеть, что происходит, а не гадать, почему сценарий не
// выдаётся.

import { ref } from 'vue'
import { type ChecksumView, readChecksum, startChecksum } from '../api/checksum'
import { ApiError } from '../api/http'

/** Состояние суммы эпизода. */
const checksum = ref<ChecksumView | null>(null)

/** Идёт ли обращение к бэкенду. */
const loading = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

/**
 * Сумма ещё не считалась ни разу.
 *
 * Отдельное состояние вместо ошибки: код `CHECKSUM_NOT_READY` — это не сбой,
 * а обычное состояние нового эпизода, и экран показывает «поставьте пересчёт».
 *
 * @param code код отказа
 * @returns `true`, если сумма ещё не считалась
 */
function isNotReady(code: string): boolean {
  return code === 'CHECKSUM_NOT_READY'
}

/**
 * Состояние экрана суммы и действия над ним.
 *
 * @returns реактивное состояние и функции экрана
 */
export function useChecksumStore() {
  /**
   * Перечитывает состояние суммы эпизода.
   *
   * @param videofileId идентификатор эпизода
   * @returns `true`, если состояние прочитано
   */
  async function reload(videofileId: number): Promise<boolean> {
    loading.value = true
    try {
      checksum.value = await readChecksum(videofileId)
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      if (failure instanceof ApiError && isNotReady(failure.code)) {
        // Суммы нет: это состояние, а не отказ, и экран показывает его словами.
        checksum.value = null
        error.value = failure.message
        errorCode.value = failure.code
        return true
      }
      if (failure instanceof ApiError) {
        errorCode.value = failure.code
        error.value = failure.message
      } else {
        errorCode.value = ''
        error.value = failure instanceof Error ? failure.message : String(failure)
      }
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Ставит пересчёт суммы и сразу перечитывает состояние.
   *
   * @param videofileId идентификатор эпизода
   * @returns `true`, если задание поставлено
   */
  async function recalculate(videofileId: number): Promise<boolean> {
    loading.value = true
    try {
      await startChecksum(videofileId)
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      if (failure instanceof ApiError) {
        errorCode.value = failure.code
        error.value = failure.message
      } else {
        errorCode.value = ''
        error.value = failure instanceof Error ? failure.message : String(failure)
      }
      return false
    } finally {
      loading.value = false
      await reload(videofileId)
    }
  }

  /** Очищает текст ошибки. */
  function clearError(): void {
    error.value = ''
    errorCode.value = ''
  }

  return { checksum, loading, error, errorCode, reload, recalculate, clearError }
}
