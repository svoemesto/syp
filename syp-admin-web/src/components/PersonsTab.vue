<script setup lang="ts">
/**
 * Вкладка персон: лица файла и персоны, которым они принадлежат.
 *
 * Форма перенесена по старому проекту. Слева — персоны файла, они же цель для
 * перетаскивания; справа — матрица миниатюр лиц и страницы лиц. Сверху фильтры
 * типов лиц: не эталон, эталон, не ручной, ручной; в старом проекте они стоят в
 * левой части окна, хотя управляют именно этой вкладкой.
 *
 * Порядок работы оператора, ради которого вкладка и делается: выбрать лицо или
 * несколько, назначить персону, пометить эталон, поставить кадр в фото персоны.
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

/** Персона, которую правят в открытом окне. */
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
const pages = computed(() => Math.max(1, Math.ceil((faces.value?.facesTotal ?? 0) / (faces.value?.limit || 1))))

/** Персона, которой назначаются выбранные лица. */
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
    notice.value = 'Персона не найдена: назначать некого'
    return
  }
  if (selectedFaces.value.length === 0) {
    notice.value = 'Не выбрано ни одного лица: назначать нечего'
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
    notice.value = 'Не выбрано ни одного лица: выбирать персону не для чего'
    return
  }
  selectOpen.value = true
}

/** Помечает выбранные лица эталонами и снимает пометку. */
async function markExamples(marked: boolean): Promise<void> {
  if (selectedFaces.value.length === 0) {
    notice.value = 'Не выбрано ни одного лица: помечать нечего'
    return
  }
  try {
    const answer = await markFaceExamples(props.videofileId, selectedFaces.value, marked)
    notice.value = `помечено эталоном: ${answer.isExample ? 'да' : 'нет'}, изменено лиц: ${answer.changed}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Ставит кадр выбранного лица в фото выбранной персоны. */
async function makePhoto(): Promise<void> {
  const face = (faces.value?.faces ?? []).find((item) => selectedFaces.value.includes(item.id))
  if (face === undefined) {
    notice.value = 'Фото берётся с кадра выбранного лица: выберите лицо'
    return
  }
  if (target.value === undefined) {
    notice.value = 'Выберите персону, которой принадлежит фото'
    return
  }
  try {
    await setPersonPhoto(props.videofileId, target.value.id, face.frameNumber)
    notice.value = `фото персоны «${target.value.name}» взято с кадра ${face.frameNumber}`
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

/** Переходит на страницу лиц. */
async function turnPage(delta: number): Promise<void> {
  const next = page.value + delta
  if (next < 0 || next >= pages.value) {
    notice.value = 'Страница за пределами: переходить некуда'
    return
  }
  page.value = next
  clearSelection()
  await reload()
}

/** Перечитывает лица и персон. */
async function reload(): Promise<void> {
  try {
    faces.value = await readFaces(props.videofileId, page.value * (faces.value?.limit ?? 200), faces.value?.limit ?? 200)
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
        <div class="syp-card-title">Персоны файла</div>
        <p class="hint">Перетащите лицо на строку персоны, чтобы назначить</p>
        <ul>
          <li
            v-for="person in persons"
            :key="person.id"
            :class="{ selected: targetPerson === person.id }"
            :draggable="true"
            @click="targetPerson = person.id"
            @dragstart="$event.dataTransfer?.setData('text/plain', String(person.id))"
            @dblclick="editing = person"
          >
            <img
              v-if="person.photoFrameNumber !== null"
              :src="facePreviewUrl(props.videofileId, person.photoFrameNumber)"
              :alt="`Фото персоны ${person.name}`"
              class="person-photo"
            />
            <span>{{ person.name }}</span>
          </li>
          <li v-if="persons.length === 0" class="empty">Персон нет</li>
        </ul>
      </aside>

      <div class="faces-area">
        <div class="toolbar">
          <fieldset class="filters">
            <legend>Типы лиц</legend>
            <label><input v-model="filters.notExample" type="checkbox" /> Не эталон</label>
            <label><input v-model="filters.example" type="checkbox" /> Эталон</label>
            <label><input v-model="filters.notManual" type="checkbox" /> Не ручной</label>
            <label><input v-model="filters.manual" type="checkbox" /> Ручной</label>
          </fieldset>
          <div class="actions">
            <button type="button" class="btn btn-sm btn-primary" @click="openSelect">Назначить персоне</button>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="target === undefined"
              @click="target !== undefined && assignToPerson(target.id)"
            >
              Назначить выбранной строке
            </button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="makePhoto">Фото персоны</button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="markExamples(true)">Пометить эталоном</button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="markExamples(false)">Снять эталон</button>
            <button type="button" class="btn btn-sm btn-outline-secondary" @click="clearSelection">Снять выделение</button>
          </div>
        </div>

        <p class="selection">
          Выбрано лиц: {{ selectedFaces.length }} на странице {{ page + 1 }} из {{ pages }}.
          Клик по миниатюре добавляет лицо к выделению, повторный — убирает.
        </p>

        <div class="pager">
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="turnPage(-1)">
            К предыдущей странице
          </button>
          <span>Страница {{ page + 1 }} из {{ pages }}</span>
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="turnPage(1)">
            К следующей странице
          </button>
        </div>

        <FaceThumbnails
          :videofile-id="props.videofileId"
          :faces="visibleFaces"
          :frame-width="faces?.frameWidth ?? 1920"
          :frame-height="faces?.frameHeight ?? 1080"
          :selected-ids="selectedFaces"
          @select="toggleFace"
        />
      </div>
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
  gap: 1rem;
  grid-template-columns: 18rem 1fr;
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
