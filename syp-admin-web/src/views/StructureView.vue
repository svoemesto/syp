// Экран структуры видеофайла. // // Экран отвечает на вопросы «что система нашла» и «где в
видеофайле // находится этот кадр». Первый закрывает таблица сцен с их планами, второй — // лист
превью: у видеофайла 88 643 кадра и 347 листов, и без листов превью // «найти сцену» означало бы
пересчитывать номера кадров в уме. // // Третий слой — сырой результат автоматики последнего
прогона. Он нужен для // сравнения: без него нельзя увидеть, что машина предложила и что человек с
// этим сделал (FR-093). Правка границ — часть этого же экрана: без неё // «контролировать разбивку
на сцены» нечем. // // Цвет происхождения задан один раз в теме: красный — алгоритм, зелёный — //
оператор, оранжевый — отменено решение алгоритма (FR-015, FR-016).

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PreviewSheetView from '../components/PreviewSheetView.vue'
import PropertyEditor from '../components/PropertyEditor.vue'
import SceneDetailPanel from '../components/SceneDetailPanel.vue'
import ShotDetailPanel from '../components/ShotDetailPanel.vue'
import StateBlock from '../components/StateBlock.vue'
import { analysisRevision } from '../stores/notifications'
import { useStructureStore } from '../stores/structure'

const route = useRoute()
const store = useStructureStore()

/** Кадр, введённый в поле перехода. */
const frameInput = ref<number | null>(null)

/** Показывается ли раздел с отказом последней операции. */
const editNotice = ref('')

/** Выбранный план: у него своя карточка с доводкой границы. */
const selectedShotId = ref<number | null>(null)

/** Идентификатор видеофайла из адреса. */
const videofileId = computed(() => Number(route.params.videofileId))

/** Сцены текущей страницы для показа. */
const scenes = computed(() => store.visibleScenes.value)

/** Выбранная сцена с её планами. */
const selected = computed(() => store.selectedScene.value)

/**
 * Выбранный план выбранной сцены.
 *
 * Пусто, пока план не выбран: молча выбирать первый план значило бы править
 * не то, что оператор открыл, а показывать блок доводки над чужим планом —
 * ещё хуже.
 */
watch(
  () => selected.value?.firstFrame ?? null,
  (frame) => {
    if (frame !== null) {
      fullFrame.value = frame
    }
  },
  { immediate: true },
)

const selectedShot = computed(() => {
  const scene = selected.value
  if (scene === null) {
    return null
  }
  return scene.shots.find((shot) => shot.id === selectedShotId.value) ?? null
})

/** Число кадров видеофайла: им ограничивается ввод номера кадра. */
/** Планы выбранной сцены — для левой панели. */
const shotsOfSelection = computed(() => selected.value?.shots ?? [])

const frameCount = computed(() => store.row.value?.frameCount ?? 0)

/** Номер кадра для крупного изображения. */
const fullFrame = ref<number | null>(null)

/** Есть ли кадр для крупного изображения. */
const fullFrameUrl = computed(() =>
  fullFrame.value === null
    ? ''
    : `/api/videofiles/${videofileId.value}/frames/${fullFrame.value}/image?width=720`,
)

/** Открытый лист превью приведённый к строке экрана. */
const sheetFrame = computed(() => store.sheetFrameRow(videofileId.value))

/** Есть ли следующая страница сцен. */
const hasNextPage = computed(() => store.row.value?.hasNextPage === true)

/** Есть ли предыдущая страница сцен. */
const hasPreviousPage = computed(() => (store.row.value?.offset ?? 0) > 0)

/** Сколько сцен помечено устаревшим на странице. */
const staleOnPage = computed(
  () => store.row.value?.scenes.filter((scene) => scene.isStale).length ?? 0,
)

/**
 * Перечитывает структуру и открывает первый лист превью.
 *
 * Открытие листа здесь, а не по кнопке: владелец после разбора должен увидеть
 * результат, а не пустой экран с одним заголовком.
 */
function reloadAll(): void {
  void store.reload(videofileId.value)
  void store.openFirstSheet(videofileId.value)
}

/**
 * Переходит к кадру: открывает его лист и выбирает сцену.
 */
function goToFrame(): void {
  if (frameInput.value !== null) {
    void store.goToFrame(videofileId.value, frameInput.value)
  }
}

/**
 * Обрабатывает выбор кадра на листе превью.
 *
 * @param frame номер кадра
 */
