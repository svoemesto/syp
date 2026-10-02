// Форматирование величин для показа пользователю.
//
// Копия `syp-admin-web/src/format/values.ts`: обе части показывают одни и те же
// величины — кадры, длительность, размер, дату — и показывать их надо
// одинаково, иначе «пять минут пятьдесят» в одной части и «350 с» в другой
// выглядят как две разные системы. Общего пакета у фронтендов нет (R-375).

/** Сотни байт в килобайтах и далее: основание 1024, как в файловых размерах. */
const BYTES_IN_KIB = 1024

/** Сотни долей секунды. */
const SECONDS_IN_MINUTE = 60

/** Миллисекунд в секунде. */
const MS_IN_SECOND = 1000

/** Миллисекунд в минуте. */
const MS_IN_MINUTE = 60 * MS_IN_SECOND

/**
 * Форматирует размер файла в байтах.
 *
 * @param bytes размер в байтах
 * @param unit название единицы для короткого значения
 * @param fraction сколько знаков после запятой у короткого значения
 * @returns размер с единицей измерения
 */
export function formatBytes(bytes: number, unit = 'байт', fraction = 0): string {
  if (bytes < BYTES_IN_KIB) {
    return `${bytes} ${unit}`
  }
  const kib = bytes / BYTES_IN_KIB
  if (kib < BYTES_IN_KIB) {
    return `${kib.toFixed(fraction)} КиБ`
  }
  const mib = kib / BYTES_IN_KIB
  if (mib < BYTES_IN_KIB) {
    return `${mib.toFixed(fraction)} МиБ`
  }
  return `${(mib / BYTES_IN_KIB).toFixed(fraction)} ГиБ`
}

/**
 * Форматирует длительность в секундах: минуты и секунды.
 *
 * @param seconds длительность в секундах
 * @param unitName название единицы для подписи при коротком значении
 * @param fraction сколько знаков после запятой у короткого значения
 * @returns длительность со значением и единицей измерения
 */
export function formatDuration(seconds: number, unitName = 'с', fraction = 1): string {
  if (seconds < SECONDS_IN_MINUTE) {
    return `${seconds.toFixed(fraction)} ${unitName}`
  }
  const minutes = Math.floor(seconds / SECONDS_IN_MINUTE)
  const rest = seconds - minutes * SECONDS_IN_MINUTE
  return `${minutes} мин ${rest.toFixed(0)} с`
}

/**
 * Форматирует длительность в миллисекундах.
 *
 * Миллисекундами отдаёт бэкенд: длительность подборки считается по фактическим
 * границам кадров, и округлять её до целых секунд на экране значило бы
 * показывать не то число, по которому соберётся файл.
 *
 * @param ms длительность в миллисекундах
 * @returns длительность словами
 */
export function formatMilliseconds(ms: number | null): string {
  if (ms === null) {
    return '—'
  }
  const seconds = ms / MS_IN_SECOND
  if (ms < MS_IN_MINUTE) {
    return `${seconds.toFixed(1)} с`
  }
  return formatDuration(seconds)
}

/**
 * Форматирует целое число с разделителем разрядов.
 *
 * @param value целое число
 * @returns число с разделителями разрядов
 */
export function formatNumber(value: number): string {
  return value.toLocaleString('ru-RU')
}

/**
 * Форматирует дату и время в понятном виде.
 *
 * @param value дата в формате ISO либо `null`
 * @returns дата и время либо прочерк
 */
export function formatDate(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return parsed.toLocaleString('ru-RU')
}

/**
 * Форматирует дату в коротком виде для подписи к ключу.
 *
 * Ключ проверки действует с момента начала его действия, и пользователю нужно
 * знать, какой ключ доверенный, а не когда обновляли страницу.
 *
 * @param value дата в формате ISO либо `null`
 * @returns дата либо прочерк
 */
export function formatDay(value: string | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) {
    return value
  }
  return parsed.toLocaleDateString('ru-RU')
}
