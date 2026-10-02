// Маршруты админки.
//
// Экраны, за которыми стоит бэкенд: приём сериалов и серий, состояние суммы
// исходника, структура разобранной серии и лица. Раздел без экрана — сценарии —
// тоже имеет адрес: пункт навигации не должен исчезать из меню из-за того, что он
// ещё не сделан, а пустой экран вместо «раздела ещё нет» — это ошибка, которой
// не было.
//
// Сумма, структура и лица живут для конкретной серии. Пока серия не выбрана,
// раздел открывается на странице с пояснением: молча уводить в приём значило бы
// потерять, что оператор собирался посмотреть.

import { createRouter, createWebHistory } from 'vue-router'
import ChecksumStatusView from './views/ChecksumStatusView.vue'
import FacesView from './views/FacesView.vue'
import SectionPlaceholder from './components/SectionPlaceholder.vue'
import SeriesIntakeView from './views/SeriesIntakeView.vue'
import SeriesRequiredView from './views/SeriesRequiredView.vue'
import StructureView from './views/StructureView.vue'

/** Что обещает раздел «Сценарии», пока экрана нет. */
const recipesPlaceholder = {
  title: 'Сценарии',
  purpose: 'Фильтры, выбор сцен, сборка сценария и его подпись.',
  missing:
    'Три уровня условий фильтра, применение, сохранённые фильтры и выдача ' +
    'сценария сборки с подписью. Выдаёт сценарий админский бэкенд, где живёт ' +
    'закрытый ключ подписи (ADR-0014).',
  hint: 'Состав сценария пользователь увидит в публичной части после выдачи.',
}

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
      path: '/series/checksum',
      name: 'sums',
      component: SeriesRequiredView,
      props: { sectionTitle: 'Суммы исходника' },
    },
    {
      path: '/series/structure',
      name: 'structure',
      component: SeriesRequiredView,
      props: { sectionTitle: 'Структура серии' },
    },
    {
      path: '/series/faces',
      name: 'faces',
      component: SeriesRequiredView,
      props: { sectionTitle: 'Лица серии' },
    },
    {
      path: '/series/:seriesId/checksum',
      name: 'checksum',
      component: ChecksumStatusView,
      props: true,
    },
    {
      path: '/series/:seriesId/structure',
      name: 'structure-of-series',
      component: StructureView,
      props: true,
    },
    {
      path: '/series/:seriesId/faces',
      name: 'faces-of-series',
      component: FacesView,
      props: true,
    },
    {
      path: '/recipes',
      name: 'recipes',
      component: SectionPlaceholder,
      props: recipesPlaceholder,
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'intake' },
    },
  ],
})
