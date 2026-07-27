package com.flick.sender.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How strongly a candidate subtitle file's name binds it to the video's name. */
enum class SubtitleMatchKind { EXACT, PREFIX, FUZZY }

/** [score] only orders candidates inside one [kind]; it is never compared across kinds. */
data class SubtitleMatch(
    val kind: SubtitleMatchKind,
    val score: Float,
    val language: String?,
)

/**
 * Name-only subtitle sourcing. Every function here is pure so the sidecar rules can be
 * tested without a device: on Android 16 there is no permission that would let Flick
 * scan the filesystem for sidecars (READ_MEDIA_VIDEO does not cover .srt and MediaStore
 * does not index it), so a folder the user granted once is matched by NAME and nothing
 * else — the content is never opened to decide.
 */
object SubtitleFiles {

    /** Extensions Media3 can sideload as a text track. */
    // No "sub": MicroDVD has no Media3 parser, and a VobSub .sub is a bitmap stream that
    // is meaningless without its .idx companion. Offering either would attach a track
    // that draws nothing.
    val SubtitleExtensions = setOf("srt", "vtt", "ass", "ssa")

    /**
     * Providers disagree about an .srt MIME type — the same file is reported as
     * application/x-subrip, text/plain or application/octet-stream depending on which
     * DocumentsProvider answers — so the picker filter is wide and [isSubtitleName]
     * decides by extension afterwards.
     */
    val PickerMimeTypes = arrayOf(
        "application/x-subrip",
        "text/plain",
        "text/vtt",
        "application/octet-stream",
    )

    /** Same ceiling the phone's /s/{token} route enforces; a subtitle is kilobytes. */
    const val MaxSubtitleBytes = 5L * 1024L * 1024L

    private val Separators = charArrayOf('.', '_', '-', ' ', '(', ')', '[', ']', '+', ',', '\'')

    /**
     * Tokens that say nothing about WHICH title a file belongs to. A shared token from
     * this set can never be the reason two names are called a match.
     */
    private val ReleaseNoise = setOf(
        "1080p", "2160p", "720p", "480p", "4k", "uhd", "hd", "sd", "hdr", "hdr10", "dv",
        "sdr", "10bit", "8bit", "x264", "x265", "h264", "h265", "hevc", "avc", "xvid",
        "divx", "web", "webrip", "webdl", "bluray", "brrip", "bdrip", "dvdrip", "hdtv",
        "remux", "proper", "repack", "extended", "unrated", "internal", "aac", "ac3",
        "dts", "eac3", "truehd", "atmos", "multi", "dual", "audio", "subs", "subtitle",
        "subtitles", "sub", "season", "episode", "part", "cd1", "cd2",
    )

    /**
     * Words too common to prove two names are the same title. They are kept out of
     * [ReleaseNoise] because [searchQuery] must not truncate "The Matrix" at "The".
     */
    private val CommonWords = setOf(
        "the", "and", "for", "with", "from", "that", "this", "les", "des", "der",
        "die", "das", "una", "los", "del", "you", "our", "his", "her",
    )

    /** Suffix words that qualify a subtitle rather than name a language. */
    private val FlagSegments = setOf(
        "forced", "sdh", "cc", "hearing", "impaired", "default", "full", "colour",
        "color", "closed", "captions",
    )

    private val Iso6391 = setOf(
        "aa", "ab", "af", "am", "ar", "as", "az", "ba", "be", "bg", "bn", "bo", "br",
        "bs", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es", "et", "eu", "fa",
        "fi", "fo", "fr", "ga", "gd", "gl", "gu", "he", "hi", "hr", "ht", "hu", "hy",
        "id", "is", "it", "ja", "jv", "ka", "kk", "km", "kn", "ko", "ku", "ky", "la",
        "lb", "lo", "lt", "lv", "mg", "mi", "mk", "ml", "mn", "mr", "ms", "mt", "my",
        "nb", "ne", "nl", "nn", "no", "oc", "or", "pa", "pl", "ps", "pt", "qu", "ro",
        "ru", "rw", "sa", "sd", "si", "sk", "sl", "so", "sq", "sr", "su", "sv", "sw",
        "ta", "te", "tg", "th", "tk", "tl", "tr", "tt", "uk", "ur", "uz", "vi", "yi",
        "yo", "zh", "zu",
    )

