// Лист превью кадров. // // Лист — это сетка 16 на 16 ячеек по 135×75, то есть 256 кадров на лист
// (research.md Т-03). Отдельный компонент потому, что лист нужен и на экране // структуры, и позже
в редакторе планов: раскладку и правила показа не должно // приходится повторять в двух местах. //
// Значимые кадры не выделяются пустотой: в ячейке всегда превью, а признаки // границы и ключевого
кадра накладываются поверх (frame-preview.md § 5).

<script setup lang="ts">
import { computed } from 'vue'
import { type PreviewUrlView, previewSheetUrl } from '../api/structure'

const props = defineProps<{
  /** Серия-владелец листа. */
  seriesId: number
  /** Описание листа с адресом и раскладкой. */
  sheet: PreviewUrlView
  /** Кадр, который нужно подсветить, либо `null`. */
  highlightFrame: number | null
}>()

/**
 * Положение подсвеченной ячейки в процентах листа.
 *
 * Считается сервером и приходит в `crop`: клиент не знает раскладку листа и
 * не должен её угадывать (FR-022).
 */
const highlight = computed(() => {
  const crop = props.sheet.crop
  if (crop === null) {
    return null
  }
  return {
    left: `${(crop.x / props.sheet.sheetWidth) * 100}%`,
    top: `${(crop.y / props.sheet.sheetHeight) * 100}%`,
    width: `${(crop.width / props.sheet.sheetWidth) * 100}%`,
    height: `${(crop.height / props.sheet.sheetHeight) * 100}%`,
  }
})
</script>

<template>
  <figure class="syp-sheet mb-0">
    <figcaption class="text-body-secondary mb-2">
      Лист №{{ sheet.index }}: кадры {{ sheet.firstFrame }}…{{ sheet.lastFrame }},
      {{ sheet.frameNumbers }} шт., {{ sheet.columns }}×{{ sheet.rows }} ячеек по
      {{ sheet.cellWidth }}×{{ sheet.cellHeight }}
    </figcaption>

    <p v-if="!sheet.isReady" class="syp-stale mb-0">
      Лист не готов: анализ не завершён или оборвался. Незавершённый лист не выдаётся — иначе
      оператор увидел бы половину серии и решил, что второй половины нет.
    </p>

    <div v-else class="syp-sheet-canvas">
      <img
        :src="previewSheetUrl(seriesId, sheet.index)"
        :alt="`Лист превью №${sheet.index}, кадры ${sheet.firstFrame}…${sheet.lastFrame}`"
        :width="sheet.sheetWidth"
        :height="sheet.sheetHeight"
      />
      <span
        v-if="highlight"
        class="syp-sheet-cell"
        :style="{
          left: highlight.left,
          top: highlight.top,
          width: highlight.width,
          height: highlight.height,
        }"
      />
    </div>
  </figure>
</template>

<style scoped>
.syp-sheet-canvas {
  display: inline-block;
  line-height: 0;
  max-width: 100%;
  position: relative;
}

.syp-sheet-canvas img {
  height: auto;
  max-width: 100%;
}

.syp-sheet-cell {
  border: 2px solid var(--syp-origin-cancelled);
  position: absolute;
}
</style>
