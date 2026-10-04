// Экран лиц видеофайла (задача T072). // // Экран отвечает на два вопроса оператора: «кого и
сколько здесь найдено» и // «кто это». Первый вопрос — про найденные лица, второй — про кластеры: //
похожие лица группируются, и кластеру даётся имя. Обе задачи решаются // здесь, потому что до
обучения модели это единственный способ узнать имена. // // Неопознанные лица **показываются и не
пропадают**: «распознано, имя не // подтверждено» и «не лицо» — это заглушки, а не ошибка (Р-12,
FR-036). Если // бы они скрывались, оператор не видел бы, что детектор где-то ошибся.

<script setup lang="ts">
import PersonsPanel from '../components/PersonsPanel.vue'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { readFacesByIds, type FaceView } from '../api/characters'
import FaceThumbnails from '../components/FaceThumbnails.vue'
import {
  type FaceClustersView,
  type FacesView,
  type PersonView,
  type PersonsView,
  deletePerson,
  nameCluster,
  readClusters,
  readFaces,
  readPersons,
  renamePerson,
} from '../api/characters'

const route = useRoute()

/** Страница лиц видеофайла. */
const faces = ref<FacesView | null>(null)

/** Кластеры видеофайла без имени. */
const clusters = ref<FaceClustersView | null>(null)

/** Лица кластеров, загруженные по их идентификаторам, для миниатюр. */
const clusterFaces = ref<Record<string, FaceView[]>>({})

/**
 * Загружает лица для показанных кластеров.
 *
 * У кластера в ответе есть только идентификаторы лиц, поэтому за ними нужно
 * идти отдельно. Берётся не больше `CLUSTER_FACES_AT_ONCE` лиц на кластер:
 * страница с двумя тысячами миниатюр оператору не помогает.
 */
const CLUSTER_FACES_AT_ONCE = 4

/**
 * Ширина кадра для миниатюр кластера.
 *
 * Миниатюр на странице кластеров немного — четыре на кластер, — поэтому
 * кадр берётся во всю ширину: лицо выходит резким.
 */
const CLUSTER_SOURCE_WIDTH = 1920

async function loadClusterFaces(videofileId: number): Promise<void> {
  const list = clusters.value?.clusters ?? []
  if (list.length === 0) {
    clusterFaces.value = {}
    return
  }
  const wanted = list.flatMap((cluster) => cluster.faceIds.slice(0, CLUSTER_FACES_AT_ONCE))
  try {
    const view = await readFacesByIds(videofileId, [...new Set(wanted)])
    const byId: Record<string, FaceView[]> = {}
    for (const cluster of list) {
      const ids = new Set(cluster.faceIds.slice(0, CLUSTER_FACES_AT_ONCE).map(String))
      byId[cluster.id] = view.faces.filter((face) => ids.has(String(face.id)))
    }
    clusterFaces.value = byId
  } catch {
    // Миниатюры не обязательны: без них кластер остаётся списком с числом лиц.
    clusterFaces.value = {}
  }
}

/** Персоны проекта. */
/**
 * Фильтр лиц по метке эталона: все, эталоны, не эталоны.
 *
 * В старом проекте здесь четыре чекбокса: «не эталон», «эталон», «не ручное»,
 * «ручное». Три переносятся один в один, четвёртый — нет: ручных лиц в нашей
 * модели нет, создавать лицо вручную пока нечем, и фильтровать не по чему.
 */
type ExampleFilter = 'all' | 'example' | 'not-example'

const exampleFilter = ref<ExampleFilter>('all')

const persons = ref<PersonsView | null>(null)

/** Текст ошибки для оператора. */
const error = ref<string | null>(null)

/** Сообщение об успешном действии. */
const notice = ref<string | null>(null)

/** Идёт ли загрузка. */
const loading = ref(false)

/** Ключ наименуемого кластера. */
const namingCluster = ref<string | null>(null)

/** Вводимое имя персоны для кластера. */
const clusterName = ref('')

/** Ключ переименовываемой персоны. */
const renamingPerson = ref<number | null>(null)

/** Вводимое новое имя персоны. */
const personName = ref('')

/** Смещение выборки лиц. */
const offset = ref(0)

/** Размер выборки лиц. */
const limit = 200

/** Идентификатор видеофайла из адреса. */
const videofileId = computed(() => Number(route.params.videofileId))

