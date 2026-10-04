<script setup lang="ts">
// Редактор фильтров.
//
// Форма повторяет `filter-edit-view` старого проекта: слева три уровня
// (фильтры, группы, условия) с кнопками порядка, добавления и удаления,
// справа файлы проекта, кнопка применения и результат отбора, внизу полоса
// хода работы.
//
// Данные фильтров, групп и условий — заглушка: бэкенда фильтров в проекте
// нет, и это написано над формой. Список файлов и планы — настоящие.
//
// Что исправлено по дефектам старого проекта: подпись под полосой хода работы
// называет, что происходит, вместо английского `Label`; подсказки кнопок
// описывают действие, а не скопированы из формы списка файлов; создание
// видео по отобранным планам говорит, что подсистемы нет, а не падает молча.

import { computed, onMounted, ref } from 'vue'
import { listVideofile, type VideofileView } from '../api/catalog'
import FilterConditionDialog from '../components/FilterConditionDialog.vue'
import {
  IS_STUB,
  STUB_NOTICE,
  applyFilter,
  conditionName,
  filters,
  newCondition,
  newFilter,
  newGroup,
  selectedShots,
  type FilterCondition,
} from '../api/filter-stubs'

const props = defineProps<{ projectId: string }>()

const project = computed(() => Number(props.projectId))

/** Файлы проекта. */
const files = ref<VideofileView[]>([])

/** Выбранные файлы: по ним применяется фильтр. */
const chosenFiles = ref<number[]>([])

/** Номер выбранного фильтра. */
const filterIndex = ref(0)

/** Номер выбранной группы. */
const groupIndex = ref(0)

/** Номер выбранного условия. */
const conditionIndex = ref(0)

/** Черновик условия в открытом диалоге. */
const draft = ref<FilterCondition | null>(null)

/** Ответ последнего действия. */
const notice = ref('')

/** Ошибка чтения. */
const error = ref('')

/** Идёт ли отбор. */
const busy = ref(false)

/** Ход работы под полосой: в старой форме здесь стоял английский `Label`. */
const progressNote = ref('отбор не запускался')

/** Выбранный фильтр, если он есть. */
const currentFilter = computed(() => filters.value[filterIndex.value] ?? null)

/** Выбранная группа, если она есть. */
const currentGroup = computed(() => currentFilter.value?.groups[groupIndex.value] ?? null)

/** Выбранное условие, если оно есть. */
const currentCondition = computed(
  () => currentGroup.value?.conditions[conditionIndex.value] ?? null,
)

/**
 * Переносит элемент списка на другое место.
 *
 * Порядок в фильтрах задаёт порядок проверки, поэтому перестановка — не
 * украшение, а часть смысла фильтра.
 *
 * @param list список
 * @param from откуда
 * @param to куда
 */
/**
 * Переставляет элемент списка.
 *
 * @param list список
 * @param from откуда
 * @param to куда
 */
function move<T>(list: T[], from: number, to: number): void {
  if (to < 0 || to >= list.length) {
    return
  }
  const [item] = list.splice(from, 1)
  list.splice(to, 0, item)
  reindex()
}

/**
 * Выбор фильтра сбрасывает вложенные указатели.
 *
 * Обработчик вынесен в метод, а не оставлен в разметке: выражение из трёх
 * операторов не влезает в строку, prettier переносит его по строкам, а
 * компилятор шаблона перенос без точек с запятой не принимает. С именем
 * строка получается короткой и переживает форматирование.
 *
 * @param index номер выбранного фильтра
 */
function pickFilter(index: number): void {
  filterIndex.value = index
  groupIndex.value = 0
  conditionIndex.value = 0
}

/**
 * Выбор группы сбрасывает указатель на условие.
 *
 * @param index номер выбранной группы
 */
function pickGroup(index: number): void {
  groupIndex.value = index
  conditionIndex.value = 0
}

/**
 * Отмечает или снимает отметку у файла.
 *
 * @param fileId номер файла
 */
function toggleFile(fileId: number): void {
  chosenFiles.value = chosenFiles.value.includes(fileId)
    ? chosenFiles.value.filter((item) => item !== fileId)
    : [...chosenFiles.value, fileId]
}

/** Пересчитывает порядковые номера после перестановки. */
function reindex(): void {
  filters.value.forEach((filter, index) => {
    filter.order = index
    filter.groups.forEach((group, groupAt) => {
      group.order = groupAt
      group.conditions.forEach((condition, conditionAt) => {
        condition.order = conditionAt
      })
    })
  })
}