    /** ISO 639-2/B and /T both appear in the wild; both map to the 639-1 tag. */
    private val Iso6392To1 = mapOf(
        "ara" to "ar", "ben" to "bn", "bul" to "bg", "cat" to "ca", "ces" to "cs",
        "cze" to "cs", "chi" to "zh", "zho" to "zh", "dan" to "da", "deu" to "de",
        "ger" to "de", "dut" to "nl", "nld" to "nl", "ell" to "el", "gre" to "el",
        "eng" to "en", "est" to "et", "fas" to "fa", "per" to "fa", "fin" to "fi",
        "fra" to "fr", "fre" to "fr", "heb" to "he", "hin" to "hi", "hrv" to "hr",
        "hun" to "hu", "ind" to "id", "isl" to "is", "ice" to "is", "ita" to "it",
        "jpn" to "ja", "kan" to "kn", "kor" to "ko", "lav" to "lv", "lit" to "lt",
        "mal" to "ml", "mar" to "mr", "may" to "ms", "msa" to "ms", "nor" to "no",
        "nob" to "nb", "pol" to "pl", "por" to "pt", "ron" to "ro", "rum" to "ro",
        "rus" to "ru", "slk" to "sk", "slo" to "sk", "slv" to "sl", "spa" to "es",
        "srp" to "sr", "swe" to "sv", "tam" to "ta", "tel" to "te", "tha" to "th",
        "tur" to "tr", "ukr" to "uk", "urd" to "ur", "vie" to "vi",
    )

    private val LanguageNames = mapOf(
        "arabic" to "ar", "bengali" to "bn", "chinese" to "zh", "czech" to "cs",
        "danish" to "da", "dutch" to "nl", "english" to "en", "finnish" to "fi",
        "french" to "fr", "german" to "de", "greek" to "el", "hebrew" to "he",
        "hindi" to "hi", "hungarian" to "hu", "indonesian" to "id", "italian" to "it",
        "japanese" to "ja", "kannada" to "kn", "korean" to "ko", "malayalam" to "ml",
        "marathi" to "mr", "norwegian" to "no", "polish" to "pl", "portuguese" to "pt",
        "romanian" to "ro", "russian" to "ru", "spanish" to "es", "swedish" to "sv",
        "tamil" to "ta", "telugu" to "te", "thai" to "th", "turkish" to "tr",
        "ukrainian" to "uk", "urdu" to "ur", "vietnamese" to "vi",
    )

    private val RegionQualified = Regex("^([A-Za-z]{2,3})[-_]([A-Za-z]{2,4})$")
    private val Year = Regex("^(19|20)\\d{2}$")
    private val SeasonEpisode = Regex("^[sS](\\d{1,2})[eE](\\d{1,3})$")

