@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.flick.sender.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.MovieHash
import com.flick.sender.media.SidecarScan
import com.flick.sender.media.SubtitleCandidate
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.media.SubtitleFolder
import com.flick.sender.media.SubtitleFolderStore
import com.flick.sender.media.SubtitleMatchKind
import com.flick.sender.media.VideoNames
import com.flick.sender.model.PlaybackUiState
import com.flick.sender.model.VideoRotation
import com.flick.sender.net.ApiKeySource
import com.flick.sender.net.FlickController
import com.flick.sender.net.OnlineSubtitle
import com.flick.sender.net.OpenSubtitlesClient
import com.flick.sender.net.OpenSubtitlesKeyStore
import com.flick.sender.net.OpenSubtitlesLanguage
import com.flick.sender.net.OpenSubtitlesSearchPolicy
import com.flick.sender.net.OpenSubtitlesTextState
import com.flick.sender.net.SubtitleFetchOutcome
import com.flick.sender.net.SubtitleLoginOutcome
import com.flick.sender.net.SubtitleQuota
import com.flick.sender.net.SubtitleSearchOutcome
import com.flick.sender.ui.displayName
import com.flick.sender.ui.rotationLabelRes
import com.flick.sender.ui.components.FlickPrimaryButton
import com.flick.sender.ui.components.FlickSubtleButton
import com.flick.sender.ui.theme.FlickCinematicTheme
import com.flick.sender.ui.theme.FlickCorners
import com.flick.sender.ui.theme.FlickIcons
import com.flick.sender.ui.theme.FlickText
import com.flick.sender.ui.theme.LocalFlickColors
import com.flick.sender.ui.theme.Motion
import com.flick.sender.ui.theme.PillShape
import com.flick.sender.ui.theme.flickRipple
import com.flick.sender.ui.theme.pressScale
import com.flick.sender.ui.theme.rememberFlickTouchHaptics
import com.flick.sender.ui.theme.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.Locale

/** Where a subtitle can come from, in the order the sheet offers them. */
private enum class SubtitleSource { FILE, FOLDER, ONLINE }

/**
 * ACTION_OPEN_DOCUMENT with the persistable-grant flag the platform contract leaves
 * out. Without it the read grant dies with the task and the server loses the file
 * halfway through a cast.
 */
private class PickSubtitleDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

/** Same reason as [PickSubtitleDocument]: the folder grant has to outlive the picker. */
private class PickSubtitleFolder : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}

/**
 * External subtitles for the video being cast. Forced cinematic, like every surface
 * the remote raises.
 *
 * The two local sources are name-based on purpose: on Android 16 no permission Flick may
 * ask for exposes .srt files, so the user either points at one file or grants one folder.
 * The third fetches from OpenSubtitles with the key this build carries, matched against a
 * fingerprint of the video file itself where the file can be read.
 */
@Composable
fun SubtitlesSheet(controller: FlickController, onDismiss: () -> Unit) {
    FlickCinematicTheme {
        SubtitlesContent(controller, onDismiss)
    }
}

