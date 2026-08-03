package com.flick.receiver.net

/** What a well-formed `setRotation` frame is asking the receiver to do. */
sealed interface RotationCommand {
    /**
     * A quarter turn the phone is ASSERTING, on top of the container's own. The
     * receiver stops reading the file for itself and applies exactly this.
     */
    data class Explicit(val degrees: Int) : RotationCommand

    /**
     * Hand the decision back. Not a degree value in disguise: the receiver
     * re-reads the media that is playing and applies whatever it decides now,
     * which is the one answer a phone cannot name.
     */
    data object Auto : RotationCommand
}

/**
 * The `setRotation` verb's two accepted shapes.
 *
 * Two exact field sets rather than one permissive check — the same construction
 * `denied` uses for its 2-key and 3-key forms, and `loadFailed`/`error` for
 * their with- and without-`httpStatus` forms. A sentinel inside `degrees` was
 * the alternative and would have made the value domain of a numeric field carry
 * a mode, so a frame off the quarter-turn grid could no longer be called
 * malformed on sight.
 *
 * `auto` accepts only `true`. There is no "not auto" to command: the phone
 * asserting an orientation is the other shape, and it carries the degrees.
 */
object RotationCommandSchema {
    const val DEGREES_FIELD = "degrees"
    const val AUTO_FIELD = "auto"

    val EXPLICIT_FIELDS = setOf("t", "v", "castId", DEGREES_FIELD)
    val AUTO_FIELDS = setOf("t", "v", "castId", AUTO_FIELD)

    /**
     * Quarter turns only. `MediaFormat.KEY_ROTATION` accepts nothing else, so a
     * value off this grid is a malformed frame rather than a value to snap.
     */
    private val DEGREES = setOf(0L, 90L, 180L, 270L)

    /** Null for anything that is not one of the two shapes, exactly. */
    fun read(o: StrictJsonValue.Obj): RotationCommand? = when {
        o.exactly(EXPLICIT_FIELDS) ->
            o.integer(DEGREES_FIELD)?.takeIf { it in DEGREES }?.let { RotationCommand.Explicit(it.toInt()) }
        o.exactly(AUTO_FIELDS) ->
            if (o.bool(AUTO_FIELD) == true) RotationCommand.Auto else null
        else -> null
    }
}
