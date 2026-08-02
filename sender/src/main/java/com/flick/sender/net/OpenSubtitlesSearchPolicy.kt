package com.flick.sender.net

import java.text.Normalizer

/** OpenSubtitles language identifiers offered by Flick's finite, validated selector. */
enum class OpenSubtitlesLanguage(val code: String) {
    ABKHAZIAN("ab"),
    AFRIKAANS("af"),
    ALBANIAN("sq"),
    AMHARIC("am"),
    ARABIC("ar"),
    ARAGONESE("an"),
    ARMENIAN("hy"),
    ASSAMESE("as"),
    ASTURIAN("at"),
    AZERBAIJANI("az-az"),
    BASQUE("eu"),
    BELARUSIAN("be"),
    BENGALI("bn"),
    BOSNIAN("bs"),
    BRETON("br"),
    BULGARIAN("bg"),
    BURMESE("my"),
    CATALAN("ca"),
    CHINESE_BILINGUAL("ze"),
    CHINESE_CANTONESE("zh-ca"),
    CHINESE_SIMPLIFIED("zh-cn"),
    CHINESE_TRADITIONAL("zh-tw"),
    CROATIAN("hr"),
    CZECH("cs"),
    DANISH("da"),
    DARI("pr"),
    DUTCH("nl"),
    ENGLISH("en"),
    ESPERANTO("eo"),
    ESTONIAN("et"),
    EXTREMADURAN("ex"),
    FINNISH("fi"),
    FRENCH("fr"),
    GAELIC("gd"),
    GALICIAN("gl"),
    GEORGIAN("ka"),
    GERMAN("de"),
    GREEK("el"),
    HEBREW("he"),
    HINDI("hi"),
    HUNGARIAN("hu"),
    ICELANDIC("is"),
    IGBO("ig"),
    INDONESIAN("id"),
    INTERLINGUA("ia"),
    IRISH("ga"),
    ITALIAN("it"),
    JAPANESE("ja"),
    KANNADA("kn"),
    KAZAKH("kk"),
    KHMER("km"),
    KOREAN("ko"),
    KURDISH("ku"),
    LATVIAN("lv"),
    LITHUANIAN("lt"),
    LUXEMBOURGISH("lb"),
    MACEDONIAN("mk"),
    MALAY("ms"),
    MALAYALAM("ml"),
    MANIPURI("ma"),
    MARATHI("mr"),
    MONGOLIAN("mn"),
    MONTENEGRIN("me"),
    NAVAJO("nv"),
    NEPALI("ne"),
    NORTHERN_SAMI("se"),
    NORWEGIAN("no"),
    OCCITAN("oc"),
    ODIA("or"),
    PERSIAN("fa"),
    POLISH("pl"),
    PORTUGUESE("pt-pt"),
    PORTUGUESE_BRAZIL("pt-br"),
    PORTUGUESE_MOZAMBIQUE("pm"),
    PUSHTO("ps"),
    ROMANIAN("ro"),
    RUSSIAN("ru"),
    SANTALI("sx"),
    SERBIAN("sr"),
    SINDHI("sd"),
    SINHALESE("si"),
    SLOVAK("sk"),
    SLOVENIAN("sl"),
    SOMALI("so"),
    SOUTH_AZERBAIJANI("az-zb"),
    SPANISH("es"),
    SPANISH_EUROPE("sp"),
    SPANISH_LATIN_AMERICA("ea"),
    SWAHILI("sw"),
    SWEDISH("sv"),
    SYRIAC("sy"),
    TAGALOG("tl"),
    TAMIL("ta"),
    TATAR("tt"),
    TELUGU("te"),
    TETUM("tm-td"),
    THAI("th"),
    TOKI_PONA("tp"),
    TURKISH("tr"),
    TURKMEN("tk"),
    UKRAINIAN("uk"),
    URDU("ur"),
    UZBEK("uz"),
    VIETNAMESE("vi"),
    WELSH("cy"),
}

enum class OpenSubtitlesTextState { EMPTY, TOO_SHORT, READY }

data class OpenSubtitlesTextQuery(
    val value: String?,
    val state: OpenSubtitlesTextState,
)

/** Pure validation and fallback rules shared by the UI and the network client. */
object OpenSubtitlesSearchPolicy {
    const val MinimumQueryCodePoints = 3
    const val MinimumYear = 1870
    const val MaximumYear = 2099
    const val MinimumSeason = 1
    const val MaximumSeason = 99
    const val MinimumEpisode = 1
    const val MaximumEpisode = 999

    val DefaultLanguage = OpenSubtitlesLanguage.ENGLISH

    fun textQuery(raw: String): OpenSubtitlesTextQuery {
        val normalized = normalizedSearchText(raw)
            ?: return OpenSubtitlesTextQuery(null, OpenSubtitlesTextState.EMPTY)
        val length = normalized.codePointCount(0, normalized.length)
        return if (length < MinimumQueryCodePoints) {
            OpenSubtitlesTextQuery(null, OpenSubtitlesTextState.TOO_SHORT)
        } else {
            OpenSubtitlesTextQuery(normalized, OpenSubtitlesTextState.READY)
        }
    }

    fun validYear(value: Int): Boolean = value in MinimumYear..MaximumYear
    fun validSeason(value: Int): Boolean = value in MinimumSeason..MaximumSeason
    fun validEpisode(value: Int): Boolean = value in MinimumEpisode..MaximumEpisode

    fun languageParameter(languages: Collection<OpenSubtitlesLanguage>): String? = languages
        .asSequence()
        .map(OpenSubtitlesLanguage::code)
        .distinct()
        .sorted()
        .toList()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(",")

    /** An exact file match is authoritative; heuristic hash answers still need text fallback. */
    fun shouldRunTextFallback(
        hashResults: List<OnlineSubtitle>,
        query: OpenSubtitlesTextQuery,
    ): Boolean = query.state == OpenSubtitlesTextState.READY && hashResults.none { it.hashMatch }

    private fun normalizedSearchText(raw: String): String? {
        val cleaned = StringBuilder(raw.length)
        raw.codePoints().forEach { codePoint ->
            when {
                codePoint in UnsafeFormatCodePoints -> Unit
                Character.getType(codePoint) in SpacingTypes || Character.isWhitespace(codePoint) ->
                    cleaned.append(' ')
                Character.getType(codePoint) == Character.CONTROL.toInt() -> cleaned.append(' ')
                Character.getType(codePoint) == Character.SURROGATE.toInt() -> Unit
                else -> cleaned.appendCodePoint(codePoint)
            }
        }
        val normalized = Normalizer.normalize(cleaned.toString(), Normalizer.Form.NFC)
            .trim()
            .replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return null
        val bounded = StringBuilder()
        normalized.codePoints().limit(MaximumQueryCodePoints.toLong()).forEach(bounded::appendCodePoint)
        return bounded.toString()
    }

    private val SpacingTypes = setOf(
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )
    private val UnsafeFormatCodePoints = setOf(
        0x00AD, 0x061C, 0x200B, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D,
        0x202E, 0x2060, 0x2066, 0x2067, 0x2068, 0x2069, 0x206A, 0x206B, 0x206C,
        0x206D, 0x206E, 0x206F, 0xFEFF, 0xFFF9, 0xFFFA, 0xFFFB,
    )
    private const val MaximumQueryCodePoints = 200
}
