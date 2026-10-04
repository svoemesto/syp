// Клиент домена персонажей: лица, кластеры и персоны.
//
// Соответствует разделу 5 контракта
// `specs/001-first-vertical-slice/contracts/admin-api.md`. Время в ответах не
// приходит: номер кадра — единственный источник правды, клиент пересчитывает
// время от `time_base` видеофайла (ADR-0001).
//
// Документация публичных функций — по правилам проекта (FR-006).

import { request } from './http'

/** Персона проекта. */
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
  /** Видеофайл кадра фото. */
  photoVideofileId: number | null
  /** Номер кадра фото. */
  photoFrameNumber: number | null
}

/** Персоны проекта. */
export interface PersonsView {
  /** Проект. */
  projectId: number
  /** Персоны: сначала служебные, затем именованные по имени. */
  persons: PersonView[]
}

/** Лицо видеофайла. */
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

/** Страница лиц видеофайла. */
export interface FacesView {
  /** Видеофайл. */
  videofileId: number
  /** Проект-владелец: по нему клиент читает справочник персон. */
  projectId: number
  /** Ширина кадра видеофайла: по ней клиент кладёт рамку на миниатюру. */
  frameWidth: number
  /** Высота кадра видеофайла. */
  frameHeight: number
  /** Сколько лиц у видеофайла всего. */
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

/** Кластеры видеофайла. */
export interface FaceClustersView {
  /** Видеофайл. */
  videofileId: number
  /** Ширина кадра видеофайла: по ней клиент кладёт рамку на миниатюру. */
  frameWidth: number
  /** Высота кадра видеофайла. */
  frameHeight: number
  /** Ключ модели эмбеддингов, которой получены векторы. */
  embeddingModelKey: string
  /** Сколько кластеров без имени у видеофайла. */
  clustersTotal: number
  /**
   * Сколько лиц отнесено к именованным кластерам, то есть к персонам.
   *
   * Нужно экрану операций: без этого числа колонка «лица распознаны» стоила
   * бы «неизвестно», хотя ответ уже посчитан вместе с кластерами.
   */
  facesNamed: number
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

/** Ответ на постановку поиска лиц в очередь. */
export interface FaceScanEnqueuedView {
  /** Номер задания в очереди. */
  jobId: number
  /** Видеофайл, для которого поставлено задание. */
  videofileId: number
  /** Ключ модели эмбеддингов, которой посчитаются векторы. */
  embeddingModelKey: string
}

/**
 * Ставит в очередь поиск лиц видеофайла.
 *
 * Задание одно на детекцию, выделение векторов и превью: отдельных операций
 * на каждое действие в проекте нет, и экран операций отмечает это под
 * чекбоксами.
 *
 * @param videofileId идентификатор видеофайла
 * @returns номер задания и ключ модели эмбеддингов
 */
export function startFacesScan(videofileId: number): Promise<FaceScanEnqueuedView> {
  return request<FaceScanEnqueuedView>('POST', `/videofiles/${videofileId}/faces`)
}

/**
 * Читает лица видеофайла.
 *
 * @param videofileId идентификатор видеофайла
 * @param offset смещение выборки
 * @param limit размер выборки
 * @returns страница лиц видеофайла
 */
export function readFaces(videofileId: number, offset = 0, limit = 200): Promise<FacesView> {
  return request<FacesView>(
    'GET',
    `/videofiles/${videofileId}/faces?offset=${offset}&limit=${limit}`,
  )
}

/**
 * Читает кластеры похожих лиц видеофайла без имени.
 *
 * @param videofileId идентификатор видеофайла
 * @returns кластеры видеофайла
 */
export function readClusters(videofileId: number): Promise<FaceClustersView> {
  return request<FaceClustersView>('GET', `/videofiles/${videofileId}/faces/clusters`)
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
 * Читает персон проекта.
 *
 * @param projectId идентификатор проекта
 * @returns персоны проекта
 */
export function readPersons(projectId: number): Promise<PersonsView> {
  return request<PersonsView>('GET', `/projects/${projectId}/persons`)
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
 * @param videofileId идентификатор видеофайла
 * @param frame номер кадра
 * @returns адрес листа превью
 */
export function facePreviewUrl(videofileId: number, frame: number): string {
  return `/api/videofiles/${videofileId}/preview-sheets/0?frame=${frame}`
}

/** Ответ на пометку эталонов. */
export interface FaceExamplesMarkedView {
  /** Сколько лиц реально изменилось. */
  changed: number
  /** Новое значение метки. */
  isExample: boolean
}

/**
 * Ставит или снимает метку эталона на лицах.
 *
 * Эталон — подтверждение оператора, что это знакомый человек. Метку ставит
 * человек, а не алгоритм: проставленный автоматически эталон обучил бы модель
 * на её же предположении.
 *
 * @param videofileId видеофайл-владелец лиц
 * @param faceIds лица, которым меняют метку
 * @param isExample новое значение метки
 * @returns сколько лиц изменилось
 */
export function markFaceExamples(
  videofileId: number,
  faceIds: number[],
  isExample: boolean,
): Promise<FaceExamplesMarkedView> {
  return request<FaceExamplesMarkedView>('PATCH', `/videofiles/${videofileId}/faces/example`, {
    faceIds,
    isExample,
  })
}

/** Ответ на назначение лиц персонам. */
export interface FacesAssignedView {
  facesAssigned: number
  personId: number
}

/** Заводит персону по имени: имена от оператора работают и без модели распознавания. */
export async function createPerson(videofileId: number, name: string): Promise<PersonView> {
  return await request<PersonView>('POST', `/videofiles/${videofileId}/persons`, { name })
}

/** Назначает лица персонам: перенос ошибочно попавших к другой. */
export async function assignFacesToPerson(
  videofileId: number,
  personId: number,
  faceIds: number[],
): Promise<FacesAssignedView> {
  return await request<FacesAssignedView>('PATCH', `/videofiles/${videofileId}/faces/person`, {
    personId,
    faceIds,
  })
}

/** Записывает кадр, на котором персона видна: фото, выбранное оператором. */
export async function setPersonPhoto(
  videofileId: number,
  personId: number,
  frameNumber: number,
): Promise<PersonView> {
  return await request<PersonView>('PATCH', `/persons/${personId}/photo`, {
    videofileId,
    frameNumber,
  })
}

/**
 * Читает лица видеофайла по перечню идентификаторов.
 *
 * Нужны миниатюры кластеров: у кластера есть только идентификаторы лиц.
 *
 * @param videofileId идентификатор видеофайла
 * @param ids идентификаторы лиц
 * @returns запрошенные лица
 */
export async function readFacesByIds(videofileId: number, ids: number[]): Promise<FacesView> {
  const response = await fetch(`/api/videofiles/${videofileId}/faces/by-ids?ids=${ids.join(',')}`)
  if (!response.ok) {
    throw new Error(`Лица по перечню не отданы: ${response.status}`)
  }
  return (await response.json()) as FacesView
}

/**
 * Адрес миниатюры лица.
 *
 * Вырезку делает сервер: лицо в кадре бывает в два десятка пикселей, и
 * растянуть его в браузере до размера ячейки можно только потеряв резкость.
 *
 * @param faceId идентификатор лица
 * @param size сторона миниатюры в пикселях
 * @returns адрес миниатюры
 */
export function faceImageUrl(faceId: number, size: number): string {
  return `/api/faces/${faceId}/image?size=${size}`
}
