package com.flick.sender.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProtocolTest {

    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val tvId = "EREREREREREREREREREREQ"
    private val keyId = "IiIiIiIiIiIiIiIiIiIiIg"
    private val clientNonce = "MzMzMzMzMzMzMzMzMzMzMw"
    private val serverNonce = "RERERERERERERERERERERA"

    @Test
    fun proofsMatchTheContractFixtures() {
        assertEquals(
            "G9mhvmEtExjWSAoOuss3RU3YbAMLeM4-qrYvtUjebGg",
            LibraryProtocol.proof(key, "client", tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421),
        )
        assertEquals(
            "Dl-vmcgpfnTbgSgDKv6NNRa-OvjJKl4JWCh7CUg8j1Q",
            LibraryProtocol.proof(key, "server", tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421),
        )
    }

    @Test
    fun itemIdMatchesTheContractFixture() {
        assertEquals("nTbULXb50u0", LibraryProtocol.itemId("A".repeat(43)))
    }

    @Test
    fun nonceIs22Base64UrlCharacters() {
        assertTrue(LibraryProtocol.nonce(LibraryProtocol.randomNonce()))
    }

    @Test
    fun anEpisodeWithEveryFieldSerializesInContractOrder() {
        val json = CatalogRules.json(episode("nTbULXb50u0", "The.Lantern.Coast.S02E04.2160p.mkv", season = 2, episode = 4))
        assertEquals(
            "{\"id\":\"nTbULXb50u0\",\"name\":\"The.Lantern.Coast.S02E04.2160p.mkv\",\"title\":\"The Lantern Coast S02E04\"," +
                "\"sortKey\":\"the lantern coast s02e04\",\"find\":\"thelanterncoasts02e042160pmkv\",\"kind\":\"episode\"," +
                "\"show\":\"The Lantern Coast\",\"showKey\":\"tv:91234\",\"s\":2,\"e\":4,\"year\":2022,\"durMs\":3130000," +
                "\"added\":1795000000,\"folder\":\"Movies/Shows/Lantern Coast/\",\"w\":3840,\"h\":2160,\"hdr\":\"dv\"," +
                "\"resumeMs\":1260000,\"art\":{\"tmdb\":91234,\"tv\":true,\"poster\":\"/a.jpg\",\"backdrop\":\"/b.jpg\",\"logo\":\"/c.png\"}}",
            json,
        )
    }

    @Test
    fun theTimestampsFollowTheFieldsTheyDate() {
        val item = episode("nTbULXb50u0", "x.mkv", season = 2, episode = 4).copy(
            playedAtSeconds = 1_800_000_000L, watched = true, watchedAtSeconds = 1_790_000_000L, art = null,
        )
        assertTrue(
            CatalogRules.json(item)!!.endsWith(
                "\"hdr\":\"dv\",\"resumeMs\":1260000,\"playedAt\":1800000000,\"watched\":true,\"watchedAt\":1790000000}",
            ),
        )
    }

    @Test
    fun aTimestampWithoutItsFieldOrOutOfRangeIsOmitted() {
        val base = episode("nTbULXb50u0", "x.mkv", season = 2, episode = 4).copy(art = null)
        val orphaned = CatalogRules.json(base.copy(resumeMs = null, playedAtSeconds = 1_800_000_000L, watchedAtSeconds = 1_790_000_000L))!!
        assertFalse(orphaned.contains("playedAt"))
        assertFalse(orphaned.contains("watchedAt"))
        listOf(0L, -1L, 4_102_444_801L).forEach { bad ->
            val json = CatalogRules.json(base.copy(playedAtSeconds = bad, watched = true, watchedAtSeconds = bad))!!
            assertFalse(json.contains("playedAt"))
            assertFalse(json.contains("watchedAt"))
            assertTrue(json.contains("\"watched\":true"))
        }
        val edges = CatalogRules.json(base.copy(playedAtSeconds = 1L, watched = true, watchedAtSeconds = 4_102_444_800L))!!
        assertTrue(edges.contains("\"playedAt\":1,"))
        assertTrue(edges.contains("\"watchedAt\":4102444800"))
    }

    @Test
    fun anEpisodeMissingItsShowIsSentAsAVideo() {
        val json = CatalogRules.json(episode("nTbULXb50u0", "x.mkv", season = 2, episode = 4).copy(showKey = null))!!
        assertTrue(json.contains("\"kind\":\"video\""))
        assertFalse(json.contains("\"s\":"))
    }

    @Test
    fun anOutOfRangeEpisodeNumberIsSentAsAVideo() {
        val json = CatalogRules.json(episode("nTbULXb50u0", "x.mkv", season = 1000, episode = 4))!!
        assertTrue(json.contains("\"kind\":\"video\""))
    }

    @Test
    fun labelsLoseControlAndFormatCharactersAndCollapseWhitespace() {
        assertEquals("a b", CatalogRules.label("  a\u0007​ \t  b  ", 255))
        assertNull(CatalogRules.label(" \u0000 ", 255))
        assertEquals("abc", CatalogRules.label("abcdef", 3))
        assertEquals("ab", CatalogRules.label("ab cd", 3))
    }

    @Test
    fun anItemWithNoNameOrABadIdIsNotSent() {
        assertNull(CatalogRules.json(film("short", "x.mkv")))
        assertNull(CatalogRules.json(film("nTbULXb50u0", "\u0001")))
    }

    @Test
    fun artPathsOutsideTheTmdbShapeAreDropped() {
        val art = CatalogItem.Art(7L, false, "/ok.jpg", "https://evil/x.jpg", "/../x.png")
        val json = CatalogRules.json(film("nTbULXb50u0", "x.mkv").copy(art = art))!!
        assertTrue(json.contains("\"art\":{\"tmdb\":7,\"tv\":false,\"poster\":\"/ok.jpg\"}"))
        val none = CatalogRules.json(film("nTbULXb50u0", "x.mkv").copy(art = CatalogItem.Art(7L, false, null, "bad", null)))!!
        assertFalse(none.contains("\"art\""))
    }

    @Test
    fun aNameShowKeyIsCutToTheContractLength() {
        val key = CatalogRules.showKey("name:" + "x".repeat(300))!!
        assertEquals(205, key.length)
        assertNull(CatalogRules.showKey("tv:0"))
        assertNull(CatalogRules.showKey("other"))
    }

    @Test
    fun everyPartFitsInOneFrameAndCarriesEveryItemInOrder() {
        val items = (0 until 1_077).map { index -> film(LibraryProtocol.itemId("f$index"), "Film number $index (2001) 2160p.mkv", find = "x".repeat(400)) }
        val catalog = EncodedCatalog.of(items)
        val frames = LibraryFrames.full(catalog)
        assertTrue(frames.all { LibraryProtocol.utf8Size(it) <= LibraryProtocol.MAX_FRAME_BYTES })
        val begin = LibraryJsonReader.objectOnly(frames.first())!!
        assertEquals("catBegin", begin.str("t"))
        assertEquals(1_077L, begin.long("count"))
        assertEquals((frames.size - 2).toLong(), begin.long("parts"))
        val ids = frames.drop(1).dropLast(1).flatMapIndexed { index, frame ->
            val part = LibraryJsonReader.objectOnly(frame)!!
            assertEquals(index.toLong(), part.long("index"))
            assertEquals(catalog.rev, part.str("rev"))
            (part.fields["items"] as LibraryJsonValue.Arr).values.map { (it as LibraryJsonValue.Obj).str("id")!! }
        }
        assertEquals(catalog.ids, ids)
        assertTrue(frames.size > 3)
    }

    @Test
    fun anEmptyCatalogIsBeginAndEndWithNoParts() {
        val frames = LibraryFrames.full(EncodedCatalog.of(emptyList()))
        assertEquals(2, frames.size)
        assertTrue(frames[0].contains("\"count\":0,\"parts\":0"))
    }

    @Test
    fun duplicateIdsAreDroppedAndCounted() {
        val catalog = EncodedCatalog.of(listOf(film("nTbULXb50u0", "a.mkv"), film("nTbULXb50u0", "b.mkv")))
        assertEquals(1, catalog.size)
        assertEquals(1, catalog.dropped)
    }

    @Test
    fun revChangesWithAnyFieldAndOrder() {
        val a = film("nTbULXb50u0", "a.mkv")
        val b = film("AAAAAAAAAAA", "b.mkv")
        val one = EncodedCatalog.of(listOf(a, b))
        assertEquals(22, one.rev.length)
        assertFalse(one.rev == EncodedCatalog.of(listOf(b, a)).rev)
        assertFalse(one.rev == EncodedCatalog.of(listOf(a.copy(watched = true), b)).rev)
        assertEquals(one.rev, EncodedCatalog.of(listOf(a, b)).rev)
    }

    @Test
    fun aPatchCarriesChangesAndRemovalsWhenTheOrderSurvivesIt() {
        val a = film("aaaaaaaaaaa", "a.mkv")
        val b = film("bbbbbbbbbbb", "b.mkv")
        val c = film("ccccccccccc", "c.mkv")
        val base = EncodedCatalog.of(listOf(a, b))
        val next = EncodedCatalog.of(listOf(c, a.copy(watched = true)))
        val patch = LibraryJsonReader.objectOnly(LibraryFrames.patch(base, next)!!)!!
        assertEquals(base.rev, patch.str("base"))
        assertEquals(next.rev, patch.str("rev"))
        val upsert = (patch.fields["upsert"] as LibraryJsonValue.Arr).values.map { (it as LibraryJsonValue.Obj).str("id") }
        assertEquals(listOf("ccccccccccc", "aaaaaaaaaaa"), upsert)
        val remove = (patch.fields["remove"] as LibraryJsonValue.Arr).values.map { (it as LibraryJsonValue.Str).value }
        assertEquals(listOf("bbbbbbbbbbb"), remove)
    }

    @Test
    fun aPatchIsRefusedWhenTheTvWouldReorder() {
        val a = film("aaaaaaaaaaa", "a.mkv")
        val b = film("bbbbbbbbbbb", "b.mkv")
        val c = film("ccccccccccc", "c.mkv")
        assertNull(LibraryFrames.patch(EncodedCatalog.of(listOf(a, b)), EncodedCatalog.of(listOf(a, c, b))))
        assertNull(LibraryFrames.patch(EncodedCatalog.of(listOf(a, b)), EncodedCatalog.of(listOf(b, a))))
    }

    @Test
    fun aPatchIsRefusedPastSixtyFourChanges() {
        val base = EncodedCatalog.of((0 until 100).map { film(LibraryProtocol.itemId("x$it"), "f$it.mkv") })
        val next = EncodedCatalog.of((0 until 100).map { film(LibraryProtocol.itemId("x$it"), "f$it.mkv", watched = it < 65) })
        assertNull(LibraryFrames.patch(base, next))
        val few = EncodedCatalog.of((0 until 100).map { film(LibraryProtocol.itemId("x$it"), "f$it.mkv", watched = it < 64) })
        assertNotNull(LibraryFrames.patch(base, few))
    }

    @Test
    fun seasonFramesSplitUnderTheCap() {
        val episodes = (1..60).map { LibraryFrames.EpisodeInfo(it, "Episode $it", "o".repeat(400), "/s$it.jpg") }
        val frames = LibraryFrames.season("tv:91234", 2, episodes)
        assertTrue(frames.size > 1)
        assertTrue(frames.all { LibraryProtocol.utf8Size(it) <= LibraryProtocol.MAX_FRAME_BYTES })
        val last = LibraryJsonReader.objectOnly(frames.last())!!
        assertEquals((frames.size - 1).toLong(), last.long("part"))
        assertEquals(frames.size.toLong(), last.long("parts"))
        assertEquals(1, LibraryFrames.season("tv:1", 1, emptyList()).size)
    }

    @Test
    fun thumbnailFramesCarryMagicAndIdAndRefuseOversize() {
        val frame = LibraryFrames.thumb("nTbULXb50u0", byteArrayOf(1, 2, 3))!!
        assertEquals("FLT1nTbULXb50u0", String(frame, 0, 15, Charsets.US_ASCII))
        assertEquals(18, frame.size)
        assertNull(LibraryFrames.thumb("nTbULXb50u0", ByteArray(LibraryFrames.THUMB_MAX_JPEG_BYTES + 1)))
        assertNotNull(LibraryFrames.thumb("nTbULXb50u0", ByteArray(LibraryFrames.THUMB_MAX_JPEG_BYTES)))
    }

    @Test
    fun tvFramesParseStrictly() {
        assertEquals(TvFrame.Play("MzMzMzMzMzMzMzMzMzMzMw", "nTbULXb50u0", true),
            TvFrame.parse("{\"t\":\"play\",\"v\":1,\"req\":\"MzMzMzMzMzMzMzMzMzMzMw\",\"id\":\"nTbULXb50u0\",\"from\":\"start\"}"))
        assertEquals(TvFrame.Malformed,
            TvFrame.parse("{\"t\":\"play\",\"v\":1,\"req\":\"MzMzMzMzMzMzMzMzMzMzMw\",\"id\":\"nTbULXb50u0\",\"from\":\"later\"}"))
        assertEquals(TvFrame.CatWant(null), TvFrame.parse("{\"t\":\"catWant\",\"v\":1}"))
        assertEquals(TvFrame.Malformed, TvFrame.parse("{\"t\":\"catWant\",\"v\":1,\"have\":7}"))
        assertEquals(TvFrame.Unknown("later"), TvFrame.parse("{\"t\":\"later\",\"v\":1,\"x\":[1,2]}"))
        assertEquals(TvFrame.Unknown("later"), TvFrame.parse("{\"t\":\"later\",\"v\":2}"))
        assertEquals(TvFrame.Malformed, TvFrame.parse("{\"t\":\"browsing\",\"v\":2}"))
        assertEquals(TvFrame.Malformed, TvFrame.parse("{\"t\":\"browsing\",\"v\":1,\"t\":\"refresh\"}"))
        assertEquals(TvFrame.Malformed, TvFrame.parse("{\"t\":\"browsing\",\"v\":1.0}"))
        assertEquals(TvFrame.Malformed, TvFrame.parse("[]"))
        assertEquals(TvFrame.Browsing, TvFrame.parse("{\"t\":\"browsing\",\"v\":1}"))
        assertEquals(TvFrame.SeasonWant("name:the show", 3), TvFrame.parse("{\"t\":\"seasonWant\",\"v\":1,\"showKey\":\"name:the show\",\"s\":3}"))
    }

    @Test
    fun aChallengeNeedsCanonicalAddressesAndAPort() {
        val good = "{\"t\":\"libChallenge\",\"v\":1,\"tvId\":\"$tvId\",\"keyId\":\"$keyId\",\"clientNonce\":\"$clientNonce\"," +
            "\"serverNonce\":\"$serverNonce\",\"peerIp\":\"192.168.1.20\",\"serverHost\":\"192.168.1.30\",\"serverPort\":42421}"
        assertEquals(TvFrame.Challenge(tvId, keyId, clientNonce, serverNonce, "192.168.1.20", "192.168.1.30", 42421), TvFrame.parse(good))
        assertEquals(TvFrame.Malformed, TvFrame.parse(good.replace("192.168.1.30", "192.168.01.30")))
        assertEquals(TvFrame.Malformed, TvFrame.parse(good.replace("42421", "70000")))
    }

    @Test
    fun builtFramesAreStrictJson() {
        listOf(
            LibraryFrames.hello(tvId, keyId, clientNonce),
            LibraryFrames.proof(clientNonce, serverNonce, "G9mhvmEtExjWSAoOuss3RU3YbAMLeM4-qrYvtUjebGg"),
            LibraryFrames.playAck(serverNonce, true),
            LibraryFrames.playAck(serverNonce, false, PlayRefusal.BUSY),
            LibraryFrames.casting(serverNonce, "nTbULXb50u0"),
            LibraryFrames.detail("nTbULXb50u0", "Quote \" and \\ and \n", null, CatalogItem.Hdr.HDR10, "English"),
            LibraryFrames.thumbMiss("nTbULXb50u0"),
        ).forEach { assertNotNull(it, LibraryJsonReader.objectOnly(it)) }
        assertTrue(LibraryFrames.playAck(serverNonce, false, PlayRefusal.BUSY).endsWith("\"ok\":false,\"code\":\"busy\"}"))
    }

    private fun film(id: String, name: String, find: String = "x", watched: Boolean = false) = CatalogItem(
        id = id, name = name, title = name, sortKey = name.lowercase(), find = find, kind = CatalogItem.Kind.FILM,
        durationMs = 6_000_000L, addedSeconds = 1_700_000_000L, watched = watched,
    )

    private fun episode(id: String, name: String, season: Int, episode: Int) = CatalogItem(
        id = id, name = name, title = "The Lantern Coast S02E04", sortKey = "the lantern coast s02e04",
        find = "thelanterncoasts02e042160pmkv", kind = CatalogItem.Kind.EPISODE, show = "The Lantern Coast",
        showKey = "tv:91234", season = season, episode = episode, year = 2022, durationMs = 3_130_000L,
        addedSeconds = 1_795_000_000L, folder = "Movies/Shows/Lantern Coast/", width = 3840, height = 2160,
        hdr = CatalogItem.Hdr.DOLBY_VISION, resumeMs = 1_260_000L,
        art = CatalogItem.Art(91234L, true, "/a.jpg", "/b.jpg", "/c.png"),
    )
}
