// Экран лиц серии (задача T072). // // Экран отвечает на два вопроса оператора: «кого и сколько
здесь найдено» и // «кто это». Первый вопрос — про найденные лица, второй — про кластеры: // похожие
лица группируются, и кластеру даётся имя. Обе задачи решаются // здесь, потому что до обучения
модели это единственный способ узнать имена. // // Неопознанные лица **показываются и не
пропадают**: «распознано, имя не // подтверждено» и «не лицо» — это заглушки, а не ошибка (Р-12,
FR-036). Если // бы они скрывались, оператор не видел бы, что детектор где-то ошибся. // //
Оформление — общая тема проекта (ADR-0015); состояния загрузки, пустоты и // отказа показывает
компонент `StateBlock`, как на остальных экранах.

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import FaceThumbnails from '../components/FaceThumbnails.vue'
import StateBlock from '../components/StateBlock.vue'
import {
  type PersonsView,
  deletePerson,
  nameCluster,
  readClusters,
  readFaces,
  readPersons,
  renamePerson,
} from '../api/characters'
import { useNotify } from '../ui/notify'
import {
  type FaceClustersRow,
  type FacesRow,
  type PersonFaceGroup,
  type PersonRow,
  toFaceClustersRow,
  toFacesRow,
  toPersonFaceGroups,
  toPersonRow,
} from '../api/view-model'

const route = useRoute()
const notify = useNotify()

/** Страница лиц серии. */
const faces = ref<FacesRow | null>(null)

/** Кластеры серии без имени. */
const clusters = ref<FaceClustersRow | null>(null)

/** Персоны сериала. */
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

/** Идентификатор серии из адреса. */
const seriesId = computed(() => Number(route.params.seriesId))

/**
 * Лица серии, сгруппированные по персонам.
 *
 * Группировка нужна оператору, а не системе: ответ «кто в кадре» читается
 * по персонам, и человек с одиннадцатью тысячами неопознанных лиц подряд
 * ничего в нём не найдёт. Само разбиение живёт в `api/view-model.ts`:
 * оно читает поля ответа, а экран получает готовые группы.
 */
const facesByPerson = computed<PersonFaceGroup[]>(() =>
  toPersonFaceGroups(faces.value?.faces ?? []),
)

/**
 * Персоны сериала без служебных заглушек.
 *
 * Заглушки в списке переименовывать и удалять нельзя, поэтому в правке они не
 * показываются вовсе: кнопка, которая всегда отвергается, в интерфейсе
 * вредна.
 */
const namedPersons = computed<PersonRow[]>(() =>
  (persons.value?.persons ?? []).filter((person) => !person.isService).map(toPersonRow),
)

/** Диапазон показанных лиц словами. */
const shownRange = computed(() => {
  const value = faces.value
  if (value === null || value.faces.length === 0) {
    return ''
  }
  return `${value.offset + 1}…${value.offset + value.faces.length}`
})

onMounted(() => {
  void reload()
})

watch(seriesId, () => {
  void reload()
})

/**
 * Перечитывает лица, кластеры и персон серии.
 */
