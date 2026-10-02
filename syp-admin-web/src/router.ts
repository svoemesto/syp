// Маршруты админки.
//
// Два экрана, которые уже есть у бэкенда: приём сериалов и серий и состояние
// суммы исходника. Остальные появятся вместе со своими задачами; пустой экран
// вместо «экрана ещё нет» показывать нельзя, поэтому неизвестный адрес
// отправляется на приём.

import { createRouter, createWebHistory } from 'vue-router'
import ChecksumStatusView from './views/ChecksumStatusView.vue'
import SeriesIntakeView from './views/SeriesIntakeView.vue'

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
      path: '/:pathMatch(.*)*',
      redirect: { name: 'intake' },
    },
  ],
})