@Composable
private fun SubtitlesContent(controller: FlickController, onDismiss: () -> Unit) {
    val colors = LocalFlickColors.current
    val haptics = rememberFlickTouchHaptics()

    val item by controller.castingItem.collectAsState()
    val videoName = item?.name
    val videoDisplayName = item?.displayName()
    val attached = controller.selectedSubtitle.collectAsState().value
    val attachedName = attached?.displayName
    val attachedUri = attached?.uri
    val attachedLanguage = attached?.language

    // The session clock ticks ~10 Hz through this same state object and nothing in
    // this sheet follows it, so the one field that matters is narrowed here rather
    // than unwrapped: only a change of choice may rebuild the sheet.
    val playbackState = controller.playback.collectAsState()
    val rotation by remember(playbackState) { derivedStateOf { playbackState.value.rotation } }
    val commandable by controller.commandable.collectAsState()

    var source by rememberSaveable { mutableStateOf(SubtitleSource.FILE) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun attach(uri: Uri, displayName: String, language: String?) {
        controller.selectSubtitle(uri, displayName, language)
        haptics.toggle(true)
        notice = null
    }

    BottomSheet(
        onDismiss = onDismiss,
        contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 26.dp),
    ) {
        SheetGrabber()
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.subs_title),
            style = FlickText.headlineMedium.copy(color = colors.onSurface),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = videoDisplayName?.let { stringResource(R.string.subs_for_video, it) }
                ?: stringResource(R.string.subs_for_nothing),
            style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(16.dp))
        AttachedRow(
            displayName = attachedName,
            language = attachedLanguage,
            onRemove = {
                controller.clearSubtitle()
                haptics.toggle(false)
                notice = null
            },
        )

        Spacer(Modifier.height(14.dp))
        SourceTabs(
            selected = source,
            onSelect = {
                if (it != source) {
                    haptics.toggle(true)
                    source = it
                    notice = null
                }
            },
        )

        Spacer(Modifier.height(16.dp))
        val reduceMotion = rememberReduceMotion()
        val motionScheme = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = source,
            transitionSpec = {
                if (reduceMotion) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                        fadeOut(motionScheme.fastEffectsSpec())
                }
            },
            label = "subtitle source",
        ) { tab ->
            Column(Modifier.fillMaxWidth()) {
                when (tab) {
                    SubtitleSource.FILE -> FilePane(
                        videoName = videoName,
                        onAttach = ::attach,
                        onRejected = { notice = it },
                    )
                    SubtitleSource.FOLDER -> FolderPane(
                        videoName = videoName,
                        attachedUri = attachedUri,
                        onAttach = ::attach,
                    )
                    SubtitleSource.ONLINE -> key(item?.uri) {
                        OnlinePane(
                            videoName = videoName,
                            videoUri = item?.uri,
                            videoSizeBytes = item?.sizeBytes ?: -1L,
                            onAttach = ::attach,
                        )
                    }
                }
            }
        }

        notice?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(message, style = FlickText.bodySmall.copy(color = colors.trouble))
        }

        if (attachedName != null) {
            Spacer(Modifier.height(12.dp))
            // A live cast is re-loaded to attach the track, so the cost of the swap is
            // stated where the swap is made rather than discovered as a re-buffer.
            Text(
                stringResource(
                    if (videoName == null) R.string.subs_note_idle else R.string.subs_note,
                ),
                style = FlickText.bodySmall.copy(color = colors.onSurfaceFaint),
            )
        }

        // The picture's own orientation, in the panel the TV keeps it in — one
        // feature reached the same way on both screens. Only while there is a film:
        // with nothing cast there is no picture to turn.
        if (item != null) {
            Spacer(Modifier.height(20.dp))
            OrientationSection(
                rotation = rotation,
                commandable = commandable,
                // Unguarded, unlike the source tabs: the cell that looks selected is
                // only what this phone last asked for, and the TV's own panel can have
                // moved the picture since. Pressing it is how the viewer re-asserts,
                // so it must reach the wire.
                onSelect = { choice ->
                    controller.setRotation(choice)
                    haptics.toggle(true)
                },
            )
        }

        Spacer(Modifier.height(18.dp))
        val doneInteraction = remember { MutableInteractionSource() }
        // Read inside the sheet that provides it: Done is the same dismissal the scrim,
        // Back and a drag down are, so it takes the same exit instead of being the one
        // way out of this sheet that cuts it and its scrim away in a single frame.
        val done = LocalSheetDismiss.current
        Text(
            text = stringResource(R.string.subs_done),
            style = FlickText.titleSmall.copy(color = colors.onInverseSurface),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(doneInteraction)
                .clip(PillShape)
                .background(colors.inverseSurface)
                .clickable(
                    interactionSource = doneInteraction,
                    indication = flickRipple(colors.onInverseSurface),
                    role = Role.Button,
                    onClick = done,
                )
                .heightIn(min = 48.dp)
                .padding(vertical = 17.dp),
        )
    }
}

/** What is attached right now, and the only way to detach it. */
@Composable
private fun ColumnScope.AttachedRow(
    displayName: String?,
    language: String?,
    onRemove: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val removeLabel = stringResource(R.string.a11y_subs_remove)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FlickCorners.statCard))
            .background(colors.fillCard)
            .padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            FlickIcons.Captions,
            contentDescription = null,
            tint = if (displayName == null) colors.onSurfaceFaint else colors.spark,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName ?: stringResource(R.string.subs_none_attached),
                style = FlickText.bodyMedium.copy(
                    color = if (displayName == null) colors.onSurfaceFaint else colors.onSurface,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            languageLabel(language)?.let {
                Text(it, style = FlickText.monoSmall.copy(color = colors.onSurfaceDim), maxLines = 1)
            }
        }
        if (displayName != null) {
            val interaction = remember { MutableInteractionSource() }
            Text(
                stringResource(R.string.subs_remove),
                style = FlickText.labelMedium.copy(color = colors.trouble),
                modifier = Modifier
                    .pressScale(interaction)
                    .clip(PillShape)
                    .semantics { contentDescription = removeLabel }
                    .clickable(
                        interactionSource = interaction,
                        indication = flickRipple(colors.onSurface),
                        role = Role.Button,
                        onClick = onRemove,
                    )
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 15.dp),
            )
        }
    }
}

@Composable
private fun ColumnScope.SourceTabs(selected: SubtitleSource, onSelect: (SubtitleSource) -> Unit) {
    val colors = LocalFlickColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.fillCard)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SourceTab(R.string.subs_tab_file, selected == SubtitleSource.FILE) {
            onSelect(SubtitleSource.FILE)
        }
        SourceTab(R.string.subs_tab_folder, selected == SubtitleSource.FOLDER) {
            onSelect(SubtitleSource.FOLDER)
        }
        SourceTab(R.string.subs_tab_online, selected == SubtitleSource.ONLINE) {
            onSelect(SubtitleSource.ONLINE)
        }
    }
}