/** Создаёт фильтр. */
function addFilter(): void {
  filters.value.push(newFilter(filters.value.length))
  filterIndex.value = filters.value.length - 1
  groupIndex.value = 0
  conditionIndex.value = 0
  notice.value = 'Фильтр добавлен. Он живёт только в этой форме: бэкенда фильтров нет'
}

/** Удаляет выбранный фильтр. */
function removeFilter(): void {
  if (currentFilter.value === null) {
    notice.value = 'Не выбран фильтр: удалять нечего'
    return
  }
  const name = currentFilter.value.name
  filters.value.splice(filterIndex.value, 1)
  if (filterIndex.value >= filters.value.length) {
    filterIndex.value = Math.max(filters.value.length - 1, 0)
  }
  groupIndex.value = 0
  conditionIndex.value = 0
  notice.value = `Фильтр «${name}» удалён из формы`
}

/** Создаёт группу в выбранном фильтре. */
function addGroup(): void {
  if (currentFilter.value === null) {
    notice.value = 'Не выбран фильтр: группе негде жить'
    return
  }
  currentFilter.value.groups.push(newGroup(currentFilter.value.groups.length))
  groupIndex.value = currentFilter.value.groups.length - 1
  conditionIndex.value = 0
  notice.value = 'Группа добавлена'
}

/** Удаляет выбранную группу. */
function removeGroup(): void {
  if (currentGroup.value === null) {
    notice.value = 'Не выбрана группа: удалять нечего'
    return
  }
  const name = currentGroup.value.name
  currentFilter.value?.groups.splice(groupIndex.value, 1)
  if (groupIndex.value >= (currentFilter.value?.groups.length ?? 0)) {
    groupIndex.value = Math.max((currentFilter.value?.groups.length ?? 1) - 1, 0)
  }
  conditionIndex.value = 0
  notice.value = `Группа «${name}» удалена`
}

/** Удаляет выбранное условие. */
function removeCondition(): void {
  if (currentCondition.value === null) {
    notice.value = 'Не выбрано условие: удалять нечего'
    return
  }
  currentGroup.value?.conditions.splice(conditionIndex.value, 1)
  if (conditionIndex.value >= (currentGroup.value?.conditions.length ?? 0)) {
    conditionIndex.value = Math.max((currentGroup.value?.conditions.length ?? 1) - 1, 0)
  }
  notice.value = 'Условие удалено'
}

/** Открывает диалог создания условия. */
function addCondition(): void {
  if (currentGroup.value === null) {
    notice.value = 'Не выбрана группа: условие ей не принадлежит'
    return
  }
  draft.value = newCondition(currentGroup.value.conditions.length)
}

/**
 * Условие подтверждено и добавлено в группу.
 *
 * @param condition подтверждённое условие
 */
function onConditionConfirmed(condition: FilterCondition): void {
  currentGroup.value?.conditions.push(condition)
  conditionIndex.value = (currentGroup.value?.conditions.length ?? 1) - 1
  draft.value = null
  notice.value = 'Условие добавлено в группу'
}

