<script setup lang="ts">
// Диалог одного условия фильтра.
//
// Форма повторяет `filter-condition-create-view` старого проекта: тип
// объекта, кнопка выбора объекта, включён или исключён, где искать — и живая
// формулировка под всеми переключателями.
//
// Отличия от старого проекта внесены намеренно, каждое — по дефекту,
// записанному в карте интерфейса:
//
// * кнопка подтверждения называет действие, а не повторяет заголовок окна;
// * формулировка показывает и ключ выбранного объекта, а не только имя;
// * ширина окна не обрезает формулировку;
// * пока объект не выбран, кнопка подтверждения заблокирована — так было и в
//   старом проекте, и это единственное место, где выбор объекта был виден.

import { computed, onMounted, ref } from 'vue'
import { readPersons, type PersonView } from '../api/characters'
import PersonSelectDialog from './PersonSelectDialog.vue'
import {
  CONFIRM_BUTTON_TEXT,
  SELECT_BUTTON_TEXT,
  conditionName,
  type ConditionIncluded,
  type ConditionObjectClass,
  type ConditionSubject,
  type FilterCondition,
} from '../api/filter-stubs'

const props = defineProps<{ projectId: number }>()

/**
 * Черновик условия — двусторонняя связь, а не чужой объект.
 *
 * Раньше окно меняло переданный объект на месте, и родитель видел правку
 * только потому, что держал его в `ref`. Правило `vue/no-mutating-props`
 * такую правку запрещает, и правильно: правка чужого объекта из окна
 * неожиданна для вызывающего. Родитель передаёт черновик через `v-model`,
 * окно отдаёт ему же готовый объект при подтверждении.
 */
const draft = defineModel<FilterCondition>('draft', { required: true })

const emit = defineEmits<{
  confirmed: [condition: FilterCondition]
  closed: []
}>()

/** Открыт ли выбор персоны. */
const selectOpen = ref(false)

/** Персоны проекта — на случай, если объект выбирается без окна выбора. */
const persons = ref<PersonView[]>([])

/** Ошибка чтения персон. */
const error = ref('')

/** Типы объектов в порядке старой формы. */
const OBJECT_CLASSES: { value: ConditionObjectClass; title: string }[] = [
  { value: 'PERSON', title: 'Person' },
  { value: 'PERSON_PROPERTY', title: 'Person property' },
  { value: 'SHOT_PROPERTY', title: 'Shot property' },
  { value: 'SCENE_PROPERTY', title: 'Scene property' },
  { value: 'EVENT_PROPERTY', title: 'Event property' },
]

/** Где искать. */
const SUBJECTS: { value: ConditionSubject; title: string }[] = [
  { value: 'SHOT', title: 'Shot' },
  { value: 'SCENE', title: 'Scene' },
  { value: 'EVENT', title: 'Event' },
]

/** Подпись кнопки выбора — меняется по типу объекта, как в старой форме. */
const selectText = computed(() => SELECT_BUTTON_TEXT[draft.value.objectClass])

/** Живая формулировка условия. */
const name = computed(() => conditionName(draft.value))

/** Выбран ли объект: без него условие бессмысленно. */
const canConfirm = computed(() => draft.value.objectKey !== null && draft.value.objectName !== '')

/**
 * Смена типа объекта сбрасывает выбор.
 *
 * Ключ выбранного объекта относится к прежнему типу, и оставить его после
 * смены типа значило бы получить условие «свойство плана = Иван».
 *
 * @param objectClass новый тип объекта
 */
function changeObjectClass(objectClass: ConditionObjectClass): void {
  if (draft.value.objectClass === objectClass) {
    return
  }
  draft.value.objectClass = objectClass
  draft.value.objectKey = null
  draft.value.objectName = ''
}

/**
 * Смена типа объекта открывает список персон, когда выбрана персона.
 *
 * @param objectClass новый тип объекта
 */
async function chooseObject(objectClass: ConditionObjectClass): Promise<void> {
  changeObjectClass(objectClass)
  if (objectClass !== 'PERSON') {
    // Свойства выбираются отдельным списком, а его в проекте нет: вместо
    // окна с пустым списком сказать прямо, чем оператор сейчас не может.
    error.value = `Списка свойств для «${SELECT_BUTTON_TEXT[objectClass]}» в проекте нет: выбирать нечего`
    return
  }
  error.value = ''
  selectOpen.value = true
}

/**
 * Person выбрана.
 *
 * @param personId идентификатор персоны
 */
