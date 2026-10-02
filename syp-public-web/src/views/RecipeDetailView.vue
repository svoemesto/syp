// Экран сценария: состав, скачивание файла, подпись и открытый ключ. // // Три вещи, которые
пользователю нужно увидеть до того, как он начнёт работу: // // 1. **что войдёт в подборку** —
состав фрагментов с обеими парами границ; // 2. **чем реальная сборка отличается от расчётной** —
округление до // ближайшего ключевого кадра (ADR-0006), и решение об этом принимается до // работы,
а не после; // 3. **чем подписано** — подпись, сумма содержимого и открытый ключ приходят // вместе
(FR-089c), показывать их по отдельности незачем. // // Скачивание — обычная ссылка на файл сценария:
браузер сохранит его под // именем, которое задал сервер. Автоматической доставки нет и не
планируется // (ADR-0009): файл пользователь кладёт в папку сам.

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StateBlock from '../components/StateBlock.vue'
import { recipeFileUrl } from '../api/recipes'
import { useShowcaseStore } from '../stores/showcase'

const route = useRoute()
const router = useRouter()
const store = useShowcaseStore()

/** Идентификатор сценария из адреса. */
const recipeId = computed(() => Number(route.params.recipeId))

/** Адрес файла сценария для сохранения. */
const fileUrl = computed(() => recipeFileUrl(recipeId.value))

onMounted(() => {
  void store.openRecipe(recipeId.value)
})

watch(recipeId, (next) => {
  void store.openRecipe(next)
})
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h1 class="syp-page-title">{{ store.detail.value?.name ?? 'Сценарий' }}</h1>
        <p class="syp-page-lead">
          Состав подборки и данные для проверки подписи. Файл сценария сохраните в папку, которую
          наблюдает воркер на вашей машине.
        </p>
      </div>
      <div class="btn-group">
        <a
          v-if="store.detail.value?.canDownload"
          class="btn btn-primary"
          :href="fileUrl"
          :download="`${store.detail.value.name}.syp-recipe.json`"
        >
          скачать файл сценария
        </a>
        <button type="button" class="btn btn-outline-secondary" @click="router.back()">
          назад
        </button>
      </div>
    </div>

    <StateBlock
      :loading="store.loading.value && store.detail.value === null"
      :error="store.error.value"
      :error-code="store.errorCode.value"
      @dismiss="store.clearError()"
    />

    <template v-if="store.detail.value">
      <div class="row g-4">
        <div class="col-xxl-8">
          <div class="card">
            <div class="card-header d-flex justify-content-between align-items-center">
              <span>Фрагменты подборки</span>
              <span class="syp-unit">
                {{ store.detail.value.items.length }} шт., длина {{ store.detail.value.duration }},
                кадров {{ store.detail.value.frameCount }}
              </span>
            </div>

            <div v-if="store.detail.value.items.length > 0" class="table-responsive">
              <table class="table align-middle">
                <thead>
                  <tr>
                    <th scope="col">№</th>
                    <th scope="col">Сцена</th>
                    <th scope="col">Персоны, место</th>
                    <th scope="col">Кадры по расчёту</th>
                    <th scope="col">Кадры нарезки</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="item in store.detail.value.items" :key="item.ordinal">
                    <td class="syp-number">{{ item.ordinal }}</td>
                    <td>
                      <div>{{ item.title }}</div>
                      <div class="syp-path">{{ item.source }}</div>
                      <div v-if="item.roundingNote" class="syp-unit">{{ item.roundingNote }}</div>
                    </td>
                    <td>
                      <div>{{ item.persons }}</div>
                      <div class="syp-unit">{{ item.location }}</div>
                    </td>
                    <td class="syp-number">{{ item.plannedFrames }}</td>
                    <td class="syp-number">{{ item.cutFrames }}</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <StateBlock
              v-else
              :error="''"
              empty-title="Сценарий пуст"
              empty-text="Ни один фрагмент в сценарий не попал — скачивать нечего."
            />

            <div class="card-body border-top">
              <p class="form-text mb-0">
                Расчётные и фактические границы показаны обе: фрагмент вырезается по ближайшему
                ключевому кадру, поэтому границы могут разойтись на несколько кадров.
                Перекодирования нет — качество не страдает (ADR-0006).
              </p>
            </div>
          </div>
        </div>

        <div class="col-xxl-4">
          <div class="card mb-4">
            <div class="card-header">Состояние</div>
            <div class="card-body">
              <span class="badge mb-3" :class="`text-bg-${store.detail.value.stateTone}`">
                {{ store.detail.value.stateTitle }}
              </span>
              <div v-if="store.detail.value.isStale" class="syp-stale my-3" role="status">
                {{ store.detail.value.staleNotice }}
                <div v-if="store.detail.value.staleReason" class="mt-1 form-text">
                  {{ store.detail.value.staleReason }}
                </div>
              </div>
              <p class="form-text mb-0">
                Устаревший сценарий всё равно проверяем по своему ключу, пока тот доверенный: файл,
                подпись и идентификатор ключа остаются прежними. Помеченный сценарий скачивается —
                запрещать его было бы запретом того, что контракт разрешает (FR-090).
              </p>
            </div>
          </div>

          <div v-if="store.signature.value" class="card">
            <div class="card-header">Проверка подписи</div>
            <div class="card-body">
              <dl class="row mb-0">
                <dt class="col-5">Алгоритм</dt>
                <dd class="col-7">{{ store.signature.value.algorithm }}</dd>

                <dt class="col-5">Ключ</dt>
                <dd class="col-7 syp-mono">{{ store.signature.value.signingKeyId }}</dd>

                <dt class="col-5">Действует с</dt>
                <dd class="col-7">{{ store.signature.value.notBefore }}</dd>
              </dl>

              <p class="syp-unit mt-3 mb-1">Сумма содержимого</p>
              <p class="syp-mono text-break mb-3">{{ store.signature.value.contentSha256 }}</p>

              <p class="syp-unit mt-3 mb-1">Подпись</p>
              <p class="syp-mono text-break mb-3">{{ store.signature.value.signature }}</p>

              <p class="syp-unit mt-3 mb-1">Открытый ключ в PEM</p>
              <pre class="syp-pem">{{ store.signature.value.publicKeyPem }}</pre>

              <p class="form-text mb-0">
                Воркер проверяет подпись <strong>до</strong> выполнения сценария и суммы файлов
                <strong>до</strong> нарезки: подмена файла иначе обнаружилась бы через час работы.
                Ключ нужен в настройках воркера.
              </p>
            </div>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>
