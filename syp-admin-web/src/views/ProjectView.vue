<script setup lang="ts">
// Главное окно проекта.
//
// Форма повторяет `project-edit-view` старого проекта: слева проект с его
// параметрами, свойствами и списком файлов, справа выбранный файл с его
// полями, свойствами и дорожками. Сверху меню проекта, действий и базы.
//
// Отличия от старого проекта внесены там, где наша модель устроена иначе, и
// каждое названо прямо, а не спрятано:
//
// * ширина, высота, частоты и кодеки живут в нашей модели **у файла**, а не у
//   проекта, поэтому стоят в правой половине, где в старом проекте стояли
//   поля файла; в левой на их месте написано, чьё это поле;
// * параметров «битрейт видео», «битрейт звука», «контейнер» и «контейнер
//   lossless» в нашей модели нет: сервер их не отдаёт, и подменять их
//   правдоподобными значениями нельзя;
// * раздел «Computer-Depended-Properties» не наполняется: таких свойств в
//   проекте нет, и таблица показывает это, а не пустую таблицу с именем.
//
// Меню повторяет блокировки старого окна: без проекта выключены удаление
// проекта, операции, фильтры и персоны; без выбранного файла — правка планов.

import { formatDuration } from '../format/values'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { useRouter } from 'vue-router'
import DatabaseSelectDialog from '../components/DatabaseSelectDialog.vue'
import {
  deleteProject,
  listVideofile,
  readProject,
  readVideofile,
  type ProjectDetailView,
  type VideofileView,
} from '../api/catalog'
import { deleteProperty, readProperties, writeProperty, type PropertyView } from '../api/properties'

const props = defineProps<{ projectId: string }>()

const router = useRouter()

const project = computed(() => Number(props.projectId))

/** Проект. */
const loaded = ref<ProjectDetailView['project'] | null>(null)

/** Файлы проекта. */
const files = ref<VideofileView[]>([])

/** Выбранный файл, развёрнутые сведения о нём. */
const chosen = ref<VideofileView | null>(null)

/** Свойства проекта. */
const projectProperties = ref<PropertyView[]>([])

/** Свойства выбранного файла. */
const fileProperties = ref<PropertyView[]>([])

/** Ключ нового свойства. */
const propertyKey = ref('')

/** Value нового свойства. */
const propertyValue = ref('')

/** Ошибка чтения или записи. */
const error = ref('')

/** Ответ последнего действия. */
const notice = ref('')

/** Открыто ли окно выбора базы. */
const databaseOpen = ref(false)

/** Ход работы под полосой: в старом окне здесь стоял английский `Label`. */
const progressNote = ref('файлы не перечитывались')

/** Есть ли проект: без него часть меню выключена. */
const hasProject = computed(() => loaded.value !== null)

/** Выбран ли файл: без него правка планов выключена. */
/**
 * Длительность выбранного файла для показа.
 *
 * Сервер отдаёт секунды с полной точностью: 3697.1517916666667. Это и
 * число, и отрезок времени оператору не нужен, поэтому показывается через
 * общий форматтер, как в приёме проектов.
 */
const durationText = computed(() => {
  const value = chosen.value?.durationSeconds
  return value === undefined || value === null ? '—' : formatDuration(value)
})

const hasFile = computed(() => chosen.value !== null)

/**
 * Читает свойства проекта или файла.
 *
 * Отказ не подменяется пустым списком: молчаливый ноль в таблице свойств
 * выглядел бы как «свойств нет», хотя их просто не удалось прочитать.
 *
 * @param kind владелец свойства
 * @param ownerId идентификатор владельца
 * @param target куда класть результат
 */
async function loadProperties(
  kind: 'PROJECT' | 'VIDEOFILE',
  ownerId: number,
  target: { value: PropertyView[] },
): Promise<void> {
  try {
    target.value = await readProperties(kind, ownerId)
  } catch (failure) {
    target.value = []
    error.value = (failure as Error).message
  }
}