/** Лица текущей страницы. */
const faceList = computed(() => faces.value?.faces ?? [])

/** Есть ли следующая страница лиц. */
const hasNextPage = computed(() => {
  const value = faces.value
  return value !== null && value.offset + value.limit < value.facesTotal
})

/** Есть ли предыдущая страница лиц. */
const hasPreviousPage = computed(() => (faces.value?.offset ?? 0) > 0)

/**
 * Лица видеофайла, сгруппированные по персонам.
 *
 * Группировка нужна оператору, а не системе: ответ «кто в кадре» читается
 * по персонам, и человек с одиннадцатью тысячами неопознанных лиц подряд
 * ничего в нём не найдёт.
 */
/**
 * Самое крупное лицо каждой персоны на странице.
 *
 * По нему оператор ставит фото персоны: на кадре с самым крупным лицом человек
 * виден лучше всего.
 */
const biggestFaces = computed(() => {
  const out: Record<number, { frameNumber: number }> = {}
  for (const [personId, group] of facesByPerson.value) {
    const biggest = [...group.faces].sort(
      (a, b) => (b.x2 - b.x1) * (b.y2 - b.y1) - (a.x2 - a.x1) * (a.y2 - a.y1),
    )[0]
    if (biggest) {
      out[personId] = { frameNumber: biggest.frameNumber }
    }
  }
  return out
})

const facesByPerson = computed(() => {
  const groups = new Map<number, { name: string; kind: string; faces: FacesView['faces'] }>()
  const wanted = faceList.value.filter((face) => {
    if (exampleFilter.value === 'example') {
      return face.isExample
    }
    if (exampleFilter.value === 'not-example') {
      return !face.isExample
    }
    return true
  })
  for (const face of wanted) {
    const group = groups.get(face.personId) ?? {
      name: face.personName,
      kind: face.personKind,
      faces: [],
    }
    group.faces.push(face)
    groups.set(face.personId, group)
  }
  return [...groups.entries()].sort((a, b) => b[1].faces.length - a[1].faces.length)
})

/**
 * Персоны проекта без служебных заглушек.
 *
 * Заглушки в списке переименовывать и удалять нельзя, поэтому в правке они не
 * показываются вовсе: кнопка, которая всегда отвергается, в интерфейсе
 * вредна.
 */
const namedPersons = computed(() =>
  (persons.value?.persons ?? []).filter((person) => !person.isService),
)

onMounted(() => {
  void reload()
})

watch(videofileId, () => {
  void reload()
})

/**
 * Перечитывает лица, кластеры и персон видеофайла.
 *
 * @returns ничего
 */
async function reload(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const loaded = await readFaces(videofileId.value, offset.value, limit)
    faces.value = loaded
    clusters.value = await readClusters(videofileId.value)
    await loadClusterFaces(videofileId.value)
    persons.value = await readPersons(loaded.projectId)
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  } finally {
    loading.value = false
  }
}

/**
 * Начинает наименование кластера.
 *
 * Обработчик вынесен в функцию, а не записан в шаблоне двумя действиями подряд:
 * форматировщик переносит такое выражение по строкам, а разборщик шаблонов
 * Vue многострочное выражение в атрибуте не принимает.
 *
 * @param clusterId ключ наименуемого кластера
 */
function startNaming(clusterId: string): void {
  namingCluster.value = clusterId
  clusterName.value = ''
}

/**
 * Начинает переименование персоны.
 *
 * @param person переименовываемая персона
 */
function startRenaming(person: PersonView): void {
  renamingPerson.value = person.id
  personName.value = person.name
}

/**
 * Сообщает о пометке эталона.
 *
 * Отдельная подпись нужна, потому что метка неочевидна: оператор должен видеть,
 * что действие состоялось, иначе «эталон» выглядит как декоративная кнопка.
 *
 * @param payload помеченное лицо и число изменившихся лиц
 */
function onExample(payload: { faceId: number; marked: boolean; changed: number }): void {
  error.value = null
  notice.value = payload.marked
    ? `Лицо ${payload.faceId} помечено эталоном: на нём модель будет учиться узнавать этого человека`
    : `Метка эталона снята с лица ${payload.faceId}`
}

/**
 * Даёт кластеру имя.
 *
 * @param clusterId ключ кластера
 * @returns ничего
 */
