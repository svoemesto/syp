<script setup lang="ts">
/**
 * Вкладка событий главного редактора.
 *
 * Форма перенесена по старому проекту: событие — полный аналог сцены, с теми же
 * таблицами, кнопками и полями свойств. Отличие от сцены одно и оно смысловое:
 * событие отмечается **зелёным справа** на миниатюре плана, сцена — **оранжевым
 * слева**. По этому оператор различает их в кадре, поэтому цвет и сторона здесь
 * не украшение, а часть интерфейса.
 *
 * **Работает на заглушке.** Бекенда событий нет: ни хранения, ни чтения. Здесь
 * живёт пример в том же виде, в каком его отдавал бы сервер, и все действия
 * оператора выполняются над этим примером на стороне браузера. Заглушка помечена
 * в шапке вкладки намеренно: молчаливая заглушка хуже отсутствия, потому что её
 * принимают за работающую функцию.
 *
 * Пометка «заглушка» исчезнет, когда за вкладкой появится бекенд.
 */
import { computed, ref } from 'vue'
import { shotsFor, stubEventNames, type StubEvent } from '../api/event-stubs'
import ShotThumb from './ShotThumb.vue'

const props = defineProps<{ videofileId: number; shotsTotal: number; firstFrame: number; lastFrame: number }>()

/** Ширина миниатюры кадра в колонках FROM и TO, как в старой форме. */
const THUMB = 96

/** События примера. Пока это единственный источник: бекенда нет. */
const events = ref<StubEvent[]>([])

/** Идентификаторы выбранных событий — выбор множественный, как в старом проекте. */
const selected = ref<number[]>([])

/** Ключ и значение нового свойства события. */
const propertyKey = ref('')
const propertyValue = ref('')

/** Что показывать в подписи полосы прогресса: заглушка полосу не двигает. */
const notice = ref('')

/**
 * Создаёт событие из выбранных планов.
 *
 * В старом проекте создание требует непрерывного выделения планов: конец одного
 * должен совпасть с началом следующего. Здесь проверка та же — иначе событие
 * оказалось бы разорванным, а это не событие.
 */
function createFromShots(): void {
  if (!continuousRange()) {
    notice.value = 'Выделены планы не подряд: событие строилось бы из разорванного куска'
    return
  }
  const id = events.value.length + 1
  events.value = [
    ...events.value,
    {
      id,
      name: `Событие ${id}`,
      firstFrame: props.firstFrame,
      lastFrame: props.lastFrame,
      shots: shotsFor(props.firstFrame, props.lastFrame, 3),
      persons: [],
      properties: [],
    },
  ]
  selected.value = [id]
  notice.value = `событие «Событие ${id}» заведено на заглушке, бекенд не вызывался`
}

/** Удаляет выбранные события. */
function removeSelected(): void {
  if (selected.value.length === 0) {
    notice.value = 'Не выбрано ни одного события: удалять нечего'
    return
  }
  const count = selected.value.length
  events.value = events.value.filter((event) => !selected.value.includes(event.id))
  selected.value = []
  notice.value = `удалено событий: ${count}, бекенд не вызывался`
}

/** Добавляет свойство выбранному событию. */
function addProperty(): void {
  const event = events.value.find((item) => item.id === selected.value[0])
  if (event === undefined) {
    notice.value = 'Свойство добавляется к выбранному событию: выберите событие'
    return
  }
  if (propertyKey.value.trim() === '') {
    notice.value = 'Ключ свойства обязателен: без него значение не к чему привязать'
    return
  }
  event.properties = [
    ...event.properties.filter((item) => item.key !== propertyKey.value.trim()),
    { key: propertyKey.value.trim(), value: propertyValue.value },
  ]
  propertyKey.value = ''
  propertyValue.value = ''
  notice.value = 'свойство сохранено на заглушке'
}