/** Выбирает файл и читает его сведения и свойства. */
async function choose(file: VideofileView): Promise<void> {
  chosen.value = file
  notice.value = ''
  try {
    const full = await readVideofile(file.id)
    chosen.value = { ...file, ...full }
    await loadProperties('VIDEOFILE', file.id, fileProperties)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Добавляет свойство к выбранному владельцу. */
async function addProperty(): Promise<void> {
  if (propertyKey.value.trim() === '') {
    notice.value = 'Ключ свойства не задан: добавлять нечего'
    return
  }
  const kind = hasFile.value ? 'VIDEOFILE' : 'PROJECT'
  const ownerId = hasFile.value ? chosen.value?.id : project.value
  if (ownerId === undefined) {
    notice.value = 'Не выбран владелец свойства: добавлять некуда'
    return
  }
  try {
    await writeProperty(kind, ownerId, propertyKey.value.trim(), propertyValue.value)
    propertyKey.value = ''
    propertyValue.value = ''
    notice.value = `свойство «${propertyKey.value}» сохранено`
    await loadProperties(kind, ownerId, hasFile.value ? fileProperties : projectProperties)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/**
 * Удаляет свойство.
 *
 * @param item удаляемое свойство
 */
async function removeProperty(item: PropertyView): Promise<void> {
  const kind = hasFile.value ? 'VIDEOFILE' : 'PROJECT'
  const ownerId = hasFile.value ? chosen.value?.id : project.value
  if (ownerId === undefined) {
    return
  }
  try {
    await deleteProperty(kind, ownerId, item.key)
    notice.value = `свойство «${item.key}» удалено`
    await loadProperties(kind, ownerId, hasFile.value ? fileProperties : projectProperties)
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Удаляет проект: подтверждается прямо здесь, второго диалога в старой форме не было. */
async function removeProject(): Promise<void> {
  if (loaded.value === null) {
    return
  }
  try {
    await deleteProject(loaded.value.id)
    router.push({ name: 'intake' })
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Открывает редактор планов выбранного файла. */
function editShots(): void {
  if (chosen.value === null) {
    return
  }
  router.push({ name: 'editor', params: { videofileId: String(chosen.value.id) } })
}

/** Открывает операции над проектом. */
function projectActions(): void {
  router.push({ name: 'actions', params: { projectId: String(project.value) } })
}

/** Открывает редактор фильтров. */
function editFilters(): void {
  router.push({ name: 'filters', params: { projectId: String(project.value) } })
}

/** Открывает персоны: они живут на вкладке «Персоны» главного редактора. */
function editPersons(): void {
  if (chosen.value === null) {
    notice.value = 'Выберите файл: персоны открываются для его планов'
    return
  }
  router.push({ name: 'editor', params: { videofileId: String(chosen.value.id) } })
}

/**
 * Получает дорожки файла из сведений о нём.
 *
 * Кнопка в старом проекте запускала MediaInfo по файлу. У нас дорожки уже
 * лежат в сведениях о видеофайле, и отдельного запуска не требуется, поэтому
 * кнопка просто перечитывает файл и говорит, что получила.
 */
async function getTracks(): Promise<void> {
  if (chosen.value === null) {
    notice.value = 'Файл не выбран: дорожки смотреть нечего'
    return
  }
  await choose(chosen.value)
  notice.value = `дорожек у файла: ${(chosen.value?.tracks ?? []).length}. Отдельный запуск MediaInfo не нужен: дорожки приходят в сведениях о файле`
}

onMounted(async () => {
  try {
    loaded.value = (await readProject(project.value)).project
    files.value = await listVideofile(project.value)
    await loadProperties('PROJECT', project.value, projectProperties)
    if (files.value.length > 0) {
      await choose(files.value[0])
    }
    progressNote.value = `файлов в проекте: ${files.value.length}`
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
})
</script>

<template>
  <section class="main">
    <h1 class="syp-page-title">Project</h1>

    <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

    <!-- Меню главного окна. Блокировки повторяют старые: без проекта выключены
         операции, фильтры и персоны, без файла — правка планов. -->
    <nav class="menu" aria-label="Project menu">
      <RouterLink :to="{ name: 'intake' }" class="menu-item">Open project</RouterLink>
      <button type="button" class="menu-item danger" :disabled="!hasProject" @click="removeProject">
        Delete the project
      </button>
      <button type="button" class="menu-item" :disabled="!hasProject" @click="projectActions">
        Project actions
      </button>
      <button type="button" class="menu-item" :disabled="!hasFile" @click="editShots">
        Editing shots
      </button>
      <button type="button" class="menu-item" :disabled="!hasProject" @click="editFilters">
        Editing filters
      </button>
      <button type="button" class="menu-item" :disabled="!hasProject" @click="editPersons">
        Editing persons
      </button>
      <button type="button" class="menu-item" @click="databaseOpen = true">
        Choose a database
      </button>
    </nav>

    <div class="panes">
      <!-- Левая половина: проект и файлы -->
      <div class="pane">
        <div class="syp-card-title">Project</div>
        <div class="line">
          <label for="p-name">Name:</label>
          <input
            id="p-name"
            class="form-control form-control-sm"
            type="text"
            :value="loaded?.name ?? ''"
            disabled
          />
        </div>
        <div class="line">
          <label for="p-short">Short name:</label>
          <input
            id="p-short"
            class="form-control form-control-sm"
            type="text"
            value="the project has no such field"
            disabled
          />
        </div>
        <div class="line">
          <label for="p-folder">Folder:</label>
          <input
            id="p-folder"
            class="form-control form-control-sm"
            type="text"
            :value="loaded?.sourceRoot ?? ''"
            disabled
          />
        </div>

        <!-- Параметры видео и звука в старом окне belonged к проекту, а в
             нашей модели принадлежат файлу. Здесь стоит именно это, а не
             правдоподобные значения: подставить их значило бы выдать чужое
             поле за наше. -->
        <div class="syp-card-title">Video and audio parameters</div>
        <p class="absent">
          In our model width, height, frame rate and codecs are properties of the
          <strong>file</strong>, not of the project, so they are shown on the right. Video and audio
          bitrates, along with the container and the lossless container, are not served by the
          server: the project has no such fields.
        </p>

        <div class="syp-card-title">Properties</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>Key</th>
              <th>Value</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="projectProperties.length === 0">
              <td colspan="3" class="empty">no project properties</td>
            </tr>
            <tr v-for="item in projectProperties" :key="item.key">
              <td>{{ item.key }}</td>
              <td>{{ item.value }}</td>
              <td>
                <button
                  type="button"
                  class="row-del"
                  title="Delete property"
                  @click="removeProperty(item)"
                >
                  ×
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="line">
          <input
            v-model="propertyKey"
            class="form-control form-control-sm"
            type="text"
            placeholder="property key"
          />
          <input
            v-model="propertyValue"
            class="form-control form-control-sm"
            type="text"
            placeholder="value"
          />
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="addProperty">
            Add
          </button>
        </div>

        <div class="syp-card-title">Machine dependent properties</div>
        <p class="absent">
          The project has no such properties: the operator machine does not affect the markup.
        </p>

        <div class="syp-card-title">Files</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th class="num">#</th>
              <th>File</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="file in files"
              :key="file.id"
              :class="{ picked: chosen?.id === file.id }"
              @click="choose(file)"
            >
              <td class="num">{{ file.ordinal }}</td>
              <td>{{ file.name }}</td>
            </tr>
          </tbody>
        </table>
        <progress class="files-progress" :value="files.length" :max="Math.max(files.length, 1)" />
        <span class="progress-note">{{ progressNote }}</span>
      </div>

      <!-- Правая половина: выбранный файл -->
      <div class="pane">
        <div class="syp-card-title">File</div>
        <div class="line">
          <label for="f-name">Name:</label>
          <input
            id="f-name"
            class="form-control form-control-sm"
            type="text"
            :value="chosen?.name ?? ''"
            disabled
          />
        </div>
        <div class="line">
          <label for="f-short">Label:</label>
          <input
            id="f-short"
            class="form-control form-control-sm"
            type="text"
            :value="chosen?.designation ?? ''"
            disabled
          />
        </div>
        <div class="line">
          <label for="f-path">Path:</label>
          <input
            id="f-path"
            class="form-control form-control-sm"
            type="text"
            :value="chosen?.sourcePath ?? ''"
            disabled
          />
        </div>

        <div class="syp-card-title">Parameters</div>
        <div class="grid">
          <span>Width, pixels</span><span>{{ chosen?.width ?? '—' }}</span>
          <span>Height, pixels</span><span>{{ chosen?.height ?? '—' }}</span> <span>Frame rate</span
          ><span>{{ chosen?.frameRate ?? '—' }}</span> <span>Video codec</span
          ><span>{{ chosen?.videoCodec ?? '—' }}</span> <span>Audio codec</span
          ><span>{{ chosen?.audioCodec ?? '—' }}</span> <span>Audio rate, Hz</span
          ><span>{{ chosen?.audioSampleRate ?? '—' }}</span> <span>Frames</span
          ><span>{{ chosen?.frameCount ?? '—' }}</span> <span>Duration</span
          ><span>{{ durationText }}</span>
        </div>

        <div class="syp-card-title">File properties</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>Key</th>
              <th>Value</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="fileProperties.length === 0">
              <td colspan="3" class="empty">no file properties</td>
            </tr>
            <tr v-for="item in fileProperties" :key="item.key">
              <td>{{ item.key }}</td>
              <td>{{ item.value }}</td>
              <td>
                <button
                  type="button"
                  class="row-del"
                  title="Delete property"
                  @click="removeProperty(item)"
                >
                  ×
                </button>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="syp-card-title">Tracks</div>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="getTracks">
          Fetch the file tracks
        </button>
        <table class="table table-sm">
          <thead>
            <tr>
              <th class="num">#</th>
              <th>Type</th>
              <th>Codec</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="(chosen?.tracks ?? []).length === 0">
              <td colspan="3" class="empty">no tracks</td>
            </tr>
            <tr v-for="track in chosen?.tracks ?? []" :key="track.index">
              <td class="num">{{ track.ordinal }}</td>
              <td>{{ track.codecType }}</td>
              <td>{{ track.codecName }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

    <DatabaseSelectDialog v-if="databaseOpen" @closed="databaseOpen = false" />
  </section>
</template>

<style scoped>
.menu {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-bottom: 0.75rem;
  padding-bottom: 0.4rem;
  border-bottom: 1px solid var(--syp-border);
}
.menu-item {
  border: none;
  background: none;
  color: var(--syp-link);
  font-size: 0.875rem;
  padding: 0.25rem 0.5rem;
  text-decoration: none;
  border-radius: 0.25rem;
}
.menu-item:hover:not(:disabled) {
  background: var(--syp-raised);
}
.menu-item:disabled {
  color: var(--syp-text-muted);
  cursor: default;
}
.menu-item.danger {
  color: var(--syp-danger);
}
.panes {
  display: flex;
  gap: 1rem;
  align-items: flex-start;
}
.pane {
  flex: 1 1 0;
  min-width: 0;
}
.line {
  display: flex;
  gap: 0.4rem;
  align-items: center;
  margin: 0.25rem 0;
}
.line label {
  flex: 0 0 8.5rem;
  font-size: 0.8125rem;
}
.line input {
  min-width: 0;
}
.absent {
  font-size: 0.78rem;
  color: var(--syp-text-muted);
  background: var(--syp-raised);
  border: 1px solid var(--syp-border);
  border-radius: 0.3rem;
  padding: 0.4rem 0.5rem;
}
.grid {
  display: grid;
  grid-template-columns: 12rem 1fr;
  gap: 0.15rem 0.5rem;
  font-size: 0.8125rem;
  margin-bottom: 0.5rem;
}
.grid span:nth-child(odd) {
  color: var(--syp-text-muted);
}
.table td,
.table th {
  padding: 0.15rem 0.3rem;
  font-size: 0.8125rem;
}
.table tbody tr.picked {
  background: var(--syp-tint);
}
.num {
  text-align: right;
}
.empty {
  text-align: center;
  color: var(--syp-text-muted);
}
.row-del {
  border: none;
  background: none;
  color: var(--syp-danger);
}
.files-progress {
  width: 100%;
  height: 0.8rem;
}
.progress-note {
  font-size: 0.78rem;
  color: var(--syp-text-muted);
}
</style>
