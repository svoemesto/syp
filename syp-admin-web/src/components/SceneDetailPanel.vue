// Выбранная сцена: её планы и доводка её границ. // // Отдельный компонент потому, что оператор
работает с двумя разными вещами: // посмотреть, что система нашла в сцене (границы планов, размеры,
рамки // первых кадров), и изменить, где сцена начинается и кончается. Смешивать их // в одной
таблице нельзя — правка границы требует решения, а не беглого // взгляда. // // Размер плана показан
ступенью шкалы и словами рядом: шкала десятиступенчатая // и сверена с эталонной старого проекта
(ADR-0003), а решение принимает // человек. Одно `MCU` без расшифровки заставляет держать шкалу в
голове. // // Границу сцены можно поставить только на границу плана — иначе сцена // разрежет план,
а план лежит в сцене целиком (ADR-0007). Поэтому рядом с // полем ввода стоят кнопки всех доступных
положений: набирать номер кадра // руками оператор не станет, а перебирать триста сорок семь листов,
чтобы его // найти, — тем более.

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { SceneRow, ShotThumbRow } from '../api/view-model'

const props = defineProps<{
  /** Выбранная сцена. */
  scene: SceneRow
  /** Рамки первых кадров планов, по номеру кадра. */
  thumbs: Record<number, ShotThumbRow>
  /** Число кадров видеофайла: им проверяется, есть ли следующая сцена. */
  frameCount: number
  /** Идёт ли правка: на время операции поле недоступно. */
  busy: boolean
  /** Идентификатор выбранного плана: его строка подсвечивается. */
  selectedShotId?: number
}>()

const emit = defineEmits<{
  pick: [frame: number]
  select: [shotId: number]
  split: [frame: number]
  merge: [frame: number]
  move: [fromFrame: number, toFrame: number]
}>()

/** Номер кадра, на который оператор ставит границу. */
const target = ref<number | null>(null)

watch(
  () => props.scene.id,
  () => {
    target.value = null
  },
)

/**
 * Кадры, на которых граница может стоять: начала планов внутри сцены и её
 * конец.
 *
 * Список derived из планов сцены, а не задан константой: он обязан совпадать
 * с тем, что действительно может принять сервер, иначе кнопки предлагали бы
 * отказ.
 */
const candidates = computed(() => {
  const frames = new Set<number>()
  for (const shot of props.scene.shots) {
    if (shot.firstFrame > props.scene.firstFrame) {
      frames.add(shot.firstFrame)
    }
  }
  if (props.scene.lastFrame < props.frameCount - 1) {
    frames.add(props.scene.lastFrame + 1)
  }
  return [...frames].sort((a, b) => a - b)
})

/**
 * Кадр, на который встанет граница по нажатой кнопке.
 *
 * @returns номер кадра либо `null`, если поле пустое
 */
function targetFrame(): number | null {
  return target.value
}

/**
 * Ставит границу в поле ввода.
 *
 * @param frame номер кадра
 */
function choose(frame: number): void {
  target.value = frame
}

/**
 * Разделяет сцену по выбранному кадру.
 */
function splitHere(): void {
  const frame = targetFrame()
  if (frame !== null) {
    emit('split', frame)
  }
}

/**
 * Сдвигает начало сцены на выбранный кадр.
 */
function moveStart(): void {
  const frame = targetFrame()
  if (frame !== null) {
    emit('move', props.scene.firstFrame, frame)
  }
}

/**
 * Сдвигает конец сцены на выбранный кадр: граница после неё стоит на кадре
 * `lastFrame + 1`, и именно её называет сервер.
 */
function moveEnd(): void {
  const frame = targetFrame()
  if (frame !== null) {
    emit('move', props.scene.lastFrame + 1, frame)
  }
}

/**
 * Объединяет сцену с предыдущей.
 */
function mergeWithPrevious(): void {
  emit('merge', props.scene.firstFrame)
}

/**
 * Рамка первого кадра плана: адрес листа и смещение внутри него.
 *
 * Рамка рисуется куском уже скачанного листа превью, а не отдельной
 * картинкой: кадры в исходном разрешении в системе нет, а лист превью браузер
 * кэширует один раз на весь экран.
 *
 * @param shot план экрана
 * @returns рамка экрана либо `undefined`, если она не загружена
 */
function thumbOf(shot: { firstFrame: number }): ShotThumbRow | undefined {
  return props.thumbs[shot.firstFrame]
}
</script>

