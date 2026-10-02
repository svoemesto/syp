// Экран структуры серии (задача T054). // // Экран показывает результат автоматики в двух слоях:
рабочую структуру — // сцены и планы с их происхождением — и, по кнопке, сырой результат //
автоматики последнего прогона. Второй слой нужен для сравнения: без него // нельзя увидеть, что
машина предложила и что человек с этим сделал (FR-093). // // Цвет происхождения задан один раз в
`theme/theme.css`: алгоритм, оператор, // отмена решения алгоритма (FR-015, FR-016). Класс цвета
приходит из // `api/view-model.ts`, а не из шаблона, — иначе правило «цвет происхождения // один раз
в CSS» перестало бы держаться.

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PreviewSheetView from '../components/PreviewSheetView.vue'
import StateBlock from '../components/StateBlock.vue'
import { type PreviewUrlView, readPreviewUrl } from '../api/structure'
import { useStructureStore } from '../stores/structure'
import { useNotify } from '../ui/notify'

const route = useRoute()
const store = useStructureStore()
const notify = useNotify()

/** Описание листа превью, показанного рядом со структурой. */
const sheet = ref<PreviewUrlView | null>(null)

/** Кадр, выбранный для показа превью. */
const selectedFrame = ref<number | null>(null)

/** Идентификатор серии из адреса. */
const seriesId = computed(() => Number(route.params.seriesId))

/** Сцены текущей страницы. */
const scenes = computed(() => store.structure.value?.scenes ?? [])

/** Структура прочитана и сцен на ней есть. */
const hasScenes = computed(() => scenes.value.length > 0)

/** Размер листа превью словами; строка байтов оператору ничего не говорит. */
const sheetSize = computed(() => {
  if (sheet.value === null || sheet.value.byteSize === null) {
    return ''
  }
  return `${(sheet.value.byteSize / 1024 / 1024).toFixed(1)} МиБ`
})

onMounted(() => {
  void store.reload(seriesId.value)
})

watch(seriesId, (next) => {
  void store.reload(next)
})

/**
 * Показывает лист превью для выбранного кадра.
 *
 * @param frame номер кадра либо `null`, чтобы показать первый лист
 */
