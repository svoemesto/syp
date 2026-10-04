// Данные заглушки для вкладки событий.
//
// Бэкенда событий у нас нет: ни хранения, ни чтения, ни эндпоинтов. Пока его
// нет, вкладка показывает пример в том же виде, в каком его отдавал бы сервер,
// и все действия оператора выполняет над этим примером на стороне браузера.
//
// Пример живёт здесь, а не внутри вкладки, по двум причинам. Первая: когда
// появится бэкенд, вкладке останется заменить этот модуль на запросы, а не
// переписывать форму. Вторая: пример — это данные, а форма это форма, и
// смешивать их в одном файле нельзя, иначе заглушка расползётся по коду.
//
// Имена подставные и ни на что не претендуют: это заглушка, а не данные.

/** Событие примера. */
export interface StubEvent {
  /** Идентификатор в пределах примера. */
  id: number
  /** Имя события. */
  name: string
  /** Номер первого кадра. */
  firstFrame: number
  /** Номер последнего кадра. */
  lastFrame: number
  /** Планы, входящие в событие. */
  shots: Array<{ first: number; last: number }>
  /** Персоны события. */
  persons: string[]
  /** Свойства события. */
  properties: Array<{ key: string; value: string }>
}

/**
 * Имена событий примера.
 *
 * @returns имена по числу заглушек
 */
export function stubEventNames(): string[] {
  return ['Пример события 1', 'Пример события 2', 'Пример события 3']
}

/**
 * Делит диапазон кадров на планы заданной длины.
 *
 * @param first первый кадр
 * @param last последний кадр
 * @param length длина плана в кадрах
 * @returns планы, покрывающие диапазон
 */
export function shotsFor(
  first: number,
  last: number,
  length: number,
): Array<{ first: number; last: number }> {
  const shots: Array<{ first: number; last: number }> = []
  let start = first
  while (start <= last) {
    const end = Math.min(start + length - 1, last)
    shots.push({ first: start, last: end })
    start = end + 1
  }
  return shots
}
