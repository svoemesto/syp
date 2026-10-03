// Лист превью кадров. // // Лист — это сетка 16 на 16 ячеек по 135×75, то есть 256 кадров на лист
// (research.md Т-03). Отдельный компонент потому, что лист нужен и на экране // структуры, и позже
// в редакторе планов: раскладку и правила показа не должно // приходиться повторять в двух местах.
// // Значимые кадры не выделяются пустотой: в ячейке всегда превью, а признаки // границы и
ключевого // кадра накладываются поверх (frame-preview.md § 5). // // Поверх картинки лежит сетка
кликабельных ячеек: без неё оператор листает // триста сорок семь листов и не может указать на кадр.
// // Компонент ничего не знает про ответ бэкенда: ему передают строку экрана с // подписью, адресом
и процентами выделения (ADR-0015).

<script setup lang="ts">
import { computed } from 'vue'
import type { PreviewSheetFrameRow } from '../api/view-model'

const props = defineProps<{
  /** Лист превью приведённый к строке экрана. */
  sheet: PreviewSheetFrameRow
}>()

const emit = defineEmits<{ pick: [frame: number] }>()

/**
 * Ячейки листа для наложения сетки.
 *
 * Номера кадров считаются от первого кадра листа подряд: лист покрывает кадры
 * без пропусков (frame-preview.md § 1), а последний листvideofile может быть не
 * полным — ячейки за его последним кадром помечаются пустыми и не кликаются.
 */
const cells = computed(() => {
  const total = props.sheet.columns * props.sheet.rows
  return Array.from({ length: total }, (_, position) => {
    const frame = props.sheet.firstFrame + position
    return { key: position, frame, exists: frame <= props.sheet.lastFrame }
  })
})
</script>

<template>
  <figure class="sheet">
    <figcaption>{{ sheet.caption }}</figcaption>

    <p v-if="!sheet.isReady" class="syp-stale">
      Лист не готов: анализ не завершён или оборвался. Незавершённый лист не выдаётся — иначе
      оператор увидел бы половину эпизода и решил, что второй половины нет.
    </p>

    <div v-else class="canvas">
      <img
        :src="sheet.url"
        :alt="sheet.alt"
        :width="sheet.sheetWidth"
        :height="sheet.sheetHeight"
      />
      <div class="grid" :style="{ gridTemplateColumns: `repeat(${sheet.columns}, 1fr)` }">
        <button
          v-for="cell in cells"
          :key="cell.key"
          type="button"
          class="cell"
          :disabled="!cell.exists"
          :title="`Кадр ${cell.frame}`"
          :aria-label="`Показать сцену с кадра ${cell.frame}`"
          @click="emit('pick', cell.frame)"
        />
      </div>
      <span
        v-if="sheet.highlight"
        class="cell-ring"
        :style="{
          left: sheet.highlight.left,
          top: sheet.highlight.top,
          width: sheet.highlight.width,
          height: sheet.highlight.height,
        }"
      />
    </div>
  </figure>
</template>

<style scoped>
.sheet {
  margin: 0;
}

figcaption {
  color: var(--syp-text-muted);
  font-size: 0.8125rem;
  margin-bottom: 0.35rem;
}

.canvas {
  display: inline-block;
  line-height: 0;
  position: relative;
}

img {
  max-width: 100%;
  height: auto;
}

/* Сетка ячеек лежит поверх картинки и ничего не рисует: рамку рисует
   выделение, а прозрачная кнопка нужна ради клика и клавиатуры. */
.grid {
  bottom: 0;
  display: grid;
  left: 0;
  position: absolute;
  right: 0;
  top: 0;
}

.cell {
  background: transparent;
  border: 0;
  cursor: pointer;
  padding: 0;
}

.cell:hover:not(:disabled) {
  box-shadow: inset 0 0 0 2px var(--syp-link);
}

.cell:disabled {
  cursor: default;
}

.cell-ring {
  border: 2px solid var(--syp-warning);
  pointer-events: none;
  position: absolute;
}
</style>