function onChosen(personId: number): void {
  const person = persons.value.find((item) => item.id === personId)
  if (person === undefined) {
    error.value = 'Person не найдена: условие останется без объекта'
    return
  }
  draft.value.objectKey = String(person.id)
  draft.value.objectName = person.name
  selectOpen.value = false
  error.value = ''
}

/**
 * Задана включённость.
 *
 * @param isIncluded включено или исключено
 */
function changeIncluded(isIncluded: ConditionIncluded): void {
  draft.value.isIncluded = isIncluded
}

/**
 * Задано, где искать.
 *
 * @param subject где искать
 */
function changeSubject(subject: ConditionSubject): void {
  draft.value.subject = subject
}

/** Подтверждает условие. */
function confirm(): void {
  if (!canConfirm.value) {
    error.value = 'Объект не выбран: подтверждать нечего'
    return
  }
  emit('confirmed', { ...draft.value })
}

onMounted(async () => {
  try {
    persons.value = (await readPersons(props.projectId)).persons
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <div class="condition-dialog" role="dialog" aria-modal="true" aria-label="Filter condition">
    <h2 class="syp-card-title">New filter condition</h2>

    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <fieldset>
      <legend class="legend">What we look for</legend>
      <div v-for="item in OBJECT_CLASSES" :key="item.value" class="form-check">
        <input
          :id="`oc-${item.value}`"
          class="form-check-input"
          type="radio"
          name="object-class"
          :checked="draft.objectClass === item.value"
          @change="chooseObject(item.value)"
        />
        <label class="form-check-label" :for="`oc-${item.value}`">{{ item.title }}</label>
      </div>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        @click="chooseObject(draft.objectClass)"
      >
        {{ selectText }}
      </button>
    </fieldset>

    <hr />

    <fieldset>
      <legend class="legend">Included or excluded</legend>
      <div class="form-check">
        <input
          id="in-yes"
          class="form-check-input"
          type="radio"
          name="included"
          :checked="draft.isIncluded"
          @change="changeIncluded(true)"
        />
        <label class="form-check-label" for="in-yes">is included</label>
      </div>
      <div class="form-check">
        <input
          id="in-no"
          class="form-check-input"
          type="radio"
          name="included"
          :checked="!draft.isIncluded"
          @change="changeIncluded(false)"
        />
        <label class="form-check-label" for="in-no">is NOT included</label>
      </div>
      <span class="in-word">in</span>
      <div v-for="item in SUBJECTS" :key="item.value" class="form-check">
        <input
          :id="`sub-${item.value}`"
          class="form-check-input"
          type="radio"
          name="subject"
          :checked="draft.subject === item.value"
          @change="changeSubject(item.value)"
        />
        <label class="form-check-label" :for="`sub-${item.value}`">{{ item.title }}</label>
      </div>
    </fieldset>

    <!-- Формулировка показана вся, вместе с ключом объекта: в старом проекте
         она обрезалась шириной окна в 200 пикселей, а ключа не показывала
         вовсе, и ошибиться в выборе персоны было нечем. -->
    <p class="statement" role="status">{{ name }}</p>

    <div class="dialog-actions">
      <button type="button" class="btn btn-sm btn-primary" :disabled="!canConfirm" @click="confirm">
        {{ CONFIRM_BUTTON_TEXT }}
      </button>
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="emit('closed')">
        Cancel
      </button>
    </div>

    <PersonSelectDialog
      v-if="selectOpen"
      :videofile-id="0"
      :project-id="projectId"
      @chosen="onChosen"
      @closed="selectOpen = false"
    />
  </div>
</template>

<style scoped>
.condition-dialog {
  position: fixed;
  inset: 50% auto auto 50%;
  transform: translate(-50%, -50%);
  z-index: 30;
  min-width: 26rem;
  max-width: 40rem;
  background: var(--syp-surface);
  border: 1px solid var(--syp-border);
  border-radius: 0.4rem;
  box-shadow: 0 1rem 3rem rgb(0 0 0 / 45%);
  padding: 1rem;
}
.legend {
  font-size: 0.8125rem;
  font-weight: 600;
}
.in-word {
  display: inline-block;
  margin: 0.25rem 0 0 1.5rem;
  font-size: 0.8125rem;
  color: var(--syp-text-muted);
}
.statement {
  margin: 0.75rem 0;
  padding: 0.5rem;
  min-height: 3rem;
  color: var(--syp-danger);
  font-weight: 600;
  background: var(--syp-surface);
  border: 1px solid var(--syp-danger);
  border-radius: 0.3rem;
  white-space: normal;
  overflow-wrap: anywhere;
}
.dialog-actions {
  display: flex;
  gap: 0.5rem;
}
</style>
