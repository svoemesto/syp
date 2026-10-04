// Экран приёма проектов и видеофайлов (задача T037). // // Экран закрывает три требования: оператор
создаёт проект с корнем // каталога, добавляет видеофайл указанием пути и видит параметры, которые
// определила система сама. Отдельно показывается внятная ошибка при // недоступном файле — «успех с
пустым результатом» на экране выглядел бы // как «видеофайл заведена», а на деле файл не был
прочитан (FR-092).

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import DatabaseSelectDialog from '../components/DatabaseSelectDialog.vue'
import { formatBytes, formatDuration } from '../format/values'

const store = useCatalogStore()

/**
 * Открывает главное окно выбранного проекта.
 *
 * Проект сначала выбирается в состоянии: главное окно читает его и список
 * файлов, и без выбора пришло бы пустым.
 *
 * @param projectId идентификатор проекта
 */
async function openMainWindow(projectId: number): Promise<void> {
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
 * Переход живёт рядом с «суммой» не украшением, а потому что иначе до сцен
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
      <h2>Приём проектов и видеофайлов</h2>
      <!-- В старом проекте выбор базы данных жил в меню главного окна, а
           главное окно было экраном приёма проектов; кнопка оставлена здесь. -->
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="databaseOpen = true">
        База данных
      </button>
    </div>

    <DatabaseSelectDialog v-if="databaseOpen" @closed="databaseOpen = false" />

    <p v-if="store.loading.value" class="note">Запрос к бэкенду…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">скрыть</button>
    </p>

    <fieldset>
      <legend>Проекты</legend>
      <table>
        <thead>
          <tr>
            <th>Название</th>
            <th>Корень каталога</th>
            <th>Видеофайлов</th>
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
              <!-- Кнопка «открыть» выбирает проект в состоянии и открывает
                   главное окно: в старом проекте `Open…` из меню вёл именно
                   в него, а не в список файлов. -->
              <button type="button" @click="openMainWindow(project.id)">открыть</button>
            </td>
          </tr>
          <tr v-if="store.projects.value.length === 0">
            <td colspan="4" class="note">Проектов пока нет</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input v-model="projectName" type="text" placeholder="Название проекта" />
        <input
          v-model="projectRoot"
          type="text"
          placeholder="Корень каталога, например /disks/HDD_16Tb_Clouds/GOT"
        />
        <button type="button" :disabled="store.loading.value" @click="submitProject">
          создать проект
        </button>
      </div>
      <p class="note">
        Корень обязателен: сценарий сборки обращается к файлам по путям относительно него, и
        относительный путь вне корня был бы выдуманным.
      </p>
    </fieldset>

    <fieldset v-if="store.current.value">
      <legend>Видеофайла проекта «{{ store.current.value.project.name }}»</legend>
      <table>
        <thead>
          <tr>
            <th>Название</th>
            <th>Путь</th>
            <th>Кадров</th>
            <th>Разрешение</th>
            <th>Частота кадров</th>
            <th>Длительность</th>
            <th>Размер</th>
            <th>Ключевых кадров</th>
            <th>Перейти</th>
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
            <td>{{ formatBytes(videofile.byteSize, 'байт', 0) }}</td>
            <td>{{ videofile.keyframeCount }}</td>
            <td class="go">
              <button type="button" @click="openChecksum(videofile.id)">сумма</button>
              <button type="button" @click="openStructure(videofile.id)">сцены</button>
              <button type="button" @click="openFaces(videofile.id)">лица</button>
            </td>
          </tr>
          <tr v-if="store.videofile.value.length === 0">
            <td colspan="9" class="note">Видеофайлов пока нет</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input
          v-model="videofilePath"
          type="text"
          placeholder="Путь к файлу видеофайла внутри корня проекта"
        />
        <input
          v-model="videofileName"
          type="text"
          placeholder="Название видеофайла (необязательно)"
        />
        <button
          type="button"
          :disabled="!store.canRegisterVideofile.value || store.loading.value"
          @click="submitVideofile"
        >
          добавить видеофайл
        </button>
      </div>
      <p class="note">
        Параметры файла определяет система: оператор их не вводит. Путь обязан лежать внутри корня
        проекта, иначе придёт отказ <code>SOURCE_UNREADABLE</code> с путём в тексте.
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
