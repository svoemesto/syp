<script setup lang="ts">
/**
 * Главный редактор: слева планы, справа четыре вкладки.
 *
 * Форма перенесена по старому проекту: одна левая часть, общая для всех
 * вкладок, и четыре вкладки справа — Frames, Persons, Scenes, Events. Левая
 * часть показывает планы, их свойства и персон выбранного плана, а также
 * фильтры лиц, которые управляют вкладкой Persons.
 *
 * Фильтры лиц стоят в левой части и в старом проекте, хотя управляют правой:
 * это сбивает с толку при переносе, поэтому здесь они помечены как относящиеся
 * к вкладке персон.
 */
import { computed, onMounted, ref } from 'vue'
import { readStructure, type ShotView, type StructureView } from '../api/structure'
import { readFaces, readPersons, type PersonView } from '../api/characters'
import EventsTab from '../components/EventsTab.vue'

const props = defineProps<{ videofileId: number }>()

/** Активная вкладка правой части. Порядок — как в старом проекте. */
const TABS = ['Frames', 'Persons', 'Scenes', 'Events'] as const
const tab = ref<(typeof TABS)[number]>('Persons')

/** Разобранный видеофайл: планы и сцены. */
const structure = ref<StructureView | null>(null)

/** Персоны проекта: цель для перетаскивания на вкладке персон. */
const persons = ref<PersonView[]>([])

/** Идентификаторы выбранных планов: выбор множественный, как в старом проекте. */
const selectedShots = ref<number[]>([])

/** Ошибка чтения: молча пустой экран хуже названной ошибки. */
const error = ref('')

/** Ключ и значение свойства плана. */
const propertyKey = ref('')
const propertyValue = ref('')

/** Заглушка примера, пока свойства не подключены к вкладке. */
const shotProperties = ref<Array<{ key: string; value: string }>>([])

/**
 * Переключает выделение плана.
 *
 * @param id идентификатор плана
 */
function toggleShot(id: number): void {
  selectedShots.value = selectedShots.value.includes(id)
    ? selectedShots.value.filter((item) => item !== id)
    : [...selectedShots.value, id]
}

/** Планы выбранного: первый из выбранных. */
const currentShot = computed<ShotView | undefined>(() =>
  structure.value?.shots.find((shot) => shot.id === selectedShots.value[0]),
)

/** Границы выбранных планов — по первому и последнему. */
const bounds = computed(() => {
  const chosen = (structure.value?.shots ?? []).filter((shot) => selectedShots.value.includes(shot.id))
  if (chosen.length === 0) {
    return { first: 0, last: 0 }
  }
  return { first: Math.min(...chosen.map((shot) => shot.firstFrame)), last: Math.max(...chosen.map((shot) => shot.lastFrame)) }
})

/** Добавляет свойство к выбранному плану. */
function addShotProperty(): void {
  if (currentShot.value === undefined) {
    error.value = 'Свойство добавляется к выбранному плану: выберите план'
    return
  }
  if (propertyKey.value.trim() === '') {
    error.value = 'Ключ свойства обязателен: без него значение не к чему привязать'
    return
  }
  shotProperties.value = [
    ...shotProperties.value.filter((item) => item.key !== propertyKey.value.trim()),
    { key: propertyKey.value.trim(), value: propertyValue.value },
  ]
  propertyKey.value = ''
  propertyValue.value = ''
  error.value = ''
}

