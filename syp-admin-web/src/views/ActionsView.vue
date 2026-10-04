<script setup lang="ts">
// Экран операций над проектом.
//
// Форма повторяет `project-actions-view` старого проекта: слева таблица
// файлов с семнадцатью колонками-индикаторами, справа переключатель
// `RECREATE IF EXISTS`, кнопка запуска, кнопка обучения модели и пятнадцать
// чекбоксов операций. Снизу две полосы хода работы, как в старой форме.
//
// Отличие одно и оно намеренное: чекбокс операции, которая в проекте не
// выполняется, остаётся на месте — иначе оператор увидит незнакомый экран —
// но подписан тем, чем эта операция кончается сейчас. Молчаливый ноль вместо
// признака «делать нечем» здесь обманчив: он выглядит как «ещё не сделано».

import { computed, onMounted, ref } from 'vue'
import { listVideofile, type VideofileView } from '../api/catalog'
import { startFacesScan } from '../api/characters'
import { startAnalysis } from '../api/structure'
import {
  INDICATORS,
  OPERATIONS,
  STATE_MARK,
  STATE_TITLE,
  readPipeline,
  type PipelineRow,
  type PipelineState,
} from '../api/pipeline'

const props = defineProps<{ projectId: string }>()

const project = computed(() => Number(props.projectId))

/** Файлы проекта, как их отдаёт сервер. */
const files = ref<VideofileView[]>([])

/** Состояние конвейера по каждому файлу. */
const rows = ref<PipelineRow[]>([])

/** Ошибка чтения; пустая строка — ошибки none. */
const error = ref('')

/** Ответ сервера на последнее нажатие; показывается под таблицей. */
const notice = ref('')

/** Идёт ли чтение состояния: без этого таблица мигает при перезагрузке. */
const busy = ref(false)

/** Идентификаторы выбранных файлов. */
const selected = ref<number[]>([])

/** Отметки операций по коду индикатора. */
const chosen = ref<Record<string, boolean>>({})

/** Перезапуск уже сделанного: без него повтор ничего не делает. */
const recreate = ref(false)

/** Первая полоса хода работы: сбор пачек заданий. */
const first = ref({ done: 0, total: 0, note: '' })

/** Вторая полоса хода работы: выполнение заданий. */
const second = ref({ done: 0, total: 0, note: '' })

/** Обучается ли модель: кнопка обучения на время нажатия занята. */
const training = ref(false)

/** Коды индикаторов, по которым состояние выводится из сервера. */
const CODES = INDICATORS.map((indicator) => indicator.code).filter(
  (code) => code !== '#' && code !== 'Файл',
)

/** Операции, которые действительно запускают задание. */
const runnable = computed(() => OPERATIONS.filter((operation) => operation.runs))

/** Выбран ли хоть один файл. */
const hasSelection = computed(() => selected.value.length > 0)

/**
 * Состояние индикатора по файлу.
 *
 * @param row строка конвейера
 * @param code код индикатора
 * @returns состояние индикатора
 */
function stateOf(row: PipelineRow, code: string): PipelineState {
  return row.states[code] ?? 'unknown'
}

/**
 * Подсказка под ячейкой индикатора.
 *
 * @param row строка конвейера
 * @param code код индикатора
 * @returns текст подсказки
 */
function titleOf(row: PipelineRow, code: string): string {
  const state = stateOf(row, code)
  const reason = row.reasons[code]
  return reason === undefined || reason === ''
    ? STATE_TITLE[state]
    : `${STATE_TITLE[state]}: ${reason}`
}

/**
 * Отмечает или снимает отметку операции.
 *
 * @param code код операции
 */
function toggle(code: string): void {
  chosen.value = { ...chosen.value, [code]: !chosen.value[code] }
}

/**
 * Выбирает файл; выбор снимается повторным щелчком, как в старой форме.
 *
 * @param videofileId видеофайл
 */
function pick(videofileId: number): void {
  selected.value = selected.value.includes(videofileId)
    ? selected.value.filter((item) => item !== videofileId)
    : [...selected.value, videofileId]
}

/** Выбирает все файлы или снимает выбор со всех. */
function pickAll(): void {
  selected.value =
    selected.value.length === files.value.length ? [] : files.value.map((file) => file.id)
}

