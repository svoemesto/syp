package ru.svoemesto.syp.admin.integrity

import ru.svoemesto.syp.admin.catalog.Episode
import ru.svoemesto.syp.admin.catalog.EpisodeStore
import ru.svoemesto.syp.admin.jobs.JobHandler
import ru.svoemesto.syp.admin.jobs.JobResult
import ru.svoemesto.syp.core.jobs.Job
import ru.svoemesto.syp.core.jobs.JobKind
import ru.svoemesto.syp.core.jobs.JobProgress
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Задание `HASH`: подсчёт суммы `sha256` исходного файла серии.
 *
 * **Почему сумма считается здесь, а не внешней программой.** У задания есть
 * два требования, и внешняя программа выполняет только одно из них. Требуется
 * **наблюдаемый прогресс по прочитанным байтам** (FR-003, М-11): интерфейс
 * должен показывать движение на файле в 5,6 ГБ, а `sha256sum` печатает
 * результат одной строкой в самом конце. Прогресс собственного чтения
 * получается точно и монотонно — по числу прочитанных байт.
 *
 * Правило constitution IV.2 (код возврата внешней программы проверяется
 * всегда) здесь не нарушается: **внешней программы у этого задания нет**.
 * Отказом для него служит ошибка чтения файла — `IOException` с текстом на
 * русском, которая переводит задание в `ERROR` (FR-092). Молчаливый «успех»
 * при этом невозможен: сумма без полного прочитанного файла не бывает.
 *
 * **Прерванный подсчёт не оставляет запись `DONE`.** Запись справочника
 * создаётся в начале работы и переходит в `DONE` только после того, как файл
 * дочитан целиком и получена сумма. Прерывание оставляет её в состоянии
 * `WORKING`, из которого выхода нет: такая запись не является результатом и не
 * может быть выдана в сценарий (FR-089).
 *
 * **Размер и время изменения файла записываются вместе с суммой** — при
 * которых она считалась. Если файл подменён, прежняя сумма станет устаревшей
 * при следующей сверке (FR-090).
 *
 * @property episodeStore хранилище серий: из него берётся путь к файлу
 * @property registry справочник сумм
 * @property blockSize размер блока чтения, байт
 * @property progressStep как часто сообщается прогресс, байт
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class HashJob(
    private val episodeStore: EpisodeStore,
    private val registry: ChecksumRegistry,
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val progressStep: Long = DEFAULT_PROGRESS_STEP,
) : JobHandler {
    /** Вид задания, который обрабатывает исполнитель. */
    override val kind: JobKind = JobKind.HASH

    /**
     * Считает сумму файла серии.
     *
     * @param job задание с предметом «серия»
     * @param progress приёмник прогресса по прочитанным байтам
     * @return результат выполнения без артефакта: сумма пишется в справочник,
     *   а не в объектное хранилище
     * @throws ru.svoemesto.syp.core.contract.DomainException если у задания нет
     *   предмета «серия» либо серия не зарегистрирована
     * @throws ru.svoemesto.syp.core.media.ExternalProgramFailed если файл
     *   прочитать не удалось: задание уходит в `ERROR` с текстом (FR-092)
     */
    override fun execute(
        job: Job,
        progress: (JobProgress) -> Unit,
    ): JobResult {
        val episode = requireEpisode(job)
        val entry = registry.begin(episode, job.id)
        val entryId =
            entry.id
                ?: throw ru.svoemesto.syp.core.db.DbException(
                    "Запись справочника сумм создана без идентификатора: подсчёту некуда писать результат",
                )
        registry.markWorking(entryId)

        val file = Path.of(episode.sourcePath)
        try {
            val digest = computeDigest(file, episode.byteSize, progress)
            val attributes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
            val completed =
                registry.complete(
                    entryId,
                    digest,
                    attributes.size(),
                    attributes.lastModifiedTime().toInstant().atOffset(java.time.ZoneOffset.UTC),
                )
            return JobResult(
                note =
                    "сумма ${completed.algorithm} посчитана для «${episode.name}»: " +
                        "${completed.byteSize} байт, посчитано ${completed.computedAt}",
                progressTotal = completed.byteSize,
            )
        } catch (interrupted: InterruptedException) {
            // Прерывание не переводит запись в ошибку: задание вернётся в
            // очередь и будет досчитано. Запись остаётся незавершённой, и это
            // правильно — частично посчитанная сумма суммой не является.
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: IOException) {
            val text =
                "не удалось прочитать файл серии «${episode.sourcePath}» для подсчёта суммы: " +
                    "${failure.message ?: failure::class.simpleName}. " +
                    "Проверьте, что архив смонтирован и файл доступен на чтение"
            registry.fail(entryId, text)
            throw HashFailed(text)
        }
    }

    /**
     * Читает файл и считает по нему сумму, сообщая прогресс.
     *
     * @param file путь к исходному файлу серии
     * @param expectedSize размер файла по данным серии: он же объём работы
     * @param progress приёмник прогресса
     * @return сумма 64 шестнадцатеричных символами в нижнем регистре
     * @throws IOException если файл не читается
     */
    private fun computeDigest(
        file: Path,
        expectedSize: Long,
        progress: (JobProgress) -> Unit,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        var reported = 0L
        Files.newInputStream(file, *DEFAULT_READ_OPTIONS).use { input ->
            val buffer = ByteArray(blockSize)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
                if (read - reported >= progressStep) {
                    reported = read
                    progress(JobProgress.of(read, expectedSize, progressNote(read, expectedSize)))
                }
            }
        }
        // Последнее сообщение прогресса обязательно: без него задание,
        // дочитавшее файл, показывало бы 99 % и ждало неизвестно чего.
        progress(JobProgress.of(read, expectedSize, progressNote(read, expectedSize)))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * Текст пояснения прогресса.
     *
     * @param read сколько байт прочитано
     * @param total сколько байт ожидается
     * @return пояснение на русском с точностью до мегабайта
     */
    private fun progressNote(
        read: Long,
        total: Long,
    ): String = "прочитано ${read / 1_048_576} из ${total / 1_048_576} МБ"

    /**
     * Читает серию по предмету задания.
     *
     * @param job задание
     * @return серия
     * @throws ru.svoemesto.syp.core.contract.DomainException если предмет задания
     *   не серия либо серия не зарегистрирована
     */
    private fun requireEpisode(job: Job): Episode {
        val subject = job.subject
        require(subject.type == SUBJECT_EPISODE && subject.identifier != null) {
            "Задание HASH без предмета «серия»: считать нечего. Предмет задания — ${subject.type}"
        }
        return episodeStore.find(subject.identifier!!)
            ?: throw ru.svoemesto.syp.core.contract.DomainException(
                ru.svoemesto.syp.core.contract.ErrorCode.NOT_FOUND,
                "серия ${subject.identifier} не зарегистрирована: подсчёт суммы невозможен",
            )
    }

    companion object {
        /** Размер блока чтения, байт: 4 МиБ. */
        const val DEFAULT_BLOCK_SIZE: Int = 4 * 1024 * 1024

        /** Как часто сообщается прогресс, байт: каждые 64 МиБ. */
        const val DEFAULT_PROGRESS_STEP: Long = 64L * 1024 * 1024

        /** Тип предмета задания для серии. */
        const val SUBJECT_EPISODE: String = "EPISODE"

        /** Имя программы в тексте ошибки: у задания нет внешней программы. */
        private const val PROGRAM_NAME: String = "подсчёт sha256"

        /**
         * Параметры открытия файла на чтение.
         *
         * Последовательное чтение запрашивается подсказкой системе, а не
         * отдельным флагом: файла серии 5,6 ГБ, и предсказуемый доступ по
         * порядку на диске заметно быстрее произвольного.
         */
        private val DEFAULT_READ_OPTIONS: Array<java.nio.file.OpenOption> =
            arrayOf(java.nio.file.StandardOpenOption.READ)
    }
}

/**
 * Подсчёт суммы не удался.
 *
 * Отдельный тип нужен, чтобы ошибка чтения файла не выглядела как «упала
 * внешняя программа»: у задания `HASH` внешней программы нет, и воркер
 * переводит задание в `ERROR` с этим текстом (FR-092).
 *
 * @property text текст ошибки для оператора
 * @see <a href="../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class HashFailed(
    val text: String,
) : RuntimeException(text)
