// Экран списка сценариев сериала. // // Пользовательский путь по контракту (public-api.md § 5):
открыть сериал, // увидеть сценарии, выбрать нужный. Сценарий создаёт и подписывает админский //
бэкенд; здесь только чтение (ADR-0014), поэтому на экране нет кнопки // «создать» — и появление её
означало бы обещание, которого у публичной части // нет (ADR-0011). // // Состояния обязательны все
три: пусто, загрузка и отказ. Список сценариев у // сериала может быть пустым, бэкенд может не
отвечать, а «ничего не показалось» // в обоих случаях выглядит одинаково — и это неправда.

<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StateBlock from '../components/StateBlock.vue'
import { useShowcaseStore } from '../stores/showcase'

const route = useRoute()
const router = useRouter()
const store = useShowcaseStore()

onMounted(() => {
  void initialise()
})

watch(
  () => route.query.serialId,
  (value) => {
    const id = Number(value)
    if (Number.isFinite(id) && id > 0) {
      void store.selectSerial(id)
    }
  },
)

/**
 * Читает сериалы и, если сериал задан адресом, открывает его список.
 *
 * Сериал живёт в адресе, а не в памяти вкладки: перезагрузка страницы и
 * пересылка ссылки должны приводить к тому же месту.
 */
async function initialise(): Promise<void> {
  const id = Number(route.query.serialId)
  const wanted = Number.isFinite(id) && id > 0 ? id : null
  if (await store.reloadSerials()) {
    const target = wanted ?? store.serials.value[0]?.id ?? null
    if (target !== null) {
      await store.selectSerial(target)
      await router.replace({ name: 'recipes', query: { serialId: String(target) } })
    }
  }
}

/**
 * Открывает сценарий.
 *
 * @param recipeId идентификатор сценария
 */
async function open(recipeId: number): Promise<void> {
  await router.push({ name: 'recipe', params: { recipeId: String(recipeId) } })
}
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h1 class="syp-page-title">Сценарии сборки</h1>
        <p class="syp-page-lead">
          Выберите сериал и сценарий. Файл сценария вы скачаете и положите в папку, которую
          наблюдает воркер на вашей машине; сборка идёт локально, без перекодирования.
        </p>
      </div>
    </div>

    <StateBlock
      :loading="store.loading.value && store.serials.value.length === 0"
      :error="store.error.value"
      :error-code="store.errorCode.value"
      @dismiss="store.clearError()"
    />

    <div v-if="store.serials.value.length > 0" class="card mb-4">
      <div class="card-header">Сериал</div>
      <div class="card-body">
        <div class="row g-2 align-items-end">
          <div class="col-md-8">
            <label class="form-label" for="serial-choice">Сериал с размеченными сценами</label>
            <select
              id="serial-choice"
              class="form-select"
              :value="store.serialId.value ?? ''"
              @change="store.selectSerial(Number(($event.target as HTMLSelectElement).value))"
            >
              <option v-for="serial in store.serials.value" :key="serial.id" :value="serial.id">
                {{ serial.name }} — серий: {{ serial.seriesCount }}
              </option>
            </select>
          </div>
        </div>
      </div>
    </div>

    <StateBlock
      v-else-if="!store.loading.value && !store.error.value"
      empty-title="Сериалов пока нет"
      empty-text="Сценарий появляется после того, как оператор подготовит сериал и выпустит подборку."
    />

    <div v-if="store.serialName.value" class="d-flex align-items-baseline gap-2 mb-2">
      <span class="syp-unit">Сценарии сериала</span>
      <strong>{{ store.serialName.value }}</strong>
    </div>

    <div v-if="store.recipes.value.length > 0" class="row g-3">
      <div v-for="recipe in store.recipes.value" :key="recipe.id" class="col-md-6 col-xxl-4">
        <div class="card h-100">
          <div class="card-body d-flex flex-column">
            <h2 class="syp-card-title">{{ recipe.name }}</h2>
            <span class="badge mb-2 align-self-start" :class="`text-bg-${recipe.stateTone}`">
              {{ recipe.stateTitle }}
            </span>
            <div v-if="recipe.isStale" class="syp-stale small align-self-start mb-3" role="status">
              {{ recipe.staleNotice }}
            </div>
            <dl class="row mt-auto mb-0">
              <dt class="col-7">Фрагментов</dt>
              <dd class="col-5 text-end">{{ recipe.itemCount }}</dd>
              <dt class="col-7">Ожидаемая длина</dt>
              <dd class="col-5 text-end">{{ recipe.duration }}</dd>
            </dl>
            <p v-if="recipe.staleReason" class="form-text mt-2 mb-0">
              {{ recipe.staleReason }}
            </p>
          </div>
          <div class="card-footer d-grid">
            <button
              type="button"
              class="btn"
              :class="recipe.canDownload ? 'btn-primary' : 'btn-outline-secondary'"
              :disabled="!recipe.canDownload"
              @click="open(recipe.id)"
            >
              {{ recipe.actionTitle }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <StateBlock
      v-else-if="!store.loading.value && !store.error.value && store.serialName.value"
      empty-title="Сценариев нет"
      empty-text="Оператор ещё не выпустил подборку для этого сериала. Сценарий создаётся и подписывается в админской части."
    />
  </section>
</template>
