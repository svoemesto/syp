// Экран приёма сериалов и серий (задача T037). // // Экран закрывает три требования: оператор
создаёт сериал с корнем каталога, // добавляет серию указанием пути и видит параметры, которые
определила система // сама. Отдельно показывается внятная ошибка при недоступном файле — «успех с //
пустым результатом» на экране выглядел бы как «серия заведена», а на деле // файл не был прочитан
(FR-092). // // Оформление — Bootstrap с общей темой проекта (ADR-0015); состояния загрузки, //
пустоты и отказа показаны компонентом `StateBlock`, чтобы все экраны выглядели // одинаково при
одном и том же событии.

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useCatalogStore } from '../stores/catalog'
import StateBlock from '../components/StateBlock.vue'

const store = useCatalogStore()
const router = useRouter()

const serialName = ref('')
const serialRoot = ref('')
const seriesPath = ref('')
const seriesName = ref('')

onMounted(() => {
  void store.reloadSerials()
})

/** Сериалов в списке. */
const serialsCount = computed(() => store.serials.value.length)

/** Создаёт сериал по введённым названию и корню каталога. */
async function submitSerial(): Promise<void> {
  if (await store.addSerial(serialName.value.trim(), serialRoot.value.trim())) {
    serialName.value = ''
    serialRoot.value = ''
  }
}

/** Регистрирует серию по введённому пути к файлу. */
async function submitSeries(): Promise<void> {
  if (await store.addSeries(seriesPath.value.trim(), seriesName.value.trim())) {
    seriesPath.value = ''
    seriesName.value = ''
  }
}

/**
 * Выбирает серию и открывает её раздел.
 *
 * @param seriesId идентификатор серии
 * @param section раздел: `checksum` или `structure`
 */
async function openSection(seriesId: number, section: 'checksum' | 'structure'): Promise<void> {
  store.selectSeries(seriesId)
  await router.push({ name: section, params: { seriesId: String(seriesId) } })
}
</script>

