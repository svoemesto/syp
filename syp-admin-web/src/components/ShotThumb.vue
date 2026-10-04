<script setup lang="ts">
// Ячейка плана: миниатюра кадра с подписью.
//
// В старой форме колонки FROM, TO и TYPE таблицы планов заполнялись не
// текстом, а картинкой: подпись времени под миниатюрой, а в колонке TYPE —
// пиктограмма типа. Здесь то же самое, потому что номер кадра оператору
// ничего не говорит: он смотрит на кадр.

import { computed } from 'vue'
import { frameImageUrl } from '../api/structure'

const props = defineProps<{
  videofileId: number
  frameNumber: number
  width: number
}>()

/** Адрес миниатюры. */
const source = computed(() => frameImageUrl(props.videofileId, props.frameNumber, props.width))
</script>

<template>
  <figure class="shot-thumb">
    <img
      :src="source"
      :alt="`frame ${frameNumber}`"
      :width="width"
      :height="Math.round((width * 9) / 16)"
      loading="lazy"
    />
    <figcaption>{{ frameNumber }}</figcaption>
  </figure>
</template>

<style scoped>
.shot-thumb {
  margin: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.1rem;
}
.shot-thumb img {
  display: block;
  border-radius: 0.15rem;
  background: var(--syp-bg);
  object-fit: cover;
}
figcaption {
  font-size: 0.6875rem;
  color: var(--syp-text-muted);
  font-variant-numeric: tabular-nums;
}
</style>
