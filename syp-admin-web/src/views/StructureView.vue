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
        <h1 class="syp-page-title">Структура видеофайла</h1>
        <p class="syp-page-lead">
          Сцены и планы, найденные разбором, и листы превью, по которым видно, где в видеофайле
          каждая из них. Границу сцены можно сдвинуть, сцену — разделить или объединить с соседней;
          правка сохраняется сразу.
        </p>
      </div>
      <div class="head-actions">
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary"
          :disabled="store.loading.value"
          @click="reloadAll"
        >
          обновить
        </button>
        <button
          type="button"
          class="btn btn-sm btn-primary"
          :disabled="store.loading.value"
          @click="store.analyse(videofileId)"
        >
          Разобрать видеофайл заново
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
        Версия алгоритма: {{ store.row.value.algorithmVersion }}.
      </span>
    </p>

    <p v-if="store.isStale.value" class="syp-stale">
      <span class="syp-mono">{{ store.staleCode }}</span>
      {{ store.row.value?.staleReason }}
      — прежние ручные правки сохранены, пересчёт запускает оператор.
    </p>

    <p v-if="editNotice" class="notice" role="status">{{ editNotice }}</p>

    <div class="legacy-window">
      <div class="card column-left">
        <div class="card-body">
          <div class="syp-card-title">Планы</div>
          <div class="legacy-shots">
            <span v-for="shot in shotsOfSelection" :key="shot.id" class="legacy-shot">
              {{ shot.firstFrame }}…{{ shot.lastFrame }}
            </span>
            <span v-if="shotsOfSelection.length === 0" class="syp-unit">
              Выберите сцену — покажем её планы
            </span>
          </div>
          <div class="syp-card-title">Кадр</div>
          <img
            v-if="fullFrameUrl"
            class="legacy-full-frame"
            :src="fullFrameUrl"
            alt="Кадр целиком"
            width="720"
            height="400"
          />
          <p v-else class="syp-unit">Кадр не выбран</p>
        </div>
      </div>

      <div class="card column-main">
        <div class="card-body">
          <div class="syp-card-title">Сцены</div>

          <nav class="pager">
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="!hasPreviousPage"
              @click="store.previousPage(videofileId)"
            >
              предыдущие
            </button>
            <span class="syp-unit">
              Показаны сцены с {{ store.row.value?.visibleFrom ?? 0 }} по
              {{ store.row.value?.visibleTo ?? 0 }} из {{ store.row.value?.scenesTotal ?? 0 }}.
              Номера считаются по полному списку.
            </span>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="!hasNextPage"
              @click="store.nextPage(videofileId)"
            >
              следующие
            </button>
            <button
              type="button"
              class="btn btn-sm btn-link"
              :disabled="store.loading.value"
              @click="store.toggleStale()"
            >
              {{
                store.staleVisible.value
                  ? 'скрыть устаревшие сцены'
                  : `показать устаревшие сцены (${staleOnPage})`
              }}
            </button>
          </nav>

          <div class="table-responsive">
            <table class="table table-sm align-middle scenes">
              <thead>
                <tr>
                  <th>№</th>
                  <th>Кадры</th>
                  <th>Название</th>
                  <th>Происхождение</th>
                  <th>Место действия</th>
                  <th>Планов</th>
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
                    <span v-if="scene.isStale" class="text-bg-warning syp-origin">устарела</span>
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
                    <div class="syp-empty-title">На этой странице сцен нет</div>
                    <div>Перейдите на другую страницу или включите показ устаревших сцен.</div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div v-if="selected" class="card-body selected-scene">
          <PropertyEditor kind="SCENE" :owner-id="selected.id" title="Свойства сцены" />
          <SceneDetailPanel
            :scene="selected"
            :thumbs="store.thumbs.value"
            :frame-count="frameCount"
            :busy="store.editing.value"
            @pick="pickFrame"
            @select="(shotId: number) => (selectedShotId = shotId)"
            @split="
              (frame: number) =>
                applyEdit(() => store.split(videofileId, frame), `Разделение на кадре ${frame}`)
            "
            @merge="
              (frame: number) =>
                applyEdit(() => store.merge(videofileId, frame), `Объединение с кадра ${frame}`)
            "
            @move="
              (from: number, to: number) =>
                applyEdit(
                  () => store.moveBoundary(videofileId, from, to),
                  `Сдвиг границы с ${from} на ${to}`,
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
                  `Разделение плана на кадре ${frame}`,
                )
            "
            @merge="
              (frame: number) =>
                applyShotEdit(
                  () => store.mergeShotAt(videofileId, frame),
                  `Объединение плана с кадра ${frame}`,
                )
            "
            @move="
              (from: number, to: number) =>
                applyShotEdit(
                  () => store.moveShotEdge(videofileId, from, to),
                  `Сдвиг границы плана с ${from} на ${to}`,
                )
            "
          />
        </div>
      </div>

      <div class="card column-sheet">
        <div class="card-body">
          <div class="syp-card-title">Лист превью</div>
          <p v-if="store.sheetNav.value" class="syp-unit">
            {{ store.sheetNav.value.position }}. {{ store.sheetNav.value.frames }}.
            <template v-if="store.sheetNav.value.isReady">
              Размер: {{ store.sheetNav.value.byteSize }}.
            </template>
            <template v-else> Лист ещё не готов.</template>
          </p>

          <div class="sheet-nav">
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="store.loading.value || !store.sheetNav.value?.hasPrevious"
              @click="store.stepSheet(videofileId, -1)"
            >
              предыдущий лист
            </button>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="store.loading.value || !store.sheetNav.value?.hasNext"
              @click="store.stepSheet(videofileId, 1)"
            >
              следующий лист
            </button>
          </div>

          <form class="jump" @submit.prevent="goToFrame">
            <label class="form-label" for="frame-jump">Перейти к кадру</label>
            <div class="jump-row">
              <input
                id="frame-jump"
                v-model.number="frameInput"
                type="number"
                class="form-control form-control-sm syp-number"
                min="0"
              />
              <button type="submit" class="btn btn-sm btn-primary" :disabled="store.loading.value">
                перейти
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
        <div class="syp-card-title">Сырой результат автоматики</div>
        <p class="syp-unit">
          Границы, которые выдал алгоритм, до ручных правок. Рабочая структура выше — то, что
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
                <th>Уровень</th>
                <th>Кадры</th>
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
          скрыть сырой результат
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
