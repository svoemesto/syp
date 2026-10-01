package ru.svoemesto.syp.admin.catalog

import ru.svoemesto.syp.core.contract.DomainException
import ru.svoemesto.syp.core.contract.ErrorCode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Регистрация серии в сериале.
 *
 * Регистрация — единственное место, где в систему попадает путь к файлу. Здесь
 * проверяется всё, что делает этот путь пригодным для сценария сборки, и всё,
 * что делает его безопасным:
 *
 * 1. **Путь лежит внутри корня каталога сериала.** Сценарий обращается к
 *    файлам по путям относительно этого корня (FR-089a): у пользователя своя
 *    копия дерева под своим корнем. Серия вне корня дала бы относительный путь,
 *    который невозможно воспроизвести, то есть выдуманный путь. Отказ здесь
 *    с кодом `SOURCE_UNREADABLE`: для файла серии «источник недоступен» и
 *    «лежит вне корня» — одно и то же, и внятный текст говорит, что именно
 *    не так.
 * 2. **Проверка идёт по настоящему пути, а не по написанному.** Символ внутри
 *    корня может уводить вбок, и путь, прошедший проверку на бумаге, оказался
 *    бы снаружи. Символы раскрываются до сравнения.
 * 3. **Файл существует и читается.** Несуществующий или нечитаемый файл даёт
 *    `SOURCE_UNREADABLE` с путём в тексте, а не «успех с пустым результатом»
 *    (FR-092).
 * 4. **Параметры снимаются с самого файла.** Оператор их не вводит: введённые
 *    расходились бы с содержимым (FR-002).
 *
 * Порядок проверок неслучаен: сначала форма пути, потом его принадлежность
 * корню, потом существование файла. Иначе серия, указанная не в том каталоге и
 * несуществующая, сообщала бы «файла нет» — а дело в том, что каталог выбран не
 * тот, и оператор пошёл бы искать несуществующий файл вместо неверного корня.
 *
 * @property serials хранилище сериалов
 * @property seriesStore хранилище серий
 * @property probe опрос файла серии
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class SeriesRegistration(
    private val serials: SerialStore,
    private val seriesStore: SeriesStore,
    private val probe: SourceProbe,
) {
    /**
     * Регистрирует серию в сериале.
     *
     * @param serialId сериал-владелец
     * @param sourcePath путь к исходному видеофайлу
     * @param name название серии; если не задано, берётся имя файла без
     *   расширения
     * @return зарегистрированная серия с определёнными параметрами
     * @throws DomainException с кодом `NOT_FOUND`, если сериала нет; с кодом
     *   `SOURCE_UNREADABLE`, если путь вне корня сериала либо файл
     *   недоступен; с кодом `CONFLICT`, если такой файл уже зарегистрирован
     */
    fun register(
        serialId: Long,
        sourcePath: String,
        name: String? = null,
    ): Series {
        val serial =
            serials.find(serialId)
                ?: throw DomainException(
                    ErrorCode.NOT_FOUND,
                    "сериал $serialId не заведён: создайте сериал с корнем каталога и повторите",
                )
        val file = requireInsideRoot(serial, sourcePath)
        val parameters = probe.probe(file)
        val series =
            Series.of(
                serialId = serial.id!!,
                ordinal = serials.nextSeriesOrdinal(serialId),
                name = (name?.takeIf { it.isNotBlank() }) ?: file.fileName.toString().substringBeforeLast('.'),
                sourcePath = file.toString(),
                parameters = parameters,
            )
        return seriesStore.insert(series)
    }

    /**
     * Проверяет, что файл лежит внутри корня каталога сериала, и отдаёт его
     * настоящий путь.
     *
     * @param serial сериал-владелец
     * @param sourcePath путь, указанный оператором
     * @return путь к файлу с раскрытыми символами
     * @throws DomainException с кодом `SOURCE_UNREADABLE`, если путь не
     *   абсолютный, лежит вне корня, ведёт вбок через символ, корневой
     *   каталог недоступен или самого файла нет
     */
    fun requireInsideRoot(
        serial: Serial,
        sourcePath: String,
    ): Path {
        val root = requireAccessibleRoot(serial)
        val declared = requireAbsolute(sourcePath)
        val normalized = declared.normalize()

        if (!normalized.startsWith(root)) {
            throw outsideRoot(serial, declared)
        }
        if (!Files.isRegularFile(normalized)) {
            throw unreadableFile(
                declared,
                "по указанному пути нет файла серии; проверьте путь и права на архив",
            )
        }

        // Символ внутри корня может уводить за его пределы: проверка на
        // написанный путь этого не видит, а сценарий получит «правильный»
        // относительный путь к файлу, лежащему совсем в другом месте.
        val real = normalized.toRealPath()
        val realRoot = root.toRealPath()
        if (!real.startsWith(realRoot)) {
            throw outsideRoot(serial, declared)
        }
        if (!Files.isReadable(real)) {
            throw unreadableFile(declared, "файл не доступен для чтения: проверьте права на архив")
        }
        return real
    }

    /**
     * Проверяет, что путь указан абсолютно.
     *
     * Относительный путь означал бы, что файл ищется относительно рабочего
     * каталога сервера: одна и та же команда нашла бы разные файлы в разных
     * запусках, а относительный путь в сценарии оказался бы выдуманным.
     *
     * @param sourcePath путь, указанный оператором
     * @return разобранный путь
     * @throws DomainException с кодом `SOURCE_UNREADABLE`, если путь не
     *   абсолютный или пуст
     */
    private fun requireAbsolute(sourcePath: String): Path {
        val text = sourcePath.trim()
        if (text.isEmpty()) {
            throw DomainException(
                ErrorCode.BAD_REQUEST,
                "путь к файлу серии не задан: укажите путь внутри корня каталога сериала",
            )
        }
        val path = runCatching { Paths.get(text) }.getOrNull()
        if (path == null || !path.isAbsolute) {
            throw DomainException(
                ErrorCode.SOURCE_UNREADABLE,
                "путь к файлу серии «$text» должен быть абсолютным: относительный путь попал бы " +
                    "в сценарий сборки и оказался бы выдуманным (FR-089a)",
            )
        }
        return path.normalize()
    }

    /**
     * Проверяет, что корневой каталог сериала доступен.
     *
     * @param serial сериал-владелец
     * @return настоящий путь корня
     * @throws DomainException с кодом `SOURCE_UNREADABLE`, если каталога нет
     */
    private fun requireAccessibleRoot(serial: Serial): Path {
        val root = Paths.get(serial.sourceRoot)
        if (!Files.isDirectory(root)) {
            throw DomainException(
                ErrorCode.SOURCE_UNREADABLE,
                "корневой каталог сериала «${serial.sourceRoot}» недоступен: " +
                    "каталога нет или он не смонтирован. Проверьте корень в карточке сериала",
            )
        }
        return root.normalize()
    }

    /**
     * Отказ «путь вне корня каталога сериала».
     *
     * @param serial сериал-владелец
     * @param path путь, указанный оператором
     * @return исключение с кодом `SOURCE_UNREADABLE`
     */
    private fun outsideRoot(
        serial: Serial,
        path: Path,
    ): DomainException =
        DomainException(
            ErrorCode.SOURCE_UNREADABLE,
            "файл «$path» лежит вне корня каталога сериала «${serial.sourceRoot}»: " +
                "сценарий сборки обращается к файлам по путям относительно этого корня, " +
                "и путь вне его был бы выдуманным (FR-089a)",
        )
}
