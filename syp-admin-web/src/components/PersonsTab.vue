<script setup lang="ts">
/**
 * Вкладка персон: лица файла и персоны, которым они принадлежат.
 *
 * Форма перенесена по старому проекту. Слева — персоны файла, они же цель для
 * перетаскивания; справа — матрица миниатюр лиц и страницы лиц. Сверху фильтры
 * типов faces: не exemplar, exemplar, не ручной, ручной; в старом проекте они стоят в
 * левой части окна, хотя управляют именно этой вкладкой.
 *
 * Порядок работы оператора, ради которого вкладка и делается: выбрать лицо или
 * несколько, назначить персону, пометить exemplar, поставить кадр в photo персоны.
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  assignFacesToPerson,
  markFaceExamples,
  readFaces,
  readPersons,
  setPersonPhoto,
  type FaceView,
  type FacesView,
  type PersonView,
  facePreviewUrl,
} from '../api/characters'
import FaceThumbnails from './FaceThumbnails.vue'
import PersonSelectDialog from './PersonSelectDialog.vue'
import PersonEditDialog from './PersonEditDialog.vue'

const props = defineProps<{ videofileId: number }>()

/** Лица текущей страницы вместе с размером кадра. */
const faces = ref<FacesView | null>(null)

/** Персоны проекта: цель для перетаскивания и для назначения. */
const persons = ref<PersonView[]>([])

/** Идентификаторы выбранных лиц. */
const selectedFaces = ref<number[]>([])

/** Идентификатор персоны, которой назначаются выбранные лица. */
const targetPerson = ref<number | null>(null)

/** Номер страницы лиц, начиная с нуля. */
const page = ref(0)

/** Фильтры типов лиц. Все включены, как в старом проекте. */
const filters = ref({ notExample: true, example: true, notManual: true, manual: true })

/** Что показать оператору: успех или причина отказа. */
const notice = ref('')

const error = ref('')

/** Идентификатор проекта: он нужен окну выбора персоны. */
const projectId = ref(0)

/** Открыто ли окно выбора персоны. */
const selectOpen = ref(false)

/** Person, которую правят в открытом окне. */
const editing = ref<PersonView | null>(null)

/** Лица, прошедшие фильтры типов. */
const visibleFaces = computed<FaceView[]>(() =>
  (faces.value?.faces ?? []).filter((face) => {
    if (face.isExample && !filters.value.example) return false
    if (!face.isExample && !filters.value.notExample) return false
    return true
  }),
)

/** Всего страниц лиц. */
const pages = computed(() =>
  Math.max(1, Math.ceil((faces.value?.facesTotal ?? 0) / (faces.value?.limit || 1))),
)

/** Person, которой назначаются выбранные лица. */
const target = computed(() => persons.value.find((person) => person.id === targetPerson.value))

/**
 * Переключает выделение лица.
 *
 * @param faceId идентификатор лица
 */
function toggleFace(faceId: number): void {
  selectedFaces.value = selectedFaces.value.includes(faceId)
    ? selectedFaces.value.filter((id) => id !== faceId)
    : [...selectedFaces.value, faceId]
}

/** Снимает выделение со всех лиц. */
function clearSelection(): void {
  selectedFaces.value = []
}

