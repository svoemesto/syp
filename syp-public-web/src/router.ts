// Маршруты публичной части.
//
// Два адреса, и оба осмысленные: список сценариев проекта и сам сценарий.
// Сценарий — ресурс с адресом, а не состояние вкладки, поэтому перезагрузка
// страницы и пересылка ссылки приводят к тому же месту.
//
// Неизвестный адрес отправляется к списку: пустая страница без объяснения
// выглядит как поломка.

import { createRouter, createWebHistory } from 'vue-router'
import RecipeDetailView from './views/RecipeDetailView.vue'
import RecipeListView from './views/RecipeListView.vue'

/** Маршруты публичной части. */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'recipes',
      component: RecipeListView,
    },
    {
      path: '/recipes/:recipeId',
      name: 'recipe',
      component: RecipeDetailView,
      props: true,
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'recipes' },
    },
  ],
})