/**
 * Запускает отмеченные операции по выбранным файлам.
 *
 * Задание ставится в очередь один раз на файл: отмеченные операции
 * «Анализ структуры» и «Лица» — это два задания, а пятнадцать отметок сводятся
 * к ним, потому что других заданий в проекте none. Файл, у которого нужный
 * результат уже есть, повторно не ставится — так же, как в старом проекте, —
 * если только не включён `RECREATE IF EXISTS`.
 */
async function run(): Promise<void> {
  if (!hasSelection.value) {
    notice.value = 'Не выбран ни один файл: запускать нечего'
    return
  }
  const analysisWanted = OPERATIONS.some(
    (operation) => chosen.value[operation.code] && operation.runs,
  )
  const facesWanted = OPERATIONS.some(
    (operation) => chosen.value[operation.code] && operation.code === 'DF',
  )
  if (!analysisWanted && !facesWanted) {
    notice.value =
      'Не отмечено ни одной операции, которая что-то запускает: отмеченные операции в проекте не выполняются'
    return
  }
  const targets = rows.value.filter((row) => selected.value.includes(row.videofileId))
  let queued = 0
  let skipped = 0
  const refusals: string[] = []
  busy.value = true
  try {
    for (const row of targets) {
      let started = false
      if (analysisWanted) {
        if (recreate.value || stateOf(row, 'AF') !== 'yes') {
          await startAnalysis(row.videofileId)
          queued += 1
          started = true
        }
      }
      if (facesWanted) {
        if (recreate.value || stateOf(row, 'DF') !== 'yes') {
          await startFacesScan(row.videofileId)
          queued += 1
          started = true
        }
      }
      if (!started) skipped += 1
    }
    error.value = ''
    notice.value =
      `заданий поставлено: ${queued}, пропущено как уже сделанные: ${skipped}` +
      (skipped > 0 && !recreate.value ? ' — повтор включите переключателем RECREATE IF EXISTS' : '')
  } catch (failure) {
    error.value = (failure as Error).message
    if (refusals.length > 0) {
      notice.value = refusals.join('; ')
    }
  } finally {
    busy.value = false
    await reload()
  }
}

/**
 * Обучает модель распознавания лиц.
 *
 * Обучения в проекте нет: таблицы версий модели пусты, кода обучения none.
 * Кнопка оставлена на месте и говорит об этом прямо, потому что по описанию
 * оператора «периодически запускает процесс дообучения» — и молчаливая
 * кнопка, которая ничего не делает, выглядела бы как поломка.
 */
async function train(): Promise<void> {
  training.value = true
  try {
    const named = rows.value
      .filter((row) => selected.value.includes(row.videofileId))
      .map((row) => `${row.name}: распознано ${row.states.RF === 'yes' ? 'есть' : 'no'}`)
    notice.value =
      'Заглушка: обучения модели в проекте none. ' +
      'Эталоны помечаются на вкладке «Персоны» главного редактора, ' +
      'но обученной версии модели в базе нет, поэтому обучать нечем. ' +
      (named.length > 0 ? `Выбрано файлов: ${named.length}.` : '')
  } finally {
    training.value = false
  }
}

