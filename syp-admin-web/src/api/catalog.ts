// Клиент приёма сериалов и серий.
//
// Соответствует разделу 3 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах
// приходит вычисленным: клиент его не хранит и не передаёт обратно как
// источник правды (ADR-0001).

import { request } from './http'

/** Описание сериала в ответе. */
export interface MovieView {
  /** Идентификатор сериала. */
  id: number
  /** Отображаемое имя сериала. */
  name: string
  /** Корень каталога сериала на машине администратора. */
  sourceRoot: string
  /** Дата создания сериала. */
  createdAt?: string | null
  /** Сколько серий заведено в сериале. */
  episodeCount: number
}

/** Описание серии в ответе со всеми измеренными параметрами. */
export interface EpisodeView {
  /** Идентификатор серии. */
  id: number
  /** Идентификатор сериала-владельца. */
  movieId: number
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
export interface CreatedMovieView {
  /** Созданный сериал. */
  movie: MovieView
  /** Значения настроек по умолчанию. */
  settings: SettingView[]
}

/** Ответ на чтение сериала вместе с его содержимым. */
export interface MovieDetailView {
  /** Сериал. */
  movie: MovieView
  /** Серии сериала. */
  episode: EpisodeView[]
  /** Настройки сериала. */
  settings: SettingView[]
}

/**
 * Перечисляет сериалы с числом серий каждого.
 *
 * @returns список сериалов
 */
export function listMovies(): Promise<MovieView[]> {
  return request<MovieView[]>('GET', '/serials')
}

/**
 * Создаёт сериал с корнем каталога на машине администратора.
 *
 * @param name название сериала
 * @param sourceRoot корневой каталог сериала: без него нельзя вычислить
 *   относительный путь к файлу, который попадёт в сценарий сборки (FR-089a)
 * @returns созданный сериал вместе с настройками по умолчанию
 */
export function createMovie(name: string, sourceRoot: string): Promise<CreatedMovieView> {
  return request<CreatedMovieView>('POST', '/serials', { name, sourceRoot })
}

/**
 * Читает сериал, его серии и настройки.
 *
 * @param movieId идентификатор сериала
 * @returns сериал с содержимым
 */
export function readMovie(movieId: number): Promise<MovieDetailView> {
  return request<MovieDetailView>('GET', `/serials/${movieId}`)
}

/**
 * Удаляет сериал вместе с производными данными.
 *
 * Файлы архива при этом не трогаются: они принадлежат не системе.
 *
 * @param movieId идентификатор сериала
 */
export function deleteMovie(movieId: number): Promise<null> {
  return request<null>('DELETE', `/serials/${movieId}`)
}

/**
 * Перечисляет серии сериала.
 *
 * @param movieId идентификатор сериала
 * @returns серии в порядке порядковых номеров
 */
export function listEpisode(movieId: number): Promise<EpisodeView[]> {
  return request<EpisodeView[]>('GET', `/serials/${movieId}/series`)
}

/**
 * Регистрирует серию по пути к файлу.
 *
 * Параметры файла определяет система: оператор их не вводит (FR-002). Файл
 * обязан лежать внутри корня сериала, иначе ответ — ошибка
 * `SOURCE_UNREADABLE` с путём в тексте.
 *
 * @param movieId идентификатор сериала
 * @param sourcePath абсолютный путь к файлу внутри корня сериала
 * @param name название серии; если не задано, берётся имя файла
 * @returns зарегистрированная серия с измеренными параметрами
 */
export function registerEpisode(
  movieId: number,
  sourcePath: string,
  name?: string,
): Promise<EpisodeView> {
  return request<EpisodeView>('POST', `/serials/${movieId}/series`, {
    sourcePath,
    name: name ?? null,
  })
}

/**
 * Читает параметры серии и состояние готовности.
 *
 * @param episodeId идентификатор серии
 * @returns параметры серии
 */
export function readEpisode(episodeId: number): Promise<EpisodeView> {
  return request<EpisodeView>('GET', `/series/${episodeId}`)
}

/**
 * Снимает серию с учёта; файл источника не трогается.
 *
 * @param episodeId идентификатор серии
 */
export function deleteEpisode(episodeId: number): Promise<null> {
  return request<null>('DELETE', `/series/${episodeId}`)
}

/**
 * Читает настройки анализа и выдачи сценария.
 *
 * @param movieId идентификатор сериала
 * @returns настройки сериала
 */
export function readSettings(movieId: number): Promise<SettingView[]> {
  return request<SettingView[]>('GET', `/serials/${movieId}/settings`)
}

/**
 * Изменяет настройки анализа и выдачи сценария.
 *
 * @param movieId идентификатор сериала
 * @param changes новые значения по именам настроек
 * @returns настройки после изменения и список действительно изменившихся
 */
export function updateSettings(
  movieId: number,
  changes: Record<string, unknown>,
): Promise<{ settings: SettingView[]; changedKeys: string[] }> {
  return request('PUT', `/serials/${movieId}/settings`, changes)
}
