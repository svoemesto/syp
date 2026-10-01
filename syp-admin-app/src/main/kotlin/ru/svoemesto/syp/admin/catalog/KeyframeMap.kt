package ru.svoemesto.syp.admin.catalog

/**
 * Карта ключевых кадров серии.
 *
 * Один бит на кадр, бит 1 — кадр ключевой. Карта хранится у серии и
 * обслуживает две задачи сразу:
 *
 * 1. показать оператору признак I-кадра по всей серии (FR-023) — без строки
 *    на каждый кадр: такая таблица была бы полной таблицей кадров, которую
 *    Р-07 запрещает (research.md Т-04);
 * 2. вычислить **фактические** границы фрагмента сценария: начало — ближайший
 *    ключевой кадр не позже начала плана, конец — ближайший не раньше конца
 *    (FR-082, research.md Т-28). Воркер на машине пользователя не обходит файл
 *    в поисках ключевых кадров, а берёт готовые границы из сценария.
 *
 * **Разрядка битов.** Кадру `n` соответствует байт `n / 8`, а внутри байта —
 * маска `0x80 shr (n mod 8)`, то есть старший бит идёт за кадр 0. Порядок
 * задан здесь один раз и не меняется: карта лежит в базе, и перестановка
 * разрядки сделала бы все ранее записанные карты неверными.
 *
 * Длина карты задаётся числом кадров: `ceil(frame_count / 8)` байт. Для серии
 * `GOT.S01E01` (88 643 кадра) это 11 081 байт — против 88 643 строк, которых
 * здесь нет.
 *
 * Карта **неизменяема**: изменения — это создание новой. Иначе карта, уже
 * прочитанная для сценария, могла бы измениться под ногами у того, кто её
 * уже вычитал.
 *
 * @property frameCount число кадров серии, которому соответствует карта
 * @see <a href="../../../../../../../../../docs/features/first-vertical-slice.md">docs/features/first-vertical-slice.md</a>
 */
