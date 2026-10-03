// Состояние экрана структуры эпизода.
//
// Экран отвечает на три вопроса оператора: «что система нашла», «что из этого
// человек принял» и «где в эпизоде находится этот кадр». Поэтому в состоянии
// лежат три слоя: рабочая структура с её планами, отдельно сырой результат
// автоматики и отдельно открытый лист превью. Смешивать их в одну таблицу
// нельзя — тогда исчезла бы сама возможность сравнить, и расхождение
// накапливалось бы незаметно (FR-093).
//
// Листов превью у эпизода сотни, а сцен — сотни, и ни то, ни другое в одну
// страницу не помещается. Лист открывается по номеру, сцена выбирается, и
// только у выбранной сцены догружаются рамки первых кадров её планов: рамка
// каждого плана — это кусок того же листа, и грузить его для всех сцен страницы
// означало бы скачать один и тот же лист сотни раз.

import { computed, ref } from 'vue'
import {
  type PreviewUrlView,
  type RawBoundariesView,
  type SceneBoundaryView,
  type ShotBoundaryView,
  type StructureView,
  mergeScenes,
  mergeShots,
  moveSceneBoundary,
  moveShotBoundary,
  readPreviewUrl,
  readRawBoundaries,
  readStructure,
  splitScene,
  splitShot,
  startAnalysis,
} from '../api/structure'
import {
  type PreviewSheetFrameRow,
  type SceneBoundaryRow,
  type ShotBoundaryRow,
  type ShotThumbRow,
  toPreviewSheetFrameRow,
  toPreviewSheetNavRow,
  toRawBoundaryRow,
  toSceneBoundaryRow,
  toShotBoundaryRow,
  toShotThumbRow,
  toStructureRow,
} from '../api/view-model'
import { ApiError } from '../api/http'

/** Рабочий структура эпизода. */
const structure = ref<StructureView | null>(null)

/** Сырой результат автоматики последнего прогона. */
const raw = ref<RawBoundariesView | null>(null)

/** Открытый лист превью со своей раскладкой. */
const sheet = ref<PreviewUrlView | null>(null)

/** Рамка кадра на листе: где кадр лежит на картинке. */
export interface FrameCrop {
  /** Номер листа, на котором лежит кадр. */
  sheetIndex: number
  /** Ширина листа в пикселях: по ней рамка масштабируется. */
  sheetWidth: number
  /** Высота листа в пикселях. */
  sheetHeight: number
  /** Область кадрирования кадра на листе. */
  crop: { x: number; y: number; width: number; height: number }
}

/** Рамки первых кадров планов выбранной сцены, по номеру кадра. */
const thumbCrops = ref<Record<number, FrameCrop>>({})

/** Идёт ли обращение к бэкенду. */
const loading = ref(false)

/** Идёт ли правка границы: на время операции поле недоступно. */
const editing = ref(false)

/** Текст последней ошибки на русском. */
const error = ref('')

/** Машинный код последней ошибки. */
const errorCode = ref('')

/** Показывается ли сырой результат автоматики. */
const rawVisible = ref(false)

/** Показываются ли сцены, помеченные устаревшим. */
const staleVisible = ref(false)

/** Страница сцен, с которой начат просмотр. */
const offset = ref(0)

/** Размер страницы сцен. */
const pageSize = 200

/** Идентификатор выбранной сцены; `null`, если сцена не выбрана. */
const selectedSceneId = ref<number | null>(null)

/** Кадр, выбранный на листе превью. */
const selectedFrame = ref<number | null>(null)

/** Ответ на последнюю правку границы — для показа результата операции. */
const lastEdit = ref<SceneBoundaryRow | null>(null)

/**
 * Итог последней правки границы плана.
 *
 * Отдельное поле, а не общее с правкой сцены: у них разный состав ответа, и
 *одно поле на обе правки означало бы, что надпись «граница сцены сдвинута»
 * может появиться под результатом правки плана.
 */
const lastShotEdit = ref<ShotBoundaryRow | null>(null)

/**
 * Помечен ли результат устаревшим.
 *
 * Отдельное вычисление, а не чтение поля: экран показывает пометку в шапке и
 * у каждой границы, и обе надписи должны говорить об одном и том же.
 *
 * @returns `true`, если результат устарел
 */
const isStale = computed(() => structure.value?.isStale === true)

