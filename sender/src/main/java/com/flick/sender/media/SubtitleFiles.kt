package com.flick.sender.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Name-only subtitle sourcing. Everything here is pure so the rules can be tested
 * without a device.
 *
 * Nothing is ever DISCOVERED: on Android 16 there is no permission Flick may ask for that
 * exposes .srt files (READ_MEDIA_VIDEO does not cover them and MediaStore does not index
 * them), so a local subtitle is the single file the user pointed at. What is left to
 * decide is decided from that file's NAME — the extension says whether Media3 can render
 * it at all, a suffix says what language it is in — and its content is never opened.
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
}

/**
 * The folder grant a build BEFORE this one took, and the only handle left on it.
 *
 * That build offered a whole folder of sidecars, picked with ACTION_OPEN_DOCUMENT_TREE and
 * held with takePersistableUriPermission — which nothing expires. The source is gone and
 * nothing takes a tree grant any more, so all this can still do is name the grant so it
 * can be handed back and then forget it. There is deliberately no way to save one.
 */
class SubtitleFolderStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("flick_subtitles", Context.MODE_PRIVATE)

    fun folder(): Uri? = prefs.getString(FOLDER, null)?.let(Uri::parse)

    fun forget(): Boolean = prefs.edit().remove(FOLDER).commit()

    private companion object { const val FOLDER = "sidecar_tree" }
}

/**
 * Hand back the folder grant a build before this one took, and forget the preference
 * naming it.
 *
 * Nothing expires a persistable URI permission, so without this that grant outlives every
 * surface that could show it: a standing read over a folder of the user's files, held by an
 * app with no screen left that admits to holding it. It used to run from the subtitles
 * sheet, which is where it was taken — but that made expiry conditional on opening a sheet
 * the user may never open again, and an invisible grant is exactly the thing that must not
 * wait on being noticed.
 *
 * Released BEFORE the preference is cleared, because afterwards there is no URI left to
 * release. Called once per process from the application and never waited on: this runs on
 * [Dispatchers.IO] so it is off the path to the first frame, and on every phone but a
 * developer's — this app has not shipped — the whole cost is one preference read answering
 * null.
 */
suspend fun releaseRetiredSubtitleFolder(context: Context) {
    withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val store = SubtitleFolderStore(app)
        val tree = store.folder() ?: return@withContext
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        store.forget()
    }
}

/**
 * What a picked subtitle document says about ITSELF, read off the provider's own columns.
 *
 * Nothing here opens a file and nothing here enumerates one: a DocumentsProvider answers a
 * name and a size for the single URI the user chose, and those two answers are all the
 * sheet needs to accept or refuse it.
 */
object SubtitleDocument {

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
}
