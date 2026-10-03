// Карточка плана: его границы, размер и доводка его границы. // // Отдельный компонент по той же
причине, что и карточка сцены: оператор // работает с двумя разными вещами — посмотреть, что система
нашла в плане // (границы, размер по шкале, рамка первого кадра), и изменить, где план // начинается
и кончается. Смешивать их в одной строке таблицы нельзя — правка // границы требует решения, а не
беглого взгляда. // // Размер плана показан ступенью шкалы и словами рядом: шкала десятиступенчатая
// и сверена с эталонной старого проекта (ADR-0003), а решение принимает // человек. Одно `MCU` без
расшифровки заставляет держать шкалу в голове. // // Граница плана не встаёт так, чтобы сцена начала
с середины плана: сцена // обязана начинаться планом (ADR-0007). Поэтому рядом с полем ввода стоят
// кнопки всех доступных положений, а отказ сервера показывается текстом // целиком — он объясняет,
между какими планами граница должна встать.

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { ShotRow } from '../api/view-model'

const props = defineProps<{
  /** Выбранный план. */
  shot: ShotRow
  /** Число кадров видеофайла: им ограничивается ввод номера кадра. */
  frameCount: number
  /** Идёт ли правка: на время операции поле недоступно. */
  busy: boolean
  /** Планы соседней сцены: по ним считаются доступные положения границы. */
  neighbours: ShotRow[]
}>()

const emit = defineEmits<{
  split: [frame: number]
  merge: [frame: number]
  move: [fromFrame: number, toFrame: number]
}>()

/** Номер кадра, на который оператор ставит границу. */
const target = ref<number | null>(null)

watch(
  () => props.shot.id,
  () => {
    target.value = null
  },
)

/**
 * Кадры, на которых граница может стоять.
 *
 * Список derived из планов сцены, а не задан константой: он обязан совпадать
 * с тем, что действительно может принять сервер, иначе кнопки предлагали бы
 * отказ. Внутри плана границу не ставят — для этого есть «разделить план
 * здесь», и она стоит отдельно.
 *
 * Верхняя граница собственного плана исключена: сдвиг туда оставил бы план
 * без собственных кадров.
 */
const candidates = computed(() => {
  const frames = new Set<number>()
  for (const shot of props.neighbours) {
    if (shot.firstFrame > props.shot.firstFrame && shot.firstFrame <= props.shot.lastFrame) {
      frames.add(shot.firstFrame)
    }
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
 * Разделяет план по выбранному кадру.
 */
function splitHere(): void {
  const frame = targetFrame()
  if (frame !== null) {
    emit('split', frame)
  }
}

/**
 * Сдвигает начало плана на выбранный кадр.
 */
function moveStart(): void {
  const frame = targetFrame()
  if (frame !== null) {
    emit('move', props.shot.firstFrame, frame)
  }
}

/**
 * Сдвигает конец плана на выбранный кадр: граница после него стоит на кадре
 * `lastFrame + 1`, и именно её называет сервер.
 */
function moveEnd(): void {
  const frame = targetFrame()
  if (frame !== null) {
    emit('move', props.shot.lastFrame + 1, frame)
  }
}

/**
 * Объединяет план с предыдущим.
 */
function mergeWithPrevious(): void {
  emit('merge', props.shot.firstFrame)
}
</script>

<template>
  <div class="shot-detail">
    <div class="syp-card-title">
      План: кадры {{ shot.frames }}
      <span v-if="shot.isStale" class="text-bg-warning syp-origin">устарел</span>
    </div>
    <p class="text-body-secondary small mb-2">
      Размер: <span class="syp-mono">{{ shot.size }}</span> — {{ shot.sizeTitle }};
      {{ shot.sizeOriginTitle }}.
    </p>

    <div class="boundary">
      <div class="syp-card-title">Доводка границы</div>
      <p class="text-body-secondary small">
        Граница плана не может встать так, чтобы сцена начала с середины плана: сцена обязана
        начинаться планом. Внутри плана границу не ставят — план разделяют. Правка сохраняется
        сразу, отдельной кнопки «сохранить» нет.
      </p>

      <div class="boundary-form">
        <label class="form-label" for="shot-boundary-target">Новый кадр границы</label>
        <input
          id="shot-boundary-target"
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
            разделить план здесь
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy || target === null || shot.firstFrame === 0"
            @click="moveStart"
          >
            сдвинуть начало
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy || target === null"
            @click="moveEnd"
          >
            сдвинуть конец
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="busy || shot.firstFrame === 0"
            @click="mergeWithPrevious"
          >
            объединить с предыдущим
          </button>
        </div>
      </div>

      <div v-if="candidates.length > 0" class="candidates">
        <span class="syp-unit">Границы планов сцены внутри этого плана:</span>
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
        Внутри плана границ планов нет: сдвигать некуда, остаётся разделение и объединение.
      </p>
    </div>
  </div>
</template>

<style scoped>
.shot-detail {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
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
