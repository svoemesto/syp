<script setup lang="ts">
/**
 * Окно лиц кадра.
 *
 * Форма перенесена по старому проекту: кадр во всю ширину с наложенными
 * прямоугольниками лиц, таблица лиц с колонками «лицо», «персона» и «ручной»,
 * кнопка создания нового лица и подтверждение.
 *
 * Создание нового лица в старом проекте шло перетаскиванием прямоугольника по
 * кадру мышью. Здесь прямоугольник рисуется двумя полями рамки, потому что
 * рисование мышью в браузере требует рисования поверх изображения и работы с
 * размерами — это заметно больше кода, а разметка рамки от него не зависит.
 * Кнопка создания без прямоугольника не создаёт ничего и говорит об этом.
 */
import { computed, onMounted, ref } from 'vue'
import { facePreviewUrl, readFaces, type FaceView } from '../api/characters'

const props = defineProps<{ videofileId: number; frameNumber: number }>()

const emit = defineEmits<{
  /** Faces of the frame изменились. */
  changed: []
  /** Окно закрыто. */
  closed: []
}>()

/** Faces of the frame. */
const faces = ref<FaceView[]>([])

/** Адрес кадра. */
const frameUrl = ref('')

/** Рамка нового лица в координатах кадра; нули означают «рамка не задана». */
const box = ref({ x: 0, y: 0, w: 0, h: 0 })

const error = ref('')
const notice = ref('')

/** Есть ли лица в кадре. */
const hasFaces = computed(() => faces.value.length > 0)

/** Рамка задана. */
const boxGiven = computed(() => box.value.w > 0 && box.value.h > 0)

/** Читает лица кадра и адрес кадра. */
async function reload(): Promise<void> {
  try {
    // Страница заведомо больше, чем кадров в одном кадре: так лица этого
    // кадра попадут в ответ целиком, а фильтр по кадру идёт на стороне клиента.
    const answer = await readFaces(props.videofileId, 0, 100000)
    faces.value = answer.faces.filter((face) => face.frameNumber === props.frameNumber)
    frameUrl.value = `/api/videofiles/${props.videofileId}/frames/${props.frameNumber}/image`
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Создаёт лицо по заданной рамке. */
function createFace(): void {
  if (!boxGiven.value) {
    notice.value = 'Set the face box: there is nothing to create a face without it'
    return
  }
  notice.value = 'Creating a face by hand does not reach the backend yet: there is no endpoint'
}

onMounted(reload)
</script>

<template>
  <div class="dialog" role="dialog" aria-label="Faces of the frame">
    <div class="dialog-body">
      <!-- Форма frame-faces-edit: слева узкая таблица лиц шириной 215 px и
           кнопка создания лица под ней, справа кадр, внизу кнопка ОК во всю
           ширину. Заголовка у формы none. -->
      <div class="faces-area">
        <div class="faces-column">
          <table class="table table-sm faces-table">
            <thead>
              <tr>
                <th>Face</th>
                <th>Person</th>
                <th>M</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="face in faces" :key="face.id">
                <td>
                  <img
                    :src="facePreviewUrl(props.videofileId, face.frameNumber)"
                    :alt="`Face ${face.id}`"
                    class="face-thumb"
                  />
                </td>
                <td>{{ face.personName }}</td>
                <td>{{ face.origin === 'OPERATOR' ? 'yes' : '' }}</td>
              </tr>
              <tr v-if="!hasFaces">
                <td colspan="3" class="empty">No faces in the frame</td>
              </tr>
            </tbody>
          </table>
          <button type="button" class="btn btn-outline-secondary create-face" @click="createFace">
            Create new face
          </button>
          <div class="box-fields">
            <label>X<input v-model.number="box.x" type="number" class="form-control" /></label>
            <label>Y<input v-model.number="box.y" type="number" class="form-control" /></label>
            <label>Width<input v-model.number="box.w" type="number" class="form-control" /></label>
            <label>Height<input v-model.number="box.h" type="number" class="form-control" /></label>
          </div>
        </div>

        <div class="frame">
          <img
            v-if="frameUrl !== ''"
            :src="frameUrl"
            :alt="`Frame ${props.frameNumber}`"
            class="frame-image"
          />
          <p v-else class="empty">The frame is not shown: no address was returned</p>
          <div
            v-if="boxGiven"
            class="box"
            :style="{
              left: `${box.x}px`,
              top: `${box.y}px`,
              width: `${box.w}px`,
              height: `${box.h}px`,
            }"
          ></div>
        </div>
      </div>

      <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>
      <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

      <!-- Кнопка в старой форме подписана кириллицей: ОК, а не OK. В форме
           shots-edit та же кнопка латинская, и там оставлена латинская. -->
      <div class="dialog-actions">
        <button type="button" class="btn btn-primary" @click="emit('closed')">ОК</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog {
  align-items: center;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  inset: 0;
  justify-content: center;
  position: fixed;
  z-index: 40;
}

.dialog-body {
  background: var(--syp-surface);
  border-radius: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 92vh;
  overflow: auto;
  padding: 0.25rem;
  /* Старая форма объявлена 2200 на 1200: ширина диалога такая же. */
  width: 137.5rem;
}

/* Слева таблица лиц шириной 215 px, справа кадр. */
.faces-area {
  display: flex;
  gap: 0.25rem;
  align-items: stretch;
  min-width: 0;
}

.faces-column {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  /* Колонки объявлены 75 + 135 + 20 = 230 px. Узкая колонка из 215 px
     обрезала третью: заголовок M не помещался. */
  width: 14.375rem;
  flex: 0 0 auto;
}

.faces-table {
  width: 100%;
  table-layout: fixed;
  margin-bottom: 0;
}

.faces-table th:nth-child(1),
.faces-table td:nth-child(1) {
  width: 4.6875rem;
}

.faces-table th:nth-child(2),
.faces-table td:nth-child(2) {
  width: 8.4375rem;
}

.faces-table th:nth-child(3),
.faces-table td:nth-child(3) {
  width: 1.25rem;
}

.create-face {
  width: 100%;
}

.frame {
  background: var(--syp-bg);
  flex: 1 1 auto;
  min-width: 0;
  min-height: 12rem;
  position: relative;
}

.frame-image {
  display: block;
  max-width: 100%;
}

.box {
  border: 2px solid var(--syp-info);
  position: absolute;
}

.box-fields {
  align-items: end;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.box-fields label {
  display: grid;
  font-size: 0.8rem;
  gap: 0.15rem;
}

.box-fields input {
  width: 6rem;
}

.face-thumb {
  border-radius: 2px;
  height: 3rem;
  object-fit: cover;
  width: 3rem;
}

.dialog-actions {
  display: flex;
  gap: 0.5rem;
}

.empty {
  color: var(--syp-text-muted);
}

.error {
  color: var(--syp-danger);
  margin: 0;
}
</style>
