// Общий слой обращения к публичному API.
//
// Файл повторяет `syp-admin-web/src/api/http.ts` намеренно: префикс `/api` и
// разбор тела ошибки должны вести себя одинаково в обеих частях, а общего
// пакета у них нет — в корне репозитория файла `package.json` нет (R-375).
// Расхождение поведения между админкой и публичной частью оператор увидел бы
// как «иногда показывает код, иногда нет».
//
// Документация публичных функций — по правилам проекта (FR-006, FR-100).

/** Тело ошибки в ответе публичного API. */
export interface ApiErrorBody {
  /** Машинный код ошибки, например `NOT_FOUND`. */
  code: string
  /** Текст ошибки на русском для пользователя. */
  message: string
  /** Проблемные объекты, если сервер их перечислил. */
  items?: { kind: string; identifier: string; detail: string }[]
}

/**
 * Отказ публичного API.
 *
 * Отдельный тип вместо `Error`: экран различает «сценария нет» и «сервер
 * упал» по коду, а не по тексту. Текст при этом всегда показывается как есть —
 * он написан для человека.
 */
export class ApiError extends Error {
  /** Машинный код ошибки. */
  readonly code: string

  /** HTTP-статус ответа. */
  readonly status: number

  /** Проблемные объекты, если сервер их перечислил. */
  readonly items: ApiErrorBody['items']

  /**
   * Собирает отказ по ответу сервера.
   *
   * @param status HTTP-статус ответа
   * @param body тело ошибки
   */
  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.code = body.code
    this.status = status
    this.items = body.items ?? []
  }
}

/**
 * Выполняет запрос к публичному API.
 *
 * Отказ без тела ошибки — сам по себе отказ: показать пустую строку значит
 * скрыть причину, а «успех» без данных приводит к пустому экрану.
 *
 * @param method HTTP-метод
 * @param path путь после префикса `/api`
 * @param body тело запроса либо `undefined` у запросов без тела
 * @returns разобранный ответ сервера
 * @throws ApiError если сервер ответил с кодом ошибки или без тела
 * @throws Error если сервер недоступен или ответ не является JSON
 */
export async function request<T>(
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
): Promise<T> {
  let response: Response
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (cause) {
    throw new Error(
      `Публичный бэкенд недоступен: ${cause instanceof Error ? cause.message : String(cause)}. ` +
        'Проверьте, запущен ли контейнер syp-public-app',
    )
  }

  const text = await response.text()
  const parsed: unknown = text === '' ? null : safeParse(text)
  if (!response.ok) {
    const errorBody = parsed as ApiErrorBody | null
    throw new ApiError(
      response.status,
      errorBody && typeof errorBody.code === 'string' && typeof errorBody.message === 'string'
        ? errorBody
        : {
            code: 'INTERNAL_ERROR',
            message: `Сервер вернул код ${response.status} без читаемого описания ошибки`,
          },
    )
  }
  return parsed as T
}

/**
 * Разбирает ответ как JSON, не роняя экран на неожиданном содержимом.
 *
 * @param text тело ответа
 * @returns разобранный объект либо исходная строка
 */
function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
