// Экран структуры эпизода (задача T054). // // Экран показывает результат автоматики в двух слоях:
рабочую структуру — // сцены и планы с их происхождением — и, по кнопке, сырой результат //
автоматики последнего прогона. Второй слой нужен для сравнения: без него // нельзя увидеть, что
машина предложила и что человек с этим сделал // (FR-093). // // Цвет происхождения задан один раз в
CSS: красный — алгоритм, зелёный — // оператор, оранжевый — отменено решение алгоритма (FR-015,
FR-016).

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import PreviewSheetView from '../components/PreviewSheetView.vue'
import { type PreviewUrlView, readPreviewUrl } from '../api/structure'
import { formatBytes } from '../format/values'
import { useStructureStore } from '../stores/structure'

const route = useRoute()
const store = useStructureStore()

/** Описание листа превью, показанного рядом со структурой. */
const sheet = ref<PreviewUrlView | null>(null)

/** Кадр, выбранный для показа превью. */
const selectedFrame = ref<number | null>(null)

/** Идентификатор эпизода из адреса. */
const episodeId = computed(() => Number(route.params.episodeId))

/** Сцены текущей страницы. */
const scenes = computed(() => store.structure.value?.scenes ?? [])

/** Есть ли следующая страница сцен. */
const hasNextPage = computed(() => {
  const value = store.structure.value
  if (value === null) {
    return false
  }
  return value.offset + value.limit < value.scenesTotal
})

onMounted(() => {
  void store.reload(episodeId.value)
})

watch(episodeId, (next) => {
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
    sheet.value = await readPreviewUrl(episodeId.value, frame ?? undefined)
  } catch (failure) {
    store.clearError()
    sheet.value = null
    store.error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

/**
 * Пояснение цвета происхождения словами.
 *
 * @param origin происхождение границы
 * @returns текст для оператора
 */
function originTitle(origin: string): string {
  switch (origin) {
    case 'AUTO':
      return 'решение алгоритма'
    case 'OPERATOR':
      return 'сделано оператором'
    case 'CANCELLED':
      return 'решение алгоритма отменено оператором'
    default:
      return origin
  }
}
</script>

<template>
  <section class="structure">
    <h2>Структура эпизода</h2>

    <p v-if="store.loading.value" class="note">Запрос к бэкенду…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">скрыть</button>
    </p>

    <p v-if="store.structure.value" class="state">
      Сцен: {{ store.structure.value.scenesTotal }}, планов: {{ store.structure.value.shotsTotal }},
      кадров: {{ store.structure.value.frameCount }}.
      <span v-if="store.structure.value.algorithmVersion">
        Версия алгоритма: {{ store.structure.value.algorithmVersion }}.
      </span>
    </p>

    <p v-if="store.isStale.value" class="stale" role="status">
      <span class="code">{{ store.staleCode }}</span>
      {{ store.structure.value?.staleReason }}
      <button type="button" @click="store.analyse(episodeId)">пересчитать</button>
    </p>

    <p class="actions">
      <button type="button" :disabled="store.loading.value" @click="store.analyse(episodeId)">
        Разобрать эпизод заново
      </button>
      <button type="button" @click="store.toggleRaw()">
        {{ store.rawVisible.value ? 'Скрыть сырой результат' : 'Показать сырой результат' }}
      </button>
      <button type="button" @click="showSheet(null)">Показать лист превью</button>
    </p>

    <nav v-if="store.structure.value" class="pager">
      <button
        type="button"
        :disabled="store.structure.value.offset === 0"
        @click="store.previousPage(episodeId)"
      >
        предыдущие сцены
      </button>
      <span>
        Показаны сцены с {{ store.structure.value.offset + 1 }} по
        {{ store.structure.value.offset + scenes.length }}
      </span>
      <button type="button" :disabled="!hasNextPage" @click="store.nextPage(episodeId)">
        следующие сцены
      </button>
    </nav>

    <table v-if="scenes.length > 0" class="scenes">
      <thead>
        <tr>
          <th>Сцена</th>
          <th>Кадры</th>
          <th>Место действия</th>
          <th>Планы</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="scene in scenes" :key="scene.id">
          <td :class="['origin', scene.origin.toLowerCase()]">{{ originTitle(scene.origin) }}</td>
          <td>
            {{ scene.firstFrame }}…{{ scene.lastFrame }}
            <span v-if="scene.isStale" class="stale-mark">устарела</span>
          </td>
          <td>{{ scene.location?.name ?? 'не назначено' }}</td>
          <td>
            <ul class="shots">
              <li v-for="shot in scene.shots" :key="shot.id">
                <span :class="['origin', shot.origin.toLowerCase()]">{{ shot.origin }}</span>
                {{ shot.firstFrame }}…{{ shot.lastFrame }} — {{ shot.size }}
                <em>({{ shot.sizeOrigin }})</em>
                <button type="button" @click="showSheet(shot.firstFrame)">превью</button>
              </li>
            </ul>
          </td>
        </tr>
      </tbody>
    </table>

    <p v-else-if="!store.loading.value" class="note">
      Сцен нет: анализ эпизода ещё не выполнялся или не завершён.
    </p>

    <section v-if="store.rawVisible.value" class="raw">
      <h3>Сырой результат автоматики</h3>
      <p class="note">
        Границы, которые выдал алгоритм, до ручных правок. Рабочая структура выше — то, что осталось
        после правок; сравнивать их нужно рядом (FR-093).
      </p>
      <p v-if="store.raw.value" class="note">
        Прогон №{{ store.raw.value.runId }}, границ всего: {{ store.raw.value.total }}, показано:
        {{ store.raw.value.boundaries.length }}.
      </p>
      <table v-if="store.raw.value && store.raw.value.boundaries.length > 0">
        <thead>
          <tr>
            <th>Уровень</th>
            <th>Кадры</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(boundary, position) in store.raw.value.boundaries" :key="position">
            <td>{{ boundary.level }}</td>
            <td>{{ boundary.firstFrame }}…{{ boundary.lastFrame }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <PreviewSheetView
      v-if="sheet"
      :episode-id="episodeId"
      :sheet="sheet"
      :highlight-frame="selectedFrame"
    />
    <p v-if="sheet && sheet.isReady" class="note">
      Размер листа: {{ formatBytes(sheet.byteSize ?? 0) }}, тип содержимого:
      {{ sheet.contentType }}.
    </p>
  </section>
</template>

<style scoped>
.structure {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.actions,
.pager {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.error {
  color: #8a1f1f;
}

.code {
  font-family: ui-monospace, monospace;
  font-weight: 600;
}

.stale {
  background: #fff4e0;
  border-left: 4px solid #d06000;
  padding: 0.4rem 0.6rem;
}

.stale-mark {
  color: #d06000;
  font-size: 0.85rem;
}

.note {
  color: #444;
  font-size: 0.9rem;
}

.scenes {
  border-collapse: collapse;
  width: 100%;
}

.scenes th,
.scenes td {
  border-bottom: 1px solid #ddd;
  padding: 0.3rem 0.5rem;
  text-align: left;
  vertical-align: top;
}

.shots {
  list-style: none;
  margin: 0;
  padding: 0;
}

.shots li {
  font-size: 0.9rem;
}

/* Цвет происхождения границы: алгоритм, оператор, отмена. */
.origin.auto {
  color: #b02020;
}

.origin.operator {
  color: #1f7a35;
}

.origin.cancelled {
  color: #d06000;
}
</style>
