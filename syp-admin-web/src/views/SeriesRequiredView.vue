// Экран раздела, которому нужна серия. // // «Суммы» и «структура» отвечают на вопрос о конкретной
серии, а серия // выбирается в приёме. Пока её не выбрали, раздел объясняет это и отправляет // в
приём одной кнопкой. Показывать вместо этого пустой экран или отказ // нельзя: ни то ни другое не
является правдой — серия просто ещё не выбрана.

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'

defineProps<{ sectionTitle: string }>()

const catalog = useCatalogStore()
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h2 class="syp-page-title">{{ sectionTitle }}</h2>
        <p class="syp-page-lead">
          Раздел работает с одной серией. Выберите серию в приёме — и вернитесь сюда по пункту
          навигации.
        </p>
      </div>
    </div>

    <div class="card">
      <div class="card-body">
        <p class="mb-3">
          Сейчас выбран: <strong>{{ catalog.current.value?.movie.name || '—' }}</strong
          ><span v-if="!catalog.episode.value.length"> — эпизодов в нём нет.</span>
        </p>
        <RouterLink class="btn btn-primary" :to="{ name: 'intake' }">
          Перейти к приёму фильмов
        </RouterLink>
        <p class="form-text mt-3 mb-0">
          У каждой серии в таблице приёма есть кнопки «сумма» и «структура» — они выбирают серию и
          открывают нужный раздел сразу.
        </p>
      </div>
    </div>
  </section>
</template>
