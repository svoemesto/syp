<script setup lang="ts">
// Корневой компонент публичной части SYP.
//
// Публичная часть только показывает результат: сериалы, сценарии и всё, что
// нужно для проверки подписи. Операций разметки здесь нет и не будет
// (constitution V, FR-085).
//
// Оформление: та же тема, что в админке, поверх того же Bootstrap
// (ADR-0015). Различаются только ширина и тон: админка рассчитана на 1920 и
// плотную таблицу, публичная часть — на чтение с экрана пользователя.
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <div class="syp-shell">
    <header class="syp-header border-bottom bg-white">
      <div class="syp-content">
        <RouterLink :to="{ name: 'recipes' }" class="syp-brand text-decoration-none">
          <span class="syp-brand-mark">SYP</span>
          <span class="syp-brand-sub">Show Your Picture</span>
        </RouterLink>
      </div>
    </header>

    <main class="syp-content py-4">
      <RouterView />
    </main>

    <footer class="syp-content">
      <div class="syp-footer d-flex justify-content-between flex-wrap gap-2">
        <span>Видео не хранится на сервере и не передаётся: вы получаете сценарий сборки</span>
        <span>Сборка выполняется на вашей машине (ADR-0009)</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.syp-shell {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

main {
  flex: 1 0 auto;
}

.syp-header {
  padding: 0.75rem 0;
}

.syp-brand {
  align-items: baseline;
  display: inline-flex;
  gap: 0.5rem;
}

.syp-brand-mark {
  color: var(--syp-primary);
  font-size: 1.25rem;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.syp-brand-sub {
  color: var(--syp-text-muted);
  font-size: 0.9375rem;
}
</style>