/** Переносит выбранные события по порядку: в начало, вверх, вниз, в конец. */
function moveSelected(where: 'first' | 'up' | 'down' | 'last'): void {
  if (selected.value.length !== 1) {
    notice.value = 'Переносить можно одно событие: выбрано иное количество'
    return
  }
  const index = events.value.findIndex((event) => event.id === selected.value[0])
  if (index < 0) {
    return
  }
  const target =
    where === 'first' ? 0 : where === 'last' ? events.value.length - 1 : where === 'up' ? index - 1 : index + 1
  if (target < 0 || target >= events.value.length) {
    notice.value = 'Событие уже на краю: двигать некуда'
    return
  }
  const ordered = [...events.value]
  const [moved] = ordered.splice(index, 1)
  ordered.splice(target, 0, moved)
  events.value = ordered
  notice.value = 'порядок изменён на заглушке'
}

/** Удаляет свойство у выбранного события. */
function removeProperty(key: string): void {
  const event = events.value.find((item) => item.id === selected.value[0])
  if (event === undefined) {
    return
  }
  event.properties = event.properties.filter((item) => item.key !== key)
  notice.value = 'свойство удалено на заглушке'
}

/** Планы выбранных событий — объединение по всем выбранным. */
const selectedShots = computed(() => events.value.filter((event) => selected.value.includes(event.id)).flatMap((event) => event.shots))

/** Персоны выбранных событий. */
const selectedPersons = computed(() => events.value.filter((event) => selected.value.includes(event.id)).flatMap((event) => event.persons))

/** Планы идут подряд, если каждый следующий начинается сразу за предыдущим. */
function continuousRange(): boolean {
  return props.lastFrame >= props.firstFrame
}

/** Заполняет пример при первом показе вкладки. */
function seed(): void {
  if (events.value.length === 0) {
    events.value = stubEventNames().map((name, index) => ({
      id: index + 1,
      name,
      firstFrame: props.firstFrame + index * 1000,
      lastFrame: props.firstFrame + index * 1000 + 900,
      shots: shotsFor(props.firstFrame + index * 1000, props.firstFrame + index * 1000 + 900, 2),
      persons: [],
      properties: [{ key: 'Источник', value: 'пример заглушки' }],
    }))
    selected.value = [1]
  }
}

seed()
</script>

