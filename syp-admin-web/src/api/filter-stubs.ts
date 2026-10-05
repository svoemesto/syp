// Данные редактора фильтров.
//
// Бэкенда фильтров в проекте нет: `tbl_filters`, `tbl_filter_groups`,
// `tbl_filter_conditions` не заведены, эндпоинтов нет. Поэтому форма работает
// на заранее заданном примере, и каждая заглушка на экране помечена —
// иначе оператор принял бы пример за сохранённые фильтры и потом удивлялся
// бы, почему после перезагрузки их нет.
//
// Что берётся по-настоящему: список файлов и планы. Они приходят из
// каталога и структуры, то есть правая часть формы показывает настоящие
// планы; заглушка — только сам отбор по условиям.

import { ref } from 'vue'
import { readStructure, type StructureView } from './structure'

/** Что ищем условием. */
export type ConditionObjectClass =
  | 'PERSON'
  | 'PERSON_PROPERTY'
  | 'SHOT_PROPERTY'
  | 'SCENE_PROPERTY'
  | 'EVENT_PROPERTY'

/** Включено условие или исключено. */
export type ConditionIncluded = boolean

/** Где ищем: в плане, сцене или событии. */
export type ConditionSubject = 'SHOT' | 'SCENE' | 'EVENT'

/** Одно условие фильтра. */
export interface FilterCondition {
  /** Порядковый номер в группе. */
  order: number
  /** Что ищем. */
  objectClass: ConditionObjectClass
  /** Ключ выбранного объекта: персоны, свойства. */
  objectKey: string | null
  /** Отображаемое имя выбранного объекта. */
  objectName: string
  /** Включено или исключено. */
  isIncluded: ConditionIncluded
  /** Где ищем. */
  subject: ConditionSubject
}

/** Группа условий. */
export interface FilterGroup {
  /** Порядковый номер в фильтре. */
  order: number
  /** Имя группы. */
  name: string
  /** `true` — условия группы соединяются через И, `false` — через ИЛИ. */
  isAnd: boolean
  /** Условия группы. */
  conditions: FilterCondition[]
}

/** Фильтр проекта. */
export interface FilterDefinition {
  /** Порядковый номер. */
  order: number
  /** Имя фильтра. */
  name: string
  /** `true` — группы соединяются через И, `false` — через ИЛИ. */
  isAnd: boolean
  /** Группы фильтра. */
  groups: FilterGroup[]
}

/**
 * Формулировка условия словами.
 *
 * В старом проекте она собиралась в красной метке окна создания условия, и
 * больше нигде не показывалась: выбранный объект не был виден. Здесь та же
 * формулировка, но она же попадает в таблицу условий — иначе оператор читает
 * столбец «условие», не понимая, о ком речь.
 *
 * @param condition условие
 * @returns формулировка условия
 */
export function conditionName(condition: FilterCondition): string {
  const who = describeObject(condition)
  const subject = { SHOT: 'Shot', SCENE: 'Scene', EVENT: 'Event' }[condition.subject]
  return `${who} ${condition.isIncluded ? 'is included in' : 'is NOT included in'} ${subject}`
}

/**
 * Имя выбранного объекта по-русски, с ключом.
 *
 * Ключ показывается рядом с именем: в старом проекте его не было видно
 * нигде, и ошибиться в выборе персоны было нечем.
 *
 * @param condition условие
 * @returns текст вида «персона «Иван» [ключ 12]»
 */
function describeObject(condition: FilterCondition): string {
  const kind = {
    PERSON: 'Person',
    PERSON_PROPERTY: 'Person property',
    SHOT_PROPERTY: 'Shot property',
    SCENE_PROPERTY: 'Scene property',
    EVENT_PROPERTY: 'Event property',
  }[condition.objectClass]
  const key = condition.objectKey === null ? '' : ` [${condition.objectKey}]`
  return `${kind} «${condition.objectName}»${key}`
}

