// Экран приёма проектов и видеофайлов (задача T037). // // Экран закрывает три требования: оператор
создаёт проект с корнем // каталога, добавляет видеофайл указанием пути и видит параметры, которые
// определила система сама. Отдельно показывается внятная ошибка при // недоступном файле — «успех с
пустым результатом» на экране выглядел бы // как «видеофайл заведена», а на деле файл не был
прочитан (FR-092).

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import ProjectSelectDialog from '../components/ProjectSelectDialog.vue'
import DatabaseSelectDialog from '../components/DatabaseSelectDialog.vue'
import { formatBytes, formatDuration } from '../format/values'

const store = useCatalogStore()
const projectOpen = ref(false)

/**
 * Открывает главное окно выбранного проекта.
 *
 * Проект сначала выбирается в состоянии: главное окно читает его и список
 * файлов, и без выбора пришло бы пустым.
 *
 * @param projectId идентификатор проекта
 */
async function pickProject(projectId: number): Promise<void> {
  await store.openProject(projectId)
  await router.push({ name: 'project', params: { projectId: String(projectId) } })
}

/** Открыто ли окно выбора базы данных. */
const databaseOpen = ref(false)
const router = useRouter()

const projectName = ref('')
const projectRoot = ref('')
const videofilePath = ref('')
const videofileName = ref('')

onMounted(() => {
  void store.reloadProjects()
})

/**
 * Создаёт проект по введённым названию и корню каталога.
 *
 * @returns `true`, если проект создан
 */
async function submitProject(): Promise<boolean> {
  return store.addProject(projectName.value.trim(), projectRoot.value.trim())
}

/**
 * Регистрирует видеофайл по введённому пути к файлу.
 *
 * @returns `true`, если видеофайл зарегистрирована
 */
async function submitVideofile(): Promise<boolean> {
  const created = await store.addVideofile(videofilePath.value.trim(), videofileName.value.trim())
  if (created) {
    videofilePath.value = ''
    videofileName.value = ''
  }
  return created
}

/**
 * Открывает экран состояния суммы для видеофайла.
 *
 * @param videofileId идентификатор видеофайла
 */
function openChecksum(videofileId: number): void {
  void router.push({ name: 'checksum', params: { videofileId: String(videofileId) } })
}

/**
 * Открывает экран сцен и планов видеофайла.
 *
 * Переход живёт рядом с «суммой» не украшением, а потому что иначе to сцен
 * можно было добраться только вкладкой в шапке, а эта вкладка появляется лишь
 * после выбора проекта. Оператору сценарий «открыл проект — посмотрел сцены»
 * обрывался, и найти экран было нечем.
 *
 * @param videofileId видеофайл
 */
function openStructure(videofileId: number): void {
  void router.push({ name: 'structure', params: { videofileId: String(videofileId) } })
}

/**
 * Открывает экран лиц видеофайла.
 *
 * @param videofileId видеофайл
 */
function openFaces(videofileId: number): void {
  void router.push({ name: 'faces', params: { videofileId: String(videofileId) } })
}
</script>

