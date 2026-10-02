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
//
// Шрифт Roboto берётся из пакета `@fontsource-variable/roboto`: файлы `.woff2`
// попадают в сборку и отдаёт их nginx. Обращения к сети в рантайме нет, и
// доступность Google Fonts на работу интерфейса не влияет.
//
// Подписка на уведомления SSE открывается один раз на приложение: оператор
// ставит задание на одном экране и смотрит на другой, и ход работы должен быть
// виден в обоих случаях. Идентификатор вкладки генерирует клиент и передаёт
// в адресе — так различаются вкладки одного браузера.
import '@fontsource-variable/roboto'
import 'bootstrap/dist/css/bootstrap.min.css'
import './theme/theme.css'
import { createApp } from 'vue'
import { BApp, createBootstrap } from 'bootstrap-vue-next'
import App from './App.vue'
import { router } from './router'
import { startNotifications } from './stores/notifications'

createApp(App).use(router).use(createBootstrap()).component('BApp', BApp).mount('#app')

startNotifications()
