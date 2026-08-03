package com.flick.sender.net

import java.net.URI
import java.util.Locale

/**
 * Which key a request is carrying. The two differ in whose allowance is being spent, and
 * the sheet says so rather than implying every search is free.
 */
enum class ApiKeySource {
    /** A key the user registered and pasted. Their consumer key, their own rate limit. */
    USER,

    /** The key this build shipped with. Shared by everyone running the same APK. */
    BUNDLED,
}

/** The key a request will actually carry, and where it came from. */
data class ResolvedApiKey(val value: String, val source: ApiKeySource)

/**
 * A signed-in OpenSubtitles account. [token] is the bearer JWT `/login` handed back; the
 * password that obtained it is never stored, never logged and never held in a field.
 */
data class OpenSubtitlesSession(
    val token: String,
    val username: String,
    val expiresAtMillis: Long,
)

/** What the API said is left of the day's download allowance, for the sheet to state. */
data class SubtitleQuota(val remaining: Int, val resetsIn: String?)

/**
 * Everything about the OpenSubtitles wire that can be decided without a socket: which key
 * a request carries, whether a token is safe to put in a header, whether a download link
 * may be fetched at all, and what order results are offered in.
 *
 * Pure on purpose. This is the half of the online path a unit test can hold still, and the
 * half whose mistakes would be security mistakes.
 */
object OpenSubtitlesWire {

    /** OpenSubtitles' own domains. A link is fetched from these names or from nowhere. */
    private val AllowedHostSuffixes = listOf("opensubtitles.com", "opensubtitles.org")

    /** A JWT is long; a consumer key is short. Both are bounded before reaching a header. */
    private const val MaxKeyLength = 256
    private const val MaxTokenLength = 4_096
    private const val MaxUsernameLength = 64
    private const val MaxResetLength = 48

    /** OpenSubtitles documents a 24-hour token and does not restate it in the answer. */
    private const val DefaultSessionSeconds = 24L * 60L * 60L

    /**
     * The key resolution order: a key the user pasted, then the key this build shipped,
     * then none.
     *
     * The user's own key wins because it is the one whose limits are theirs — a power user
     * who registered a consumer key, or anyone building from source, must not be silently
     * moved onto a shared allowance. A blank [bundledKey] is the DEFAULT state of this
     * repository and resolves to no key at all, which is a state the online tab is
     * required to explain rather than an error it should report.
     */
    fun resolveKey(userKey: String?, bundledKey: String): ResolvedApiKey? {
        headerSafe(userKey, MaxKeyLength)?.let { return ResolvedApiKey(it, ApiKeySource.USER) }
        headerSafe(bundledKey, MaxKeyLength)?.let { return ResolvedApiKey(it, ApiKeySource.BUNDLED) }
        return null
    }

    /**
     * The session a `/login` answer amounts to, or null when it amounts to none. A token
     * carrying a space, a control character or a newline is not a token: it is something
     * that would be concatenated into an `Authorization` header, so it is refused here
     * rather than trusted to whatever the HTTP layer happens to do with it.
     *
     * [username] is the name the user typed, not a value the server chose, and is kept
     * only so the sheet can say who is signed in. [ttlSeconds] stands in for the answer's
     * silence about expiry; the API's own 401 remains the authority on a dead token.
     */
    fun sessionOf(
        token: String?,
        username: String?,
        nowMillis: Long,
        ttlSeconds: Long? = null,
    ): OpenSubtitlesSession? {
        val life = (ttlSeconds?.takeIf { it > 0L } ?: DefaultSessionSeconds) * 1_000L
        val expiry = if (nowMillis > Long.MAX_VALUE - life) Long.MAX_VALUE else nowMillis + life
        return restoredSession(token, username, expiry)
    }

    /**
     * The same session read back out of storage, with the expiry that was stored rather
     * than one computed now. The token is re-checked on the way out: a preferences file is
     * a file, and a value that could not be put in a header is not one this trusts because
     * it was written yesterday.
     */
    fun restoredSession(
        token: String?,
        username: String?,
        expiresAtMillis: Long,
    ): OpenSubtitlesSession? {
        val bearer = headerSafe(token, MaxTokenLength) ?: return null
        return OpenSubtitlesSession(
            token = bearer,
            username = ControlProtocolV2.normalizedLabel(username, MaxUsernameLength).orEmpty(),
            expiresAtMillis = expiresAtMillis,
        )
    }