<template>
  <section class="intake">
    <div class="intake-head">
      <h2>Intake of projects and videofiles</h2>
      <!-- В старом проекте выбор базы данных жил в меню главного окна, а
           главное окно было экраном приёма проектов; кнопка оставлена здесь. -->
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="databaseOpen = true">
        База данных
      </button>
    </div>

    <ProjectSelectDialog v-if="projectOpen" @chosen="pickProject" @closed="projectOpen = false" />
    <DatabaseSelectDialog v-if="databaseOpen" @closed="databaseOpen = false" />

    <p v-if="store.loading.value" class="note">Request to the backend…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">hide</button>
    </p>

    <fieldset>
      <legend>Projects</legend>
      <table>
        <thead>
          <tr>
            <th>Title</th>
            <th>Catalog root</th>
            <th>Videofiles</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="project in store.projects.value" :key="project.id">
            <td>{{ project.name }}</td>
            <td class="path">
              {{ project.sourceRoot }}
            </td>
            <td>{{ project.videofileCount }}</td>
            <td>
              <!-- Кнопка «open» выбирает проект в состоянии и открывает
                   главное окно: в старом проекте `Open…` из меню вёл именно
                   в него, а не в список файлов. -->
              <button type="button" @click="projectOpen = true">open</button>
            </td>
          </tr>
          <tr v-if="store.projects.value.length === 0">
            <td colspan="4" class="note">No projects yet</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input v-model="projectName" type="text" placeholder="Project title" />
        <input
          v-model="projectRoot"
          type="text"
          placeholder="Catalog root, for example /disks/HDD_16Tb_Clouds/GOT"
        />
        <button type="button" :disabled="store.loading.value" @click="submitProject">
          create a project
        </button>
      </div>
      <p class="note">
        The root is required: the build script addresses files by paths relative to it, and a
        relative path outside the root would be made up.
      </p>
    </fieldset>

    <fieldset v-if="store.current.value">
      <legend>Videofiles of the project {{ store.current.value.project.name }}</legend>
      <table>
        <thead>
          <tr>
            <th>Title</th>
            <th>Path</th>
            <th>Frames</th>
            <th>Resolution</th>
            <th>Frame rate</th>
            <th>Duration</th>
            <th>Size</th>
            <th>Key frames</th>
            <th>Go</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="videofile in store.videofile.value" :key="videofile.id">
            <td>{{ videofile.name }}</td>
            <td class="path">
              {{ videofile.relativePath ?? videofile.sourcePath }}
            </td>
            <td>{{ videofile.frameCount.toLocaleString('ru-RU') }}</td>
            <td>{{ videofile.width }}×{{ videofile.height }}</td>
            <td>{{ videofile.frameRate }}</td>
            <td>{{ formatDuration(videofile.durationSeconds) }}</td>
            <td>{{ formatBytes(videofile.byteSize, 'B', 0) }}</td>
            <td>{{ videofile.keyframeCount }}</td>
            <td class="go">
              <button type="button" @click="openChecksum(videofile.id)">sum</button>
              <button type="button" @click="openStructure(videofile.id)">scenes</button>
              <button type="button" @click="openFaces(videofile.id)">faces</button>
            </td>
          </tr>
          <tr v-if="store.videofile.value.length === 0">
            <td colspan="9" class="note">No videofiles yet</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input
          v-model="videofilePath"
          type="text"
          placeholder="Path to the videofile inside the project root"
        />
        <input v-model="videofileName" type="text" placeholder="Videofile name (optional)" />
        <button
          type="button"
          :disabled="!store.canRegisterVideofile.value || store.loading.value"
          @click="submitVideofile"
        >
          add videofile
        </button>
      </div>
      <p class="note">
        The system determines the file parameters: the operator does not enter them. The path must
        lie inside the project root, otherwise a <code>SOURCE_UNREADABLE</code> failure comes with
        the path in the text.
      </p>
    </fieldset>
  </section>
</template>

<style scoped>
.intake-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.intake {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

fieldset {
  border: 1px solid var(--syp-border-control);
  padding: 0.75rem 1rem 1rem;
}

legend {
  font-weight: 600;
}

table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 0.75rem;
}

th,
td {
  border-bottom: 1px solid var(--syp-text-muted);
  padding: 0.35rem 0.5rem;
  text-align: left;
  font-size: 0.9rem;
}

.path {
  font-family: monospace;
  font-size: 0.8rem;
  word-break: break-all;
}

.form {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

input {
  flex: 1 1 18rem;
  padding: 0.35rem 0.5rem;
}

.error {
  border-left: 3px solid var(--syp-danger);
  padding-left: 0.5rem;
}

.code {
  font-family: monospace;
  margin-right: 0.5rem;
}

.note {
  color: var(--syp-text-muted);
  font-size: 0.85rem;
}
</style>
