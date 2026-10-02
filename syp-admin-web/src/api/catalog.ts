// Клиент приёма фильмов и эпизодов.
//
// Соответствует разделу 3 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах
// приходит вычисленным: клиент его не хранит и не передаёт обратно как
// источник правды (ADR-0001).

import { request } from './http'

/** Описание фильма в ответе. */
export interface MovieView {
  /** Идентификатор фильма. */
  id: number
  /** Отображаемое имя фильма. */
  name: string
  /** Корень каталога фильма на машине администратора. */
  sourceRoot: string
  /** Дата создания фильма. */
  createdAt?: string | null
  /** Сколько эпизодов заведено в фильме. */
  episodeCount: number
}

/** Описание эпизода в ответе со всеми измеренными параметрами. */
export interface EpisodeView {
  /** Идентификатор эпизода. */
  id: number
  /** Идентификатор фильма-владельца. */
  movieId: number
  /** Порядковый номер эпизода в фильме. */
  ordinal: number
  /** Отображаемое имя эпизода. */
  name: string
  /** Абсолютный путь к исходному файлу. */
  sourcePath: string
  /** Путь относительно корня фильма: он и попадёт в сценарий сборки. */
  relativePath?: string | null
  /** Размер файла в байтах. */
  byteSize: number
  /** Время изменения файла. */
  fileMtime: string
  /** Число кадров эпизода. */
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
  /** Длительность эпизода, вычисленная по кадрам. */
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
  /** Готова ли эпизод к работе. */
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

/** Ответ на создание фильма: сам фильм и его настройки по умолчанию. */
export interface CreatedMovieView {
  /** Созданный фильм. */
  movie: MovieView
  /** Значения настроек по умолчанию. */
  settings: SettingView[]
}

/** Ответ на чтение фильма вместе с его содержимым. */
export interface MovieDetailView {
  /** Фильм. */
  movie: MovieView
  /** Эпизода фильма. */
  episode: EpisodeView[]
  /** Настройки фильма. */
  settings: SettingView[]
}

/**
 * Перечисляет фильмы с числом эпизодов каждого.
 *
 * @returns список фильмов
 */
export function listMovies(): Promise<MovieView[]> {
  return request<MovieView[]>('GET', '/movies')
}

/**
 * Создаёт фильм с корнем каталога на машине администратора.
 *
 * @param name название фильма
 * @param sourceRoot корневой каталог фильма: без него нельзя вычислить
 *   относительный путь к файлу, который попадёт в сценарий сборки (FR-089a)
 * @returns созданный фильм вместе с настройками по умолчанию
 */
export function createMovie(name: string, sourceRoot: string): Promise<CreatedMovieView> {
  return request<CreatedMovieView>('POST', '/movies', { name, sourceRoot })
}

/**
 * Читает фильм, его эпизода и настройки.
 *
 * @param movieId идентификатор фильма
 * @returns фильм с содержимым
 */
export function readMovie(movieId: number): Promise<MovieDetailView> {
  return request<MovieDetailView>('GET', `/movies/${movieId}`)
}

/**
 * Удаляет фильм вместе с производными данными.
 *
 * Файлы архива при этом не трогаются: они принадлежат не системе.
 *
 * @param movieId идентификатор фильма
 */
export function deleteMovie(movieId: number): Promise<null> {
  return request<null>('DELETE', `/movies/${movieId}`)
}

/**
 * Перечисляет эпизоды фильма.
 *
 * @param movieId идентификатор фильма
 * @returns эпизода в порядке порядковых номеров
 */
export function listEpisode(movieId: number): Promise<EpisodeView[]> {
  return request<EpisodeView[]>('GET', `/movies/${movieId}/episodes`)
}

/**
 * Регистрирует эпизод по пути к файлу.
 *
 * Параметры файла определяет система: оператор их не вводит (FR-002). Файл
 * обязан лежать внутри корня фильма, иначе ответ — ошибка
 * `SOURCE_UNREADABLE` с путём в тексте.
 *
 * @param movieId идентификатор фильма
 * @param sourcePath абсолютный путь к файлу внутри корня фильма
 * @param name название эпизода; если не задано, берётся имя файла
 * @returns зарегистрированный эпизод с измеренными параметрами
 */
export function registerEpisode(
  movieId: number,
  sourcePath: string,
  name?: string,
): Promise<EpisodeView> {
  return request<EpisodeView>('POST', `/movies/${movieId}/episodes`, {
    sourcePath,
    name: name ?? null,
  })
}

/**
 * Читает параметры эпизода и состояние готовности.
 *
 * @param episodeId идентификатор эпизода
 * @returns параметры эпизода
 */
export function readEpisode(episodeId: number): Promise<EpisodeView> {
  return request<EpisodeView>('GET', `/episodes/${episodeId}`)
}

/**
 * Снимает эпизод с учёта; файл источника не трогается.
 *
 * @param episodeId идентификатор эпизода
 */
export function deleteEpisode(episodeId: number): Promise<null> {
  return request<null>('DELETE', `/episodes/${episodeId}`)
}

/**
 * Читает настройки анализа и выдачи сценария.
 *
 * @param movieId идентификатор фильма
 * @returns настройки фильма
 */
export function readSettings(movieId: number): Promise<SettingView[]> {
  return request<SettingView[]>('GET', `/movies/${movieId}/settings`)
}

/**
 * Изменяет настройки анализа и выдачи сценария.
 *
 * @param movieId идентификатор фильма
 * @param changes новые значения по именам настроек
 * @returns настройки после изменения и список действительно изменившихся
 */
export function updateSettings(
  movieId: number,
  changes: Record<string, unknown>,
): Promise<{ settings: SettingView[]; changedKeys: string[] }> {
  return request('PUT', `/movies/${movieId}/settings`, changes)
}
