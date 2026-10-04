<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  deleteProperty,
  readProperties,
  writeProperty,
  type PropertyOwnerKind,
  type PropertyView,
} from '../api/properties'

/**
 * Редактор произвольных свойств владельца.
 *
 * В старом проекте это таблица «ключ — значение» с полями ввода и шестью
 * кнопками: перенести свойство в начало, вверх, вниз, в конец и delete. Здесь
 * порядок задаёт сама база (`ordinal`), а кнопка одна — «write», потому что
 * перестановка свойств оператору нужна заметно реже, чем их заведение.
 *
 * Ключ вводит оператор: системных ключей нет ни одного, и это не недочёт —
 * схема фиксирована, а дописать своё надо куда-то.
 */
const props = defineProps<{
  kind: PropertyOwnerKind
  ownerId: number
  title?: string
}>()

const properties = ref<PropertyView[]>([])
const key = ref('')
const value = ref('')
const notice = ref('')
const error = ref('')
const busy = ref(false)

/** Перечитывает свойства владельца. */
async function reload(): Promise<void> {
  try {
    properties.value = await readProperties(props.kind, props.ownerId)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Записывает свойство; пустой ключ не пишется. */
async function save(): Promise<void> {
  if (key.value.trim() === '' || busy.value) {
    return
  }
  busy.value = true
  try {
    await writeProperty(props.kind, props.ownerId, key.value.trim(), value.value)
    key.value = ''
    value.value = ''
    notice.value = 'свойство записано'
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

/** Удаляет свойство по ключу. */
async function remove(property: PropertyView): Promise<void> {
  if (busy.value) {
    return
  }
  busy.value = true
  try {
    await deleteProperty(props.kind, props.ownerId, property.key)
    notice.value = `свойство «${property.key}» удалено`
    error.value = ''
    await reload()
  } catch (failure) {
    error.value = (failure as Error).message
  } finally {
    busy.value = false
  }
}

onMounted(reload)
watch(() => [props.kind, props.ownerId], reload)
</script>

<template>
  <section class="properties">
    <div class="syp-card-title">{{ title ?? 'Properties' }}</div>

    <p v-if="properties.length === 0" class="syp-unit">
      No properties. You enter the key and the value yourself — the project keeps no list of known
      keys.
    </p>

    <table v-else class="table table-sm align-middle">
      <thead>
        <tr>
          <th>Key</th>
          <th>Value</th>
          <th aria-label="action"></th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="property in properties" :key="property.id">
          <td class="syp-mono">{{ property.key }}</td>
          <td>{{ property.value }}</td>
          <td>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="busy"
              @click="remove(property)"
            >
              delete
            </button>
          </td>
        </tr>
      </tbody>
    </table>

    <form class="properties-fields" @submit.prevent="save">
      <input v-model="key" class="form-control" placeholder="Key, for example: location" />
      <input v-model="value" class="form-control" placeholder="Value" />
      <button type="submit" class="btn btn-primary" :disabled="busy || key.trim() === ''">
        write
      </button>
    </form>

    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.properties-fields {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr 1fr auto;
  margin-top: 0.5rem;
}

@media (max-width: 900px) {
  .properties-fields {
    grid-template-columns: 1fr;
  }
}
</style>
