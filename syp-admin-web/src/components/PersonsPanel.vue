<script setup lang="ts">
import { onMounted, ref } from 'vue'
import {
  assignFacesToPerson,
  createPerson,
  facePreviewUrl,
  readPersons,
  setPersonPhoto,
  type PersonView,
} from '../api/characters'

/**
 * Панель персон видеофайла.
 *
 * В старом проекте это список персон сбоку, на строку которого перетаскивали
 * лицо. Здесь то же: строка — цель перетаскивания, а рядом форма для нового
 * имени.
 *
 * Перетаскивание работает раньше, чем появится модель распознавания: лица уже
 * найдены, а персону можно add по имени manually. Поэтому разметка не ждёт
 * кластеров.
 */
const props = defineProps<{
  videofileId: number
  projectId: number
  /** Самое крупное лицо каждой персоны на текущей странице, по номеру персоны. */
  biggestFaces: Record<number, { frameNumber: number }>
}>()

const persons = ref<PersonView[]>([])
const newName = ref('')
const notice = ref('')
const error = ref('')
const busy = ref(false)
const overPerson = ref<number | null>(null)

/** Перечитывает персон. */
async function reload(): Promise<void> {
  try {
    persons.value = (await readPersons(props.projectId)).persons
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Заводит персону по имени. */
async function addPerson(): Promise<void> {
  if (newName.value.trim() === '' || busy.value) {
    return
  }
  busy.value = true
  try {
    const created = await createPerson(props.videofileId, newName.value.trim())
    newName.value = ''
    notice.value = `персона «${created.name}» заведена`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

/** Принимает перетащенное лицо и назначает его персоне. */
async function drop(event: DragEvent, person: PersonView): Promise<void> {
  overPerson.value = null
  const faceId = event.dataTransfer?.getData('text/plain')
  if (!faceId || busy.value) {
    return
  }
  busy.value = true
  try {
    const answer = await assignFacesToPerson(props.videofileId, person.id, [Number(faceId)])
    notice.value = `лиц перенесено к «${person.name}»`
    error.value = ''
    void answer
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

/**
 * Ставит кадр персоны в её photo.
 *
 * В старом проекте это пункт меню на выделенных лицах. Здесь — кнопка у строки
 * персоны: она берёт самое крупное лицо этой персоны на текущей странице, то есть
 * кадр, где человек виден лучше всего. Перебор кадров по всем лицам здесь не
 * делается — это отдельная работа, и она ничего не даёт оператору сверх выбора
 * одного кадра из уже показанных.
 *
 * @param person персона
 * @param face кадр с самым крупным её лицом
 */
async function makePhoto(
  person: PersonView,
  face: { frameNumber: number } | undefined,
): Promise<void> {
  if (!face || busy.value) {
    return
  }
  busy.value = true
  try {
    await setPersonPhoto(props.videofileId, person.id, face.frameNumber)
    notice.value = `photo персоны «${person.name}» взято с кадра ${face.frameNumber}`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(reload)
</script>

<template>
  <section class="persons">
    <div class="syp-card-title">Persons</div>

    <p v-if="persons.length === 0" class="syp-unit">
      No persons. Create the first one by name — the faces are already found, and dragging a face
      onto a person assigns it.
    </p>

    <ul v-else class="person-list">
      <li
        v-for="person in persons"
        :key="person.id"
        class="person"
        :class="{ over: overPerson === person.id }"
        :data-person-id="person.id"
        @dragover.prevent="overPerson = person.id"
        @dragleave="overPerson = null"
        @drop.prevent="drop($event, person)"
      >
        <img
          v-if="person.photoFrameNumber !== null"
          class="person-photo"
          :src="facePreviewUrl(person.photoVideofileId ?? videofileId, person.photoFrameNumber)"
          :alt="`Person photo ${person.name}`"
        />
        <span class="person-name">{{ person.name }}</span>
        <small :title="person.isService ? 'service person' : ''">({{ person.kind }})</small>
        <span class="syp-unit">drag a face here</span>
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary photo-button"
          :disabled="busy || !props.biggestFaces[person.id]"
          @click="makePhoto(person, props.biggestFaces[person.id])"
        >
          photo
        </button>
      </li>
    </ul>

    <form class="person-fields" @submit.prevent="addPerson">
      <input v-model="newName" class="form-control" placeholder="Person name" />
      <button type="submit" class="btn btn-primary" :disabled="busy || newName.trim() === ''">
        add
      </button>
    </form>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.person-list {
  display: grid;
  gap: 0.25rem;
  list-style: none;
  margin: 0;
  padding: 0;
}

.person {
  align-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  display: flex;
  gap: 0.5rem;
  padding: 0.25rem 0.4rem;
}

.person.over {
  border-color: var(--syp-accent, var(--syp-primary));
}

.person-name {
  font-weight: 600;
}

.person-fields {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr auto;
  margin-top: 0.5rem;
}
</style>
