<script setup lang="ts">
/**
 * Вкладка сцен: сцены файла, планы выбранных сцен, персоны и свойства сцены.
 *
 * Форма перенесена по старому проекту. Отличие сцены от события — в цвете и
 * стороне отметки на миниатюре плана: сцена помечается **orange on the left**,
 * событие — green on the right. Поэтому здесь оранжевая полоса слева.
 *
 * В отличие от старого проекта удаление сцен работает: там обработчик удаления
 * был пустым, кнопка на месте, а действия none.
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

/** File scenes. */
const scenes = ref<SceneView[]>([])

/** Персоны проекта. */
const persons = ref<PersonView[]>([])

/** Идентификаторы выбранных сцен: выбор множественный. */
const selected = ref<number[]>([])

/** Ключ и значение свойства сцены. */
const propertyKey = ref('')
const propertyValue = ref('')

/** Scene properties по идентификатору сцены. */
const properties = ref<Record<number, Array<{ key: string; value: string }>>>({})

const error = ref('')
const notice = ref('')

/** Сцены, выбранные оператором. */
const chosen = computed(() => scenes.value.filter((scene) => selected.value.includes(scene.id)))

/** Shots of selected scenes, объединённые по всем выбранным. */
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
    notice.value = 'Scene creation is under way по выбранным планам: выберите планы в левой части'
    return
  }
  const ordered = [...chosenShots.value].sort((left, right) => left.firstFrame - right.firstFrame)
  for (let index = 1; index < ordered.length; index += 1) {
    if (ordered[index].firstFrame !== ordered[index - 1].lastFrame + 1) {
      notice.value = 'Selected shots: nе подряд: сцена из разорванного куска не получается'
      return
    }
  }
  notice.value = 'Scene creation by выбранным планам ещё не ходит в бэкенд: эндпоинта нет'
}

/** Удаляет выбранные сцены. */
function removeSelected(): void {
  if (selected.value.length === 0) {
    notice.value = 'Not a single shot selectedой сцены: удалять нечего'
    return
  }
  notice.value = 'Scene deletion is not не ходит в бэкенд: эндпоинта нет'
}

/** Добавляет свойство выбранной сцене. */
function addProperty(): void {
  const first = chosen.value[0]
  if (first === undefined) {
    notice.value = 'Property addedется к выбранной сцене: выберите сцену'
    return
  }
  if (propertyKey.value.trim() === '') {
    notice.value = 'The property key is required: there is nothing to bind the value to without it'
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
  notice.value = 'property saved'
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
  notice.value = 'property deleted'
}

/** Двигает начало сцены на кадр. */
async function moveBoundary(frame: number, delta: number): Promise<void> {
  const first = chosen.value[0]
  if (first === undefined) {
    notice.value = 'The boundary moves у выбранной сцены: выберите сцену'
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
    notice.value = 'The cut follows the selected scene: choose a scene'
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
        <div class="syp-card-title">File scenes</div>
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
              <td>{{ scene.title ?? `Scene ${scene.id}` }}</td>
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
              <td colspan="3" class="empty">No scenes</td>
            </tr>
          </tbody>
        </table>
        <div class="actions">
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="createFromShots">
            Create a scene from the selected shots
          </button>
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="removeSelected">
            Delete the selected scenes
          </button>
        </div>
        <p class="legend">
          A scene is marked on the shot thumbnail
          <span class="swatch scene">orange on the left</span>, event —
          <span class="swatch event">green on the right</span>.
        </p>
      </div>

      <div class="column">
        <div class="syp-card-title">Shots of selected scenes</div>
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
              <td colspan="2" class="empty">No shots</td>
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
            Start is further left
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length === 0"
            @click="moveBoundary(chosen[0]?.firstFrame ?? 0, 1)"
          >
            Start is further right
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length === 0"
            @click="splitAt(chosen[0]?.firstFrame ?? 0)"
          >
            Cut
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="chosen.length < 2"
            @click="mergeAt(chosen[0]?.lastFrame ?? 0)"
          >
            Merge
          </button>
        </div>
      </div>

      <div class="column">
        <div class="syp-card-title">Persons of selected scenes</div>
        <ul class="persons">
          <li v-for="person in persons" :key="person.id">{{ person.name }}</li>
          <li v-if="persons.length === 0" class="empty">No persons</li>
        </ul>
      </div>
    </div>

    <div class="properties">
      <div class="syp-card-title">Scene properties</div>
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
                delete
              </button>
            </td>
          </tr>
          <tr v-if="chosenProperties.length === 0">
            <td colspan="3" class="empty">No properties</td>
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
        <button type="button" class="btn btn-primary" @click="addProperty">Add property</button>
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