    /** Lower-cased extension without the dot, or null when the name carries none. */
    fun extensionOf(displayName: String): String? {
        val name = fileName(displayName)
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    fun isSubtitleName(displayName: String): Boolean = extensionOf(displayName) in SubtitleExtensions

    /** The name with its final extension removed; unchanged when there is none. */
    fun baseName(displayName: String): String {
        val name = fileName(displayName)
        val extension = extensionOf(name) ?: return name
        return name.substring(0, name.length - extension.length - 1)
    }

    /** Base name split on every separator a release name uses, lower-cased. */
    fun tokensOf(displayName: String): List<String> =
        baseName(displayName).split(*Separators).filter { it.isNotBlank() }.map { it.lowercase() }

    /** Separator- and case-insensitive form two names can be compared in. */
    fun normalize(displayName: String): String = tokensOf(displayName).joinToString(" ")

    /**
     * How well [candidateDisplayName] names the same title as [videoDisplayName], or
     * null when it names something else — a non-match must stay a non-match, or the
     * folder tab starts offering every subtitle on the phone for every film.
     */
    fun match(videoDisplayName: String, candidateDisplayName: String): SubtitleMatch? {
        if (!isSubtitleName(candidateDisplayName)) return null
        val videoTokens = tokensOf(videoDisplayName)
        val candidateTokens = tokensOf(candidateDisplayName)
        if (videoTokens.isEmpty() || candidateTokens.isEmpty()) return null
        // Two SxxEyy markers that disagree are a different episode of the same show,
        // which every other rule below would otherwise call a match.
        val videoEpisode = episodeOf(videoDisplayName)
        val candidateEpisode = episodeOf(candidateDisplayName)
        if (videoEpisode != null && candidateEpisode != null && videoEpisode != candidateEpisode) return null
        val language = languageTagOf(videoDisplayName, candidateDisplayName)

        val video = videoTokens.joinToString(" ")
        val candidate = candidateTokens.joinToString(" ")
        if (video == candidate) return SubtitleMatch(SubtitleMatchKind.EXACT, 1f, language)
        if (candidate.startsWith("$video ") || video.startsWith("$candidate ")) {
            val shorter = minOf(video.length, candidate.length).toFloat()
            return SubtitleMatch(SubtitleMatchKind.PREFIX, shorter / maxOf(video.length, candidate.length), language)
        }

        // Release names reorder and re-tag the same film, so the fallback is token
        // overlap — measured against the CANDIDATE's own content tokens, because a
        // sidecar carries fewer of them than the video it belongs to.
        val videoContent = videoTokens.toSet()
        val candidateContent = candidateTokens.filterIndexed { index, token ->
            index == 0 || (primaryLanguage(token) == null && token !in FlagSegments)
        }.toSet()
        if (candidateContent.isEmpty()) return null
        val shared = candidateContent.filter { it in videoContent }
        if (shared.none { it.any(Char::isLetter) && it.length >= 3 && it !in ReleaseNoise && it !in CommonWords }) {
            return null
        }
        val coverage = shared.size.toFloat() / candidateContent.size
        if (coverage < FuzzyFloor) return null
        return SubtitleMatch(SubtitleMatchKind.FUZZY, coverage, language)
    }

    /**
     * BCP-47 tag parsed out of a sidecar's suffix (".en.srt", ".eng.srt", ".pt-BR.srt"),
     * or null when the name carries no language at all. The first segment is always the
     * title, never a language: "It.srt" is a film, not Italian.
     */
    fun languageTagOf(videoDisplayName: String, candidateDisplayName: String): String? {
        val base = baseName(candidateDisplayName)
        // Read before the separator split, which would otherwise eat the region hyphen.
        base.split('.').filter { it.isNotBlank() }.forEachIndexed { index, segment ->
            if (index == 0) return@forEachIndexed
            val qualified = RegionQualified.matchEntire(segment) ?: return@forEachIndexed
            val primary = primaryLanguage(qualified.groupValues[1]) ?: return@forEachIndexed
            return primary + "-" + regionSubtag(qualified.groupValues[2])
        }
        val videoSegments = tokensOf(videoDisplayName).toSet()
        base.split(*Separators).filter { it.isNotBlank() }.forEachIndexed { index, segment ->
            if (index == 0) return@forEachIndexed
            if (segment.lowercase() in videoSegments) return@forEachIndexed
            primaryLanguage(segment)?.let { return it }
        }
        return null
    }

    /**
     * The title an online search should be run with: everything up to the first
     * release-noise token, year or SxxEyy marker.
     */
    fun searchQuery(videoDisplayName: String): String {
        val segments = baseName(videoDisplayName).split(*Separators).filter { it.isNotBlank() }
        val title = segments.takeWhile { segment ->
            val token = segment.lowercase()
            !Year.matches(token) && !SeasonEpisode.matches(segment) && token !in ReleaseNoise
        }
        return (if (title.isEmpty()) segments else title).joinToString(" ").trim()
    }

    /** Season and episode read off an SxxEyy marker, or null when the name has none. */
    fun episodeOf(videoDisplayName: String): Pair<Int, Int>? {
        baseName(videoDisplayName).split(*Separators).forEach { segment ->
            val marker = SeasonEpisode.matchEntire(segment) ?: return@forEach
            val season = marker.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = marker.groupValues[2].toIntOrNull() ?: return@forEach
            return season to episode
        }
        return null
    }

    /** Ranked best-first: an exact base name beats a prefix, which beats token overlap. */
    fun bestFirst(matched: List<SubtitleMatch>): List<SubtitleMatch> =
        matched.sortedWith(compareBy({ it.kind.ordinal }, { -it.score }))

    private fun primaryLanguage(segment: String): String? {
        val token = segment.lowercase()
        if (token in FlagSegments) return null
        if (token.length == 2 && token in Iso6391) return token
        if (token.length == 3) return Iso6392To1[token]
        return LanguageNames[token]
    }

    private fun regionSubtag(value: String): String =
        if (value.length == 4) value.take(1).uppercase() + value.drop(1).lowercase() else value.uppercase()

    /** Some providers hand back a path rather than a bare display name. */
    private fun fileName(displayName: String): String =
        displayName.substringAfterLast('/').substringAfterLast('\\')

    private const val FuzzyFloor = 0.6f
}

/** One subtitle file found in the granted folder. [match] is null when only the user can say. */
data class SubtitleCandidate(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val match: SubtitleMatch?,
)

/** Outcome of enumerating the remembered folder. [Found] may legitimately be empty. */
sealed interface SidecarScan {
    data class Found(val candidates: List<SubtitleCandidate>) : SidecarScan

