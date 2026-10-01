// Клиент приёма сериалов и серий.
//
// Соответствует разделу 3 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах
// приходит вычисленным: клиент его не хранит и не передаёт обратно как
// источник правды (ADR-0001).

import { request } from './http'

/** Описание сериала в ответе. */
export interface SerialView {
  /** Идентификатор сериала. */
  id: number
  /** Отображаемое имя сериала. */
  name: string
  /** Корень каталога сериала на машине администратора. */
  sourceRoot: string
  /** Дата создания сериала. */
  createdAt?: string | null
  /** Сколько серий заведено в сериале. */
  seriesCount: number
}

/** Описание серии в ответе со всеми измеренными параметрами. */
export interface SeriesView {
  /** Идентификатор серии. */
  id: number
  /** Идентификатор сериала-владельца. */
  serialId: number
  /** Порядковый номер серии в сериале. */
  ordinal: number
  /** Отображаемое имя серии. */
  name: string
  /** Абсолютный путь к исходному файлу. */
  sourcePath: string
  /** Путь относительно корня сериала: он и попадёт в сценарий сборки. */
  relativePath?: string | null
  /** Размер файла в байтах. */
  byteSize: number
  /** Время изменения файла. */
  fileMtime: string
  /** Число кадров серии. */
  frameCount: number
  /** Числитель длительности кадра в секундах. */
  timeBaseNum: number
  /** Знаменатель длительности кадра в секундах. */
  timeBaseDen: number
  /** Частокадровая база в виде `кадров/секунду`. */
  frameRate: string
  /** Ширина кадра. */
  width: number
  /** Высота кадра. */
  height: number
  /** Длительность серии, вычисленная по кадрам. */
  durationSeconds: number
  /** Кодек видео. */
  videoCodec: string
  /** Профиль видео. */
  videoProfile?: string | null
  /** Формат пикселей. */
  pixelFormat: string
  /** Кодек аудио. */
  audioCodec?: string | null
  /** Число аудиоканалов. */
  audioChannels?: number | null
  /** Частота дискретизации звука. */
  audioSampleRate?: number | null
  /** Сколько ключевых кадров найдено. */
  keyframeCount: number
  /** Длина карты ключевых кадров в байтах. */
  keyframeMapBytes: number
  /** Готова ли серия к работе. */
  ready: boolean
}

/** Одна настройка анализа и выдачи сценария. */
export interface SettingView {
  /** Имя настройки. */
  key: string
  /** Назначение человеческим языком. */
  title: string
  /** Тип значения: `NUMBER`, `INTEGER` или `NUMBER_LIST`. */
  kind: string
  /** Значение настройки. */
  value: unknown
  /** Когда значение записано в последний раз. */
  updatedAt?: string | null
}

/** Ответ на создание сериала: сам сериал и его настройки по умолчанию. */
export interface CreatedSerialView {
  /** Созданный сериал. */
  serial: SerialView
  /** Значения настроек по умолчанию. */
  settings: SettingView[]
}

/** Ответ на чтение сериала вместе с его содержимым. */
export interface SerialDetailView {
  /** Сериал. */
  serial: SerialView
  /** Серии сериала. */
  series: SeriesView[]
  /** Настройки сериала. */
  settings: SettingView[]
}

/**
 * Перечисляет сериалы с числом серий каждого.
 *
 * @returns список сериалов
 */
export function listSerials(): Promise<SerialView[]> {
  return request<SerialView[]>('GET', '/serials')
}

/**
 * Создаёт сериал с корнем каталога на машине администратора.
 *
 * @param name название сериала
 * @param sourceRoot корневой каталог сериала: без него нельзя вычислить
 *   относительный путь к файлу, который попадёт в сценарий сборки (FR-089a)
 * @returns созданный сериал вместе с настройками по умолчанию
 */
export function createSerial(name: string, sourceRoot: string): Promise<CreatedSerialView> {
  return request<CreatedSerialView>('POST', '/serials', { name, sourceRoot })
}

/**
 * Читает сериал, его серии и настройки.
 *
 * @param serialId идентификатор сериала
 * @returns сериал с содержимым
 */
export function readSerial(serialId: number): Promise<SerialDetailView> {
  return request<SerialDetailView>('GET', `/serials/${serialId}`)
}

/**
 * Удаляет сериал вместе с производными данными.
 *
 * Файлы архива при этом не трогаются: они принадлежат не системе.
 *
 * @param serialId идентификатор сериала
 */
export function deleteSerial(serialId: number): Promise<null> {
  return request<null>('DELETE', `/serials/${serialId}`)
}

/**
 * Перечисляет серии сериала.
 *
 * @param serialId идентификатор сериала
 * @returns серии в порядке порядковых номеров
 */
export function listSeries(serialId: number): Promise<SeriesView[]> {
  return request<SeriesView[]>('GET', `/serials/${serialId}/series`)
}

/**
 * Регистрирует серию по пути к файлу.
 *
 * Параметры файла определяет система: оператор их не вводит (FR-002). Файл
 * обязан лежать внутри корня сериала, иначе ответ — ошибка
 * `SOURCE_UNREADABLE` с путём в тексте.
 *
 * @param serialId идентификатор сериала
 * @param sourcePath абсолютный путь к файлу внутри корня сериала
 * @param name название серии; если не задано, берётся имя файла
 * @returns зарегистрированная серия с измеренными параметрами
 */
export function registerSeries(
  serialId: number,
  sourcePath: string,
  name?: string,
): Promise<SeriesView> {
  return request<SeriesView>('POST', `/serials/${serialId}/series`, {
    sourcePath,
    name: name ?? null,
  })
}

/**
 * Читает параметры серии и состояние готовности.
 *
 * @param seriesId идентификатор серии
 * @returns параметры серии
 */
export function readSeries(seriesId: number): Promise<SeriesView> {
  return request<SeriesView>('GET', `/series/${seriesId}`)
}

/**
 * Снимает серию с учёта; файл источника не трогается.
 *
 * @param seriesId идентификатор серии
 */
export function deleteSeries(seriesId: number): Promise<null> {
  return request<null>('DELETE', `/series/${seriesId}`)
}

/**
 * Читает настройки анализа и выдачи сценария.
 *
 * @param serialId идентификатор сериала
 * @returns настройки сериала
 */
export function readSettings(serialId: number): Promise<SettingView[]> {
  return request<SettingView[]>('GET', `/serials/${serialId}/settings`)
}

/**
 * Изменяет настройки анализа и выдачи сценария.
 *
 * @param serialId идентификатор сериала
 * @param changes новые значения по именам настроек
 * @returns настройки после изменения и список действительно изменившихся
 */
export function updateSettings(
  serialId: number,
  changes: Record<string, unknown>,
): Promise<{ settings: SettingView[]; changedKeys: string[] }> {
  return request('PUT', `/serials/${serialId}/settings`, changes)
}
