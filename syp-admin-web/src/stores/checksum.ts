// Состояние экрана суммы исходника серии.
//
// Экран состояния суммы показывает не «есть сумма — да, нет — нет», а
// состояние подсчёта: считается, посчитано, устарела, ошибка. Причина в том,
// что сценарий сборки выдаётся только при актуальной сумме (FR-089), и
// оператор должен видеть, что происходит, а не гадать, почему сценарий не
// выдаётся.
//
// В состоянии лежит строка экрана из `api/view-model.ts`, а не ответ
// бэкенда: поля ответа читаются в одном месте (см. `api/view-model.ts`).

import { ref } from 'vue'
import { readChecksum, startChecksum } from '../api/checksum'
import { ApiError } from '../api/http'
import { type ChecksumRow, toChecksumRow } from '../api/view-model'

/** Состояние суммы серии на экране; `null`, пока сумму не считали ни разу. */
const checksum = ref<ChecksumRow | null>(null)

/** Идёт ли обращение к бэкенду. */
const loading = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

/** Считалась ли сумма у этой серии хотя бы раз. */
const hasRecord = ref(false)

/**
 * Сумма ещё не считалась ни разу.
 *
 * Отдельное состояние вместо ошибки: код `CHECKSUM_NOT_READY` — это не сбой,
 * а обычное состояние новой серии, и экран показывает «поставьте пересчёт».
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
   * Перечитывает состояние суммы серии.
   *
   * @param seriesId идентификатор серии
   * @returns `true`, если состояние прочитано
   */
  async function reload(seriesId: number): Promise<boolean> {
    loading.value = true
    try {
      checksum.value = toChecksumRow(await readChecksum(seriesId))
      hasRecord.value = true
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      if (failure instanceof ApiError && isNotReady(failure.code)) {
        // Суммы нет: это состояние, а не отказ, и экран показывает его словами.
        checksum.value = null
        hasRecord.value = false
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
   * @param seriesId идентификатор серии
   * @returns `true`, если задание поставлено
   */
  async function recalculate(seriesId: number): Promise<boolean> {
    loading.value = true
    try {
      await startChecksum(seriesId)
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
      await reload(seriesId)
    }
  }

  /** Очищает текст ошибки. */
  function clearError(): void {
    error.value = ''
    errorCode.value = ''
  }

  return { checksum, hasRecord, loading, error, errorCode, reload, recalculate, clearError }
}