@Composable
private fun RowScope.SourceTab(labelRes: Int, active: Boolean, onClick: () -> Unit) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    // A tab change is a finger's consequence, so the fill travels on a spring rather
    // than swapping between two flat colours.
    val fill = animateColorAsState(
        targetValue = if (active) colors.inverseSurface else Color.Transparent,
        animationSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.fastEffectsSpec<Color>()),
        label = "tab fill",
    )
    val ink = animateColorAsState(
        targetValue = if (active) colors.onInverseSurface else colors.onSurfaceDim,
        animationSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.fastEffectsSpec<Color>()),
        label = "tab ink",
    )
    val label = stringResource(labelRes)
    Box(
        Modifier
            .weight(1f)
            .pressScale(interaction)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(fill.value)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onInverseSurface),
                onClick = onClick,
            )
            .semantics {
                this.role = Role.Tab
                this.selected = active
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FlickText.labelMedium.copy(color = ink.value), maxLines = 1)
    }
}

// --- the picture's own orientation --------------------------------------------------

/**
 * The turn Flick adds to the container's own, in the same cell vocabulary the
 * subtitle sources above are picked with — one pill group, one selected fill, and
 * the same 48 dp touch target.
 *
 * Five cells rather than four: [VideoRotation.AsFiled] is what a viewer presses
 * when the TV's Auto read their file wrong, and without it the only way back to the
 * container's own answer would be the choice that just overruled it.
 *
 * There is no readout of what Auto decided. That verdict is the receiver's reading
 * of the file and no frame carries it back — see [PlaybackUiState.rotation] for why
 * the `state` frame must not grow one — so the phone states the choice it made and
 * claims nothing about the answer.
 */
@Composable
private fun ColumnScope.OrientationSection(
    rotation: VideoRotation,
    commandable: Boolean,
    onSelect: (VideoRotation) -> Unit,
) {
    val colors = LocalFlickColors.current
    Text(
        stringResource(R.string.subs_orientation_label),
        style = FlickText.monoEyebrow.copy(color = colors.onSurfaceFaint),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.subs_orientation_body),
        style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
    )
    Spacer(Modifier.height(12.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(PillShape)
            .background(colors.fillCard)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        VideoRotation.ALL.forEach { choice ->
            OrientationCell(
                labelRes = rotationLabelRes(choice),
                selected = choice == rotation,
                enabled = commandable,
                onClick = { onSelect(choice) },
            )
        }
    }
    // Stated rather than left to a dead cell: the verb needs an Active cast on a
    // live socket, and the same guard arms the media notification's transport.
    if (!commandable) {
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.subs_orientation_unavailable),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceFaint),
        )
    }
}

/**
 * One cell. Deliberately the same lockup as [SourceTab] above — the sheet already
 * has a vocabulary for "pick one of these", and a second one would read as a
 * different kind of control.
 */
@Composable
private fun RowScope.OrientationCell(
    @StringRes labelRes: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val reduceMotion = rememberReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val fill = animateColorAsState(
        targetValue = if (selected) colors.inverseSurface else Color.Transparent,
        animationSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.fastEffectsSpec<Color>()),
        label = "orientation fill",
    )
    val ink = animateColorAsState(
        targetValue = when {
            selected -> colors.onInverseSurface
            enabled -> colors.onSurfaceDim
            else -> colors.onSurfaceDim.copy(alpha = 0.5f)
        },
        animationSpec = Motion.orSnap(reduceMotion, MaterialTheme.motionScheme.fastEffectsSpec<Color>()),
        label = "orientation ink",
    )
    val label = stringResource(labelRes)
    val description = stringResource(R.string.a11y_subs_orientation, label)
    val unavailable = stringResource(R.string.subs_orientation_unavailable)
    Box(
        Modifier
            .weight(1f)
            .then(if (enabled) Modifier.pressScale(interaction) else Modifier)
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(fill.value)
            .then(
                if (enabled) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = flickRipple(colors.onInverseSurface),
                        onClick = onClick,
                    )
                } else {
                    Modifier.semantics { disabled(); stateDescription = unavailable }
                },
            )
            .semantics {
                this.role = Role.RadioButton
                this.selected = selected
                contentDescription = description
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FlickText.labelMedium.copy(color = ink.value), maxLines = 1)
    }
}

// --- source 1: one file, picked by hand -------------------------------------------

