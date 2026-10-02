// Маршруты админки.
//
// Четыре экрана, которые уже есть у бэкенда: приём сериалов и серий,
// состояние суммы исходника, структура разобранной серии и лица. Остальные появятся вместе со
// своими задачами; пустой экран вместо «экрана ещё нет» показывать нельзя,
// поэтому неизвестный адрес отправляется на приём.

import { createRouter, createWebHistory } from 'vue-router'
import ChecksumStatusView from './views/ChecksumStatusView.vue'
import FacesView from './views/FacesView.vue'
import SeriesIntakeView from './views/SeriesIntakeView.vue'
import StructureView from './views/StructureView.vue'

/** Маршруты админки. */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'intake',
      component: SeriesIntakeView,
    },
    {
      path: '/series/:seriesId/checksum',
      name: 'checksum',
      component: ChecksumStatusView,
      props: true,
    },
    {
      path: '/series/:seriesId/structure',
      name: 'structure',
      component: StructureView,
      props: true,
    },
    {
      path: '/series/:seriesId/faces',
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
