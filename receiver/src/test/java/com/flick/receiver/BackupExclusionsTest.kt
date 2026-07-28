package com.flick.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup exclusions and the code that writes those files are coupled by
 * nothing but two identical string literals sitting in different languages: the
 * prefs name lives in Kotlin, the exclusion path lives in XML, and the build has
 * never had an opinion about whether they agree. A single typo in either — or a
 * new prefs file nobody thought about — silently starts shipping every paired
 * phone's 256-bit key to Google Drive, with no error anywhere and nothing on
 * screen to notice.
 *
 * This test is that opinion. It parses the real `res/xml` files out of the source
 * tree (not a copy, not a fixture) and it also reads every `getSharedPreferences`
 * call in `src/main`, so neither half can move without the other.
 *
 * Adding a prefs file is meant to fail this test. Classify it below — as a
 * credential store or as something that may legitimately ride along — and say why.
 */
class BackupExclusionsTest {

    @Test fun everySecretBearingPrefsFileIsExcludedFromCloudBackup() {
        val excluded = sharedPrefExcludes(fullBackupSection(xml("backup_rules.xml")))
        assertExcluded(SECRET_BEARING, excluded, "backup_rules.xml (cloud backup)")
    }

    @Test fun everySecretBearingPrefsFileIsExcludedFromBothTransferPaths() {
        val root = xml("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val excluded = sharedPrefExcludes(childSection(root, section))
            assertExcluded(SECRET_BEARING, excluded, "data_extraction_rules.xml <$section>")
        }
    }

    /**
     * The Kotlin half. A prefs name that no longer matches its exclusion path is a
     * leak that the XML alone cannot show, because the XML would still look right.
     */
    @Test fun everyPrefsFileInTheSourcesIsClassified() {
        val found = prefsNamesInSources()
        assertTrue(
            "found no getSharedPreferences call at all in $MODULE/src/main — this test " +
                "is not reading the sources it thinks it is",
            found.isNotEmpty(),
        )
        val known = SECRET_BEARING + CARRIED_DELIBERATELY.keys
        val unclassified = found - known
        assertEquals(
            "unclassified SharedPreferences file(s) in $MODULE: $unclassified. Decide whether " +
                "each one holds a credential. If it does, add it to SECRET_BEARING and exclude " +
                "it in backup_rules.xml AND both sections of data_extraction_rules.xml; if it " +
                "does not, add it to CARRIED_DELIBERATELY with the reason.",
            emptySet<String>(),
            unclassified,
        )
        val vanished = SECRET_BEARING - found
        assertEquals(
            "SECRET_BEARING names $vanished, which no getSharedPreferences call in $MODULE " +
                "opens any more. Either the store was renamed — in which case the exclusion " +
                "paths in res/xml now protect nothing — or it is gone and this list is stale.",
            emptySet<String>(),
            vanished,
        )
    }

    // --- Assertions ---------------------------------------------------------

    private fun assertExcluded(required: Set<String>, excluded: Set<String>, where: String) {
        val missing = required.map { "$it.xml" }.filterNot { it in excluded }
        if (missing.isNotEmpty()) {
            fail(
                "$where does not exclude $missing. That file holds the 256-bit pairing key of " +
                    "every phone this TV admits; without the exclusion it is uploaded verbatim. " +
                    "Present exclusions were: ${excluded.sorted()}",
            )
        }
    }

    // --- Reading the real files ---------------------------------------------

    private fun xml(name: String): Element {
        val file = File(moduleDir, "src/main/res/xml/$name")
        if (!file.isFile) fail("cannot read ${file.absolutePath} — the backup rules are not where this test looks")
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    }

    private fun fullBackupSection(root: Element): Element {
        assertEquals("backup_rules.xml root element", "full-backup-content", root.tagName)
        return root
    }

    private fun childSection(root: Element, tag: String): Element {
        assertEquals("data_extraction_rules.xml root element", "data-extraction-rules", root.tagName)
        val nodes = root.getElementsByTagName(tag)
        if (nodes.length != 1) {
            fail("data_extraction_rules.xml must carry exactly one <$tag>; found ${nodes.length}")
        }
        return nodes.item(0) as Element
    }

    /** Every `<exclude domain="sharedpref">` path under [section]. */
    private fun sharedPrefExcludes(section: Element): Set<String> {
        val nodes = section.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "sharedpref" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    /**
     * Every prefs name opened anywhere in `src/main`, resolving the constant when
     * the call site passes one rather than a literal — which both of this module's
     * stores do. An unresolvable call site fails rather than being skipped: a store
     * this test cannot see is a store it cannot protect.
     */
    private fun prefsNamesInSources(): Set<String> {
        val names = mutableSetOf<String>()
        File(moduleDir, "src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            CALL_SITE.findAll(text).forEach { match ->
                val literal = match.groupValues[1]
                val identifier = match.groupValues[2]
                when {
                    literal.isNotEmpty() -> names += literal
                    else -> {
                        val declared = Regex("\\bconst\\s+val\\s+$identifier\\s*=\\s*\"([^\"]*)\"").find(text)
                        if (declared == null) {
                            fail(
                                "${file.name} opens SharedPreferences with `$identifier`, whose value " +
                                    "this test cannot resolve. Inline the name or declare it as a " +
                                    "`const val` in the same file — an unreadable prefs name cannot " +
                                    "be checked against the backup exclusions.",
                            )
                        } else {
                            names += declared.groupValues[1]
                        }
                    }
                }
            }
        }
        return names
    }

    private val moduleDir: File by lazy {
        val marker = "src/main/res/xml/backup_rules.xml"
        generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(it, File(it, MODULE)) }
            .firstOrNull { File(it, marker).isFile }
            ?: throw AssertionError(
                "cannot locate the $MODULE module from ${File("").absolutePath}; looked for $marker",
            )
    }

    private companion object {
        const val MODULE = "receiver"

        /**
         * Prefs files that hold a credential. Every one of these must be excluded
         * from cloud backup AND device transfer.
         *
         * `flick_pairing` holds one 256-bit key per paired phone — the entire
         * authorization story for this TV. A restore onto a second TV would hand
         * that TV the right to be driven by phones that never paired with it.
         */
        val SECRET_BEARING = setOf("flick_pairing")

        /**
         * Prefs files that carry no credential, with the reason each is allowed to
         * ride along.
         */
        val CARRIED_DELIBERATELY = mapOf(
            "flick_control" to
                "the last control-server port number. It authorizes nothing — the port is " +
                    "advertised over NSD anyway, and a connection to it still has to pair or " +
                    "prove a key",
        )

        /** `getSharedPreferences("name"` or `getSharedPreferences(CONSTANT`. */
        val CALL_SITE = Regex("""getSharedPreferences\(\s*(?:"([^"]*)"|([A-Za-z_][A-Za-z0-9_]*))""")
    }
}
