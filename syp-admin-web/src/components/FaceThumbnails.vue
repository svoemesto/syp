// Миниатюры лиц. // // Отдельный компонент потому, что список лиц нужен и на экране лиц, и позже //
в редакторе: правила показа не должно приходиться повторять в двух местах. // // Миниатюра лица
показывается **кадром, а не отдельным файлом**: кадров в // исходном разрешении система не хранит
(FR-024), а уменьшённые превью всех // кадров уже лежат на листах. Отдельная картинка на каждое лицо
означала бы // ещё около 17,5 ГБ данных, которые ничего не добавляют оператору (Q10). // // Рамка
лица рисуется поверх кадра: координаты приходят в пикселях кадра // полного разрешения, а картинка —
135×75. Масштаб считается от разрешения // видеофайла, поэтому рамка попадает на лицо при любом
размере миниатюры.

<script setup lang="ts">
import { computed } from 'vue'
import { markFaceExamples } from '../api/characters'

const emit = defineEmits<{
  /** Лицо помечено эталоном или метка снята. */
  example: [payload: { faceId: number; marked: boolean; changed: number }]
  /** Лицо выбрано или выбор снят. */
  select: [faceId: number]
}>()
import { type FaceView, facePreviewUrl } from '../api/characters'

const props = defineProps<{
  /** Видеофайл-владелец лиц. */
  videofileId: number
  /** Лица для показа. */
  faces: FaceView[]
  /** Разрешение кадра видеофайла: по нему считается положение рамки. */
  frameWidth: number
  /** Высота кадра видеофайла. */
  frameHeight: number
  /** Показывать ли кнопку метки эталона. */
  markable?: boolean
  /** Считать ли рамки рамками, а не заливкой: так показывают нарисованные вручную. */
  outlined?: boolean
  /** Разрешено ли перетаскивать лицо на персону. */
  draggable?: boolean
  /** Выбранные лица: показываются рамкой. */
  selectedIds?: number[]
}>()

/**
 * Кладёт номер лица в перетаскиваемые данные.
 *
 * В старом проекте лицо перетаскивали прямо на строку персоны, и это основной
 * способ исправить ошибку: лицо попало не в того человека. Браузер отдаёт
 * перетаскиваемое только если положил его этот код, поэтому номер кладём явно.
 *
 * @param event событие начала перетаскивания
 * @param face перетаскиваемое лицо
 */
function startDrag(event: DragEvent, face: FaceView): void {
  if (props.draggable !== true || !event.dataTransfer) {
    return
  }
  event.dataTransfer.setData('text/plain', String(face.id))
  event.dataTransfer.effectAllowed = 'move'
}

/**
 * Меняет метку эталона на одном лице.
 *
 * Вызов идёт здесь, а не в потребителе: адрес и правило «метку ставит человек»
 * относятся к метке, а не к экрану. Потребителю достаётся событие с
 * результатом — чтобы он мог показать, сколько лиц изменилось.
 *
 * @param faceId лицо
 */
async function toggleExample(faceId: number): Promise<void> {
  const face = props.faces.find((item) => item.id === faceId)
  if (!face) return
  const result = await markFaceExamples(props.videofileId, [faceId], !face.isExample)
  face.isExample = !face.isExample
  emit('example', { faceId, marked: face.isExample, changed: result.changed })
}

/**
 * Положение и размер рамок в процентах миниатюры.
 *
 * Считается от разрешения видеофайла, а не от размеров картинки: миниатюра может
 * показываться в любом размере, а рамка обязана остаться на месте.
 */
const boxes = computed(() =>
  props.faces.map((face) => ({
    left: `${(face.x1 / props.frameWidth) * 100}%`,
    top: `${(face.y1 / props.frameHeight) * 100}%`,
    width: `${((face.x2 - face.x1) / props.frameWidth) * 100}%`,
    height: `${((face.y2 - face.y1) / props.frameHeight) * 100}%`,
  })),
)

/**
 * Подпись миниатюры для оператора.
 *
 * @param face лицо
 * @returns текст для `alt`
 */
function caption(face: FaceView): string {
  return `Лицо на кадре ${face.frameNumber}, ${face.personName}`
}
</script>

<template>
  <ul class="thumbnails">
    <li
      v-for="(face, index) in faces"
      :key="face.id"
      class="thumb"
      :class="{ outlined: outlined === true, chosen: (selectedIds ?? []).includes(face.id) }"
    >
      <img
        class="frame"
        :src="facePreviewUrl(videofileId, face.frameNumber)"
        :alt="caption(face)"
        :draggable="draggable === true"
        loading="lazy"
        @click="emit('select', face.id)"
        @dragstart="startDrag($event, face)"
      />
      <span class="box" :style="boxes[index]" />
      <button
        v-if="markable === true"
        type="button"
        class="example"
        :class="{ on: face.isExample }"
        :aria-pressed="face.isExample"
        :title="
          face.isExample
            ? 'Эталон: снять метку «этот человек известен»'
            : 'Пометить эталоном: подтвердить, что это этот человек'
        "
        @click="toggleExample(face.id)"
      >
        эталон
      </button>
      <span class="caption">
        кадр {{ face.frameNumber }}
        <span v-if="face.origin === 'OPERATOR'" class="operator" title="рамку нарисовал оператор">
          вручную
        </span>
      </span>
    </li>
  </ul>
</template>

<style scoped>
.thumbnails {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.thumb {
  width: 135px;
  position: relative;
  border: 1px solid #d0d4d8;
  background: #ffffff;
}

.thumb.thumb.chosen {
  outline: 2px solid #1a4f8a;
}

.outlined {
  border-color: #2f7d32;
}

.frame {
  display: block;
  width: 100%;
  height: auto;
}

.box {
  position: absolute;
  border: 2px solid #c62828;
  box-sizing: border-box;
  pointer-events: none;
}

.thumb.outlined .box {
  border-style: dashed;
  border-color: #2f7d32;
}

.caption {
  font-size: 11px;
  padding: 2px 4px;
  color: #333333;
  display: flex;
  gap: 4px;
  justify-content: space-between;
}

.operator {
  color: #2f7d32;
}
</style>
