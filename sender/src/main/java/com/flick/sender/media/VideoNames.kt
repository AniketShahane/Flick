package com.flick.sender.media

import java.text.Normalizer
import java.util.Locale

enum class VideoEdition {
    DIRECTORS_CUT,
    EXTENDED_CUT,
    FINAL_CUT,
    SPECIAL_EDITION,
    ULTIMATE_EDITION,
    COLLECTORS_EDITION,
    THEATRICAL_CUT,
    IMAX_EDITION,
    UNRATED,
    REDUX,
}

/** A filename-derived title and the structured fields safe to send to a search API. */
data class ParsedVideoName(
    val originalName: String,
    val displayName: String,
    val title: String?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val edition: VideoEdition?,
    val searchEligible: Boolean,
) {
    val searchQuery: String get() = title.takeIf { searchEligible }.orEmpty()
}

/**
 * Filename presentation and search parsing share this one conservative decision.
 * Unstructured names lose only their final video extension; files are never renamed.
 */
object VideoNames {
    private val VideoExtensions = setOf(
        "3g2", "3gp", "asf", "avi", "divx", "f4v", "flv", "m2ts", "m2v", "m4v",
        "mkv", "mov", "mp4", "mpeg", "mpeg2", "mpg", "mpv", "mts", "ogv", "rm",
        "rmvb", "ts", "vob", "webm", "wmv",
    )
    private val Segments = Regex("[^._\\s\\[\\](){}]+")
    private val Year = Regex("^(?:18[7-9]\\d|19\\d{2}|20\\d{2})$")
    private val ParenthesizedYear = Regex("^(.*\\S)\\s*\\(((?:18[7-9]\\d|19\\d{2}|20\\d{2}))\\)$")
    private val SeasonEpisode = Regex("(?i)^s(\\d{1,2})e(\\d{1,3})$")
    private val ReleaseNoise = setOf(
        "2160p", "1080p", "720p", "480p", "4k", "uhd", "hdr", "hdr10", "hdr10+",
        "dv", "dovi", "sdr", "10bit", "8bit", "x264", "x265", "h264", "h265",
        "hevc", "avc", "av1", "xvid", "divx", "web", "webrip", "web-dl", "webdl",
        "bluray", "blu-ray", "brrip", "bdrip", "dvdrip", "hdtv", "remux", "proper",
        "repack", "rerip", "internal", "aac", "aac2", "aac5", "ac3", "ddp", "ddp5",
        "eac3", "dts", "dts-hd", "truehd", "atmos", "flac", "multi", "dual", "dubbed",
        "readnfo", "nfofix", "sample", "mkv", "mp4",
    )
    private val OpaquePatterns = listOf(
        Regex("(?i)^(?:img|vid|pxl|mvimg)[-_ ]?\\d{6,}.*$"),
        Regex("(?i)^(?:img|dsc|dscf|dscn|mvimg)[-_ ]?\\d{3,6}(?:[-_ ].*)?$"),
        Regex("(?i)^vid[-_]\\d{8}[-_]wa\\d{4,}.*$"),
        Regex("^\\d{8}[-_]\\d{6}(?:[-_]\\d+)?$"),
        Regex("(?i)^screen[-_ ]?recording[-_ ].*\\d{6,}.*$"),
        Regex("(?i)^(?:generator[-_ ]?created[-_ ]?video|generated[-_ ]?video|ai[-_ ]?video)(?:[-_ ].*)?$"),
        Regex("(?i)^[a-z0-9]+[-_]generated[-_]video[-_][a-z0-9]{6,}$"),
        Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"),
        Regex("(?i)^[0-9a-f]{20,}$"),
    )

