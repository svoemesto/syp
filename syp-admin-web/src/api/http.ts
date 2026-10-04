// Общий слой обращения к админскому API.
//
// Единая точка запроса нужна по двум причинам. Первая: префикс `/api` и
// разбор тела ошибки должны быть написаны один раз — иначе один из экранов
// покажет «500» вместо текста на русском. Вторая: ошибка приходит с телом,
// где есть машинный код и текст для человека, и терять их нельзя: интерфейс
// принимает решение по коду, оператор читает сообщение.
//
// Документация публичных функций — по правилам проекта (FR-006).

/** Тело ошибки в ответе админского API. */
export interface ApiErrorBody {
  /** Машинный код ошибки, например `SOURCE_UNREADABLE`. */
  code: string
  /** Текст ошибки на русском для оператора. */
  message: string
  /** Проблемные объекты, если сервер их перечислил. */
  items?: { kind: string; identifier: string; detail: string }[]
}

/**
 * Отказ админского API.
 *
 * Отдельный тип вместо `Error`: интерфейс различает «файл недоступен»,
 * «проект не заведён» и «сервер упал» по коду, а не по тексту. Текст при
 * этом всегда показывается оператору как есть — он написан для человека.
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
 * Выполняет запрос к админскому API.
 *
 * Отказ без тела ошибки — сам по себе отказ: показать оператору пустую строку
 * значит скрыть причину, а «успех» без данных приводит к пустому экрану.
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
      `The admin backend is unavailable: ${cause instanceof Error ? cause.message : String(cause)}. ` +
        'Check whether the syp-admin-app container is running',
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
