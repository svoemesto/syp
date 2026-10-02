// Экран состояния суммы исходника (задача T044). // // Экран отвечает на вопрос, который оператор
задаёт чаще всего: «файл у меня // такой же, как у вас?» Сценарий сборки выдаётся только при
актуальной сумме // (FR-089), поэтому показывается не «есть/нет», а состояние подсчёта: что //
считается, когда посчитано, почему сумма устарела и что было раньше.

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { formatDigest } from '../api/checksum'
import { formatBytes, formatDate } from '../format/values'
import { useChecksumStore } from '../stores/checksum'

const route = useRoute()
const router = useRouter()
const store = useChecksumStore()

/** Идентификатор серии из адреса. */
const episodeId = computed(() => Number(route.params.episodeId))

onMounted(() => {
  void store.reload(episodeId.value)
})

watch(episodeId, (next) => {
  void store.reload(next)
})

/**
 * Пояснение состояния суммы словами.
 *
 * Отдельная надпись вместо кодов: оператор читает текст, а код нужен
 * интерфейсу, а не человеку.
 */
const stateTitle = computed(() => {
  const value = store.checksum.value
  if (value === null) {
    return 'Сумма не считалась'
  }
  switch (value.state) {
    case 'CREATING':
      return 'Задание поставлено, чтение ещё не началось'
    case 'WORKING':
      return 'Идёт чтение файла и подсчёт'
    case 'ERROR':
      return 'Подсчёт не удался'
    case 'DONE':
      return value.isStale ? 'Сумма устарела: файл изменился после подсчёта' : 'Сумма актуальна'
    default:
      return value.state
  }
})

/** Считается ли сумма прямо сейчас. */
const isRunning = computed(() => {
  const state = store.checksum.value?.state
  return state === 'CREATING' || state === 'WORKING'
})

/**
 * Ставит пересчёт и перечитывает состояние.
 *
 * @returns `true`, если задание поставлено
 */
async function recalculate(): Promise<boolean> {
  return store.recalculate(episodeId.value)
}
</script>

<template>
  <section class="checksum">
    <h2>Состояние суммы исходника</h2>
    <p class="note">
      Сумма <code>SHA-256</code> файла серии — эталон, с которым машина пользователя сверяет файл
      <em>до</em> нарезки. Без актуальной суммы сценарий сборки выдавать нельзя.
    </p>

    <p v-if="store.loading.value" class="note">Запрос к бэкенду…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">скрыть</button>
    </p>

    <p class="state">
      <strong>{{ stateTitle }}</strong>
      <span v-if="isRunning" class="note">Подсчёт идёт заданием, экран не блокируется.</span>
    </p>

    <table v-if="store.checksum.value">
      <tbody>
        <tr>
          <th>Алгоритм</th>
          <td>{{ store.checksum.value.algorithm }}</td>
        </tr>
        <tr>
          <th>Сумма</th>
          <td class="digest">
            {{ formatDigest(store.checksum.value.digest) }}
          </td>
        </tr>
        <tr>
          <th>Посчитана</th>
          <td>{{ formatDate(store.checksum.value.computedAt) }}</td>
        </tr>
        <tr>
          <th>Устарела</th>
          <td>{{ store.checksum.value.isStale ? 'да, файл изменился после подсчёта' : 'нет' }}</td>
        </tr>
        <tr>
          <th>Пригодна для сверки</th>
          <td>{{ store.checksum.value.isUsable ? 'да' : 'нет' }}</td>
        </tr>
        <tr>
          <th>Размер файла при подсчёте</th>
          <td>{{ formatBytes(store.checksum.value.byteSize) }}</td>
        </tr>
        <tr>
          <th>Время изменения файла</th>
          <td>{{ formatDate(store.checksum.value.fileMtime) }}</td>
        </tr>
        <tr>
          <th>Записей пересчётов</th>
          <td>{{ store.checksum.value.historyCount }}</td>
        </tr>
        <tr v-if="store.checksum.value.errorText">
          <th>Ошибка</th>
          <td>{{ store.checksum.value.errorText }}</td>
        </tr>
      </tbody>
    </table>

    <div class="form">
      <button type="button" :disabled="store.loading.value" @click="recalculate">
        поставить пересчёт
      </button>
      <button type="button" @click="router.back()">назад</button>
    </div>

    <p v-if="store.checksum.value" class="note">
      Повторный пересчёт не затирает прежнюю запись: она остаётся в истории и помечается устаревшей.
      Актуальной остаётся ровно одна сумма на серию.
    </p>
    <p v-if="store.checksum.value" class="note">
      Проверить сумму у себя можно командой
      <code>sha256sum путь/к/файлу</code> — формат значения совпадает.
    </p>
    <p class="note">Чтение файла 5,6 ГБ занимает около 32 секунд на этой машине.</p>
  </section>
</template>

<style scoped>
.checksum {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  max-width: 60rem;
}

table {
  border-collapse: collapse;
}

th,
td {
  border-bottom: 1px solid #e6e6e6;
  padding: 0.35rem 0.75rem 0.35rem 0;
  text-align: left;
  font-size: 0.9rem;
  vertical-align: top;
}

th {
  width: 16rem;
  color: #444444;
  font-weight: 500;
}

.digest {
  font-family: monospace;
  word-break: break-all;
}

.state {
  display: flex;
  gap: 0.75rem;
  align-items: baseline;
  flex-wrap: wrap;
}

.error {
  border-left: 3px solid #b3261e;
  padding-left: 0.5rem;
}

.code {
  font-family: monospace;
  margin-right: 0.5rem;
}

.form {
  display: flex;
  gap: 0.5rem;
}

.note {
  color: #555555;
  font-size: 0.85rem;
}
</style>