    fun parse(rawName: String): ParsedVideoName {
        val original = safeFileName(rawName)
        val base = removeFinalVideoExtension(original)
        if (base.isBlank()) return ParsedVideoName(original, base, null, null, null, null, null, false)

        ParenthesizedYear.matchEntire(base)?.let { marker ->
            val title = marker.groupValues[1].trim()
            val year = marker.groupValues[2].toInt()
            if (title.any(Char::isLetter) && !isOpaque(base) && !isOpaque(title)) {
                return ParsedVideoName(
                    originalName = original,
                    displayName = "$title ($year)",
                    title = title,
                    year = year,
                    season = null,
                    episode = null,
                    edition = null,
                    searchEligible = true,
                )
            }
        }

        val segments = Segments.findAll(base).map { it.value.trimEdgePunctuation() }
            .filter { it.isNotBlank() }.toList()
        if (segments.isEmpty()) return ParsedVideoName(original, base, null, null, null, null, null, false)

        val lowered = segments.map { it.lowercase() }
        val episodeIndex = segments.indexOfFirst { SeasonEpisode.matches(it) }.takeIf { it >= 0 }
        val episodeMarker = episodeIndex?.let { SeasonEpisode.matchEntire(segments[it]) }
        val nearestEpisodeYear = episodeIndex?.let { ep ->
            (ep - 1).takeIf { it > 0 && Year.matches(segments[it]) }
        }
        val releaseYear = if (episodeIndex == null) {
            segments.indices.lastOrNull { index ->
                val suffixHasNoise = lowered.drop(index + 1).any(::isReleaseNoise)
                val suffixHasEdition = editionOf(lowered.drop(index + 1)) != null
                val suffixHasStructure = suffixHasNoise || suffixHasEdition
                val bareYearLooksLikeMetadata = index > 0 && index == segments.lastIndex &&
                    lowered[index - 1] !in YearTitleConnectors &&
                    (segments[index].toIntOrNull() ?: Int.MAX_VALUE) <= BareYearCeiling
                index > 0 && Year.matches(segments[index]) &&
                    (index == segments.lastIndex || Year.matches(segments[index + 1]).not()) &&
                    (base.take(markerStart(base, segments[index])).containsSceneSeparator() ||
                        suffixHasStructure) &&
                    (suffixHasStructure || bareYearLooksLikeMetadata)
            }
        } else null
        val technicalIndex = if (episodeIndex == null && releaseYear == null) {
            lowered.indices.firstOrNull { index ->
                index > 0 && isReleaseNoise(lowered[index]) &&
                    lowered.drop(index).sumOf(::releaseEvidenceCount) >= 2 &&
                    base.take(markerStart(base, segments[index])).containsSceneSeparator()
            }
        } else null

        val titleEnd = nearestEpisodeYear ?: episodeIndex ?: releaseYear ?: technicalIndex
        val structured = titleEnd != null && titleEnd > 0
        if (structured) {
            val title = segments.take(titleEnd).joinToString(" ").cleanSpacing()
            if (title.any(Char::isLetter) && !isOpaque(base) && !isOpaque(title)) {
                val yearIndex = nearestEpisodeYear ?: releaseYear
                val year = yearIndex?.let { segments[it].toIntOrNull() }
                val season = episodeMarker?.groupValues?.get(1)?.toIntOrNull()
                val episode = episodeMarker?.groupValues?.get(2)?.toIntOrNull()
                val suffixStart = when {
                    episodeIndex != null -> episodeIndex + 1
                    releaseYear != null -> releaseYear + 1
                    else -> technicalIndex ?: segments.size
                }
                val edition = editionOf(lowered.drop(suffixStart))
                val display = buildList {
                    add(title)
                    year?.let { add("($it)") }
                    if (season != null && episode != null) {
                        add("S%02dE%02d".format(Locale.ROOT, season, episode))
                    }
                }.joinToString(" ")
                return ParsedVideoName(
                    originalName = original,
                    displayName = display,
                    title = title,
                    year = year,
                    season = season,
                    episode = episode,
                    edition = edition,
                    searchEligible = true,
                )
            }
        }

        val query = base.replace(Regex("[._\\s]+"), " ").cleanSpacing()
        val opaque = isOpaque(base) || isOpaque(query) || lowered.all { token ->
            isReleaseNoise(token) || token.all(Char::isDigit)
        }
        val eligible = !opaque && query.any(Char::isLetter)
        return ParsedVideoName(
            originalName = original,
            displayName = base,
            title = query.takeIf { eligible },
            year = null,
            season = null,
            episode = null,
            edition = null,
            searchEligible = eligible,
        )
    }

