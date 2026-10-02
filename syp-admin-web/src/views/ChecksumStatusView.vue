// Экран состояния суммы исходника (задача T044). // // Экран отвечает на вопрос, который оператор
задаёт чаще всего: «файл у меня // такой же, как у вас?» Сценарий сборки выдаётся только при
актуальной сумме // (FR-089), поэтому показывается не «есть/нет», а состояние подсчёта: что //
считается, когда посчитано, почему сумма устарела и что было раньше.

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StateBlock from '../components/StateBlock.vue'
import { useChecksumStore } from '../stores/checksum'
import { useNotify } from '../ui/notify'

const route = useRoute()
const router = useRouter()
const store = useChecksumStore()
const notify = useNotify()

/** Идентификатор серии из адреса. */
const seriesId = computed(() => Number(route.params.seriesId))

/** Тон сообщения о состоянии подсчёта. */
const toneClass = computed(() => {
  switch (store.checksum.value?.stateTone) {
    case 'success':
      return 'alert-success'
    case 'warning':
      return 'alert-warning'
    case 'danger':
      return 'alert-danger'
    default:
      return 'alert-info'
  }
})

onMounted(() => {
  void store.reload(seriesId.value)
})

watch(seriesId, (next) => {
  void store.reload(next)
})

/** Ставит пересчёт и перечитывает состояние. */
async function recalculate(): Promise<void> {
  if (await store.recalculate(seriesId.value)) {
    notify('Пересчёт поставлен в очередь. Экран можно закрыть — работа идёт заданием.', 'info')
  }
}
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h2 class="syp-page-title">Состояние суммы исходника</h2>
        <p class="syp-page-lead">
          Сумма <span class="syp-mono">SHA-256</span> файла серии — эталон, с которым машина
          пользователя сверяет файл <em>до</em> нарезки. Без актуальной суммы сценарий сборки
          выдавать нельзя.
        </p>
      </div>
      <div class="btn-group">
        <button
          type="button"
          class="btn btn-primary"
          :disabled="store.loading.value"
          @click="recalculate"
        >
          поставить пересчёт
        </button>
        <button type="button" class="btn btn-outline-secondary" @click="router.back()">
          назад
        </button>
      </div>
    </div>

    <StateBlock
      :loading="store.loading.value && store.checksum.value === null"
      :error="store.error.value"
      :error-code="store.errorCode.value"
      @dismiss="store.clearError()"
    />

    <div class="row g-4">
      <div class="col-xxl-5">
        <div v-if="store.checksum.value" class="card">
          <div class="card-header">Подсчёт</div>
          <div class="card-body">
            <div class="alert mb-0" :class="toneClass" role="status">
              <strong>{{ store.checksum.value.stateTitle }}</strong>
              <div v-if="store.checksum.value.isRunning" class="form-text">
                Подсчёт идёт заданием, экран не блокируется.
              </div>
              <div v-if="store.checksum.value.errorText" class="mt-2">
                {{ store.checksum.value.errorText }}
              </div>
            </div>

            <dl class="row mt-3 mb-0">
              <dt class="col-sm-5">Алгоритм</dt>
              <dd class="col-sm-7">{{ store.checksum.value.algorithm }}</dd>

              <dt class="col-sm-5">Пригодна для сверки</dt>
              <dd class="col-sm-7">
                <span
                  class="badge"
                  :class="store.checksum.value.isUsable ? 'text-bg-success' : 'text-bg-secondary'"
                >
                  {{ store.checksum.value.isUsable ? 'да' : 'нет' }}
                </span>
              </dd>

              <dt class="col-sm-5">Устарела</dt>
              <dd class="col-sm-7">
                {{ store.checksum.value.isStale ? 'да, файл изменился после подсчёта' : 'нет' }}
              </dd>

              <dt class="col-sm-5">Посчитана</dt>
              <dd class="col-sm-7">{{ store.checksum.value.computedAt }}</dd>

              <dt class="col-sm-5">Размер при подсчёте</dt>
              <dd class="col-sm-7">{{ store.checksum.value.byteSize }}</dd>

              <dt class="col-sm-5">Время изменения файла</dt>
              <dd class="col-sm-7">{{ store.checksum.value.fileMtime }}</dd>

              <dt class="col-sm-5">Записей пересчётов</dt>
              <dd class="col-sm-7">{{ store.checksum.value.historyCount }}</dd>
            </dl>
          </div>
        </div>
      </div>

      <div class="col-xxl-7">
        <div class="card h-100">
          <div class="card-header">Значение суммы</div>
          <div class="card-body">
            <template v-if="store.checksum.value">
              <p class="syp-mono mb-3 text-break">{{ store.checksum.value.digest }}</p>
            </template>
            <p v-else class="mb-3 text-body-secondary">
              Сумма ещё не считалась. Поставьте пересчёт — он пойдёт заданием и не заблокирует
              экран.
            </p>

            <p class="form-text mb-2">
              Проверить сумму у себя можно командой
              <span class="syp-mono">sha256sum путь/к/файлу</span> — формат значения совпадает.
              Чтение большого файла занимает десятки секунд, поэтому подсчёт идёт заданием.
            </p>
            <p class="form-text mb-0">
              Повторный пересчёт не затирает прежнюю запись: она остаётся в истории и помечается
              устаревшей. Актуальной остаётся ровно одна сумма на серию.
            </p>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
