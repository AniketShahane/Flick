@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.flick.sender.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.SidecarScan
import com.flick.sender.media.SubtitleCandidate
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.media.SubtitleFolder
import com.flick.sender.media.SubtitleFolderStore
import com.flick.sender.media.SubtitleMatchKind
import com.flick.sender.net.FlickController
import com.flick.sender.net.OnlineSubtitle
import com.flick.sender.net.OpenSubtitlesClient
import com.flick.sender.net.OpenSubtitlesKeyStore
import com.flick.sender.net.SubtitleFetchOutcome
import com.flick.sender.net.SubtitleSearchOutcome
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
 * Everything here is name-based on purpose: on Android 16 no permission Flick may ask
 * for exposes .srt files, so the user either points at one file, grants one folder, or
 * fetches one with their own OpenSubtitles key.
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
    val attached = controller.selectedSubtitle.collectAsState().value
    val attachedName = attached?.displayName
    val attachedUri = attached?.uri
    val attachedLanguage = attached?.language

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
            text = videoName?.let { stringResource(R.string.subs_for_video, it) }
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
                    SubtitleSource.ONLINE -> OnlinePane(
                        videoName = videoName,
                        onAttach = ::attach,
                    )
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

        Spacer(Modifier.height(18.dp))
        val doneInteraction = remember { MutableInteractionSource() }
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
                    onClick = onDismiss,
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

// --- source 3: the user's own OpenSubtitles key ------------------------------------

@Composable
private fun ColumnScope.OnlinePane(
    videoName: String?,
    onAttach: (Uri, String, String?) -> Unit,
) {
    val colors = LocalFlickColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keys = remember(context) { OpenSubtitlesKeyStore(context.applicationContext) }
    val client = remember(context) { OpenSubtitlesClient(context.applicationContext, keys) }
    DisposableEffect(client) { onDispose { client.close() } }

    var hasKey by remember { mutableStateOf(keys.key() != null) }
    var keyEntry by remember { mutableStateOf("") }
    var query by remember(videoName) {
        mutableStateOf(videoName?.let { SubtitleFiles.searchQuery(it) }.orEmpty())
    }
    val marker = remember(videoName) { videoName?.let { SubtitleFiles.episodeOf(it) } }
    var season by remember(videoName) { mutableStateOf(marker?.first?.toString().orEmpty()) }
    var episode by remember(videoName) { mutableStateOf(marker?.second?.toString().orEmpty()) }
    var results by remember { mutableStateOf<List<OnlineSubtitle>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    val offline = stringResource(R.string.subs_error_offline)
    val badKey = stringResource(R.string.subs_error_key)
    val rateLimited = stringResource(R.string.subs_error_rate)
    val quotaSpent = stringResource(R.string.subs_error_quota)
    val unavailable = stringResource(R.string.subs_error_unavailable)
    val tooLarge = stringResource(R.string.subs_too_large)

    if (!hasKey) {
        Text(
            stringResource(R.string.subs_online_no_key),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = keyEntry,
            onValueChange = { keyEntry = it.trim() },
            label = { Text(stringResource(R.string.subs_online_key_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        FlickPrimaryButton(
            text = stringResource(R.string.subs_online_key_save),
            enabled = keyEntry.isNotBlank(),
            onClick = {
                if (keys.save(keyEntry)) {
                    keyEntry = ""
                    hasKey = true
                }
            },
        )
        return
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text(stringResource(R.string.subs_online_query_label)) },
        singleLine = true,
        shape = RoundedCornerShape(FlickCorners.tuneBtn),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = season,
            onValueChange = { season = it.filter(Char::isDigit).take(2) },
            label = { Text(stringResource(R.string.subs_online_season_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = episode,
            onValueChange = { episode = it.filter(Char::isDigit).take(3) },
            label = { Text(stringResource(R.string.subs_online_episode_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(FlickCorners.tuneBtn),
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(12.dp))
    when {
        searching -> BusyRow(stringResource(R.string.subs_online_searching))
        downloading -> BusyRow(stringResource(R.string.subs_online_downloading))
        else -> FlickPrimaryButton(
            text = stringResource(R.string.subs_online_search),
            enabled = query.isNotBlank(),
            onClick = {
                error = null
                searching = true
                scope.launch {
                    val outcome = client.search(query, season.toIntOrNull(), episode.toIntOrNull())
                    searching = false
                    when (outcome) {
                        is SubtitleSearchOutcome.Found -> results = outcome.results
                        SubtitleSearchOutcome.NoKey -> hasKey = false
                        SubtitleSearchOutcome.Offline -> error = offline
                        SubtitleSearchOutcome.BadKey -> error = badKey
                        SubtitleSearchOutcome.RateLimited -> error = rateLimited
                        SubtitleSearchOutcome.Unavailable -> error = unavailable
                    }
                }
            },
        )
    }

    error?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, style = FlickText.bodySmall.copy(color = colors.trouble))
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
            val detail = listOfNotNull(
                languageLabel(result.language),
                stringResource(R.string.subs_online_downloads, result.downloads),
            ).joinToString(" · ")
            SelectableRow(
                title = result.fileName,
                detail = detail,
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
                                is SubtitleFetchOutcome.Ready ->
                                    onAttach(outcome.uri, outcome.displayName, outcome.language)
                                SubtitleFetchOutcome.NoKey -> hasKey = false
                                SubtitleFetchOutcome.Offline -> error = offline
                                SubtitleFetchOutcome.BadKey -> error = badKey
                                SubtitleFetchOutcome.RateLimited -> error = rateLimited
                                SubtitleFetchOutcome.QuotaSpent -> error = quotaSpent
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

    Spacer(Modifier.height(4.dp))
    FlickSubtleButton(
        text = stringResource(R.string.subs_online_key_forget),
        onClick = {
            keys.clear()
            hasKey = false
            results = null
            error = null
        },
    )
}

// --- shared leaves -----------------------------------------------------------------

@Composable
private fun SelectableRow(
    title: String,
    detail: String?,
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
                    maxLines = 1,
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
