package com.flick.receiver.library

import com.flick.receiver.net.StrictJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFramesTest {

    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val tvId = "EREREREREREREREREREREQ"
    private val keyId = "IiIiIiIiIiIiIiIiIiIiIg"
    private val clientNonce = "MzMzMzMzMzMzMzMzMzMzMw"
    private val serverNonce = "RERERERERERERERERERERA"
    private val rev = "AAAAAAAAAAAAAAAAAAAAAA"
    private val rev2 = "BBBBBBBBBBBBBBBBBBBBBB"

    private val exampleItem =
        "{\"id\":\"nTbULXb50u0\",\"name\":\"The.Lantern.Coast.S02E04.2160p.mkv\",\"title\":\"The Lantern Coast S02E04\"," +
            "\"sortKey\":\"the lantern coast s02e04\",\"find\":\"thelanterncoasts02e042160pmkv\",\"kind\":\"episode\"," +
            "\"show\":\"The Lantern Coast\",\"showKey\":\"tv:91234\",\"s\":2,\"e\":4,\"year\":2022,\"durMs\":3130000," +
            "\"added\":1795000000,\"folder\":\"Movies/Shows/Lantern Coast/\",\"w\":3840,\"h\":2160,\"hdr\":\"dv\"," +
            "\"resumeMs\":1260000,\"art\":{\"tmdb\":91234,\"tv\":true,\"poster\":\"/a.jpg\",\"backdrop\":\"/b.jpg\",\"logo\":\"/c.png\"}}"

    @Test
    fun proofsMatchTheContractFixtures() {
        assertEquals(
            "G9mhvmEtExjWSAoOuss3RU3YbAMLeM4-qrYvtUjebGg",
            LibraryAuth.proof(key, "client", tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421),
        )
        assertEquals(
            "Dl-vmcgpfnTbgSgDKv6NNRa-OvjJKl4JWCh7CUg8j1Q",
            LibraryAuth.proof(key, "server", tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421),
        )
    }

    @Test
    fun theContractExampleItemParses() {
        val part = PhoneFrame.parse("{\"t\":\"catPart\",\"v\":1,\"rev\":\"$rev\",\"index\":0,\"items\":[$exampleItem]}") as PhoneFrame.CatPart
        val item = part.items.single()
        assertEquals("nTbULXb50u0", item.id)
        assertEquals(LibraryItemKind.EPISODE, item.kind)
        assertEquals("tv:91234", item.showKey)
        assertEquals(2, item.season)
        assertEquals(4, item.episode)
        assertEquals(LibraryHdr.DOLBY_VISION, item.hdr)
        assertEquals(1_260_000L, item.resumeMs)
        assertEquals(LibraryArt(91234L, true, "/a.jpg", "/b.jpg", "/c.png"), item.art)
        assertTrue(item.isEpisode)
    }

    @Test
    fun unknownItemFieldsAreIgnoredButOneBadItemFailsThePart() {
        val extra = exampleItem.dropLast(1) + ",\"later\":{\"x\":1}}"
        assertTrue(PhoneFrame.parse("{\"t\":\"catPart\",\"v\":1,\"rev\":\"$rev\",\"index\":0,\"items\":[$extra]}") is PhoneFrame.CatPart)
        val bad = exampleItem.replace("\"durMs\":3130000", "\"durMs\":-1")
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"catPart\",\"v\":1,\"rev\":\"$rev\",\"index\":0,\"items\":[$exampleItem,$bad]}"))
    }

    @Test
    fun artNeedsAtLeastOneValidPath() {
        val noPaths = exampleItem.replace("\"art\":{\"tmdb\":91234,\"tv\":true,\"poster\":\"/a.jpg\",\"backdrop\":\"/b.jpg\",\"logo\":\"/c.png\"}", "\"art\":{\"tmdb\":1}")
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"catPart\",\"v\":1,\"rev\":\"$rev\",\"index\":0,\"items\":[$noPaths]}"))
        val pathOnly = exampleItem.replace("\"art\":{\"tmdb\":91234,\"tv\":true,\"poster\":\"/a.jpg\",\"backdrop\":\"/b.jpg\",\"logo\":\"/c.png\"}", "\"art\":{\"poster\":\"/a.webp\"}")
        val part = PhoneFrame.parse("{\"t\":\"catPart\",\"v\":1,\"rev\":\"$rev\",\"index\":0,\"items\":[$pathOnly]}") as PhoneFrame.CatPart
        assertEquals(LibraryArt(null, null, "/a.webp", null, null), part.items.single().art)
    }

    @Test
    fun unknownTypesAreIgnoredAndKnownTypesAreStrict() {
        assertEquals(PhoneFrame.Unknown("later"), PhoneFrame.parse("{\"t\":\"later\",\"v\":1}"))
        assertEquals(PhoneFrame.Unknown("later"), PhoneFrame.parse("{\"t\":\"later\",\"v\":9}"))
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"catEnd\",\"v\":2,\"rev\":\"$rev\"}"))
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"catEnd\",\"v\":1,\"rev\":\"short\"}"))
        assertEquals(PhoneFrame.CatEnd(rev), PhoneFrame.parse("{\"t\":\"catEnd\",\"v\":1,\"rev\":\"$rev\"}"))
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"catEnd\",\"v\":1,\"rev\":\"$rev\",\"rev\":\"$rev\"}"))
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("not json"))
    }

    @Test
    fun playAcksCarryARefusalAndUnknownCodesReadAsUnavailable() {
        assertEquals(PhoneFrame.PlayAck(serverNonce, true, null), PhoneFrame.parse("{\"t\":\"playAck\",\"v\":1,\"req\":\"$serverNonce\",\"ok\":true}"))
        assertEquals(PhoneFrame.PlayAck(serverNonce, false, PlayRefusal.BUSY), PhoneFrame.parse("{\"t\":\"playAck\",\"v\":1,\"req\":\"$serverNonce\",\"ok\":false,\"code\":\"busy\"}"))
        assertEquals(PhoneFrame.PlayAck(serverNonce, false, PlayRefusal.UNAVAILABLE), PhoneFrame.parse("{\"t\":\"playAck\",\"v\":1,\"req\":\"$serverNonce\",\"ok\":false,\"code\":\"new\"}"))
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"playAck\",\"v\":1,\"req\":\"$serverNonce\",\"ok\":false}"))
    }

    @Test
    fun seasonAndDetailAndCastingParse() {
        val season = PhoneFrame.parse(
            "{\"t\":\"season\",\"v\":1,\"showKey\":\"tv:91234\",\"s\":2,\"part\":0,\"parts\":1,\"episodes\":[{\"e\":4,\"name\":\"The Quiet Tide\",\"overview\":\"o\",\"still\":\"/d.jpg\"}]}",
        )
        assertEquals(PhoneFrame.Season("tv:91234", 2, 0, 1, listOf(LibraryEpisodeInfo(4, "The Quiet Tide", "o", "/d.jpg"))), season)
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"season\",\"v\":1,\"showKey\":\"tv:1\",\"s\":2,\"part\":1,\"parts\":1,\"episodes\":[]}"))
        // A long season may need more parts than a short one; the cap is the catalog's.
        assertEquals(PhoneFrame.Season("tv:1", 2, 64, 65, emptyList()), PhoneFrame.parse("{\"t\":\"season\",\"v\":1,\"showKey\":\"tv:1\",\"s\":2,\"part\":64,\"parts\":65,\"episodes\":[]}"))
        assertEquals(PhoneFrame.Season("tv:1", 2, 3999, 4000, emptyList()), PhoneFrame.parse("{\"t\":\"season\",\"v\":1,\"showKey\":\"tv:1\",\"s\":2,\"part\":3999,\"parts\":4000,\"episodes\":[]}"))
        assertEquals(PhoneFrame.Malformed, PhoneFrame.parse("{\"t\":\"season\",\"v\":1,\"showKey\":\"tv:1\",\"s\":2,\"part\":0,\"parts\":4001,\"episodes\":[]}"))
        assertEquals(
            PhoneFrame.Detail("nTbULXb50u0", "Over", null, LibraryHdr.HDR10, "English"),
            PhoneFrame.parse("{\"t\":\"detail\",\"v\":1,\"id\":\"nTbULXb50u0\",\"overview\":\"Over\",\"hdr\":\"hdr10\",\"subtitle\":\"English\"}"),
        )
        assertEquals(PhoneFrame.Casting(serverNonce, "nTbULXb50u0"), PhoneFrame.parse("{\"t\":\"casting\",\"v\":1,\"castId\":\"$serverNonce\",\"id\":\"nTbULXb50u0\"}"))
    }

    @Test
    fun thumbnailsNeedMagicAndAnId() {
        val jpeg = byteArrayOf(-1, -40, -1)
        val (id, bytes) = PhoneFrame.thumbnail("FLT1nTbULXb50u0".toByteArray(Charsets.US_ASCII) + jpeg)!!
        assertEquals("nTbULXb50u0", id)
        assertTrue(bytes.contentEquals(jpeg))
        assertNull(PhoneFrame.thumbnail("FLT2nTbULXb50u0".toByteArray(Charsets.US_ASCII) + jpeg))
        assertNull(PhoneFrame.thumbnail("FLT1nTbULXb50u0".toByteArray(Charsets.US_ASCII)))
        assertNull(PhoneFrame.thumbnail(ByteArray(LibraryFrames.MAX_FRAME_BYTES + 1)))
    }

    @Test
    fun builtFramesAreStrictJsonWithContractFields() {
        val frames = listOf(
            LibraryFrames.challenge(tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421),
            LibraryFrames.ready(serverNonce, "Dl-vmcgpfnTbgSgDKv6NNRa-OvjJKl4JWCh7CUg8j1Q"),
            LibraryFrames.denied(),
            LibraryFrames.catWant(rev),
            LibraryFrames.catWant(null),
            LibraryFrames.play(serverNonce, "nTbULXb50u0", PlayFrom.RESUME),
            LibraryFrames.detailWant("nTbULXb50u0"),
            LibraryFrames.seasonWant("name:a \"quoted\" show", 1),
            LibraryFrames.thumbWant("nTbULXb50u0"),
            LibraryFrames.refresh(),
            LibraryFrames.browsing(),
        )
        frames.forEach { assertNotNull(it, StrictJson.objectOnly(it)) }
        assertEquals("{\"t\":\"catWant\",\"v\":1}", LibraryFrames.catWant(null))
        assertEquals(
            "{\"t\":\"play\",\"v\":1,\"req\":\"$serverNonce\",\"id\":\"nTbULXb50u0\",\"from\":\"resume\"}",
            LibraryFrames.play(serverNonce, "nTbULXb50u0", PlayFrom.RESUME),
        )
    }

    @Test
    fun theAssemblerSwapsOnlyACompleteMatchingCatalog() {
        val assembler = CatalogAssembler()
        val a = item("aaaaaaaaaaa")
        val b = item("bbbbbbbbbbb")
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.begin(rev, 2, 2))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.part(rev, 0, listOf(a)))
        assertNull(assembler.current)
        assertEquals(CatalogAssembler.Outcome.Violation, assembler.end(rev))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.begin(rev, 2, 2))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.part(rev, 0, listOf(a)))
        assertEquals(CatalogAssembler.Outcome.Violation, assembler.part(rev, 0, listOf(b)))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.begin(rev, 2, 2))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.part(rev, 0, listOf(a)))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.part(rev, 1, listOf(b)))
        val swapped = assembler.end(rev) as CatalogAssembler.Outcome.Swapped
        assertEquals(listOf(a, b), swapped.catalog.items)
        assertEquals(a, swapped.catalog["aaaaaaaaaaa"])
    }

    @Test
    fun theAssemblerRefusesOverLimitsAndDuplicates() {
        val assembler = CatalogAssembler()
        assertEquals(CatalogAssembler.Outcome.Violation, assembler.begin(rev, LibraryWire.MAX_ITEMS + 1, 1))
        assertEquals(CatalogAssembler.Outcome.Violation, assembler.begin(rev, 1, LibraryWire.MAX_PARTS + 1))
        assertEquals(CatalogAssembler.Outcome.Violation, assembler.begin(rev, 1, 0))
        assertEquals(CatalogAssembler.Outcome.Pending, assembler.begin(rev, 2, 1))
        assertEquals(CatalogAssembler.Outcome.Violation, assembler.part(rev, 0, listOf(item("aaaaaaaaaaa"), item("aaaaaaaaaaa"))))
    }

    @Test
    fun aPatchPrependsNewItemsAndNeedsItsBase() {
        val assembler = CatalogAssembler()
        val a = item("aaaaaaaaaaa")
        val b = item("bbbbbbbbbbb")
        val c = item("ccccccccccc")
        assertEquals(CatalogAssembler.Outcome.NeedFull, assembler.patch(rev, rev2, listOf(c), emptyList()))
        assembler.begin(rev, 2, 1)
        assembler.part(rev, 0, listOf(a, b))
        assembler.end(rev)
        assertEquals(CatalogAssembler.Outcome.NeedFull, assembler.patch(rev2, rev, listOf(c), emptyList()))
        val watchedA = a.copy(watched = true)
        val swapped = assembler.patch(rev, rev2, listOf(c, watchedA), listOf("bbbbbbbbbbb")) as CatalogAssembler.Outcome.Swapped
        assertEquals(listOf(c, watchedA), swapped.catalog.items)
        assertEquals(rev2, swapped.catalog.rev)
    }

    private fun item(id: String) = LibraryItem(
        id = id, name = "$id.mkv", title = id, sortKey = id, find = id, kind = LibraryItemKind.VIDEO,
    )
}
