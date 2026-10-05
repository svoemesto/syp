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

/** Идентификатор видеофайла из адреса. */
const videofileId = computed(() => Number(route.params.videofileId))

onMounted(() => {
  void store.reload(videofileId.value)
})

watch(videofileId, (next) => {
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
    return 'Sum не считалась'
  }
  switch (value.state) {
    case 'CREATING':
      return 'The job is queued, reading has not started yet'
    case 'WORKING':
      return 'Reading the file and computing the sum'
    case 'ERROR':
      return 'Подсчёт не удался'
    case 'DONE':
      return value.isStale
        ? 'Sum is stale: the file changed after it was computed'
        : 'Sum актуальна'
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
  return store.recalculate(videofileId.value)
}
</script>

<template>
  <section class="checksum">
    <h2>Source sum state</h2>
    <p class="note">
      Sum <code>SHA-256</code> of the videofile is the exemplar the user machine compares against
      when cutting. Without a fresh sum the build script must not publish.
    </p>
    <p v-if="store.loading.value" class="note">Request to the backend…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">hide</button>
    </p>

    <p class="state">
      <strong>{{ stateTitle }}</strong>
      <span v-if="isRunning" class="note"
        >The sum is computed by a job, the screen is not blocked.</span
      >
    </p>

    <table v-if="store.checksum.value">
      <tbody>
        <tr>
          <th>Algorithm</th>
          <td>{{ store.checksum.value.algorithm }}</td>
        </tr>
        <tr>
          <th>Sum</th>
          <td class="digest">
            {{ formatDigest(store.checksum.value.digest) }}
          </td>
        </tr>
        <tr>
          <th>Computed</th>
          <td>{{ formatDate(store.checksum.value.computedAt) }}</td>
        </tr>
        <tr>
          <th>Stale</th>
          <td>
            {{
              store.checksum.value.isStale ? 'yes, the file changed after the sum was made' : 'no'
            }}
          </td>
        </tr>
        <tr>
          <th>Usable for verification</th>
          <td>{{ store.checksum.value.isUsable ? 'yes' : 'no' }}</td>
        </tr>
        <tr>
          <th>File size during summation</th>
          <td>{{ formatBytes(store.checksum.value.byteSize) }}</td>
        </tr>
        <tr>
          <th>File change time</th>
          <td>{{ formatDate(store.checksum.value.fileMtime) }}</td>
        </tr>
        <tr>
          <th>Recomputation records</th>
          <td>{{ store.checksum.value.historyCount }}</td>
        </tr>
        <tr v-if="store.checksum.value.errorText">
          <th>Error</th>
          <td>{{ store.checksum.value.errorText }}</td>
        </tr>
      </tbody>
    </table>

    <div class="form">
      <button type="button" :disabled="store.loading.value" @click="recalculate">
        queue a recomputation
      </button>
      <button type="button" @click="router.back()">back</button>
    </div>

    <p v-if="store.checksum.value" class="note">
      A repeated recomputation does not erase the previous record: it stays in the history and is
      marked stale. Exactly one sum per videofile stays fresh.
    </p>
    <p v-if="store.checksum.value" class="note">
      The sum can be checked locally with a command
      <code>sha256sum path/to/file</code> — the value format is the same.
    </p>
    <p class="note">Reading a 5.6 GB file takes about 32 seconds on this machine.</p>
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
  border-bottom: 1px solid var(--syp-text-muted);
  padding: 0.35rem 0.75rem 0.35rem 0;
  text-align: left;
  font-size: 0.9rem;
  vertical-align: top;
}

th {
  width: 16rem;
  color: var(--syp-text-muted);
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
  border-left: 3px solid var(--syp-danger);
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
  color: var(--syp-text-muted);
  font-size: 0.85rem;
}
</style>