async function showSheet(frame: number | null): Promise<void> {
  selectedFrame.value = frame
  try {
    sheet.value = await readPreviewUrl(seriesId.value, frame ?? undefined)
  } catch (failure) {
    store.clearError()
    sheet.value = null
    store.error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

/** Ставит анализ структуры заново. */
async function analyse(): Promise<void> {
  if (await store.analyse(seriesId.value)) {
    notify('Задание анализа поставлено в очередь. Сцена и планы появятся после прогона.', 'info')
  }
}
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h2 class="syp-page-title">Структура серии</h2>
        <p class="syp-page-lead">
          Сцены и планы с их происхождением. Сырой результат автоматики показывается отдельно —
          сравнивать его с рабочей структурой иначе нечем.
        </p>
      </div>
      <div class="btn-group">
        <button
          type="button"
          class="btn btn-primary"
          :disabled="store.loading.value"
          @click="analyse"
        >
          разобрать заново
        </button>
        <button type="button" class="btn btn-outline-secondary" @click="store.toggleRaw()">
          {{ store.rawVisible.value ? 'скрыть сырой результат' : 'показать сырой результат' }}
        </button>
        <button type="button" class="btn btn-outline-secondary" @click="showSheet(null)">
          лист превью
        </button>
      </div>
    </div>

    <StateBlock
      :loading="store.loading.value && store.structure.value === null"
      :error="store.error.value"
      :error-code="store.errorCode.value"
      @dismiss="store.clearError()"
    />

    <div v-if="store.structure.value" class="syp-stale mb-3" role="status">
      {{ store.structure.value.summary }}.
      <span class="text-body-secondary ms-2">
        Версия алгоритма: {{ store.structure.value.algorithmVersion }}.
      </span>
    </div>

    <div v-if="store.isStale.value" class="syp-stale mb-3" role="status">
      <span class="syp-mono me-2">{{ store.staleCode.value }}</span>
      {{ store.staleReason.value }}
      <button type="button" class="btn btn-sm btn-outline-secondary ms-2" @click="analyse">
        пересчитать
      </button>
    </div>

    <div v-if="store.structure.value" class="card">
      <div class="card-header d-flex justify-content-between align-items-center">
        <span
          >Сцены {{ store.structure.value.visibleFrom }}…{{ store.structure.value.visibleTo }}</span
        >
        <div class="btn-group">
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="store.loading.value || store.structure.value.offset === 0"
            @click="store.previousPage(seriesId)"
          >
            предыдущие
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="store.loading.value || !store.structure.value.hasNextPage"
            @click="store.nextPage(seriesId)"
          >
            следующие
          </button>
        </div>
      </div>

      <div v-if="hasScenes" class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col">Происхождение</th>
              <th scope="col">Кадры</th>
              <th scope="col">Место действия</th>
              <th scope="col">Планы</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="scene in scenes" :key="scene.id">
              <td>
                <span class="syp-origin" :class="`syp-origin-${scene.originClass}`">
                  {{ scene.originTitle }}
                </span>
              </td>
              <td class="syp-number">
                {{ scene.frames }}
                <span v-if="scene.isStale" class="badge text-bg-warning ms-1">устарела</span>
              </td>
              <td>{{ scene.location }}</td>
              <td>
                <ul class="syp-list-plain">
                  <li
                    v-for="shot in scene.shots"
                    :key="shot.id"
                    class="d-flex gap-2 align-items-baseline"
                  >
                    <span
                      class="syp-origin"
                      :class="`syp-origin-${shot.originClass}`"
                      :title="shot.originTitle"
                    >
                      {{ shot.size }}
                    </span>
                    <span class="syp-number">{{ shot.frames }}</span>
                    <span class="syp-unit">{{ shot.sizeTitle }}</span>
                    <button
                      type="button"
                      class="btn btn-sm btn-link p-0"
                      @click="showSheet(shot.firstFrame)"
                    >
                      превью
                    </button>
                    <span v-if="shot.isStale" class="badge text-bg-warning">устарел</span>
                  </li>
                </ul>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <StateBlock
        v-else-if="!store.loading.value"
        :error="''"
        empty-title="Сцен нет"
        empty-text="Анализ серии ещё не выполнялся или не завершён."
      />
    </div>

    <div v-if="store.rawVisible.value" class="card mt-4">
      <div class="card-header">Сырой результат автоматики</div>
      <div class="card-body">
        <p class="mb-2 text-body-secondary">
          Границы, которые выдал алгоритм, до ручных правок. Рабочая структура выше — то, что
          осталось после правок; сравнивать их нужно рядом (FR-093).
        </p>
        <p v-if="store.rawRunId.value" class="form-text">
          Прогон №{{ store.rawRunId.value }}, границ всего: {{ store.rawTotal.value }}, показано:
          {{ store.raw.value.length }}.
        </p>
        <div v-if="store.raw.value.length > 0" class="table-responsive">
          <table class="table table-sm align-middle">
            <thead>
              <tr>
                <th scope="col">Уровень</th>
                <th scope="col">Кадры</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(boundary, position) in store.raw.value" :key="position">
                <td>{{ boundary.level }}</td>
                <td class="syp-number">{{ boundary.frames }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="mb-0 text-body-secondary">Прогонов ещё не было.</p>
      </div>
    </div>

    <div v-if="sheet" class="card mt-4">
      <div class="card-header d-flex justify-content-between align-items-center">
        <span>Лист превью</span>
        <span class="syp-unit">{{ sheetSize }}</span>
      </div>
      <div class="card-body">
        <PreviewSheetView :series-id="seriesId" :sheet="sheet" :highlight-frame="selectedFrame" />
      </div>
    </div>
  </section>
</template>