@Composable
private fun ColumnScope.FilePane(
    videoName: String?,
    onAttach: (Uri, String, String?) -> Unit,
    onRejected: (String) -> Unit,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notASubtitle = stringResource(R.string.subs_not_a_subtitle)
    val tooLarge = stringResource(R.string.subs_too_large)
    val unreadable = stringResource(R.string.subs_unreadable_pick)

    val picker = rememberLauncherForActivityResult(remember { PickSubtitleDocument() }) { picked ->
        val uri = picked ?: return@rememberLauncherForActivityResult
        // Best effort: a provider that refuses to persist still leaves the transient
        // grant, which outlives the cast that is being set up right now.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        scope.launch {
            val name = SubtitleFolder.displayNameOf(context, uri)
            when {
                name == null -> onRejected(unreadable)
                !SubtitleFiles.isSubtitleName(name) -> onRejected(notASubtitle)
                SubtitleFolder.sizeOf(context, uri) > SubtitleFiles.MaxSubtitleBytes ->
                    onRejected(tooLarge)
                else -> onAttach(
                    uri,
                    name,
                    SubtitleFiles.languageTagOf(videoName.orEmpty(), name),
                )
            }
        }
    }

    Text(
        stringResource(R.string.subs_pick_body),
        style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
    )
    Spacer(Modifier.height(14.dp))
    FlickPrimaryButton(
        text = stringResource(R.string.subs_pick_action),
        onClick = { picker.launch(SubtitleFiles.PickerMimeTypes) },
    )
}

// --- source 2: sidecars out of one remembered folder -------------------------------