async function giveName(clusterId: string): Promise<void> {
  error.value = null
  notice.value = null
  const name = clusterName.value.trim()
  if (name === '') {
    error.value = 'Имя персоны обязательно: кластер без имени остаётся безымянным'
    return
  }
  try {
    const named = await nameCluster(clusterId, name)
    notice.value = `Кластеру дано имя «${named.name}», лиц в нём: ${named.facesAssigned}`
    namingCluster.value = null
    clusterName.value = ''
    await reload()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

/**
 * Переименовывает персону.
 *
 * @param person персона
 * @returns ничего
 */
async function saveName(person: PersonView): Promise<void> {
  error.value = null
  notice.value = null
  const name = personName.value.trim()
  if (name === '') {
    error.value = 'Имя персоны обязательно'
    return
  }
  try {
    await renamePerson(person.id, name)
    notice.value = `Персона переименована в «${name}»`
    renamingPerson.value = null
    personName.value = ''
    await reload()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

/**
 * Удаляет именованную персону.
 *
 * Лица при этом не удаляются: они переходят в неопознанные (FR-036). Экран
 * говорит об этом прямо, потому что удаление без предупреждения выглядело
 * бы как потеря разметки.
 *
 * @param person персона
 * @returns ничего
 */
async function remove(person: PersonView): Promise<void> {
  error.value = null
  notice.value = null
  try {
    await deletePerson(person.id)
    notice.value = `Персона «${person.name}» удалена; её лица перешли в неопознанные и не потеряны`
    await reload()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

/**
 * Переходит на страницу лиц.
 *
 * @param delta смещение относительно текущей страницы
 * @returns ничего
 */
async function turnPage(delta: number): Promise<void> {
  offset.value = Math.max(0, offset.value + delta)
  await reload()
}

/**
 * Пояснение вида персоны словами.
 *
 * @param kind вид персоны
 * @returns текст для оператора
 */
function personKindTitle(kind: string): string {
  switch (kind) {
    case 'PERSON':
      return 'именованная персона'
    case 'UNRECOGNIZED':
      return 'лицо найдено, имя не подтверждено оператором'
    case 'NONPERSON':
      return 'рамка оказалась не лицом'
    default:
      return kind
  }
}
</script>

<template>
  <section class="faces">
    <h2>Лица видеофайла</h2>

    <p v-if="loading" class="note">Запрос к бэкенду…</p>

    <p v-if="error" class="error" role="alert">
      {{ error }}
      <button type="button" @click="error = null">скрыть</button>
    </p>

    <p v-if="notice" class="notice" role="status">
      {{ notice }}
      <button type="button" @click="notice = null">скрыть</button>
    </p>

    <p v-if="faces" class="state">
      Лиц найдено: {{ faces.facesTotal }} на странице показано {{ faceList.length }}. Разрешение
      кадра: {{ faces.frameWidth }}×{{ faces.frameHeight }}.
    </p>

    <p v-if="clusters" class="state">
      Кластеров без имени: {{ clusters.clustersTotal }}
      <span v-if="clusters.embeddingModelKey">
        (ключ модели эмбеддингов: {{ clusters.embeddingModelKey }}).
      </span>
    </p>

    <p v-if="faces && faces.facesTotal === 0" class="note">
      Лиц не найдено. Это не «лиц нет в видеофайле»: детекция могла не выполняться или оборваться.
      Проверьте задание <code>FACES</code> в очереди.
    </p>

    <nav class="face-filters" aria-label="Фильтр лиц по метке эталона">
      <label><input v-model="exampleFilter" type="radio" value="all" /> все</label>
      <label><input v-model="exampleFilter" type="radio" value="example" /> эталоны</label>
      <label><input v-model="exampleFilter" type="radio" value="not-example" /> не эталоны</label>
    </nav>

    <section v-if="clusters && clusters.clusters.length > 0" class="clusters">
      <h3>Кластеры похожих лиц без имени</h3>
      <p class="note">
        Кластер — это группа похожих лиц, которой ещё не дано имя. Дайте имя — и лица кластера
        получат его. Кластер строится до обучения модели, по векторам признаков.
      </p>
      <ul class="cluster-list">
        <li v-for="cluster in clusters.clusters" :key="cluster.id" class="cluster">
          <span class="cluster-faces">
            <FaceThumbnails
              v-if="(clusterFaces[cluster.id] ?? []).length > 0"
              :videofile-id="videofileId"
              :source-width="CLUSTER_SOURCE_WIDTH"
              :faces="clusterFaces[cluster.id] ?? []"
              :frame-width="faces?.frameWidth ?? 1920"
              :frame-height="faces?.frameHeight ?? 1080"
            />
          </span>
          <span class="cluster-size">лиц: {{ cluster.size }}</span>
          <span class="cluster-id">{{ cluster.id }}</span>
          <template v-if="namingCluster === cluster.id">
            <input v-model="clusterName" type="text" placeholder="Имя персоны" />
            <button type="button" @click="giveName(cluster.id)">дать имя</button>
            <button type="button" @click="namingCluster = null">отмена</button>
          </template>
          <button v-else type="button" @click="startNaming(cluster.id)">дать имя</button>
        </li>
      </ul>
    </section>

    <PersonsPanel
      v-if="faces"
      :videofile-id="videofileId"
      :project-id="faces.projectId"
      :biggest-faces="biggestFaces"
    />

    <section v-if="faces" class="groups">
      <h3>Лица по персонам</h3>
      <div v-for="[personId, group] in facesByPerson" :key="personId" class="group">
        <h4>
          {{ group.name }}
          <small :title="personKindTitle(group.kind)">({{ group.kind }})</small>
          <span class="count">на странице: {{ group.faces.length }}</span>
        </h4>
        <FaceThumbnails
          :videofile-id="videofileId"
          :faces="group.faces"
          draggable
          :frame-width="faces.frameWidth"
          :frame-height="faces.frameHeight"
          markable
          @example="onExample"
        />
      </div>
    </section>

    <nav v-if="faces" class="pager">
      <button type="button" :disabled="!hasPreviousPage" @click="turnPage(-limit)">
        предыдущие
      </button>
      <span>{{ faces.offset }}…{{ faces.offset + faces.faces.length }}</span>
      <button type="button" :disabled="!hasNextPage" @click="turnPage(limit)">следующие</button>
    </nav>

    <section v-if="namedPersons.length > 0" class="persons">
      <h3>Персоны проекта</h3>
      <table>
        <thead>
          <tr>
            <th>Имя</th>
            <th>Ключ класса в модели</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="person in namedPersons" :key="person.id">
            <td>
              <template v-if="renamingPerson === person.id">
                <input v-model="personName" type="text" />
                <button type="button" @click="saveName(person)">сохранить</button>
                <button type="button" @click="renamingPerson = null">отмена</button>
              </template>
              <template v-else>{{ person.name }}</template>
            </td>
            <td>{{ person.recognizerKey }}</td>
            <td>
              <button type="button" @click="startRenaming(person)">переименовать</button>
              <button type="button" @click="remove(person)">удалить</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="note">
        Удаление персоны не удаляет её лица: они переходят в «распознано, имя не подтверждено» и
        остаются в разметке.
      </p>
    </section>
  </section>
</template>

<style scoped>
.faces {
  padding: 12px;
}

.state {
  color: var(--syp-text-muted);
}

.note {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
  border: 1px solid var(--syp-danger);
  padding: 6px 8px;
}

.notice {
  color: var(--syp-success);
  border: 1px solid var(--syp-success);
  padding: 6px 8px;
}

.face-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.9rem;
  margin: 0.5rem 0;
}

.face-filters label {
  display: inline-flex;
  gap: 0.25rem;
  align-items: center;
  white-space: nowrap;
}

.cluster-faces {
  display: inline-block;
  vertical-align: middle;
}

.cluster-list {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cluster {
  display: flex;
  gap: 8px;
  align-items: center;
}

.cluster-id {
  font-family: monospace;
  color: var(--syp-text-muted);
}

.group h4 {
  margin: 12px 0 4px;
  font-weight: 600;
  /* Имя и счётчик — разными строками.
   *
   * Зачем: имя неподтверждённого лица — длинное («Распознано, имя не
   * подтверждено»), и счётчик вставал на ту же строку поверх него. Читалось
   * как «на странице» впереди и «на странице» позади — два разных числа на
   * одном месте. */
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 8px;
  line-height: 1.5;
}

.count {
  font-weight: 400;
  color: var(--syp-text-muted);
  font-size: 12px;
  flex: 0 0 100%;
}

.pager {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 12px 0;
}

.persons table {
  border-collapse: collapse;
}

.persons th,
.persons td {
  border: 1px solid var(--syp-border-control);
  padding: 4px 8px;
  text-align: left;
}
</style>
