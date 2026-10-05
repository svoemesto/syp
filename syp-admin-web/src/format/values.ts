// Форматирование величин для показа оператору.
//
// Величины показываются в том виде, в котором оператор их сравнивает с тем,
// что видит на своей машине: байты — как байты, длительность — в секундах с
// одной десятой. Число без единицы измерения на экране не является числом.

/** Сотни байт в килобайтах и далее: основание 1024, как в файловых размерах. */
const BYTES_IN_KIB = 1024

/** Сотни долей секунды. */
const SECONDS_IN_MINUTE = 60

/**
 * Форматирует размер файла в байтах.
 *
 * @param bytes размер в байтах
 * @returns размер с единицей измерения
 */
export function formatBytes(bytes: number, unit = 'байт', fraction = 0): string {
  if (bytes < BYTES_IN_KIB) {
    return `${bytes} ${unit}`
  }
  const kib = bytes / BYTES_IN_KIB
  if (kib < BYTES_IN_KIB) {
    return `${kib.toFixed(fraction)} Киб`
  }
  const mib = kib / BYTES_IN_KIB
  if (mib < BYTES_IN_KIB) {
    return `${mib.toFixed(fraction)} Миб`
  }
  return `${(mib / BYTES_IN_KIB).toFixed(fraction)} Гиб`
}

/**
 * Форматирует длительность в секундах: минуты и секунды.
 *
 * @param seconds длительность в секундах
 * @param unitName название единицы для подписи при коротком значении
 * @param fraction сколько знаков после запятой у короткого значения
 * @returns длительность со значением и единицей измерения
 */
export function formatDuration(seconds: number, unitName = 's', fraction = 1): string {
  if (seconds < SECONDS_IN_MINUTE) {
    return `${seconds.toFixed(fraction)} ${unitName}`
  }
  const minutes = Math.floor(seconds / SECONDS_IN_MINUTE)
  const rest = seconds - minutes * SECONDS_IN_MINUTE
  return `${minutes} min ${rest.toFixed(0)} s`
}

/**
 * Форматирует целое число с разделителем разрядов.
 *
 * Разряды разделяются по-русски, потому что оператор сравнивает числа между
 * собой, и «88 643» читается быстрее, чем «88643». Само число при этом не
 * меняется: это текст для показа, а не значение для вычислений.
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
