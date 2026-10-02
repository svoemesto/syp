// Состояния экрана: загрузка, пусто и отказ. // // Тот же набор, что в админке, по той же причине:
это одно решение, а не три. // Пользователь должен одинаково понимать одно и то же место на экране в
обеих // частях системы.

<script setup lang="ts">
withDefaults(
  defineProps<{
    /** Идёт ли обращение к бэкенду. */
    loading?: boolean
    /** Текст отказа; пустой текст означает, что отказа нет. */
    error?: string
    /** Машинный код отказа; пустой код означает, что его нет. */
    errorCode?: string
    /** Заголовок пустого состояния. */
    emptyTitle?: string
    /** Пояснение пустого состояния. */
    emptyText?: string
    /** Что показать при загрузке. */
    loadingText?: string
  }>(),
  {
    loading: false,
    error: '',
    errorCode: '',
    emptyTitle: '',
    emptyText: '',
    loadingText: 'Запрос к бэкенду…',
  },
)

const emit = defineEmits<{ dismiss: [] }>()
</script>

<template>
  <div v-if="loading" class="syp-loading" role="status" aria-live="polite">
    <span class="spinner-border spinner-border-sm text-primary" aria-hidden="true" />
    <span>{{ loadingText }}</span>
  </div>

  <div v-else-if="error" class="syp-error" role="alert">
    <div class="d-flex justify-content-between align-items-start gap-3">
      <div>
        <span v-if="errorCode" class="syp-mono text-danger me-2">{{ errorCode }}</span>
        <span>{{ error }}</span>
      </div>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary flex-shrink-0"
        @click="emit('dismiss')"
      >
        скрыть
      </button>
    </div>
  </div>

  <div v-else-if="emptyTitle" class="syp-empty">
    <div class="syp-empty-title">{{ emptyTitle }}</div>
    <div v-if="emptyText">{{ emptyText }}</div>
    <slot name="empty-action" />
  </div>

  <slot />
</template>
