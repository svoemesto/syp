// Точка входа фронтенда админки SYP.
//
// Админка отвечает за подготовку данных: приём сериалов и серий, разметку,
// анализ, обучение моделей и очередь заданий. Смешивать эту ответственность с
// публичной частью запрещено (constitution V).
//
// Оформление: Bootstrap 5.3 как основа и своя тема поверх него (ADR-0015).
// `bootstrap-vue-next` подключается точечно — ради `BApp` и тостов, чем админка
// Karaoke пользуется: кнопка постановки задания обязана сказать, что задание
// поставлено, иначе оператор жмёт её второй раз.
import 'bootstrap/dist/css/bootstrap.min.css'
import './theme/theme.css'
import { createApp } from 'vue'
import { BApp, createBootstrap } from 'bootstrap-vue-next'
import App from './App.vue'
import { router } from './router'

createApp(App).use(router).use(createBootstrap()).component('BApp', BApp).mount('#app')