/** Читает файлы проекта и состояние конвейера по каждому. */
async function reload(): Promise<void> {
  busy.value = true
  try {
    files.value = await listVideofile(project.value)
    const collected: PipelineRow[] = []
    for (const file of files.value) {
      collected.push(await readPipeline(file.id, file.ordinal, file.name))
    }
    rows.value = collected
    if (collected.length > 0) {
      first.value = {
        done: collected.length,
        total: files.value.length,
        note: `файлов прочитано: ${collected.length} из ${files.value.length}`,
      }
      second.value = {
        done: collected.filter((row) => stateOf(row, 'AF') === 'yes').length,
        total: collected.length,
        note: 'проанализировано',
      }
    }
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(reload)
</script>

<template>
  <section class="actions">
    <h1 class="syp-page-title">Project actions</h1>

    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <div class="actions-body">
      <div class="files">
        <div class="syp-card-title">Files</div>
        <div class="files-toolbar">
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="pickAll">
            {{ selected.length === files.length ? 'Clear selection' : 'Select all' }}
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy"
            @click="reload"
          >
            Refresh
          </button>
        </div>
        <div class="files-wrap">
          <table class="table table-sm files-table">
            <thead>
              <tr>
                <th v-for="indicator in INDICATORS" :key="indicator.code" :title="indicator.title">
                  {{ indicator.code }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="row in rows"
                :key="row.videofileId"
                :class="{ picked: selected.includes(row.videofileId) }"
                @click="pick(row.videofileId)"
              >
                <td class="num">{{ row.ordinal }}</td>
                <td class="name" :title="`${row.name}, frames: ${row.framesTotal ?? 'unknown'}`">
                  {{ row.name }}
                </td>
                <td
                  v-for="code in CODES"
                  :key="code"
                  class="mark"
                  :class="stateOf(row, code)"
                  :title="titleOf(row, code)"
                >
                  {{ STATE_MARK[stateOf(row, code)] }}
                </td>
              </tr>
              <tr v-if="rows.length === 0">
                <td :colspan="INDICATORS.length" class="empty">
                  {{ busy ? 'Reading the file state…' : 'The project has no files' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <aside class="side">
        <div class="syp-card-title">Run processing</div>
        <div class="form-check">
          <input id="recreate" v-model="recreate" class="form-check-input" type="checkbox" />
          <label class="form-check-label" for="recreate">RECREATE IF EXISTS</label>
        </div>
        <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="run">
          Run operations
        </button>
        <button
          type="button"
          class="btn btn-sm btn-outline-primary"
          :disabled="training"
          @click="train"
        >
          Train the face model
        </button>
        <div class="syp-card-title">Actions</div>
        <div v-for="operation in OPERATIONS" :key="operation.code" class="operation">
          <div class="form-check">
            <input
              :id="`op-${operation.code}`"
              class="form-check-input"
              type="checkbox"
              :checked="chosen[operation.code] === true"
              @change="toggle(operation.code)"
            />
            <label class="form-check-label" :for="`op-${operation.code}`">{{
              operation.title
            }}</label>
          </div>
          <p class="effect" :class="{ runs: operation.runs }">{{ operation.effect }}</p>
        </div>
      </aside>
    </div>

    <div class="progress-row">
      <progress :value="first.done" :max="Math.max(first.total, 1)" />
      <span class="progress-note">{{ first.note }}</span>
    </div>
    <div class="progress-row">
      <progress :value="second.done" :max="Math.max(second.total, 1)" />
      <span class="progress-note">{{ second.note }}</span>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>
    <p class="counts">
      Jobs run the operations: {{ runnable.length }} of {{ OPERATIONS.length }}; the rest are not
      performed in this deployment, and this is written under every checkbox.
    </p>
  </section>
</template>

<style scoped>
.actions-body {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
}
.files {
  flex: 1 1 auto;
  min-width: 0;
}
.files-toolbar {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}
/* Семнадцать колонок не помещаются в левую часть: без прокрутки последние
   обрезались и операции на них нельзя было увидеть. */
.files-wrap {
  max-height: 26rem;
  overflow: auto;
}

.files-table th,
.files-table td {
  padding: 0.15rem 0.3rem;
  font-size: 0.8rem;
  white-space: nowrap;
}
.files-table th {
  text-align: center;
}
.files-table .num {
  text-align: right;
}
.files-table .name {
  max-width: 18rem;
  overflow: hidden;
  text-overflow: ellipsis;
}
.files-table tbody tr {
  cursor: pointer;
}
.files-table tbody tr.picked {
  background: var(--syp-tint);
}
.mark {
  text-align: center;
}
.mark.yes {
  color: var(--syp-success);
  font-weight: 600;
}
.mark.no {
  color: var(--syp-danger);
}
.mark.absent {
  color: var(--syp-text-muted);
}
.mark.unknown {
  color: var(--syp-warning);
  font-weight: 600;
}
.empty {
  text-align: center;
  color: var(--syp-text-muted);
}
.side {
  flex: 0 0 26rem;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.operation {
  border-bottom: 1px solid var(--syp-border);
  padding-bottom: 0.2rem;
}
.effect {
  margin: 0 0 0.2rem 1.5rem;
  font-size: 0.75rem;
  color: var(--syp-text-muted);
}
.effect.runs {
  color: var(--syp-link);
}
.progress-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.5rem;
}
.progress-row progress {
  flex: 0 0 20rem;
  height: 0.9rem;
}
.progress-note {
  font-size: 0.8rem;
  color: var(--syp-text-muted);
}
.counts {
  font-size: 0.8rem;
  color: var(--syp-text-muted);
}
</style>