@Composable
private fun ColumnScope.FolderPane(
    videoName: String?,
    attachedUri: Uri?,
    onAttach: (Uri, String, String?) -> Unit,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val store = remember(context) { SubtitleFolderStore(context.applicationContext) }
    var folder by remember { mutableStateOf(store.folder()) }
    var scan by remember { mutableStateOf<SidecarScan?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    val chooser = rememberLauncherForActivityResult(remember { PickSubtitleFolder() }) { tree ->
        val granted = tree ?: return@rememberLauncherForActivityResult
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(
                granted,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.isSuccess
        // A folder that cannot be persisted is worse than no folder: it would be
        // remembered and then fail silently on the next cast.
        if (kept) {
            store.save(granted)
            folder = granted
        }
    }

    LaunchedEffect(folder, videoName) {
        val tree = folder
        if (tree == null) {
            scan = null
            return@LaunchedEffect
        }
        scanning = true
        scan = SubtitleFolder.scan(context, tree, videoName)
        scanning = false
    }

    if (folder == null) {
        Text(
            stringResource(R.string.subs_folder_body),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
        Spacer(Modifier.height(14.dp))
        FlickPrimaryButton(
            text = stringResource(R.string.subs_folder_action),
            onClick = { chooser.launch(null) },
        )
        return
    }

    when {
        scanning -> BusyRow(stringResource(R.string.subs_folder_scanning))
        scan is SidecarScan.AccessLost -> Text(
            stringResource(R.string.subs_folder_lost),
            style = FlickText.bodySmall.copy(color = colors.caution),
        )
        scan is SidecarScan.Unreadable -> Text(
            stringResource(R.string.subs_folder_unreadable),
            style = FlickText.bodySmall.copy(color = colors.caution),
        )
        else -> {
            val candidates = (scan as? SidecarScan.Found)?.candidates.orEmpty()
            val matched = candidates.filter { it.match != null }
            val rest = candidates.filter { it.match == null }
            when {
                candidates.isEmpty() -> Text(
                    stringResource(R.string.subs_folder_empty),
                    style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
                )
                matched.isEmpty() && !showAll -> Text(
                    stringResource(R.string.subs_folder_no_match),
                    style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
                )
                else -> Unit
            }
            val shown = if (showAll) candidates else matched
            shown.forEach { candidate ->
                CandidateRow(
                    candidate = candidate,
                    attached = candidate.uri == attachedUri,
                    onClick = {
                        onAttach(candidate.uri, candidate.displayName, candidate.match?.language)
                    },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (rest.isNotEmpty()) {
                FlickSubtleButton(
                    text = if (showAll) {
                        stringResource(R.string.subs_folder_show_matches)
                    } else {
                        stringResource(R.string.subs_folder_show_all, rest.size)
                    },
                    onClick = { showAll = !showAll },
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    FlickSubtleButton(
        text = stringResource(R.string.subs_folder_change),
        onClick = { chooser.launch(folder) },
    )
}

@Composable
private fun CandidateRow(
    candidate: SubtitleCandidate,
    attached: Boolean,
    onClick: () -> Unit,
) {
    val detail = listOfNotNull(
        languageLabel(candidate.match?.language),
        when (candidate.match?.kind) {
            SubtitleMatchKind.EXACT -> stringResource(R.string.subs_match_exact)
            SubtitleMatchKind.PREFIX -> stringResource(R.string.subs_match_prefix)
            SubtitleMatchKind.FUZZY -> stringResource(R.string.subs_match_fuzzy)
            null -> null
        },
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    SelectableRow(
        title = candidate.displayName,
        detail = detail,
        attached = attached,
        onClick = onClick,
    )
}

// --- source 3: OpenSubtitles, keyed by the app and quota'd by the account -----------

/**
 * The online tab. Two things are being kept apart here, because conflating them is what
 * made this tab do nothing for everyone: the **key** identifies the app and is what makes
 * a request legal at all, while the daily **allowance** belongs to whichever account is
 * signed in. So the search works the moment a key is present, and the sign-in is an
 * optional way to stop sharing one small allowance with every other install.
 *
 * [videoUri] is read only to fingerprint the file — two 64 KiB windows, off the main
 * thread — which is what makes a result an in-sync match rather than a guess at the title.
 */
@Composable
private fun ColumnScope.OnlinePane(
    videoName: String?,
    videoUri: Uri?,
    videoSizeBytes: Long,
    onAttach: (Uri, String, String?) -> Unit,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = remember(context) { OpenSubtitlesKeyStore(context.applicationContext) }
    val client = remember(context) { OpenSubtitlesClient(context.applicationContext, keys) }
    DisposableEffect(client) { onDispose { client.close() } }

    var key by remember { mutableStateOf(keys.resolved()) }
    var session by remember { mutableStateOf(client.session()) }
    var keyEntry by remember { mutableStateOf("") }
    var showKeyEntry by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf("") }
    // Deliberately NOT rememberSaveable: saved instance state is written out by the
    // system, and a password must never be one of the things it writes down.
    var password by remember { mutableStateOf("") }
    val parsedName = remember(videoName) { videoName?.let(VideoNames::parse) }
    var query by remember(videoName) { mutableStateOf(parsedName?.searchQuery.orEmpty()) }
    var year by remember(videoName) { mutableStateOf(parsedName?.year?.toString().orEmpty()) }
    var season by remember(videoName) { mutableStateOf(parsedName?.season?.toString().orEmpty()) }
    var episode by remember(videoName) { mutableStateOf(parsedName?.episode?.toString().orEmpty()) }
    var language by remember { mutableStateOf(OpenSubtitlesSearchPolicy.DefaultLanguage) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>?>(null) }
    var quota by remember { mutableStateOf<SubtitleQuota?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun invalidateSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        searching = false
        results = null
        error = null
    }

    // The fingerprint of the file being cast, or null when there is no file, it is too
    // small, or the provider will not seek. Search waits for this bounded read so its
    // first request cannot race ahead as a weaker text-only lookup.
    var fingerprint by remember(videoUri) { mutableStateOf<MovieHash.Fingerprint?>(null) }
    var fingerprintChecking by remember(videoUri, videoSizeBytes) {
        mutableStateOf(videoUri != null)
    }
    LaunchedEffect(videoUri, videoSizeBytes) {
        fingerprintChecking = videoUri != null
        val computed = videoUri?.let { MovieHash.of(context, it, videoSizeBytes) }
        fingerprintChecking = false
        if (computed != fingerprint) invalidateSearch()
        fingerprint = computed
    }

    val offline = stringResource(R.string.subs_error_offline)
    val badKeyMine = stringResource(R.string.subs_error_key_mine)
    val badKeyApp = stringResource(R.string.subs_error_key_app)
    val rateLimited = stringResource(R.string.subs_error_rate)
    val quotaShared = stringResource(R.string.subs_error_quota)
    val quotaOwn = stringResource(R.string.subs_error_quota_signed)
    val badSignIn = stringResource(R.string.subs_error_sign_in)
    val signInExpired = stringResource(R.string.subs_error_sign_in_expired)
    val linkRejected = stringResource(R.string.subs_error_link)
    val unavailable = stringResource(R.string.subs_error_unavailable)
    val tooLarge = stringResource(R.string.subs_too_large)

    // A key the user pasted is one they can fix; the app's own is not, so the sentence
    // about a refused key has to be a different sentence.
    fun refusedKey(): String = if (key?.source == ApiKeySource.USER) badKeyMine else badKeyApp

    fun saveOwnKey() {
        if (keys.saveUserKey(keyEntry)) {
            keyEntry = ""
            showKeyEntry = false
            key = keys.resolved()
            error = null
        }
    }

    // No key at all — the state of this build until OpenSubtitles approves Flick's own
    // consumer key. Say that plainly, point at the tab that still works, and leave the
    // field as the way somebody with their own key gets past it.
    if (key == null) {
        Text(
            stringResource(R.string.subs_online_no_key),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
        Spacer(Modifier.height(14.dp))
        KeyEntry(entry = keyEntry, onEntry = { keyEntry = it }, onSave = ::saveOwnKey)
        return
    }

    Text(
        stringResource(R.string.subs_online_body),
        style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
    )
    Spacer(Modifier.height(14.dp))
    OnlineLanguageSelector(
        selected = language,
        enabled = !downloading,
        onSelect = { selected ->
            if (selected != language) {
                invalidateSearch()
                language = selected
            }
        },
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { value ->
            if (value != query) invalidateSearch()
            query = value
        },
        label = { Text(stringResource(R.string.subs_online_query_label)) },
        singleLine = true,
        shape = RoundedCornerShape(FlickCorners.tuneBtn),
        modifier = Modifier.fillMaxWidth(),
    )
    val normalizedQuery = OpenSubtitlesSearchPolicy.textQuery(query)
    val yearValue = year.toIntOrNull()
    val seasonValue = season.toIntOrNull()
    val episodeValue = episode.toIntOrNull()
    val yearValid = year.isBlank() || yearValue?.let(OpenSubtitlesSearchPolicy::validYear) == true
    val seasonValid = season.isBlank() || seasonValue?.let(OpenSubtitlesSearchPolicy::validSeason) == true
    val episodeValid = episode.isBlank() || episodeValue?.let(OpenSubtitlesSearchPolicy::validEpisode) == true
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = year,
        onValueChange = { value ->
            val filtered = value.filter(Char::isDigit).take(4)
            if (filtered != year) invalidateSearch()
            year = filtered
        },
        label = { Text(stringResource(R.string.subs_online_year_label)) },
        isError = !yearValid,
        supportingText = {
            if (!yearValid) Text(stringResource(R.string.subs_online_year_invalid))
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(FlickCorners.tuneBtn),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = season,
            onValueChange = { value ->
                val filtered = value.filter(Char::isDigit).take(2)
                if (filtered != season) invalidateSearch()
                season = filtered
            },
            label = { Text(stringResource(R.string.subs_online_season_label)) },
            isError = !seasonValid,
            supportingText = {
                if (!seasonValid) Text(stringResource(R.string.subs_online_season_invalid))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = episode,
            onValueChange = { value ->
                val filtered = value.filter(Char::isDigit).take(3)
                if (filtered != episode) invalidateSearch()
                episode = filtered
            },
            label = { Text(stringResource(R.string.subs_online_episode_label)) },
            isError = !episodeValid,
            supportingText = {
                if (!episodeValid) Text(stringResource(R.string.subs_online_episode_invalid))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.weight(1f),
        )
    }

    val numericInputsValid = yearValid && seasonValid && episodeValid
    val canSearchText = normalizedQuery.state == OpenSubtitlesTextState.READY
    val canSearchHash = fingerprint != null
    val queryGuidance = when {
        fingerprintChecking -> R.string.subs_online_fingerprint_reading
        normalizedQuery.state == OpenSubtitlesTextState.TOO_SHORT && canSearchHash ->
            R.string.subs_online_query_short_hash_only
        normalizedQuery.state == OpenSubtitlesTextState.TOO_SHORT ->
            R.string.subs_online_query_too_short
        normalizedQuery.state == OpenSubtitlesTextState.EMPTY && !canSearchHash ->
            R.string.subs_online_query_or_fingerprint_unavailable
        else -> null
    }
    queryGuidance?.let { message ->
        Spacer(Modifier.height(8.dp))
        Text(stringResource(message), style = FlickText.bodySmall.copy(color = colors.onSurfaceDim))
    }

    Spacer(Modifier.height(12.dp))
    when {
        searching -> BusyRow(stringResource(R.string.subs_online_searching))
        downloading -> BusyRow(stringResource(R.string.subs_online_downloading))
        else -> FlickPrimaryButton(
            text = stringResource(R.string.subs_online_search),
            // A file Flick can fingerprint is searchable even with the title box empty:
            // the hash names it better than any typed words would.
            enabled = !fingerprintChecking && numericInputsValid && (canSearchText || canSearchHash),
            onClick = {
                val generation = searchGeneration + 1
                searchGeneration = generation
                searchJob?.cancel()
                error = null
                results = null
                searching = true
                val requestedQuery = query
                val requestedYear = yearValue
                val requestedSeason = seasonValue
                val requestedEpisode = episodeValue
                val requestedFingerprint = fingerprint
                val requestedLanguage = language
                searchJob = scope.launch {
                    try {
                        val outcome = client.search(
                            query = requestedQuery,
                            year = requestedYear,
                            season = requestedSeason,
                            episode = requestedEpisode,
                            movieFingerprint = requestedFingerprint,
                            language = requestedLanguage,
                        )
                        if (generation != searchGeneration) return@launch
                        when (outcome) {
                            is SubtitleSearchOutcome.Found -> results = outcome.results
                            SubtitleSearchOutcome.NoKey -> key = null
                            SubtitleSearchOutcome.Offline -> error = offline
                            SubtitleSearchOutcome.BadKey -> error = refusedKey()
                            SubtitleSearchOutcome.SignInExpired -> {
                                session = null
                                error = signInExpired
                            }
                            SubtitleSearchOutcome.RateLimited -> error = rateLimited
                            SubtitleSearchOutcome.Unavailable -> error = unavailable
                        }
                    } finally {
                        if (generation == searchGeneration) {
                            searching = false
                            searchJob = null
                        }
                    }
                }
            },
        )
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = FlickText.bodySmall.copy(color = colors.trouble))
    }

    quota?.let { left ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = left.resetsIn?.let { reset ->
                stringResource(R.string.subs_online_remaining_reset, left.remaining, reset)
            } ?: stringResource(R.string.subs_online_remaining, left.remaining),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
    }

    results?.let { list ->
        Spacer(Modifier.height(12.dp))
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.subs_online_empty),
                style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
            )
        }
        list.forEach { result ->
            val feature = when {
                result.season != null && result.episode != null ->
                    stringResource(R.string.subs_online_result_episode, result.season, result.episode)
                result.featureType.equals("movie", ignoreCase = true) ->
                    stringResource(R.string.subs_online_result_movie)
                else -> null
            }
            val detail = listOfNotNull(
                stringResource(R.string.subs_online_hash_match).takeIf { result.hashMatch },
                stringResource(R.string.subs_online_foreign_only).takeIf { result.foreignPartsOnly },
                stringResource(R.string.subs_online_machine).takeIf { result.machineTranslated },
                stringResource(R.string.subs_online_ai).takeIf {
                    result.aiTranslated && !result.machineTranslated
                },
                stringResource(R.string.subs_online_trusted).takeIf { result.trusted },
                // A text search answers with whatever its fuzzy match produced, so the work
                // a row belongs to is stated rather than left to be inferred from a release
                // name that looks plausible for any film.
                result.featureParentTitle ?: result.featureTitle ?: result.featureName,
                feature,
                result.featureYear?.let { stringResource(R.string.subs_online_result_year, it) },
                stringResource(R.string.subs_online_sdh).takeIf { result.hearingImpaired },
                result.rating.takeIf { it > 0.0 && result.votes > 0 }
                    ?.let { stringResource(R.string.subs_online_rating, it) },
                languageLabel(result.language),
                stringResource(R.string.subs_online_downloads, result.downloads),
            ).joinToString(" · ")
            SelectableRow(
                title = result.fileName,
                detail = detail,
                detailMaxLines = 2,
                attached = false,
                onClick = {
                    // One download at a time: the API counts every call against the
                    // account's allowance, so a double tap must not spend two of them.
                    if (!downloading) {
                        error = null
                        downloading = true
                        scope.launch {
                            val outcome = client.download(result)
                            downloading = false
                            when (outcome) {
                                is SubtitleFetchOutcome.Ready -> {
                                    quota = outcome.quota
                                    onAttach(outcome.uri, outcome.displayName, outcome.language)
                                }
                                SubtitleFetchOutcome.NoKey -> key = null
                                SubtitleFetchOutcome.Offline -> error = offline
                                SubtitleFetchOutcome.BadKey -> error = refusedKey()
                                SubtitleFetchOutcome.SignInExpired -> {
                                    session = null
                                    error = signInExpired
                                }
                                SubtitleFetchOutcome.RateLimited -> error = rateLimited
                                SubtitleFetchOutcome.QuotaSpent ->
                                    error = if (session != null) quotaOwn else quotaShared
                                SubtitleFetchOutcome.LinkRejected -> error = linkRejected
                                SubtitleFetchOutcome.TooLarge -> error = tooLarge
                                SubtitleFetchOutcome.Unavailable -> error = unavailable
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(16.dp))
    val signedIn = session
    if (signedIn != null) {
        Text(
            text = signedIn.username.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.subs_online_signed_in, it) }
                ?: stringResource(R.string.subs_online_signed_in_anon),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
        FlickSubtleButton(
            text = stringResource(R.string.subs_online_sign_out),
            onClick = {
                // Cleared here and asked of the server after: the token is gone from this
                // phone whatever the network does with the request.
                session = null
                quota = null
                scope.launch { client.signOut() }
            },
        )
    } else {
        Text(
            stringResource(R.string.subs_online_shared),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
        if (!showSignIn) {
            FlickSubtleButton(
                text = stringResource(R.string.subs_online_sign_in),
                onClick = { showSignIn = true },
            )
        } else {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = account,
                onValueChange = { account = it.trim() },
                label = { Text(stringResource(R.string.subs_online_user_label)) },
                singleLine = true,
                shape = RoundedCornerShape(FlickCorners.tuneBtn),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.subs_online_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(FlickCorners.tuneBtn),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.subs_online_password_note),
                style = FlickText.bodySmall.copy(color = colors.onSurfaceFaint),
            )
            Spacer(Modifier.height(12.dp))
            if (signingIn) {
                BusyRow(stringResource(R.string.subs_online_signing_in))
            } else {
                FlickPrimaryButton(
                    text = stringResource(R.string.subs_online_sign_in_action),
                    enabled = account.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        error = null
                        signingIn = true
                        val name = account
                        val secret = password
                        // Dropped from state before the request goes out, not after it
                        // returns: nothing on screen holds a password across a round trip.
                        password = ""
                        scope.launch {
                            val outcome = client.signIn(name, secret)
                            signingIn = false
                            when (outcome) {
                                is SubtitleLoginOutcome.Signed -> {
                                    session = client.session()
                                    showSignIn = false
                                    account = ""
                                }
                                SubtitleLoginOutcome.NoKey -> key = null
                                SubtitleLoginOutcome.Offline -> error = offline
                                SubtitleLoginOutcome.BadKey -> error = refusedKey()
                                SubtitleLoginOutcome.BadCredentials -> error = badSignIn
                                SubtitleLoginOutcome.RateLimited -> error = rateLimited
                                SubtitleLoginOutcome.Unavailable -> error = unavailable
                            }
                        }
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    when {
        key?.source == ApiKeySource.USER -> {
            Text(
                stringResource(R.string.subs_online_key_mine),
                style = FlickText.bodySmall.copy(color = colors.onSurfaceFaint),
            )
            FlickSubtleButton(
                text = stringResource(R.string.subs_online_key_forget),
                onClick = {
                    keys.clearUserKey()
                    // Falls back to the key this build shipped rather than to nothing.
                    key = keys.resolved()
                    results = null
                    quota = null
                    error = null
                },
            )
        }
        showKeyEntry -> KeyEntry(entry = keyEntry, onEntry = { keyEntry = it }, onSave = ::saveOwnKey)
        else -> FlickSubtleButton(
            text = stringResource(R.string.subs_online_key_show),
            onClick = { showKeyEntry = true },
        )
    }
}

@Composable
internal fun OnlineLanguageSelector(
    selected: OpenSubtitlesLanguage,
    enabled: Boolean,
    onSelect: (OpenSubtitlesLanguage) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = stringArrayResource(R.array.subs_online_language_names)
    val selectedLabel = labels[selected.ordinal]
    val accessibilityLabel = stringResource(R.string.a11y_subs_online_language, selectedLabel)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.subs_online_language_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth()
                .semantics { contentDescription = accessibilityLabel },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OpenSubtitlesLanguage.entries.forEach { language ->
                DropdownMenuItem(
                    text = { Text(labels[language.ordinal]) },
                    onClick = {
                        expanded = false
                        onSelect(language)
                    },
                )
            }
        }
    }
}

/** The secondary path: a consumer key the user registered themselves. */
@Composable
private fun ColumnScope.KeyEntry(
    entry: String,
    onEntry: (String) -> Unit,
    onSave: () -> Unit,
) {
    val colors = LocalFlickColors.current
    OutlinedTextField(
        value = entry,
        onValueChange = { onEntry(it.trim()) },
        label = { Text(stringResource(R.string.subs_online_key_label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(FlickCorners.tuneBtn),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.subs_online_key_hint),
        style = FlickText.bodySmall.copy(color = colors.onSurfaceFaint),
    )
    Spacer(Modifier.height(12.dp))
    FlickPrimaryButton(
        text = stringResource(R.string.subs_online_key_save),
        enabled = entry.isNotBlank(),
        onClick = onSave,
    )
}

// --- shared leaves -----------------------------------------------------------------

@Composable
private fun SelectableRow(
    title: String,
    detail: String?,
    detailMaxLines: Int = 1,
    attached: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFlickColors.current
    val interaction = remember { MutableInteractionSource() }
    val description = stringResource(R.string.a11y_subs_attach, title)
    val attachedState = stringResource(R.string.a11y_subs_attached)
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(FlickCorners.statCard))
            .background(colors.fillCard)
            .clickable(
                interactionSource = interaction,
                indication = flickRipple(colors.onSurface),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (attached) stateDescription = attachedState
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = FlickText.bodyMedium.copy(color = colors.onSurface),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it,
                    style = FlickText.monoSmall.copy(color = colors.onSurfaceDim),
                    maxLines = detailMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (attached) {
            Spacer(Modifier.width(10.dp))
            Icon(
                FlickIcons.CheckCircle,
                contentDescription = null,
                tint = colors.spark,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** One in-flight read. Nothing here is determinate: neither SAF nor the API reports a fraction. */
@Composable
private fun BusyRow(label: String) {
    val colors = LocalFlickColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rememberReduceMotion()) {
            // A loop never reaches an end state, so reduce motion gets the resting shape.
            Box(
                Modifier
                    .size(22.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(colors.primary),
            )
        } else {
            LoadingIndicator(modifier = Modifier.size(22.dp), color = colors.primary)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = FlickText.bodyMedium.copy(color = colors.onSurfaceDim))
    }
}

/** The platform's own name for a BCP-47 tag; null when the tag names no language. */
@Composable
private fun languageLabel(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    return remember(tag) {
        runCatching { Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != tag }
    }
}
