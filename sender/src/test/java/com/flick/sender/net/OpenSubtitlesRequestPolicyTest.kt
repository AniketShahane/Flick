package com.flick.sender.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Three rules about the HTTP calls the online subtitle path makes that no compiler and no
 * pure function can check, because they are properties of how the client is *configured*
 * and of what is written next to a credential:
 *
 *  1. the client follows no redirect, so nothing can bounce a request — or the header it
 *     carries — to a host this code did not name;
 *  2. the CDN fetch attaches no credential, because that address is chosen by the server;
 *  3. no log line mentions the key, the token or the password, which `SECURITY.md` forbids
 *     outright.
 *
 * So this test reads the real source file, exactly as `BackupExclusionsTest` reads the real
 * backup rules. It is coarse on purpose: it cannot prove behaviour, but it fails loudly the
 * moment somebody deletes the one line that produces it.
 */
class OpenSubtitlesRequestPolicyTest {

    @Test fun theClientFollowsNoRedirectAtAll() {
        val clients = Regex("""HttpClient\(CIO\)""").findAll(source).count()
        val guarded = Regex("""HttpClient\(CIO\)\s*\{\s*followRedirects\s*=\s*false""")
            .findAll(source)
            .count()
        assertTrue("$FILE creates no HTTP client at all — this test is not reading what it thinks", clients > 0)
        assertEquals(
            "every HttpClient in $FILE must be built with `followRedirects = false`. Ktor " +
                "copies request headers across a redirect, so a followed 3xx would send the " +
                "Api-Key or the bearer token to whatever host a Location header names — and " +
                "on the CDN fetch it would send the download itself there.",
            clients,
            guarded,
        )
    }

    @Test fun theCdnFetchCarriesNoCredential() {
        val fetch = functionBody("private suspend fun fetch(")
        for (forbidden in listOf("apiHeaders", "Api-Key", "Authorization", "Bearer")) {
            assertTrue(
                "the CDN fetch in $FILE mentions `$forbidden`. The download link is an " +
                    "address the server chose; a credential must never travel to one.",
                !fetch.contains(forbidden),
            )
        }
    }

    @Test fun onlyOnePlaceInTheFileNamesACredentialHeader() {
        // Both headers live in `apiHeaders` and nowhere else, so there is exactly one
        // place to read to know where a secret can go.
        assertEquals(
            "\"Api-Key\" appears more than once in $FILE; keep every credential header in " +
                "apiHeaders so the CDN fetch cannot grow one by accident.",
            1,
            Regex("\"Api-Key\"").findAll(source).count(),
        )
        assertEquals(
            "HttpHeaders.Authorization appears more than once in $FILE; same reason.",
            1,
            Regex("""HttpHeaders\.Authorization""").findAll(source).count(),
        )
    }

    @Test fun noLogLineCanCarryACredential() {
        val forbidden = listOf("token", "password", "api-key", "apikey", "key.value", "bearer")
        source.lines().filter { it.contains("FlickLog.") }.forEach { line ->
            val lowered = line.lowercase()
            forbidden.forEach { term ->
                assertTrue(
                    "a log call in $FILE mentions `$term`: \"${line.trim()}\". SECURITY.md " +
                        "forbids a credential reaching a log, an exception or a notification.",
                    !lowered.contains(term),
                )
            }
        }
    }

    // --- reading the real file ------------------------------------------------

    /**
     * The text between [signature] and the next declaration at the same indentation. Fails
     * rather than returns empty: a body this test cannot find is a body it cannot check.
     */
    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$FILE no longer declares `$signature`", start >= 0)
        val rest = source.substring(start + signature.length)
        val end = Regex("""\n {4}(private|internal|public|suspend|fun|@|val|companion)""").find(rest)
        return if (end == null) rest else rest.substring(0, end.range.first)
    }

    private val source: String by lazy {
        val file = File(moduleDir, "src/main/java/com/flick/sender/net/$FILE")
        assertTrue("cannot read ${file.absolutePath} — the client is not where this test looks", file.isFile)
        file.readText()
    }

    private val moduleDir: File by lazy {
        val marker = "src/main/java/com/flick/sender/net/$FILE"
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it, File(it, MODULE)) }
            .firstOrNull { File(it, marker).isFile }
            ?: throw AssertionError(
                "cannot locate the $MODULE module from ${File("").absolutePath}; looked for $marker",
            )
    }

    private companion object {
        const val MODULE = "sender"
        const val FILE = "OpenSubtitlesClient.kt"
    }
}
