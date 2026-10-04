// Шапка админки: название и навигация по разделам. // // Навигация перечисляет **все** разделы, а
не только те, что уже работают. // Оператор должен видеть, что в системе есть, и понимать, что
достроенного // пункта не откроет пустую страницу, а заглушку «в работе». // // Разделы «суммы» и
«структура» живут для конкретной серии. Пока серия не // выбрана, пункт ведёт на экран с пояснением
и кнопкой возврата в приём: // молчаливый переход на пустой экран выглядел бы как потеря данных.

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import { readVideofile } from '../api/catalog'
import {
  connectionIsAttention,
  connectionLabel,
  connectionTone,
  currentQueue,
  retryNotifications,
} from '../stores/notifications'
import JobProgressMeter from './JobProgressMeter.vue'

const route = useRoute()
const catalog = useCatalogStore()

/** Разделы админки в порядке работы оператора. */
const sections = computed(() => [
  { name: 'intake', title: 'Приём проектов', hint: 'Проекты, серии, параметры файла' },
  { name: 'project', title: 'Проект', hint: 'Параметры проекта, файлы, дорожки' },
  { name: 'sums', title: 'Суммы', hint: 'Состояние подсчёта SHA-256 серии' },
  { name: 'structure', title: 'Структура', hint: 'Сцены, планы, границы' },
  { name: 'faces', title: 'Лица', hint: 'Детекция, кластеры, персоны' },
  { name: 'actions', title: 'Операции', hint: 'Запуск обработки по файлам проекта' },
  { name: 'filters', title: 'Фильтры', hint: 'Построение условий отбора планов' },
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

/** Название выбранного проекта для показа в шапке. */
const selectedVideofileLabel = computed(() => {
  const catalogEntry = catalog.current.value
  if (catalogEntry !== null) {
    return catalogEntry.project.name
  }
  // Каталог заполняется на экране приёма. При прямом заходе по адресу
  // видеофайла он пуст, и подпись «проект не выбран» противоречила тому, что
  // видно: задание по этому видеофайлу работает. Лучше сказать, какой видеофайл
  // открыт, чем утверждать, что ничего не выбрано.
  const raw = route.params.videofileId
  if (typeof raw === 'string' && raw !== '') {
    return `видеофайл ${raw}`
  }
  return 'проект не выбран'
})

/**
 * Проект, для которого открыт раздел, либо `null`, если он не известен.
 *
 * Разделы «проект», «операции» и «фильтры» живут по проекту. При прямом
 * заходе по адресу вида файла проект в каталоге не выбран, и раньше такие
 * пункты молча пропадали из навигации: ссылка без обязательного параметра не
 * разрешалась, и элемент не отрисовывался. Теперь пункт виден всегда, а без
 * проекта помечен недоступным с внятной причиной.
 */
const projectOfSection = computed<number | null>(() => {
  const current = catalog.current.value
  if (current !== null) {
    return current.project.id
  }
  const raw = route.params.videofileId
  return typeof raw === 'string' && raw !== '' ? videofileProject.value : null
})

/** Проект видеофайла, открытого по адресу. */
const videofileProject = ref<number | null>(null)

/** Читает проект видеофайла, открытого по адресу. */
async function readVideofileProject(): Promise<void> {
  const raw = route.params.videofileId
  if (typeof raw !== 'string' || raw === '' || catalog.current.value !== null) {
    return
  }
  try {
    videofileProject.value = (await readVideofile(Number(raw))).projectId
  } catch {
    // Неизвестный видеофайл — обычное дело при устаревшей ссылке: пункт остаётся
    // недоступным с причиной, молчаливого обрыва здесь быть не должно.
    videofileProject.value = null
  }
}

watch(() => route.params.videofileId, readVideofileProject, { immediate: true })

/**
 * Адрес раздела с учётом выбранной серии.
 *
 * Суммы, структура и лица живут для конкретной серии: пункт ведёт на её экран,
 * а пока серия не выбрана — на страницу с пояснением.
 *
 * @param name имя раздела
 * @returns адрес маршрута либо `null`, если раздел недоступен
 */
function linkFor(
  name: string,
): { name: string; params?: { videofileId: string } | { projectId: string } } | null {
  // Операции живут по проекту: набор файлов и признаки «уже сделано» имеют
  // смысл только для всех файлов проекта вместе, а не для одного видеофайла.
  if (name === 'actions' || name === 'filters' || name === 'project') {
    const projectId = projectOfSection.value
    return projectId === null ? null : { name, params: { projectId: String(projectId) } }
  }
  const perVideofile: Record<string, string> = {
    sums: 'checksum',
    structure: 'structure',
    faces: 'faces',
  }
  const target = perVideofile[name]
  if (target === undefined) {
    return { name }
  }
  // Разделы «суммы», «структура» и «лица» живут по видеофайлу, и видеофайл берётся
  // из адреса: выбранного видеофайла в состоянии нет, а адрес — единственное
  // место, где он зафиксирован.
  const videofileId = route.params.videofileId
  if (typeof videofileId !== 'string' || videofileId === '') {
    return { name }
  }
  return { name: target, params: { videofileId } }
}

/** Активен ли раздел по адресу. */
function isActive(name: string): boolean {
  if (route.name === name) {
    return true
  }
  // Экран серии считается активным вместе со своим разделом: иначе при
  // переходе на `/videofiles/:videofileId/structure` пункт «структура» гас бы,
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
  <header class="syp-header border-bottom syp-header__bar">
    <div class="syp-content">
      <div class="d-flex align-items-center justify-content-between py-2 gap-3 flex-wrap">
        <RouterLink :to="{ name: 'intake' }" class="syp-brand text-decoration-none">
          <span class="syp-brand-mark">SYP</span>
          <span class="syp-brand-sub">админка</span>
        </RouterLink>

        <span class="syp-selection text-body-secondary">
          {{ selectedVideofileLabel }}
        </span>

        <!-- Прогресс-мер заданий: ход работы, а не только то, что кнопку
             нажали. -->
        <JobProgressMeter />

        <!-- Состояние живого канала уведомлений и сводка по очереди. Ход
             задания здесь не повторяется: его уже показывает прогресс-мер, а
             два показателя об одном задании разойдутся с ним же. -->
        <div class="syp-live d-flex align-items-center gap-2 flex-wrap">
          <span v-if="queue !== null" class="syp-queue text-body-secondary">
            очередь: {{ queue.summary }}
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
        <!-- Пункт без проекта остаётся видимым и помечается недоступным: раньше
             такие пункты просто исчезали, и оператор не видел, что раздел есть. -->
        <template v-for="section in sections" :key="section.name">
          <RouterLink
            v-if="linkFor(section.name) !== null"
            :to="linkFor(section.name)!"
            class="syp-nav-link"
            :class="{ 'is-current': isActive(section.name) }"
            :title="section.hint"
          >
            {{ section.title }}
          </RouterLink>
          <span
            v-else
            class="syp-nav-link is-off"
            :title="`${section.hint}. Раздел откроется, когда будет выбран проект`"
          >
            {{ section.title }}
          </span>
        </template>
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

.syp-queue {
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

.syp-nav-link.is-off {
  color: var(--syp-text-muted);
  opacity: 0.55;
  cursor: default;
}
.syp-nav-link.is-current {
  border-bottom-color: var(--syp-primary);
  color: var(--syp-primary-dark);
  font-weight: 600;
}
</style>
