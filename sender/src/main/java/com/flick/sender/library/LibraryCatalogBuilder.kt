package com.flick.sender.library

import com.flick.sender.media.FilmArt
import com.flick.sender.media.FilmPosterPolicy
import com.flick.sender.media.FilmPosterQuery
import com.flick.sender.media.FoldedText
import com.flick.sender.media.ParsedVideoName
import com.flick.sender.media.PlaybackCheckpoint
import com.flick.sender.media.VideoEdition
import com.flick.sender.media.VideoNames
import com.flick.sender.media.librarySortTitle
import com.flick.sender.ui.screens.LibrarySearchPolicy

/**
 * One library row as plain values. `MediaItem` carries an Android `Uri`, so the mapping below
 * is written over this instead and stays provable on a JVM.
 */
internal data class CatalogSourceRow(
    val fingerprint: String,
    val uriKey: String,
    val name: String,
    val durationMs: Long,
    val addedSeconds: Long,
    val relativePath: String?,
    val width: Int,
    val height: Int,
)

/** Everything a row's catalog item depends on besides the row itself, read once per build. */
internal class CatalogInputs(
    val simplifiedNames: Boolean,
    /** What the tile shows for a name that parses to nothing. */
    val genericTitle: String,
    val editionLabel: (VideoEdition) -> String,
    /** The phone's own resume rule, or null where the phone would start from the top. */
    val resumeMs: (fingerprint: String, durationMs: Long) -> Long?,
    /** The stored checkpoint behind [resumeMs], for its time and for §9's backfill. */
    val checkpoint: (fingerprint: String) -> PlaybackCheckpoint?,
    /** The watched ledger: fingerprint to when it was recorded, epoch ms. */
    val watched: Map<String, Long>,
    /** Keyed by `MediaItem.uriKey`, as the coordinator's marks are. */
    val unplayable: Set<String>,
    val silent: Set<String>,
    /**
     * Cached TMDB art, or null when the film-art setting is off. That setting is the owner's
     * word on TMDB, so with it off nothing TMDB said shapes an item: no art, no film kind and
     * no `tv:` show key.
     */
    val art: ((FilmPosterQuery) -> FilmArt?)?,
    /** An already-probed HDR verdict, or null where nobody has looked. */
    val hdr: (uriKey: String) -> CatalogItem.Hdr?,
)

/**
 * What a file name alone decides, kept across builds: a push re-runs every 2 s while art
 * arrives, and three regex parses per row of a large library are the bulk of a build.
 */
internal class CatalogNameFacts private constructor(
    val parsed: ParsedVideoName,
    val sortKey: String,
    val find: String,
    val query: FilmPosterQuery?,
) {
    class Cache {
        private val facts = HashMap<String, CatalogNameFacts>()

        fun of(name: String): CatalogNameFacts = facts.getOrPut(name) {
            val parsed = VideoNames.parse(name)
            CatalogNameFacts(parsed, librarySortTitle(name), LibrarySearchPolicy.searchText(name), FilmPosterPolicy.queryFor(parsed))
        }

        /** Drops names no longer in the library, so the cache is bounded by it. */
        fun retain(names: Set<String>) {
            facts.keys.retainAll(names)
        }
    }
}

/** Contract §5.1: the phone's library, in its own order, as catalog items. */
internal object LibraryCatalogBuilder {

    fun build(
        rows: List<CatalogSourceRow>,
        inputs: CatalogInputs,
        names: CatalogNameFacts.Cache = CatalogNameFacts.Cache(),
    ): List<CatalogItem> = rows.map { item(it, inputs, names.of(it.name)) }

    fun item(row: CatalogSourceRow, inputs: CatalogInputs, facts: CatalogNameFacts): CatalogItem {
        val parsed = facts.parsed
        val lookup = inputs.art
        val query = if (lookup != null) facts.query else null
        val art = if (lookup != null && query != null) lookup(query)?.takeUnless { it.isEmpty } else null
        // An entry cached before the catalogue was recorded is the query's own kind.
        val artIsShow = if (art != null && query != null) art.show ?: query.show else null
        val show = parsed.title?.takeIf { it.isNotBlank() }
        val episodic = parsed.season != null && parsed.episode != null && show != null
        val kind = when {
            episodic -> CatalogItem.Kind.EPISODE
            art != null && artIsShow == false -> CatalogItem.Kind.FILM
            else -> CatalogItem.Kind.VIDEO
        }
        val showKey = if (episodic && show != null) {
            val tmdbId = art?.tmdbId?.takeIf { artIsShow == true && it > 0L }
            if (tmdbId != null) "tv:$tmdbId" else "name:" + FoldedText.fold(show)
        } else {
            null
        }
        val blocked = when (row.uriKey) {
            in inputs.unplayable -> CatalogItem.Block.UNPLAYABLE
            in inputs.silent -> CatalogItem.Block.SILENT
            else -> null
        }
        val resumeMs = inputs.resumeMs(row.fingerprint, row.durationMs)
        val checkpoint = inputs.checkpoint(row.fingerprint)
        val watchedAtMs = inputs.watched[row.fingerprint]
            ?: WatchedLedgerPolicy.backfillAtMs(checkpoint, row.durationMs, recorded = false)
        return CatalogItem(
            id = LibraryProtocol.itemId(row.fingerprint),
            name = row.name,
            title = title(row.name, parsed, inputs),
            sortKey = facts.sortKey,
            find = facts.find,
            kind = kind,
            show = show.takeIf { episodic },
            showKey = showKey,
            season = parsed.season.takeIf { episodic },
            episode = parsed.episode.takeIf { episodic },
            year = parsed.year,
            durationMs = row.durationMs,
            addedSeconds = row.addedSeconds,
            folder = row.relativePath,
            width = row.width,
            height = row.height,
            hdr = inputs.hdr(row.uriKey),
            resumeMs = resumeMs,
            playedAtSeconds = checkpoint?.takeIf { resumeMs != null }?.updatedAtEpochMs?.div(1_000L),
            watched = watchedAtMs != null,
            watchedAtSeconds = watchedAtMs?.div(1_000L),
            blocked = blocked,
            art = art?.let { CatalogItem.Art(it.tmdbId, artIsShow, it.poster, it.backdrop, it.logo) },
        )
    }

    /** Exactly the library tile's `displayName()`: same preference, same edition label, same fallback. */
    fun title(name: String, parsed: ParsedVideoName, inputs: CatalogInputs): String {
        val displayed = if (inputs.simplifiedNames) {
            VideoNames.format(parsed, parsed.edition?.let(inputs.editionLabel))
        } else {
            VideoNames.safeFileName(name)
        }
        return displayed.ifBlank { inputs.genericTitle }
    }
}
