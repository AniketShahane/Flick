package com.flick.sender.net

import java.net.URI

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
     * Hash matches first.
     *
     * A subtitle the server flagged `moviehash_match` was uploaded against this exact
     * file, so it is already in sync with the bytes being cast. That is worth more than
     * any download count, and the sheet must not bury it under a more popular guess at
     * the same title. Ordering is stable, so everything else keeps the order the API
     * chose — which is its own popularity ranking.
     */
    fun ordered(results: List<OnlineSubtitle>): List<OnlineSubtitle> =
        results.sortedByDescending { it.hashMatch }

    /**
     * Hash results ahead of text results, one row per file id, capped at [limit]. The two
     * searches overlap by design; a file that both found keeps the hash-search copy,
     * because that is the copy that knows it matches.
     */
    fun merged(
        hashResults: List<OnlineSubtitle>,
        textResults: List<OnlineSubtitle>,
        limit: Int,
    ): List<OnlineSubtitle> {
        val out = ArrayList<OnlineSubtitle>(minOf(limit, hashResults.size + textResults.size))
        val seen = HashSet<Long>()
        for (result in ordered(hashResults) + textResults) {
            if (out.size >= limit) break
            if (seen.add(result.fileId)) out += result
        }
        return out
    }

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
