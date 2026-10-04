<script setup lang="ts">
/**
 * Вкладка сцен: сцены файла, планы выбранных сцен, персоны и свойства сцены.
 *
 * Форма перенесена по старому проекту. Отличие сцены от события — в цвете и
 * стороне отметки на миниатюре плана: сцена помечается **оранжевым слева**,
 * событие — зелёным справа. Поэтому здесь оранжевая полоса слева.
 *
 * В отличие от старого проекта удаление сцен работает: там обработчик удаления
 * был пустым, кнопка на месте, а действия нет.
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  mergeScenes,
  moveSceneBoundary,
  readStructure,
  splitScene,
  type SceneView,
  type ShotView,
} from '../api/structure'
import ShotThumb from './ShotThumb.vue'
import { readFaces, readPersons, type PersonView } from '../api/characters'

const props = defineProps<{ videofileId: number }>()

/** Ширина миниатюры кадра в колонках FROM и TO, как в старой форме. */
const THUMB = 96

/** Сцены файла. */
const scenes = ref<SceneView[]>([])

/** Персоны проекта. */
const persons = ref<PersonView[]>([])

/** Идентификаторы выбранных сцен: выбор множественный. */
const selected = ref<number[]>([])

/** Ключ и значение свойства сцены. */
const propertyKey = ref('')
const propertyValue = ref('')

/** Свойства сцены по идентификатору сцены. */
const properties = ref<Record<number, Array<{ key: string; value: string }>>>({})

const error = ref('')
const notice = ref('')

/** Сцены, выбранные оператором. */
const chosen = computed(() => scenes.value.filter((scene) => selected.value.includes(scene.id)))

/** Планы выбранных сцен, объединённые по всем выбранным. */
const chosenShots = computed<ShotView[]>(() => chosen.value.flatMap((scene) => scene.shots))

/** Свойства первой из выбранных сцен — как в старом проекте, где таблица одна. */
const chosenProperties = computed(() => {
  const first = chosen.value[0]
  return first === undefined ? [] : (properties.value[first.id] ?? [])
})

/**
 * Переключает выделение сцены.
 *
 * @param id идентификатор сцены
 */
function toggle(id: number): void {
  selected.value = selected.value.includes(id)
    ? selected.value.filter((item) => item !== id)
    : [...selected.value, id]
}

/** Создаёт сцену по выбранным планам. */
function createFromShots(): void {
  if (chosenShots.value.length === 0) {
    notice.value = 'Создание сцены идёт по выбранным планам: выберите планы в левой части'
    return
  }
  const ordered = [...chosenShots.value].sort((left, right) => left.firstFrame - right.firstFrame)
  for (let index = 1; index < ordered.length; index += 1) {
    if (ordered[index].firstFrame !== ordered[index - 1].lastFrame + 1) {
      notice.value = 'Выделены планы не подряд: сцена из разорванного куска не получается'
      return
    }
  }
  notice.value = 'Создание сцены по выбранным планам ещё не ходит в бэкенд: эндпоинта нет'
}

/** Удаляет выбранные сцены. */
function removeSelected(): void {
  if (selected.value.length === 0) {
    notice.value = 'Не выбрано ни одной сцены: удалять нечего'
    return
  }
  notice.value = 'Удаление сцен ещё не ходит в бэкенд: эндпоинта нет'
}

/** Добавляет свойство выбранной сцене. */
function addProperty(): void {
  const first = chosen.value[0]
  if (first === undefined) {
    notice.value = 'Свойство добавляется к выбранной сцене: выберите сцену'
    return
  }
  if (propertyKey.value.trim() === '') {
    notice.value = 'Ключ свойства обязателен: без него значение не к чему привязать'
    return
  }
  const existing = properties.value[first.id] ?? []
  properties.value = {
    ...properties.value,
    [first.id]: [
      ...existing.filter((item) => item.key !== propertyKey.value.trim()),
      { key: propertyKey.value.trim(), value: propertyValue.value },
    ],
  }
  propertyKey.value = ''
  propertyValue.value = ''
  notice.value = 'свойство сохранено'
}