/**
 * Показывается ли результат устаревшим.
 *
 * @returns `true`, если у результата есть машинный код устаревания
 */
const staleCode = computed(() => structure.value?.staleResultCode ?? '')

/**
 * Структура эпизода приведённая к строке экрана.
 *
 * @returns строка экрана со сценами, планами и счётчиками
 */
const row = computed(() => (structure.value === null ? null : toStructureRow(structure.value)))

/**
 * Сцены, которые показывает таблица.
 *
 * Помеченные устаревшим сцены скрыты по умолчанию: их появление после
 * повторного разбора удваивало бы список, а номера в нём считаются по полному
 * списку. Показать их можно кнопкой — нужно для сравнения с сырым результатом
 * автоматики.
 *
 * @returns сцены текущей страницы для показа
 */
const visibleScenes = computed(() => {
  const value = row.value
  if (value === null) {
    return []
  }
  return staleVisible.value ? value.scenes : value.scenes.filter((scene) => !scene.isStale)
})

/**
 * Выбранная сцена.
 *
 * @returns строка выбранной сцены либо `null`
 */
const selectedScene = computed(() => {
  const value = row.value
  if (value === null || selectedSceneId.value === null) {
    return null
  }
  return value.scenes.find((scene) => scene.id === selectedSceneId.value) ?? null
})

/**
 * Открытый лист превью приведённый к строке экрана.
 *
 * Экран и компонент листа получают готовые подписи, адрес и проценты выделения:
 * поля ответа бэкенда читаются здесь, в представлении, и нигде больше
 * (ADR-0015).
 *
 * @param videofileId эпизод-владелец листа
 * @returns строка экрана либо `null`, если лист не открыт
 */
function sheetFrameRow(videofileId: number): PreviewSheetFrameRow | null {
  return sheet.value === null
    ? null
    : toPreviewSheetFrameRow(sheet.value, videofileId, selectedFrame.value)
}

/** Эпизод, которому принадлежат загруженные рамки. */
const lastThumbVideofile = ref(0)

/**
 * Рамки первых кадров планов выбранной сцены для показа.
 *
 * @param videofileId эпизод-владелец листов
 * @returns рамки по номеру кадра: значения приведены к виду экрана
 */
const thumbs = computed<Record<number, ShotThumbRow>>(() => {
  const result: Record<number, ShotThumbRow> = {}
  for (const [frame, found] of Object.entries(thumbCrops.value)) {
    result[Number(frame)] = toShotThumbRow(
      lastThumbVideofile.value,
      found.sheetIndex,
      found.sheetWidth,
      found.sheetHeight,
      found.crop,
    )
  }
  return result
})

/**
 * Сырые границы прогона приведённые к строкам экрана.
 *
 * @returns строки сырых границ текущей страницы
 */
const rawRows = computed(() =>
  (raw.value?.boundaries ?? []).map((boundary) =>
    toRawBoundaryRow(boundary.level, boundary.firstFrame, boundary.lastFrame),
  ),
)

/**
 * Навигация по открытому листу превью.
 *
 * @returns строка навигации либо `null`, если лист не открыт
 */
const sheetNav = computed(() => (sheet.value === null ? null : toPreviewSheetNavRow(sheet.value)))

/**
 * Следующая за выбранной сценой сцена на странице: на неё уходит граница,
 * когда оператор жмёт «вниз» или «вверх».
 *
 * @param step куда сдвинуться по списку: `1` или `-1`
 * @returns соседняя сцена либо `null`, если её нет
 */
function neighbourScene(step: 1 | -1) {
  const scenes = visibleScenes.value
  const current = selectedSceneId.value
  if (current === null) {
    return scenes[step === 1 ? 0 : scenes.length - 1] ?? null
  }
  const position = scenes.findIndex((scene) => scene.id === current)
  if (position < 0) {
    return null
  }
  return scenes[position + step] ?? null
}

/**
 * Состояние экрана структуры и действия над ним.
 *
 * @returns реактивное состояние и функции экрана
 */
