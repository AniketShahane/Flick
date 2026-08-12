@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flick.sender.R
import com.flick.sender.media.MovieHash
import com.flick.sender.media.PickRejection
import com.flick.sender.media.SubtitleDocument
import com.flick.sender.media.SubtitleFiles
import com.flick.sender.media.VideoNames
import com.flick.sender.media.subtitlePickRejection
import com.flick.sender.net.FlickController
import com.flick.sender.net.OnlineSubtitle
import com.flick.sender.net.OpenSubtitlesClient
import com.flick.sender.net.OpenSubtitlesKeyStore
import com.flick.sender.net.OpenSubtitlesLanguage
import com.flick.sender.net.OpenSubtitlesSearchPolicy
import com.flick.sender.net.OpenSubtitlesTextState
import com.flick.sender.net.SubtitleFetchOutcome
import com.flick.sender.net.SubtitleQuota
import com.flick.sender.net.SubtitleSearchOutcome
import com.flick.sender.ui.displayName
import com.flick.sender.ui.components.FlickPrimaryButton
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
private enum class SubtitleSource { FILE, ONLINE }

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

/**
 * External subtitles for the video being cast. Forced cinematic, like every surface
 * the remote raises.
 *
 * The local source is one file the user points at, and it has to be: on Android 16 no
 * permission Flick may ask for exposes .srt files, so nothing is ever discovered by
 * scanning. The other fetches from OpenSubtitles with the key this build carries,
 * matched against a fingerprint of the video file itself where the file can be read.
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
    val context = LocalContext.current

    val item by controller.castingItem.collectAsState()
    val videoName = item?.name
    val videoDisplayName = item?.displayName()
    val attached = controller.selectedSubtitle.collectAsState().value
    val attachedName = attached?.displayName
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
    val unnamed = stringResource(R.string.subs_unnamed_file)
    val sizeUnknown = stringResource(R.string.subs_size_unknown)

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
            val name = SubtitleDocument.displayNameOf(context, uri)
            // The size is read whatever the name says, so the pick path and the serving
            // path judge the same file on the same two answers: MediaHttpServer refuses an
            // unmeasurable subtitle with a 404, and accepting one here would attach a
            // track the TV is never allowed to fetch.
            val size = SubtitleDocument.sizeOf(context, uri)
            when (subtitlePickRejection(name, size)) {
                PickRejection.UNNAMED -> onRejected(unnamed)
                PickRejection.WRONG_KIND -> onRejected(notASubtitle)
                PickRejection.UNMEASURABLE -> onRejected(sizeUnknown)
                PickRejection.TOO_LARGE -> onRejected(tooLarge)
                null -> onAttach(
                    uri,
                    name.orEmpty(),
                    SubtitleFiles.languageTagOf(videoName.orEmpty(), name.orEmpty()),
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

// --- source 2: OpenSubtitles, keyed by the app and quota'd by that key --------------

/**
 * The online tab. The key this build shipped is what makes a request legal at all, and the
 * daily allowance behind it is shared with every other install of the same APK — which is
 * why a spent allowance is stated as a fact about the day rather than as a fault.
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
    val keys = remember { OpenSubtitlesKeyStore() }
    val client = remember(context) { OpenSubtitlesClient(context.applicationContext, keys) }
    DisposableEffect(client) { onDispose { client.close() } }

    var key by remember { mutableStateOf(keys.resolved()) }
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
    val badKeyApp = stringResource(R.string.subs_error_key_app)
    val rateLimited = stringResource(R.string.subs_error_rate)
    val quotaShared = stringResource(R.string.subs_error_quota)
    val linkRejected = stringResource(R.string.subs_error_link)
    val unavailable = stringResource(R.string.subs_error_unavailable)
    val notSaved = stringResource(R.string.subs_error_not_saved)
    val tooLarge = stringResource(R.string.subs_too_large)

    // No key at all — the state of this build until OpenSubtitles approves Flick's own
    // consumer key. Nothing here can be repaired from the phone, so it says so plainly
    // and points at the tab that still works.
    if (key == null) {
        Text(
            stringResource(R.string.subs_online_no_key),
            style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
        )
        return
    }

    // No explainer above the form: the tab is named Online, the first control is a
    // language, and what a search does is the one thing on this sheet nobody has to be
    // told. The paragraph that stood here pushed the Search button off a short window,
    // which made the tab look like it had nothing to press.
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
    // All three narrowers on one line. They are the only fields here that take a number
    // rather than a phrase, and a full-width row for four digits was the single largest
    // piece of the height between the title field and the Search button.
    //
    // A third of the sheet leaves about 87 dp for a label on a 412 dp frame, which holds
    // "Episode" — the longest of the three — to a 1.8x font scale, and roughly 1.45x on a
    // 360 dp one. Past that the label clips while the field keeps working: the values are
    // two to four digits and never run out of room, which is why the labels are the thing
    // measured here and the only thing that gives.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            modifier = Modifier.weight(1f),
        )
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
                            SubtitleSearchOutcome.BadKey -> error = badKeyApp
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
            // The language is the highest-value fact an empty result can carry: it is a
            // filter the user set, may not remember setting, and can change in one tap.
            // Which sentence is true depends on what was actually searched — a title, or
            // this exact file's fingerprint.
            val languageName = stringArrayResource(R.array.subs_online_language_names)[language.ordinal]
            Text(
                if (normalizedQuery.state == OpenSubtitlesTextState.READY) {
                    stringResource(R.string.subs_online_empty_language, languageName)
                } else {
                    stringResource(R.string.subs_online_empty_hash, languageName)
                },
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
                    // One download at a time: the API counts every call against the key's
                    // allowance, so a double tap must not spend two of them.
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
                                SubtitleFetchOutcome.BadKey -> error = badKeyApp
                                SubtitleFetchOutcome.RateLimited -> error = rateLimited
                                SubtitleFetchOutcome.QuotaSpent -> error = quotaShared
                                SubtitleFetchOutcome.LinkRejected -> error = linkRejected
                                SubtitleFetchOutcome.TooLarge -> error = tooLarge
                                SubtitleFetchOutcome.NotSaved -> error = notSaved
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
    // Whose allowance this spends, stated once at the foot of the tab. There is nothing to
    // act on — the key is the app's — so it is a note and not an offer.
    Text(
        stringResource(R.string.subs_online_shared),
        style = FlickText.bodySmall.copy(color = colors.onSurfaceDim),
    )
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

/**
 * The platform's own name for a BCP-47 tag; null when the tag names no language. Shared
 * with the Detail sheet, which names the same selection the attached row does.
 */
@Composable
internal fun languageLabel(tag: String?): String? {
    if (tag.isNullOrBlank()) return null
    return remember(tag) {
        runCatching { Locale.forLanguageTag(tag).getDisplayName(Locale.getDefault()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != tag }
    }
}
