// Корневой компонент фронтенда админки SYP. // // Здесь только каркас: заголовок, навигация между
экранами и область // содержимого. Вся работа экранов живёт в `views/`, обращение к бэкенду — в //
`api/`, состояние — в `stores/`.
<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <main>
    <header>
      <h1>SYP — админка</h1>
      <nav>
        <RouterLink :to="{ name: 'intake' }"> Приём сериалов и серий </RouterLink>
      </nav>
    </header>
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
