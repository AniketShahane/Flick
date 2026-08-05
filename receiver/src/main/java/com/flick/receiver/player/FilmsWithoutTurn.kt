package com.flick.receiver.player

/**
 * The films whose picture could not be turned during this run of the app.
 *
 * `turnUnavailableForFilm` is cleared with the film, which is right for a
 * DIFFERENT film and wrong for the same one. `StartupRetryPolicy` re-prepares the
 * identical media inside one cast, `resetVideoRotation` hands the verdict back on
 * each attempt, the same turn is engaged and fails the same way — the permanent
 * "this film will not cast" loop [com.flick.receiver.session.StartupDeadlinePolicy]
 * warns about, reached from the other side. So the verdict is kept against
 * whatever identifies the film across those attempts, and nothing else is: it is
 * never persisted, so a new run of the app gives every film its attempt back, and
 * it is bounded, so a long session cannot grow it without limit.
 *
 * The key is the media URL, because the media id is not one: the session builds
 * that from the cast's own generation. What the URL does NOT survive is the
 * viewer pressing retry on the phone — that mints a fresh capability token per
 * start, so the receiver is handed a film it has demonstrably never seen and
 * cannot honestly refuse a turn to. That attempt engages the turn again and is
 * caught again by one deadline, which is the bounded cost rather than the loop.
 *
 * [CAPACITY] is small deliberately. This is a session memory of a rare failure
 * rather than a cache; the entries that have to survive are the ones a viewer
 * might retry now, and evicting the least recently condemned film costs it
 * nothing worse than one more attempt.
 *
 * The keys carry the sender's capability token. They are held and compared here
 * and must never be logged — see `FlickLog`.
 */
internal class FilmsWithoutTurn(private val capacity: Int = CAPACITY) {

    // Insertion-ordered, so the entry evicted is the one condemned longest ago.
    private val keys = LinkedHashSet<String>()

    /** A film with no key is remembered about nothing; that is a no-op, not a wildcard. */
    fun remember(key: String?) {
        if (key.isNullOrEmpty()) return
        keys.remove(key)
        keys.add(key)
        while (keys.size > capacity) {
            keys.remove(keys.first())
        }
    }

    fun remembers(key: String?): Boolean = !key.isNullOrEmpty() && key in keys

    companion object {
        const val CAPACITY = 8
    }
}