export function useStructureStore() {
  /**
   * Перечитывает оба слоя структуры эпизода.
   *
   * @param videofileId идентификатор эпизода
   * @returns `true`, если структура прочитана
   */
  async function reload(videofileId: number): Promise<boolean> {
    loading.value = true
    try {
      structure.value = await readStructure(videofileId, offset.value, pageSize)
      raw.value = await readRawBoundaries(videofileId, undefined, 0, pageSize)
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Переходит на следующую страницу сцен.
   *
   * @param videofileId идентификатор эпизода
   */
  async function nextPage(videofileId: number): Promise<void> {
    const total = structure.value?.scenesTotal ?? 0
    if (offset.value + pageSize >= total) {
      return
    }
    offset.value += pageSize
    await reload(videofileId)
  }

  /**
   * Возвращается на предыдущую страницу сцен.
   *
   * @param videofileId идентификатор эпизода
   */
  async function previousPage(videofileId: number): Promise<void> {
    if (offset.value === 0) {
      return
    }
    offset.value = Math.max(0, offset.value - pageSize)
    await reload(videofileId)
  }

  /**
   * Ставит анализ структуры заново.
   *
   * @param videofileId идентификатор эпизода
   * @returns `true`, если задание поставлено
   */
  async function analyse(videofileId: number): Promise<boolean> {
    loading.value = true
    try {
      await startAnalysis(videofileId)
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Показывает или прячет сырой результат автоматики.
   */
  function toggleRaw(): void {
    rawVisible.value = !rawVisible.value
  }

  /**
   * Показывает или прячет сцены, помеченные устаревшим.
   */
  function toggleStale(): void {
    staleVisible.value = !staleVisible.value
  }

  /**
   * Сбрасывает текст ошибки.
   */
  function clearError(): void {
    error.value = ''
    errorCode.value = ''
  }

  /**
   * Открывает лист превью по номеру.
   *
   * Лист — это адрес и раскладка; изображение грузит сам компонент. Отдельно
   * запрашивается кадр, если он выбран: сервер возвращает его область
   * кадрирования, и клиенту не нужно знать, как устроен лист.
   *
   * @param videofileId идентификатор эпизода
   * @param index номер листа, с нуля
   * @param frame кадр для подсветки либо `null`
   * @returns `true`, если лист открыт
   */
  async function openSheet(
    videofileId: number,
    index: number,
    frame: number | null = null,
  ): Promise<boolean> {
    loading.value = true
    try {
      sheet.value = await readPreviewUrl(videofileId, index, frame ?? undefined)
      if (frame !== null) {
        selectedFrame.value = frame
      }
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Открывает первый лист превью.
   *
   * @param videofileId идентификатор эпизода
   * @returns `true`, если лист открыт
   */
  async function openFirstSheet(videofileId: number): Promise<boolean> {
    return openSheet(videofileId, 0)
  }

  /**
   * Листает превью вперёд или назад.
   *
   * @param videofileId идентификатор эпизода
   * @param step куда листать: `1` — вперёд, `-1` — назад
   * @returns `true`, если лист сменился
   */
  async function stepSheet(videofileId: number, step: 1 | -1): Promise<boolean> {
    const current = sheet.value
    if (current === null) {
      return openFirstSheet(videofileId)
    }
    const next = current.index + step
    if (next < 0 || next >= current.sheetCount) {
      return false
    }
    return openSheet(videofileId, next, selectedFrame.value)
  }

  /**
   * Открывает лист превью, в котором лежит кадр.
   *
   * @param videofileId идентификатор эпизода
   * @param frame номер кадра
   * @returns `true`, если лист открыт
   */
  async function openSheetForFrame(videofileId: number, frame: number): Promise<boolean> {
    const current = sheet.value
    if (current === null) {
      return openFirstSheet(videofileId)
    }
    if (frame >= current.firstFrame && frame <= current.lastFrame) {
      return openSheet(videofileId, current.index, frame)
    }
    // Раскладка листа принадлежит серверу, поэтому номер листа запрашивается у
    // него же одним вызовом с кадром: клиент не угадывает, на каком листе
    // лежит кадр, и не делает два обращения там, где хватает одного.
    loading.value = true
    try {
      sheet.value = await readPreviewUrl(videofileId, undefined, frame)
      selectedFrame.value = frame
      error.value = ''
      errorCode.value = ''
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      loading.value = false
    }
  }

  /**
   * Выбирает сцену и догружает рамки первых кадров её планов.
   *
   * @param videofileId идентификатор эпизода
   * @param sceneId идентификатор сцены либо `null`, чтобы снять выбор
   * @returns `true`, если сцена выбрана
   */
  async function selectScene(videofileId: number, sceneId: number | null): Promise<boolean> {
    selectedSceneId.value = sceneId
    if (sceneId === null) {
      return true
    }
    return loadThumbs(videofileId, sceneId)
  }

  /**
   * Догружает рамки первых кадров планов сцены.
   *
   * @param videofileId идентификатор эпизода
   * @param sceneId идентификатор сцены
   * @returns `true`, если рамки загружены
   */
  async function loadThumbs(videofileId: number, sceneId: number): Promise<boolean> {
    const scene = selectedScene.value
    if (scene === null || scene.id !== sceneId) {
      return false
    }
    thumbCrops.value = {}
    lastThumbVideofile.value = videofileId
    try {
      const loaded = await Promise.all(
        scene.shots.map((shot) => readPreviewUrl(videofileId, undefined, shot.firstFrame)),
      )
      const next: Record<number, FrameCrop> = {}
      loaded.forEach((found, position) => {
        if (found.crop !== null) {
          next[scene.shots[position].firstFrame] = {
            sheetIndex: found.index,
            sheetWidth: found.sheetWidth,
            sheetHeight: found.sheetHeight,
            crop: {
              x: found.crop.x,
              y: found.crop.y,
              width: found.crop.width,
              height: found.crop.height,
            },
          }
        }
      })
      thumbCrops.value = next
      return true
    } catch {
      // Рамка — украшение: её отсутствие не должно стирать саму сцену и не
      // должно занимать оператора отказом вместо того, что он делал.
      thumbCrops.value = {}
      return false
    }
  }

  /**
   * Переходит к кадру: открывает его лист и выбирает содержащую сцену.
   *
   * @param videofileId идентификатор эпизода
   * @param frame номер кадра
   * @returns `true`, если кадр показан
   */
  async function goToFrame(videofileId: number, frame: number): Promise<boolean> {
    const total = structure.value?.frameCount ?? 0
    if (!Number.isInteger(frame) || frame < 0 || frame >= total) {
      // Проверка на клиенте, а не отказ сервера: машинного кода у неё нет, и
      // выдавать за машинный код слово «неверный кадр» значило бы научить
      // интерфейс доверять тому, что придумал он сам.
      errorCode.value = ''
      error.value = `Кадра ${frame} у эпизода нет: в нём ${total} кадров, нумерация с нуля`
      return false
    }
    selectedFrame.value = frame
    const opened = await openSheetForFrame(videofileId, frame)
    const scene = sceneAtFrame(frame)
    if (scene !== null) {
      await selectScene(videofileId, scene.id)
    }
    return opened
  }

  /**
   * Сцена, содержащая кадр, по загруженной странице.
   *
   * Ищется по планам, а не по границам сцены: показывать сцену, в которую
   * кадр не попал, нельзя, а границы сцены шире её планов на стыке структуры,
   * достроенной вручную.
   *
   * @param frame номер кадра
   * @returns строка сцены либо `null`, если на странице её нет
   */
  function sceneAtFrame(frame: number) {
    return visibleScenes.value.find((scene) => shotsContain(scene, frame)) ?? null
  }

  /**
   * Проверяет, содержит ли планы сцены кадр.
   *
   * @param scene сцена экрана
   * @param frame проверяемый кадр
   * @returns `true`, если кадр принадлежит плану сцены
   */
  function shotsContain(
    scene: { shots: { firstFrame: number; lastFrame: number }[] },
    frame: number,
  ): boolean {
    return scene.shots.some((shot) => frame >= shot.firstFrame && frame <= shot.lastFrame)
  }

  /**
   * Сдвигает границу сцены.
   *
   * @param videofileId идентификатор эпизода
   * @param fromFrame кадр, на котором граница стоит
   * @param toFrame кадр, на который её ставят
   * @returns `true`, если правка выполнена
   */
  async function moveBoundary(
    videofileId: number,
    fromFrame: number,
    toFrame: number,
  ): Promise<boolean> {
    return applyEdit(videofileId, () => moveSceneBoundary(videofileId, fromFrame, toFrame))
  }

  /**
   * Разделяет сцену по кадру.
   *
   * @param videofileId идентификатор эпизода
   * @param frame первый кадр второй из получившихся сцен
   * @returns `true`, если правка выполнена
   */
  async function split(videofileId: number, frame: number): Promise<boolean> {
    return applyEdit(videofileId, () => splitScene(videofileId, frame))
  }

  /**
   * Объединяет сцену, начинающуюся с кадра, с предыдущей.
   *
   * @param videofileId идентификатор эпизода
   * @param frame первый кадр поглощаемой сцены
   * @returns `true`, если правка выполнена
   */
  async function merge(videofileId: number, frame: number): Promise<boolean> {
    return applyEdit(videofileId, () => mergeScenes(videofileId, frame))
  }

  /**
   * Сдвигает границу плана.
   *
   * @param videofileId идентификатор эпизода
   * @param fromFrame кадр, на котором граница стоит
   * @param toFrame кадр, на который её ставят
   * @returns `true`, если правка выполнена
   */
  async function moveShotEdge(
    videofileId: number,
    fromFrame: number,
    toFrame: number,
  ): Promise<boolean> {
    return applyShotEdit(videofileId, () => moveShotBoundary(videofileId, fromFrame, toFrame))
  }

  /**
   * Разделяет план по кадру.
   *
   * @param videofileId идентификатор эпизода
   * @param frame первый кадр второго из получившихся планов
   * @returns `true`, если правка выполнена
   */
  async function splitShotAt(videofileId: number, frame: number): Promise<boolean> {
    return applyShotEdit(videofileId, () => splitShot(videofileId, frame))
  }

  /**
   * Объединяет план, начинающийся с кадра, с предыдущим.
   *
   * @param videofileId идентификатор эпизода
   * @param frame первый кадр поглощаемого плана
   * @returns `true`, если правка выполнена
   */
  async function mergeShotAt(videofileId: number, frame: number): Promise<boolean> {
    return applyShotEdit(videofileId, () => mergeShots(videofileId, frame))
  }

  /**
   * Выполняет правку границы плана и обновляет экран.
   *
   * Страница перечитывается целиком по той же причине, что и после правки
   * сцены: правка отвечает изменённым участком, а состав планов на странице
   * после неё меняется.
   *
   * @param videofileId идентификатор эпизода
   * @param operation операция правки
   * @returns `true`, если правка выполнена
   */
  async function applyShotEdit(
    videofileId: number,
    operation: () => Promise<ShotBoundaryView>,
  ): Promise<boolean> {
    editing.value = true
    try {
      lastShotEdit.value = toShotBoundaryRow(await operation())
      error.value = ''
      errorCode.value = ''
      await reload(videofileId)
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      editing.value = false
    }
  }

  /**
   * Выполняет правку границы и обновляет экран.
   *
   * Правка отвечает изменённым участком, но список сцен на странице после неё
   * меняется по составу, поэтому страница перечитывается целиком: иначе в
   * таблице остались бы строки, выведенные из работы, а новых не было бы видно.
   *
   * @param videofileId идентификатор эпизода
   * @param operation операция правки
   * @returns `true`, если правка выполнена
   */
  async function applyEdit(
    videofileId: number,
    operation: () => Promise<SceneBoundaryView>,
  ): Promise<boolean> {
    editing.value = true
    try {
      lastEdit.value = toSceneBoundaryRow(await operation())
      error.value = ''
      errorCode.value = ''
      await reload(videofileId)
      return true
    } catch (failure) {
      remember(failure)
      return false
    } finally {
      editing.value = false
    }
  }

  /**
   * Запоминает отказ: текст для человека, код для интерфейса.
   *
   * @param failure отказ клиента
   */
  function remember(failure: unknown): void {
    if (failure instanceof ApiError) {
      errorCode.value = failure.code
      error.value = failure.message
      return
    }
    errorCode.value = ''
    error.value = failure instanceof Error ? failure.message : String(failure)
  }

  return {
    structure,
    raw,
    rawRows,
    sheet,
    sheetFrameRow,
    thumbs,
    loading,
    editing,
    error,
    errorCode,
    rawVisible,
    staleVisible,
    selectedFrame,
    lastEdit,
    lastShotEdit,
    isStale,
    staleCode,
    row,
    visibleScenes,
    selectedScene,
    sheetNav,
    neighbourScene,
    reload,
    nextPage,
    previousPage,
    analyse,
    toggleRaw,
    toggleStale,
    clearError,
    openSheet,
    openFirstSheet,
    stepSheet,
    openSheetForFrame,
    selectScene,
    goToFrame,
    moveBoundary,
    split,
    merge,
    moveShotEdge,
    splitShotAt,
    mergeShotAt,
  }
}