function pickFrame(frame: number): void {
  frameInput.value = frame
  void store.goToFrame(videofileId.value, frame)
}

/**
 * Выбирает сцену и открывает её планы.
 *
 * @param sceneId идентификатор сцены
 */
function selectScene(sceneId: number): void {
  void store.selectScene(videofileId.value, sceneId)
}

/**
 * Выполняет правку границы и записывает, чем она закончилась.
 *
 * @param operation операция правки
 * @param description что именно нажал оператор
 */
async function applyEdit(operation: () => Promise<boolean>, description: string): Promise<void> {
  const done = await operation()
  editNotice.value = done
    ? `${description}: ${store.lastEdit.value?.title ?? 'структура изменена'}`
    : `${description}: не выполнено`
}

/**
 * Выполняет правку границы плана и показывает, что именно изменилось.
 *
 * В подпись входит число пересчитанных размеров: размер плана меняется
 * вместе с границей, и без этого числа правка выглядит как «ничего не
 * сделал», хотя размер стал другим.
 *
 * @param operation операция правки
 * @param description что делала операция, словами
 */
async function applyShotEdit(
  operation: () => Promise<boolean>,
  description: string,
): Promise<void> {
  const done = await operation()
  if (!done) {
    editNotice.value = `${description}: не выполнено`
    return
  }
  const sizes = store.lastShotEdit.value?.sizesRecomputed ?? 0
  editNotice.value =
    `${description}: ${store.lastShotEdit.value?.title ?? 'структура изменена'}` +
    (sizes > 0 ? `; размер пересчитан у ${sizes} планов` : '')
}

/**
 * Листает превью по клавишам.
 *
 * Влево и вправо — обязательны: триста сорок семь листов мышью листать
 * невозможно. Обработчик снимается при уходе с экрана, иначе стрелки продолжали
 * бы листать превью на другом экране.
 *
 * @param event клавиатурное событие
 */
function onKeydown(event: KeyboardEvent): void {
  const target = event.target
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
    return
  }
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    void store.stepSheet(videofileId.value, -1)
  }
  if (event.key === 'ArrowRight') {
    event.preventDefault()
    void store.stepSheet(videofileId.value, 1)
  }
}

/** Номер изменения структуры: по нему экран перечитывает данные сам. */
const revision = computed(() => analysisRevision(videofileId.value))

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  reloadAll()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
})

watch(videofileId, () => {
  reloadAll()
})

watch(revision, () => {
  reloadAll()
})
</script>

