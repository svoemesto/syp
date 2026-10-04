<script setup lang="ts">
/**
 * Окно выбора персоны.
 *
 * Форма перенесена по старому проекту: узкая панель с поиском, таблицей персон,
 * добавлением и подтверждением. Поиск живой — он же в старом проекте шёл через
 * фильтр по мере ввода, и это единственный способ найти персону, когда их
 * десятки, а имена известны частично.
 *
 * Смысл окна — выбрать персону для действия, а не завести новую. Заведение есть
 * здесь же, но отдельной командой, чтобы его нельзя было выбрать случайно.
 */
import { computed, onMounted, ref } from 'vue'
import { createPerson, readPersons, type PersonView } from '../api/characters'

const props = defineProps<{ videofileId: number; projectId: number }>()

const emit = defineEmits<{
  /** Персона выбрана и подтверждена. */
  chosen: [personId: number]
  /** Окно закрыто без выбора. */
  closed: []
}>()

/** Все персоны проекта. */
const persons = ref<PersonView[]>([])

/** Строка поиска: по ней фильтруется список. */
const query = ref('')

/** Идентификатор выбранной персоны. */
const chosen = ref<number | null>(null)

/** Имя новой персоны: отдельное поле, чтобы заведение не выбралось случаем. */
const newName = ref('')

const error = ref('')

/** Персоны, подходящие под строку поиска. */
const shown = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (needle === '') {
    return persons.value
  }
  return persons.value.filter((person) => person.name.toLowerCase().includes(needle))
})

/** Подтверждает выбор и закрывает окно. */
function accept(): void {
  if (chosen.value === null) {
    error.value = 'Персона не выбрана: выбирать нечего'
    return
  }
  emit('chosen', chosen.value)
}

/** Заводит персону по имени. */
async function addPerson(): Promise<void> {
  if (newName.value.trim() === '') {
    error.value = 'Имя персоны обязательно: без него персону не завести'
    return
  }
  try {
    const created = await createPerson(props.videofileId, newName.value.trim())
    newName.value = ''
    persons.value = [...persons.value, created]
    chosen.value = created.id
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

onMounted(async () => {
  try {
    persons.value = (await readPersons(props.projectId)).persons
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <div class="dialog" role="dialog" aria-label="Выбор персоны">
    <div class="dialog-body">
      <h2 class="syp-card-title">Выбор персоны</h2>

      <input
        v-model="query"
        class="form-control"
        type="search"
        placeholder="Поиск по имени"
        @keyup.enter="persons.length > 0 && (chosen = shown[0]?.id ?? null)"
      />

      <ul class="person-list">
        <li
          v-for="person in shown"
          :key="person.id"
          :class="{ selected: chosen === person.id }"
          @click="chosen = person.id"
          @dblclick="accept"
        >
          <img
            v-if="person.photoFrameNumber !== null"
            :src="`/api/videofiles/${props.videofileId}/faces/${person.photoFrameNumber}/preview`"
            :alt="`Фото персоны ${person.name}`"
            class="person-photo"
          />
          <span>{{ person.name }}</span>
          <small v-if="person.isService" class="service">служебная</small>
        </li>
        <li v-if="shown.length === 0" class="empty">Никого не найдено</li>
      </ul>

      <div class="new-person">
        <input v-model="newName" class="form-control" placeholder="Имя новой персоны" />
        <button type="button" class="btn btn-outline-secondary" @click="addPerson">Завести</button>
      </div>

      <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

      <div class="dialog-actions">
        <button type="button" class="btn btn-primary" @click="accept">Подтвердить</button>
        <button type="button" class="btn btn-outline-secondary" @click="emit('closed')">Отмена</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog {
  align-items: center;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  inset: 0;
  justify-content: center;
  position: fixed;
  z-index: 40;
}

.dialog-body {
  background: #fff;
  border-radius: 6px;
  display: grid;
  gap: 0.5rem;
  max-height: 80vh;
  overflow: auto;
  padding: 1rem;
  width: 22rem;
}

.person-list {
  list-style: none;
  margin: 0;
  max-height: 22rem;
  overflow: auto;
  padding: 0;
}

.person-list li {
  align-items: center;
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  gap: 0.5rem;
  padding: 0.25rem;
}

.person-list li.selected {
  background: #e7f0fd;
  border-color: #1a4f8a;
}

.person-photo {
  border-radius: 2px;
  height: 2.5rem;
  object-fit: cover;
  width: 3.5rem;
}

.service {
  color: #777;
}

.new-person {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr auto;
}

.dialog-actions {
  display: flex;
  gap: 0.5rem;
}

.empty {
  color: #777;
}

.error {
  color: #a61b1b;
  margin: 0;
}
</style>
