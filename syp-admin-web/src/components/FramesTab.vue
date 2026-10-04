<script setup lang="ts">
/**
 * Вкладка кадров: матрица миниатюр и страницы кадров.
 *
 * Форма перенесена по старому проекту. Там границы планов правятся **двойным
 * щелчком по миниатюре кадра**, и это первое действие оператора: разбиение на
 * сцены не имеет смысла, пока планы не совпадают с тем, что на экране.
 *
 * Признаки на миниатюре перенесены вместе с их смыслом: красная граница — найдена
 * алгоритмом, **оранжевая — отменена оператором, зелёная — добавлена оператором**,
 * голубая I — ключевой кадр, синий угол — в кадре есть лица. Смысл цветов в том,
 * что оператор видит на одном экране и что предложила машина, и что он решил сам.
 *
 * Состояние переключателя здесь то же, что в старом проекте: граница не найдена
 * и не отменена; найдена; отменена; добавлена. Переключение даёт разрез плана
 * внутри или слияние с предыдущим.
 */
import { computed, onMounted, ref, watch } from 'vue'
import {
  mergeShots,
  moveShotBoundary,
  readFrames,
  readStructure,
  splitShot,
  type FrameView,
  type FramesView,
} from '../api/structure'
import { readVideofile } from '../api/catalog'
import FrameFacesDialog from './FrameFacesDialog.vue'

const props = defineProps<{ videofileId: number }>()

/** Кадры текущей страницы. */
const frames = ref<FramesView | null>(null)

/** Номер страницы, начиная с нуля. */
const page = ref(0)

/** Размер страницы кадров. */
const PAGE_SIZE = 60

/** Состояние переключателя границы: 0 не найдена, 1 найдена, 2 отменена, 3 добавлена. */
const boundaryState = ref(0)

/** Номер выбранного кадра. */
const chosenFrame = ref<number | null>(null)

/** Открыто ли окно лиц выбранного кадра. */
const facesOpen = ref(false)

const error = ref('')
const notice = ref('')

/** Число кадров на странице. */
const shown = computed(() => frames.value?.frames ?? [])

/**
 * Кадров в секунду видеофайла.
 *
 * Берётся у самого файла: без него страницу нечем подписать временем, а в
 * старой форме в таблице страниц есть колонки «Время: с» и «Время: по».
 */
const framesPerSecond = ref(0)

/**
 * Время кадра в секундах.
 *
 * @param frameNumber номер кадра
 * @returns время в секундах либо `null`, когда частота кадров неизвестна
 */
function timeOf(frameNumber: number): string | null {
  return framesPerSecond.value > 0 ? (frameNumber / framesPerSecond.value).toFixed(1) : null
}

/**
 * Страницы кадров для таблицы.
 *
 * Границы выводятся из общего числа кадров и размера страницы, а не из
 * загруженной страницы: иначе таблица показывала бы только текущую.
 */
const pageRows = computed(() => {
  const total = frames.value?.total ?? 0
  const rows: {
    number: number
    from: number
    to: number
    fromTime: string | null
    toTime: string | null
  }[] = []
  for (let index = 0; index < pages.value; index += 1) {
    const from = index * PAGE_SIZE
    const to = Math.min(from + PAGE_SIZE - 1, Math.max(total - 1, 0))
    rows.push({ number: index, from, to, fromTime: timeOf(from), toTime: timeOf(to) })
  }
  return rows
})

/** Всего страниц кадров. */
const pages = computed(() => Math.max(1, Math.ceil((frames.value?.total ?? 0) / PAGE_SIZE)))

/** Стиль рамки кадра по состоянию границы. */
function frameClass(frame: FrameView): string[] {
  const classes = ['frame-cell']
  if (frame.isKeyframe) classes.push('keyframe')
  if (frame.faceCount > 0) classes.push('has-faces')
  if (frame.isShotBoundary) {
    classes.push(boundaryState.value === 2 ? 'boundary-cancelled' : 'boundary-found')
  }
  if (chosenFrame.value === frame.frameNumber) classes.push('chosen')
  return classes
}