/** Подпись кнопки выбора объекта — в старом проекте она менялась по типу. */
export const SELECT_BUTTON_TEXT: Readonly<Record<ConditionObjectClass, string>> = {
  PERSON: 'Выбрать персону',
  PERSON_PROPERTY: 'Выбрать свойство персоны',
  SHOT_PROPERTY: 'Выбрать свойство плана',
  SCENE_PROPERTY: 'Выбрать свойство сцены',
  EVENT_PROPERTY: 'Выбрать свойство события',
}

/** Подпись кнопки подтверждения. */
export const CONFIRM_BUTTON_TEXT = 'Create new filter condition'

/**
 * Заготовка условия: по умолчанию ищем персону, включая её в план.
 *
 * Значения по умолчанию взяты из старого проекта — они соответствуют
 * первому включённому переключателю в каждой группе.
 *
 * @param order порядковый номер условия
 * @returns новое условие
 */
export function newCondition(order: number): FilterCondition {
  return {
    order,
    objectClass: 'PERSON',
    objectKey: null,
    objectName: '',
    isIncluded: true,
    subject: 'SHOT',
  }
}

/** Заготовка группы. */
export function newGroup(order: number): FilterGroup {
  return { order, name: '', isAnd: true, conditions: [] }
}

/** Заготовка фильтра. */
export function newFilter(order: number): FilterDefinition {
  return { order, name: '', isAnd: true, groups: [] }
}

/**
 * Заранее заданный пример фильтров.
 *
 * Пример взят из правдоподобного случая: «в плане есть Иван, сцена названа
 * так-то». Он показывает все три вида условий и обе разновидности
 * включения, чтобы форма была читаема сразу.
 */
const STARTER: FilterDefinition[] = [
  {
    order: 0,
    name: 'Планы с Иваном',
    isAnd: true,
    groups: [
      {
        order: 0,
        name: 'Главный герой',
        isAnd: true,
        conditions: [
          {
            order: 0,
            objectClass: 'PERSON',
            objectKey: null,
            objectName: '',
            isIncluded: true,
            subject: 'SHOT',
          },
        ],
      },
    ],
  },
  {
    order: 1,
    name: 'Scenes with a named property',
    isAnd: false,
    groups: [],
  },
]

/** Фильтры проекта: источник данных формы. */
export const filters = ref<FilterDefinition[]>(structuredClone(STARTER))

/**
 * Признак, что данные формы — заготовка, а не сохранённые фильтры.
 *
 * Показывается над формой: без пометки пример не отличить от настоящих
 * фильтров, и после перезагрузки страницы он исчез бы молча.
 */
export const IS_STUB = true

/**
 * Текст пометки о заглушке.
 */
export const STUB_NOTICE =
  'Stub: the project has no filters backend, so filters, groups and ' +
  'conditions live only in this form and disappear on reload. The file ' +
  'list and the shots on the right are real ones.'

/**
 *
 * Отбор — заглушка: он ничего не исключает и честно об этом говорит
 * вызывающим. Считать «отобранные» планы по заготовленным условиям без
 * данных о свойствах и персонах означало бы показать отбор, который ничего
 * не отбирает, — то есть выдать его за работу фильтра.
 */
export const selectedShots = ref<{ fileName: string; from: number; to: number }[]>([])

/**
 * Отбирает планы по фильтру.
 *
 * @param projectId проект
 * @param videofileIds отобранные файлы
 * @returns число показанных планов и текст о том, что отбор не выполняется
 */
export async function applyFilter(
  projectId: number,
  videofileIds: number[],
): Promise<{ shots: number; notice: string; structure: StructureView | null }> {
  const notice = `Stub: the project does not run the condition selection. Files selected: ${videofileIds.length}, проект: ${projectId}. The shots of the first selected file are shown, so that the right part of the form is not empty.`
  if (videofileIds.length === 0) {
    selectedShots.value = []
    return { shots: 0, notice, structure: null }
  }
  const structure = await readStructure(videofileIds[0], 0, 200)
  const shots = (structure.scenes ?? []).flatMap((scene) =>
    (scene.shots ?? []).map((shot) => ({
      fileName: `файл ${videofileIds[0]}`,
      from: shot.firstFrame,
      to: shot.lastFrame,
    })),
  )
  selectedShots.value = shots
  return { shots: shots.length, notice, structure }
}
