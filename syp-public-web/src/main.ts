// Точка входа публичной части SYP.
//
// Публичная часть только показывает результат: сериалы, сценарии и всё, что
// нужно для проверки подписи. Операций разметки здесь нет и не будет
// (constitution V, FR-085).
//
// Оформление: тот же Bootstrap 5.3 и та же тема, что в админке (ADR-0015).
// Обёртка `bootstrap-vue-next` здесь не нужна: экранов мало, интерактивных
// компонентов на них нет, а лишняя зависимость в публичной части была бы
// ballast'ом без пользы.
//
// Шрифт Roboto берётся из пакета `@fontsource-variable/roboto`: файлы `.woff2`
// попадают в сборку и отдаёт их nginx. Обращения к сети в рантайме нет.
import '@fontsource-variable/roboto'
import 'bootstrap/dist/css/bootstrap.min.css'
import './theme/theme.css'
import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router'

createApp(App).use(router).mount('#app')