/** Выбирает кадр. */
function choose(frame: FrameView): void {
  chosenFrame.value = frame.frameNumber
  notice.value = ''
}

/** Открывает окно лиц выбранного кадра. */
function openFaces(): void {
  if (chosenFrame.value === null) {
    notice.value = 'Выберите кадр: лица смотреть нечего'
    return
  }
  facesOpen.value = true
}

/**
 * Двойной щелчок переключает состояние границы.
 *
 * @param frame кадр, по которому щёлкнули
 */
function toggleBoundary(frame: FrameView): void {
  chosenFrame.value = frame.frameNumber
  boundaryState.value = boundaryState.value === 3 ? 0 : boundaryState.value + 1
  notice.value = `граница на кадре ${frame.frameNumber}: состояние ${boundaryState.value} из 3`
}

/** Применяет состояние переключателя к границам планов. */
async function apply(): Promise<void> {
  if (chosenFrame.value === null) {
    notice.value = 'Выберите кадр: применять нечего'
    return
  }
  const frame = chosenFrame.value
  try {
    if (boundaryState.value === 1) {
      await splitShot(props.videofileId, frame)
      notice.value = `план разрезан по кадру ${frame}`
    } else if (boundaryState.value === 2) {
      await moveShotBoundary(props.videofileId, frame, frame - 1)
      notice.value = `граница по кадру ${frame} отменена`
    } else if (boundaryState.value === 3) {
      await moveShotBoundary(props.videofileId, frame, frame + 1)
      notice.value = `граница по кадру ${frame} добавлена`
    } else {
      await mergeShots(props.videofileId, frame)
      notice.value = `планы слиты по кадру ${frame}`
    }
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Переходит на страницу кадров. */
async function turnPage(delta: number): Promise<void> {
  const next = page.value + delta
  if (next < 0 || next >= pages.value) {
    notice.value = 'Страница за пределами: переходить некуда'
    return
  }
  page.value = next
  chosenFrame.value = null
  await reload()
}

/** Перечитывает кадры текущей страницы. */
async function reload(): Promise<void> {
  // Частота кадров нужна таблице страниц: без неё колонки времени пусты.
  try {
    const file = await readVideofile(props.videofileId)
    framesPerSecond.value = file.timeBaseDen > 0 ? file.timeBaseNum / file.timeBaseDen : 0
  } catch {
    framesPerSecond.value = 0
  }
  try {
    frames.value = await readFrames(props.videofileId, page.value * PAGE_SIZE, PAGE_SIZE)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Проверяет, что видеофайл разобран: без структуры кадры бессмысленны. */
const structureLoaded = ref(false)

onMounted(async () => {
  try {
    const structure = await readStructure(props.videofileId)
    structureLoaded.value = structure.shotsTotal > 0
    if (!structureLoaded.value) {
      notice.value = 'Планы не созданы: сначала разберите файл на планы, потом правьте границы'
    }
  } catch (failure) {
    error.value = (failure as Error).message
  }
  await reload()
})

watch(
  () => props.videofileId,
  () => {
    page.value = 0
    chosenFrame.value = null
    void reload()
  },
)
</script>

<template>
  <section class="frames">
    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <div class="toolbar">
      <div class="swatches">
        <span class="swatch keyframe">ключевой кадр</span>
        <span class="swatch has-faces">есть лица</span>
        <span class="swatch boundary-found">граница найдена</span>
        <span class="swatch boundary-cancelled">отменена</span>
        <span class="swatch boundary-added">добавлена</span>
      </div>
      <div class="actions">
        <button type="button" class="btn btn-sm btn-primary" @click="apply">Применить к границам планов</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="openFaces">Лица кадра</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="turnPage(-1)">К предыдущей странице</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="turnPage(1)">К следующей странице</button>
      </div>
    </div>

    <!-- Таблица страниц: в старой форме она называется tblPagesFrames и несёт
         четыре колонки — время и номера кадров начала и конца. -->
    <table class="table table-sm pages">
      <thead>
        <tr>
          <th>Время: с</th>
          <th>Время: по</th>
          <th>Кадры: с</th>
          <th>Кадры: по</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="row in pageRows"
          :key="row.number"
          :class="{ picked: row.number === page }"
          @click="turnPage(row.number - page)"
        >
          <td>{{ row.fromTime ?? '—' }}</td>
          <td>{{ row.toTime ?? '—' }}</td>
          <td>{{ row.from }}</td>
          <td>{{ row.to }}</td>
        </tr>
      </tbody>
    </table>

    <p class="state">
      Состояние границы: {{ boundaryState }} из 3 (0 — не найдена, 1 — найдена, 2 — отменена, 3 — добавлена).
      Выбран кадр: {{ chosenFrame ?? 'нет' }}. Страница {{ page + 1 }} из {{ pages }}.
    </p>

    <div class="frames-matrix">
      <button
        v-for="frame in shown"
        :key="frame.frameNumber"
        type="button"
        :class="frameClass(frame)"
        :title="`кадр ${frame.frameNumber}, лиц: ${frame.faceCount}`"
        @click="choose(frame)"
        @dblclick="toggleBoundary(frame)"
      >
        <span class="number">{{ frame.frameNumber }}</span>
        <span class="time">{{ timeOf(frame.frameNumber) ?? frame.frameNumber }}</span>
        <span v-if="frame.isKeyframe" class="mark key">I</span>
        <span v-if="frame.faceCount > 0" class="mark faces">лица</span>
      </button>
      <p v-if="shown.length === 0" class="empty">Кадров на странице нет</p>
    </div>

    <p v-if="!structureLoaded" class="notice" role="status">
      Планы ещё не созданы, поэтому границы править не на чем. Правка станет доступна после разбора файла.
    </p>
    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

    <FrameFacesDialog
      v-if="facesOpen && chosenFrame !== null"
      :videofile-id="props.videofileId"
      :frame-number="chosenFrame"
      @closed="facesOpen = false"
    />
  </section>
</template>

<style scoped>
.frames {
  display: grid;
  gap: 0.75rem;
}

.toolbar {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 1rem;
  justify-content: space-between;
}

.swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.swatch {
  border: 1px solid var(--syp-text-muted);
  font-size: 0.8rem;
  padding: 0.1rem 0.35rem;
}

.swatch.keyframe {
  border-color: var(--syp-link);
}

.swatch.has-faces {
  border-color: var(--syp-primary);
}

.swatch.boundary-found {
  border-color: var(--syp-danger);
}

.swatch.boundary-cancelled {
  border-color: var(--syp-warning);
}

.swatch.boundary-added {
  border-color: var(--syp-success);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
}

.pages {
  max-height: 13rem;
  overflow: auto;
  margin-bottom: 0.5rem;
}

.pages td,
.pages th {
  padding: 0.15rem 0.4rem;
  font-size: 0.78rem;
  font-variant-numeric: tabular-nums;
}

.pages tbody tr {
  cursor: pointer;
}

.pages tbody tr.picked {
  background: var(--syp-tint);
}

.frames-matrix {
  display: grid;
  gap: 0.35rem;
  grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr));
}

.frame-cell {
  background: var(--syp-bg);
  border: 2px solid var(--syp-raised);
  border-radius: 3px;
  color: var(--syp-text-muted);
  cursor: pointer;
  display: grid;
  gap: 0.1rem;
  padding: 0.35rem;
  position: relative;
  text-align: left;
}

.frame-cell.keyframe {
  border-color: var(--syp-link);
}

.frame-cell.has-faces::after {
  border-color: var(--syp-primary);
  border-style: solid;
  border-width: 0 8px 8px 0;
  content: '';
  position: absolute;
  right: 0;
  top: 0;
}

.frame-cell.boundary-found {
  border-color: var(--syp-danger);
}

.frame-cell.boundary-cancelled {
  border-color: var(--syp-warning);
}

.frame-cell.chosen {
  outline: 2px solid var(--syp-warning);
}

.number {
  font-weight: 600;
}

.time {
  color: var(--syp-text-muted);
  font-size: 0.75rem;
}

.mark {
  font-size: 0.7rem;
}

.mark.key {
  color: var(--syp-link);
}

.mark.faces {
  color: var(--syp-primary);
}

.state,
.empty {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
}
</style>
