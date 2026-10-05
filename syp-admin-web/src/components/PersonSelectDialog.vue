<script setup lang="ts">
/**
 * Окно выбора персоны.
 *
 * Форма перенесена по старому проекту: узкая панель с поиском, таблицей персон,
 * добавлением и подтверждением. Поиск живой — он же в старом проекте шёл через
 * фильтр по мере ввода, и это единственный способ найти персону, когда их
 * десятки, а имена известны частично.
 *
 * Смысл окна — выбрать персону для действия, а не add новую. Заведение есть
 * здесь же, но отдельной командой, чтобы его нельзя было выбрать случайно.
 */
import { computed, onMounted, ref } from 'vue'
import {
  createPerson,
  deletePerson,
  facePreviewUrl,
  readPersons,
  type PersonView,
} from '../api/characters'

const props = defineProps<{ videofileId: number; projectId: number }>()

const emit = defineEmits<{
  /** Person выбрана и подтверждена. */
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

/** New person name: отдельное поле, чтобы заведение не выбралось случаем. */

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
    error.value = 'Person is not selected: there is nothing to choose'
    return
  }
  emit('chosen', chosen.value)
}

/** Заводит персону по имени. */
async function addByName(): Promise<void> {
  const name = query.value.trim()
  if (name === '') {
    error.value = 'Person name is required: without it a person cannot be created'
    return
  }
  try {
    const created = await createPerson(props.videofileId, name)
    query.value = ''
    persons.value = [...persons.value, created]
    chosen.value = created.id
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Удаляет выбранную персону. Лица при этом переходят в неподтверждённые. */
async function removeChosen(): Promise<void> {
  if (chosen.value === null) {
    return
  }
  try {
    await deletePerson(chosen.value)
    persons.value = persons.value.filter((p) => p.id !== chosen.value)
    chosen.value = null
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
  <div class="dialog" role="dialog" aria-label="PERSON">
    <div class="dialog-body">
      <!-- Форма person-select: поле поиска сверху, под ним таблица PERSON
           шириной 150 px и две квадратные кнопки 46 на 46, затем OK и
           Отмена во всю ширину. Заголовка у формы нет. -->
      <input
        v-model="query"
        class="form-control search"
        type="search"
        placeholder="Search by name"
        @keyup.enter="accept"
      />

      <div class="select-area">
        <table class="table table-sm persons-table">
          <thead>
            <tr>
              <th>PERSON</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="person in shown"
              :key="person.id"
              :class="{ selected: chosen === person.id }"
              @click="chosen = person.id"
              @dblclick="accept"
            >
              <td>
                <img
                  v-if="person.photoFrameNumber !== null"
                  :src="facePreviewUrl(props.videofileId, person.photoFrameNumber)"
                  :alt="`Person photo ${person.name}`"
                  class="person-photo"
                />
                <span>{{ person.name }}</span>
                <small v-if="person.isService" class="service">service</small>
              </td>
            </tr>
            <tr v-if="shown.length === 0">
              <td class="empty">Nobody found</td>
            </tr>
          </tbody>
        </table>

        <div class="glyph-buttons">
          <button type="button" class="glyph" title="Add person" @click="addByName">
            &#10133;
          </button>
          <button type="button" class="glyph" title="Delete person" @click="removeChosen">
            &#10006;
          </button>
        </div>
      </div>

      <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

      <div class="dialog-actions">
        <button type="button" class="btn btn-primary" @click="accept">OK</button>
        <button type="button" class="btn btn-outline-secondary" @click="emit('closed')">
          Отмена
        </button>
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
  background: var(--syp-surface);
  border-radius: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 90vh;
  overflow: auto;
  padding: 0.25rem;
  /* Форма объявлена шириной 210: таблица 150 плюс две кнопки по 46. */
  width: 13.125rem;
}

.select-area {
  display: flex;
  gap: 0.25rem;
  align-items: stretch;
}

.persons-table {
  width: 9.375rem;
  flex: 0 0 auto;
  table-layout: fixed;
  margin-bottom: 0;
}

.person-photo {
  height: 1.5rem;
  width: 1.5rem;
  object-fit: cover;
  vertical-align: middle;
}

.glyph-buttons {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  width: 2.875rem;
  flex: 0 0 auto;
}

.glyph {
  width: 2.875rem;
  height: 2.875rem;
  padding: 0;
  font-size: 1rem;
  line-height: 1;
}

.dialog-actions {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.dialog-actions .btn {
  width: 100%;
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
  background: var(--syp-tint);
  border-color: var(--syp-link);
}

.person-photo {
  border-radius: 2px;
  height: 2.5rem;
  object-fit: cover;
  width: 3.5rem;
}

.service {
  color: var(--syp-text-muted);
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
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
  margin: 0;
}
</style>
