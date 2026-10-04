<script setup lang="ts">
// Карточка базы данных.
//
// Форма повторяет `database-edit-view` старого проекта: идентификатор (недоступен
// для правки), имя, драйвер, адрес, пользователь, пароль и две кнопки.
//
// Два отличия внесены намеренно:
//
// * пароль — поле с маской, а не обычное текстовое поле: в старом проекте пароль
//   был виден на экране целиком, и это читалось с чужого места;
// * поля не правятся, и это сказано прямо, вместо того чтобы дать оператору
//   внести изменение, которое развёртывание всё равно перезапишет при старте.

import { ref } from 'vue'

const props = defineProps<{ database: { id: string; name: string; driver: string; url: string; user: string } }>()

const emit = defineEmits<{ closed: [] }>()

/** Ответ последнего действия. */
const notice = ref('')
</script>

<template>
  <div class="database-card" role="dialog" aria-modal="true" aria-label="Карточка базы данных">
    <h2 class="syp-card-title">Карточка базы данных</h2>

    <p class="stub">
      Поля не правятся: параметры подключения читаются при старте из окружения
      развёртывания, и изменение здесь было бы стёрто следующим запуском. Место
      для правки — `deploy/.env`.
    </p>

    <div class="row">
      <label for="db-id">ID:</label>
      <input id="db-id" class="form-control form-control-sm" type="text" :value="props.database.id" disabled />
    </div>
    <div class="row">
      <label for="db-name">Имя:</label>
      <input id="db-name" class="form-control form-control-sm" type="text" :value="props.database.name" disabled />
    </div>
    <div class="row">
      <label for="db-driver">Драйвер:</label>
      <input
        id="db-driver"
        class="form-control form-control-sm"
        type="text"
        :value="props.database.driver"
        disabled
      />
    </div>
    <div class="row">
      <label for="db-url">Адрес:</label>
      <input id="db-url" class="form-control form-control-sm" type="text" :value="props.database.url" disabled />
    </div>
    <div class="row">
      <label for="db-user">Пользователь:</label>
      <input id="db-user" class="form-control form-control-sm" type="text" :value="props.database.user" disabled />
    </div>
    <div class="row">
      <label for="db-password">Пароль:</label>
      <input
        id="db-password"
        class="form-control form-control-sm"
        type="password"
        :value="props.database.user"
        disabled
        title="Пароль не показывается: секреты хранятся только в окружении развёртывания"
      />
    </div>

    <div class="dialog-actions">
      <button
        type="button"
        class="btn btn-sm btn-primary"
        @click="notice = 'Сохранения нет: поля берутся из окружения развёртывания при каждом старте'"
      >
        Подтвердить
      </button>
      <button type="button" class="btn btn-sm btn-outline-secondary" @click="emit('closed')">Отмена</button>
    </div>

    <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>
  </div>
</template>

<style scoped>
.database-card {
  position: fixed;
  inset: 50% auto auto 50%;
  transform: translate(-50%, -50%);
  z-index: 31;
  min-width: 26rem;
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
.row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-top: 0.35rem;
}
.row label {
  flex: 0 0 9rem;
  font-size: 0.8125rem;
}
.dialog-actions {
  display: flex;
  gap: 0.5rem;
  margin-top: 0.75rem;
}
.notice {
  font-size: 0.8125rem;
  margin-top: 0.5rem;
}
</style>
