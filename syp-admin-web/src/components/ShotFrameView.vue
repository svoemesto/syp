<script setup lang="ts">
// Крупный кадр выбранного плана с рамками лиц.
//
// В старой форме это метка `lblFrameFull` размером 720×400 в левой части
// окна: она показывала кадр с наложенными прямоугольниками лиц и по правому
// щелчку открывала правку лиц кадра. Здесь то же, но без контекстного меню:
// правка вынесена в отдельную форму, а на кадре она открывается кнопкой.

import { computed, onMounted, ref, watch } from 'vue'
import { readFaces, type FaceView } from '../api/characters'
import { frameImageUrl, type ShotView } from '../api/structure'
import FrameFacesDialog from './FrameFacesDialog.vue'

const props = defineProps<{
  videofileId: number
  shot: ShotView | null
}>()

const emit = defineEmits<{ editing: [frameNumber: number] }>()

/** Лица, попавшие в выбранный план. */
const faces = ref<FaceView[]>([])

/**
 * Ширина кадра полного разрешения.
 *
 * Берётся у сервера вместе с лицами, а не зашивается: у другого видеофайла
 * кадр может быть другим, и рамки лиц тогда уехали бы.
 */
const frameWidth = ref(0)

/** Высота кадра полного разрешения. */
const frameHeight = ref(0)

/** Ошибка чтения. */
const error = ref('')

/** Открыта ли форма правки лиц кадра. */
const editing = ref(false)

/**
 * Кадр для показа: средний кадр плана.
 *
 * Средний, а не первый: на первом кадре плана лиц обычно ещё нет — план
 * начинается с пустоты, и оператор видел бы метку без рамок.
 */
const frame = computed(() =>
  props.shot === null
    ? null
    : Math.round((props.shot.firstFrame + props.shot.lastFrame) / 2),
)

/** Адрес кадра. */
const source = computed(() =>
  frame.value === null ? '' : frameImageUrl(props.videofileId, frame.value, 720),
)

/**
 * Рамки лиц, посчитанные в процентах ширины и высоты.
 *
 * Рамки приходят в пикселях кадра полного разрешения, а показываются на
 * уменьшенной копии, поэтому пересчитываются в доли: иначе при ширине 720
 * рамка 400 пикселей полного кадра уехала бы за край.
 */
const boxes = computed(() =>
  faces.value.map((face) => ({
    id: face.id,
    left: (face.x1 / Math.max(frameWidth.value, 1)) * 100,
    top: (face.y1 / Math.max(frameHeight.value, 1)) * 100,
    width: ((face.x2 - face.x1) / Math.max(frameWidth.value, 1)) * 100,
    height: ((face.y2 - face.y1) / Math.max(frameHeight.value, 1)) * 100,
  })),
)

/**
 * Правка лиц кадра сохранена.
 *
 * В шаблоне несколько операторов и приведение типа не разбираются, поэтому
 * обработчик назван здесь.
 */
function onSaved(): void {
  void reload()
  if (frame.value !== null) {
    emit('editing', frame.value)
  }
}

/** Читает лица выбранного плана. */
async function reload(): Promise<void> {
  if (props.shot === null) {
    faces.value = []
    return
  }
  try {
    const read = await readFaces(props.videofileId, 0, 200)
    frameWidth.value = read.frameWidth
    frameHeight.value = read.frameHeight
    faces.value = read.faces.filter(
      (face) =>
        face.frameNumber >= props.shot!.firstFrame && face.frameNumber <= props.shot!.lastFrame,
    )
    error.value = ''
  } catch (failure) {
    faces.value = []
    error.value = (failure as Error).message
  }
}

watch(() => props.shot?.id, reload, { immediate: true })
onMounted(reload)
</script>

<template>
  <div class="frame-view">
    <div class="frame-box">
      <img v-if="source !== ''" :src="source" alt="Кадр выбранного плана" />
      <p v-else class="empty">План не выбран</p>
      <span
        v-for="box in boxes"
        :key="box.id"
        class="box"
        :style="{ left: `${box.left}%`, top: `${box.top}%`, width: `${box.width}%`, height: `${box.height}%` }"
      />
    </div>
    <div class="frame-actions">
      <span class="caption">
        Кадр {{ frame ?? '—' }}; лиц в плане: {{ faces.length }}
      </span>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        :disabled="frame === null"
        @click="editing = true"
      >
        Править лица кадра
      </button>
    </div>
    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <FrameFacesDialog
      v-if="editing && frame !== null"
      :videofile-id="videofileId"
      :frame-number="frame"
      @closed="editing = false"
      @saved="onSaved"
    />
  </div>
</template>

<style scoped>
.frame-box {
  position: relative;
  width: 720px;
  max-width: 100%;
  aspect-ratio: 16 / 9;
  background: var(--syp-bg);
  border: 1px solid var(--syp-border);
  border-radius: 0.25rem;
  overflow: hidden;
}
.frame-box img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.box {
  position: absolute;
  border: 2px solid var(--syp-warning);
  border-radius: 0.1rem;
  pointer-events: none;
}
.empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--syp-text-muted);
  font-size: 0.8125rem;
}
.frame-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.25rem;
}
.caption {
  font-size: 0.75rem;
  color: var(--syp-text-muted);
  font-variant-numeric: tabular-nums;
}
</style>
