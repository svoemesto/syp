// Клиент домена персонажей: лица, кластеры и персоны.
//
// Соответствует разделу 5 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах не
// приходит: номер кадра — единственный источник правды, клиент пересчитывает
// время от `time_base` серии (ADR-0001).
//
// Документация публичных функций — по правилам проекта (FR-006).

import { request } from './http'

/** Персона сериала. */
export interface PersonView {
  /** Идентификатор персоны. */
  id: number
  /** Отображаемое имя. */
  name: string
  /** Вид: `PERSON`, `UNRECOGNIZED` или `NONPERSON`. */
  kind: string
  /** Служебная ли это персона-заглушка. */
  isService: boolean
  /** Ключ класса в модели; у служебных персон пуст. */
  recognizerKey: string | null
}

/** Персоны сериала. */
export interface PersonsView {
  /** Сериал. */
  serialId: number
  /** Персоны: сначала служебные, затем именованные по имени. */
  persons: PersonView[]
}

/** Лицо серии. */
export interface FaceView {
  /** Идентификатор лица. */
  id: number
  /** Номер кадра, нумерация с нуля. */
  frameNumber: number
  /** Порядковый номер лица в кадре, с нуля. */
  faceIndex: number
  /** Левая граница рамки, пиксели кадра. */
  x1: number
  /** Верхняя граница рамки, пиксели кадра. */
  y1: number
  /** Правая граница рамки, пиксели кадра. */
  x2: number
  /** Нижняя граница рамки, пиксели кадра. */
  y2: number
  /** План, которому принадлежит лицо, либо `null`. */
  shotId: number | null
  /** Персона лица; непустая всегда. */
  personId: number
  /** Отображаемое имя персоны. */
  personName: string
  /** Вид персоны. */
  personKind: string
  /** Происхождение рамки: `AUTO` или `OPERATOR`. */
  origin: string
  /** Помечено ли лицо эталоном для обучения. */
  isExample: boolean
  /** Уверенность детектора либо `null`. */
  detectConfidence: number | null
}

/** Страница лиц серии. */
export interface FacesView {
  /** Серия. */
  seriesId: number
  /** Сериал-владелец: по нему клиент читает справочник персон. */
  serialId: number
  /** Ширина кадра серии: по ней клиент кладёт рамку на миниатюру. */
  frameWidth: number
  /** Высота кадра серии. */
  frameHeight: number
  /** Сколько лиц у серии всего. */
  facesTotal: number
  /** Смещение выборки. */
  offset: number
  /** Размер выборки. */
  limit: number
  /** Лица выборки. */
  faces: FaceView[]
}

/** Кластер похожих лиц без имени. */
export interface FaceClusterView {
  /** Ключ кластера; его же принимает `nameCluster`. */
  id: string
  /** Сколько лиц в кластере. */
  size: number
  /** Идентификаторы лиц кластера. */
  faceIds: number[]
  /** Лицо, рамка которого показывается картинкой. */
  thumbnailFaceId: number
}

/** Кластеры серии. */
export interface FaceClustersView {
  /** Серия. */
  seriesId: number
  /** Ширина кадра серии: по ней клиент кладёт рамку на миниатюру. */
  frameWidth: number
  /** Высота кадра серии. */
  frameHeight: number
  /** Ключ модели эмбеддингов, которой получены векторы. */
  embeddingModelKey: string
  /** Сколько кластеров без имени у серии. */
  clustersTotal: number
  /** Кластеры по убыванию числа лиц. */
  clusters: FaceClusterView[]
}

/** Ответ на постановку имени кластеру. */
export interface ClusterNamedView {
  /** Созданная персона. */
  personId: number
  /** Отображаемое имя. */
  name: string
  /** Ключ класса в модели. */
  recognizerKey: string
  /** Сколько лиц переведено этой персоне. */
  facesAssigned: number
}

/**
 * Читает лица серии.
 *
 * @param seriesId идентификатор серии
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница лиц серии
 */
export function readFaces(seriesId: number, offset = 0, limit = 200): Promise<FacesView> {
  return request<FacesView>('GET', `/series/${seriesId}/faces?offset=${offset}&limit=${limit}`)
}

/**
 * Читает кластеры похожих лиц серии без имени.
 *
 * @param seriesId идентификатор серии
 * @returns кластеры серии
 */
export function readClusters(seriesId: number): Promise<FaceClustersView> {
  return request<FaceClustersView>('GET', `/series/${seriesId}/faces/clusters`)
}

/**
 * Даёт кластеру имя: заводит персону и назначает её лицам кластера.
 *
 * Работа занимает секунды, но идёт записью в базу, поэтому ответ ожидается
 * здесь — в отличие от постановки анализа, которая уходит в очередь.
 *
 * @param clusterId ключ кластера
 * @param name отображаемое имя персоны
 * @param recognizerKey ключ класса в модели; по умолчанию берётся ключ кластера
 * @returns созданная персона с числом назначенных лиц
 */
export function nameCluster(
  clusterId: string,
  name: string,
  recognizerKey?: string,
): Promise<ClusterNamedView> {
  return request<ClusterNamedView>('POST', `/clusters/${clusterId}/person`, {
    name,
    recognizerKey: recognizerKey ?? null,
  })
}

/**
 * Читает персон сериала.
 *
 * @param serialId идентификатор сериала
 * @returns персоны сериала
 */
export function readPersons(serialId: number): Promise<PersonsView> {
  return request<PersonsView>('GET', `/serials/${serialId}/persons`)
}

/**
 * Переименовывает именованную персону.
 *
 * @param personId идентификатор персоны
 * @param name новое отображаемое имя
 * @returns переименованная персона
 */
export function renamePerson(personId: number, name: string): Promise<PersonView> {
  return request<PersonView>('PATCH', `/persons/${personId}`, { name })
}

/**
 * Удаляет именованную персону.
 *
 * Лица персоны при этом не удаляются — они переходят в неопознанные (FR-036).
 *
 * @param personId идентификатор персоны
 * @returns пустой ответ при коде `204`
 */
export function deletePerson(personId: number): Promise<null> {
  return request<null>('DELETE', `/persons/${personId}`)
}

/**
 * Адрес листа превью с кадром, которому принадлежит лицо.
 *
 * Отдельная функция вместо строки в шаблоне: адрес собирается в одном месте,
 * и переименование пути не потребует правок по экрану.
 *
 * @param seriesId идентификатор серии
 * @param frame номер кадра
 * @returns адрес листа превью
 */
export function facePreviewUrl(seriesId: number, frame: number): string {
  return `/api/series/${seriesId}/preview-sheets/0?frame=${frame}`
}
