// Миниатюры лиц. // // Отдельный компонент потому, что список лиц нужен и на экране лиц, и позже //
в редакторе: правила показа не должно приходиться повторять в двух местах. // // Миниатюра лица
показывается **кадром, а не отдельным файлом**: кадров в // исходном разрешении система не хранит
(FR-024), а уменьшённые превью всех // кадров уже лежат на листах. Отдельная картинка на каждое лицо
означала бы // ещё около 17,5 ГБ данных, которые ничего не добавляют оператору (Q10). // // Рамка
лица рисуется поверх кадра: координаты приходят в пикселях кадра // полного разрешения, а картинка —
135×75. Масштаб считается от разрешения // серии, поэтому рамка попадает на лицо при любом размере
миниатюры.

<script setup lang="ts">
import { computed } from 'vue'
import { facePreviewUrl } from '../api/characters'
import type { FaceRow } from '../api/view-model'

const props = defineProps<{
  /** Серия-владелец лиц. */
  seriesId: number
  /** Лица для показа. */
  faces: FaceRow[]
  /** Разрешение кадра серии: по нему считается положение рамки. */
  frameWidth: number
  /** Высота кадра серии. */
  frameHeight: number
  /** Считать ли рамки рамками, а не заливкой: так показывают нарисованные вручную. */
  outlined?: boolean
}>()

/**
 * Положение и размер рамок в процентах миниатюры.
 *
 * Считается от разрешения серии, а не от размеров картинки: миниатюра может
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
        :src="facePreviewUrl(seriesId, face.frameNumber)"
        :alt="face.caption"
        loading="lazy"
      />
      <span class="box" :style="boxes[index]" />
      <span class="caption">
        кадр {{ face.frameLabel }}
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
  gap: 0.5rem;
}

.thumb {
  width: 135px;
  position: relative;
  background: #0d1117;
  border: 1px solid var(--syp-border);
  border-radius: var(--bs-border-radius);
  overflow: hidden;
}

.thumb.outlined {
  border-color: var(--syp-origin-operator);
}

.frame {
  display: block;
  width: 100%;
  height: auto;
}

.box {
  position: absolute;
  border: 2px solid var(--syp-origin-auto);
  box-sizing: border-box;
  pointer-events: none;
}

.thumb.outlined .box {
  border-style: dashed;
  border-color: var(--syp-origin-operator);
}

.caption {
  font-size: 0.6875rem;
  padding: 0.2rem 0.35rem;
  color: var(--syp-text-muted);
  background-color: var(--syp-raised);
  display: flex;
  gap: 0.25rem;
  justify-content: space-between;
}

.operator {
  color: var(--syp-origin-operator);
}
</style>
