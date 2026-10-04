<script setup lang="ts">
/**
 * Выбор проекта — форма `project-select`.
 *
 * Раскладка взята из формы: заголовок `DB` по центру, таблица с колонками
 * `#` и `Проект`, кнопки `OK` и `Отмена` во всю ширину, форма шириной
 * 400 px. Колонка `#` объявлена шириной 40 px.
 *
 * Четырёх кнопок перемещения из формы здесь нет: порядок проектов нигде не
 * хранится, ни поля порядка в таблице, ни эндпоинта перестановки. Кнопки,
 * которые ни на что не влияют, были бы видимостью работы без работы.
 */
import { onMounted, ref } from 'vue'
import { listProjects, type ProjectView } from '../api/catalog'

const emit = defineEmits<{ chosen: [projectId: number]; closed: [] }>()

const projects = ref<ProjectView[]>([])
const chosen = ref<number | null>(null)
const error = ref('')

function accept(): void {
  if (chosen.value !== null) {
    emit('chosen', chosen.value)
  }
}

onMounted(async () => {
  try {
    projects.value = await listProjects()
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <div class="project-dialog" role="dialog" aria-label="DB">
    <div class="dialog-body">
      <!-- Заголовок формы — надпись DB, а не название раздела. -->
      <h2 class="syp-card-title">DB</h2>

      <table class="table table-sm projects-table">
        <thead>
          <tr>
            <th class="num">#</th>
            <th>Проект</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(project, index) in projects"
            :key="project.id"
            :class="{ selected: chosen === project.id }"
            @click="chosen = project.id"
            @dblclick="accept"
          >
            <td class="num">{{ index + 1 }}</td>
            <td>{{ project.name }}</td>
          </tr>
          <tr v-if="projects.length === 0">
            <td colspan="2" class="empty">No content in table</td>
          </tr>
        </tbody>
      </table>

      <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

      <div class="dialog-actions">
        <button type="button" class="btn btn-primary" @click="accept">OK</button>
        <button type="button" class="btn btn-outline-secondary" @click="emit('closed')">
          Отмена
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.project-dialog {
  background: var(--syp-surface);
  border: 1px solid var(--syp-border);
  border-radius: 0;
  box-shadow: 0 0 20rem rgb(0 0 0 / 25%);
}

.dialog-body {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  /* Форма объявлена 400 на 400. */
  padding: 0.25rem;
  width: 25rem;
}

.syp-card-title {
  text-align: center;
  font-size: 1rem;
  margin: 0;
}

.projects-table {
  table-layout: fixed;
  margin-bottom: 0;
  flex: 1 1 auto;
}

.projects-table thead th {
  /* В форме колонка подписана `Проект`, а не `ПРОЕКТ`. */
  text-transform: none;
}

.projects-table .num {
  /* Колонка объявлена шириной 40 px. */
  width: 2.5rem;
}

.empty {
  color: var(--syp-muted);
  text-align: center;
}

.dialog-actions {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.dialog-actions .btn {
  width: 100%;
}
</style>
