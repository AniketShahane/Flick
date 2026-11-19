package com.flick.receiver.library

/**
 * TMDB image URLs, built on the TV from the paths the phone sends. The phone never sends a
 * URL (§5.1), and these need no API key. Sizes are from TMDB's configuration lists; the UI is
 * drawn at 1080p, so nothing here asks for `original`.
 */
object LibraryImages {
    private const val BASE = "https://image.tmdb.org/t/p/"

    /** Full-width hero behind the home screen and the detail page. */
    fun heroBackdrop(path: String?): String? = url("w1280", path)

    /** A 16:9 row tile. */
    fun tileBackdrop(path: String?): String? = url("w780", path)

    /** A 2:3 poster tile in the Shows and Films grids. */
    fun poster(path: String?): String? = url("w342", path)

    /** The title logo over a hero. */
    fun logo(path: String?): String? = url("w500", path)

    /** An episode still on the show page. */
    fun still(path: String?): String? = url("w300", path)

    private fun url(size: String, path: String?): String? =
        path?.takeIf(LibraryWire::imagePath)?.let { BASE + size + it }
}
