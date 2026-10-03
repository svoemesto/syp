package ru.svoemesto.syp.core.media

/**
 * Дорожка видеофайла, как её видит зонд.
 *
 * @property index номер доржки в файле, как его называет зонд; уникален в
 *   пределах видеофайла и служит ключом при повторном определении
 * @property ordinal порядковый номер среди дорожек того же вида
 * @property codecType вид дорожки: видео, аудио, субтитры, данные, вложение
 * @property codecName название кодека, каким его называет зонд
 */
data class MediaTrack(
    val index: Int,
    val ordinal: Int,
    val codecType: String,
    val codecName: String?,
)
