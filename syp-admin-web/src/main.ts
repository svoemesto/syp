// Точка входа фронтенда админки SYP.
//
// Админка отвечает за подготовку данных: приём фильмов и эпизодов, разметку,
// анализ, обучение моделей и очередь заданий. Смешивать эту ответственность с
// публичной частью запрещено (constitution V).
import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'

createApp(App).use(router).mount('#app')
