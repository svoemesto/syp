// Экран приёма фильмов и эпизодов (задача T037). // // Экран закрывает три требования: оператор
создаёт фильм с корнем // каталога, добавляет эпизод указанием пути и видит параметры, которые //
определила система сама. Отдельно показывается внятная ошибка при // недоступном файле — «успех с
пустым результатом» на экране выглядел бы // как «эпизод заведена», а на деле файл не был прочитан
(FR-092).

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import { formatBytes, formatDuration } from '../format/values'

const store = useCatalogStore()
const router = useRouter()

const movieName = ref('')
const movieRoot = ref('')
const episodePath = ref('')
const episodeName = ref('')

onMounted(() => {
  void store.reloadMovies()
})

/**
 * Создаёт фильм по введённым названию и корню каталога.
 *
 * @returns `true`, если фильм создан
 */
async function submitMovie(): Promise<boolean> {
  return store.addMovie(movieName.value.trim(), movieRoot.value.trim())
}

/**
 * Регистрирует эпизод по введённому пути к файлу.
 *
 * @returns `true`, если эпизод зарегистрирована
 */
async function submitEpisode(): Promise<boolean> {
  const created = await store.addEpisode(episodePath.value.trim(), episodeName.value.trim())
  if (created) {
    episodePath.value = ''
    episodeName.value = ''
  }
  return created
}

/**
 * Открывает экран состояния суммы для эпизода.
 *
 * @param episodeId идентификатор эпизода
 */
function openChecksum(episodeId: number): void {
  void router.push({ name: 'checksum', params: { episodeId: String(episodeId) } })
}
</script>

<template>
  <section class="intake">
    <h2>Приём фильмов и эпизодов</h2>

    <p v-if="store.loading.value" class="note">Запрос к бэкенду…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">скрыть</button>
    </p>

    <fieldset>
      <legend>Фильмы</legend>
      <table>
        <thead>
          <tr>
            <th>Название</th>
            <th>Корень каталога</th>
            <th>Эпизодов</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="movie in store.movies.value" :key="movie.id">
            <td>{{ movie.name }}</td>
            <td class="path">
              {{ movie.sourceRoot }}
            </td>
            <td>{{ movie.episodeCount }}</td>
            <td>
              <button type="button" @click="store.openMovie(movie.id)">открыть</button>
            </td>
          </tr>
          <tr v-if="store.movies.value.length === 0">
            <td colspan="4" class="note">Фильмов пока нет</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input v-model="movieName" type="text" placeholder="Название фильма" />
        <input
          v-model="movieRoot"
          type="text"
          placeholder="Корень каталога, например /disks/HDD_16Tb_Clouds/GOT"
        />
        <button type="button" :disabled="store.loading.value" @click="submitMovie">
          создать фильм
        </button>
      </div>
      <p class="note">
        Корень обязателен: сценарий сборки обращается к файлам по путям относительно него, и
        относительный путь вне корня был бы выдуманным.
      </p>
    </fieldset>

    <fieldset v-if="store.current.value">
      <legend>Эпизода фильма «{{ store.current.value.movie.name }}»</legend>
      <table>
        <thead>
          <tr>
            <th>Название</th>
            <th>Путь</th>
            <th>Кадров</th>
            <th>Разрешение</th>
            <th>Частота кадров</th>
            <th>Длительность</th>
            <th>Размер</th>
            <th>Ключевых кадров</th>
            <th>Сумма</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="episode in store.episode.value" :key="episode.id">
            <td>{{ episode.name }}</td>
            <td class="path">
              {{ episode.relativePath ?? episode.sourcePath }}
            </td>
            <td>{{ episode.frameCount.toLocaleString('ru-RU') }}</td>
            <td>{{ episode.width }}×{{ episode.height }}</td>
            <td>{{ episode.frameRate }}</td>
            <td>{{ formatDuration(episode.durationSeconds) }}</td>
            <td>{{ formatBytes(episode.byteSize, 'байт', 0) }}</td>
            <td>{{ episode.keyframeCount }}</td>
            <td>
              <button type="button" @click="openChecksum(episode.id)">сумма</button>
            </td>
          </tr>
          <tr v-if="store.episode.value.length === 0">
            <td colspan="9" class="note">Эпизодов пока нет</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input
          v-model="episodePath"
          type="text"
          placeholder="Путь к файлу эпизода внутри корня фильма"
        />
        <input v-model="episodeName" type="text" placeholder="Название эпизода (необязательно)" />
        <button
          type="button"
          :disabled="!store.canRegisterEpisode.value || store.loading.value"
          @click="submitEpisode"
        >
          добавить эпизод
        </button>
      </div>
      <p class="note">
        Параметры файла определяет система: оператор их не вводит. Путь обязан лежать внутри корня
        фильма, иначе придёт отказ <code>SOURCE_UNREADABLE</code> с путём в тексте.
      </p>
    </fieldset>
  </section>
</template>

<style scoped>
.intake {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

fieldset {
  border: 1px solid #d0d0d0;
  padding: 0.75rem 1rem 1rem;
}

legend {
  font-weight: 600;
}

table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 0.75rem;
}

th,
td {
  border-bottom: 1px solid #e6e6e6;
  padding: 0.35rem 0.5rem;
  text-align: left;
  font-size: 0.9rem;
}

.path {
  font-family: monospace;
  font-size: 0.8rem;
  word-break: break-all;
}

.form {
  display: flex;
  gap: 0.5rem;
  flex-wrap: wrap;
}

input {
  flex: 1 1 18rem;
  padding: 0.35rem 0.5rem;
}

.error {
  border-left: 3px solid #b3261e;
  padding-left: 0.5rem;
}

.code {
  font-family: monospace;
  margin-right: 0.5rem;
}

.note {
  color: #555555;
  font-size: 0.85rem;
}
</style>
