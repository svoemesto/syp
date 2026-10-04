<!--
  Прогресс-мер в шапке админки.

  Показывает, что задание идёт и насколько далеко оно продвинулось, а не
  только то, что кнопку нажали. Показывать это в шапке, а не на экране
  раздела, потому что задание может идти минуты, а оператор в это время
  смотрит другой раздел.

  Образец — `ProcessWorker.vue` админки Karaoke: название процесса, полоса с
  процентом, состояние.

  Опрос, а не подписка: пока живого канала уведомлений нет, а молчащий экран
  хуже отставшего на пару секунд.
-->
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  ACTIVE_JOB_STATES,
  type JobState,
  type JobView,
  fetchJobs,
  jobHeadline,
  jobProgressText,
} from '../api/jobs'

/** Как часто спрашиваем состояние очереди, миллисекунды. */
const POLL_INTERVAL_MS = 2000

const jobs = ref<JobView[]>([])
const reachable = ref(true)
let timer: number | undefined

/** Задания, которые ещё не закончились: их показываем на первом месте. */
const active = computed(() => jobs.value.filter((job) => ACTIVE_JOB_STATES.includes(job.state)))

/**
 * Задание для строки прогресса: первое незавершённое, иначе последнее
 * завершённое. Показывать пустую шапку после каждой задачи не надо — оператор
 * должен видеть, что работа закончилась, даже если он смотрел в другой
 * раздел.
 */
const headline = computed<JobView | null>(() => {
  if (active.value.length > 0) {
    return active.value[0]
  }
  return jobs.value.length > 0 ? jobs.value[jobs.value.length - 1] : null
})

/** Снято ли упавшее задание оператором. */
const dismissed = ref<number | null>(null)

/**
 * Задание, упавшее раньше, чем оператор открыл экран.
 *
 * Показывается отдельной строкой и снимается кнопкой: последнее упавшее
 * задание иначе занимало шапку на всех экранах и не уходило, хотя активной
 * работы не было. Ошибка должна быть видна, но не должна застрягивать.
 */
const lastFailure = computed<JobView | null>(() => {
  if (active.value.length > 0 || dismissed.value !== null) {
    return null
  }
  return jobs.value.filter((job) => job.state === 'ERROR').slice(-1)[0] ?? null
})

/** Процент заполнения полосы, от 0 до 100. */
const percent = computed<number>(() => {
  const job = headline.value
  if (job === null || job.progressPercent === null) {
    return 0
  }
  return Math.max(0, Math.min(100, job.progressPercent))
})

/** Идёт ли работа прямо сейчас: полоса с бегущей полосатой, как в Караоке. */
const running = computed<boolean>(
  () => headline.value !== null && ACTIVE_JOB_STATES.includes(headline.value.state),
)

/** Подпись состояния задания для показа оператору. */
const label = computed<string>(() => {
  const job = headline.value
  if (job === null) {
    return ''
  }
  return jobHeadline(job)
})

/** Второй строкой под названием: счётчик кадров либо пояснение хода. */
const detail = computed<string>(() => {
  const job = headline.value
  if (job === null) {
    return ''
  }
  if (job.state === 'ERROR') {
    return job.errorText ?? 'причина не записана'
  }
  if (job.state === 'DONE') {
    return job.progressNote !== '' ? job.progressNote : 'готово'
  }
  return jobProgressText(job)
})

/** Число заданий в очереди: показывается, когда их больше одного. */
const queuedCount = computed<number>(() => active.value.length)

async function refresh(): Promise<void> {
  try {
    jobs.value = await fetchJobs(['CREATING', 'WAITING', 'WORKING', 'DONE', 'ERROR'] as JobState[])
    reachable.value = true
  } catch {
    // Бэкенд недоступен: это само по себе состояние, которое оператор должен
    // видеть. Молча оставлять прошлые данные значило бы показывать прогресс
    // задания, которое давно никто не исполняет.
    reachable.value = false
  }
}

onMounted(() => {
  void refresh()
  timer = window.setInterval(() => void refresh(), POLL_INTERVAL_MS)
})

onBeforeUnmount(() => {
  if (timer !== undefined) {
    window.clearInterval(timer)
  }
})
</script>

<template>
  <div class="job-meter" role="status" aria-live="polite">
    <template v-if="!reachable">
      <span class="job-meter__state job-meter__state--error"> Бэкенд админки недоступен </span>
    </template>
    <template v-else-if="headline === null">
      <span class="job-meter__idle">Очередь пуста</span>
    </template>
    <div v-if="lastFailure !== null" class="job-meter__failure">
      <span>Задание упало: {{ lastFailure.errorText ?? 'причина не записана' }}</span>
      <button type="button" class="btn btn-sm btn-link" @click="dismissed = lastFailure?.id ?? null">
        скрыть
      </button>
    </div>
    <template v-else>
      <div class="job-meter__row">
        <span class="job-meter__state">{{ label }}</span>
        <span
          v-if="queuedCount > 1"
          class="job-meter__queued"
          :title="queuedCount > 1 ? 'В очереди заданий' : ''"
        >
          +{{ queuedCount - 1 }}
        </span>
      </div>
      <div
        class="job-meter__bar"
        role="progressbar"
        :aria-valuenow="percent"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <div
          class="job-meter__fill"
          :class="{ 'job-meter__fill--running': running }"
          :style="{ width: `${percent}%` }"
        />
      </div>
      <div class="job-meter__detail">{{ detail }}</div>
    </template>
  </div>
</template>

<style scoped>
.job-meter {
  min-width: 15rem;
  max-width: 24rem;
  padding: 0.25rem 0 0.4rem;
}

.job-meter__row {
  display: flex;
  align-items: baseline;
  gap: 0.5rem;
  justify-content: space-between;
}

.job-meter__state {
  font-size: 0.85rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.job-meter__state--error {
  color: var(--syp-danger);
}

.job-meter__queued {
  font-size: 0.75rem;
  color: var(--syp-muted);
}

.job-meter__bar {
  height: 0.4rem;
  margin: 0.25rem 0;
  border-radius: 0.2rem;
  background: var(--syp-surface-sunken);
  overflow: hidden;
}

.job-meter__fill {
  height: 100%;
  background: var(--syp-primary);
  transition: width 0.4s ease;
}

/* Бегущая полоскатность работает только пока задание идёт: у завершённого
   задания полоса стоит на месте и не создаёт впечатления, что что-то ещё
   происходит. */
.job-meter__fill--running {
  background-image: linear-gradient(
    45deg,
    rgb(255 255 255 / 25%) 25%,
    transparent 25%,
    transparent 50%,
    rgb(255 255 255 / 25%) 50%,
    rgb(255 255 255 / 25%) 75%,
    transparent 75%
  );
  background-size: 1rem 1rem;
  animation: job-meter-stripes 1s linear infinite;
}

.job-meter__failure {
  display: flex;
  align-items: center;
  gap: 0.35rem;
  max-width: 32rem;
  overflow: hidden;
  color: var(--syp-danger);
  font-size: 0.75rem;
}

.job-meter__failure span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.job-meter__detail {
  font-size: 0.75rem;
  color: var(--syp-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.job-meter__idle {
  font-size: 0.8rem;
  color: var(--syp-muted);
}

@keyframes job-meter-stripes {
  from {
    background-position: 1rem 0;
  }

  to {
    background-position: 0 0;
  }
}
</style>
