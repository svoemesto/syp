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
  <figure class="sheet">
    <figcaption>
      Лист №{{ sheet.index }}: кадры {{ sheet.firstFrame }}…{{ sheet.lastFrame }},
      {{ sheet.frameNumbers }} шт., {{ sheet.columns }}×{{ sheet.rows }} ячеек по
      {{ sheet.cellWidth }}×{{ sheet.cellHeight }}
    </figcaption>

    <p v-if="!sheet.isReady" class="note">
      Лист не готов: анализ не завершён или оборвался. Незавершённый лист не выдаётся — иначе
      оператор увидел бы половину серии и решил, что второй половины нет.
    </p>

    <div v-else class="canvas">
      <img
        :src="previewSheetUrl(seriesId, sheet.index)"
        :alt="`Лист превью №${sheet.index}, кадры ${sheet.firstFrame}…${sheet.lastFrame}`"
        :width="sheet.sheetWidth"
        :height="sheet.sheetHeight"
      />
      <span
        v-if="highlight"
        class="cell"
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
.sheet {
  margin: 0;
}

figcaption {
  font-size: 0.9rem;
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

.cell {
  border: 2px solid #d06000;
  position: absolute;
}

.note {
  font-size: 0.9rem;
}
</style>
