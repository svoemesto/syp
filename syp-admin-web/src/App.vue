// Корневой компонент фронтенда админки SYP. // // Здесь только каркас: заголовок, навигация между
экранами и область // содержимого. Вся работа экранов живёт в `views/`, обращение к бэкенду — в //
`api/`, состояние — в `stores/`.
<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()

/**
 * Серия, для которой показывается ссылка на структуру.
 *
 * Ссылка появляется только на экране серии: с приёма структуру открывать
 * нечего, а пустая ссылка вела бы на пустую страницу.
 */
const structureEpisodeId = computed(() => {
  const raw = route.params.episodeId
  if (typeof raw === 'string' && raw !== '') {
    return raw
  }
  return null
})
</script>

<template>
  <main>
    <header>
      <h1>SYP — админка</h1>
      <nav>
        <RouterLink :to="{ name: 'intake' }"> Приём сериалов и серий </RouterLink>
        <RouterLink
          v-if="structureEpisodeId"
          :to="{ name: 'structure', params: { episodeId: structureEpisodeId } }"
        >
          Структура серии
        </RouterLink>
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
