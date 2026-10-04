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
import ShotFrameView from '../components/ShotFrameView.vue'
import ShotThumb from '../components/ShotThumb.vue'
import PersonsTab from '../components/PersonsTab.vue'
import ScenesTab from '../components/ScenesTab.vue'
import FramesTab from '../components/FramesTab.vue'

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

/**
 * Охват персон: `file` — персоны файла (по умолчанию), `all` — все.
 */
const personScope = ref<'file' | 'all'>('file')

/** Охват лиц: `file` — лица файла (по умолчанию), `all` — все. */
const faceScope = ref<'file' | 'all'>('file')

/** Отмеченные типы лиц; по умолчанию отмечены все четыре, как в старой форме. */
const faceTypes = ref<string[]>(['notExample', 'example', 'notManual', 'manual'])

/** Ширина миниатюры кадра в колонках FROM и TO, как в старой форме. */
const SHOT_THUMB_WIDTH = 96

/** Значки типов плана вместо строкового значения в колонке данных. */
const TYPE_MARKS: Record<string, string> = {
  XLS: 'XL',
  XS: 'XS',
  S: 'S',
  M: 'M',
  L: 'L',
  NONE: '—',
}

/** Ошибка чтения: молча пустой экран хуже названной ошибки. */
const error = ref('')

/** Ответ на последнее действие оператора: показывается под формой. */
const notice = ref('')

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

/**
 * Все планы видеофайла, собранные из сцен.
 *
 * Отдельного списка планов в ответе нет: планы лежат внутри сцен, и верхнего
 * уровня у ответа не существует. Здесь они собираются в один список, потому что
 * левая часть показывает планы целиком, а не по сценам.
 */
const shots = computed<ShotView[]>(() =>
  (structure.value?.scenes ?? []).flatMap((scene) => scene.shots),
)

/** Планы выбранного: первый из выбранных. */
const currentShot = computed<ShotView | undefined>(() =>
  shots.value.find((shot) => shot.id === selectedShots.value[0]),
)