onMounted(async () => {
  try {
    structure.value = await readStructure(Number(props.videofileId))
    // Проект приходит из ответа по лицам: в структуре видеофайла проекта нет,
    // а персоны принадлежат проекту, а не видеофайлу.
    const faces = await readFaces(Number(props.videofileId), 0, 1)
    persons.value = (await readPersons(faces.projectId)).persons
    if (structure.value.shots.length > 0) {
      selectedShots.value = [structure.value.shots[0].id]
    }
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <section class="editor">
    <h1 class="syp-page-title">Редактор планов</h1>

    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <div class="editor-body">
      <aside class="left">
        <div class="syp-card-title">Планы</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>FROM</th>
              <th>TO</th>
              <th>Кадров</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="shot in structure?.shots ?? []"
              :key="shot.id"
              :class="{ selected: selectedShots.includes(shot.id) }"
              @click="toggleShot(shot.id)"
            >
              <td>{{ shot.firstFrame }}</td>
              <td>{{ shot.lastFrame }}</td>
              <td>{{ shot.size }}</td>
            </tr>
            <tr v-if="(structure?.shots ?? []).length === 0">
              <td colspan="3" class="empty">Планов нет</td>
            </tr>
          </tbody>
        </table>
        <p class="bounds">
          Выделено планов: {{ selectedShots.length }}, кадры с {{ bounds.first }} по {{ bounds.last }}
        </p>

        <div class="syp-card-title">Персоны выбранного плана</div>
        <ul class="persons">
          <li v-for="person in persons" :key="person.id">{{ person.name }}</li>
          <li v-if="persons.length === 0" class="empty">Персон нет</li>
        </ul>

        <fieldset class="filters">
          <legend>Фильтры лиц</legend>
          <p class="hint">Управляют вкладкой Persons</p>
          <label><input type="checkbox" checked /> Не эталон</label>
          <label><input type="checkbox" checked /> Эталон</label>
          <label><input type="checkbox" checked /> Не ручной</label>
          <label><input type="checkbox" checked /> Ручной</label>
        </fieldset>

        <div class="syp-card-title">Свойства плана</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>Key</th>
              <th>Value</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="property in shotProperties" :key="property.key">
              <td>{{ property.key }}</td>
              <td>{{ property.value }}</td>
            </tr>
            <tr v-if="shotProperties.length === 0">
              <td colspan="2" class="empty">Свойств нет</td>
            </tr>
          </tbody>
        </table>
        <div class="property-fields">
          <input v-model="propertyKey" class="form-control" placeholder="Key" />
          <input v-model="propertyValue" class="form-control" placeholder="Value" />
          <button type="button" class="btn btn-sm btn-primary" @click="addShotProperty">Добавить</button>
        </div>
      </aside>

      <div class="right">
        <nav class="tabs">
          <button
            v-for="name in TABS"
            :key="name"
            type="button"
            class="tab"
            :class="{ active: tab === name }"
            @click="tab = name"
          >
            {{ name }}
          </button>
        </nav>

        <EventsTab
          v-if="tab === 'Events'"
          :shots-total="structure?.shotsTotal ?? 0"
          :first-frame="bounds.first"
          :last-frame="bounds.last"
        />

        <section v-else-if="tab === 'Persons'" class="placeholder">
          <div class="syp-card-title">Лица и персоны</div>
          <p>Раздел переносится: матрица миниатюр лиц, страницы лиц и действия над лицом.</p>
        </section>

        <section v-else-if="tab === 'Scenes'" class="placeholder">
          <div class="syp-card-title">Сцены</div>
          <p>Раздел переносится: сцены файла, планы выбранных сцен, персоны и свойства сцены.</p>
        </section>

        <section v-else class="placeholder">
          <div class="syp-card-title">Кадры</div>
          <p>Раздел переносится: матрица миниатюр кадров и страницы кадров.</p>
        </section>
      </div>
    </div>
  </section>
</template>

<style scoped>
.editor-body {
  display: grid;
  gap: 1rem;
  grid-template-columns: 22rem 1fr;
}

.left {
  border: 1px solid #d8dde5;
  border-radius: 4px;
  display: grid;
  gap: 0.75rem;
  padding: 0.75rem;
}

.selected {
  background: #e7f0fd;
}

.tabs {
  display: flex;
  gap: 0.25rem;
  margin-bottom: 0.75rem;
}

.tab {
  background: #eef1f5;
  border: 1px solid #d8dde5;
  border-radius: 4px 4px 0 0;
  cursor: pointer;
  padding: 0.4rem 1rem;
}

.tab.active {
  background: #fff;
  font-weight: 600;
}

.persons {
  list-style: none;
  margin: 0;
  max-height: 12rem;
  overflow: auto;
  padding: 0;
}

.filters {
  border: 1px solid #d8dde5;
  border-radius: 4px;
  display: grid;
  gap: 0.25rem;
}

.hint {
  color: #777;
  font-size: 0.8rem;
  margin: 0 0 0.25rem;
}

.property-fields {
  display: grid;
  gap: 0.25rem;
  grid-template-columns: 1fr 1fr auto;
}

.placeholder {
  border: 1px dashed #c3cad6;
  border-radius: 4px;
  padding: 1rem;
}

.bounds,
.empty {
  color: #777;
}

.error {
  color: #a61b1b;
}
</style>
