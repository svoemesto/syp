<script setup lang="ts">
// Окно выбора базы данных.
//
// Форма повторяет `database-select-view` старого проекта: список баз и пять
// кнопок — подтвердить, редактировать, добавить, delete, отмена.
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
  <div class="database-dialog" role="dialog" aria-modal="true" aria-label="База данных">
    <h2 class="syp-card-title">База данных</h2>

    <p class="stub">
      The stub is that the database cannot be switched from the interface: the project has one
      database, and the connection parameters are set by the deployment environment variables
      (`SYP_DB_HOST_PORT`, `SYP_DB_NAME`, `SYP_DB_USER`, `SYP_DB_PASSWORD`). The list of databases
      cannot be switchable — otherwise changing the address and the user would be an operator
      action, not a change of the deployment.
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
      <button type="button" class="btn btn-sm btn-primary" @click="select">OK</button>
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="editing = true">
        Редактировать базу данных
      </button>
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary"
        @click="
          notice =
            'A new database cannot be added: the project works with one database, and a second one would mean a second deployment. That is a change of the environment, not an operator action'
        "
      >
        Добавить новую базу данных
      </button>
      <button
        type="button"
        class="btn btn-sm btn-outline-danger"
        @click="
          notice =
            'Deleting the database is not performed: it holds filters, events, faces and properties. The button is left in place so that it is visible that there is no action, not to erase the data'
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
  min-width: 18.75rem;
  max-width: 18.75rem;
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