/** Применяет фильтр к выбранным файлам. */
async function apply(): Promise<void> {
  busy.value = true
  try {
    const answer = await applyFilter(project.value, chosenFiles.value)
    progressNote.value = `показано планов: ${answer.shots}`
    notice.value = answer.notice
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

/** Создание видео по отобранным планам: подсистемы в проекте none. */
function createVideo(): void {
  notice.value =
    `Подсистемы нарезки видео в проекте нет, поэтому файлы по отобранным планам ` +
    `(${selectedShots.value.length}) не создаются. Кнопка оставлена на месте, чтобы ` +
    `отсутствие подсистемы было видно, а не выглядело как поломка`
}

/** Создание видео по отобранным планам для всех персон: то же самое. */
function createVideoForAllPersons(): void {
  notice.value =
    'Подсистемы нарезки видео в проекте нет, поэтому файлы для всех персон ' +
    'по отобранным планам не создаются'
}

onMounted(async () => {
  try {
    files.value = await listVideofile(project.value)
    chosenFiles.value = files.value.map((file) => file.id)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <section class="filters">
    <h1 class="syp-page-title">Filters editor</h1>

    <p v-if="IS_STUB" class="stub" role="status">{{ STUB_NOTICE }}</p>
    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <div class="filters-body">
      <div class="levels">
        <!-- Уровень 1: фильтры -->
        <div class="level">
          <div class="syp-card-title">Filters</div>
          <table class="table table-sm">
            <thead>
              <tr>
                <th class="num">#</th>
                <th>Filter</th>
                <th class="join" title="Group join">&amp;|</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(item, index) in filters"
                :key="index"
                :class="{ picked: index === filterIndex }"
                @click="pickFilter(index)"
              >
                <td class="num">{{ index }}</td>
                <td>{{ item.name }}</td>
                <td class="join">{{ item.isAnd ? 'AND' : 'OR' }}</td>
              </tr>
            </tbody>
          </table>
          <input
            v-if="currentFilter"
            v-model="currentFilter.name"
            class="form-control form-control-sm"
            type="text"
            placeholder="filter name"
          />
          <div class="andor">
            <label
              ><input v-model="currentFilter!.isAnd" type="radio" :checked="currentFilter!.isAnd" />
              AND</label
            >
            <label
              ><input
                v-model="currentFilter!.isAnd"
                type="radio"
                :checked="!currentFilter!.isAnd"
              />
              OR</label
            >
          </div>
          <div class="row-buttons">
            <button type="button" title="To the start" @click="move(filters, filterIndex, 0)">
              ⟰
            </button>
            <button type="button" title="Up" @click="move(filters, filterIndex, filterIndex - 1)">
              ⇧
            </button>
            <button type="button" title="Down" @click="move(filters, filterIndex, filterIndex + 1)">
              ⇩
            </button>
            <button
              type="button"
              title="To the end"
              @click="move(filters, filterIndex, filters.length - 1)"
            >
              ⟱
            </button>
            <button type="button" class="add" title="Add filter" @click="addFilter">+</button>
            <button type="button" class="del" title="Delete filter" @click="removeFilter">×</button>
          </div>
        </div>

        <!-- Уровень 2: группы -->
        <div class="level">
          <div class="syp-card-title">Groups</div>
          <table class="table table-sm">
            <thead>
              <tr>
                <th class="num">#</th>
                <th>Group</th>
                <th class="join">&amp;|</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="(currentFilter?.groups.length ?? 0) === 0">
                <td colspan="3" class="empty">no groups</td>
              </tr>
              <tr
                v-for="(item, index) in currentFilter?.groups ?? []"
                :key="index"
                :class="{ picked: index === groupIndex }"
                @click="pickGroup(index)"
              >
                <td class="num">{{ index }}</td>
                <td>{{ item.name }}</td>
                <td class="join">{{ item.isAnd ? 'AND' : 'OR' }}</td>
              </tr>
            </tbody>
          </table>
          <input
            v-if="currentGroup"
            v-model="currentGroup.name"
            class="form-control form-control-sm"
            type="text"
            placeholder="group name"
          />
          <div v-if="currentGroup" class="andor">
            <label
              ><input v-model="currentGroup.isAnd" type="radio" :checked="currentGroup.isAnd" />
              AND</label
            >
            <label
              ><input v-model="currentGroup.isAnd" type="radio" :checked="!currentGroup.isAnd" />
              OR</label
            >
          </div>
          <div class="row-buttons">
            <button
              type="button"
              title="To the start"
              @click="move(currentFilter?.groups ?? [], groupIndex, 0)"
            >
              ⟰
            </button>
            <button
              type="button"
              title="Up"
              @click="move(currentFilter?.groups ?? [], groupIndex, groupIndex - 1)"
            >
              ⇧
            </button>
            <button
              type="button"
              title="Down"
              @click="move(currentFilter?.groups ?? [], groupIndex, groupIndex + 1)"
            >
              ⇩
            </button>
            <button
              type="button"
              title="To the end"
              @click="
                move(
                  currentFilter?.groups ?? [],
                  groupIndex,
                  (currentFilter?.groups.length ?? 1) - 1,
                )
              "
            >
              ⟱
            </button>
            <button type="button" class="add" title="Add group" @click="addGroup">+</button>
            <button type="button" class="del" title="Delete group" @click="removeGroup">×</button>
          </div>
        </div>

        <!-- Уровень 3: условия -->
        <div class="level">
          <div class="syp-card-title">Conditions</div>
          <table class="table table-sm">
            <thead>
              <tr>
                <th class="num">#</th>
                <th>Condition</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="(currentGroup?.conditions.length ?? 0) === 0">
                <td colspan="2" class="empty">no conditions</td>
              </tr>
              <tr
                v-for="(item, index) in currentGroup?.conditions ?? []"
                :key="index"
                :class="{ picked: index === conditionIndex }"
                @click="conditionIndex = index"
              >
                <td class="num">{{ index }}</td>
                <td>{{ conditionName(item) }}</td>
              </tr>
            </tbody>
          </table>
          <div class="row-buttons">
            <button
              type="button"
              title="To the start"
              @click="move(currentGroup?.conditions ?? [], conditionIndex, 0)"
            >
              ⟰
            </button>
            <button
              type="button"
              title="Up"
              @click="move(currentGroup?.conditions ?? [], conditionIndex, conditionIndex - 1)"
            >
              ⇧
            </button>
            <button
              type="button"
              title="Down"
              @click="move(currentGroup?.conditions ?? [], conditionIndex, conditionIndex + 1)"
            >
              ⇩
            </button>
            <button
              type="button"
              title="To the end"
              @click="
                move(
                  currentGroup?.conditions ?? [],
                  conditionIndex,
                  (currentGroup?.conditions.length ?? 1) - 1,
                )
              "
            >
              ⟱
            </button>
            <button type="button" class="add" title="Add condition" @click="addCondition">+</button>
            <button type="button" class="del" title="Delete condition" @click="removeCondition">
              ×
            </button>
          </div>
        </div>
      </div>

      <!-- Правая часть: файлы, кнопка применения и результат -->
      <div class="apply">
        <div class="syp-card-title">Files</div>
        <table class="table table-sm files">
          <thead>
            <tr>
              <th class="num">#</th>
              <th>File</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="file in files"
              :key="file.id"
              :class="{ picked: chosenFiles.includes(file.id) }"
              @click="toggleFile(file.id)"
            >
              <td class="num">{{ file.ordinal }}</td>
              <td>{{ file.name }}</td>
            </tr>
          </tbody>
        </table>
        <button
          type="button"
          class="btn btn-sm btn-primary apply-button"
          :disabled="busy"
          @click="apply"
        >
          &gt;&gt; Apply filter
        </button>
        <div class="syp-card-title">Shots</div>
        <table class="table table-sm shots">
          <thead>
            <tr>
              <th>FILE</th>
              <th>FROM</th>
              <th>TO</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="selectedShots.length === 0">
              <td colspan="3" class="empty">selection has not run</td>
            </tr>
            <tr v-for="(shot, index) in selectedShots" :key="index">
              <td>{{ shot.fileName }}</td>
              <td class="num">{{ shot.from }}</td>
              <td class="num">{{ shot.to }}</td>
            </tr>
          </tbody>
        </table>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="createVideo">
          Create a video from the selected shots
        </button>
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary"
          @click="createVideoForAllPersons"
        >
          Create a video from the selected shots for all persons
        </button>
      </div>
    </div>

    <div class="progress-row">
      <progress :value="selectedShots.length" :max="Math.max(selectedShots.length, 1)" />
      <span class="progress-note">{{ progressNote }}</span>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

    <FilterConditionDialog
      v-if="draft !== null"
      v-model:draft="draft"
      :project-id="project"
      @confirmed="onConditionConfirmed"
      @closed="draft = null"
    />
  </section>
</template>

<style scoped>
.stub {
  padding: 0.5rem 0.75rem;
  background: var(--syp-tint);
  border: 1px solid var(--syp-warning);
  border-radius: 0.3rem;
  font-size: 0.8125rem;
}
.filters-body {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
}
.levels {
  display: flex;
  gap: 0.75rem;
  flex: 1 1 auto;
  min-width: 0;
}
.level {
  flex: 1 1 0;
  min-width: 0;
}
.level .table td,
.level .table th {
  padding: 0.15rem 0.3rem;
  font-size: 0.8rem;
}
.level .table tbody tr {
  cursor: pointer;
}
.level .table tbody tr.picked {
  background: var(--syp-tint);
}
.num {
  text-align: right;
}
.join {
  text-align: center;
}
.empty {
  text-align: center;
  color: var(--syp-text-muted);
}
.andor {
  display: flex;
  gap: 0.75rem;
  margin: 0.25rem 0;
  font-size: 0.8rem;
}
.row-buttons {
  display: flex;
  gap: 0.2rem;
  margin-top: 0.3rem;
}
.row-buttons button {
  width: 1.9rem;
  height: 1.9rem;
  font-size: 0.9rem;
}
.row-buttons .add {
  margin-left: auto;
}
.row-buttons .del {
  color: var(--syp-danger);
}
.apply {
  flex: 0 0 24rem;
}
.apply .table td,
.apply .table th {
  padding: 0.15rem 0.3rem;
  font-size: 0.8rem;
}
.apply .files tbody tr {
  cursor: pointer;
}
.apply .files tbody tr.picked {
  background: var(--syp-tint);
}
.apply-button {
  margin: 0.35rem 0 0.75rem;
}
.progress-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.75rem;
}
.progress-row progress {
  flex: 0 0 16rem;
  height: 0.9rem;
}
.progress-note {
  font-size: 0.8rem;
  color: var(--syp-text-muted);
}
</style>
