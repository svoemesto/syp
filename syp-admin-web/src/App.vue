// Корневой компонент фронтенда админки SYP. // // Здесь только каркас: заголовок, навигация между
экранами и область // содержимого. Вся работа экранов живёт в `views/`, обращение к бэкенду — в //
`api/`, состояние — в `stores/`.
<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppHeader from './components/AppHeader.vue'

/**
 * Видеофайл, для которой показывается ссылка на структуру.
 *
 * Ссылка появляется только на экране видеофайла: с приёма структуру открывать
 * нечего, а пустая ссылка вела бы на пустую страницу.
 */
</script>

<template>
  <AppHeader />
  <main class="syp-content">
    <RouterView />
  </main>
</template>

<style scoped>
main {
  font-family: system-ui, sans-serif;
  margin: 1.5rem auto;
  max-width: 72rem;
  padding: 0 1rem;
}

nav a {
  color: #1a4f8a;
}
</style>
