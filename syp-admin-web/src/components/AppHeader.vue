// Шапка админки: название и навигация по разделам. // // Навигация перечисляет **все** разделы, а
не только те, что уже работают. // Оператор должен видеть, что в системе есть, и понимать, что
достроенного // пункта не откроет пустую страницу, а заглушку «в работе». // // Разделы «суммы» и
«структура» живут для конкретной серии. Пока серия не // выбрана, пункт ведёт на экран с пояснением
и кнопкой возврата в приём: // молчаливый переход на пустой экран выглядел бы как потеря данных.

<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import {
  connectionIsAttention,
  connectionLabel,
  connectionTone,
  currentJob,
  currentQueue,
  retryNotifications,
} from '../stores/notifications'

const route = useRoute()
const catalog = useCatalogStore()

/** Разделы админки в порядке работы оператора. */
const sections = computed(() => [
  { name: 'intake', title: 'Приём сериалов', hint: 'Сериалы, серии, параметры файла' },
  { name: 'sums', title: 'Суммы', hint: 'Состояние подсчёта SHA-256 серии' },
  { name: 'structure', title: 'Структура', hint: 'Сцены, планы, границы' },
  { name: 'faces', title: 'Лица', hint: 'Детекция, кластеры, персоны' },
  { name: 'recipes', title: 'Сценарии', hint: 'Фильтры, выдача, подпись' },
])

/** Состояние потока уведомлений словами. */
const connection = computed(() => connectionLabel())

/** Класс индикатора состояния потока. */
const connectionBadge = computed(() => connectionTone())

/** Требует ли состояние потока внимания оператора. */
const connectionAttention = computed(() => connectionIsAttention())

/** Сводка по очереди заданий. */
const queue = computed(() => currentQueue())

/** Ход последнего задания одной строкой. */
const jobCaption = computed(() => {
  const row = currentJob()
  if (row === null) {
    return null
  }
  const done = row.percent === null ? row.done : `${row.percent}%`
  return `${row.kindTitle}: ${row.stateTitle} (${done})`
})

/** Название выбранной серии для показа в шапке. */
const selectedSeriesLabel = computed(() => {
  if (catalog.selectedSeriesId.value === null) {
    return 'серия не выбрана'
  }
  const row = catalog.series.value.find((item) => item.id === catalog.selectedSeriesId.value)
  return row === undefined ? `серия №${catalog.selectedSeriesId.value}` : row.name
})

/**
 * Адрес раздела с учётом выбранной серии.
 *
 * Суммы, структура и лица живут для конкретной серии: пункт ведёт на её экран,
 * а пока серия не выбрана — на страницу с пояснением.
 *
 * @param name имя раздела
 * @returns адрес маршрута
 */
function linkFor(name: string): { name: string; params?: { seriesId: string } } {
  const perSeries: Record<string, string> = {
    sums: 'checksum',
    structure: 'structure-of-series',
    faces: 'faces-of-series',
  }
  const target = perSeries[name]
  if (target === undefined) {
    return { name }
  }
  const seriesId = catalog.selectedSeriesId.value
  if (seriesId === null) {
    return { name }
  }
  return { name: target, params: { seriesId: String(seriesId) } }
}

/** Активен ли раздел по адресу. */
function isActive(name: string): boolean {
  if (route.name === name) {
    return true
  }
  // Экран серии считается активным вместе со своим разделом: иначе при
  // переходе на `/series/:seriesId/structure` пункт «структура» гас бы,
  // хотя оператор именно в нём.
  const perSeries: Record<string, string> = {
    sums: 'checksum',
    structure: 'structure-of-series',
    faces: 'faces-of-series',
  }
  return route.name === perSeries[name]
}
</script>

<template>
  <header class="syp-header border-bottom bg-white">
    <div class="syp-content">
      <div class="d-flex align-items-center justify-content-between py-2 gap-3 flex-wrap">
        <RouterLink :to="{ name: 'intake' }" class="syp-brand text-decoration-none">
          <span class="syp-brand-mark">SYP</span>
          <span class="syp-brand-sub">админка</span>
        </RouterLink>

        <span class="syp-selection text-body-secondary">
          {{ selectedSeriesLabel }}
        </span>

        <div class="syp-live d-flex align-items-center gap-2 flex-wrap">
          <span v-if="queue !== null" class="syp-queue text-body-secondary">
            очередь: {{ queue.summary }}
          </span>
          <span v-if="jobCaption !== null" class="syp-job text-body-secondary">
            {{ jobCaption }}
          </span>
          <span class="badge" :class="connectionBadge">{{ connection }}</span>
          <button
            v-if="connectionAttention"
            type="button"
            class="btn btn-sm btn-outline-light"
            @click="retryNotifications"
          >
            подключиться снова
          </button>
        </div>
      </div>

      <nav class="syp-nav" aria-label="Разделы админки">
        <RouterLink
          v-for="section in sections"
          :key="section.name"
          :to="linkFor(section.name)"
          class="syp-nav-link"
          :class="{ 'is-current': isActive(section.name) }"
          :title="section.hint"
        >
          {{ section.title }}
        </RouterLink>
      </nav>
    </div>
  </header>
</template>

<style scoped>
.syp-header {
  position: sticky;
  top: 0;
  z-index: 20;
}

.syp-brand {
  align-items: baseline;
  display: inline-flex;
  gap: 0.5rem;
}

.syp-brand-mark {
  color: var(--syp-primary);
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.syp-brand-sub {
  color: var(--syp-text-muted);
  font-size: 0.875rem;
}

.syp-selection {
  font-size: 0.8125rem;
}

.syp-live {
  font-size: 0.8125rem;
}

.syp-queue,
.syp-job {
  font-variant-numeric: tabular-nums;
}

.syp-nav {
  display: flex;
  gap: 0.25rem;
  overflow-x: auto;
}

.syp-nav-link {
  border-bottom: 2px solid transparent;
  color: var(--syp-text-muted);
  font-size: 0.9375rem;
  padding: 0.5rem 0.75rem;
  text-decoration: none;
  white-space: nowrap;
}

.syp-nav-link:hover {
  color: var(--syp-primary-dark);
}

.syp-nav-link.is-current {
  border-bottom-color: var(--syp-primary);
  color: var(--syp-primary-dark);
  font-weight: 600;
}
</style>
