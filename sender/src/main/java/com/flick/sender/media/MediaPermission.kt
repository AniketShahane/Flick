package com.flick.sender.media

/**
 * What the video permission leaves the library able to ask for.
 *
 * Lifted from `CameraPermission.state`, which already resolves exactly this from the same
 * three signals. The media permission had no equivalent, so [MediaAccess.NONE] meant both
 * "never asked" and "blocked for good" — and only one of those can be asked again: after
 * a permanent denial the launcher returns instantly with no system UI at all, which is the
 * one control a locked install ever sees.
 */
enum class MediaPermissionState { UNREQUESTED, DENIED, BLOCKED }

/**
 * The platform reports no rationale both BEFORE the first prompt and AFTER a second
 * refusal, so [requested] is the only thing separating a library that can still ask from
 * one that can only point at Settings.
 *
 * [granted] resolves to [MediaPermissionState.UNREQUESTED] because a granted permission
 * has no locked empty state to draw; the caller must not consult this while it holds.
 */
fun mediaPermissionState(
    granted: Boolean,
    showRationale: Boolean,
    requested: Boolean,
): MediaPermissionState = when {
    granted -> MediaPermissionState.UNREQUESTED
    showRationale -> MediaPermissionState.DENIED
    requested -> MediaPermissionState.BLOCKED
    else -> MediaPermissionState.UNREQUESTED
}
