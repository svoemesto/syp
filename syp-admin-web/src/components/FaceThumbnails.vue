// Миниатюры лиц. // // Отдельный компонент потому, что список лиц нужен и на экране лиц, и позже //
в редакторе: правила показа не должно приходиться повторять в двух местах. // // Миниатюра лица
показывается **кадром, а не отдельным файлом**: кадров в // исходном разрешении система не хранит
(FR-024), а уменьшённые превью всех // кадров уже лежат на листах. Отдельная картинка на каждое лицо
означала бы // ещё около 17,5 ГБ данных, которые ничего не добавляют оператору (Q10). // // Рамка
лица рисуется поверх кадра: координаты приходят в пикселях кадра // полного разрешения, а картинка —
135×75. Масштаб считается от разрешения // эпизода, поэтому рамка попадает на лицо при любом размере
миниатюры.

<script setup lang="ts">
import { computed } from 'vue'
import { type FaceView, facePreviewUrl } from '../api/characters'

const props = defineProps<{
  /** Эпизод-владелец лиц. */
  episodeId: number
  /** Лица для показа. */
  faces: FaceView[]
  /** Разрешение кадра эпизода: по нему считается положение рамки. */
  frameWidth: number
  /** Высота кадра эпизода. */
  frameHeight: number
  /** Считать ли рамки рамками, а не заливкой: так показывают нарисованные вручную. */
  outlined?: boolean
}>()

/**
 * Положение и размер рамок в процентах миниатюры.
 *
 * Считается от разрешения эпизода, а не от размеров картинки: миниатюра может
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
      :class="{ outlined: outlined === true }"
    >
      <img
        class="frame"
        :src="facePreviewUrl(episodeId, face.frameNumber)"
        :alt="caption(face)"
        loading="lazy"
      />
      <span class="box" :style="boxes[index]" />
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

.thumb.outlined {
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
