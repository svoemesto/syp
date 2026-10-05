<script setup lang="ts">
/**
 * Окно правки персоны.
 *
 * Форма перенесена по старому проекту: имя, photo, произвольные свойства
 * «ключ — значение» и подтверждение. Отличие от старого проекта одно и оно
 * существенное: свойства привязаны к персоне **настоящим владельцем**, а не
 * именем класса строкой. Поэтому ключи и значения хранятся в базе с привязкой к
 * идентификатору, и переименование персоны их не уносит.
 *
 * В старом проекте кнопки добавления и удаления персон были объявлены, а
 * обработчики у них были пустыми. Здесь кнопки не показываются вовсе: незаметная
 * кнопка хуже отсутствующей.
 */
import { computed, onMounted, ref } from 'vue'
import { readProperties, writeProperty, type PropertyView } from '../api/properties'
import { deletePerson, facePreviewUrl, renamePerson, type PersonView } from '../api/characters'

const props = defineProps<{ videofileId: number; person: PersonView }>()

const emit = defineEmits<{
  /** Person сохранена или удалена. */
  saved: []
  /** Окно закрыто. */
  closed: []
}>()

/** Name: персоны: редактируется и сохраняется. */
const name = ref(props.person.name)

/** Properties персоны, привязанные к ней настоящим владельцем. */
const properties = ref<PropertyView[]>([])

/** Пусто ли в таблице свойств: от этого зависит надпись в пустой строке. */
const hasProperties = computed(() => properties.value.length > 0)

/** Адрес фотографии персоны; пустая строка — фотографии нет. */
const photoUrl = computed(() =>
  props.person.photoFrameNumber === null
    ? ''
    : facePreviewUrl(props.videofileId, props.person.photoFrameNumber),
)

/** Ключ и значение нового свойства. */
const propertyKey = ref('')
const propertyValue = ref('')

const error = ref('')
const notice = ref('')

/** Читает свойства персоны. */
async function reload(): Promise<void> {
  try {
    properties.value = await readProperties('PERSON', props.person.id)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Сохраняет имя и закрывает окно. */
async function save(): Promise<void> {
  if (name.value.trim() === '') {
    error.value = 'Name: person is required: without it the person cannot be identified'
    return
  }
  try {
    await renamePerson(props.person.id, name.value.trim())
    notice.value = `персона «${name.value.trim()}» сохранена`
    error.value = ''
    emit('saved')
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Удаляет персону. */
async function remove(): Promise<void> {
  try {
    await deletePerson(props.person.id)
    notice.value = `the person "${props.person.name}" is deleted`
    error.value = ''
    emit('saved')
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Добавляет свойство персоны. */
async function addProperty(): Promise<void> {
  if (propertyKey.value.trim() === '') {
    error.value = 'The property key is required: there is nothing to bind the value to without it'
    return
  }
  try {
    await writeProperty('PERSON', props.person.id, propertyKey.value.trim(), propertyValue.value)
    propertyKey.value = ''
    propertyValue.value = ''
    await reload()
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

onMounted(reload)
</script>

<template>
  <div class="dialog" role="dialog" aria-label="Person">
    <div class="dialog-body">
      <!-- Форма person-edit: слева поле переименования, таблица PERSON и две
           квадратные кнопки 46 на 46; справа фотография, Name:, заголовок
           Properties, таблица Key и Value и кнопка OK во всю ширину.
           Заголовка PERSON над всем окном в форме нет: это колонка. -->
      <div class="edit-area">
        <div class="persons-column">
          <input
            v-model="name"
            class="form-control rename"
            placeholder="Name:"
            @keyup.enter="save"
          />

          <table class="table table-sm persons-table">
            <thead>
              <tr>
                <th>PERSON</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="!hasProperties">
                <td class="empty">No content in table</td>
              </tr>
            </tbody>
          </table>

          <div class="glyph-buttons">
            <!-- Пара кнопок стоит рядом с таблицей PERSON, значит и
                 работает с персонами: завести и удалить. -->
            <button type="button" class="glyph" title="Add person" @click="addProperty">
              &#10133;
            </button>
            <button type="button" class="glyph" title="Delete person" @click="remove">
              &#10006;
            </button>
          </div>
        </div>

        <div class="properties-column">
          <div class="photo">
            <img v-if="photoUrl !== ''" :src="photoUrl" alt="Person photo" class="photo-image" />
            <p v-else class="empty">No photo set</p>
          </div>

          <label class="field">
            <span>Name:</span>
            <input v-model="name" class="form-control" />
          </label>

          <div class="syp-card-title">Properties</div>
          <table class="table table-sm properties-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Value</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="property in properties" :key="property.key">
                <td>{{ property.key }}</td>
                <td>{{ property.value }}</td>
              </tr>
              <tr v-if="!hasProperties">
                <td colspan="2" class="empty">No content in table</td>
              </tr>
            </tbody>
          </table>

          <div class="property-fields">
            <input v-model="propertyKey" class="form-control" placeholder="property key" />
            <input v-model="propertyValue" class="form-control" placeholder="value" />
            <button type="button" class="btn btn-primary" @click="addProperty">Add</button>
          </div>

          <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>

          <div class="dialog-actions">
            <button type="button" class="btn btn-primary" @click="save">OK</button>
            <button type="button" class="btn btn-outline-secondary" @click="emit('closed')">
              Close
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog {
  align-items: center;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  inset: 0;
  justify-content: center;
  position: fixed;
  z-index: 40;
}

.dialog-body {
  background: var(--syp-surface);
  border-radius: 0;
  display: flex;
  flex-direction: column;
  padding: 0.25rem;
  /* Форма объявлена 730 на 900. */
  width: 45.625rem;
}

.edit-area {
  display: flex;
  gap: 0.25rem;
  align-items: stretch;
  min-width: 0;
}

.persons-column {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  width: 13.4375rem;
  flex: 0 0 auto;
}

.persons-table {
  flex: 1 1 auto;
  table-layout: fixed;
  margin-bottom: 0;
}

.glyph-buttons {
  display: flex;
  gap: 0.25rem;
}

.glyph {
  width: 2.875rem;
  height: 2.875rem;
  padding: 0;
  font-size: 1rem;
  line-height: 1;
}

.properties-column {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  flex: 1 1 auto;
  min-width: 0;
}

.photo {
  background: var(--syp-bg);
  min-height: 12rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.photo-image {
  max-width: 100%;
  max-height: 20rem;
  object-fit: contain;
}

.properties-table {
  table-layout: fixed;
  margin-bottom: 0;
}

.field {
  display: grid;
  gap: 0.25rem;
}

.property-fields {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr 2fr auto;
}

.dialog-actions {
  display: flex;
  gap: 0.5rem;
}

.empty {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
  margin: 0;
}
</style>
