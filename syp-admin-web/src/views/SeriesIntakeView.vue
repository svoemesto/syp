// Экран приёма сериалов и серий (задача T037). // // Экран закрывает три требования: оператор
создаёт сериал с корнем // каталога, добавляет серию указанием пути и видит параметры, которые //
определила система сама. Отдельно показывается внятная ошибка при // недоступном файле — «успех с
пустым результатом» на экране выглядел бы // как «серия заведена», а на деле файл не был прочитан
(FR-092).

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import { formatBytes, formatDuration } from '../format/values'

const store = useCatalogStore()
const router = useRouter()

const serialName = ref('')
const serialRoot = ref('')
const seriesPath = ref('')
const seriesName = ref('')

onMounted(() => {
  void store.reloadSerials()
})

/**
 * Создаёт сериал по введённым названию и корню каталога.
 *
 * @returns `true`, если сериал создан
 */
async function submitSerial(): Promise<boolean> {
  return store.addSerial(serialName.value.trim(), serialRoot.value.trim())
}

/**
 * Регистрирует серию по введённому пути к файлу.
 *
 * @returns `true`, если серия зарегистрирована
 */
async function submitSeries(): Promise<boolean> {
  const created = await store.addSeries(seriesPath.value.trim(), seriesName.value.trim())
  if (created) {
    seriesPath.value = ''
    seriesName.value = ''
  }
  return created
}

/**
 * Открывает экран состояния суммы для серии.
 *
 * @param seriesId идентификатор серии
 */
function openChecksum(seriesId: number): void {
  void router.push({ name: 'checksum', params: { seriesId: String(seriesId) } })
}
</script>

<template>
  <section class="intake">
    <h2>Приём сериалов и серий</h2>

    <p v-if="store.loading.value" class="note">Запрос к бэкенду…</p>

    <p v-if="store.error.value" class="error" role="alert">
      <span v-if="store.errorCode.value" class="code">{{ store.errorCode.value }}</span>
      {{ store.error.value }}
      <button type="button" @click="store.clearError()">скрыть</button>
    </p>

    <fieldset>
      <legend>Сериалы</legend>
      <table>
        <thead>
          <tr>
            <th>Название</th>
            <th>Корень каталога</th>
            <th>Серий</th>
            <th />
          </tr>
        </thead>
        <tbody>
          <tr v-for="serial in store.serials.value" :key="serial.id">
            <td>{{ serial.name }}</td>
            <td class="path">
              {{ serial.sourceRoot }}
            </td>
            <td>{{ serial.seriesCount }}</td>
            <td>
              <button type="button" @click="store.openSerial(serial.id)">открыть</button>
            </td>
          </tr>
          <tr v-if="store.serials.value.length === 0">
            <td colspan="4" class="note">Сериалов пока нет</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input v-model="serialName" type="text" placeholder="Название сериала" />
        <input
          v-model="serialRoot"
          type="text"
          placeholder="Корень каталога, например /disks/HDD_16Tb_Clouds/GOT"
        />
        <button type="button" :disabled="store.loading.value" @click="submitSerial">
          создать сериал
        </button>
      </div>
      <p class="note">
        Корень обязателен: сценарий сборки обращается к файлам по путям относительно него, и
        относительный путь вне корня был бы выдуманным.
      </p>
    </fieldset>

    <fieldset v-if="store.current.value">
      <legend>Серии сериала «{{ store.current.value.serial.name }}»</legend>
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
          <tr v-for="series in store.series.value" :key="series.id">
            <td>{{ series.name }}</td>
            <td class="path">
              {{ series.relativePath ?? series.sourcePath }}
            </td>
            <td>{{ series.frameCount.toLocaleString('ru-RU') }}</td>
            <td>{{ series.width }}×{{ series.height }}</td>
            <td>{{ series.frameRate }}</td>
            <td>{{ formatDuration(series.durationSeconds) }}</td>
            <td>{{ formatBytes(series.byteSize, 'байт', 0) }}</td>
            <td>{{ series.keyframeCount }}</td>
            <td>
              <button type="button" @click="openChecksum(series.id)">сумма</button>
            </td>
          </tr>
          <tr v-if="store.series.value.length === 0">
            <td colspan="9" class="note">Серий пока нет</td>
          </tr>
        </tbody>
      </table>

      <div class="form">
        <input
          v-model="seriesPath"
          type="text"
          placeholder="Путь к файлу серии внутри корня сериала"
        />
        <input v-model="seriesName" type="text" placeholder="Название серии (необязательно)" />
        <button
          type="button"
          :disabled="!store.canRegisterSeries.value || store.loading.value"
          @click="submitSeries"
        >
          добавить серию
        </button>
      </div>
      <p class="note">
        Параметры файла определяет система: оператор их не вводит. Путь обязан лежать внутри корня
        сериала, иначе придёт отказ <code>SOURCE_UNREADABLE</code> с путём в тексте.
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
