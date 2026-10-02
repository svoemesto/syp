<script setup lang="ts">
// Корневой компонент фронтенда админки SYP.
//
// Здесь только каркас: шапка с навигацией и рабочая область. Вся работа экранов
// живёт в `views/`, обращение к бэкенду — в `api/`, состояние — в `stores/`,
// оформление — в `theme/theme.css` поверх Bootstrap (ADR-0015).
//
// `BApp` обязателен: он устанавливает оркестратор тостов, которым пользуются
// экраны постановки заданий.
import { BApp } from 'bootstrap-vue-next'
import AppHeader from './components/AppHeader.vue'
import { RouterView } from 'vue-router'
</script>

<template>
  <BApp>
    <div class="syp-shell">
      <AppHeader />
      <main class="syp-content py-4">
        <RouterView />
      </main>
      <footer class="syp-content">
        <div class="syp-footer d-flex justify-content-between flex-wrap gap-2">
          <span>SYP — подготовка данных: приём, анализ, разметка, выдача сценария</span>
          <span>Видео на сервере не хранится и не передаётся (ADR-0009)</span>
        </div>
      </footer>
    </div>
  </BApp>
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
</style>
