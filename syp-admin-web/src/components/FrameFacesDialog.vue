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
import { readFaces, type FaceView } from '../api/characters'
import { readPreviewUrl } from '../api/structure'

const props = defineProps<{ videofileId: number; frameNumber: number }>()

const emit = defineEmits<{
  /** Лица кадра изменились. */
  changed: []
  /** Окно закрыто. */
  closed: []
}>()

/** Лица кадра. */
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
    frameUrl.value = readPreviewUrl(props.videofileId, props.frameNumber)
    error.value = ''
  } catch (failure) {
    error.value = (failure as Error).message
  }
}

/** Создаёт лицо по заданной рамке. */
function createFace(): void {
  if (!boxGiven.value) {
    notice.value = 'Задайте рамку лица: без неё лицо создать нечем'
    return
  }
  notice.value = 'Создание лица вручную ещё не ходит в бэкенд: эндпоинта нет'
}

onMounted(reload)
</script>

<template>
  <div class="dialog" role="dialog" aria-label="Лица кадра">
    <div class="dialog-body">
      <h2 class="syp-card-title">Лица кадра {{ props.frameNumber }}</h2>

      <div class="frame">
        <img v-if="frameUrl !== ''" :src="frameUrl" :alt="`Кадр ${props.frameNumber}`" class="frame-image" />
        <p v-else class="empty">Кадр не показывается: адрес не получен</p>
        <div v-if="boxGiven" class="box" :style="{ left: `${box.x}px`, top: `${box.y}px`, width: `${box.w}px`, height: `${box.h}px` }"></div>
      </div>

      <div class="box-fields">
        <label>X<input v-model.number="box.x" type="number" class="form-control" /></label>
        <label>Y<input v-model.number="box.y" type="number" class="form-control" /></label>
        <label>Ширина<input v-model.number="box.w" type="number" class="form-control" /></label>
        <label>Высота<input v-model.number="box.h" type="number" class="form-control" /></label>
        <button type="button" class="btn btn-outline-secondary" @click="createFace">Создать лицо</button>
      </div>

      <table class="table table-sm">
        <thead>
          <tr>
            <th>Лицо</th>
            <th>Персона</th>
            <th>M</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="face in faces" :key="face.id">
            <td>
              <img :src="`/api/videofiles/${props.videofileId}/faces/${face.frameNumber}/preview`" :alt="`Лицо ${face.id}`" class="face-thumb" />
            </td>
            <td>{{ face.personName }}</td>
            <td>{{ face.origin === 'OPERATOR' ? 'да' : '' }}</td>
          </tr>
          <tr v-if="!hasFaces">
            <td colspan="3" class="empty">В кадре лиц нет</td>
          </tr>
        </tbody>
      </table>

      <p v-if="error !== ''" class="error" role="alert">{{ error }}</p>
      <p v-if="notice !== ''" class="notice" role="status">{{ notice }}</p>

      <div class="dialog-actions">
        <button type="button" class="btn btn-primary" @click="emit('closed')">Подтвердить</button>
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
  background: #fff;
  border-radius: 6px;
  display: grid;
  gap: 0.5rem;
  max-height: 90vh;
  overflow: auto;
  padding: 1rem;
  width: 60rem;
}

.frame {
  background: #000;
  min-height: 12rem;
  position: relative;
}

.frame-image {
  display: block;
  max-width: 100%;
}

.box {
  border: 2px solid #4dabf7;
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
  color: #777;
}

.error {
  color: #a61b1b;
  margin: 0;
}
</style>