    /** The persisted grant is gone — the user must pick the folder again. */
    data object AccessLost : SidecarScan
    data object Unreadable : SidecarScan
}

/**
 * The one folder grant Flick keeps. ACTION_OPEN_DOCUMENT_TREE plus
 * takePersistableUriPermission is the only way to enumerate sidecars at all without
 * MANAGE_EXTERNAL_STORAGE, which is Play-policy restricted and is never requested.
 */
class SubtitleFolderStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_subtitles", Context.MODE_PRIVATE)

    fun folder(): Uri? = prefs.getString(FOLDER, null)?.let(Uri::parse)

    fun save(tree: Uri): Boolean = prefs.edit().putString(FOLDER, tree.toString()).commit()

    fun forget(): Boolean = prefs.edit().remove(FOLDER).commit()

    private companion object { const val FOLDER = "sidecar_tree" }
}

/** Reads through the granted tree. Nothing here opens a file; names decide everything. */
object SubtitleFolder {

    private const val MaxDepth = 2
    private const val MaxEntries = 4_000
    private const val MaxCandidates = 300

    /** True while the persisted read grant for [tree] is still held. */
    fun holdsGrant(context: Context, tree: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions.any { it.isReadPermission && it.uri == tree }
    }.getOrDefault(false)

    /**
     * Every subtitle file in [tree] (one level of subfolders included — "Subs/" next to
     * the film is the common shape), each carrying its match against [videoDisplayName].
     */
    suspend fun scan(context: Context, tree: Uri, videoDisplayName: String?): SidecarScan =
        withContext(Dispatchers.IO) {
            if (!holdsGrant(context, tree)) return@withContext SidecarScan.AccessLost
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?: return@withContext SidecarScan.Unreadable
            val resolver = context.contentResolver
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val queue = ArrayDeque<Pair<String, Int>>()
            queue += rootId to 0
            val found = ArrayList<SubtitleCandidate>()
            var visited = 0
            var readAnything = false
            while (queue.isNotEmpty() && visited < MaxEntries && found.size < MaxCandidates) {
                val (documentId, depth) = queue.removeFirst()
                val children = runCatching {
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
                }.getOrNull() ?: continue
                runCatching {
                    resolver.query(children, projection, null, null, null)?.use { cursor ->
                        readAnything = true
                        val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        while (cursor.moveToNext() && visited < MaxEntries && found.size < MaxCandidates) {
                            visited++
                            val childId = if (idColumn >= 0) cursor.getString(idColumn) else null
                            val name = if (nameColumn >= 0) cursor.getString(nameColumn) else null
                            val mime = if (mimeColumn >= 0) cursor.getString(mimeColumn) else null
                            if (childId == null || name == null) continue
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth < MaxDepth - 1) queue += childId to depth + 1
                                continue
                            }
                            if (!SubtitleFiles.isSubtitleName(name)) continue
                            val size = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                                cursor.getLong(sizeColumn)
                            } else {
                                -1L
                            }
                            if (size > SubtitleFiles.MaxSubtitleBytes) continue
                            found += SubtitleCandidate(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId),
                                displayName = name,
                                sizeBytes = size,
                                match = videoDisplayName?.let { SubtitleFiles.match(it, name) },
                            )
                        }
                    }
                }
            }
            if (!readAnything) SidecarScan.Unreadable else SidecarScan.Found(rank(found))
        }

    /** Display name of a single picked document, or null when the provider withholds one. */
    suspend fun displayNameOf(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /** Byte size of a single picked document, or -1 when the provider reports none. */
    suspend fun sizeOf(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) {
                        cursor.getLong(column)
                    } else {
                        -1L
                    }
                }
        }.getOrNull() ?: -1L
    }

    private fun rank(candidates: List<SubtitleCandidate>): List<SubtitleCandidate> =
        candidates.sortedWith(
            compareBy(
                { it.match?.kind?.ordinal ?: Int.MAX_VALUE },
                { -(it.match?.score ?: 0f) },
                { it.displayName.lowercase() },
            ),
        )
}