<template>
  <section>
    <div class="syp-page-head">
      <div>
        <h2 class="syp-page-title">Приём сериалов и серий</h2>
        <p class="syp-page-lead">
          Сериал — произведение, серия — его видеофайл. Название и корень каталога вводит оператор,
          параметры файла определяет система.
        </p>
      </div>
      <span class="syp-unit">сериалов: {{ serialsCount }}</span>
    </div>

    <StateBlock
      :loading="store.loading.value && serialsCount === 0"
      :error="store.error.value"
      :error-code="store.errorCode.value"
      @dismiss="store.clearError()"
    />

    <div class="row g-4">
      <div class="col-xxl-7">
        <div class="card h-100">
          <div class="card-header d-flex justify-content-between align-items-center">
            <span>Сериалы</span>
            <button
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="store.loading.value"
              @click="store.reloadSerials()"
            >
              обновить
            </button>
          </div>

          <div v-if="serialsCount > 0" class="table-responsive">
            <table class="table table-hover align-middle">
              <thead>
                <tr>
                  <th scope="col">Название</th>
                  <th scope="col">Корень каталога</th>
                  <th scope="col" class="syp-number">Серий</th>
                  <th scope="col" class="text-end">Действие</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="serial in store.serials.value" :key="serial.id">
                  <th scope="row" class="fw-normal">{{ serial.name }}</th>
                  <td class="syp-path">{{ serial.sourceRoot }}</td>
                  <td class="syp-number">{{ serial.seriesCount }}</td>
                  <td class="text-end">
                    <button
                      type="button"
                      class="btn btn-sm btn-outline-secondary"
                      :disabled="store.loading.value"
                      @click="store.openSerial(serial.id)"
                    >
                      открыть
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <StateBlock
            v-else-if="!store.loading.value"
            :error="''"
            empty-title="Сериалов пока нет"
            empty-text="Создайте первый сериал: укажите название и корень каталога с исходниками."
          />

          <div class="card-body border-top">
            <form class="row g-2 align-items-end" @submit.prevent="submitSerial">
              <div class="col-md-4">
                <label class="form-label" for="serial-name">Название сериала</label>
                <input
                  id="serial-name"
                  v-model="serialName"
                  type="text"
                  class="form-control"
                  placeholder="Например: Игра престолов"
                  required
                />
              </div>
              <div class="col-md-5">
                <label class="form-label" for="serial-root">Корень каталога</label>
                <input
                  id="serial-root"
                  v-model="serialRoot"
                  type="text"
                  class="form-control syp-path"
                  placeholder="/disks/HDD_16Tb_Clouds/GOT"
                  required
                />
              </div>
              <div class="col-md-3">
                <button type="submit" class="btn btn-primary w-100" :disabled="store.loading.value">
                  создать сериал
                </button>
              </div>
            </form>
            <p class="form-text mt-2 mb-0">
              Корень обязателен: сценарий сборки обращается к файлам по путям относительно него, и
              относительный путь вне корня был бы выдуманным.
            </p>
          </div>
        </div>
      </div>

      <div class="col-xxl-5">
        <div class="card h-100">
          <div class="card-header">Серии сериала</div>

          <template v-if="store.hasCurrent.value">
            <p class="card-body pb-2 mb-0">
              Сериал <strong>{{ store.currentName.value }}</strong
              ><span v-if="!store.hasSeries.value"> — серий в нём нет.</span>
            </p>

            <div v-if="store.hasSeries.value" class="table-responsive">
              <table class="table table-hover align-middle">
                <thead>
                  <tr>
                    <th scope="col">Серия</th>
                    <th scope="col">Путь</th>
                    <th scope="col" class="syp-number">Кадров</th>
                    <th scope="col">Длина</th>
                    <th scope="col" class="text-end">Разделы</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="series in store.series.value" :key="series.id">
                    <th scope="row" class="fw-normal">
                      {{ series.name }}
                      <span v-if="!series.ready" class="badge text-bg-warning ms-1">не готова</span>
                    </th>
                    <td class="syp-path">{{ series.displayPath }}</td>
                    <td class="syp-number">{{ series.frameCount }}</td>
                    <td class="syp-number">{{ series.duration }}</td>
                    <td class="text-end text-nowrap">
                      <button
                        type="button"
                        class="btn btn-sm btn-outline-secondary me-1"
                        :disabled="store.loading.value"
                        @click="openSection(series.id, 'structure')"
                      >
                        структура
                      </button>
                      <button
                        type="button"
                        class="btn btn-sm btn-outline-secondary"
                        :disabled="store.loading.value"
                        @click="openSection(series.id, 'checksum')"
                      >
                        сумма
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <StateBlock
              v-else
              :error="''"
              empty-title="Серий пока нет"
              empty-text="Добавьте серию, указав путь к файлу внутри корня сериала."
            />

            <div class="card-body border-top">
              <form class="row g-2 align-items-end" @submit.prevent="submitSeries">
                <div class="col-md-6">
                  <label class="form-label" for="series-path">Путь к файлу серии</label>
                  <input
                    id="series-path"
                    v-model="seriesPath"
                    type="text"
                    class="form-control syp-path"
                    placeholder="Путь внутри корня сериала"
                    required
                  />
                </div>
                <div class="col-md-3">
                  <label class="form-label" for="series-name">Название серии</label>
                  <input
                    id="series-name"
                    v-model="seriesName"
                    type="text"
                    class="form-control"
                    placeholder="необязательно"
                  />
                </div>
                <div class="col-md-3">
                  <button
                    type="submit"
                    class="btn btn-primary w-100"
                    :disabled="!store.canRegisterSeries.value || store.loading.value"
                  >
                    добавить серию
                  </button>
                </div>
              </form>
              <p class="form-text mt-2 mb-0">
                Параметры файла определяет система: оператор их не вводит. Путь обязан лежать внутри
                корня сериала, иначе придёт отказ <span class="syp-mono">SOURCE_UNREADABLE</span>
                с путём в тексте.
              </p>
            </div>
          </template>

          <StateBlock
            v-else
            :error="''"
            empty-title="Сериал не выбран"
            empty-text="Откройте сериал в списке слева, чтобы увидеть его серии."
          />
        </div>
      </div>
    </div>
  </section>
</template>
