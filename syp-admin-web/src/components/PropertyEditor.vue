<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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

/** Идентификатор выбранного свойства; строка выбирается щелчком. */
const selectedId = ref<number | null>(null)

/** Выбранное свойство; без выбора полоса кнопок удалять нечего. */
const selected = computed(() => properties.value.find((p) => p.id === selectedId.value))
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

    <div v-else class="properties-body">
      <table class="table table-sm align-middle">
        <thead>
          <tr>
            <th>Key</th>
            <th>Value</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="property in properties"
            :key="property.id"
            :class="{ picked: selectedId === property.id }"
            @click="selectedId = property.id"
          >
            <td class="syp-mono">{{ property.key }}</td>
            <td>{{ property.value }}</td>
          </tr>
        </tbody>
      </table>

      <!-- Полоса кнопок справа от таблицы, как в форме. Кнопок перемещения
           нет: порядок свойств не хранится. -->
      <div class="property-strip">
        <button
          type="submit"
          class="glyph"
          form="property-form"
          :disabled="busy || key.trim() === ''"
          title="Add property"
        >
          &#10133;
        </button>
        <button
          type="button"
          class="glyph"
          :disabled="busy || selected === undefined"
          title="Delete property"
          @click="selected !== undefined && remove(selected)"
        >
          &#10006;
        </button>
      </div>
    </div>

    <form id="property-form" class="properties-fields" @submit.prevent="save">
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
.properties-body {
  display: flex;
  gap: 0.25rem;
  align-items: flex-start;
}

.properties-body table {
  flex: 1 1 auto;
  min-width: 0;
}

.properties-body tbody tr.picked {
  background: var(--syp-tint);
}

.property-strip {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  width: 2.875rem;
  flex: 0 0 auto;
}

.glyph {
  width: 2.875rem;
  height: 2.875rem;
  padding: 0;
  font-size: 1rem;
  line-height: 1;
}

.properties-fields {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr 1fr;
  margin-top: 0.5rem;
}

@media (max-width: 900px) {
  .properties-fields {
    grid-template-columns: 1fr;
  }
}
</style>
