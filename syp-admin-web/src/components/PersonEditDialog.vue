<script setup lang="ts">
/**
 * Окно правки персоны.
 *
 * Форма перенесена по старому проекту: имя, фото, произвольные свойства
 * «ключ — значение» и подтверждение. Отличие от старого проекта одно и оно
 * существенное: свойства привязаны к персоне **настоящим владельцем**, а не
 * именем класса строкой. Поэтому ключи и значения хранятся в базе с привязкой к
 * идентификатору, и переименование персоны их не уносит.
 *
 * В старом проекте кнопки добавления и удаления персон были объявлены, а
 * обработчики у них были пустыми. Здесь кнопки не показываются вовсе: незаметная
 * кнопка хуже отсутствующей.
 */
import { onMounted, ref } from 'vue'
import { deleteProperty, readProperties, writeProperty, type PropertyView } from '../api/properties'
import { deletePerson, facePreviewUrl, renamePerson, type PersonView } from '../api/characters'

const props = defineProps<{ videofileId: number; person: PersonView }>()

const emit = defineEmits<{
  /** Персона сохранена или удалена. */
  saved: []
  /** Окно закрыто. */
  closed: []
}>()

/** Имя персоны: редактируется и сохраняется. */
const name = ref(props.person.name)

/** Свойства персоны, привязанные к ней настоящим владельцем. */
const properties = ref<PropertyView[]>([])

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
    error.value = 'Имя персоны обязательно: без него персону не опознать'
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
    notice.value = `персона «${props.person.name}» удалена`
    error.value = ''
    emit('saved')
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Добавляет свойство персоны. */
async function addProperty(): Promise<void> {
  if (propertyKey.value.trim() === '') {
    error.value = 'Ключ свойства обязателен: без него значение не к чему привязать'
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

/** Удаляет свойство персоны. */
async function removeProperty(key: string): Promise<void> {
  try {
    await deleteProperty('PERSON', props.person.id, key)
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

onMounted(reload)
</script>

<template>
  <div class="dialog" role="dialog" aria-label="Персона">
    <div class="dialog-body">
      <h2 class="syp-card-title">Персона</h2>

      <div class="photo">
        <img
          v-if="props.person.photoFrameNumber !== null"
          :src="facePreviewUrl(props.videofileId, props.person.photoFrameNumber)"
          :alt="`Фото персоны ${props.person.name}`"
        />
        <p v-else class="empty">Фото не поставлено</p>
      </div>

      <label class="field">
        <span>Имя</span>
        <input v-model="name" class="form-control" />
      </label>

      <div class="syp-card-title">Свойства</div>
      <table class="table table-sm">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="property in properties" :key="property.key">
            <td>{{ property.key }}</td>
            <td>{{ property.value }}</td>
            <td>
              <button type="button" class="btn btn-sm btn-outline-secondary" @click="removeProperty(property.key)">
                удалить
              </button>
            </td>
          </tr>
          <tr v-if="properties.length === 0">
            <td colspan="3" class="empty">Свойств нет</td>
          </tr>
        </tbody>
      </table>
      <div class="property-fields">
        <input v-model="propertyKey" class="form-control" placeholder="Key" />
        <textarea v-model="propertyValue" class="form-control" rows="2" placeholder="Value"></textarea>
        <button type="button" class="btn btn-primary" @click="addProperty">Добавить</button>
      </div>

      <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>
      <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

      <div class="dialog-actions">
        <button type="button" class="btn btn-primary" @click="save">Подтвердить</button>
        <button type="button" class="btn btn-outline-secondary" @click="remove">Удалить персону</button>
        <button type="button" class="btn btn-outline-secondary" @click="emit('closed')">Закрыть</button>
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
  border-radius: 6px;
  display: grid;
  gap: 0.5rem;
  max-height: 85vh;
  overflow: auto;
  padding: 1rem;
  width: 34rem;
}

.photo img {
  border-radius: 3px;
  max-width: 18rem;
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
  color: #777;
}

.error {
  color: #a61b1b;
  margin: 0;
}
</style>
