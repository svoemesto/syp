// Маршруты админки.
//
// Четыре экрана, которые уже есть у бэкенда: приём проектов и видеофайлов,
// состояние суммы исходника, структура разобранного видеофайла и лица. Остальные появятся вместе со
// своими задачами; пустой экран вместо «экрана ещё нет» показывать нельзя,
// поэтому неизвестный адрес отправляется на приём.

import { createRouter, createWebHistory } from 'vue-router'
import ActionsView from './views/ActionsView.vue'
import ChecksumStatusView from './views/ChecksumStatusView.vue'
import FacesView from './views/FacesView.vue'
import VideofileIntakeView from './views/VideofileIntakeView.vue'
import StructureView from './views/StructureView.vue'
import EditorView from './views/EditorView.vue'

/** Маршруты админки. */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'intake',
      component: VideofileIntakeView,
    },
    {
      path: '/videofiles/:videofileId/checksum',
      name: 'checksum',
      component: ChecksumStatusView,
      props: true,
    },
    {
      path: '/videofiles/:videofileId/structure',
      name: 'structure',
      component: StructureView,
      props: true,
    },
    {
      // Главный редактор: слева планы, справа четыре вкладки.
      path: '/videofiles/:videofileId/editor',
      name: 'editor',
      component: EditorView,
      props: true,
    },
    {
      path: '/videofiles/:videofileId/faces',
      name: 'faces',
      component: FacesView,
      props: true,
    },
    {
      // Операции над проектом: таблица файлов с индикаторами конвейера,
      // пятнадцать операций и запуск. Экран работает по проекту, а не по
      // видеофайлу: операции применяются сразу ко всем файлам проекта.
      path: '/projects/:projectId/actions',
      name: 'actions',
      component: ActionsView,
      props: true,
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'intake' },
    },
  ],
})
