<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { assignFacesToPerson, createPerson, readPersons, type PersonView } from '../api/characters'

/**
 * Панель персон видеофайла.
 *
 * В старом проекте это список персон сбоку, на строку которого перетаскивали
 * лицо. Здесь то же: строка — цель перетаскивания, а рядом форма для нового
 * имени.
 *
 * Перетаскивание работает раньше, чем появится модель распознавания: лица уже
 * найдены, а персону можно завести по имени вручную. Поэтому разметка не ждёт
 * кластеров.
 */
const props = defineProps<{
  videofileId: number
  projectId: number
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

onMounted(reload)
</script>

<template>
  <section class="persons">
    <div class="syp-card-title">Персоны</div>

    <p v-if="persons.length === 0" class="syp-unit">
      Персон нет. Заведите первую по имени — лица уже найдены, и перетаскиванием на строку можно
      разнести их по людям.
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
        <span class="person-name">{{ person.name }}</span>
        <small :title="person.isService ? 'служебная персона' : ''">({{ person.kind }})</small>
        <span class="syp-unit">перетащите лицо сюда</span>
      </li>
    </ul>

    <form class="person-fields" @submit.prevent="addPerson">
      <input v-model="newName" class="form-control" placeholder="Имя персоны" />
      <button type="submit" class="btn btn-primary" :disabled="busy || newName.trim() === ''">
        завести
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
  border-color: var(--syp-accent, #4c8dff);
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