    fun displayName(rawName: String, simplify: Boolean, editionLabel: String?): String =
        if (simplify) format(parse(rawName), editionLabel) else safeFileName(rawName)

    fun format(parsed: ParsedVideoName, editionLabel: String?): String =
        editionLabel?.let { "${parsed.displayName} - $it" } ?: parsed.displayName

    fun safeFileName(rawName: String): String {
        val leaf = rawName.substringAfterLast('/').substringAfterLast('\\')
        val safe = StringBuilder(leaf.length)
        leaf.codePoints().forEach { codePoint ->
            when {
                codePoint in UnsafeFormatCodePoints -> Unit
                Character.getType(codePoint) == Character.CONTROL.toInt() -> safe.append(' ')
                Character.getType(codePoint) == Character.SURROGATE.toInt() -> Unit
                else -> safe.appendCodePoint(codePoint)
            }
        }
        return Normalizer.normalize(safe.toString(), Normalizer.Form.NFC).cleanSpacing()
    }

    private fun removeFinalVideoExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.lastIndex) return name
        return if (name.substring(dot + 1).lowercase() in VideoExtensions) name.substring(0, dot) else name
    }

    private fun isOpaque(base: String): Boolean = OpaquePatterns.any { it.matches(base) }

    private fun isReleaseNoise(token: String): Boolean =
        releaseEvidenceCount(token) > 0

    private fun releaseEvidenceCount(token: String): Int {
        val compound = compoundReleasePrefixLength(token)
        return if (compound >= 2) compound else if (isSimpleReleaseNoise(token)) 1 else 0
    }

    private fun isSimpleReleaseNoise(token: String): Boolean =
        token in ReleaseNoise ||
            Regex("(?i)^(?:aac|ddp|dts|eac3|ac3)\\d(?:[.-]\\d)?$").matches(token) ||
            Regex("(?i)^\\d{3,4}p$").matches(token)

    private fun compoundReleasePrefixLength(token: String): Int {
        val parts = token.split('-').filter(String::isNotBlank)
        if (parts.size < 2) return 0
        return parts.takeWhile { part -> isSimpleReleaseNoise(part) || part == "dl" }.size
    }

    private fun editionOf(tokens: List<String>): VideoEdition? {
        val joined = tokens.joinToString(" ").replace("’", "'")
        return when {
            tokens.firstOrNull() == "dc" || "director's cut" in joined ||
                "directors cut" in joined || "director cut" in joined -> VideoEdition.DIRECTORS_CUT
            "extended cut" in joined || tokens.firstOrNull() == "extended" -> VideoEdition.EXTENDED_CUT
            "final cut" in joined -> VideoEdition.FINAL_CUT
            "special edition" in joined -> VideoEdition.SPECIAL_EDITION
            "ultimate edition" in joined -> VideoEdition.ULTIMATE_EDITION
            "collector's edition" in joined || "collectors edition" in joined -> VideoEdition.COLLECTORS_EDITION
            "theatrical cut" in joined || tokens.firstOrNull() == "theatrical" -> VideoEdition.THEATRICAL_CUT
            "imax edition" in joined -> VideoEdition.IMAX_EDITION
            tokens.firstOrNull() == "unrated" -> VideoEdition.UNRATED
            tokens.firstOrNull() == "redux" -> VideoEdition.REDUX
            else -> null
        }
    }

    private fun markerStart(base: String, marker: String): Int =
        base.indexOf(marker).takeIf { it >= 0 } ?: base.length

    private fun String.containsSceneSeparator(): Boolean = any { it == '.' || it == '_' }

    private fun String.trimEdgePunctuation(): String = trim { it in "[](){}_" }

    private fun String.cleanSpacing(): String = trim().replace(Regex("\\s+"), " ")

    private val UnsafeFormatCodePoints = setOf(
        0x00AD, 0x061C, 0x200B, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D,
        0x202E, 0x2060, 0x2066, 0x2067, 0x2068, 0x2069, 0x206A, 0x206B, 0x206C,
        0x206D, 0x206E, 0x206F, 0xFEFF, 0xFFF9, 0xFFFA, 0xFFFB,
    )
    private val YearTitleConnectors = setOf("of", "in", "from", "since", "until")
    private const val BareYearCeiling = 2035
}
