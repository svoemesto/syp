// Клиент приёма проектов и видеофайлов.
//
// Соответствует разделу 3 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах
// приходит вычисленным: клиент его не хранит и не передаёт обратно как
// источник правды (ADR-0001).

import { request } from './http'

/** Описание проекта в ответе. */
export interface ProjectView {
  /** Идентификатор проекта. */
  id: number
  /** Отображаемое имя проекта. */
  name: string
  /** Корень каталога проекта на машине администратора. */
  sourceRoot: string
  /** Дата создания проекта. */
  createdAt?: string | null
  /** Сколько видеофайлов заведено в проекте. */
  videofileCount: number
}

/** Описание видеофайла в ответе со всеми измеренными параметрами. */
export interface VideofileView {
  /** Идентификатор видеофайла. */
  id: number
  /** Идентификатор проекта-владельца. */
  projectId: number
  /** Порядковый номер видеофайла в проекте. */
  ordinal: number
  /** Отображаемое имя видеофайла. */
  name: string
  /** Абсолютный путь к исходному файлу. */
  sourcePath: string
  /** Путь относительно корня проекта: он и попадёт в сценарий сборки. */
  relativePath?: string | null
  /** Размер файла в байтах. */
  byteSize: number
  /** Время изменения файла. */
  fileMtime: string
  /** Число кадров видеофайла. */
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
  /** Длительность видеофайла, вычисленная по кадрам. */
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
  /** Готова ли видеофайл к работе. */
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

/** Ответ на создание проекта: сам проект и его настройки по умолчанию. */
export interface CreatedProjectView {
  /** Созданный проект. */
  project: ProjectView
  /** Значения настроек по умолчанию. */
  settings: SettingView[]
}

/** Ответ на чтение проекта вместе с его содержимым. */
export interface ProjectDetailView {
  /** Проект. */
  project: ProjectView
  /** Видеофайла проекта. */
  videofile: VideofileView[]
  /** Настройки проекта. */
  settings: SettingView[]
}

/**
 * Перечисляет проекты с числом видеофайлов каждого.
 *
 * @returns список проектов
 */
export function listProjects(): Promise<ProjectView[]> {
  return request<ProjectView[]>('GET', '/projects')
}

/**
 * Создаёт проект с корнем каталога на машине администратора.
 *
 * @param name название проекта
 * @param sourceRoot корневой каталог проекта: без него нельзя вычислить
 *   относительный путь к файлу, который попадёт в сценарий сборки (FR-089a)
 * @returns созданный проект вместе с настройками по умолчанию
 */
export function createProject(name: string, sourceRoot: string): Promise<CreatedProjectView> {
  return request<CreatedProjectView>('POST', '/projects', { name, sourceRoot })
}

/**
 * Читает проект, его видеофайла и настройки.
 *
 * @param projectId идентификатор проекта
 * @returns проект с содержимым
 */
export function readProject(projectId: number): Promise<ProjectDetailView> {
  return request<ProjectDetailView>('GET', `/projects/${projectId}`)
}

/**
 * Удаляет проект вместе с производными данными.
 *
 * Файлы архива при этом не трогаются: они принадлежат не системе.
 *
 * @param projectId идентификатор проекта
 */
export function deleteProject(projectId: number): Promise<null> {
  return request<null>('DELETE', `/projects/${projectId}`)
}

/**
 * Перечисляет видеофайлы проекта.
 *
 * @param projectId идентификатор проекта
 * @returns видеофайла в порядке порядковых номеров
 */
export function listVideofile(projectId: number): Promise<VideofileView[]> {
  return request<VideofileView[]>('GET', `/projects/${projectId}/videofiles`)
}

/**
 * Регистрирует видеофайл по пути к файлу.
 *
 * Параметры файла определяет система: оператор их не вводит (FR-002). Файл
 * обязан лежать внутри корня проекта, иначе ответ — ошибка
 * `SOURCE_UNREADABLE` с путём в тексте.
 *
 * @param projectId идентификатор проекта
 * @param sourcePath абсолютный путь к файлу внутри корня проекта
 * @param name название видеофайла; если не задано, берётся имя файла
 * @returns зарегистрированный видеофайл с измеренными параметрами
 */
export function registerVideofile(
  projectId: number,
  sourcePath: string,
  name?: string,
): Promise<VideofileView> {
  return request<VideofileView>('POST', `/projects/${projectId}/videofiles`, {
    sourcePath,
    name: name ?? null,
  })
}

/**
 * Читает параметры видеофайла и состояние готовности.
 *
 * @param videofileId идентификатор видеофайла
 * @returns параметры видеофайла
 */
export function readVideofile(videofileId: number): Promise<VideofileView> {
  return request<VideofileView>('GET', `/videofiles/${videofileId}`)
}

/**
 * Снимает видеофайл с учёта; файл источника не трогается.
 *
 * @param videofileId идентификатор видеофайла
 */
export function deleteVideofile(videofileId: number): Promise<null> {
  return request<null>('DELETE', `/videofiles/${videofileId}`)
}

/**
 * Читает настройки анализа и выдачи сценария.
 *
 * @param projectId идентификатор проекта
 * @returns настройки проекта
 */
export function readSettings(projectId: number): Promise<SettingView[]> {
  return request<SettingView[]>('GET', `/projects/${projectId}/settings`)
}

/**
 * Изменяет настройки анализа и выдачи сценария.
 *
 * @param projectId идентификатор проекта
 * @param changes новые значения по именам настроек
 * @returns настройки после изменения и список действительно изменившихся
 */
export function updateSettings(
  projectId: number,
  changes: Record<string, unknown>,
): Promise<{ settings: SettingView[]; changedKeys: string[] }> {
  return request('PUT', `/projects/${projectId}/settings`, changes)
}