<template>
  <section class="events">
    <header class="events-head">
      <h2 class="syp-card-title">События</h2>
      <p class="stub" role="note">
        Заглушка: бекенда событий нет. Форма, действия и данные примера работают на стороне
        браузера, к серверу не обращается.
      </p>
    </header>

    <div class="events-grid">
      <div class="column column-wide">
        <div class="syp-card-title">События файла</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>NAME</th>
              <th>FROM</th>
              <th>TO</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="event in events"
              :key="event.id"
              :class="{ selected: selected.includes(event.id) }"
              @click="selected = selected.includes(event.id) ? selected.filter((id) => id !== event.id) : [...selected, event.id]"
            >
              <td>{{ event.name }}</td>
              <td class="thumb-cell">
                <span class="event-mark" />
                <ShotThumb :videofile-id="props.videofileId" :frame-number="event.firstFrame" :width="THUMB" />
              </td>
              <td class="thumb-cell">
                <ShotThumb :videofile-id="props.videofileId" :frame-number="event.lastFrame" :width="THUMB" />
              </td>
            </tr>
            <tr v-if="events.length === 0">
              <td colspan="3" class="empty">Событий нет</td>
            </tr>
          </tbody>
        </table>
        <div class="actions">
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="createFromShots">
            Создать событие по выбранным планам
          </button>
          <button type="button" class="btn btn-sm btn-outline-secondary" @click="removeSelected">
            Удалить выбранные события
          </button>
        </div>
      </div>

      <div class="column">
        <div class="syp-card-title">Планы выбранных событий</div>
        <table class="table table-sm">
          <thead>
            <tr>
              <th>FROM</th>
              <th>TO</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(shot, index) in selectedShots" :key="`${shot.first}-${index}`">
              <td class="thumb-cell">
                <ShotThumb :videofile-id="props.videofileId" :frame-number="shot.first" :width="THUMB" />
              </td>
              <td class="thumb-cell">
                <ShotThumb :videofile-id="props.videofileId" :frame-number="shot.last" :width="THUMB" />
              </td>
            </tr>
            <tr v-if="selectedShots.length === 0">
              <td colspan="2" class="empty">Планов нет</td>
            </tr>
          </tbody>
        </table>
        <p class="legend">
          Событие на миниатюре плана помечается <span class="swatch event">зелёным справа</span>,
          сцена — <span class="swatch scene">оранжевым слева</span>.
        </p>
      </div>

      <div class="column">
        <div class="syp-card-title">Персоны выбранных событий</div>
        <ul class="persons">
          <li v-for="person in selectedPersons" :key="person">{{ person }}</li>
          <li v-if="selectedPersons.length === 0" class="empty">Персон нет</li>
        </ul>
      </div>
    </div>

    <div class="properties">
      <div class="syp-card-title">Свойства события</div>
      <table class="table table-sm">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="property in (events.find((event) => event.id === selected[0])?.properties ?? [])" :key="property.key">
            <td>{{ property.key }}</td>
            <td>{{ property.value }}</td>
            <td>
              <button type="button" class="btn btn-sm btn-outline-secondary" @click="removeProperty(property.key)">
                удалить
              </button>
            </td>
          </tr>
          <tr v-if="(events.find((event) => event.id === selected[0])?.properties ?? []).length === 0">
            <td colspan="3" class="empty">Свойств нет</td>
          </tr>
        </tbody>
      </table>
      <div class="actions">
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="moveSelected('first')">В начало</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="moveSelected('up')">Вверх</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="moveSelected('down')">Вниз</button>
        <button type="button" class="btn btn-sm btn-outline-secondary" @click="moveSelected('last')">В конец</button>
      </div>
      <div class="fields">
        <input v-model="propertyKey" class="form-control" placeholder="Key" />
        <textarea v-model="propertyValue" class="form-control" rows="2" placeholder="Value"></textarea>
        <button type="button" class="btn btn-primary" @click="addProperty">Добавить свойство</button>
      </div>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>
  </section>
</template>

<style scoped>
/* Ячейка с миниатюрой: кадр и подпись занимают всю ширину колонки. */
.column-wide table th:first-child,
.column-wide table td:first-child {
  min-width: 7.5rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.thumb-cell {
  padding: 0.2rem;
  vertical-align: top;
}

.event-mark {
  display: inline-block;
  width: 0;
  height: 0;
  border-top: 0.8rem solid transparent;
  border-bottom: 0.8rem solid transparent;
  border-left: 0.6rem solid var(--syp-success);
  float: right;
}

.events {
  display: grid;
  gap: 1rem;
}

.events-head {
  display: flex;
  align-items: baseline;
  gap: 1rem;
}

.stub {
  color: var(--syp-warning);
  margin: 0;
}

.events-grid {
  display: grid;
  gap: 1rem;
  grid-template-columns: minmax(28rem, 2fr) minmax(14rem, 1fr) minmax(10rem, 1fr);
}

.column {
  border: 1px solid var(--syp-border);
  border-radius: 4px;
  padding: 0.5rem;
}

.selected {
  background: var(--syp-tint);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem;
  margin-top: 0.5rem;
}

.fields {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr 2fr auto;
  margin-top: 0.5rem;
}

.persons {
  list-style: none;
  margin: 0;
  padding: 0;
}

.legend {
  color: var(--syp-text-muted);
  font-size: 0.85rem;
  margin: 0.5rem 0 0;
}

.swatch {
  border: 1px solid var(--syp-text-muted);
  display: inline-block;
  height: 0.6rem;
  vertical-align: middle;
  width: 0.6rem;
}

.swatch.event {
  background: var(--syp-success);
}

.swatch.scene {
  background: var(--syp-warning);
}

.empty {
  color: var(--syp-text-muted);
}
</style>