<template>
  <section class="structure">
    <header class="syp-page-head">
      <div>
        <h1 class="syp-page-title">Videofile structure</h1>
        <p class="syp-page-lead">
          The scenes and shots found by the analysis, and the preview sheets that show where in the
          videofile each of them happens. A scene boundary can be moved, a scene can be split or
          merged with a neighbour; the edit is saved at once.
        </p>
      </div>
      <div class="head-actions">
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary"
          :disabled="store.loading.value"
          @click="reloadAll"
        >
          refresh
        </button>
        <button
          type="button"
          class="btn btn-sm btn-primary"
          :disabled="store.loading.value"
          @click="store.analyse(videofileId)"
        >
          Analyse the videofile again
        </button>
      </div>
    </header>

    <StateBlock
      :loading="store.loading.value && store.structure.value === null"
      :error="store.error.value"
      :error-code="store.errorCode.value"
      :empty-title="store.row.value === null ? '' : ''"
      empty-text=""
      @dismiss="store.clearError()"
    />

    <p v-if="store.row.value" class="counters">
      {{ store.row.value.summary }}.
      <span v-if="store.row.value.algorithmVersion !== '—'">
        Algorithm version: {{ store.row.value.algorithmVersion }}.
      </span>
    </p>

    <p v-if="store.isStale.value" class="syp-stale">
      <span class="syp-mono">{{ store.staleCode }}</span>
      {{ store.row.value?.staleReason }}
      — previous manual edits are kept, the operator starts the recomputation.
    </p>

    <p v-if="editNotice" class="notice" role="status">{{ editNotice }}</p>

    <div class="legacy-window">
      <div class="card column-left">
        <div class="card-body">
          <div class="syp-card-title">Shots</div>
          <div class="legacy-shots">
            <span v-for="shot in shotsOfSelection" :key="shot.id" class="legacy-shot">
              {{ shot.firstFrame }}…{{ shot.lastFrame }}
            </span>
            <span v-if="shotsOfSelection.length === 0" class="syp-unit">
              Choose a scene — its shots will be shown
            </span>
          </div>
          <div class="syp-card-title">Frame</div>
          <img
            v-if="fullFrameUrl"
            class="legacy-full-frame"
            :src="fullFrameUrl"
            alt="Whole frame"
            width="720"
            height="400"
          />
          <p v-else class="syp-unit">No frame selected</p>
        </div>
      </div>

      <div class="card column-main">
        <div class="card-body">
          <div class="syp-card-title">Scenes</div>

          <nav class="pager">
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="!hasPreviousPage"
              @click="store.previousPage(videofileId)"
            >
              previous
            </button>
            <span class="syp-unit">
              Scenes from {{ store.row.value?.visibleFrom ?? 0 }} to
              {{ store.row.value?.visibleTo ?? 0 }} of {{ store.row.value?.scenesTotal ?? 0 }} are
              shown. Numbers are counted over the whole list.
            </span>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="!hasNextPage"
              @click="store.nextPage(videofileId)"
            >
              next
            </button>
            <button
              type="button"
              class="btn btn-sm btn-link"
              :disabled="store.loading.value"
              @click="store.toggleStale()"
            >
              {{
                store.staleVisible.value
                  ? 'hide stale scenes'
                  : `show stale scenes (${staleOnPage})`
              }}
            </button>
          </nav>

          <div class="table-responsive">
            <table class="table table-sm align-middle scenes">
              <thead>
                <tr>
                  <th>№</th>
                  <th>Frames</th>
                  <th>Title</th>
                  <th>Origin</th>
                  <th>Location</th>
                  <th>Shots</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="scene in scenes"
                  :key="scene.id"
                  class="scene-row"
                  :class="{ selected: scene.id === selected?.id }"
                  tabindex="0"
                  @click="selectScene(scene.id)"
                  @keydown.enter="selectScene(scene.id)"
                >
                  <td class="syp-number">{{ scene.number }}</td>
                  <td class="syp-number">
                    {{ scene.frames }}
                    <span v-if="scene.isStale" class="text-bg-warning syp-origin">stale</span>
                  </td>
                  <td>{{ scene.title || '—' }}</td>
                  <td>
                    <span :class="['syp-origin', `syp-origin-${scene.originClass}`]">
                      {{ scene.originTitle }}
                    </span>
                  </td>
                  <td>{{ scene.location }}</td>
                  <td class="syp-number">{{ scene.shots.length }}</td>
                </tr>
                <tr v-if="scenes.length === 0">
                  <td colspan="6" class="syp-empty">
                    <div class="syp-empty-title">No scenes on this page</div>
                    <div>Go to another page or turn on showing stale scenes.</div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="selected" class="card-body selected-scene">
          <PropertyEditor kind="SCENE" :owner-id="selected.id" title="Scene properties" />
          <SceneDetailPanel
            :scene="selected"
            :thumbs="store.thumbs.value"
            :frame-count="frameCount"
            :busy="store.editing.value"
            @pick="pickFrame"
            @select="(shotId: number) => (selectedShotId = shotId)"
            @split="
              (frame: number) =>
                applyEdit(() => store.split(videofileId, frame), `Splitting at frame ${frame}`)
            "
            @merge="
              (frame: number) =>
                applyEdit(() => store.merge(videofileId, frame), `Merging from frame ${frame}`)
            "
            @move="
              (from: number, to: number) =>
                applyEdit(
                  () => store.moveBoundary(videofileId, from, to),
                  `Moving the boundary from ${from} to ${to}`,
                )
            "
          />
        </div>

        <div v-if="selectedShot" class="card-body selected-scene">
          <ShotDetailPanel
            :shot="selectedShot"
            :neighbours="selected?.shots ?? []"
            :frame-count="frameCount"
            :busy="store.editing.value"
            @split="
              (frame: number) =>
                applyShotEdit(
                  () => store.splitShotAt(videofileId, frame),
                  `Splitting the shot at frame ${frame}`,
                )
            "
            @merge="
              (frame: number) =>
                applyShotEdit(
                  () => store.mergeShotAt(videofileId, frame),
                  `Merging the shot from frame ${frame}`,
                )
            "
            @move="
              (from: number, to: number) =>
                applyShotEdit(
                  () => store.moveShotEdge(videofileId, from, to),
                  `Moving the shot boundary from ${from} to ${to}`,
                )
            "
          />
        </div>
      </div>

      <div class="card column-sheet">
        <div class="card-body">
          <div class="syp-card-title">Preview sheet</div>
          <p v-if="store.sheetNav.value" class="syp-unit">
            {{ store.sheetNav.value.position }}. {{ store.sheetNav.value.frames }}.
            <template v-if="store.sheetNav.value.isReady">
              Size: {{ store.sheetNav.value.byteSize }}.
            </template>
            <template v-else> The sheet is not ready yet.</template>
          </p>

          <div class="sheet-nav">
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="store.loading.value || !store.sheetNav.value?.hasPrevious"
              @click="store.stepSheet(videofileId, -1)"
            >
              previous sheet
            </button>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="store.loading.value || !store.sheetNav.value?.hasNext"
              @click="store.stepSheet(videofileId, 1)"
            >
              next sheet
            </button>
          </div>

          <form class="jump" @submit.prevent="goToFrame">
            <label class="form-label" for="frame-jump">Go to frame</label>
            <div class="jump-row">
              <input
                id="frame-jump"
                v-model.number="frameInput"
                type="number"
                class="form-control form-control-sm syp-number"
                min="0"
              />
              <button type="submit" class="btn btn-sm btn-primary" :disabled="store.loading.value">
                go to
              </button>
            </div>
            <div class="form-text">
              Клавиши ← и → листают превью. Клик по ячейке листа показывает сцену этого кадра.
            </div>
          </form>

          <PreviewSheetView v-if="sheetFrame" :sheet="sheetFrame" @pick="pickFrame" />
        </div>
      </div>
    </div>

    <section v-if="store.rawVisible.value" class="card">
      <div class="card-body">
        <div class="syp-card-title">Raw automation result</div>
        <p class="syp-unit">
          The boundaries the algorithm produced, to ручных правок. Рабочая структура выше — то, что
          осталось после правок; сравнивать их нужно рядом (FR-093).
        </p>
        <p v-if="store.raw.value" class="syp-unit">
          Прогон №{{ store.raw.value.runId }}, границ всего: {{ store.raw.value.total }}, показано:
          {{ store.rawRows.value.length }}.
        </p>
        <div class="table-responsive">
          <table class="table table-sm">
            <thead>
              <tr>
                <th>Level</th>
                <th>Frames</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(boundary, position) in store.rawRows.value" :key="position">
                <td>{{ boundary.level }}</td>
                <td class="syp-number">{{ boundary.frames }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="store.toggleRaw()">
          hide the raw result
        </button>
      </div>
    </section>
  </section>
</template>

<style scoped>
.structure {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.head-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.counters,
.notice {
  color: var(--syp-text-muted);
  font-size: 0.875rem;
  margin: 0;
}

.notice {
  color: var(--syp-success);
}

/* Раскладка повторяет окно «Редактор планов» старого проекта: слева узкая
   панель с планами и крупным кадром, справа широкая рабочая часть. */
.legacy-window {
  align-items: start;
  display: grid;
  gap: 1rem;
  grid-template-columns: minmax(320px, 730px) minmax(0, 1fr);
}

.legacy-shots {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-bottom: 0.75rem;
}

.legacy-shot {
  background: rgba(255, 255, 255, 0.06);
  border-radius: 3px;
  font-family: var(--syp-mono, monospace);
  font-size: 0.75rem;
  padding: 0.1rem 0.35rem;
}

.legacy-full-frame {
  background: var(--syp-bg);
  border: 1px solid rgba(255, 255, 255, 0.12);
  display: block;
  height: auto;
  max-width: 100%;
  width: 720px;
}

.column-left,
.column-main {
  min-width: 0;
}

.column-main {
  flex: 3 1 34rem;
}

.column-sheet {
  flex: 1 1 22rem;
  position: sticky;
  top: 1rem;
}

.pager {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.scenes {
  cursor: pointer;
}

.scene-row.selected td {
  background-color: var(--syp-raised);
}

.selected-scene {
  border-top: 1px solid var(--syp-border);
}

.sheet-nav {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.75rem;
}

.jump {
  margin-bottom: 0.75rem;
  max-width: 18rem;
}

.jump-row {
  display: flex;
  gap: 0.5rem;
}
</style>