    /** Whether [session] is still worth attaching; a 401 drops it on the same terms. */
    fun sessionIsLive(session: OpenSubtitlesSession, nowMillis: Long): Boolean =
        session.expiresAtMillis > nowMillis

    /**
     * Whether the CDN link the API handed back may be fetched.
     *
     * The link is signed by a host the server chose, and it is fetched with no credential
     * attached — but it still has to be an address this app named: https, no user-info, a
     * default port, and a name under one of [AllowedHostSuffixes]. An allow-list is only
     * as current as that list, which is the trade being made: a CDN domain OpenSubtitles
     * adds later reads as a refused link the user is told about, rather than as a silent
     * fetch from anywhere at all.
     */
    fun downloadLinkIsAllowed(link: String?): Boolean {
        val target = link?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val uri = runCatching { URI(target) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return AllowedHostSuffixes.any { host == it || host.endsWith(".$it") }
    }

    /**
     * The one hop a `Location` header is allowed to name, or null.
     *
     * The client follows no redirects at all, so a 3xx from the CDN arrives here as a
     * response rather than as a fetch that already happened somewhere else. A relative
     * target is refused — resolving one means trusting the server about which base it
     * resolves against — and an absolute one has to pass [downloadLinkIsAllowed], the same
     * check the original link passed.
     */
    fun allowedRedirect(location: String?): String? =
        location?.trim()?.takeIf { downloadLinkIsAllowed(it) }

    /**
     * A `/subtitles` query in the only spelling the API answers.
     *
     * OpenSubtitles does not merely accept a query, it normalises one, and replies to
     * anything else with a 301 naming the spelling it wanted. Three rules, each confirmed
     * against the live API: parameters sorted by name, values lower-cased, and a space
     * written `+` rather than `%20`. Searching for `Blade Runner` breaks all three at once,
     * and `query` before `season_number` breaks the first — which is to say every text
     * search this app can produce was un-canonical, while the hash search, a lone
     * lower-case hex parameter, was canonical by accident.
     *
     * That 301 is not a hop available to this client. Redirects are refused on every call
     * it makes, for the reason given on its [HttpClient][OpenSubtitlesClient], so an
     * un-canonical query does not cost a round trip — it returns no results at all, as a
     * `Unavailable`. The correct spelling has to be built here or not at all.
     *
     * Percent-encoding is left to the caller and is not part of the normal form: `%27`,
     * `%3A`, `%26`, `%C3%A9` and a literal `*` all answer 200 unchanged. Only the space is
     * special-cased, because `+` and `%20` are both legal spellings of it and the server
     * has picked one.
     *
     * The lower-casing is [Locale.ROOT]'s, never the device's: the term is derived from a
     * filename, and a phone set to Turkish would map the `I` in `INCEPTION` to a dotless
     * `ı` and search for a title that does not exist.
     */
    fun canonicalQuery(parameters: List<Pair<String, Any>>): List<Pair<String, String>> =
        parameters
            .map { (name, value) -> name to value.toString().lowercase(Locale.ROOT) }
            .sortedBy { it.first }

    fun textSearchParameters(
        query: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        language: OpenSubtitlesLanguage,
    ): List<Pair<String, Any>> = buildList {
        val term = OpenSubtitlesSearchPolicy.textQuery(query).value ?: return@buildList
        val isEpisode = season?.let(OpenSubtitlesSearchPolicy::validSeason) == true &&
            episode?.let(OpenSubtitlesSearchPolicy::validEpisode) == true
        add("query" to term)
        add("languages" to language.code)
        year?.takeIf { !isEpisode && OpenSubtitlesSearchPolicy.validYear(it) }?.let { add("year" to it) }
        season?.takeIf(OpenSubtitlesSearchPolicy::validSeason)?.let { add("season_number" to it) }
        episode?.takeIf(OpenSubtitlesSearchPolicy::validEpisode)?.let { add("episode_number" to it) }
        if (isEpisode) add("type" to "episode")
    }

    fun hashSearchParameters(
        fingerprint: com.flick.sender.media.MovieHash.Fingerprint?,
        language: OpenSubtitlesLanguage,
    ): List<Pair<String, Any>> {
        val exact = fingerprint?.takeIf {
            com.flick.sender.media.MovieHash.isWellFormed(it.hash) &&
                it.sizeBytes >= com.flick.sender.media.MovieHash.MinBytes
        } ?: return emptyList()
        return buildList {
            add("moviehash" to exact.hash)
            add("moviebytesize" to exact.sizeBytes)
            add("languages" to language.code)
            add("moviehash_match" to "only")
        }
    }

    /**
     * Exact-file sync remains the first discriminator. Within the same match class, which
     * work a result is about outranks everything about how good a subtitle it is, then
     * complete/human/trusted provenance, then ratings and popularity make the API's quality
     * signals deterministic instead of depending on response order.
     *
     * A result the hash named is never re-judged on [title]: the hash identifies these
     * exact bytes, and second-guessing it with a filename-derived title is how a correct
     * answer gets buried by a renamed file.
     */
    fun ordered(
        results: List<OnlineSubtitle>,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ): List<OnlineSubtitle> = results.sortedWith(
        compareByDescending<OnlineSubtitle> { it.hashMatch }
            .thenByDescending { result ->
                if (result.hashMatch) {
                    ExactFileRelevance
                } else {
                    OpenSubtitlesMatchPolicy.relevance(result, title, year, season, episode)
                }
            }
            .thenBy { it.foreignPartsOnly }
            .thenBy { it.aiTranslated || it.machineTranslated }
            .thenByDescending { it.trusted }
            .thenByDescending { result -> if (result.votes > 0) result.rating else 0.0 }
            .thenByDescending { it.votes }
            .thenByDescending { it.downloads }
            .thenBy { it.fileId },
    )

    /**
     * Hash results ahead of text results, one row per file id, capped at [limit]. The two
     * searches overlap by design; a file that both found keeps the hash-search copy,
     * because that is the copy that knows it matches.
     *
     * Only the text half is filtered for recognizability. A hash result already named this
     * file and owes no explanation to a title that may be a rename.
     */
    fun merged(
        hashResults: List<OnlineSubtitle>,
        textResults: List<OnlineSubtitle>,
        limit: Int,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ): List<OnlineSubtitle> {
        val recognizable = OpenSubtitlesMatchPolicy.recognizable(textResults, title, season, episode)
        val out = ArrayList<OnlineSubtitle>(minOf(limit, hashResults.size + recognizable.size))
        val seen = HashSet<Long>()
        for (result in ordered(hashResults, title, year, season, episode) +
            ordered(recognizable, title, year, season, episode)
        ) {
            if (out.size >= limit) break
            if (seen.add(result.fileId)) out += result
        }
        return out
    }

    /** Every hash result shares this, so they tie here and settle on quality as before. */
    private val ExactFileRelevance = SubtitleRelevance(
        title = SubtitleTitleAgreement.AGREES,
        kind = SubtitleKindAgreement.AGREES,
        metadata = 2,
    )

    /**
     * The remaining-downloads figure out of a `/download` answer, or null when it stated
     * none. [resetTime] is server-supplied prose, so it is normalized and cut short before
     * it can be put on screen.
     */
    fun quotaOf(remaining: Int, resetTime: String?): SubtitleQuota? {
        if (remaining < 0) return null
        return SubtitleQuota(
            remaining = remaining,
            resetsIn = ControlProtocolV2.normalizedLabel(resetTime, MaxResetLength),
        )
    }

    /**
     * A trimmed value that can be written into a header verbatim: visible ASCII only, so
     * no space, tab, CR or LF can split one header into two.
     */
    private fun headerSafe(value: String?, maximum: Int): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.length > maximum) return null
        return trimmed.takeIf { candidate -> candidate.all { it.code in 0x21..0x7E } }
    }
}