class KeyframeMap private constructor(
    val frameCount: Int,
    private val bits: ByteArray,
) {
    /** Длина карты в байтах: ровно `ceil(frameCount / 8)`. */
    val byteLength: Int
        get() = bits.size

    /**
     * Является ли кадр ключевым.
     *
     * @param frame номер кадра с нуля
     * @return `true`, если кадр ключевой
     * @throws IllegalArgumentException если номер кадра вне серии
     */
    fun isKeyframe(frame: Int): Boolean {
        requireFrame(frame)
        return (bits[frame / BITS_PER_BYTE].toInt() and maskOf(frame)) != 0
    }

    /**
     * Число ключевых кадров в серии.
     *
     * Считается проходом по байтам карты, а не хранится отдельным полем:
     * второе поле означало бы второй источник правды о том же факте.
     *
     * @return число установленных битов
     */
    fun keyframeCount(): Int {
        var total = 0
        for (index in 0 until lastFrameIndex() / BITS_PER_BYTE + 1) {
            total += Integer.bitCount(bits[index].toInt() and 0xFF)
        }
        return total
    }

    /**
     * Номера всех ключевых кадров по возрастанию.
     *
     * @return список номеров кадров
     */
    fun keyframeIndices(): List<Int> {
        val indices = mutableListOf<Int>()
        for (frame in 0..lastFrameIndex()) {
            if (isKeyframe(frame)) indices.add(frame)
        }
        return indices
    }

    /**
     * Ближайший ключевой кадр **не позже** указанного.
     *
     * Именно такое направление округления требуется для начала фрагмента:
     * обрезать раньше расчётной границы нельзя — сценарий потеряет кадры
     * плана, а обрезать позже можно (ADR-0006, FR-082).
     *
     * @param frame номер кадра
     * @return номер ключевого кадра или `null`, если раньше него ключевых нет
     * @throws IllegalArgumentException если номер кадра вне серии
     */
    fun lastKeyframeAtOrBefore(frame: Int): Int? {
        requireFrame(frame)
        for (candidate in frame downTo 0) {
            if (isKeyframe(candidate)) return candidate
        }
        return null
    }

    /**
     * Ближайший ключевой кадр **не раньше** указанного.
     *
     * Так округляется конец фрагмента: закончить раньше расчётной границы
     * нельзя, позже — можно.
     *
     * @param frame номер кадра
     * @return номер ключевого кадра или `null`, если после него ключевых нет
     * @throws IllegalArgumentException если номер кадра вне серии
     */
    fun firstKeyframeAtOrAfter(frame: Int): Int? {
        requireFrame(frame)
        for (candidate in frame..lastFrameIndex()) {
            if (isKeyframe(candidate)) return candidate
        }
        return null
    }

    /**
     * Байты карты для записи в базу.
     *
     * @return копия байтов карты длиной [byteLength]
     */
    fun toByteArray(): ByteArray = bits.copyOf()

    /** Проверяет, что номер кадра принадлежит серии. */
    private fun requireFrame(frame: Int) {
        require(frame in 0..lastFrameIndex()) {
            "Кадр $frame вне серии из $frameCount кадров: нумерация с нуля, " +
                "последний кадр ${lastFrameIndex()}"
        }
    }

    /** Номер последнего кадра серии. */
    private fun lastFrameIndex(): Int = frameCount - 1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyframeMap) return false
        return frameCount == other.frameCount && bits.contentEquals(other.bits)
    }

    override fun hashCode(): Int = 31 * frameCount + bits.contentHashCode()

    override fun toString(): String =
        "KeyframeMap(frameCount=$frameCount, bytes=$byteLength, keyframes=${keyframeCount()})"

    companion object {
        /** Сколько кадров помещается в один байт карты. */
        const val BITS_PER_BYTE: Int = 8

        /**
         * Требуемая длина карты для серии с указанным числом кадров.
         *
         * @param frameCount число кадров серии
         * @return длина карты в байтах: округление вверх до целого байта
         * @throws IllegalArgumentException если число кадров не положительно
         */
        fun requiredLength(frameCount: Int): Int {
            require(frameCount > 0) {
                "Число кадров серии должно быть положительным, задано $frameCount"
            }
            return (frameCount + BITS_PER_BYTE - 1) / BITS_PER_BYTE
        }

        /**
         * Строит карту по номерам ключевых кадров.
         *
         * @param frameCount число кадров серии
         * @param keyframes номера ключевых кадров; повторы допустимы, порядок
         *   значения не имеет
         * @return готовая карта
         * @throws IllegalArgumentException если номер кадра вне серии
         */
        fun build(
            frameCount: Int,
            keyframes: Iterable<Int>,
        ): KeyframeMap {
            val bits = ByteArray(requiredLength(frameCount))
            val map = KeyframeMap(frameCount, bits)
            keyframes.forEach { frame ->
                require(frame in 0..map.lastFrameIndex()) {
                    "Ключевой кадр $frame вне серии из $frameCount кадров"
                }
                bits[frame / BITS_PER_BYTE] =
                    (bits[frame / BITS_PER_BYTE].toInt() or maskOf(frame)).toByte()
            }
            return map
        }

        /**
         * Строит карту из последовательности признаков по кадрам.
         *
         * Принимается поток признаков «по одному на кадр» в том же порядке, в
         * каком кадры идут в файле: номер кадра получается позицией, а не
         * округлением времени, — это и есть требование ADR-0001.
         *
         * @param frameCount число кадров серии
         * @param flags признак «кадр ключевой» по кадрам, начиная с первого
         * @return готовая карта
         * @throws IllegalArgumentException если признаков не столько же,
         *   сколько кадров
         */
        fun ofFlags(
            frameCount: Int,
            flags: Iterable<Boolean>,
        ): KeyframeMap {
            val bits = ByteArray(requiredLength(frameCount))
            var frame = 0
            for (flag in flags) {
                require(frame <= frameCount) {
                    "Признаков больше, чем кадров в серии: кадров $frameCount, " +
                        "признак №${frame + 1}"
                }
                if (flag) {
                    bits[frame / BITS_PER_BYTE] =
                        (bits[frame / BITS_PER_BYTE].toInt() or maskOf(frame)).toByte()
                }
                frame++
            }
            require(frame == frameCount) {
                "Признаков меньше, чем кадров в серии: кадров $frameCount, признаков $frame"
            }
            return KeyframeMap(frameCount, bits)
        }

        /**
         * Разбирает карту из байтов, прочитанных в базе.
         *
         * @param frameCount число кадров серии
         * @param bytes байты карты
         * @return готовая карта
         * @throws IllegalArgumentException если длина байтов не равна
         *   требуемой для указанного числа кадров
         */
        fun parse(
            frameCount: Int,
            bytes: ByteArray,
        ): KeyframeMap {
            val expected = requiredLength(frameCount)
            require(bytes.size == expected) {
                "Длина карты ключевых кадров ${bytes.size} байт, а для серии из " +
                    "$frameCount кадров требуется $expected: карта не соответствует серии"
            }
            return KeyframeMap(frameCount, bytes.copyOf())
        }

        /**
         * Карта без единого ключевого кадра.
         *
         * Случай «ключевых кадров нет» в файле невозможен, но пустая карта
         * нужна до заполнения: серия зарегистрирована, а карта ещё не
         * посчитана.
         *
         * @param frameCount число кадров серии
         * @return пустая карта
         */
        fun empty(frameCount: Int): KeyframeMap = KeyframeMap(frameCount, ByteArray(requiredLength(frameCount)))

        /** Маска бита кадра внутри его байта. */
        private fun maskOf(frame: Int): Int = 0x80 shr (frame % BITS_PER_BYTE)
    }
}
