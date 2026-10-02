// Маршруты админки.
//
// Четыре экрана, которые уже есть у бэкенда: приём сериалов и серий,
// состояние суммы исходника, структура разобранной серии и лица. Остальные появятся вместе со
// своими задачами; пустой экран вместо «экрана ещё нет» показывать нельзя,
// поэтому неизвестный адрес отправляется на приём.

import { createRouter, createWebHistory } from 'vue-router'
import ChecksumStatusView from './views/ChecksumStatusView.vue'
import FacesView from './views/FacesView.vue'
import EpisodeIntakeView from './views/EpisodeIntakeView.vue'
import StructureView from './views/StructureView.vue'

/** Маршруты админки. */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'intake',
      component: EpisodeIntakeView,
    },
    {
      path: '/episodes/:episodeId/checksum',
      name: 'checksum',
      component: ChecksumStatusView,
      props: true,
    },
    {
      path: '/episodes/:episodeId/structure',
      name: 'structure',
      component: StructureView,
      props: true,
    },
    {
      path: '/episodes/:episodeId/faces',
      name: 'faces',
      component: FacesView,
      props: true,
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'intake' },
    },
  ],
})