/** Назначает выбранные лица выбранной персоне. */
async function assignToPerson(personId: number): Promise<void> {
  const person = persons.value.find((item) => item.id === personId)
  if (person === undefined) {
    notice.value = 'Person не найдена: назначать некого'
    return
  }
  if (selectedFaces.value.length === 0) {
    notice.value = 'No face is selected: there is nothing to assign'
    return
  }
  try {
    await assignFacesToPerson(props.videofileId, person.id, selectedFaces.value)
    notice.value = `лиц перенесено к «${person.name}»: ${selectedFaces.value.length}`
    error.value = ''
    selectOpen.value = false
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Открывает окно выбора персоны. */
function openSelect(): void {
  if (selectedFaces.value.length === 0) {
    notice.value = 'No face is selected: there is nothing to choose a person for'
    return
  }
  selectOpen.value = true
}

/** Помечает выбранные лица эталонами и снимает пометку. */
async function markExamples(marked: boolean): Promise<void> {
  if (selectedFaces.value.length === 0) {
    notice.value = 'No face is selected: there is nothing to mark'
    return
  }
  try {
    const answer = await markFaceExamples(props.videofileId, selectedFaces.value, marked)
    notice.value = `помечено эталоном: ${answer.isExample ? 'yes' : 'no'}, изменено faces: ${answer.changed}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Ставит кадр выбранного лица в photo выбранной персоны. */
async function makePhoto(): Promise<void> {
  const face = (faces.value?.faces ?? []).find((item) => selectedFaces.value.includes(item.id))
  if (face === undefined) {
    notice.value = 'Фото берётся с кадра выбранного лица: выберите лицо'
    return
  }
  if (target.value === undefined) {
    notice.value = 'Choose the person to whom it belongs photo'
    return
  }
  try {
    await setPersonPhoto(props.videofileId, target.value.id, face.frameNumber)
    notice.value = `photo персоны «${target.value.name}» взято с кадра ${face.frameNumber}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Лицо приняло перетаскивание персоны. */
function onDrop(personId: number): void {
  void assignToPerson(personId)
}

/**
 * Переходит на страницу лиц по номеру из таблицы страниц.
 *
 * В старой форме страницы перебираются таблицей со столбцом «#», а не
 * кнопками «предыдущая» и «следующая»: оператор видит, сколько страниц
 * осталось, и попадает на нужную сразу.
 *
 * @param index номер страницы с нуля
 */
async function goToPage(index: number): Promise<void> {
  if (index < 0 || index >= pages.value || index === page.value) {
    return
  }
  page.value = index
  clearSelection()
  await reload()
}

/**
 * Кладёт идентификатор персоны в переносимые данные строки.
 *
 * @param event событие начала переноса
 * @param personId номер персоны
 */
function onDragStart(event: DragEvent, personId: number): void {
  event.dataTransfer?.setData('text/plain', String(personId))
}

/** Перечитывает лица и персон. */
async function reload(): Promise<void> {
  try {
    faces.value = await readFaces(
      props.videofileId,
      page.value * (faces.value?.limit ?? 200),
      faces.value?.limit ?? 200,
    )
    const loaded = faces.value?.projectId
    if (loaded !== undefined) {
      projectId.value = loaded
      persons.value = (await readPersons(loaded)).persons
    }
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

onMounted(reload)

watch(
  () => props.videofileId,
  () => {
    page.value = 0
    clearSelection()
    void reload()
  },
)
</script>

<template>
  <section class="persons-tab">
    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <div class="persons-tab-body">
      <aside
        class="persons-list"
        :class="{ over: targetPerson !== null && selectedFaces.length > 0 }"
        @dragover.prevent
        @drop.prevent="onDrop(Number($event.dataTransfer?.getData('text/plain')))"
      >
        <table class="table table-sm persons-table">
          <thead>
            <tr>
              <th>Name</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="person in persons"
              :key="person.id"
              :class="{ selected: targetPerson === person.id }"
              draggable="true"
              @click="targetPerson = person.id"
              @dragstart="onDragStart($event, person.id)"
              @dblclick="editing = person"
            >
              <td>
                <img
                  v-if="person.photoFrameNumber !== null"
                  :src="facePreviewUrl(props.videofileId, person.photoFrameNumber)"
                  :alt="`Person photo ${person.name}`"
                  class="person-photo"
                />
                <span>{{ person.name }}</span>
              </td>
            </tr>
            <tr v-if="persons.length === 0">
              <td class="empty">No persons</td>
            </tr>
          </tbody>
        </table>
        <p class="hint">Drag a face onto a person row to assign it</p>
      </aside>

      <div class="faces-area">
        <div class="toolbar">
          <fieldset class="filters">
            <legend>Face types</legend>
            <label><input v-model="filters.notExample" type="checkbox" /> Not reference</label>
            <label><input v-model="filters.example" type="checkbox" /> Reference</label>
            <label><input v-model="filters.notManual" type="checkbox" /> Not manual</label>
            <label><input v-model="filters.manual" type="checkbox" /> Manual</label>
          </fieldset>
          <div class="actions">
            <button type="button" class="btn btn-sm btn-primary" @click="openSelect">
              Assign to person
            </button>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="target === undefined"
              @click="target !== undefined && assignToPerson(target.id)"
            >
              Assign to selected row
            </button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="makePhoto">
              Person photo
            </button>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              @click="markExamples(true)"
            >
              Mark as reference
            </button>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              @click="markExamples(false)"
            >
              Clear reference
            </button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="clearSelection">
              Clear selection
            </button>
          </div>
        </div>

        <p class="selection">
          Faces selected: {{ selectedFaces.length }} on page {{ page + 1 }} of {{ pages }}. A click
          on a thumbnail adds the face to the selection, a second click removes it.
        </p>

        <FaceThumbnails
          :videofile-id="props.videofileId"
          :faces="visibleFaces"
          :selected-ids="selectedFaces"
          @select="toggleFace"
        />
      </div>
      <table class="table table-sm pages-table">
        <thead>
          <tr>
            <th>#</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="number in pages"
            :key="number"
            :class="{ selected: page === number - 1 }"
            @click="goToPage(number - 1)"
          >
            <td>{{ number }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

    <PersonSelectDialog
      v-if="selectOpen"
      :videofile-id="props.videofileId"
      :project-id="projectId"
      @chosen="assignToPerson"
      @closed="selectOpen = false"
    />
    <PersonEditDialog
      v-if="editing !== null"
      :videofile-id="props.videofileId"
      :person="editing"
      @saved="reload"
      @closed="editing = null"
    />
  </section>
</template>

<style scoped>
.persons-tab-body {
  display: grid;
  gap: 0.5rem;
  /* Три области, как во вкладке Persons старой формы: таблица персон
     шириной 175 px, область лиц и таблица страниц шириной 200 px. */
  grid-template-columns: 10.9375rem minmax(0, 1fr) 12.5rem;
  align-items: start;
}

.persons-table,
.pages-table {
  width: 100%;
  table-layout: fixed;
  margin-bottom: 0;
}

.persons-table td {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pages-table tbody tr {
  cursor: pointer;
}

.pages-scroll {
  max-height: 30rem;
  overflow: auto;
}

.pages-table tbody td {
  text-align: center;
}

.pages-table tr.selected td {
  background-color: var(--syp-tint-origin, rgba(0, 150, 201, 0.14));
}

.persons-list {
  border: 1px solid var(--syp-border);
  border-radius: 4px;
  padding: 0.5rem;
}

.persons-list.over {
  border-color: var(--syp-success);
}

.persons-list ul {
  list-style: none;
  margin: 0;
  max-height: 30rem;
  overflow: auto;
  padding: 0;
}

.persons-list li {
  align-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  gap: 0.5rem;
  padding: 0.25rem;
}

.persons-list li.selected {
  background: var(--syp-tint);
  border-color: var(--syp-link);
}

.person-photo {
  border-radius: 2px;
  height: 3rem;
  object-fit: cover;
  width: 4rem;
}

.hint {
  color: var(--syp-text-muted);
  font-size: 0.8rem;
}

.toolbar {
  align-items: flex-start;
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
}

.filters {
  border: 1px solid var(--syp-border);
  border-radius: 4px;
  display: grid;
  gap: 0.25rem;
}

.pager {
  align-items: center;
  display: flex;
  gap: 0.75rem;
  margin: 0.5rem 0;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.selection {
  color: var(--syp-text-muted);
}

.empty {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
}
</style>