/** Удаляет свойство выбранной сцены. */
function removeProperty(key: string): void {
  const first = chosen.value[0]
  if (first === undefined) {
    return
  }
  properties.value = {
    ...properties.value,
    [first.id]: (properties.value[first.id] ?? []).filter((item) => item.key !== key),
  }
  notice.value = 'свойство удалено'
}

/** Двигает начало сцены на кадр. */
async function moveBoundary(frame: number, delta: number): Promise<void> {
  const first = chosen.value[0]
  if (first === undefined) {
    notice.value = 'Граница двигается у выбранной сцены: выберите сцену'
    return
  }
  try {
    await moveSceneBoundary(props.videofileId, frame, frame + delta)
    notice.value = `граница сцены перенесена с кадра ${frame} на ${frame + delta}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Разрезает сцену по кадру. */
async function splitAt(frame: number): Promise<void> {
  if (chosen.value.length === 0) {
    notice.value = 'Разрез идёт по выбранной сцене: выберите сцену'
    return
  }
  try {
    await splitScene(props.videofileId, frame)
    notice.value = `сцена разрезана по кадру ${frame}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Сливает выбранные сцены по кадру. */
async function mergeAt(frame: number): Promise<void> {
  if (chosen.value.length < 2) {
    notice.value = 'Слияние нужно для двух и более сцен: выбрано иное количество'
    return
  }
  try {
    await mergeScenes(props.videofileId, frame)
    notice.value = `сцены слиты по кадру ${frame}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Перечитывает сцены и персон. */
async function reload(): Promise<void> {
  try {
    scenes.value = (await readStructure(props.videofileId)).scenes
    // Проекта в ответе структуры нет: персоны принадлежат проекту, а не
    // видеофайлу, и проект приходит из ответа по лицам.
    const faces = await readFaces(props.videofileId, 0, 1)
    persons.value = (await readPersons(faces.projectId)).persons
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

onMounted(reload)

watch(
  () => props.videofileId,
  () => {
    selected.value = []
    void reload()
  },
)
</script>

<template>
  <section class="scenes">
    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <div class="scenes-grid">
      <div class="column column-wide">
        <div class="syp-card-title">Сцены файла</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>NAME</th>
              <th>FROM</th>
              <th>TO</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="scene in scenes"
              :key="scene.id"
              :class="{ selected: selected.includes(scene.id) }"
              @click="toggle(scene.id)"
            >
              <td>{{ scene.title ?? `Сцена ${scene.id}` }}</td>
              <td class="thumb-cell">
                <span class="shot-mark" />
                <ShotThumb
                  :videofile-id="props.videofileId"
                  :frame-number="scene.firstFrame"
                  :width="THUMB"
                />
              </td>
              <td class="thumb-cell">
                <ShotThumb
                  :videofile-id="props.videofileId"
                  :frame-number="scene.lastFrame"
                  :width="THUMB"
                />
              </td>
            </tr>
            <tr v-if="scenes.length === 0">
              <td colspan="3" class="empty">Сцен нет</td>
            </tr>
          </tbody>
        </table>
        <div class="actions">
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="createFromShots">
            Создать сцену по выбранным планам
          </button>
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="removeSelected">
            Удалить выбранные сцены
          </button>
        </div>
        <p class="legend">
          Сцена на миниатюре плана помечается <span class="swatch scene">оранжевым слева</span>,
          событие — <span class="swatch event">зелёным справа</span>.
        </p>
      </div>

      <div class="column">
        <div class="syp-card-title">Планы выбранных сцен</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>FROM</th>
              <th>TO</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="shot in chosenShots" :key="shot.id">
              <td class="thumb-cell">
                <ShotThumb
                  :videofile-id="props.videofileId"
                  :frame-number="shot.firstFrame"
                  :width="THUMB"
                />
              </td>
              <td class="thumb-cell">
                <ShotThumb
                  :videofile-id="props.videofileId"
                  :frame-number="shot.lastFrame"
                  :width="THUMB"
                />
              </td>
            </tr>
            <tr v-if="chosenShots.length === 0">
              <td colspan="2" class="empty">Планов нет</td>
            </tr>
          </tbody>
        </table>
        <div class="actions">
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length === 0"
            @click="moveBoundary(chosen[0]?.firstFrame ?? 0, -1)"
          >
            Начало левее
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length === 0"
            @click="moveBoundary(chosen[0]?.firstFrame ?? 0, 1)"
          >
            Начало правее
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length === 0"
            @click="splitAt(chosen[0]?.firstFrame ?? 0)"
          >
            Разрезать
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length < 2"
            @click="mergeAt(chosen[0]?.lastFrame ?? 0)"
          >
            Слить
          </button>
        </div>
      </div>

      <div class="column">
        <div class="syp-card-title">Персоны выбранных сцен</div>
        <ul class="persons">
          <li v-for="person in persons" :key="person.id">{{ person.name }}</li>
          <li v-if="persons.length === 0" class="empty">Персон нет</li>
        </ul>
      </div>
    </div>

    <div class="properties">
      <div class="syp-card-title">Свойства сцены</div>
      <table class="table table-sm">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="property in chosenProperties" :key="property.key">
            <td>{{ property.key }}</td>
            <td>{{ property.value }}</td>
            <td>
              <button
                type="button"
                class="btn btn-sm btn-outline-secondary"
                @click="removeProperty(property.key)"
              >
                удалить
              </button>
            </td>
          </tr>
          <tr v-if="chosenProperties.length === 0">
            <td colspan="3" class="empty">Свойств нет</td>
          </tr>
        </tbody>
      </table>
      <div class="fields">
        <input v-model="propertyKey" class="form-control" placeholder="Key" />
        <textarea
          v-model="propertyValue"
          class="form-control"
          rows="2"
          placeholder="Value"
        ></textarea>
        <button type="button" class="btn btn-primary" @click="addProperty">
          Добавить свойство
        </button>
      </div>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
/* Ячейка с миниатюрой: кадр и подпись занимают всю ширину колонки. */
.column-wide table th:first-child,
.column-wide table td:first-child {
  min-width: 7.5rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thumb-cell {
  padding: 0.2rem;
  vertical-align: top;
}

.event-mark {
  display: inline-block;
  width: 0;
  height: 0;
  border-top: 0.8rem solid transparent;
  border-bottom: 0.8rem solid transparent;
  border-left: 0.6rem solid var(--syp-success);
  float: right;
}

.scenes {
  display: grid;
  gap: 1rem;
}

.scenes-grid {
  display: grid;
  gap: 1rem;
  grid-template-columns: minmax(28rem, 2fr) minmax(14rem, 1fr) minmax(10rem, 1fr);
}

.column {
  border: 1px solid var(--syp-border);
  border-radius: 4px;
  padding: 0.5rem;
}

.selected {
  background: var(--syp-tint);
}

.shot-mark {
  border-left: 4px solid var(--syp-warning);
  display: inline-block;
  padding-left: 0.25rem;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-top: 0.5rem;
}

.fields {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr 2fr auto;
  margin-top: 0.5rem;
}

.persons {
  list-style: none;
  margin: 0;
  padding: 0;
}

.legend {
  color: var(--syp-text-muted);
  font-size: 0.85rem;
  margin: 0.5rem 0 0;
}

.swatch {
  border: 1px solid var(--syp-text-muted);
  display: inline-block;
  height: 0.6rem;
  vertical-align: middle;
  width: 0.6rem;
}

.swatch.event {
  background: var(--syp-success);
}

.swatch.scene {
  background: var(--syp-warning);
}

.empty {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
}
</style>