<template>
  <div class="scene-detail">
    <div class="syp-card-title">
      Scene no. {{ scene.number }}: frames {{ scene.frames }}
      <span v-if="scene.title" class="scene-title">— {{ scene.title }}</span>
    </div>
    <p class="text-body-secondary small mb-2">
      Location: {{ scene.location }}. Shots: {{ scene.shotCount }}.
    </p>

    <div class="table-responsive">
      <table class="table table-sm align-middle shots">
        <thead>
          <tr>
            <th>Frame</th>
            <th>Boundaries</th>
            <th>Size</th>
            <th>Origin</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="shot in scene.shots"
            :key="shot.id"
            class="shot-row"
            :class="{ selected: shot.id === selectedShotId }"
            tabindex="0"
            @click="emit('select', shot.id)"
            @keydown.enter="emit('select', shot.id)"
          >
            <td>
              <button
                v-if="thumbOf(shot) !== undefined"
                type="button"
                class="thumb"
                :style="{
                  width: thumbOf(shot)?.width,
                  height: thumbOf(shot)?.height,
                  backgroundImage: `url(${thumbOf(shot)?.url})`,
                  backgroundSize: thumbOf(shot)?.backgroundSize,
                  backgroundPosition: thumbOf(shot)?.backgroundPosition,
                }"
                :title="`Show frame ${shot.firstFrame}`"
                :aria-label="`Show frame ${shot.firstFrame}`"
                @click="emit('pick', shot.firstFrame)"
              />
              <span v-else class="syp-unit">preview is not loaded</span>
            </td>
            <td class="syp-number">
              {{ shot.frames }}
              <span v-if="shot.isStale" class="text-bg-warning syp-origin">stale</span>
            </td>
            <td>
              <span class="syp-mono">{{ shot.size }}</span>
              <div class="syp-unit">{{ shot.sizeTitle }}</div>
              <div class="syp-unit">{{ shot.sizeOriginTitle }}</div>
            </td>
            <td>
              <span :class="['syp-origin', `syp-origin-${shot.originClass}`]">
                {{ shot.originTitle }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="boundary">
      <div class="syp-card-title">Boundary adjustment</div>
      <p class="text-body-secondary small">
        A scene boundary only lands on a shot boundary: a scene that cut a shot would leave frames
        without a scene in the database. The edit is saved at once, there is no separate save
        button.
      </p>

      <div class="boundary-form">
        <label class="form-label" for="boundary-target">New boundary frame</label>
        <input
          id="boundary-target"
          v-model.number="target"
          type="number"
          class="form-control form-control-sm syp-number"
          min="0"
          :max="frameCount - 1"
          :disabled="busy"
        />
        <div class="boundary-buttons">
          <button
            type="button"
            class="btn btn-sm btn-primary"
            :disabled="busy || target === null"
            @click="splitHere"
          >
            split the scene here
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy || target === null || scene.firstFrame === 0"
            @click="moveStart"
          >
            move the start
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy || target === null || scene.lastFrame >= frameCount - 1"
            @click="moveEnd"
          >
            move the end
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy || scene.firstFrame === 0"
            @click="mergeWithPrevious"
          >
            merge with the previous one
          </button>
        </div>
      </div>

      <div v-if="candidates.length > 0" class="candidates">
        <span class="syp-unit">Shot boundaries of the scene:</span>
        <button
          v-for="frame in candidates"
          :key="frame"
          type="button"
          class="btn btn-sm btn-link"
          :disabled="busy"
          @click="choose(frame)"
        >
          {{ frame }}
        </button>
      </div>
      <p v-else class="syp-unit">
        The scene has no inner shot boundaries: there is nothing to split it by, only the end can be
        moved.
      </p>
    </div>
  </div>
</template>

<style scoped>
.scene-detail {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.scene-title {
  color: var(--syp-text);
}

.shots {
  width: auto;
  min-width: 32rem;
}

.shot-row.selected > td {
  background-color: var(--syp-raised);
}

.thumb {
  background-repeat: no-repeat;
  border: 1px solid var(--syp-border-control);
  cursor: pointer;
  display: block;
  padding: 0;
}

.thumb:hover {
  border-color: var(--syp-link);
}

.boundary {
  border-top: 1px solid var(--syp-border);
  padding-top: 0.75rem;
}

.boundary-form {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  max-width: 24rem;
}

.boundary-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.candidates {
  align-items: baseline;
  display: flex;
  flex-wrap: wrap;
  gap: 0.15rem;
}
</style>