async function reload(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const loaded = await readFaces(seriesId.value, offset.value, limit)
    faces.value = toFacesRow(loaded)
    clusters.value = toFaceClustersRow(await readClusters(seriesId.value))
    persons.value = await readPersons(faces.value.serialId)
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
 * @param person переименовываемая персона на экране
 */
function startRenaming(person: PersonRow): void {
  renamingPerson.value = person.id
  personName.value = person.name
}

/**
 * Даёт кластеру имя.
 *
 * @param clusterId ключ кластера
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
    notify(`Кластеру дано имя «${named.name}», лиц в нём: ${named.facesAssigned}`)
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
 */
async function saveName(person: PersonRow): Promise<void> {
  error.value = null
  notice.value = null
  const name = personName.value.trim()
  if (name === '') {
    error.value = 'Имя персоны обязательно'
    return
  }
  try {
    await renamePerson(person.id, name)
    notify(`Персона переименована в «${name}»`)
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
 * говорит об этом прямо, потому что удаление без предупреждения выглядело бы
 * как потеря разметки.
 *
 * @param person персона на экране
 */
async function remove(person: PersonRow): Promise<void> {
  error.value = null
  notice.value = null
  try {
    await deletePerson(person.id)
    notify(
      `Персона «${person.name}» удалена; её лица перешли в неопознанные и не потеряны`,
      'warning',
    )
    await reload()
  } catch (failure) {
    error.value = failure instanceof Error ? failure.message : String(failure)
  }
}

/**
 * Переходит на страницу лиц.
 *
 * @param delta смещение относительно текущей страницы
 */
async function turnPage(delta: number): Promise<void> {
  offset.value = Math.max(0, offset.value + delta)
  await reload()
}
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h2 class="syp-page-title">Лица серии</h2>
        <p class="syp-page-lead">
          Кто и сколько найдено и кто это. Похожие лица группируются в кластеры, и кластеру даётся
          имя; неопознанные и ошибочные рамки показываются и не теряются.
        </p>
      </div>
      <div v-if="faces" class="btn-group">
        <button
          type="button"
          class="btn btn-outline-secondary"
          :disabled="loading || !faces.hasPreviousPage"
          @click="turnPage(-limit)"
        >
          предыдущие
        </button>
        <button
          type="button"
          class="btn btn-outline-secondary"
          :disabled="loading || !faces.hasNextPage"
          @click="turnPage(limit)"
        >
          следующие
        </button>
      </div>
    </div>

    <StateBlock :loading="loading && faces === null" :error="error ?? ''" @dismiss="error = null" />

    <div v-if="notice" class="alert alert-success" role="status">
      {{ notice }}
      <button type="button" class="btn-close float-end" @click="notice = null">скрыть</button>
    </div>

    <div v-if="faces" class="syp-stale mb-3" role="status">
      {{ faces.summary }}.
      <span class="text-body-secondary ms-2">Показаны лица {{ shownRange }}.</span>
    </div>

    <div v-if="clusters" class="syp-stale mb-4" role="status">
      Кластеров без имени: {{ clusters.total }}
      <span class="text-body-secondary ms-2"
        >Ключ модели эмбеддингов: {{ clusters.modelKey }}.</span
      >
    </div>

    <div v-if="faces && faces.facesTotal === 0 && !loading" class="card mb-4">
      <div class="card-body">
        <StateBlock
          :error="''"
          empty-title="Лиц не найдено"
          empty-text="Это не «лиц нет в серии»: детекция могла не выполняться или оборваться. Проверьте задание FACES в очереди."
        />
      </div>
    </div>

    <div v-if="clusters && clusters.clusters.length > 0" class="card mb-4">
      <div class="card-header">Кластеры похожих лиц без имени</div>
      <div class="card-body">
        <p class="form-text">
          Кластер — это группа похожих лиц, которой ещё не дано имя. Дайте имя — и лица кластера
          получат его. Кластер строится до обучения модели, по векторам признаков.
        </p>
        <ul class="syp-list-plain d-flex flex-column gap-2 mb-0">
          <li
            v-for="cluster in clusters.clusters"
            :key="cluster.id"
            class="d-flex gap-2 align-items-center flex-wrap"
          >
            <span class="badge text-bg-info">{{ cluster.size }}</span>
            <span class="syp-mono">{{ cluster.id }}</span>
            <template v-if="namingCluster === cluster.id">
              <input
                v-model="clusterName"
                type="text"
                class="form-control form-control-sm"
                style="max-width: 18rem"
                placeholder="Имя персоны"
              />
              <button type="button" class="btn btn-sm btn-primary" @click="giveName(cluster.id)">
                дать имя
              </button>
              <button
                type="button"
                class="btn btn-sm btn-outline-secondary"
                @click="namingCluster = null"
              >
                отмена
              </button>
            </template>
            <button
              v-else
              type="button"
              class="btn btn-sm btn-outline-secondary"
              @click="startNaming(cluster.id)"
            >
              дать имя
            </button>
          </li>
        </ul>
      </div>
    </div>

    <div v-for="group in facesByPerson" :key="group.personId" class="card mb-3">
      <div class="card-header d-flex justify-content-between align-items-center flex-wrap gap-2">
        <span>{{ group.name }}</span>
        <span class="syp-unit">
          {{ group.kindTitle }} — на странице: {{ group.faces.length }}
        </span>
      </div>
      <div class="card-body">
        <FaceThumbnails
          :series-id="seriesId"
          :faces="group.faces"
          :frame-width="faces?.frameWidth ?? 0"
          :frame-height="faces?.frameHeight ?? 0"
        />
      </div>
    </div>

    <div v-if="namedPersons.length > 0" class="card">
      <div class="card-header">Персоны сериала</div>
      <div class="table-responsive">
        <table class="table align-middle">
          <thead>
            <tr>
              <th scope="col">Имя</th>
              <th scope="col">Ключ класса в модели</th>
              <th scope="col" class="text-end">Действия</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="person in namedPersons" :key="person.id">
              <td>
                <template v-if="renamingPerson === person.id">
                  <div class="d-flex gap-2">
                    <input v-model="personName" type="text" class="form-control form-control-sm" />
                    <button
                      type="button"
                      class="btn btn-sm btn-primary flex-shrink-0"
                      @click="saveName(person)"
                    >
                      сохранить
                    </button>
                    <button
                      type="button"
                      class="btn btn-sm btn-outline-secondary flex-shrink-0"
                      @click="renamingPerson = null"
                    >
                      отмена
                    </button>
                  </div>
                </template>
                <template v-else>{{ person.name }}</template>
              </td>
              <td class="syp-mono">{{ person.recognizerKey }}</td>
              <td class="text-end text-nowrap">
                <button
                  type="button"
                  class="btn btn-sm btn-outline-secondary me-1"
                  @click="startRenaming(person)"
                >
                  переименовать
                </button>
                <button
                  type="button"
                  class="btn btn-sm btn-outline-secondary"
                  @click="remove(person)"
                >
                  удалить
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="card-footer">
        <p class="form-text mb-0">
          Удаление персоны не удаляет её лица: они переходят в «распознано, имя не подтверждено» и
          остаются в разметке.
        </p>
      </div>
    </div>
  </section>
</template>
