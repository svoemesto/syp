<script setup lang="ts">
// Окно выбора базы данных.
//
// Форма повторяет `database-select-view` старого проекта: список баз и пять
// кнопок — подтвердить, редактировать, добавить, удалить, отмена.
//
// Отличие не в разметке, а в самом предмете. В старом проекте база данных была
// переключаемой: список подключений, драйвер, адрес, пользователь, пароль —
// всё это редактировалось в карточке, и удаление базы было обычной кнопкой.
// В нашем проекте база одна, она описана переменными окружения развёртывания
// (`AGENTS.md`, запрет хранения секретов вне окружения), и переключить её из
// интерфейса нельзя: смена адреса и пользователя — это изменение развёртывания,
// а не операция оператора.
//
// Поэтому форма показывает правду: база одна, параметры названы, пароль не
// показывается, а кнопки правки и удаления объясняют, почему они не делают
// того, что делали в старом проекте. Молчаливый отказ или, того хуже, кнопка,
// которая стирает базу, здесь недопустимы.

import { ref } from 'vue'
import DatabaseEditDialog from './DatabaseEditDialog.vue'

/** Единственная база проекта: значения взяты из `deploy/.env.example`. */
const database = {
  id: 'syp',
  name: 'syp',
  driver: 'PostgreSQL',
  url: 'jdbc:postgresql://syp-db:5432/syp',
  user: 'syp',
  passwordShown: false,
}

const emit = defineEmits<{ closed: [] }>()

/** Выбрана ли база. */
const selected = ref(true)

/** Открыта ли карточка базы. */
const editing = ref(false)

/** Ответ последнего действия. */
const notice = ref('')

/** Подтверждает выбор и закрывает окно. */
function select(): void {
  if (!selected.value) {
    notice.value = 'База не выбрана: подтверждать нечего'
    return
  }
  emit('closed')
}
</script>

<template>
  <div class="database-dialog" role="dialog" aria-modal="true" aria-label="Выбор базы данных">
    <h2 class="syp-card-title">Выбор базы данных</h2>

    <p class="stub">
      Заглушка в том, что базу нельзя переключить из интерфейса: в проекте база одна, и параметры
      подключения задаются переменными окружения развёртывания (`SYP_DB_HOST_PORT`, `SYP_DB_NAME`,
      `SYP_DB_USER`, `SYP_DB_PASSWORD`). Список баз переключаемым быть не может — иначе правка
      адреса и пользователя была бы операцией оператора, а не изменением развёртывания.
    </p>

    <table class="table table-sm">
      <thead>
        <tr>
          <th>База данных</th>
        </tr>
      </thead>
      <tbody>
        <tr :class="{ picked: selected }" @click="selected = !selected">
          <td>{{ database.name }}</td>
        </tr>
      </tbody>
    </table>

    <div class="dialog-actions">
      <button type="button" class="btn btn-sm btn-primary" @click="select">Подтвердить</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="editing = true">
        Редактировать базу данных
      </button>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        @click="
          notice =
            'Новую базу добавить нельзя: проект работает с одной базой, и вторая база означала бы второе развёртывание. Это изменение окружения, а не операция оператора'
        "
      >
        Добавить новую базу данных
      </button>
      <button
        type="button"
        class="btn btn-sm btn-outline-danger"
        @click="
          notice =
            'Удаление базы не выполняется: в ней фильмы, события, лица и свойства. Кнопка оставлена на месте, чтобы было видно, что действия нет, а не чтобы стереть данные'
        "
      >
        Удалить выбранную базу данных
      </button>
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="emit('closed')">
        Отмена
      </button>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

    <DatabaseEditDialog v-if="editing" :database="database" @closed="editing = false" />
  </div>
</template>

<style scoped>
.database-dialog {
  position: fixed;
  inset: 50% auto auto 50%;
  transform: translate(-50%, -50%);
  z-index: 30;
  min-width: 24rem;
  max-width: 34rem;
  background: var(--syp-surface);
  border: 1px solid var(--syp-border);
  border-radius: 0.4rem;
  box-shadow: 0 1rem 3rem rgb(0 0 0 / 45%);
  padding: 1rem;
}
.stub {
  font-size: 0.78rem;
  color: var(--syp-text-muted);
  background: var(--syp-raised);
  border: 1px solid var(--syp-border);
  border-radius: 0.3rem;
  padding: 0.5rem;
}
.table tbody tr {
  cursor: pointer;
}
.table tbody tr.picked {
  background: var(--syp-tint);
}
.dialog-actions {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.notice {
  font-size: 0.8125rem;
  margin-top: 0.5rem;
}
</style>