/** Границы выбранных планов — по первому и последнему. */
const bounds = computed(() => {
  const chosen = shots.value.filter((shot) => selectedShots.value.includes(shot.id))
  if (chosen.length === 0) {
    return { first: 0, last: 0 }
  }
  return {
    first: Math.min(...chosen.map((shot) => shot.firstFrame)),
    last: Math.max(...chosen.map((shot) => shot.lastFrame)),
  }
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

/**
 * Значок типа плана в колонке TYPE.
 *
 * В старой форме там пиктограмма; у нас тип приходит строкой (`XLS`, `NONE`),
 * и показывать её как есть — значит вернуть ту самую заглушку в колонке
 * данных. Поэтому показывается значок, а строковое значение уходит в подсказку.
 *
 * @param shot план
 * @returns значок типа
 */
function typeMark(shot: { size: string }): string {
  return TYPE_MARKS[shot.size] ?? '•'
}

/**
 * Подсказка с настоящим значением типа.
 *
 * @param shot план
 * @returns текст подсказки
 */
function typeTitle(shot: { size: string; sizeOrigin: string }): string {
  return `тип: ${shot.size}, происхождение: ${shot.sizeOrigin}`
}

onMounted(async () => {
  try {
    structure.value = await readStructure(Number(props.videofileId))
    // Проект приходит из ответа по лицам: в структуре видеофайла проекта нет,
    // а персоны принадлежат проекту, а не видеофайлу.
    const faces = await readFaces(Number(props.videofileId), 0, 1)
    persons.value = (await readPersons(faces.projectId)).persons
    if (shots.value.length > 0) {
      selectedShots.value = [shots.value[0].id]
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
    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

    <div class="editor-body">
      <!-- Левая часть по форме shots-edit-view: планы и их свойства слева,
           персона выбранного плана и крупный кадр — в соседней колонке.
           В старой форме ширина левой части ограничена 730 px, а правая
           требует не меньше 920 px; здесь те же пропорции, но окно
           пользователя может быть уже, поэтому колонки сжимаются. -->
      <aside class="left">
        <div class="left-plans">
          <div class="syp-card-title">Планы</div>
          <div class="shots-scroll">
            <table class="table table-sm shots">
              <thead>
                <tr>
                  <th>FROM</th>
                  <th>TO</th>
                  <th>TYPE</th>
                  <th class="type-button" title="Выбрать тип плана"></th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="shot in shots"
                  :key="shot.id"
                  :class="{ selected: selectedShots.includes(shot.id) }"
                  @click="toggleShot(shot.id)"
                >
                  <td>
                    <ShotThumb
                      :videofile-id="Number(props.videofileId)"
                      :frame-number="shot.firstFrame"
                      :width="SHOT_THUMB_WIDTH"
                    />
                  </td>
                  <td>
                    <ShotThumb
                      :videofile-id="Number(props.videofileId)"
                      :frame-number="shot.lastFrame"
                      :width="SHOT_THUMB_WIDTH"
                    />
                  </td>
                  <td class="type-cell">
                    <span class="type-mark" :title="typeTitle(shot)">{{ typeMark(shot) }}</span>
                  </td>
                  <td class="type-button">
                    <button
                      type="button"
                      class="btn btn-sm btn-outline-secondary"
                      title="Выбрать тип плана"
                      @click.stop="
                        notice = `Тип плана меняется оператором: в проекте нет эндпоинта, меняющего тип`
                      "
                    >
                      ▾
                    </button>
                  </td>
                </tr>
                <tr v-if="shots.length === 0">
                  <td colspan="4" class="empty">Планов нет</td>
                </tr>
              </tbody>
            </table>
          </div>
          <progress
            class="left-progress"
            :value="selectedShots.length"
            :max="Math.max(shots.length, 1)"
          />
          <p class="bounds">
            Выделено планов: {{ selectedShots.length }}, кадры с {{ bounds.first }} по
            {{ bounds.last }}
          </p>

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
            <input v-model="propertyKey" class="form-control form-control-sm" placeholder="Key" />
            <input
              v-model="propertyValue"
              class="form-control form-control-sm"
              placeholder="Value"
            />
            <button type="button" class="btn btn-sm btn-primary" @click="addShotProperty">
              Добавить
            </button>
          </div>
        </div>

        <div class="left-shot">
          <div class="shot-head">
            <div class="syp-card-title">Персоны выбранного плана</div>
            <div class="shot-filters">
              <div class="filter-group">
                <span class="filter-title">Persons:</span>
                <label><input v-model="personScope" type="radio" value="all" /> All</label>
                <label><input v-model="personScope" type="radio" value="file" /> File</label>
              </div>
              <div class="filter-group">
                <span class="filter-title">Faces:</span>
                <label><input v-model="faceScope" type="radio" value="all" /> All</label>
                <label><input v-model="faceScope" type="radio" value="file" /> File</label>
              </div>
              <div class="filter-group">
                <span class="filter-title">Типы лиц:</span>
                <label
                  ><input v-model="faceTypes" type="checkbox" value="notExample" /> Not
                  example</label
                >
                <label><input v-model="faceTypes" type="checkbox" value="example" /> Example</label>
                <label
                  ><input v-model="faceTypes" type="checkbox" value="notManual" /> Not manual</label
                >
                <label><input v-model="faceTypes" type="checkbox" value="manual" /> Manual</label>
              </div>
            </div>
          </div>
          <ul class="persons">
            <li
              v-for="person in persons"
              :key="person.id"
              :title="`персона плана, кадров: ${bounds.first}—${bounds.last}`"
            >
              {{ person.name }}
            </li>
            <li v-if="persons.length === 0" class="empty">План не выбран или в нём нет персон</li>
          </ul>
          <progress
            class="left-progress"
            :value="persons.length"
            :max="Math.max(persons.length, 1)"
          />

          <div class="syp-card-title">Кадр</div>
          <ShotFrameView :videofile-id="Number(props.videofileId)" :shot="currentShot ?? null" />
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
          :videofile-id="Number(props.videofileId)"
          :shots-total="structure?.shotsTotal ?? 0"
          :first-frame="bounds.first"
          :last-frame="bounds.last"
        />

        <PersonsTab v-else-if="tab === 'Persons'" :videofile-id="Number(props.videofileId)" />

        <ScenesTab v-else-if="tab === 'Scenes'" :videofile-id="Number(props.videofileId)" />

        <FramesTab v-else :videofile-id="Number(props.videofileId)" />
      </div>
    </div>
  </section>
</template>

<style scoped>
.editor-body {
  display: grid;
  gap: 1rem;
  /*
   * Левая часть: колонка планов 440 px плюс крупный кадр 720 px, как в старой
   * форме. Заявленные там 730 px на всю левую часть меньше этой суммы, и
   * JavaFX давал колонкам выйти за границу; здесь ширина берётся по содержимому,
   * а правая часть получает остаток окна и не меньше 920 px.
   */
  grid-template-columns: minmax(27.5rem, max-content) minmax(57.5rem, 1fr);
  align-items: start;
}

.left {
  display: grid;
  /* Второй столбец сжимаемый: при нехватке места фиксированные 45 rem
     выталкивали кадр за границу левой части, и он налезал на вкладки. */
  grid-template-columns: 27.5rem minmax(0, 45rem);
  gap: 0.75rem;
  /* Без этого колонку с крупным кадром раздувает сама картинка: по умолчанию
     элемент сетки не сжимается меньше содержимого. */
  min-width: 0;
}

.left > * {
  min-width: 0;
}

.left-plans,
.left-shot {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  min-width: 0;
}

.shots-scroll {
  /* Список планов прокручивается сам: в старой форме таблица ограничена по
     высоте, и без этого страница растёт на все планы подряд. */
  max-height: 26rem;
  overflow: auto;
}

.shots td {
  padding: 0.2rem;
  vertical-align: top;
}

.type-cell {
  text-align: center;
}

.type-mark {
  display: inline-block;
  min-width: 1.75rem;
  padding: 0.1rem 0.25rem;
  border: 1px solid var(--syp-border);
  border-radius: 0.2rem;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--syp-text-muted);
}

.type-button {
  width: 2rem;
}

.left-progress {
  width: 100%;
  height: 0.7rem;
}

.shot-head {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}

.shot-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  font-size: 0.75rem;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}

.filter-title {
  color: var(--syp-text-muted);
}

.filter-group label {
  display: inline-flex;
  align-items: center;
  gap: 0.15rem;
  white-space: nowrap;
}

.left {
  border: 1px solid var(--syp-border);
  border-radius: 4px;
  display: grid;
  gap: 0.75rem;
  padding: 0.75rem;
}

.selected {
  background: var(--syp-tint);
}

.tabs {
  display: flex;
  gap: 0.25rem;
  margin-bottom: 0.75rem;
}

.tab {
  background: var(--syp-raised);
  border: 1px solid var(--syp-border);
  border-radius: 4px 4px 0 0;
  cursor: pointer;
  padding: 0.4rem 1rem;
}

.tab.active {
  background: var(--syp-surface);
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
  border: 1px solid var(--syp-border);
  border-radius: 4px;
  display: grid;
  gap: 0.25rem;
}

.hint {
  color: var(--syp-text-muted);
  font-size: 0.8rem;
  margin: 0 0 0.25rem;
}

.property-fields {
  display: grid;
  gap: 0.25rem;
  grid-template-columns: 1fr 1fr auto;
}

.placeholder {
  border: 1px dashed var(--syp-text-muted);
  border-radius: 4px;
  padding: 1rem;
}

.bounds,
.empty {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
}

.notice {
  color: var(--syp-text-muted);
  font-size: 0.8125rem;
}
</style>
