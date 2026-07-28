package com.flick.sender.net

import android.net.Uri

sealed interface PairLaunchParseResult {
    /**
     * A launch envelope this build understands. [host]/[port] are present from the v3
     * payload onward; they are an UNTRUSTED prefill hint until the user-typed code
     * proves the endpoint.
     *
     * There is deliberately no field here for a pairing code, and there must never be
     * one: this is the shape every externally delivered launch resolves to, and a value
     * with nowhere to put a code cannot carry one in from an Intent. Only
     * [ScannedPairLaunch] holds a code, and only [PairLaunch.parseScanned] builds one.
     */
    data class Valid(val host: String? = null, val port: Int? = null) : PairLaunchParseResult
    data object Invalid : PairLaunchParseResult
    data object UnsupportedVersion : PairLaunchParseResult
}

/**
 * A payload this app's OWN camera read, and the only value in the sender that can carry
 * a pairing code.
 *
 * The camera runs in this process, so the string came off a QR that was physically in
 * front of the user. Nothing else about a launch string proves that: `flick://pair` is a
 * BROWSABLE deep link on an exported activity, so any installed app — needing only
 * INTERNET, and no permission the user is asked about — can bind a server on this
 * phone's own LAN address and fire a URI naming it. The receiver's echoed `peerIp`
 * cannot catch that, because the address such a server reports is one the phone
 * genuinely owns.
 */
data class ScannedPairLaunch(val result: PairLaunchParseResult, val code: String?) {
    /** A value that prints itself eventually prints into a log; the code is a live secret. */
    override fun toString(): String =
        "ScannedPairLaunch(result=$result, code=${if (code == null) "absent" else "held"})"
}

data class IncomingPairEvent(val eventId: Long, val result: PairLaunchParseResult)

/** Parses only the launch envelope. Deliberately never retains a caller supplied URI. */
object PairLaunch {
    /** Receiver default control port. Only ever a prefill hint; never dialed blind. */
    const val DEFAULT_CONTROL_PORT = 47654

    fun parse(uri: Uri?): PairLaunchParseResult = uri?.toString()?.let(::parse) ?: PairLaunchParseResult.Invalid

    /**
     * The ingress for a launch string this app did not read itself: an Intent from any
     * app on the phone, or a browser hand-off.
     *
     * A custom scheme cannot be verified — `autoVerify` needs an https App Link and a
     * signed `assetlinks.json` — so any installed app may register this same filter,
     * appear in the chooser, and fire a URI it wrote itself, and a browser hand-off also
     * leaves the URL in history and account sync. A v4 payload arriving this way is
     * therefore demoted to a v3 one: the endpoint prefills the form and the four digits
     * are dropped here, inside the parser, so nothing downstream is ever offered them.
     * The typed code is the whole out-of-band proof that the address belongs to a TV in
     * the room rather than to a server the calling app just bound on this phone.
     */
    fun parse(raw: String): PairLaunchParseResult = parsePayload(raw).result

    /**
     * The in-app scanner's ingress, and the only one that may keep a v4 code. See
     * [ScannedPairLaunch] for why the camera is the line between the two.
     */
    fun parseScanned(raw: String): ScannedPairLaunch = parsePayload(raw)

    private fun parsePayload(raw: String): ScannedPairLaunch {
        val invalid = ScannedPairLaunch(PairLaunchParseResult.Invalid, null)
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return invalid
        if (uri.isOpaque || !uri.isAbsolute) return invalid
        if (!uri.scheme.equals("flick", ignoreCase = true) || uri.host != "pair") return invalid
        if (uri.userInfo != null || uri.port != -1 || uri.fragment != null || uri.path.orEmpty().isNotEmpty()) {
            return invalid
        }
        val params = uri.rawQuery?.split('&')?.map {
            val separator = it.indexOf('=')
            if (separator < 0) return invalid
            it.substring(0, separator) to it.substring(separator + 1)
        } ?: return invalid
        val names = params.map { it.first }
        // A repeated name would be silently collapsed by toMap(); reject it outright.
        if (names.size != names.toSet().size) return invalid
        val fields = params.toMap()
        val nameSet = names.toSet()
        val version = fields["v"] ?: return invalid
        return when {
            // Legacy launch-only envelope: an un-updated TV must still open the app.
            nameSet == setOf("v") && version == "2" ->
                ScannedPairLaunch(PairLaunchParseResult.Valid(), null)
            nameSet == setOf("v", "h", "p") && version == "3" -> {
                val host = fields.getValue("h")
                val port = fields.getValue("p")
                if (!isCanonicalIpv4(host) || !isCanonicalPort(port)) invalid
                else ScannedPairLaunch(PairLaunchParseResult.Valid(host, port.toInt()), null)
            }
            // The code is held to the same rigour as the endpoint beside it, and a
            // payload wrong in any field is rejected whole rather than salvaged into a
            // prefill: a malformed v4 is not a v3, and guessing which half to believe is
            // how an endpoint nobody checked ends up in the form.
            nameSet == setOf("v", "h", "p", "c") && version == "4" -> {
                val host = fields.getValue("h")
                val port = fields.getValue("p")
                val code = fields.getValue("c")
                if (!isCanonicalIpv4(host) || !isCanonicalPort(port) || !isCode(code)) invalid
                else ScannedPairLaunch(PairLaunchParseResult.Valid(host, port.toInt()), code)
            }
            version == "1" -> ScannedPairLaunch(PairLaunchParseResult.UnsupportedVersion, null)
            else -> invalid
        }
    }

    fun isCanonicalIpv4(value: String): Boolean {
        if (value.isEmpty() || value.any { it !in '0'..'9' && it != '.' }) return false
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || (it.length > 1 && it.startsWith('0')) }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return (first == 10 || first == 192 && second == 168 || first == 172 && second in 16..31)
    }

    fun isCanonicalPort(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' } &&
            !(value.length > 1 && value.startsWith('0')) && (value.toIntOrNull() ?: 0) in 1..65535

    fun isCode(value: String): Boolean = value.length == 4 && value.all { it in '0'..'9' }
}
