// Заглушка раздела, экрана у которого ещё нет. // // Раздел без экрана — это «в работе», а не
ошибка: показывать отказ на пункте // меню, который заведомо недостроен, значит врать оператору о
том, что // сломалось. Здесь написано, что раздел делает, чего в нём пока нет и откуда // имеет
смысл начать; срок не выдумывается — он неизвестен до решения владельца.

<script setup lang="ts">
defineProps<{
  /** Название раздела. */
  title: string
  /** Что раздел делает — одна фраза. */
  purpose: string
  /** Чего в разделе пока нет. */
  missing: string
  /** Раздел, откуда имеет смысл начать. */
  hint?: string
}>()
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h2 class="syp-page-title">{{ title }}</h2>
        <p class="syp-page-lead">{{ purpose }}</p>
      </div>
      <span class="badge text-bg-light border">в работе</span>
    </div>

    <div class="card">
      <div class="card-body">
        <p class="mb-2">Раздел ещё не сделан. Что в нём будет:</p>
        <p class="mb-0 text-body-secondary">{{ missing }}</p>
      </div>
      <div v-if="hint" class="card-footer text-body-secondary">{{ hint }}</div>
    </div>
  </section>
</template>
