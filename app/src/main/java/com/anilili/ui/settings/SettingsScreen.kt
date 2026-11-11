package com.anilili.ui.settings

import com.anilili.ui.theme.canvasColor
import com.anilili.ui.theme.drawAppCanvas
import com.anilili.ui.theme.LocalAppBackdrop
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.draw.clip
import androidx.annotation.DrawableRes
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.anilili.data.provider.remote.config.RemoteProviderManager
import com.anilili.data.provider.remote.config.RemoteProviderConfigStore
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import com.anilili.BuildConfig
import com.anilili.data.ProviderCatalog
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.auth.MalAuthManager
import com.anilili.data.auth.OpenSubtitlesAuthManager
import com.anilili.data.backup.AppBackup
import com.anilili.data.backup.BackupCredentials
import com.anilili.data.backup.BackupDocument
import com.anilili.data.backup.BackupManager
import com.anilili.data.backup.MalHandoff
import com.anilili.data.cache.CacheManager
import com.anilili.data.discord.DiscordAuthManager
import com.anilili.data.discord.DiscordRedirect
import com.anilili.data.discord.DiscordRedirectBus
import com.anilili.data.library.LibraryStore
import com.anilili.data.library.MalExportFile
import com.anilili.data.library.MalImport
import com.anilili.data.reminder.AiringPollScheduler
import com.anilili.data.reminder.AutomaticReleaseManager
import com.anilili.data.reminder.ReleaseSyncScheduler
import com.anilili.data.remote.DohProvider
import com.anilili.data.settings.ClockFormat
import com.anilili.data.settings.DiscordStatus
import com.anilili.data.settings.DEFAULT_PLAYBACK_SPEED
import com.anilili.data.settings.DefaultQuality
import com.anilili.data.settings.DownloadDestination
import com.anilili.data.settings.DownloadQuality
import com.anilili.data.settings.FormFactorPreference
import com.anilili.data.settings.MAX_SERVER_PRIORITY
import com.anilili.data.settings.MenuLanguage
import com.anilili.data.settings.PlaybackBufferSize
import com.anilili.data.settings.VideoCacheSize
import com.anilili.data.settings.PlayerContentScale
import com.anilili.data.settings.PosterElement
import com.anilili.data.settings.PreferredAudioLanguage
import com.anilili.data.settings.PreferredSubtitleLanguage
import com.anilili.data.settings.SKIP_BUTTON_MAX_SECONDS
import com.anilili.data.settings.SKIP_BUTTON_MIN_SECONDS
import com.anilili.R
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.SourceFilterLanguage
import com.anilili.data.settings.SubtitleSearchLanguage
import com.anilili.data.settings.ThemeAccent
import com.anilili.data.settings.ThemeBackground
import com.anilili.data.settings.AppBackdropDim
import com.anilili.data.settings.AppBackdropSource
import com.anilili.data.settings.ThemeContrast
import com.anilili.data.settings.ThemePreset
import com.anilili.data.settings.ThemeStyle
import com.anilili.data.settings.UiScale
import com.anilili.data.settings.MAX_TV_OVERSCAN_PERCENT
import com.anilili.data.settings.WATCHED_THRESHOLD_MAX_PERCENT
import com.anilili.data.settings.WATCHED_THRESHOLD_MIN_PERCENT
import com.anilili.data.update.UpdateManager
import com.anilili.diagnostics.DiagnosticShareResult
import com.anilili.diagnostics.DiagnosticSubmissionDialog
import com.anilili.diagnostics.DiagnosticTrigger
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.diagnostics.DiagnosticsShareManager
import com.anilili.errors.AnililiError
import com.anilili.errors.asFailure
import com.anilili.errors.raise
import com.anilili.errors.report
import com.anilili.errors.toFailure
import com.anilili.playback.DeviceDownloadAccess
import com.anilili.playback.DownloadLocation
import com.anilili.playback.DownloadLocationOption
import com.anilili.playback.DownloadLocations
import com.anilili.playback.ExternalPlayerTarget
import com.anilili.playback.MediaCache
import com.anilili.playback.externalPlayerLabel
import com.anilili.playback.externalPlayerTargets
import com.anilili.playback.supportsMiniPlayer
import com.anilili.ui.UiState
import com.anilili.ui.adaptive.FocusFillColor
import com.anilili.ui.adaptive.LocalAppDeviceProfile
import com.anilili.ui.adaptive.focusFill
import com.anilili.ui.adaptive.rememberFocusFill
import com.anilili.ui.adaptive.rememberScreenReaderActive
import com.anilili.ui.components.CaptionAppearanceDialog
import com.anilili.ui.components.CompiledSeparately
import com.anilili.ui.components.LocalAppChromeBottomInset
import com.anilili.ui.components.LocalAppChromeReveal
import com.anilili.ui.components.LocalSystemUses24Hour
import com.anilili.ui.components.PageTitleBar
import com.anilili.ui.components.ScrollAwareTopBar
import com.anilili.ui.dialogs.LocalMidnightSettingSpotlight
import com.anilili.ui.dialogs.MidnightChoicePicker
import com.anilili.ui.dialogs.MidnightChoiceRow
import com.anilili.ui.dialogs.MidnightConfirmDialog
import com.anilili.ui.dialogs.MidnightDialogFooter
import com.anilili.ui.dialogs.MidnightDialogHost
import com.anilili.ui.dialogs.MidnightFilledAction
import com.anilili.ui.dialogs.MidnightHelper
import com.anilili.ui.dialogs.MidnightInlineNotice
import com.anilili.ui.dialogs.MidnightMediaHeader
import com.anilili.ui.dialogs.MidnightModal
import com.anilili.ui.dialogs.MidnightModalTitle
import com.anilili.ui.dialogs.MidnightNoticeTone
import com.anilili.ui.dialogs.MidnightQuietAction
import com.anilili.ui.dialogs.MidnightSelectionRow
import com.anilili.ui.dialogs.MidnightSettingRow
import com.anilili.ui.dialogs.MidnightSettingSpotlight
import com.anilili.ui.dialogs.MidnightSettingSwitchRow
import com.anilili.ui.dialogs.MidnightStepperRow
import com.anilili.ui.pairing.PhonePairingScreen
import com.anilili.ui.profile.AniListProfile
import com.anilili.ui.profile.LoginWebView
import com.anilili.ui.profile.MalImportProgress
import com.anilili.ui.profile.MalImportStage
import com.anilili.ui.profile.ProfileViewModel
import com.anilili.ui.profile.confirmSignedIn
import com.anilili.ui.theme.LocalMidnightAccent
import com.anilili.ui.theme.Midnight
import com.anilili.ui.theme.swatch
import com.anilili.ui.theme.wallpaperAccentAvailable
import com.anilili.ui.theme.wallpaperSeed
import com.anilili.ui.watch.PlaybackSpeeds
import com.anilili.ui.watch.formatPlaybackSpeed
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.anilili.data.settings.GlassSection
import com.anilili.ui.components.backdropBlurAvailable
import com.anilili.ui.dialogs.MidnightSectionLabel
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import com.anilili.ui.components.LocalAppChromeVisible
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind

/** A MyAnimeList authorization being run so a backup can carry a sign-in for another device. */
private data class MalHandoffRequest(
    val state: String,
    val verifier: String,
    /** Held across the authorization so the file can be sealed the moment the code arrives. */
    val passphrase: String,
)

/**
 * The Video cache row's value: the choice, and beside it the size it came to whenever that is not
 * simply the choice — always for Automatic, and for a fixed size free space has cut down.
 */
private fun videoCacheValue(context: Context, size: VideoCacheSize, budget: Long?): String {
    val applied = budget?.takeIf { size != VideoCacheSize.OFF && (size.bytes == null || it < size.bytes) }
        ?: return size.label
    return "${size.label} · ${Formatter.formatShortFileSize(context, applied)}"
}

/** Which choice picker is open, if any. One value beats a dozen independent booleans. */
private enum class SettingsPicker {
    SKIP_BUTTON_LENGTH,
    WATCHED_THRESHOLD,
    DEFAULT_QUALITY,
    PLAYBACK_BUFFER_SIZE,
    VIDEO_CACHE_SIZE,
    DEFAULT_PLAYBACK_SPEED,
    HOLD_PLAYBACK_SPEED,
    AUDIO_LANGUAGE,
    SECONDARY_AUDIO_LANGUAGE,
    CONTENT_SCALE,
    EXTERNAL_PLAYER,
    DOWNLOAD_QUALITY,
    DOWNLOAD_DESTINATION,
    DOWNLOAD_LOCATION,
    ENABLED_SERVERS,
    SERVER_PRIORITY,
    SOURCE_LANGUAGES,
    DOH_PROVIDER,
    CUSTOM_SOURCES,
    SUBTITLE_LANGUAGE,
    SECONDARY_SUBTITLE_LANGUAGE,
    SUBTITLE_SEARCH_LANGUAGE,
    MENU_LANGUAGE,
    CLOCK_FORMAT,
    DISCORD_STATUS,
    UI_SCALE,
    TV_OVERSCAN,
    THEME_BACKGROUND,
    THEME_ACCENT,
    THEME_STYLE,
    THEME_CONTRAST,
    THEME_CUSTOM,
    THEME_BACKDROP,
    THEME_BACKDROP_ANIME,
    THEME_BACKDROP_IMAGE_TV,
    THEME_BACKDROP_DIM,
    FORM_FACTOR,
    POSTER_ELEMENTS,
    HOME_LAYOUT,
}

/**
 * Settings as an editorial list (design/midnight-anime/design.md §22, §45–46).
 *
 * Every row is title, one line, current value. The explanations that used to sit under each row as
 * a grey paragraph — and the chip walls that came with them — now live inside the picker the row
 * opens, which is what makes the page scannable rather than a wall of collapsed groups.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: ProfileViewModel = viewModel(),
) {
    val context = LocalContext.current
    val device = LocalAppDeviceProfile.current
    val screenReaderActive = rememberScreenReaderActive()
    val token by AuthManager.token.collectAsState()
    val malLoggedIn by MalAuthManager.loggedIn.collectAsState()
    val openSubtitlesLoggedIn by OpenSubtitlesAuthManager.loggedIn.collectAsState()
    val discordLoggedIn by DiscordAuthManager.loggedIn.collectAsState()
    val discordAccount by DiscordAuthManager.account.collectAsState()
    val discordRichPresence by SettingsStore.discordRichPresence.collectAsState()
    val discordStatus by SettingsStore.discordStatus.collectAsState()
    val subtitleSearchLanguages by SettingsStore.subtitleSearchLanguages.collectAsState()
    val openSubtitlesUsername by OpenSubtitlesAuthManager.username.collectAsState()
    val profileState by vm.profile.collectAsState()
    val history by LibraryStore.history.collectAsState()
    val watchlist by LibraryStore.watchlist.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val playOnOpen by SettingsStore.playOnOpen.collectAsState()
    val openEpisodesInExternalPlayer by SettingsStore.openEpisodesInExternalPlayer.collectAsState()
    val externalPlayerPackage by SettingsStore.externalPlayerPackage.collectAsState()
    // A package-manager lookup, so it is read once per choice rather than once per composition.
    val externalPlayerName = remember(externalPlayerPackage) {
        externalPlayerLabel(context, externalPlayerPackage)
    }
    val pictureInPicture by SettingsStore.pictureInPicture.collectAsState()
    // A device without the system feature never shows the window, so the switch would be a promise
    // nothing keeps. Read once: a device does not acquire picture-in-picture while Settings is open.
    val miniPlayerSupported = remember(context) { supportsMiniPlayer(context) }
    val pauseDetailsOverlay by SettingsStore.pauseDetailsOverlay.collectAsState()
    val autoSkip by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoSkipFiller by SettingsStore.autoSkipFiller.collectAsState()
    val showSkipButton by SettingsStore.showSkipButton.collectAsState()
    val skipButtonSeconds by SettingsStore.skipButtonSeconds.collectAsState()
    val autoSync by SettingsStore.autoSyncAniList.collectAsState()
    val watchedThresholdPercent by SettingsStore.watchedThresholdPercent.collectAsState()
    val preferDub by SettingsStore.preferDub.collectAsState()
    val defaultQuality by SettingsStore.defaultQuality.collectAsState()
    val playbackBufferSize by SettingsStore.playbackBufferSize.collectAsState()
    val videoCacheSize by SettingsStore.videoCacheSize.collectAsState()
    val videoCacheApplied by MediaCache.appliedBytes.collectAsState()
    val defaultPlaybackSpeed by SettingsStore.defaultPlaybackSpeed.collectAsState()
    val holdToSpeedEnabled by SettingsStore.holdToSpeedEnabled.collectAsState()
    val holdPlaybackSpeed by SettingsStore.holdPlaybackSpeed.collectAsState()
    val preferredAudioLanguage by SettingsStore.preferredAudioLanguage.collectAsState()
    val preferredSubtitleLanguage by SettingsStore.preferredSubtitleLanguage.collectAsState()
    val secondaryAudioLanguage by SettingsStore.secondaryAudioLanguage.collectAsState()
    val secondarySubtitleLanguage by SettingsStore.secondarySubtitleLanguage.collectAsState()
    val playerContentScale by SettingsStore.playerContentScale.collectAsState()
    val uiScale by SettingsStore.uiScale.collectAsState()
    val tvOverscanPercent by SettingsStore.tvOverscanPercent.collectAsState()
    val themeBackground by SettingsStore.themeBackground.collectAsState()
    val themeAccent by SettingsStore.themeAccent.collectAsState()
    val themeStyle by SettingsStore.themeStyle.collectAsState()
    val themeContrast by SettingsStore.themeContrast.collectAsState()
    val playerAccent by SettingsStore.playerAccent.collectAsState()
    val appIconMatchesTheme by SettingsStore.appIconMatchesTheme.collectAsState()
    val themeCustomSeed by SettingsStore.themeCustomSeed.collectAsState()
    val showColourPages by SettingsStore.showColourPages.collectAsState()
    val appBackdropSource by SettingsStore.appBackdropSource.collectAsState()
    val appBackdropAnimeUrl by SettingsStore.appBackdropAnimeUrl.collectAsState()
    val appBackdropAnimeTitle by SettingsStore.appBackdropAnimeTitle.collectAsState()
    val appBackdropImage by SettingsStore.appBackdropImage.collectAsState()
    val appBackdropDim by SettingsStore.appBackdropDim.collectAsState()
    val appBackdropBlur by SettingsStore.appBackdropBlur.collectAsState()
    val pickBackdropPhoto = rememberBackdropPhotoPicker { problem ->
        android.widget.Toast.makeText(context, problem, android.widget.Toast.LENGTH_LONG).show()
    }
    val wallpaperSeedColor = remember(device.isTv) {
        if (wallpaperAccentAvailable(device.isTv)) wallpaperSeed(context) else null
    }
    val formFactorPreference by SettingsStore.formFactorPreference.collectAsState()
    val playerGestures by SettingsStore.playerGestures.collectAsState()
    val playerSeekGesture by SettingsStore.playerSeekGesture.collectAsState()
    val playerStatusBar by SettingsStore.playerStatusBar.collectAsState()
    val playerClock by SettingsStore.playerClock.collectAsState()
    val tvDoubleBackToExit by SettingsStore.tvDoubleBackToExit.collectAsState()
    val serverPriority by SettingsStore.serverPriority.collectAsState()
    val disabledProviders by SettingsStore.disabledProviders.collectAsState()
    val preferredSourceLanguages by SettingsStore.preferredSourceLanguages.collectAsState()
    val dohProvider by SettingsStore.dohProvider.collectAsState()
    val downloadQuality by SettingsStore.downloadQuality.collectAsState()
    val downloadDestination by SettingsStore.downloadDestination.collectAsState()
    val releaseNotifications by SettingsStore.releaseNotifications.collectAsState()
    val announcements by SettingsStore.announcements.collectAsState()
    val hideAdultContent by SettingsStore.hideAdultContent.collectAsState()
    val hideAiringTomorrowFromContinue by SettingsStore.hideAiringTomorrowFromContinue.collectAsState()
    val blurEpisodeImages by SettingsStore.blurEpisodeImages.collectAsState()
    val showSeriesLogos by SettingsStore.showSeriesLogos.collectAsState()
    val hiddenPosterElements by SettingsStore.hiddenPosterElements.collectAsState()
    val homeLayout by SettingsStore.homeLayout.collectAsState()
    val subtitlesWithDub by SettingsStore.subtitlesWithDub.collectAsState()
    val subtitlesByDefault by SettingsStore.subtitlesByDefault.collectAsState()
    val updateCheckOnLaunch by SettingsStore.updateCheckOnLaunch.collectAsState()
    val staticAppChrome by SettingsStore.staticAppChrome.collectAsState()
    val searchAsYouType by SettingsStore.searchAsYouType.collectAsState()
    val videoLaunchIntro by SettingsStore.videoLaunchIntro.collectAsState()
    val syncSavedToAniList by SettingsStore.syncSavedToAniList.collectAsState()
    val menuLanguage by SettingsStore.menuLanguage.collectAsState()
    val clockFormat by SettingsStore.clockFormat.collectAsState()
    val systemUses24Hour = LocalSystemUses24Hour.current
    val updateState by UpdateManager.state.collectAsState()
    val quietUpdate by UpdateManager.quietUpdate.collectAsState()
    val pendingUpdate by UpdateManager.pendingUpdate.collectAsState()
    // The section holding "Check for updates" carries the same badge the notification bell does,
    // so an update the viewer dismissed — or a rebuild that never popped up — still shows.
    val badgedSections = if (pendingUpdate != null) setOf("app") else emptySet()
    val profile = (profileState as? UiState.Success<AniListProfile>)?.data
    val scope = rememberCoroutineScope()
    val perScreen = remember { SettingsScreenState() }
    var pendingMalExport by perScreen.pendingMalExport
    var malExportBusy by perScreen.malExportBusy
    var malExportMessage by perScreen.malExportMessage
    var malImportBusy by perScreen.malImportBusy
    var malImportMessage by perScreen.malImportMessage
    var malImportProgress by perScreen.malImportProgress
    var malImportJob by perScreen.malImportJob
    var loginService by perScreen.loginService
    var tvPairingVisible by perScreen.tvPairingVisible
    var diagnosticsMessage by perScreen.diagnosticsMessage
    var diagnosticsBusy by perScreen.diagnosticsBusy
    var diagnosticsDialogVisible by perScreen.diagnosticsDialogVisible
    var diagnosticsError by perScreen.diagnosticsError
    var captionAppearanceVisible by perScreen.captionAppearanceVisible
    var openSubtitlesAccountVisible by perScreen.openSubtitlesAccountVisible
    var openSubtitlesAccountBusy by perScreen.openSubtitlesAccountBusy
    var openSubtitlesAccountError by perScreen.openSubtitlesAccountError
    var openSubtitlesAccountMessage by perScreen.openSubtitlesAccountMessage
    // Non-null while the Discord sign-in is running in the in-app WebView rather than the browser.
    var discordLoginUrl by perScreen.discordLoginUrl
    var discordBusy by perScreen.discordBusy
    var discordMessage by perScreen.discordMessage
    var discordError by perScreen.discordError
    var discordDisconnectVisible by perScreen.discordDisconnectVisible
    var malTagsVisible by perScreen.malTagsVisible
    var cacheUsage by perScreen.cacheUsage
    var videoCacheBudget by perScreen.videoCacheBudget
    var cacheClearing by perScreen.cacheClearing
    var cacheMessage by perScreen.cacheMessage
    // The document is built before the file picker opens, so it describes the app as it was when
    // the viewer asked rather than as it is after they spend a minute choosing a folder.
    var pendingBackup by perScreen.pendingBackup
    var backupBusy by perScreen.backupBusy
    var backupMessage by perScreen.backupMessage
    var backupFailed by perScreen.backupFailed
    var backupOptionsVisible by perScreen.backupOptionsVisible
    // A MyAnimeList authorization in progress on behalf of whoever restores the file.
    var malHandoffRequest by perScreen.malHandoffRequest
    var backupMalSkipped by perScreen.backupMalSkipped
    // A backup that has been read and understood, waiting on the confirmation before it is applied.
    var pendingRestore by perScreen.pendingRestore
    var restoreBusy by perScreen.restoreBusy
    var restoreMessage by perScreen.restoreMessage
    var restoreTone by perScreen.restoreTone
    var restorePassphrase by perScreen.restorePassphrase
    var restorePassphraseError by perScreen.restorePassphraseError
    var pendingDownloadDestination by perScreen.pendingDownloadDestination
    var downloadDestinationMessage by perScreen.downloadDestinationMessage
    val downloadLocation by DownloadLocations.selected(context).collectAsState()
    // Read when the picker opens rather than once per composition: a memory card can be plugged in
    // while the app is open, and the list is a handful of binder calls plus a stat per volume.
    var downloadLocationOptions by perScreen.downloadLocationOptions
    var pendingDownloadLocation by perScreen.pendingDownloadLocation
    var downloadLocationMessage by perScreen.downloadLocationMessage
    // Read when the picker opens, for the same reason the save-location list is: a video player can
    // be installed while this screen is open, and a stale list is how "mpvNova is not there" starts.
    // Null until the query has run, so the "nothing is installed" notice below cannot flash on the
    // frame before the answer arrives — which on a device with players would be a plain lie.
    var externalPlayerOptions by perScreen.externalPlayerOptions
    var openPicker by perScreen.openPicker
    // Which section the page is inside, by id rather than by title so it survives the menu language
    // renaming the App section. Null is the index: a grid of every category, which is what the page
    // opens as instead of forty rows you have to scroll to survey.
    val device0 = LocalAppDeviceProfile.current
    var openSectionId by rememberSaveable {
        mutableStateOf<String?>(if (device0.isTv) "player" else null)
    }
    // The row a search asked for, marked inside the section that search just opened. Held here
    // rather than inside the field so it outlives the field: picking a result is what closes the
    // index, and the mark has to survive that to be worth anything.
    var spotlightRow by rememberSaveable { mutableStateOf<String?>(null) }
    // Every way into and out of a section goes through here, so a mark can never be left behind on
    // a page it does not belong to.
    fun goToSection(id: String?, spotlight: String? = null) {
        openSectionId = id
        spotlightRow = spotlight
    }

    // The floating navigation capsule covers the bottom of the window, so a marked row scrolled up
    // from below has to ask to clear it or it arrives half behind the glass.
    val chromeInset = LocalAppChromeBottomInset.current
    val spotlight = remember(spotlightRow, chromeInset) {
        spotlightRow?.let { title ->
            MidnightSettingSpotlight(title, chromeInset + 16.dp) { spotlightRow = null }
        }
    }
    // Same television rule the tiles follow: no Display section, so nothing may search into one.
    val searchEntries = remember(device.isTv) { settingsSearchEntries(isTv = device.isTv) }
    var searchPaneVisible by perScreen.searchPaneVisible
    val sectionTiles = remember(device.isTv, menuLanguage) {
        buildList {
            add(SettingsSectionTile("player", "Player", Icons.Outlined.PlayCircleOutline, "Playback, external players, and skip preferences"))
            add(SettingsSectionTile("subtitles", "Subtitles", Icons.Outlined.ClosedCaption, "Languages, appearance, and timing"))
            add(SettingsSectionTile("downloads", "Downloads", Icons.Outlined.FileDownload, "Resolution, where episodes are saved, and storage"))
            add(SettingsSectionTile("servers", "Servers", Icons.Outlined.Dns, "Which sources are used, and in what order"))
            add(
                SettingsSectionTile(
                    "accounts",
                    "Accounts & sync",
                    Icons.Outlined.SyncAlt,
                    if (device.isTv) "AniList and MAL sign-in, and what is synced" else "AniList and MAL sign-in, linking a TV, and what is synced",
                ),
            )
            add(SettingsSectionTile("notifications", "Notifications", Icons.Outlined.Notifications, "New episodes, announcements, and updates"))
            add(SettingsSectionTile("content", "Content", Icons.Outlined.VisibilityOff, "What the catalogue shows and what it hides"))
            // Every form factor: the theme is for everyone. Interface size and Spoken feedback inside
            // it stay television-only — a phone reaches both through the system's own settings.
            add(SettingsSectionTile("appearance", "Appearance", Icons.Outlined.Palette, "Theme, colours, layout, and how cards look"))
            add(SettingsSectionTile("data", "Data", Icons.Outlined.Storage, "Caches, network, and diagnostics"))
            add(
                SettingsSectionTile(
                    id = "app",
                    title = if (menuLanguage.usesSpanish()) "Aplicación" else "App",
                    icon = Icons.Outlined.Tune,
                    summary = "Language, updates, and how the app behaves",
                ),
            )
            add(SettingsSectionTile("about", "About", Icons.Outlined.Info, "Version, updates, and the people behind it"))
        }
    }
    val openSection = sectionTiles.firstOrNull { it.id == openSectionId }
    // A section could leave the list underneath the page (the television-only Display section once
    // did), and an id with no tile would strand the page on a screen with no title and no rows.
    // A section is a view in place, not a route, so the root's per-route reset never fires here.
    val revealChrome = LocalAppChromeReveal.current
    LaunchedEffect(openSectionId) { revealChrome() }
    LaunchedEffect(openSectionId, openSection) {
        if (openSectionId != null && openSection == null) goToSection(null)
    }
    BackHandler(enabled = openSectionId != null && !device.isTv) { goToSection(null) }

    LaunchedEffect(token, malLoggedIn) { vm.loadIfLoggedIn() }
    // Re-measured when the size changes and again once the open cache has been trimmed to it,
    // because a smaller size deletes files and the usage beside it would otherwise be stale.
    LaunchedEffect(videoCacheSize, videoCacheApplied) {
        videoCacheBudget = withContext(Dispatchers.IO) { MediaCache.budgetBytes(context) }
        cacheUsage = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
    }

    /**
     * Finishes a Discord sign-in from whichever surface delivered the redirect.
     *
     * Connecting turns the switch on. The account is only ever connected *in order to* show a
     * status, so leaving it off would mean two deliberate steps to get one intended outcome — and
     * the switch is right below, for the moment that stops being what the viewer wants.
     */
    fun completeDiscordLogin(redirectUrl: String) {
        discordBusy = true
        discordError = null
        scope.launch {
            DiscordAuthManager.completeAuthorization(redirectUrl)
                .onSuccess { account ->
                    SettingsStore.setDiscordRichPresence(true)
                    discordMessage = account.displayName
                        .takeIf { it.isNotBlank() && it != "Connected" }
                        ?.let { "Discord connected as $it" }
                        ?: "Discord connected"
                }
                .onFailure { error ->
                    discordError = error.message ?: "Discord sign-in failed."
                }
            discordBusy = false
        }
    }

    // The browser hand-off can outlive this process, so the answer may already be waiting by the
    // time anything collects — which is what the bus's replay buffer is for.
    LaunchedEffect(Unit) {
        DiscordRedirectBus.redirects.collect { redirectUrl ->
            DiscordRedirectBus.consume()
            if (DiscordAuthManager.isRedirect(redirectUrl)) completeDiscordLogin(redirectUrl)
        }
    }

    /**
     * Opens the Discord consent page, in the system browser on a phone that has one.
     *
     * The browser is preferred on a phone because the viewer is usually already signed in to
     * Discord there and only has to press Authorize — no password typed into a window this app
     * drew. A television always takes the in-app WebView with the loopback redirect, exactly as
     * the AniList and MyAnimeList sign-ins do, even when something answers `https://`: TV
     * browsers are typically WebView shells that load the `discord-<id>:` redirect themselves
     * instead of handing it to Android, and strand the viewer on `ERR_UNKNOWN_URL_SCHEME` with a
     * perfectly good code in the address bar. Nobody is signed in to Discord in one anyway.
     */
    fun startDiscordLogin() {
        discordError = null
        discordMessage = null
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://discord.com"))
        val hasBrowser = !device.isTv && context.packageManager.resolveActivity(browserIntent, 0) != null
        if (hasBrowser) {
            val url = DiscordAuthManager.authorizeUrl(DiscordRedirect.BROWSER)
            val opened = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (opened) return
        }
        discordLoginUrl = DiscordAuthManager.authorizeUrl(DiscordRedirect.LOOPBACK)
    }

    CompiledSeparately {
    if (discordDisconnectVisible) {
        MidnightConfirmDialog(
            kicker = "Discord",
            title = "Disconnect Discord?",
            body = "Your status stops showing immediately, and the token this device holds is " +
                "revoked with Discord rather than merely forgotten here.",
            confirmText = "Disconnect",
            onConfirm = {
                discordDisconnectVisible = false
                scope.launch {
                    SettingsStore.setDiscordRichPresence(false)
                    DiscordAuthManager.logout()
                    discordMessage = "Discord disconnected"
                }
            },
            onDismiss = { discordDisconnectVisible = false },
        )
    }
    }

    CompiledSeparately {
    if (malTagsVisible) {
        MalTagsDialog(onDismiss = { malTagsVisible = false })
    }
    }

    CompiledSeparately {
    if (captionAppearanceVisible) {
        CaptionAppearanceDialog(
            onDismiss = { captionAppearanceVisible = false },
            footnote = "Applies to the built-in player. Servers that play in a web view render " +
                "their own subtitles and may ignore some of these.",
        )
    }
    }
    CompiledSeparately {
    if (openSubtitlesAccountVisible) {
        OpenSubtitlesAccountDialog(
            loggedIn = openSubtitlesLoggedIn,
            currentUsername = openSubtitlesUsername,
            busy = openSubtitlesAccountBusy,
            errorMessage = openSubtitlesAccountError,
            onDismiss = {
                if (!openSubtitlesAccountBusy) {
                    openSubtitlesAccountVisible = false
                    openSubtitlesAccountError = null
                }
            },
            onSignIn = { username, password ->
                scope.launch {
                    openSubtitlesAccountBusy = true
                    openSubtitlesAccountError = null
                    runCatching { OpenSubtitlesAuthManager.signIn(username, password) }
                        .onSuccess {
                            openSubtitlesAccountMessage = "OpenSubtitles connected as $username"
                            openSubtitlesAccountVisible = false
                        }
                        .onFailure { error ->
                            openSubtitlesAccountError = error.message ?: "OpenSubtitles sign-in failed."
                        }
                    openSubtitlesAccountBusy = false
                }
            },
            onSignOut = {
                OpenSubtitlesAuthManager.logout()
                openSubtitlesAccountMessage = "OpenSubtitles disconnected"
                openSubtitlesAccountError = null
                openSubtitlesAccountVisible = false
            },
        )
    }
    }
    CompiledSeparately {
    if (diagnosticsDialogVisible) {
        DiagnosticSubmissionDialog(
            title = "Send diagnostics",
            introduction = "Describe the problem so the report has useful context. The app creates a " +
                "redacted diagnostic ZIP, then you choose where to send it with Android's share sheet.",
            descriptionRequired = true,
            sending = diagnosticsBusy,
            errorMessage = diagnosticsError,
            onDismiss = {
                diagnosticsDialogVisible = false
                diagnosticsError = null
            },
            onSend = { submission ->
                diagnosticsBusy = true
                diagnosticsError = null
                diagnosticsMessage = "Preparing the full diagnostic report…"
                scope.launch {
                    try {
                        val result = DiagnosticsShareManager.share(
                            context,
                            DiagnosticTrigger.MANUAL,
                            submission,
                        )
                        if (result is DiagnosticShareResult.Failed) {
                            diagnosticsError = result.userMessage()
                            diagnosticsMessage = null
                        } else {
                            diagnosticsMessage = result.userMessage()
                            diagnosticsDialogVisible = false
                        }
                    } finally {
                        diagnosticsBusy = false
                    }
                }
            },
        )
    }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        SettingsStore.setReleaseNotifications(granted)
        AiringPollScheduler.sync(context, granted)
        if (granted) {
            ReleaseSyncScheduler.runNow(context)
            AiringPollScheduler.runNow(context)
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val destination = pendingDownloadDestination
        pendingDownloadDestination = null
        if (granted && destination != null) {
            SettingsStore.setDownloadDestination(destination)
            downloadDestinationMessage = null
        } else {
            SettingsStore.setDownloadDestination(DownloadDestination.APP_ONLY)
            downloadDestinationMessage =
                "Storage permission is needed for Device Downloads on Android 9 and earlier."
        }
    }
    // Only the public Downloads folder needs Android 6–9's permission. Choosing a card or a picked
    // folder instead is the way past this prompt, so a refusal leaves the old location alone rather
    // than dropping the viewer back to the app library.
    val downloadLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val location = pendingDownloadLocation
        pendingDownloadLocation = null
        if (granted && location != null) {
            DownloadLocations.setLocation(context, location)
            downloadLocationMessage = null
        } else {
            downloadLocationMessage =
                "Storage permission is needed to write to the Downloads folder on Android 9 and " +
                    "earlier. A memory card or a folder you pick needs no permission."
        }
    }
    val downloadFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        downloadLocationMessage = if (DownloadLocations.rememberPickedFolder(context, uri)) {
            null
        } else {
            "That folder could not be kept as the save location. Try another one."
        }
    }
    val malExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/xml"),
    ) { uri ->
        val file = pendingMalExport
        pendingMalExport = null
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(file.xml.toByteArray(Charsets.UTF_8))
            } ?: AnililiError.BACKUP_FILE_UNWRITABLE.raise(
                detail = "The document picker returned a file nothing could be written to.",
            )
        }.onSuccess {
            malExportMessage = buildString {
                append("Exported ${file.exportedCount} anime")
                if (file.skippedCount > 0) append("; skipped ${file.skippedCount} without MAL IDs")
            }
        }.onFailure { error ->
            malExportMessage = error.toFailure(AnililiError.BACKUP_FILE_UNWRITABLE).withCode()
        }
    }
    // MAL serves exports as .xml.gz, which file pickers report under assorted mime types, so the
    // filter stays wide open and the parser decides.
    val malImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            DiagnosticsLog.event("mal_import", "picker.cancelled")
            return@rememberLauncherForActivityResult
        }
        malImportJob = scope.launch {
            malImportBusy = true
            malImportMessage = null
            malImportProgress = MalImportProgress(MalImportStage.READING)
            try {
                val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                val reportedBytes = context.reportedFileSize(uri)
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "picker.file_selected",
                    attributes = mapOf(
                        "mimeType" to (mimeType ?: "unknown"),
                        "reportedBytes" to (reportedBytes ?: -1L),
                    ),
                )
                if (reportedBytes != null && reportedBytes > MalImport.MAX_SOURCE_BYTES) {
                    AnililiError.BACKUP_NOT_A_MAL_EXPORT.raise(
                        detail = "The chosen file is ${reportedBytes / 1_048_576} MB, past the " +
                            "${MalImport.MAX_SOURCE_BYTES / 1_048_576} MB safety limit for an export.",
                    )
                }
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        MalImport.readSource(stream)
                    }
                        ?: AnililiError.BACKUP_FILE_UNREADABLE.raise(
                            detail = "The document picker returned a file nothing could be read from.",
                        )
                }
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "picker.file_read",
                    attributes = mapOf("actualBytes" to bytes.size),
                )
                val summary = vm.importMalXml(bytes) { progress ->
                    malImportProgress = progress
                }
                malImportMessage = buildString {
                    append("Imported ${summary.added} new anime")
                    if (summary.alreadySaved > 0) append("; ${summary.alreadySaved} already saved")
                    if (summary.unmatched > 0) append("; ${summary.unmatched} not found on AniList")
                }
            } catch (error: CancellationException) {
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "ui.cancelled",
                    attributes = mapOf("stage" to (malImportProgress?.stage?.name?.lowercase() ?: "reading")),
                )
                malImportMessage = "MyAnimeList import cancelled"
                throw error
            } catch (error: Exception) {
                DiagnosticsLog.event(
                    category = "mal_import",
                    name = "ui.failed",
                    attributes = mapOf(
                        "stage" to (malImportProgress?.stage?.name?.lowercase() ?: "reading"),
                        "errorType" to error.javaClass.simpleName,
                        "message" to (error.message ?: "none"),
                    ),
                )
                malImportMessage = error.toFailure(AnililiError.BACKUP_NOT_A_MAL_EXPORT).withCode()
            } finally {
                malImportProgress = null
                malImportBusy = false
                malImportJob = null
            }
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(AppBackup.MIME_TYPE),
    ) { uri ->
        val document = pendingBackup
        pendingBackup = null
        backupBusy = false
        if (uri == null) return@rememberLauncherForActivityResult
        if (document == null) {
            // The screen was rebuilt while the picker was open, taking the snapshot with it. Say
            // so: an empty file, or nothing at all, would read as the backup having worked.
            backupFailed = true
            backupMessage = "The backup was interrupted. Try again."
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(AppBackup.encode(document).toByteArray(Charsets.UTF_8))
            } ?: AnililiError.BACKUP_FILE_UNWRITABLE.raise(
                detail = "The document picker returned a file nothing could be written to.",
            )
        }.onSuccess {
            backupFailed = false
            backupMessage = "Backed up ${document.describe()}" +
                if (backupMalSkipped) ", without the MyAnimeList sign-in" else ""
        }.onFailure { error ->
            backupFailed = true
            backupMessage = error.toFailure(AnililiError.BACKUP_FILE_UNWRITABLE).withCode()
        }
    }
    // A backup is JSON, but a file picker will only offer files whose type it already believes —
    // and a document restored from cloud storage or a messaging app routinely arrives as
    // text/plain or with no type at all. The filter stays wide and the parser decides, exactly as
    // the MAL import does.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            restoreBusy = true
            restoreMessage = null
            try {
                val reportedBytes = context.reportedFileSize(uri)
                if (reportedBytes != null && reportedBytes > AppBackup.MAX_SOURCE_BYTES) {
                    AnililiError.BACKUP_NOT_A_BACKUP.raise(
                        detail = "The chosen file is ${reportedBytes / 1_048_576} MB; a backup is " +
                            "never larger than ${AppBackup.MAX_SOURCE_BYTES / 1_048_576} MB.",
                    )
                }
                pendingRestore = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: AnililiError.BACKUP_FILE_UNREADABLE.raise(
                        detail = "The document picker returned a file nothing could be read from.",
                    )
                    AppBackup.decode(text)
                }
                restoreTone = MidnightNoticeTone.SUCCESS
            } catch (error: Exception) {
                restoreTone = MidnightNoticeTone.ERROR
                // A file that parsed as something else keeps BAK-03 rather than becoming a generic
                // "couldn't read it": which file was wrong is the whole of what helps here.
                restoreMessage = error.toFailure(AnililiError.BACKUP_FILE_UNREADABLE).withCode()
            } finally {
                restoreBusy = false
            }
        }
    }

    when (loginService) {
        AccountService.ANILIST -> {
            LoginWebView(
                authorizeUrl = remember(loginService) { AuthManager.authorizeUrl() },
                isRedirect = AuthManager::isRedirect,
                extractResult = AuthManager::extractToken,
                onResult = {
                    loginService = null
                    vm.onLoggedIn(it)
                    confirmSignedIn(context, "AniList")
                },
                onCancel = { loginService = null },
            )
            return
        }
        AccountService.MAL -> {
            LoginWebView(
                authorizeUrl = remember(loginService) { MalAuthManager.authorizeUrl() },
                isRedirect = MalAuthManager::isRedirect,
                extractResult = MalAuthManager::extractCode,
                onResult = { loginService = null; vm.onMalCode(it) },
                onCancel = { loginService = null },
            )
            return
        }
        null -> Unit
    }

    // Signing a television in from this phone — the same flow as the one on My List's sign-in card
    // and account sheet. It lived only there, and a viewer looking for it went to Settings first.
    if (tvPairingVisible) {
        PhonePairingScreen(
            onFinished = { tvPairingVisible = false },
            onCancel = { tvPairingVisible = false },
        )
        return
    }

    // Discord's own sign-in, when it is running in here rather than in a browser. Its redirect
    // carries the authorization code in the query, so the whole URL is what has to come back —
    // unlike AniList and MyAnimeList, where one field out of it is enough.
    discordLoginUrl?.let { authorizeUrl ->
        LoginWebView(
            authorizeUrl = authorizeUrl,
            isRedirect = DiscordAuthManager::isRedirect,
            extractResult = { url -> url.takeIf(DiscordAuthManager::isRedirect) },
            onResult = { url ->
                discordLoginUrl = null
                completeDiscordLogin(url)
            },
            onCancel = {
                discordLoginUrl = null
                DiscordAuthManager.cancelPendingAuthorization()
            },
        )
        return
    }

    fun setReleaseNotifications(enabled: Boolean) {
        if (!enabled) {
            SettingsStore.setReleaseNotifications(false)
            AutomaticReleaseManager.cancelAll()
            AiringPollScheduler.sync(context, false)
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            SettingsStore.setReleaseNotifications(true)
            ReleaseSyncScheduler.runNow(context)
            AiringPollScheduler.sync(context, true)
            AiringPollScheduler.runNow(context)
        }
    }

    fun setWatchlistSync(enabled: Boolean) {
        SettingsStore.setSyncSavedToAniList(enabled)
        if (enabled) {
            LibraryStore.syncSavedToRemote()
            vm.loadIfLoggedIn(refresh = true)
        }
    }

    fun exportMal() {
        if (malExportBusy) return
        scope.launch {
            malExportBusy = true
            malExportMessage = null
            runCatching { vm.buildMalExport(profile, watchlist, history) }
                .onSuccess { file ->
                    if (file.exportedCount == 0) {
                        // Not reported: nothing failed. There is simply nothing here that
                        // MyAnimeList could be given, which is a fact about the library.
                        malExportMessage = AnililiError.BACKUP_NOTHING_TO_EXPORT.asFailure().withCode(
                            then = "MyAnimeList only accepts titles with a MAL id.",
                        )
                    } else {
                        pendingMalExport = file
                        // Same missing picker as the backup row. This lambda sits outside the
                        // runCatching above, so an unguarded launch here crashes the app.
                        runCatching { malExportLauncher.launch(file.fileName) }.onFailure { error ->
                            pendingMalExport = null
                            malExportMessage = AnililiError.BACKUP_PICKER_MISSING.report(cause = error).withCode()
                        }
                    }
                }
                .onFailure { error ->
                    malExportMessage = error.toFailure(AnililiError.BACKUP_FILE_UNWRITABLE).withCode()
                }
            malExportBusy = false
        }
    }

    fun clearCache() {
        if (cacheClearing) return
        scope.launch {
            cacheClearing = true
            cacheMessage = null
            val before = cacheUsage ?: withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
            withContext(Dispatchers.IO) { CacheManager.clear(context) }
            val after = withContext(Dispatchers.IO) { CacheManager.usageBytes(context) }
            cacheUsage = after
            cacheMessage = "Freed ${Formatter.formatShortFileSize(context, (before - after).coerceAtLeast(0))}"
            cacheClearing = false
        }
    }

    fun backUpSettings(passphrase: String?, malHandoff: MalHandoff?) {
        if (backupBusy) return
        backupOptionsVisible = false
        backupMessage = null
        backupFailed = false
        backupBusy = true
        scope.launch {
            val document = runCatching { BackupManager.create(passphrase, malHandoff) }.getOrElse { error ->
                backupBusy = false
                backupFailed = true
                backupMessage = error.toFailure(AnililiError.BACKUP_FILE_UNWRITABLE).withCode()
                return@launch
            }
            pendingBackup = document
            // Android TV builds routinely ship without a document picker, so what fails is the
            // launch itself rather than the write. Falling back to a folder the app can always
            // write keeps the feature working on the devices with nowhere else to put a file.
            if (runCatching { backupLauncher.launch(AppBackup.fileName()) }.isSuccess) return@launch
            pendingBackup = null
            backupBusy = false
            runCatching { withContext(Dispatchers.IO) { BackupManager.writeFallback(context, document) } }
                .onSuccess { file -> backupMessage = "Saved ${document.describe()} to ${file.absolutePath}" }
                .onFailure { error ->
                    backupFailed = true
                    backupMessage = error.toFailure(AnililiError.BACKUP_FILE_UNWRITABLE).withCode()
                }
        }
    }

    /**
     * The backup dialog's answer, which for MyAnimeList means one more step before the file exists.
     *
     * MAL cannot hand over the login this device already holds — see `BackupCredentials` — so an
     * authorization is run here, on behalf of whoever restores the file, and the code it returns is
     * what travels. Everything else goes straight to the picker.
     */
    fun startBackup(passphrase: String?, includeMal: Boolean) {
        backupOptionsVisible = false
        backupMalSkipped = false
        if (!includeMal) {
            backUpSettings(passphrase, null)
            return
        }
        malHandoffRequest = MalHandoffRequest(
            state = BackupManager.newMalHandoffState(),
            verifier = BackupManager.newMalHandoffVerifier(),
            passphrase = passphrase.orEmpty(),
        )
    }

    fun restoreFromBackup() {
        if (restoreBusy) return
        restoreMessage = null
        restoreTone = MidnightNoticeTone.SUCCESS
        val filter = arrayOf(AppBackup.MIME_TYPE, "text/plain", "application/octet-stream", "*/*")
        if (runCatching { restoreLauncher.launch(filter) }.isSuccess) return
        // Same missing picker as above, from the other side: read back whatever the backup row
        // last left in the app's own folder.
        val file = BackupManager.latestFallback(context)
        if (file == null) {
            restoreTone = MidnightNoticeTone.ERROR
            // One of the few notices with room for an extra sentence, and it needs one: without the
            // folder there is nothing a viewer on a television can do about a missing picker.
            restoreMessage = AnililiError.BACKUP_PICKER_MISSING.report().withCode(
                then = "Copy a backup into ${BackupManager.fallbackDirectory(context).absolutePath} " +
                    "and try again.",
            )
            return
        }
        scope.launch {
            restoreBusy = true
            try {
                pendingRestore = withContext(Dispatchers.IO) { AppBackup.decode(file.readText()) }
            } catch (error: Exception) {
                restoreTone = MidnightNoticeTone.ERROR
                restoreMessage = error.toFailure(AnililiError.BACKUP_FILE_UNREADABLE).withCode()
            } finally {
                restoreBusy = false
            }
        }
    }

    fun applyRestore(document: BackupDocument, passphrase: String) {
        restoreMessage = null
        restoreTone = MidnightNoticeTone.SUCCESS
        restorePassphraseError = null
        restoreBusy = true
        scope.launch {
            try {
                val summary = BackupManager.restore(document, passphrase.takeIf { it.isNotBlank() })
                pendingRestore = null
                restorePassphrase = ""
                // A refused credential is reported alongside what did land, not instead of it:
                // the restore worked, one part of it did not, and both facts matter.
                restoreTone = if (summary.warnings.isEmpty()) {
                    MidnightNoticeTone.SUCCESS
                } else {
                    MidnightNoticeTone.WARNING
                }
                restoreMessage = buildString {
                    append(
                        if (summary.isEmpty) {
                            "This device already matches that backup"
                        } else {
                            "Restored ${summary.describe()}"
                        },
                    )
                    summary.warnings.forEach { append(". $it") }
                }
            } catch (error: Exception) {
                // A mistyped passphrase is the one failure the viewer can fix without starting
                // over, so it is reported inside the dialog and the dialog stays put. Nothing has
                // been written at that point: the seal is opened before the restore begins.
                if (document.credentials != null) {
                    restorePassphraseError = error.message ?: "Restore failed"
                } else {
                    pendingRestore = null
                    restoreTone = MidnightNoticeTone.ERROR
                    restoreMessage = error.message ?: "Restore failed"
                }
            } finally {
                restoreBusy = false
            }
        }
    }

    CompiledSeparately {
    if (backupOptionsVisible) {
        BackupOptionsDialog(
            aniListLogin = token != null,
            openSubtitlesLogin = openSubtitlesLoggedIn,
            malLogin = malLoggedIn,
            onDismiss = { backupOptionsVisible = false },
            onBackUp = ::startBackup,
        )
    }
    }

    // The MyAnimeList authorization that a hand-off is made of. It takes over the screen the way
    // signing in does, because that is what it is — the account holder confirming, on MAL's own
    // page, one grant for the device that will restore this backup.
    malHandoffRequest?.let { request ->
        LoginWebView(
            authorizeUrl = remember(request) {
                MalAuthManager.pairingAuthorizeUrl(request.state, request.verifier)
            },
            isRedirect = MalAuthManager::isRedirect,
            extractResult = { url -> MalAuthManager.extractPairingCode(url, request.state) },
            onResult = { code ->
                malHandoffRequest = null
                backUpSettings(
                    passphrase = request.passphrase.takeIf { it.isNotEmpty() },
                    malHandoff = MalHandoff(code = code, verifier = request.verifier),
                )
            },
            onCancel = {
                // Backing out of the MAL page is not backing out of the backup: the file is still
                // worth writing, just without the one credential that needed the confirmation.
                malHandoffRequest = null
                backupMalSkipped = true
                backUpSettings(request.passphrase.takeIf { it.isNotEmpty() }, null)
            },
        )
        return
    }

    pendingRestore?.let { document ->
        val saved = remember(document) {
            document.createdAt.takeIf { it > 0L }?.let { millis ->
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
            }
        }
        val sealedServices = document.credentials?.services.orEmpty()
        MidnightConfirmDialog(
            kicker = "Data",
            title = "Restore this backup?",
            body = buildString {
                append("It holds ${document.describe()}")
                if (saved != null) append(", saved $saved")
                if (document.appVersion.isNotBlank()) append(" from v${document.appVersion}")
                append(
                    ". Your settings are replaced by the file's; saved anime and Continue Watching " +
                        "are added to what is already on this device.",
                )
                if (sealedServices.isEmpty()) {
                    append(" Signed-in accounts are left alone — this backup carries no logins.")
                } else {
                    val names = sealedServices.joinToString(" and ") { BackupCredentials.label(it) }
                    append(" It also carries a $names login, which replaces whichever account is signed in here.")
                    if (BackupCredentials.SERVICE_MAL in sealedServices) {
                        append(
                            " The MyAnimeList part is a one-time sign-in made for this device and " +
                                "expires quickly, so it may already be spent.",
                        )
                    }
                }
            },
            confirmText = "Restore",
            confirmEnabled = !restoreBusy,
            onConfirm = { applyRestore(document, restorePassphrase) },
            onDismiss = {
                pendingRestore = null
                restorePassphrase = ""
                restorePassphraseError = null
            },
            details = if (sealedServices.isEmpty()) {
                null
            } else {
                {
                    OutlinedTextField(
                        value = restorePassphrase,
                        onValueChange = {
                            restorePassphrase = it
                            restorePassphraseError = null
                        },
                        label = { Text("Backup passphrase") },
                        enabled = !restoreBusy,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                    restorePassphraseError?.let {
                        MidnightInlineNotice(it, tone = MidnightNoticeTone.ERROR)
                    }
                    MidnightHelper(
                        "Leave it blank to restore everything except the logins.",
                    )
                }
            },
        )
    }

    fun chooseDownloadLocation(location: DownloadLocation) {
        if (
            DownloadLocations.requiresLegacyPermission(location) &&
            !DownloadLocations.hasWriteAccess(context, location)
        ) {
            pendingDownloadLocation = location
            downloadLocationMessage = null
            downloadLocationPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            DownloadLocations.setLocation(context, location)
            // Settings has room for the whole sentence, so this is one of the few places that
            // shows the full message rather than a row label — but it still carries the code, so a
            // viewer reporting "I can't pick a folder" arrives with EXP-07 rather than a paraphrase.
            downloadLocationMessage = DownloadLocations.unavailableReason(context, location)
                ?.asFailure()
                ?.withCode()
        }
    }

    fun chooseDownloadDestination(destination: DownloadDestination) {
        if (destination.includesDevice && DeviceDownloadAccess.needsWritePermission(context)) {
            pendingDownloadDestination = destination
            downloadDestinationMessage = null
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            downloadDestinationMessage = null
            SettingsStore.setDownloadDestination(destination)
        }
    }

    LaunchedEffect(openPicker, downloadLocation) {
        if (openPicker != SettingsPicker.DOWNLOAD_LOCATION) return@LaunchedEffect
        downloadLocationOptions = withContext(Dispatchers.IO) { DownloadLocations.options(context) }
    }

    LaunchedEffect(openPicker) {
        if (openPicker != SettingsPicker.EXTERNAL_PLAYER) return@LaunchedEffect
        externalPlayerOptions = withContext(Dispatchers.IO) {
            externalPlayerTargets(context, device.isTv)
        }
    }

    CompiledSeparately {
    val closePicker = { openPicker = null }
    when (openPicker) {
        SettingsPicker.SKIP_BUTTON_LENGTH -> MidnightChoicePicker(
            kicker = "Player",
            title = "Skip button length",
            explanation = "How far the player's skip button jumps. A standard TV anime opening " +
                "is 85 seconds.",
            options = SKIP_BUTTON_PRESETS,
            selected = skipButtonSeconds,
            label = { "${it}s" },
            onSelect = SettingsStore::setSkipButtonSeconds,
            onDismiss = closePicker,
            dismissOnSelect = false,
            body = {
                MidnightStepperRow(
                    label = "Fine tune",
                    value = "${skipButtonSeconds}s",
                    onDecrease = { SettingsStore.setSkipButtonSeconds(skipButtonSeconds - 5) },
                    onIncrease = { SettingsStore.setSkipButtonSeconds(skipButtonSeconds + 5) },
                    decreaseLabel = "−5s",
                    increaseLabel = "+5s",
                    decreaseEnabled = skipButtonSeconds > SKIP_BUTTON_MIN_SECONDS,
                    increaseEnabled = skipButtonSeconds < SKIP_BUTTON_MAX_SECONDS,
                )
            },
        )

        SettingsPicker.WATCHED_THRESHOLD -> MidnightChoicePicker(
            kicker = "Player",
            title = "Watched at",
            explanation = "How much of an episode has to play before it counts as watched. Moves " +
                "Continue Watching on and syncs progress to AniList or MyAnimeList.",
            options = WATCHED_THRESHOLD_PRESETS,
            selected = watchedThresholdPercent,
            label = { "$it%" },
            onSelect = SettingsStore::setWatchedThresholdPercent,
            onDismiss = closePicker,
            dismissOnSelect = false,
            body = {
                MidnightStepperRow(
                    label = "Fine tune",
                    value = "$watchedThresholdPercent%",
                    onDecrease = { SettingsStore.setWatchedThresholdPercent(watchedThresholdPercent - 5) },
                    onIncrease = { SettingsStore.setWatchedThresholdPercent(watchedThresholdPercent + 5) },
                    decreaseLabel = "−5%",
                    increaseLabel = "+5%",
                    decreaseEnabled = watchedThresholdPercent > WATCHED_THRESHOLD_MIN_PERCENT,
                    increaseEnabled = watchedThresholdPercent < WATCHED_THRESHOLD_MAX_PERCENT,
                )
            },
        )

        SettingsPicker.DEFAULT_QUALITY -> MidnightChoicePicker(
            kicker = "Player",
            title = "Default quality",
            explanation = "Picked automatically when an episode starts. Data Saver caps playback " +
                "at 360p when possible and otherwise uses the closest available low resolution.",
            options = DefaultQuality.entries,
            selected = defaultQuality,
            label = { it.label },
            onSelect = SettingsStore::setDefaultQuality,
            onDismiss = closePicker,
        )

        SettingsPicker.PLAYBACK_BUFFER_SIZE -> MidnightChoicePicker(
            kicker = "Player",
            title = "Buffer size",
            explanation = "How far ahead of the picture the player downloads. A larger buffer " +
                "rides out an unsteady connection — a distant router, a slow server, a busy " +
                "evening — and waits longer after a stall before resuming, so one interruption " +
                "does not become a stutter. It costs memory, and on a device short of it the " +
                "buffer stops at whatever fits rather than at the time you asked for. Automatic " +
                "follows the device, which means televisions and low-memory phones hold the " +
                "least. This is memory, not storage; what is kept on disk after watching is " +
                "Video cache, under Data. Takes effect from the next episode.",
            options = PlaybackBufferSize.entries,
            selected = playbackBufferSize,
            label = { it.label },
            description = { it.description },
            onSelect = SettingsStore::setPlaybackBufferSize,
            onDismiss = closePicker,
        )

        SettingsPicker.VIDEO_CACHE_SIZE -> MidnightChoicePicker(
            kicker = "Data",
            title = "Video cache",
            explanation = "Video you have already watched is kept on this device's storage, so " +
                "seeking back and reopening an episode play straight from disk. Automatic sizes " +
                "it to the device — less on televisions and when storage is short, more on a " +
                "phone with room to spare. A fixed size never takes more than half of the free " +
                "space. Off keeps nothing. A smaller size frees the difference right away.",
            options = VideoCacheSize.entries,
            selected = videoCacheSize,
            label = { it.label },
            description = {
                when (it) {
                    VideoCacheSize.AUTOMATIC -> "Sized to this device's free storage"
                    VideoCacheSize.OFF -> "Seeking back downloads again"
                    else -> null
                }
            },
            trailing = { option ->
                videoCacheBudget
                    ?.takeIf { option == videoCacheSize && option != VideoCacheSize.OFF }
                    ?.takeIf { option.bytes == null || it < option.bytes }
                    ?.let { "${Formatter.formatShortFileSize(context, it)} now" }
            },
            onSelect = SettingsStore::setVideoCacheSize,
            onDismiss = closePicker,
        )

        SettingsPicker.DEFAULT_PLAYBACK_SPEED -> MidnightChoicePicker(
            kicker = "Player",
            title = "Playback speed",
            explanation = "Every episode starts at this speed, on the built-in player and on " +
                "servers that play in a web view. Changing the speed inside the player changes " +
                "this too, so a speed you pick once carries into the next episode. Past about " +
                "2x, time-stretched audio gets hard to follow and some devices struggle to " +
                "decode at all.",
            options = PlaybackSpeeds,
            selected = defaultPlaybackSpeed,
            label = { it.formatPlaybackSpeed() },
            description = { if (it == DEFAULT_PLAYBACK_SPEED) "Normal" else null },
            onSelect = SettingsStore::setDefaultPlaybackSpeed,
            onDismiss = closePicker,
        )

        SettingsPicker.HOLD_PLAYBACK_SPEED -> MidnightChoicePicker(
            kicker = "Player",
            title = "Hold speed",
            explanation = "The player temporarily uses this speed while you press and hold the " +
                "picture, then restores the speed you were watching at. This is independent of " +
                "brightness, volume and seek gestures.",
            options = PlaybackSpeeds,
            selected = holdPlaybackSpeed,
            label = { it.formatPlaybackSpeed() },
            onSelect = SettingsStore::setHoldPlaybackSpeed,
            onDismiss = closePicker,
        )

        SettingsPicker.AUDIO_LANGUAGE -> MidnightChoicePicker(
            kicker = "Player",
            title = "Preferred audio language",
            explanation = "Some servers carry several dubs in one stream. Your pick is chosen " +
                "whenever an episode offers it; episodes that do not are left alone, and the " +
                "player's audio button still overrides it for that episode. Spanish also starts " +
                "dubs on AnimeAV1, whose dub is Latino Spanish, when you have not picked a server.",
            options = PreferredAudioLanguage.entries,
            selected = preferredAudioLanguage,
            label = { it.label },
            onSelect = SettingsStore::setPreferredAudioLanguage,
            onDismiss = closePicker,
        )

        SettingsPicker.SECONDARY_AUDIO_LANGUAGE -> MidnightChoicePicker(
            kicker = "Player",
            title = "Second choice audio",
            explanation = "Used only when a stream does not carry your first choice. Leave it on " +
                "Automatic to have no second choice at all.",
            options = PreferredAudioLanguage.entries,
            selected = secondaryAudioLanguage,
            label = { it.label },
            onSelect = SettingsStore::setSecondaryAudioLanguage,
            onDismiss = closePicker,
        )

        SettingsPicker.CONTENT_SCALE -> MidnightChoicePicker(
            kicker = "Player",
            title = "Default screen size",
            explanation = "Used every time the native player opens. Crop fills the screen without " +
                "stretching; Fill stretches the picture to the screen edges.",
            options = PlayerContentScale.entries,
            selected = playerContentScale,
            label = { it.label },
            onSelect = SettingsStore::setPlayerContentScale,
            onDismiss = closePicker,
        )

        SettingsPicker.EXTERNAL_PLAYER -> MidnightChoicePicker<ExternalPlayerTarget?>(
            kicker = "Player",
            title = "External player",
            explanation = "Which app receives an episode handed to another player. Naming one " +
                "skips Android's chooser, which is worth doing on a television: the chooser " +
                "remembers \"Always\" for good and offers no way back from in here.",
            options = listOf(null) + externalPlayerOptions.orEmpty(),
            selected = externalPlayerOptions.orEmpty()
                .firstOrNull { it.packageName == externalPlayerPackage },
            label = { it?.label ?: "Ask every time" },
            description = {
                when {
                    it == null -> "Android's chooser asks which app opens each episode"
                    it.builtForTv -> "Built for a remote · ${it.packageName}"
                    else -> it.packageName
                }
            },
            onSelect = { SettingsStore.setExternalPlayerPackage(it?.packageName.orEmpty()) },
            onDismiss = closePicker,
            notice = "No other video player is installed on this device."
                .takeIf { externalPlayerOptions?.isEmpty() == true },
        )

        SettingsPicker.DOWNLOAD_QUALITY -> MidnightChoicePicker(
            kicker = "Downloads",
            title = "Download resolution",
            explanation = "Limits the saved HLS rendition; direct video files keep their source " +
                "resolution.",
            options = DownloadQuality.entries,
            selected = downloadQuality,
            label = { it.label },
            onSelect = SettingsStore::setDownloadQuality,
            onDismiss = closePicker,
        )

        SettingsPicker.DOWNLOAD_DESTINATION -> MidnightChoicePicker(
            kicker = "Downloads",
            title = "Where downloads go",
            explanation = buildString {
                append("Device Downloads rewraps each episode into one MP4 under ")
                append(DownloadLocations.label(context, downloadLocation))
                append(" and drops the cached copy.")
                if (
                    DeviceDownloadAccess.requiresLegacyWritePermission() &&
                    DownloadLocations.requiresLegacyPermission(downloadLocation)
                ) {
                    append(" Android will ask for storage permission.")
                }
            },
            options = DownloadDestination.entries,
            selected = downloadDestination,
            label = { it.label },
            description = {
                when (it) {
                    DownloadDestination.APP_ONLY -> "Available offline inside the app"
                    DownloadDestination.DEVICE_ONLY ->
                        "One MP4 in ${DownloadLocations.label(context, downloadLocation)}"
                    DownloadDestination.BOTH -> "Both copies, at roughly double the storage"
                }
            },
            onSelect = ::chooseDownloadDestination,
            onDismiss = closePicker,
            notice = downloadDestinationMessage,
            noticeTone = MidnightNoticeTone.ERROR,
        )

        SettingsPicker.DOWNLOAD_LOCATION -> MidnightChoicePicker<DownloadLocationOption?>(
            kicker = "Downloads",
            title = "Save location",
            explanation = "Where finished episodes and their subtitle files are written. A memory " +
                "card or a folder you pick needs no storage permission.",
            options = downloadLocationOptions,
            selected = downloadLocationOptions.firstOrNull { it.location == downloadLocation },
            label = { it?.title.orEmpty() },
            description = { it?.detail },
            trailing = { option ->
                option?.freeBytes?.let { "${Formatter.formatFileSize(context, it)} free" }
            },
            onSelect = { option -> option?.let { chooseDownloadLocation(it.location) } },
            onDismiss = closePicker,
            notice = downloadLocationMessage,
            noticeTone = MidnightNoticeTone.ERROR,
            body = {
                // Not every television ships a document picker, so this row only appears where the
                // system can actually answer it. The volume list above needs no picker at all,
                // which is what keeps memory cards reachable on those devices.
                if (DownloadLocations.canPickFolder(context)) {
                    MidnightChoiceRow(
                        title = "Choose a folder…",
                        subtitle = "Any folder, including a USB drive",
                        selected = false,
                        onSelect = {
                            closePicker()
                            downloadFolderLauncher.launch(null)
                        },
                    )
                }
            },
        )

        SettingsPicker.ENABLED_SERVERS -> EnabledServersPicker(
            disabled = disabledProviders,
            onChange = SettingsStore::setDisabledProviders,
            onDismiss = closePicker,
        )

        SettingsPicker.SERVER_PRIORITY -> ServerPriorityPicker(
            priority = serverPriority,
            disabled = disabledProviders,
            onChange = SettingsStore::setServerPriority,
            onDismiss = closePicker,
        )

        SettingsPicker.SOURCE_LANGUAGES -> SourceLanguagesPicker(
            selected = preferredSourceLanguages,
            onChange = SettingsStore::setPreferredSourceLanguages,
            onDismiss = closePicker,
        )

        SettingsPicker.CUSTOM_SOURCES -> CustomSourcesDialog(
            onDismiss = closePicker,
        )

        SettingsPicker.POSTER_ELEMENTS -> PosterElementsPicker(
            hidden = hiddenPosterElements,
            onChange = SettingsStore::setHiddenPosterElements,
            onDismiss = closePicker,
        )

        SettingsPicker.HOME_LAYOUT -> com.anilili.ui.home.HomeLayoutDialog(onDismiss = closePicker)

        SettingsPicker.SUBTITLE_LANGUAGE -> MidnightChoicePicker(
            kicker = "Subtitles",
            title = "Preferred subtitle language",
            explanation = "Chosen among the subtitle tracks a source already carries, whenever it " +
                "offers your language; sources that do not are left on their first track. It does " +
                "not turn subtitles on, the player's subtitle button still overrides it for that " +
                "episode, and a file you attach from OpenSubtitles wins over it. Spanish also " +
                "starts episodes on AnimeAV1, whose Spanish subtitles are part of the picture, " +
                "when you have not picked a server.",
            options = PreferredSubtitleLanguage.entries,
            selected = preferredSubtitleLanguage,
            label = { it.label },
            onSelect = SettingsStore::setPreferredSubtitleLanguage,
            onDismiss = closePicker,
        )

        SettingsPicker.SECONDARY_SUBTITLE_LANGUAGE -> MidnightChoicePicker(
            kicker = "Player",
            title = "Second choice subtitles",
            explanation = "Used only when a stream does not carry your first choice. Leave it on " +
                "Automatic to have no second choice at all.",
            options = PreferredSubtitleLanguage.entries,
            selected = secondarySubtitleLanguage,
            label = { it.label },
            onSelect = SettingsStore::setSecondarySubtitleLanguage,
            onDismiss = closePicker,
        )

        SettingsPicker.SUBTITLE_SEARCH_LANGUAGE -> MidnightChoicePicker(
            kicker = "Subtitles",
            title = "Subtitle search language",
            explanation = "Used every time the player searches OpenSubtitles, so a phone set to " +
                "one language can still look for subtitles in another. Follow device uses the " +
                "system language. The search panel's own field still accepts any code, or " +
                "several at once, and what you set there is remembered here.",
            options = SubtitleSearchLanguage.entries,
            selected = SubtitleSearchLanguage.fromStored(subtitleSearchLanguages)
                ?: SubtitleSearchLanguage.FOLLOW_DEVICE,
            label = { it.label },
            onSelect = { SettingsStore.setSubtitleSearchLanguages(it.storedValue) },
            onDismiss = closePicker,
        )

        SettingsPicker.MENU_LANGUAGE -> MidnightChoicePicker(
            kicker = "App",
            title = if (menuLanguage.usesSpanish()) "Idioma del menú" else "Menu language",
            explanation = if (menuLanguage.usesSpanish()) {
                "Cambia las etiquetas de navegación principales."
            } else {
                "Changes the main navigation labels."
            },
            options = MenuLanguage.entries,
            selected = menuLanguage,
            label = { it.pickerLabel(menuLanguage.usesSpanish()) },
            onSelect = SettingsStore::setMenuLanguage,
            onDismiss = closePicker,
        )

        SettingsPicker.DISCORD_STATUS -> MidnightChoicePicker(
            kicker = "Discord",
            title = "Status while watching",
            explanation = "The status your profile shows while an episode is on it. Do Not Disturb " +
                "is Discord's own: it silences your Discord notifications on every device for as " +
                "long as it is showing, so pick it only if that is what you want.",
            options = DiscordStatus.entries,
            selected = discordStatus,
            label = { it.label },
            onSelect = SettingsStore::setDiscordStatus,
            onDismiss = closePicker,
        )

        SettingsPicker.CLOCK_FORMAT -> MidnightChoicePicker(
            kicker = "App",
            title = "Time format",
            explanation = "Applies to Schedule's air times and, on a television, the clock in the " +
                "corner of the top bar. System follows the device's own 12/24-hour setting, which " +
                "is what most people want — the explicit choices are for the boxes that offer no " +
                "such setting, or report the wrong one.",
            options = ClockFormat.entries,
            selected = clockFormat,
            label = { it.label },
            onSelect = SettingsStore::setClockFormat,
            onDismiss = closePicker,
        )

        SettingsPicker.THEME_BACKGROUND -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Background",
            explanation = "The canvas behind everything. AMOLED black switches an OLED screen's " +
                "pixels off entirely and keeps raised cards and sheets just above it; Dark is " +
                "Material's own dark grey. Takes effect as you choose.",
            options = ThemeBackground.entries,
            selected = themeBackground,
            label = { it.label },
            description = { it.description },
            onSelect = SettingsStore::setThemeBackground,
            onDismiss = closePicker,
            dismissOnSelect = false,
        )

        SettingsPicker.THEME_ACCENT -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Accent colour",
            explanation = "The colour every button, selection, switch and focus highlight is " +
                "drawn in, and the seed Material 3 generates the rest of the theme from. " +
                "Monochrome keeps the app black and white.",
            options = ThemeAccent.entries.filter {
                it != ThemeAccent.WALLPAPER || wallpaperSeedColor != null || it == themeAccent
            },
            selected = themeAccent,
            label = { it.label },
            leading = { accent -> { ThemeSwatch(accent.swatch(wallpaperSeedColor, themeCustomSeed)) } },
            onSelect = { accent ->
                SettingsStore.setThemeAccent(accent)
                // Custom is a colour still to be mixed, so choosing it opens the mixer.
                if (accent == ThemeAccent.CUSTOM) openPicker = SettingsPicker.THEME_CUSTOM
            },
            onDismiss = closePicker,
            dismissOnSelect = false,
        )

        SettingsPicker.THEME_CUSTOM -> ThemeCustomColourDialog(
            initialArgb = themeCustomSeed,
            onDismiss = closePicker,
        )

        SettingsPicker.THEME_BACKDROP -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Background image",
            explanation = "A picture in place of the plain background, behind every page. It is " +
                "darkened and softened so posters and text stay readable; both can be tuned below.",
            options = AppBackdropSource.entries,
            selected = appBackdropSource,
            label = { it.label },
            description = { source ->
                when {
                    source == AppBackdropSource.ANIME && appBackdropAnimeTitle != null -> appBackdropAnimeTitle
                    else -> source.description
                }
            },
            onSelect = { source ->
                // A kind already chosen once comes straight back; only a first choice needs picking.
                when (source) {
                    AppBackdropSource.NONE -> {
                        SettingsStore.setAppBackdropSource(source)
                        closePicker()
                    }
                    AppBackdropSource.ANIME -> if (appBackdropAnimeUrl != null) {
                        SettingsStore.setAppBackdropSource(source)
                        closePicker()
                    } else {
                        openPicker = SettingsPicker.THEME_BACKDROP_ANIME
                    }
                    AppBackdropSource.IMAGE -> if (appBackdropImage != null) {
                        SettingsStore.setAppBackdropSource(source)
                        closePicker()
                    } else if (device.isTv) {
                        openPicker = SettingsPicker.THEME_BACKDROP_IMAGE_TV
                    } else {
                        closePicker()
                        pickBackdropPhoto()
                    }
                }
            },
            onDismiss = closePicker,
            dismissOnSelect = false,
        )

        SettingsPicker.THEME_BACKDROP_ANIME -> AnimeBackdropPickerDialog(onDismiss = closePicker)

        SettingsPicker.THEME_BACKDROP_IMAGE_TV -> TvBackdropImageDialog(onDismiss = closePicker)

        SettingsPicker.THEME_BACKDROP_DIM -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Darken background",
            explanation = "How far the background picture is darkened. Stronger keeps white text " +
                "and posters easy to read over bright or busy artwork.",
            options = AppBackdropDim.entries,
            selected = appBackdropDim,
            label = { it.label },
            description = { it.description },
            onSelect = SettingsStore::setAppBackdropDim,
            onDismiss = closePicker,
            dismissOnSelect = false,
        )

        SettingsPicker.THEME_STYLE -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Colour style",
            explanation = "How Material 3 spreads the accent across the theme. Vivid keeps the " +
                "exact colour you picked; the others are Material You's own styles, which soften " +
                "it for dark screens.",
            options = ThemeStyle.entries,
            selected = themeStyle,
            label = { it.label },
            description = { it.description },
            onSelect = SettingsStore::setThemeStyle,
            onDismiss = closePicker,
            dismissOnSelect = false,
        )

        SettingsPicker.THEME_CONTRAST -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Contrast",
            explanation = "Moves text, outlines and surfaces further apart. Higher contrast helps " +
                "in glare, on a dim panel, or with low vision.",
            options = ThemeContrast.entries,
            selected = themeContrast,
            label = { it.label },
            description = { it.description },
            onSelect = SettingsStore::setThemeContrast,
            onDismiss = closePicker,
            dismissOnSelect = false,
        )

        SettingsPicker.UI_SCALE -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Interface size",
            explanation = "Sizes the whole app — cards, the featured banner, buttons and text. " +
                "The player keeps its own size so its controls stay clear of the edges your " +
                "television crops. Takes effect as you choose.",
            options = UiScale.entries,
            selected = uiScale,
            label = { it.label },
            onSelect = SettingsStore::setUiScale,
            onDismiss = closePicker,
            // Choosing resizes the app underneath the picker, which is the point: pick it at the
            // distance you actually watch from.
            dismissOnSelect = false,
        )

        SettingsPicker.TV_OVERSCAN -> MidnightChoicePicker(
            kicker = "Appearance",
            title = "Overscan",
            explanation = "If the edges of the app are cut off on your TV, pull everything in " +
                "until the whole menu bar fits. Moves the player too. Takes effect as you choose.",
            options = (0..MAX_TV_OVERSCAN_PERCENT).toList(),
            selected = tvOverscanPercent,
            label = ::overscanLabel,
            onSelect = SettingsStore::setTvOverscanPercent,
            onDismiss = closePicker,
            // Like Interface size: the app moves behind the picker, so the viewer stops on the
            // first value where the edges come back into view.
            dismissOnSelect = false,
        )

        SettingsPicker.DOH_PROVIDER -> MidnightChoicePicker(
            kicker = "Servers",
            title = "DNS over HTTPS",
            explanation = "Some providers leave a source's servers reachable and just refuse to " +
                "give out their address, which looks from in here like the source being down. " +
                "Choosing a resolver moves that one lookup onto an HTTPS request your provider " +
                "cannot read or answer for. None uses your network's own resolver, which is " +
                "faster and tells nobody else what you are watching — leave it there unless a " +
                "source works on another network and not on this one. Sources that have to be " +
                "opened in a web view are outside this setting's reach.",
            options = DohProvider.entries,
            selected = dohProvider,
            label = { it.label },
            description = { it.description },
            onSelect = SettingsStore::setDohProvider,
            onDismiss = closePicker,
            // The point is to try one against a source that was failing, and the next resolver is
            // one row away rather than four taps back through the section.
            dismissOnSelect = false,
        )

        SettingsPicker.FORM_FACTOR -> MidnightChoicePicker(
            kicker = "App",
            title = "Layout",
            explanation = "Automatic follows what the device reports. Some set-top boxes report " +
                "themselves as a tablet even when they are plugged into a television — choose TV " +
                "here to get the remote-friendly layout anyway. Takes effect as you choose.",
            options = FormFactorPreference.entries,
            selected = formFactorPreference,
            label = { it.label },
            onSelect = SettingsStore::setFormFactorPreference,
            onDismiss = closePicker,
            // The whole interface is rebuilt underneath the picker on each choice, which is the
            // only way to see which one is right.
            dismissOnSelect = false,
        )

        null -> Unit
    }
    }

    // Search takes the whole screen rather than opening over it: the keys need a column of their
    // own beside the results, which is room a card floating on top of the categories does not have.
    if (searchPaneVisible) {
        TvSettingsSearchPane(
            entries = searchEntries,
            spanish = menuLanguage.usesSpanish(),
            onOpenSection = {
                searchPaneVisible = false
                goToSection(it.sectionId, it.title)
            },
            onDismiss = { searchPaneVisible = false },
        )
        return
    }

    Scaffold(
        containerColor = canvasColor(),
        modifier = modifier,
        topBar = {
            if (device.isTv) return@Scaffold
            ScrollAwareTopBar {
                PageTitleBar(
                    title = openSection?.title
                        ?: if (menuLanguage.usesSpanish()) "Ajustes" else "Settings",
                    leading = if (openSection == null) {
                        null
                    } else {
                        {
                            val extraFocus1 = rememberFocusFill()
                            IconButton(
                                colors = extraFocus1.iconButtonColors(IconButtonDefaults.iconButtonColors()),onClick = { goToSection(null) }, modifier = Modifier.focusFill(extraFocus1, CircleShape),) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = if (menuLanguage.usesSpanish()) "Atrás" else "Back",
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        TvSettingsTwoPane(
            enabled = device.isTv,
            tiles = sectionTiles,
            badgedSections = badgedSections,
            openSection = openSection,
            spanish = menuLanguage.usesSpanish(),
            onSelect = { goToSection(it) },
            onSearch = { searchPaneVisible = true },
            // Pinned where every row of the section can change it without scrolling it away. Above
            // the rows it left room for two of them and covered the last; as a sticky header the
            // focused row went behind it.
            openSectionPanel = if (openSectionId == "appearance") {
                { ThemePreview() }
            } else {
                null
            },
        ) {
        SettingsList(
            spotlight = spotlight,
            // Scroll padding, not layout padding: rows travel under the bars instead of the list
            // being shunted about by them.
            //
            // The two bottom insets are alternatives, not addends. LocalAppChromeBottomInset is the
            // floating capsule *plus* the system navigation inset it sits above, and Scaffold's own
            // bottom padding is that same system inset — adding them counted the navigation bar
            // twice. That dead space is what made a fully collapsed Settings scroll: eleven section
            // headers fit the screen, the padding did not, so a drag moved the list far enough to
            // take the "Settings" title away and revealed nothing in exchange. Whichever inset is
            // larger is the one that clears everything: the capsule on a phone, the system bar on a
            // television or a rail layout, where the capsule is not drawn and the chrome inset is 0.
            contentPadding = PaddingValues(
                start = device.pagePadding,
                end = device.pagePadding,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding()
                    .coerceAtLeast(LocalAppChromeBottomInset.current) + 32.dp,
            ),
        ) {
            if (openSectionId == null) {
                item(key = "settings-search") {
                    // Both devices put search above the categories, and on both it is the first
                    // thing the page offers. They differ only in what a press does: a phone types
                    // into the field in place, while a television opens the pane that has room for
                    // a keyboard. What a television must not have is a real field here — it is a
                    // focus stop, so landing on it would raise the keyboard, Back would close it
                    // and hand focus straight back, and Down would open it again, with the whole of
                    // Settings behind that loop.
                    if (device.isTv) {
                        TvSettingsSearchBar(
                            spanish = menuLanguage.usesSpanish(),
                            onClick = { searchPaneVisible = true },
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    } else {
                        SettingsSearch(
                            entries = searchEntries,
                            spanish = menuLanguage.usesSpanish(),
                            onOpenSection = { goToSection(it.sectionId, it.title) },
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }
                val columns = if (device.isTv) 5 else 3
                items(sectionTiles.chunked(columns), key = { it.first().id }) { row ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { tile ->
                            SettingsSectionCard(
                                tile = tile,
                                badged = tile.id in badgedSections,
                                onClick = { goToSection(tile.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }

            settingsSection("player", openSectionId) {
                item(key = "Play when an episode opens") {
                    MidnightSettingSwitchRow(
                        title = "Play when an episode opens",
                        description = "Off: the player waits for you to press play",
                        checked = playOnOpen,
                        onCheckedChange = SettingsStore::setPlayOnOpen,
                    )
                }
                item(key = "Open episodes in external player") {
                    MidnightSettingSwitchRow(
                        title = "Open episodes in external player",
                        description = "Skip Anilili's player and open another installed video app",
                        checked = openEpisodesInExternalPlayer,
                        onCheckedChange = SettingsStore::setOpenEpisodesInExternalPlayer,
                    )
                }
                item(key = "External player") {
                    // Named whether or not the switch above is on: the player sheet's "Open in
                    // another player" uses the same choice, and that is reachable at any time.
                    MidnightSettingRow(
                        title = "External player",
                        description = "Which app receives an episode sent to another player",
                        value = externalPlayerName ?: "Ask every time",
                        onClick = { openPicker = SettingsPicker.EXTERNAL_PLAYER },
                    )
                }
                item(key = "Autoplay next episode") {
                    MidnightSettingSwitchRow(
                        title = "Autoplay next episode",
                        description = "Continue automatically after the credits",
                        checked = autoplay,
                        onCheckedChange = SettingsStore::setAutoplay,
                    )
                }
                if (miniPlayerSupported) {
                    item(key = "Keep playing in a mini player") {
                        MidnightSettingSwitchRow(
                            title = "Keep playing in a mini player",
                            description = "Off: leaving the app pauses the episode instead of " +
                                "shrinking it into a floating window",
                            checked = pictureInPicture,
                            onCheckedChange = SettingsStore::setPictureInPicture,
                        )
                    }
                }
                item(key = "Show details when paused") {
                    MidnightSettingSwitchRow(
                        title = "Show details when paused",
                        description = "After 15s paused. Off: pausing leaves the picture alone",
                        checked = pauseDetailsOverlay,
                        onCheckedChange = SettingsStore::setPauseDetailsOverlay,
                    )
                }
                item(key = "Auto-skip intro, recap and outro") {
                    MidnightSettingSwitchRow(
                        title = "Auto-skip intro, recap and outro",
                        description = "Use provider or AniSkip timings when available",
                        checked = autoSkip,
                        onCheckedChange = SettingsStore::setAutoSkipIntroOutro,
                    )
                }
                item(key = "Skip filler episodes") {
                    MidnightSettingSwitchRow(
                        title = "Skip filler episodes",
                        description = "Next and auto-play go to the next episode that isn't filler",
                        checked = autoSkipFiller,
                        onCheckedChange = SettingsStore::setAutoSkipFiller,
                    )
                }
                item(key = "Skip button") {
                    MidnightSettingSwitchRow(
                        title = "Skip button",
                        description = "A one-press jump on the controls when a show has no skip timings",
                        checked = showSkipButton,
                        onCheckedChange = SettingsStore::setShowSkipButton,
                    )
                }
                if (showSkipButton) {
                    item(key = "Skip button length") {
                        MidnightSettingRow(
                            title = "Skip button length",
                            description = "How far one press jumps",
                            value = "${skipButtonSeconds}s",
                            onClick = { openPicker = SettingsPicker.SKIP_BUTTON_LENGTH },
                        )
                    }
                }
                item(key = "Watched at") {
                    MidnightSettingRow(
                        title = "Watched at",
                        description = "When an episode counts as watched",
                        value = "$watchedThresholdPercent%",
                        onClick = { openPicker = SettingsPicker.WATCHED_THRESHOLD },
                    )
                }
                item(key = "Default quality") {
                    MidnightSettingRow(
                        title = "Default quality",
                        description = "Picked automatically when an episode starts",
                        value = defaultQuality.label,
                        onClick = { openPicker = SettingsPicker.DEFAULT_QUALITY },
                    )
                }
                item(key = "Buffer size") {
                    MidnightSettingRow(
                        title = "Buffer size",
                        description = "How far ahead the player downloads, held in memory",
                        value = playbackBufferSize.label,
                        onClick = { openPicker = SettingsPicker.PLAYBACK_BUFFER_SIZE },
                    )
                }
                item(key = "Playback speed") {
                    MidnightSettingRow(
                        title = "Playback speed",
                        description = "Kept between episodes, and by the player's own slider",
                        value = defaultPlaybackSpeed.formatPlaybackSpeed(),
                        onClick = { openPicker = SettingsPicker.DEFAULT_PLAYBACK_SPEED },
                    )
                }
                item(key = "Preferred audio language") {
                    MidnightSettingRow(
                        title = "Preferred audio language",
                        description = "Chosen when a stream carries several dubs",
                        value = preferredAudioLanguage.label,
                        onClick = { openPicker = SettingsPicker.AUDIO_LANGUAGE },
                    )
                }
                item(key = "Second choice audio") {
                    MidnightSettingRow(
                        title = "Second choice audio",
                        description = "Used when the first choice is not in the stream",
                        value = secondaryAudioLanguage.label,
                        onClick = { openPicker = SettingsPicker.SECONDARY_AUDIO_LANGUAGE },
                    )
                }
                item(key = "Default screen size") {
                    MidnightSettingRow(
                        title = "Default screen size",
                        description = "How the picture fits the screen",
                        value = playerContentScale.label,
                        onClick = { openPicker = SettingsPicker.CONTENT_SCALE },
                    )
                }
                item(key = "Prefer dubbed audio") {
                    MidnightSettingSwitchRow(
                        title = "Prefer dubbed audio",
                        description = "Use dub first when available",
                        checked = preferDub,
                        onCheckedChange = SettingsStore::setPreferDub,
                    )
                }
                // Not inside the phone-only block below: a television has no status bar of its own
                // to read the time off, so this is the only clock a viewer on one ever gets.
                item(key = "Clock and finishing time") {
                    MidnightSettingSwitchRow(
                        title = "Clock and finishing time",
                        description = "Show the time now and when the episode ends",
                        checked = playerClock,
                        onCheckedChange = SettingsStore::setPlayerClock,
                    )
                }
                if (device.isTv) {
                    item(key = "Press Back twice to leave") {
                        MidnightSettingSwitchRow(
                            title = "Press Back twice to leave",
                            description = "Off: one press leaves a playing episode",
                            checked = tvDoubleBackToExit,
                            onCheckedChange = SettingsStore::setTvDoubleBackToExit,
                        )
                    }
                }
                if (!device.isTv) {
                    item(key = "Hold to change speed") {
                        MidnightSettingSwitchRow(
                            title = "Hold to change speed",
                            description = "Press and hold the picture to play at " +
                                holdPlaybackSpeed.formatPlaybackSpeed(),
                            checked = holdToSpeedEnabled,
                            onCheckedChange = SettingsStore::setHoldToSpeedEnabled,
                        )
                    }
                    if (holdToSpeedEnabled) {
                        item(key = "Hold speed") {
                            MidnightSettingRow(
                                title = "Hold speed",
                                description = "Temporary speed while the picture is held",
                                value = holdPlaybackSpeed.formatPlaybackSpeed(),
                                onClick = { openPicker = SettingsPicker.HOLD_PLAYBACK_SPEED },
                            )
                        }
                    }
                    item(key = "Brightness and volume gestures") {
                        MidnightSettingSwitchRow(
                            title = "Brightness and volume gestures",
                            description = "Swipe up and down over the picture",
                            checked = playerGestures,
                            onCheckedChange = SettingsStore::setPlayerGestures,
                        )
                    }
                    item(key = "Drag to seek") {
                        MidnightSettingSwitchRow(
                            title = "Drag to seek",
                            description = "Swipe left and right to move through the episode",
                            checked = playerSeekGesture,
                            onCheckedChange = SettingsStore::setPlayerSeekGesture,
                        )
                    }
                    item(key = "Status bar over the player") {
                        MidnightSettingSwitchRow(
                            title = "Status bar over the player",
                            description = "Show the clock and battery with the player's controls",
                            checked = playerStatusBar,
                            onCheckedChange = SettingsStore::setPlayerStatusBar,
                        )
                    }
                }
            }

            settingsSection("subtitles", openSectionId) {
                item(key = "Show subtitles by default") {
                    MidnightSettingSwitchRow(
                        title = "Show subtitles by default",
                        description = "Text baked into the video cannot be hidden",
                        checked = subtitlesByDefault,
                        onCheckedChange = SettingsStore::setSubtitlesByDefault,
                    )
                }
                if (subtitlesByDefault) {
                    item(key = "Also on dubbed audio") {
                        MidnightSettingSwitchRow(
                            title = "Also on dubbed audio",
                            description = "Applies from the next episode",
                            checked = subtitlesWithDub,
                            onCheckedChange = SettingsStore::setSubtitlesWithDub,
                        )
                    }
                }
                item(key = "Preferred subtitle language") {
                    MidnightSettingRow(
                        title = "Preferred subtitle language",
                        description = "Which track to take when a source carries several",
                        value = preferredSubtitleLanguage.label,
                        onClick = { openPicker = SettingsPicker.SUBTITLE_LANGUAGE },
                    )
                }
                item(key = "Second choice subtitles") {
                    MidnightSettingRow(
                        title = "Second choice subtitles",
                        description = "Used when the first choice is not in the stream",
                        value = secondarySubtitleLanguage.label,
                        onClick = { openPicker = SettingsPicker.SECONDARY_SUBTITLE_LANGUAGE },
                    )
                }
                item(key = "Caption appearance") {
                    MidnightSettingRow(
                        title = "Caption appearance",
                        description = "Font, size, colour, background and position",
                        onClick = { captionAppearanceVisible = true },
                    )
                }
                item(key = "OpenSubtitles") {
                    MidnightSettingRow(
                        title = "OpenSubtitles",
                        description = when {
                            BuildConfig.OPEN_SUBTITLES_API_KEY.isBlank() ->
                                "Not configured in this build"
                            openSubtitlesLoggedIn ->
                                "Automatic episode subtitle search is on"
                            else -> "Sign in once for automatic subtitle search"
                        },
                        enabled = BuildConfig.OPEN_SUBTITLES_API_KEY.isNotBlank(),
                        connected = openSubtitlesLoggedIn,
                        connectedText = if (openSubtitlesLoggedIn) {
                            openSubtitlesUsername ?: "Connected"
                        } else {
                            "Sign in"
                        },
                        onClick = {
                            openSubtitlesAccountError = null
                            openSubtitlesAccountVisible = true
                        },
                    )
                }
                item(key = "Subtitle search language") {
                    MidnightSettingRow(
                        title = "Subtitle search language",
                        description = "Which language the player's subtitle search asks for",
                        value = SubtitleSearchLanguage.describe(subtitleSearchLanguages),
                        enabled = BuildConfig.OPEN_SUBTITLES_API_KEY.isNotBlank(),
                        onClick = { openPicker = SettingsPicker.SUBTITLE_SEARCH_LANGUAGE },
                    )
                }
                openSubtitlesAccountMessage?.let { message ->
                    item { MidnightInlineNotice(message, tone = MidnightNoticeTone.SUCCESS) }
                }
            }

            settingsSection("downloads", openSectionId) {
                item(key = "Download resolution") {
                    MidnightSettingRow(
                        title = "Download resolution",
                        description = "Limits the rendition saved for offline playback",
                        value = downloadQuality.label,
                        onClick = { openPicker = SettingsPicker.DOWNLOAD_QUALITY },
                    )
                }
                item(key = "Where downloads go") {
                    MidnightSettingRow(
                        title = "Where downloads go",
                        description = "App library, a file on this device, or both",
                        value = downloadDestination.label,
                        onClick = { openPicker = SettingsPicker.DOWNLOAD_DESTINATION },
                    )
                }
                downloadDestinationMessage?.let { message ->
                    item { MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR) }
                }
                item(key = "Save location") {
                    MidnightSettingRow(
                        title = "Save location",
                        description = "Internal storage, a memory card, or a folder you pick",
                        value = DownloadLocations.label(context, downloadLocation),
                        // Nothing is written outside the app when the library is the only
                        // destination, so the row would be choosing where nothing goes.
                        enabled = downloadDestination.includesDevice,
                        onClick = { openPicker = SettingsPicker.DOWNLOAD_LOCATION },
                    )
                }
                downloadLocationMessage?.let { message ->
                    item { MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR) }
                }
            }

            settingsSection("servers", openSectionId) {
                item(key = "Enabled servers") {
                    MidnightSettingRow(
                        title = "Enabled servers",
                        description = "Servers switched off are never tried",
                        value = enabledServersSummary(disabledProviders, hideAdultContent),
                        onClick = { openPicker = SettingsPicker.ENABLED_SERVERS },
                    )
                }
                item(key = "Server priority") {
                    MidnightSettingRow(
                        title = "Server priority",
                        description = "The order sources are tried in",
                        value = if (serverPriority.isEmpty()) {
                            "Automatic"
                        } else {
                            ProviderCatalog.label(serverPriority.first()) +
                                if (serverPriority.size > 1) " +${serverPriority.size - 1}" else ""
                        },
                        onClick = { openPicker = SettingsPicker.SERVER_PRIORITY },
                    )
                }
                item(key = "Preferred server languages") {
                    MidnightSettingRow(
                        title = "Preferred server languages",
                        description = "Limits the language tags and filters in the server picker",
                        value = preferredSourceLanguages.summary(),
                        onClick = { openPicker = SettingsPicker.SOURCE_LANGUAGES },
                    )
                }
                item(key = "Custom sources") {
                    MidnightSettingRow(
                        title = "Custom sources",
                        description = "Add and manage remote provider configs",
                        value = CustomSourcesSummary(),
                        onClick = { openPicker = SettingsPicker.CUSTOM_SOURCES },
                    )
                }
                item(key = "DNS over HTTPS") {
                    MidnightSettingRow(
                        title = "DNS over HTTPS",
                        description = "Useful when your provider blocks a source's address",
                        value = dohProvider.label,
                        onClick = { openPicker = SettingsPicker.DOH_PROVIDER },
                    )
                }
            }

            settingsSection("accounts", openSectionId) {
                if (device.isTv) {
                    item(key = "Manage profiles") {
                        val profiles by com.anilili.data.profiles.Profiles.profiles.collectAsState()
                        MidnightSettingRow(
                            title = "Manage profiles",
                            description = "Everyone on this TV gets their own history, My List, settings and account",
                            value = when (profiles.size) {
                                0 -> "None"
                                1 -> "1 profile"
                                else -> "${profiles.size} profiles"
                            },
                            onClick = com.anilili.ui.profiles.ProfilesUi::showManage,
                        )
                    }
                    item(key = "Profile lock") {
                        val profiles by com.anilili.data.profiles.Profiles.profiles.collectAsState()
                        val activeId by com.anilili.data.profiles.Profiles.activeId.collectAsState()
                        val active = profiles.firstOrNull { it.id == activeId }
                        if (active != null) {
                            MidnightSettingRow(
                                title = "Profile lock",
                                description = "A 4-digit PIN asked for whenever someone opens ${active.name}",
                                value = if (active.locked) "On" else "Off",
                                onClick = com.anilili.ui.profiles.ProfilesUi::showLock,
                            )
                        }
                    }
                }
                item(key = "AniList") {
                    MidnightSettingRow(
                        title = "AniList",
                        description = when {
                            token != null -> "The active list service"
                            malLoggedIn -> "Signing in switches list sync to AniList"
                            else -> "Your lists and episode progress, on every device"
                        },
                        connected = token != null,
                        connectedText = if (token != null) "Connected" else "Sign in",
                        onClick = { loginService = AccountService.ANILIST },
                    )
                }
                item(key = "MyAnimeList") {
                    MidnightSettingRow(
                        title = "MyAnimeList",
                        description = when {
                            malLoggedIn && token == null -> "The active list service"
                            token != null -> "Signing in switches list sync to MyAnimeList"
                            else -> "Your lists and episode progress, on every device"
                        },
                        connected = malLoggedIn,
                        connectedText = if (malLoggedIn) "Connected" else "Sign in",
                        onClick = { loginService = AccountService.MAL },
                    )
                }
                // Phone only: a television signs itself in from its own sign-in card, by asking a
                // phone to do this.
                if (!device.isTv) {
                    item(key = "Link a TV") {
                        MidnightSettingRow(
                            title = "Link a TV",
                            description = "Sign your TV in from this phone",
                            onClick = { tvPairingVisible = true },
                        )
                    }
                }
                item(key = "Sync episode progress") {
                    MidnightSettingSwitchRow(
                        title = "Sync episode progress",
                        description = "Update watched episodes while playing",
                        checked = autoSync,
                        onCheckedChange = SettingsStore::setAutoSyncAniList,
                    )
                }
                item(key = "Sync watchlist with Planning") {
                    MidnightSettingSwitchRow(
                        title = "Sync watchlist with Planning",
                        description = "Import Planning and add new saves without replacing progress",
                        checked = syncSavedToAniList,
                        onCheckedChange = ::setWatchlistSync,
                    )
                }
                // Beside the list service rather than one of them: see SimklSettingRow.
                item(key = "Simkl") { SimklSettingRow() }
                if (malLoggedIn) {
                    item(key = "MyAnimeList tags as lists") {
                        MidnightSettingRow(
                            title = "MyAnimeList tags as lists",
                            description = "Choose which of your tags show up on My List",
                            onClick = { malTagsVisible = true },
                        )
                    }
                }
                item(key = "Discord") {
                    MidnightSettingRow(
                        title = "Discord",
                        description = if (discordLoggedIn) {
                            "Signed in for rich presence only — no messages, no servers"
                        } else {
                            "Show the anime you are watching on your Discord profile"
                        },
                        busy = discordBusy,
                        connected = discordLoggedIn,
                        connectedText = if (discordLoggedIn) {
                            discordAccount?.displayName?.takeIf { it.isNotBlank() } ?: "Connected"
                        } else {
                            "Connect"
                        },
                        onClick = {
                            if (discordLoggedIn) {
                                discordDisconnectVisible = true
                            } else {
                                startDiscordLogin()
                            }
                        },
                    )
                }
                if (!discordLoggedIn) {
                    // Said here because it cannot be fixed where it belongs. The scope that can set
                    // an activity reaches this app only through Discord's `sdk.social_layer_presence`
                    // umbrella, which expands into six consent lines — friends list, blocked users,
                    // game invites — that there is no code here to use. Asking for the two it does
                    // use is refused for this application; see DiscordAuthManager.SCOPES. So the
                    // screen overstates what happens, and warning first is the honest move left.
                    item {
                        MidnightInlineNotice(
                            "Discord's consent screen asks for more than this uses: it bundles " +
                                "presence with friends-list and game-invite access. Anilili only " +
                                "ever sets your activity.",
                            tone = MidnightNoticeTone.INFO,
                        )
                    }
                }
                if (discordLoggedIn) {
                    item(key = "Show what you're watching") {
                        MidnightSettingSwitchRow(
                            title = "Show what you're watching",
                            description = "The title, episode and how far in you are",
                            checked = discordRichPresence,
                            onCheckedChange = SettingsStore::setDiscordRichPresence,
                        )
                    }
                    if (discordRichPresence) {
                        item(key = "Status while watching") {
                            MidnightSettingRow(
                                title = "Status while watching",
                                description = "What your profile shows while an episode is on it",
                                value = discordStatus.label,
                                onClick = { openPicker = SettingsPicker.DISCORD_STATUS },
                            )
                        }
                    }
                }
                discordError?.let { message ->
                    item { MidnightInlineNotice(message, tone = MidnightNoticeTone.ERROR) }
                }
                discordMessage?.let { message ->
                    item { MidnightInlineNotice(message, tone = MidnightNoticeTone.SUCCESS) }
                }
            }

            settingsSection("notifications", openSectionId) {
                item(key = "Notification alerts") {
                    MidnightSettingSwitchRow(
                        title = "Notification alerts",
                        description = "AniList notifications, or releases of saved anime when signed out",
                        checked = releaseNotifications,
                        onCheckedChange = ::setReleaseNotifications,
                    )
                }
                item(key = "Announcements") {
                    MidnightSettingSwitchRow(
                        title = "Announcements",
                        description = "Rare notices from the developer, like an outage or a moved download",
                        checked = announcements,
                        onCheckedChange = SettingsStore::setAnnouncements,
                    )
                }
            }

            settingsSection("content", openSectionId) {
                item(key = "Hide adult content") {
                    MidnightSettingSwitchRow(
                        title = "Hide adult content",
                        description = "Keep hentai out of Home, Search, Browse and Schedule",
                        checked = hideAdultContent,
                        onCheckedChange = SettingsStore::setHideAdultContent,
                    )
                }
                item(key = "Only episodes you can watch") {
                    MidnightSettingSwitchRow(
                        title = "Only episodes you can watch",
                        description = "Drop a title from Continue Watching until its next episode " +
                            "is out",
                        checked = hideAiringTomorrowFromContinue,
                        onCheckedChange = SettingsStore::setHideAiringTomorrowFromContinue,
                    )
                }
            }

            settingsSection("appearance", openSectionId) {
                // A television shows it in the rail under the Appearance row instead (see
                // TvSettingsTwoPane's openSectionPanel), where it stays in sight for every change and
                // costs the rows none of their height. A phone scrolls back to it with a flick.
                // Pinned on a phone, so every row below can be changed with its effect in view
                // instead of a flick back up after each one. The backing is the page colour so the
                // rows pass cleanly beneath it.
                if (!device.isTv) {
                    stickyHeader(key = "Theme preview") {
                        // A sticky header pins to the list's own top edge, and this list runs
                        // behind the title bar and the clock. So it is pushed down by whatever is
                        // at the top right now — the title bar, or just the status bar once the
                        // title has tucked away — but only by as much of that as it would be under,
                        // so it glides to a stop below the bar instead of jumping there.
                        val chromeVisible = LocalAppChromeVisible.current
                        val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                        val clearance by animateDpAsState(
                            targetValue = if (chromeVisible) padding.calculateTopPadding() else statusBar,
                            animationSpec = tween(220),
                            label = "previewClearance",
                        )
                        var top by remember { mutableFloatStateOf(Float.MAX_VALUE) }
                        var left by remember { mutableFloatStateOf(0f) }
                        val backing = MaterialTheme.colorScheme.background
                        val backdrop = LocalAppBackdrop.current
                        val rootView = androidx.compose.ui.platform.LocalView.current.rootView
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .onGloballyPositioned {
                                    top = it.positionInWindow().y
                                    left = it.positionInWindow().x
                                }
                                .graphicsLayer { translationY = (clearance.toPx() - top).coerceAtLeast(0f) }
                                // The backing reaches up over the gap it was pushed down by, so the
                                // rows passing beneath do not show through behind the clock.
                                .drawBehind {
                                    val lift = (clearance.toPx() - top).coerceAtLeast(0f)
                                    // The picture's slice, when there is one, lines up with where
                                    // this box is drawn — its laid-out place plus the lift.
                                    drawAppCanvas(
                                        backdrop = backdrop,
                                        colour = backing,
                                        origin = Offset(left, top + lift),
                                        window = androidx.compose.ui.unit.IntSize(rootView.width, rootView.height),
                                        topLeft = Offset(0f, -lift),
                                        rectSize = Size(size.width, size.height + lift),
                                    )
                                },
                        ) {
                            ThemePreview()
                        }
                    }
                }
                item(key = "Theme presets") {
                    ThemePresetRow(
                        title = "Theme presets",
                        selected = ThemePreset.entries.firstOrNull {
                            it.matches(themeBackground, themeAccent, themeStyle, themeContrast)
                        },
                        onSelect = SettingsStore::applyThemePreset,
                    )
                }
                item(key = "Background") {
                    MidnightSettingRow(
                        title = "Background",
                        description = "AMOLED black or Material's dark grey",
                        value = themeBackground.label,
                        onClick = { openPicker = SettingsPicker.THEME_BACKGROUND },
                    )
                }
                item(key = "Background image") {
                    MidnightSettingRow(
                        title = "Background image",
                        description = "A picture behind every page: from an anime, or your own",
                        value = when (appBackdropSource) {
                            AppBackdropSource.ANIME -> appBackdropAnimeTitle ?: appBackdropSource.label
                            else -> appBackdropSource.label
                        },
                        onClick = { openPicker = SettingsPicker.THEME_BACKDROP },
                    )
                }
                if (appBackdropSource != AppBackdropSource.NONE) {
                    item(key = "Change background picture") {
                        MidnightSettingRow(
                            title = "Change picture",
                            description = if (appBackdropSource == AppBackdropSource.ANIME) {
                                "Another backdrop, or another title's"
                            } else {
                                "Pick a different photo"
                            },
                            onClick = {
                                when {
                                    appBackdropSource == AppBackdropSource.ANIME ->
                                        openPicker = SettingsPicker.THEME_BACKDROP_ANIME
                                    device.isTv -> openPicker = SettingsPicker.THEME_BACKDROP_IMAGE_TV
                                    else -> pickBackdropPhoto()
                                }
                            },
                        )
                    }
                    item(key = "Darken background") {
                        MidnightSettingRow(
                            title = "Darken background",
                            description = "How much the picture is dimmed under text and posters",
                            value = appBackdropDim.label,
                            onClick = { openPicker = SettingsPicker.THEME_BACKDROP_DIM },
                        )
                    }
                    item(key = "Blur background") {
                        MidnightSettingSwitchRow(
                            title = "Blur background",
                            description = "Soften the picture so it reads as a backdrop, not a poster",
                            checked = appBackdropBlur,
                            onCheckedChange = SettingsStore::setAppBackdropBlur,
                        )
                    }
                }
                item(key = "Accent colour") {
                    MidnightSettingRow(
                        title = "Accent colour",
                        description = "Buttons, selections and focus, and the seed for the theme",
                        value = themeAccent.label,
                        leading = { ThemeSwatch(themeAccent.swatch(wallpaperSeedColor, themeCustomSeed)) },
                        onClick = { openPicker = SettingsPicker.THEME_ACCENT },
                    )
                }
                item(key = "Custom colour") {
                    MidnightSettingRow(
                        title = "Custom colour",
                        description = "Mix your own accent by hue, vividness and brightness, or type a hex",
                        value = "#%06X".format(themeCustomSeed and 0xFFFFFF),
                        leading = { ThemeSwatch(androidx.compose.ui.graphics.Color(themeCustomSeed)) },
                        onClick = { openPicker = SettingsPicker.THEME_CUSTOM },
                    )
                }
                item(key = "Colour style") {
                    MidnightSettingRow(
                        title = "Colour style",
                        // Monochrome has no hue for a style to spread, so the row says so rather
                        // than offering four choices that would all look the same.
                        description = if (themeAccent == ThemeAccent.MONOCHROME) {
                            "Pick an accent colour to choose a style"
                        } else {
                            "How strongly the accent colours the rest of the app"
                        },
                        value = themeStyle.label,
                        enabled = themeAccent != ThemeAccent.MONOCHROME,
                        onClick = { openPicker = SettingsPicker.THEME_STYLE },
                    )
                }
                item(key = "Contrast") {
                    MidnightSettingRow(
                        title = "Contrast",
                        description = "How far apart text, outlines and surfaces sit",
                        value = themeContrast.label,
                        onClick = { openPicker = SettingsPicker.THEME_CONTRAST },
                    )
                }
                item(key = "Colour anime pages by the show") {
                    MidnightSettingSwitchRow(
                        title = "Colour anime pages by the show",
                        description = "Each anime's page takes its own colour from its poster",
                        checked = showColourPages,
                        onCheckedChange = SettingsStore::setShowColourPages,
                    )
                }
                item(key = "Accent in the player") {
                    MidnightSettingSwitchRow(
                        title = "Accent in the player",
                        description = "Colour the seek bar and player menus; off keeps them white over the video",
                        checked = playerAccent,
                        onCheckedChange = SettingsStore::setPlayerAccent,
                    )
                }
                // Phone APK only: the TV flavor ships one fixed icon so pinned Fire TV tiles stay put.
                if (BuildConfig.FLAVOR == "mobile") {
                    item(key = "Match app icon to theme") {
                        MidnightSettingSwitchRow(
                            title = "Match app icon to theme",
                            description = "Colour the home-screen icon with the accent preset. It " +
                                "changes after you leave the app, and some launchers make you re-add it",
                            checked = appIconMatchesTheme,
                            onCheckedChange = SettingsStore::setAppIconMatchesTheme,
                        )
                    }
                }
                // Phones on Android 12+ only: below it nothing blurs, and a television never draws
                // glass, so there would be nothing here to switch.
                if (!device.isTv && backdropBlurAvailable) {
                    item(key = "Liquid glass") {
                        MidnightSectionLabel("Liquid glass", Modifier.padding(start = 14.dp))
                    }
                    // One switch over the lot, for someone who wants none of it and would otherwise
                    // flip six rows. On only while every part is on, so a single part turned off
                    // shows here as off, and turning it back on restores all six.
                    item(key = "Liquid glass all") {
                        val states = GlassSection.entries.map { section ->
                            SettingsStore.glass.getValue(section).collectAsState().value
                        }
                        val allOn = states.all { it }
                        MidnightSettingSwitchRow(
                            title = "All glass effects",
                            description = when {
                                allOn -> "Every part below blurs what is behind it"
                                states.none { it } -> "Off everywhere; turn on to blur every part again"
                                else -> "${states.count { it }} of ${states.size} parts on; turn on for all"
                            },
                            checked = allOn,
                            onCheckedChange = SettingsStore::setAllGlass,
                        )
                    }
                    GlassSection.entries.forEach { section ->
                        item(key = "Liquid glass ${section.name}") {
                            val on by SettingsStore.glass.getValue(section).collectAsState()
                            MidnightSettingSwitchRow(
                                title = section.label,
                                description = section.description,
                                checked = on,
                                onCheckedChange = { SettingsStore.setGlass(section, it) },
                            )
                        }
                    }
                }
                item(key = "Reset theme") {
                    MidnightSettingRow(
                        title = "Reset theme",
                        description = "Back to AMOLED black and monochrome",
                        showChevron = false,
                        onClick = SettingsStore::resetTheme,
                    )
                }
                // Television only. A phone reaches its display size and screen reader through the
                // system's own settings; a television offers neither.
                if (device.isTv) {
                    item(key = "Interface size") {
                        MidnightSettingRow(
                            title = "Interface size",
                            description = "Sizes cards, text and buttons across the app",
                            value = uiScale.label,
                            onClick = { openPicker = SettingsPicker.UI_SCALE },
                        )
                    }
                    item(key = "Overscan") {
                        MidnightSettingRow(
                            title = "Overscan",
                            description = "Pull the app in from edges your TV cuts off",
                            value = overscanLabel(tvOverscanPercent),
                            onClick = { openPicker = SettingsPicker.TV_OVERSCAN },
                        )
                    }
                }
                // On every form factor rather than a television-only list: the viewer who needs this
                // row is by definition someone the app has *not* recognised as being on one.
                item(key = "Layout") {
                    MidnightSettingRow(
                        title = "Layout",
                        description = "Use the TV layout on a box that reports itself as a tablet",
                        value = when (formFactorPreference) {
                            FormFactorPreference.AUTOMATIC ->
                                "${formFactorPreference.label} · ${device.formFactor.name.lowercase()}"
                            else -> formFactorPreference.label
                        },
                        onClick = { openPicker = SettingsPicker.FORM_FACTOR },
                    )
                }
                // One switch on both: the phone's bars tuck away on a scroll, the television's top
                // menu fades out a few seconds after the cursor leaves it.
                item(key = "Keep the bars on screen") {
                    MidnightSettingSwitchRow(
                        title = "Keep the bars on screen",
                        description = if (device.isTv) {
                            "Stop the top menu fading out while you browse"
                        } else {
                            "Stop the top bar and bottom menu tucking away as you scroll"
                        },
                        checked = staticAppChrome,
                        onCheckedChange = SettingsStore::setStaticAppChrome,
                    )
                }
                item(key = "Search as you type") {
                    MidnightSettingSwitchRow(
                        title = "Search as you type",
                        // One line on a TV row, and the reason to turn it off is in the search terms.
                        description = "Off: no suggestions, results wait for the search key",
                        checked = searchAsYouType,
                        onCheckedChange = SettingsStore::setSearchAsYouType,
                    )
                }
                // Keyed on the APK like the intro itself: only the TV flavor carries the video.
                if (BuildConfig.FLAVOR == "tv") {
                    item(key = "Video intro") {
                        MidnightSettingSwitchRow(
                            title = "Video intro",
                            description = "Off: open on the water-fill logo instead",
                            checked = videoLaunchIntro,
                            onCheckedChange = SettingsStore::setVideoLaunchIntro,
                        )
                    }
                }
                item(key = "Arrange Home") {
                    MidnightSettingRow(
                        title = "Arrange Home",
                        description = "Reorder Home's rows and choose which to show",
                        value = if (homeLayout == null) "Default" else "Custom",
                        onClick = { openPicker = SettingsPicker.HOME_LAYOUT },
                    )
                }
                item(key = "Card details") {
                    MidnightSettingRow(
                        title = "Card details",
                        description = "Which badges and captions posters draw around their artwork",
                        value = posterElementsSummary(hiddenPosterElements),
                        onClick = { openPicker = SettingsPicker.POSTER_ELEMENTS },
                    )
                }
                item(key = "Blur episode images") {
                    MidnightSettingSwitchRow(
                        title = "Blur episode images",
                        description = "Hide possible spoilers in episode thumbnails",
                        checked = blurEpisodeImages,
                        onCheckedChange = SettingsStore::setBlurEpisodeImages,
                    )
                }
                item(key = "Series logos") {
                    MidnightSettingSwitchRow(
                        title = "Series logos",
                        description = "Show a series' logo instead of its name on its page and in the player",
                        checked = showSeriesLogos,
                        onCheckedChange = SettingsStore::setShowSeriesLogos,
                    )
                }
                item(key = "Time format") {
                    MidnightSettingRow(
                        title = "Time format",
                        // The player's own clock prints a time of day on every form factor, so both
                        // sides of this row have a clock to answer for.
                        description = "How clocks and air times are written",
                        value = clockFormat.settingLabel(systemUses24Hour),
                        onClick = { openPicker = SettingsPicker.CLOCK_FORMAT },
                    )
                }
                if (device.isTv) {
                    item(key = "Spoken feedback") {
                        MidnightSettingRow(
                            title = "Spoken feedback",
                            description = "Opens Android TV's accessibility settings",
                            value = if (screenReaderActive) "On" else "Off",
                            onClick = { openAccessibilitySettings(context) },
                        )
                    }
                }
            }

            settingsSection("data", openSectionId) {
                item(key = "Back up to a file") {
                    MidnightSettingRow(
                        title = "Back up to a file",
                        description = "Settings, saved anime, Continue Watching — and your logins if you want them",
                        enabled = !backupBusy,
                        busy = backupBusy,
                        showChevron = !backupBusy,
                        onClick = {
                            backupMessage = null
                            backupOptionsVisible = true
                        },
                    )
                }
                backupMessage?.let { message ->
                    item {
                        MidnightInlineNotice(
                            message,
                            tone = if (backupFailed) MidnightNoticeTone.ERROR else MidnightNoticeTone.SUCCESS,
                        )
                    }
                }
                item(key = "Restore from a backup") {
                    MidnightSettingRow(
                        title = "Restore from a backup",
                        description = "Replaces settings; adds saved anime and history to this device",
                        enabled = !restoreBusy,
                        busy = restoreBusy,
                        showChevron = !restoreBusy,
                        onClick = ::restoreFromBackup,
                    )
                }
                restoreMessage?.let { message ->
                    item {
                        MidnightInlineNotice(
                            message,
                            tone = restoreTone,
                        )
                    }
                }
                item(key = "Export MyAnimeList XML") {
                    MidnightSettingRow(
                        title = "Export MyAnimeList XML",
                        description = "Save your list as a MAL-compatible file",
                        enabled = !malExportBusy && ((token == null && !malLoggedIn) || profile != null),
                        busy = malExportBusy,
                        showChevron = !malExportBusy,
                        onClick = ::exportMal,
                    )
                }
                malExportMessage?.let { message ->
                    item { MidnightInlineNotice(message) }
                }
                item {
                    MidnightSettingRow(
                        title = if (malImportBusy) "Cancel import" else "Import MyAnimeList XML",
                        description = malImportProgress?.label ?: "Add a MAL export to your list",
                        busy = malImportBusy,
                        showChevron = !malImportBusy,
                        onClick = {
                            if (malImportBusy) {
                                malImportJob?.cancel()
                            } else {
                                runCatching {
                                    malImportLauncher.launch(
                                        arrayOf(
                                            "text/xml",
                                            "application/xml",
                                            "application/gzip",
                                            "application/octet-stream",
                                            "*/*",
                                        ),
                                    )
                                }.onFailure { error ->
                                    malImportMessage = AnililiError.BACKUP_PICKER_MISSING.report(cause = error).withCode()
                                }
                            }
                        },
                    )
                }
                malImportMessage?.let { message ->
                    item { MidnightInlineNotice(message) }
                }
                item(key = "Video cache") {
                    MidnightSettingRow(
                        title = "Video cache",
                        description = "Watched video kept on storage for seeking back",
                        value = videoCacheValue(context, videoCacheSize, videoCacheBudget),
                        onClick = { openPicker = SettingsPicker.VIDEO_CACHE_SIZE },
                    )
                }
                item(key = "Clear cache") {
                    MidnightSettingRow(
                        title = "Clear cache",
                        description = "Streamed video and images kept on storage",
                        value = cacheUsage?.let { Formatter.formatShortFileSize(context, it) },
                        enabled = !cacheClearing && (cacheUsage ?: 0L) > 0L,
                        busy = cacheClearing,
                        onClick = ::clearCache,
                    )
                }
                cacheMessage?.let { message ->
                    item { MidnightInlineNotice(message, tone = MidnightNoticeTone.SUCCESS) }
                }
                item(key = "Re-download metadata") { MetadataRefreshRow(title = "Re-download metadata") }
                item(key = "Clear viewing history") {
                    MidnightSettingRow(
                        title = "Clear viewing history",
                        description = "Empties Continue Watching on this device",
                        enabled = history.isNotEmpty(),
                        danger = true,
                        onClick = LibraryStore::clearHistory,
                    )
                }
            }

            settingsSection("app", openSectionId) {
                item {
                    MidnightSettingRow(
                        title = if (menuLanguage.usesSpanish()) "Idioma del menú" else "Menu language",
                        description = if (menuLanguage.usesSpanish()) {
                            "Cambia las etiquetas de navegación"
                        } else {
                            "Changes the main navigation labels"
                        },
                        value = menuLanguage.pickerLabel(menuLanguage.usesSpanish()),
                        onClick = { openPicker = SettingsPicker.MENU_LANGUAGE },
                    )
                }
                item(key = "Check for updates on launch") {
                    MidnightSettingSwitchRow(
                        title = "Check for updates on launch",
                        description = "Prompt when a new version is available",
                        checked = updateCheckOnLaunch,
                        onCheckedChange = SettingsStore::setUpdateCheckOnLaunch,
                    )
                }
                item {
                    MidnightSettingRow(
                        title = when (updateState) {
                            is UpdateManager.State.Checking -> "Checking for updates…"
                            is UpdateManager.State.Downloading -> "Downloading update…"
                            else -> "Check for updates"
                        },
                        description = when (val state = updateState) {
                            is UpdateManager.State.UpToDate ->
                                if (state.latestPublishedVersion == UpdateManager.currentVersion) {
                                    "You're on the latest published version"
                                } else {
                                    "Published v${state.latestPublishedVersion}"
                                }
                            // A rebuild of this version never pops up; this is where it is found.
                            else -> quietUpdate?.let { update ->
                                "Build ${update.build} of v${update.version} is ready · tap to install"
                            } ?: "Installed v${UpdateManager.currentVersion} (build ${UpdateManager.currentBuild})"
                        },
                        value = "v${UpdateManager.currentVersion}",
                        enabled = updateState !is UpdateManager.State.Checking &&
                            updateState !is UpdateManager.State.Downloading,
                        busy = updateState is UpdateManager.State.Checking ||
                            updateState is UpdateManager.State.Downloading,
                        onClick = { UpdateManager.check(context, manual = true) },
                    )
                }
                item(key = "Send diagnostics") {
                    MidnightSettingRow(
                        title = "Send diagnostics",
                        description = "Creates a redacted debugging ZIP and lets you send it",
                        enabled = !diagnosticsBusy,
                        busy = diagnosticsBusy,
                        onClick = {
                            diagnosticsError = null
                            diagnosticsDialogVisible = true
                        },
                    )
                }
                diagnosticsMessage?.let { message ->
                    item { MidnightInlineNotice(message) }
                }
            }

            settingsSection("about", openSectionId) {
                item(key = "Discord server") {
                    MidnightSettingRow(
                        title = "Discord server",
                        description = "Questions, source problems and release news",
                        leading = { AboutBrandGlyph(R.drawable.ic_brand_discord) },
                        onClick = { openLink(context, "https://discord.gg/Anilili") },
                    )
                }
                item(key = "about-note") { AboutSignature() }
            }
        }
        }
    }
}

/**
 * One foldable settings group: the §22.1 header, then its rows only while the section is open.
 *
 * The rows are not merely hidden — a closed section emits no items at all, so the list is as short
 * as it looks and a remote's next press lands on the following header rather than travelling
 * through forty rows it cannot see. [id] is what is remembered, so a section whose title changes
 * with the menu language keeps its state. [icon] is required rather than optional because a page
 * where only some headers carry a glyph reads as ragged rather than as an index.
 */
/**
 * One category's rows, emitted only while the page is inside that category.
 *
 * The section no longer draws a header of its own: its name is the page title while it is open,
 * which is where every other drill-down in the app puts it, and a second copy of the word directly
 * underneath was the first thing this looked wrong with.
 */
private fun LazyListScope.settingsSection(
    id: String,
    openId: String?,
    content: LazyListScope.() -> Unit,
) {
    if (id == openId) content()
}

/**
 * The page's list, with the searched-for row's mark hung above it.
 *
 * The rows are composed lazily, one screen at a time, and every one of them is a separate call
 * somewhere in the two thousand lines above — so the mark travels as a composition local, provided
 * once here. A lazy list subcomposes its items under this call, which is what lets a row two
 * hundred lines and one scroll away read a value that was never passed to it.
 */
@Composable
private fun SettingsList(
    spotlight: MidnightSettingSpotlight?,
    contentPadding: PaddingValues,
    content: LazyListScope.() -> Unit,
) {
    val state = rememberLazyListState()
    val indexedContent = IndexedSettingsContent().apply(content)
    val targetIndex = indexedContent.indexOf(spotlight?.title)
    var scrolledTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(spotlight?.title, targetIndex) {
        scrolledTitle = null
        if (spotlight == null || targetIndex < 0) return@LaunchedEffect
        // Off-screen rows cannot request their own relocation until the lazy list composes them.
        // Finish the list scroll first so the row's chrome-aware adjustment cannot cancel it.
        state.animateScrollToItem(targetIndex)
        scrolledTitle = spotlight.title
    }
    CompositionLocalProvider(
        LocalMidnightSettingSpotlight provides spotlight?.takeIf { it.title == scrolledTitle },
    ) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            indexedContent.emit(this)
        }
    }
}

/** One category on the Settings index: its glyph, with its name underneath. */
private data class SettingsSectionTile(
    val id: String,
    val title: String,
    val icon: ImageVector,
    /**
     * One line saying what is inside, printed under the title in the television's detail pane.
     *
     * Only the couch layout has room for it: a handheld shows one section at a time with its name
     * in the bar, where a second line of explanation would push the first row off the fold.
     */
    val summary: String = "",
)

/**
 * A category as a tile rather than a row.
 *
 * Ten stacked rows of a word and a chevron is a list of labels; the same ten as a grid of glyphs is
 * something you aim at. It also matches the shape the rest of the app settled on — cards with the
 * picture on top and the name underneath — so Settings stops being the one page built out of a
 * different material.
 */
@Composable
private fun SettingsSectionCard(
    tile: SettingsSectionTile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badged: Boolean = false,
) {
    val fillFocus1 = rememberFocusFill()
    Surface(
        modifier = modifier
            .focusFill(fillFocus1, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = fillFocus1.background(MaterialTheme.colorScheme.surface),
        contentColor = fillFocus1.content(LocalContentColor.current),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionBadge(badged) {
                Icon(
                    tile.icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = fillFocus1.content(LocalMidnightAccent.current),
                )
            }
            Text(
                tile.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = fillFocus1.content(LocalContentColor.current),
            )
        }
    }
}

/**
 * Which servers may be used at all, as a multi-select list.
 *
 * A server switched off here is never asked for a catalogue and never appears in the player's
 * picker. That is worth a setting for two different viewers: the one who has decided they only
 * want two servers and is otherwise waiting on fifteen for every title, and the one whose network
 * or region blocks a particular server, who currently has no way to say so and instead fails over
 * past it on every single episode.
 */
@Composable
private fun EnabledServersPicker(
    disabled: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val hideAdult by SettingsStore.hideAdultContent.collectAsState()
    val servers = remember(hideAdult) { ProviderCatalog.selectableProviders(hideAdult) }
    val enabled = servers.count { it !in disabled }
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Servers",
                title = "Enabled servers",
                metadata = "$enabled of ${servers.size} on",
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(
                // The quiet slot rather than the danger one: this restores servers, and the
                // destructive styling put a red "Turn all on" next to a list it only ever adds to.
                extraQuietText = "Turn all on".takeIf { enabled < servers.size },
                // Only the servers on screen: an adult-only one hidden by "hide adult content"
                // is not shown here, and a button cannot turn on something it is not offering.
                onExtraQuiet = { onChange(disabled - servers) },
                quietText = "Done",
                onQuiet = onDismiss,
            )
        },
    ) {
        MidnightHelper(
            "A server that is off is never asked for episodes and never offered in the player. " +
                "At least one has to stay on.",
        )
        servers.forEach { server ->
            val on = server !in disabled
            MidnightSelectionRow(
                title = ProviderCatalog.label(server),
                trailing = "Fast".takeIf { ProviderCatalog.isFast(server) },
                selected = on,
                // The last one standing cannot be turned off. With nothing left to ask, every
                // catalogue comes back empty and every title reports no playable source — the app
                // would look broken rather than configured, and the way out would be a screen the
                // viewer has no reason to connect to it.
                enabled = !on || enabled > 1,
                onSelectedChange = { onChange(if (it) disabled - server else disabled + server) },
                claimInitialFocus = server == servers.firstOrNull(),
            )
        }
    }
}

/**
 * Server priority as a ranked picker (§13): tapping an unpicked server appends it and it takes the
 * next number; tapping a picked one removes it and everything behind closes the gap.
 */
@Composable
private fun ServerPriorityPicker(
    priority: List<String>,
    disabled: Set<String>,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val hideAdult by SettingsStore.hideAdultContent.collectAsState()
    // Only servers that are switched on can be ranked: a rank on one that is never asked is a
    // preference the resolver can never honour, and it would sit in the list of three taking a
    // slot from a server that can.
    val servers = remember(hideAdult, disabled) {
        ProviderCatalog.enabledProviders(ProviderCatalog.selectableProviders(hideAdult), disabled)
    }
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Servers",
                title = "Server priority",
                metadata = "Up to $MAX_SERVER_PRIORITY, tried in the order you pick them",
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(
                dangerText = "Clear".takeIf { priority.isNotEmpty() },
                onDanger = { onChange(emptyList()) },
                quietText = "Done",
                onQuiet = onDismiss,
            )
        },
    ) {
        MidnightHelper("With none picked, the built-in order is used.")
        servers.forEach { server ->
            val rank = priority.indexOf(server)
            val picked = rank >= 0
            val full = priority.size >= MAX_SERVER_PRIORITY
            MidnightChoiceRow(
                title = ProviderCatalog.label(server),
                trailing = if (picked) "#${rank + 1}" else null,
                selected = picked,
                // A full list still lets you tap a picked row, so the way out is dropping the one
                // you no longer want rather than clearing everything.
                enabled = picked || !full,
                onSelect = {
                    onChange(
                        if (picked) priority.filterNot { it == server } else priority + server,
                    )
                },
                claimInitialFocus = rank == 0,
            )
        }
    }
}

/**
 * Which badges and captions a poster draws, as a multi-select list.
 *
 * Every element is on by default and every one of them can be switched off, including the title:
 * the cards are a wall of artwork, and how much writing belongs on top of it is taste rather than
 * function. Nothing has to stay on — a grid of bare covers is a legitimate way to want this screen,
 * and unlike the server list there is no hidden cost to it, because the artwork already carries the
 * title for anyone who recognises it and the detail page carries the rest.
 */
@Composable
private fun PosterElementsPicker(
    hidden: Set<PosterElement>,
    onChange: (Set<PosterElement>) -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = PosterElement.entries.count { it !in hidden }
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Content",
                title = "Card details",
                metadata = "$shown of ${PosterElement.entries.size} on",
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(
                extraQuietText = "Show all".takeIf { hidden.isNotEmpty() },
                onExtraQuiet = { onChange(emptySet()) },
                quietText = "Done",
                onQuiet = onDismiss,
            )
        },
    ) {
        MidnightHelper(
            "Applies to every poster in the app — Home, Search, Browse, My library and " +
                "Continue Watching. The artwork itself always stays.",
        )
        PosterElement.entries.forEach { element ->
            MidnightSelectionRow(
                title = element.label,
                subtitle = element.description,
                selected = element !in hidden,
                onSelectedChange = { onChange(if (it) hidden - element else hidden + element) },
                claimInitialFocus = element == PosterElement.entries.first(),
            )
        }
    }
}

/** Multi-select shortlist for the language chips shown while choosing a server. */
@Composable
private fun SourceLanguagesPicker(
    selected: Set<SourceFilterLanguage>,
    onChange: (Set<SourceFilterLanguage>) -> Unit,
    onDismiss: () -> Unit,
) {
    MidnightDialogHost(
        onDismiss = onDismiss,
        header = { showClose ->
            MidnightMediaHeader(
                kicker = "Servers",
                title = "Preferred server languages",
                metadata = "Choose any number of languages",
                onDismiss = onDismiss.takeIf { showClose },
            )
        },
        footer = {
            MidnightDialogFooter(quietText = "Done", onQuiet = onDismiss)
        },
    ) {
        // Which row claims the remote's cursor is decided once, on open. Left derived from the live
        // selection it would re-claim on every toggle, and clearing the last language would drag
        // the cursor from wherever the viewer was back up to "All languages".
        var touched by remember { mutableStateOf(false) }
        MidnightHelper(
            "Only selected languages appear as server tags and filter choices. " +
                "Select all languages to keep every language a server reports.",
        )
        MidnightChoiceRow(
            title = "All languages",
            subtitle = "Show every language discovered for this episode",
            selected = selected.isEmpty(),
            onSelect = {
                touched = true
                onChange(emptySet())
            },
            claimInitialFocus = !touched && selected.isEmpty(),
        )
        SourceFilterLanguage.entries.forEach { language ->
            val picked = language in selected
            MidnightChoiceRow(
                title = language.label,
                trailing = language.tag,
                selected = picked,
                onSelect = {
                    touched = true
                    onChange(if (picked) selected - language else selected + language)
                },
                claimInitialFocus = !touched && selected.firstOrNull() == language,
            )
        }
    }
}

private fun Set<SourceFilterLanguage>.summary(): String {
    if (isEmpty()) return "All languages"
    val ordered = SourceFilterLanguage.entries.filter { it in this }
    return ordered.first().label + if (ordered.size > 1) " +${ordered.size - 1}" else ""
}

/**
 * The value on the "Enabled servers" row.
 *
 * Counted against the servers the viewer can actually see — "hide adult content" removes some from
 * the picker, and a total that included them would never reach "All" no matter what was switched on.
 */
/**
 * The value on the "Card details" row. Names the one thing that is off while there is only one,
 * because "6 of 7" makes the viewer open the picker to find out which — and switching off a single
 * element is what most of the people who touch this at all will do.
 */
private fun posterElementsSummary(hidden: Set<PosterElement>): String {
    val total = PosterElement.entries.size
    return when (hidden.size) {
        0 -> "All $total"
        total -> "Artwork only"
        1 -> "${PosterElement.entries.first { it in hidden }.label} off"
        else -> "${total - hidden.size} of $total"
    }
}

private fun enabledServersSummary(disabled: Set<String>, hideAdult: Boolean): String {
    val servers = ProviderCatalog.selectableProviders(hideAdult)
    val enabled = servers.count { it !in disabled }
    return if (enabled == servers.size) "All ${servers.size}" else "$enabled of ${servers.size}"
}

@Composable
private fun CustomSourcesSummary(): String {
    val remoteProviderManager = com.anilili.data.AppGraph.remoteProviderManager
    var summary by remember { mutableStateOf("Loading...") }

    LaunchedEffect(remoteProviderManager) {
        summary = remoteProviderManager.configStore.hasConfig().let { hasConfig ->
            if (!hasConfig) "None"
            else {
                val config = remoteProviderManager.configStore.getCurrentConfig()
                if (config == null) "None"
                else "${config.config.providers.size} providers"
            }
        }
    }

    return summary
}

@Composable
private fun OpenSubtitlesAccountDialog(
    loggedIn: Boolean,
    currentUsername: String?,
    busy: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignOut: () -> Unit,
) {
    var username by remember(currentUsername, loggedIn) { mutableStateOf(currentUsername.orEmpty()) }
    var password by remember(loggedIn) { mutableStateOf("") }
    MidnightModal(onDismiss = { if (!busy) onDismiss() }) {
        MidnightModalTitle(
            kicker = "OpenSubtitles",
            title = if (loggedIn) "Connected as ${currentUsername ?: "your account"}" else "Sign in",
            body = if (loggedIn) {
                "Enter credentials below only to reconnect with another account."
            } else {
                "Use a free OpenSubtitles.com account. Credentials are encrypted on this device so " +
                    "the app can renew its short-lived API session automatically."
            },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        errorMessage?.let { MidnightInlineNotice(it, tone = MidnightNoticeTone.ERROR) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loggedIn) {
                MidnightQuietAction(
                    text = "Sign out",
                    onClick = onSignOut,
                    enabled = !busy,
                    destructive = true,
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MidnightQuietAction(text = "Cancel", onClick = onDismiss, enabled = !busy)
                MidnightFilledAction(
                    text = when {
                        busy -> "Signing in…"
                        loggedIn -> "Reconnect"
                        else -> "Sign in"
                    },
                    onClick = { onSignIn(username, password) },
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    destructive = false,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * What goes in the backup, asked before the file picker opens.
 *
 * The one real question is whether the logins travel, because that is the difference between a file
 * you can leave anywhere and a file that is your account. Answering yes requires a passphrase, and
 * the app offers one rather than leaving people to invent "password1" at the moment they are asked
 * — it is typed once here and once on the television, and nothing can recover it afterwards.
 */
@Composable
private fun BackupOptionsDialog(
    aniListLogin: Boolean,
    openSubtitlesLogin: Boolean,
    malLogin: Boolean,
    onDismiss: () -> Unit,
    onBackUp: (passphrase: String?, includeMal: Boolean) -> Unit,
) {
    val carriable = buildList {
        if (aniListLogin) add("AniList")
        if (openSubtitlesLogin) add("OpenSubtitles")
    }
    var includeLogins by remember { mutableStateOf(carriable.isNotEmpty()) }
    // Off unless asked for, and asked for separately from the rest: it costs a sign-in now and can
    // only be redeemed once, which is not what someone taking a routine backup wants by default.
    var includeMal by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf(BackupManager.suggestPassphrase()) }
    val passphraseReady = passphrase.trim().length >= BackupManager.MIN_PASSPHRASE_LENGTH
    val wantsLogins = (includeLogins && carriable.isNotEmpty()) || (includeMal && malLogin)
    MidnightModal(onDismiss = onDismiss) {
        MidnightModalTitle(
            kicker = "Data",
            title = "Back up to a file",
            body = "Settings, saved anime, Continue Watching and recent searches. Downloaded " +
                "episodes stay on this device — they are too large for a file like this.",
        )
        if (carriable.isNotEmpty()) {
            MidnightSettingSwitchRow(
                title = "Include ${carriable.joinToString(" and ")} login",
                description = "Signs a new device in without signing in again",
                checked = includeLogins,
                onCheckedChange = { includeLogins = it },
            )
        }
        if (malLogin) {
            MidnightSettingSwitchRow(
                title = "Include MyAnimeList sign-in",
                description = "One-time and short-lived: confirm on MAL now, restore soon",
                checked = includeMal,
                onCheckedChange = { includeMal = it },
            )
        }
        if (wantsLogins) {
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text("Passphrase") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )
            MidnightHelper(
                "Write this down. The logins are encrypted with it, this app cannot recover it, " +
                    "and you will be asked for it on the other device. At least " +
                    "${BackupManager.MIN_PASSPHRASE_LENGTH} characters.",
            )
            MidnightInlineNotice(
                "Anyone who has both the file and the passphrase can use your account.",
                tone = MidnightNoticeTone.WARNING,
            )
        }
        if (includeMal && malLogin) {
            MidnightInlineNotice(
                "MyAnimeList replaces its refresh token every time it is used, so the file cannot " +
                    "carry your login itself. Instead it carries one sign-in made on the other " +
                    "device's behalf: MAL will ask you to confirm in a moment, and the device you " +
                    "restore on gets a session of its own. It works once, and not for long.",
                tone = MidnightNoticeTone.WARNING,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MidnightQuietAction(text = "Cancel", onClick = onDismiss)
            MidnightFilledAction(
                text = if (includeMal && malLogin) "Confirm on MAL" else "Save backup",
                onClick = {
                    onBackUp(passphrase.trim().takeIf { wantsLogins }, includeMal && malLogin)
                },
                enabled = !wantsLogins || passphraseReady,
                destructive = false,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private val SKIP_BUTTON_PRESETS = listOf(30, 60, 85, 90, 120)

private val WATCHED_THRESHOLD_PRESETS = listOf(70, 80, 90)

/** The menu language names itself in whichever language is currently on screen. */
/** "System" alone does not say which clock it landed on, which is the thing being chosen. */
private fun ClockFormat.settingLabel(systemUses24Hour: Boolean): String = when (this) {
    ClockFormat.SYSTEM -> {
        val resolved = if (uses24Hour(systemUses24Hour)) {
            ClockFormat.TWENTY_FOUR_HOUR
        } else {
            ClockFormat.TWELVE_HOUR
        }
        "$label · ${resolved.label}"
    }
    else -> label
}

private fun MenuLanguage.pickerLabel(spanish: Boolean): String = when (this) {
    MenuLanguage.SYSTEM -> if (spanish) "Sistema" else "System"
    MenuLanguage.ENGLISH -> if (spanish) "Inglés" else "English"
    MenuLanguage.SPANISH -> "Español"
}

private fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        // A television with no browser swallowed this silently, which from the outside is a row that
        // does nothing when pressed. Nothing can be shown from here — the caller is a menu row — but
        // PLAY-09 in the bundle is the difference between diagnosing that and guessing.
        .onFailure { error ->
            AnililiError.BROWSER_MISSING.report(cause = error, attributes = mapOf("surface" to "settings"))
        }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }.onFailure {
        runCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
        }
    }
}

/** What the picker says a chosen file weighs, so a wrong pick is refused before it is read. */
private fun Context.reportedFileSize(uri: Uri): Long? =
    runCatching {
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0L }
        }
    }.getOrNull()

/**
 * The couch layout for Settings: the categories down the left, the open one's rows on the right.
 *
 * A television has the width for both at once and no cursor to spare on walking back to an index
 * between every change. Handhelds keep the index-then-section pages they already had, where the
 * screen is too narrow to show a rail without taking the rows down to half a line each — so this
 * wraps rather than replaces, and is a no-op with [enabled] false.
 *
 * The rail is a plain column, not a lazy one: eleven rows always fit, and a lazy list would let
 * focus travel to a row that has not been composed.
 */
@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TvSettingsTwoPane(
    enabled: Boolean,
    tiles: List<SettingsSectionTile>,
    badgedSections: Set<String>,
    openSection: SettingsSectionTile?,
    spanish: Boolean,
    onSelect: (String) -> Unit,
    onSearch: () -> Unit,
    /** Drawn in the rail directly under the open section's row: the theme preview, for Appearance. */
    openSectionPanel: (@Composable () -> Unit)? = null,
    detail: @Composable () -> Unit,
) {
    if (!enabled) {
        detail()
        return
    }
    // One requester per category, so Left out of the open section can land on that section's row.
    val railRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val openRequester = openSection?.id?.let { id -> railRequesters.getOrPut(id) { FocusRequester() } }
    Row(
        modifier = Modifier
            .fillMaxSize()
            // No room left for the top bar here: the Settings route is already wrapped in
            // TvTopNavigationInset, which reserves the bar's full height. Clearing it twice put a
            // band of nothing across the top of the page.
            .padding(start = 34.dp, end = 34.dp, top = 8.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            modifier = Modifier
                .width(TV_SETTINGS_RAIL_WIDTH)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                // Left out of a section returns to that section's row. Left alone, Compose's search
                // picks whichever row sits level with the control being left, so backing out of
                // Subtitles from a row halfway down it landed on Downloads or Servers instead.
                // Entering from above (the top navigation) keeps the ordinary search.
                .focusProperties {
                    enter = { direction ->
                        if (direction == FocusDirection.Left) openRequester ?: FocusRequester.Default
                        else FocusRequester.Default
                    }
                }
                .focusGroup()
                // Inside the scroll, which clips at its ends: the focused row lifts and draws its
                // ring outside itself, and the last row's (About) was cut off at the rail's foot.
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = if (spanish) "Ajustes" else "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
            )
            TvSettingsRailRow(
                title = if (spanish) "Buscar ajustes" else "Search settings",
                icon = Icons.Outlined.Search,
                selected = false,
                onClick = onSearch,
            )
            tiles.forEach { tile ->
                TvSettingsRailRow(
                    title = tile.title,
                    icon = tile.icon,
                    selected = tile.id == openSection?.id,
                    badged = tile.id in badgedSections,
                    onClick = { onSelect(tile.id) },
                    focusRequester = railRequesters.getOrPut(tile.id) { FocusRequester() },
                )
                if (tile.id == openSection?.id && openSectionPanel != null) {
                    // Not a focus stop, so nothing would ever scroll it into view on its own: the
                    // rail is brought round to it as it appears, which can lift the row above.
                    val panelInView = remember { BringIntoViewRequester() }
                    LaunchedEffect(tile.id) { panelInView.bringIntoView() }
                    Box(Modifier.bringIntoViewRequester(panelInView)) { openSectionPanel() }
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
        ) {
            Column(Modifier.fillMaxSize()) {
                openSection?.let { section ->
                    Column(Modifier.padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 10.dp)) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (section.summary.isNotBlank()) {
                            Text(
                                text = section.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f)) { detail() }
            }
        }
    }
}

/** One category in the television rail: glyph, name, chevron — the badge every row in the app is. */
@Composable
private fun TvSettingsRailRow(
    title: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    badged: Boolean = false,
) {
    val fill = rememberFocusFill()
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusFill(fill, shape)
            .clip(shape)
            .background(
                // The cursor is the television's ring, which focusFill draws; the row keeps these.
                when {
                    selected -> FocusFillColor.copy(alpha = 0.16f)
                    fill.focused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                },
                shape,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = MaterialTheme.colorScheme.onSurface
        SectionBadge(badged) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || fill.focused) FontWeight.Bold else FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = ink.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The notification bell's badge on a section's glyph: a "1" for the one thing waiting inside.
 * Only an available update raises it, so there is never a count higher than one to show.
 */
@Composable
private fun SectionBadge(badged: Boolean, icon: @Composable () -> Unit) {
    if (!badged) {
        icon()
        return
    }
    BadgedBox(badge = { Badge { Text("1") } }) { icon() }
}

/** Wide enough for "Accounts & sync" on one line at the largest interface size. */
private val TV_SETTINGS_RAIL_WIDTH = 300.dp

/**
 * A brand mark before an About row's title.
 *
 * Both glyphs are drawn as a filled disc with the logo knocked out of it, so they take the row's
 * own ink: white on a dark row, and inverted along with everything else on the white focused one.
 */
@Composable
private fun AboutBrandGlyph(@DrawableRes id: Int) {
    Icon(
        painter = painterResource(id),
        contentDescription = null,
        tint = LocalContentColor.current,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * The sign-off at the foot of About.
 *
 * Not a row: there is nothing to press and nothing to change, so it wears no badge and takes no
 * focus — a remote walking the section should not have to step over a paragraph on its way out.
 */
@Composable
private fun AboutSignature() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 22.dp, bottom = 8.dp),
    ) {
        Text(
            text = "Anilili is a one-person project: an anime app that finds an episode across " +
                "dozens of sources, remembers where you stopped, and keeps your list in step with " +
                "AniList or MyAnimeList. No adverts, and no account of its own.",
            style = MaterialTheme.typography.bodyMedium,
            color = Midnight.Text2,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 14.dp),
        ) {
            Icon(
                Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "Made with love by lili",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}


/**
 * The Settings page's own transient state — which dialog is open, what is busy, what to tell the
 * viewer — remembered once instead of forty-six times.
 *
 * Each of these was its own `remember { mutableStateOf(...) }`, and each is a group of slot-table
 * code inlined into [SettingsScreen] itself; together they held that method over ART's 10 000-unit
 * JIT ceiling in debug builds and within a few hundred units of it in release (see
 * [com.anilili.ui.components.CompiledSeparately]). Nothing that uses them changes: each local is
 * still a delegate, now to a field here. Only unkeyed states whose initial value is a constant
 * belong here — anything keyed keeps its own `remember`, or it would reset at a different time.
 */
@Stable
private class SettingsScreenState {
    val pendingMalExport = mutableStateOf<MalExportFile?>(null)
    val malExportBusy = mutableStateOf(false)
    val malExportMessage = mutableStateOf<String?>(null)
    val malImportBusy = mutableStateOf(false)
    val malImportMessage = mutableStateOf<String?>(null)
    val malImportProgress = mutableStateOf<MalImportProgress?>(null)
    val malImportJob = mutableStateOf<Job?>(null)
    val loginService = mutableStateOf<AccountService?>(null)
    val tvPairingVisible = mutableStateOf(false)
    val diagnosticsMessage = mutableStateOf<String?>(null)
    val diagnosticsBusy = mutableStateOf(false)
    val diagnosticsDialogVisible = mutableStateOf(false)
    val diagnosticsError = mutableStateOf<String?>(null)
    val captionAppearanceVisible = mutableStateOf(false)
    val openSubtitlesAccountVisible = mutableStateOf(false)
    val openSubtitlesAccountBusy = mutableStateOf(false)
    val openSubtitlesAccountError = mutableStateOf<String?>(null)
    val openSubtitlesAccountMessage = mutableStateOf<String?>(null)
    val discordLoginUrl = mutableStateOf<String?>(null)
    val discordBusy = mutableStateOf(false)
    val discordMessage = mutableStateOf<String?>(null)
    val discordError = mutableStateOf<String?>(null)
    val discordDisconnectVisible = mutableStateOf(false)
    val malTagsVisible = mutableStateOf(false)
    val cacheUsage = mutableStateOf<Long?>(null)
    val videoCacheBudget = mutableStateOf<Long?>(null)
    val cacheClearing = mutableStateOf(false)
    val cacheMessage = mutableStateOf<String?>(null)
    val pendingBackup = mutableStateOf<BackupDocument?>(null)
    val backupBusy = mutableStateOf(false)
    val backupMessage = mutableStateOf<String?>(null)
    val backupFailed = mutableStateOf(false)
    val backupOptionsVisible = mutableStateOf(false)
    val malHandoffRequest = mutableStateOf<MalHandoffRequest?>(null)
    val backupMalSkipped = mutableStateOf(false)
    val pendingRestore = mutableStateOf<BackupDocument?>(null)
    val restoreBusy = mutableStateOf(false)
    val restoreMessage = mutableStateOf<String?>(null)
    val restoreTone = mutableStateOf(MidnightNoticeTone.SUCCESS)
    val restorePassphrase = mutableStateOf("")
    val restorePassphraseError = mutableStateOf<String?>(null)
    val pendingDownloadDestination = mutableStateOf<DownloadDestination?>(null)
    val downloadDestinationMessage = mutableStateOf<String?>(null)
    val downloadLocationOptions = mutableStateOf<List<DownloadLocationOption>>(emptyList())
    val pendingDownloadLocation = mutableStateOf<DownloadLocation?>(null)
    val downloadLocationMessage = mutableStateOf<String?>(null)
    val externalPlayerOptions = mutableStateOf<List<ExternalPlayerTarget>?>(null)
    val openPicker = mutableStateOf<SettingsPicker?>(null)
    val searchPaneVisible = mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomSourcesDialog(onDismiss: () -> Unit) {
    val remoteProviderManager = com.anilili.data.AppGraph.remoteProviderManager
    val scope = rememberCoroutineScope()

    var configUrl by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var testingSource by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var trustConfirmed by remember { mutableStateOf(false) }
    var allowRuntimeEndpoints by remember { mutableStateOf(true) }
    var previewResult by remember { mutableStateOf<com.anilili.data.provider.remote.config.ConfigPreviewResult?>(null) }

    val currentConfig = remoteProviderManager.configStore.getCurrentConfig()
    val hasConfig = currentConfig != null
    val providerCount = currentConfig?.config?.providers?.size ?: 0
    val configMetadata = currentConfig?.config?.metadata
    val configUrlDisplay = currentConfig?.originalUrl ?: ""
    val currentTrust = remoteProviderManager.getTrust()

    val parsedOrigin = remember(configUrl) {
        if (configUrl.isNotBlank()) com.anilili.data.provider.remote.config.RemoteSourceTrust.parseOrigin(configUrl) else null
    }
    val isLoopback = parsedOrigin?.isLoopback == true
    val isHttp = parsedOrigin?.protocol == "http"
    val isPrivate = parsedOrigin?.isPrivateOrLocal == true
    val requiresTrust = isHttp || isPrivate

    MidnightModal(onDismiss = onDismiss) {
        MidnightModalTitle(
            kicker = "Servers",
            title = "Custom sources",
            body = "Add a remote provider configuration from a JSON URL. The config defines how to fetch episodes, servers, and streams from custom sources.",
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasConfig) {
                if (previewResult == null) {
                    // Add source form
                    Text(
                        text = "Add custom provider source",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    androidx.compose.material3.OutlinedTextField(
                        value = configUrl,
                        onValueChange = {
                            configUrl = it
                            addError = null
                            trustConfirmed = false
                        },
                        label = { Text("Config URL (HTTPS or HTTP)") },
                        placeholder = { Text("https://example.com/provider.json or http://192.168.1.10:8080/provider.json") },
                        modifier = Modifier.fillMaxWidth(),
                        isError = addError != null || isLoopback,
                        supportingText = {
                            if (isLoopback) {
                                Text("Loopback addresses (localhost/127.0.0.1) are strictly prohibited", color = MaterialTheme.colorScheme.error)
                            } else if (addError != null) {
                                Text(addError!!, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )

                    if (requiresTrust && !isLoopback) {
                        val origin = parsedOrigin ?: return@MidnightModal
                        // Trust and Security Notice Card
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (isHttp) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = if (isHttp && isPrivate) "Insecure Local Network Source (HTTP)"
                                    else if (isHttp) "Insecure Connection Warning (HTTP)"
                                    else "Local Network Source Notice (Private IP/LAN)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isHttp) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )

                                Text(
                                    text = if (isHttp) "Traffic to this server will not be encrypted. Do not send sensitive credentials."
                                    else "This source connects to a private network address.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )

                                Text(
                                    text = "Target Host: ${origin.host}\nPort: ${origin.port}\nScope: Configuration Fetching",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )

                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = trustConfirmed,
                                        onCheckedChange = { trustConfirmed = it },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "I trust this server and authorize this connection",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = { onDismiss() }) {
                            Text("Cancel")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                addError = null
                                if (isLoopback) {
                                    addError = "Loopback addresses are strictly forbidden"
                                    return@Button
                                }
                                val candidateTrust = if (requiresTrust) {
                                    com.anilili.data.provider.remote.config.RemoteSourceTrust.createTrustGrant(
                                        configUrl = configUrl,
                                        allowHttpConfig = isHttp,
                                        allowPrivateIpConfig = isPrivate,
                                    )
                                } else null

                                isPreviewing = true
                                scope.launch {
                                    try {
                                        val result = remoteProviderManager.previewCustomSource(configUrl, candidateTrust)
                                        if (result.success) {
                                            previewResult = result
                                        } else {
                                            addError = result.error ?: "Failed to preview source"
                                        }
                                    } catch (e: Exception) {
                                        addError = "Error: ${e.message}"
                                    } finally {
                                        isPreviewing = false
                                    }
                                }
                            },
                            enabled = !isPreviewing && configUrl.isNotBlank() && !isLoopback && (!requiresTrust || trustConfirmed),
                        ) {
                            if (isPreviewing) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Previewing...")
                            } else {
                                Text("Preview source")
                            }
                        }
                    }
                } else {
                    // Preview Step
                    val preview = previewResult!!
                    val meta = preview.config?.metadata

                    Text(
                        text = "Source Preview",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = "Name: ${meta?.name ?: "Unknown"}\nVersion: ${meta?.version ?: "1.0"}\nProviders: ${preview.config?.providers?.size ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    if (!meta?.description.isNullOrBlank()) {
                        Text(
                            text = "Description: ${meta?.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val requestedEndpoints = (preview.discoveredHttpEndpoints + preview.discoveredPrivateIpEndpoints).distinct()
                    if (requestedEndpoints.isNotEmpty()) {
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "Requested Runtime Endpoints",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = "This configuration defines providers pointing to:\n${requestedEndpoints.joinToString("\n") { "• $it" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp),
                                ) {
                                    androidx.compose.material3.Checkbox(
                                        checked = allowRuntimeEndpoints,
                                        onCheckedChange = { allowRuntimeEndpoints = it },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Authorize runtime queries to these endpoints",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    if (addError != null) {
                        Text(addError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = { previewResult = null }) {
                            Text("Back")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                isAdding = true
                                addError = null
                                scope.launch {
                                    try {
                                        val endpointOrigins = if (allowRuntimeEndpoints) {
                                            (preview.discoveredHttpEndpoints + preview.discoveredPrivateIpEndpoints).toSet()
                                        } else emptySet()

                                        val fullTrust = if (requiresTrust || endpointOrigins.isNotEmpty()) {
                                            com.anilili.data.provider.remote.config.RemoteSourceTrust.createTrustGrant(
                                                configUrl = configUrl,
                                                allowHttpConfig = isHttp,
                                                allowPrivateIpConfig = isPrivate,
                                                trustedEndpointOrigins = endpointOrigins,
                                                allowHttpEndpoints = allowRuntimeEndpoints && preview.discoveredHttpEndpoints.isNotEmpty(),
                                                allowPrivateIpEndpoints = allowRuntimeEndpoints && preview.discoveredPrivateIpEndpoints.isNotEmpty(),
                                            )
                                        } else null

                                        val result = remoteProviderManager.addCustomSource(configUrl, fullTrust)
                                        if (result.success) {
                                            configUrl = ""
                                            previewResult = null
                                            trustConfirmed = false
                                        } else {
                                            addError = result.error ?: "Failed to install source"
                                        }
                                    } catch (e: Exception) {
                                        addError = "Error: ${e.message}"
                                    } finally {
                                        isAdding = false
                                    }
                                }
                            },
                            enabled = !isAdding,
                        ) {
                            if (isAdding) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Installing...")
                            } else {
                                Text("Install & Trust Source")
                            }
                        }
                    }
                }
            } else {
                // Show current config info
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Current config",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("URL: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(configUrlDisplay, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, overflow = TextOverflow.Ellipsis)
                    }

                    // Trust details
                    if (currentTrust != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = if (currentTrust.isLocalNetwork) "Trust: Local Network (Trusted)" else "Trust: Cleartext HTTP (Trusted)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = "Config Origin: ${currentTrust.configOrigin}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (currentTrust.trustedEndpointOrigins.isNotEmpty()) {
                                Text(
                                    text = "Approved Endpoints: ${currentTrust.trustedEndpointOrigins.joinToString()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Trust: Public HTTPS (Default)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (configMetadata != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Name: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(configMetadata.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Version: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(configMetadata.version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Providers: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$providerCount", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }

                        if (configMetadata.description.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Description: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(configMetadata.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    // Actions
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Test source button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    testingSource = "current"
                                    testResult = null
                                    scope.launch {
                                        try {
                                            val result = remoteProviderManager.fetcher.manualUpdate(configUrlDisplay)
                                            testResult = if (result.success) "Test passed: ${result.message}" else "Test failed: ${result.error}"
                                        } catch (e: Exception) {
                                            testResult = "Test error: ${e.message}"
                                        } finally {
                                            testingSource = null
                                        }
                                    }
                                },
                                enabled = testingSource == null,
                            ) {
                                if (testingSource != null) {
                                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Testing...")
                                } else {
                                    Text("Test source")
                                }
                            }
                            testResult?.let { result ->
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (result.startsWith("Test passed")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                            }
                        }

                        // Update button
                        androidx.compose.material3.OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = remoteProviderManager.fetcher.manualUpdate(configUrlDisplay)
                                }
                            },
                        ) {
                            Text("Check for updates")
                        }

                        // Remove & Revoke trust button
                        OutlinedButton(
                            onClick = {
                                showRemoveConfirm = true
                            },
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Revoke trust & Remove source", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (showRemoveConfirm) {
            MidnightConfirmDialog(
                kicker = "Custom sources",
                title = "Revoke trust & remove source?",
                body = "This will remove the custom source configuration, revoke all network permissions, and clear stored trust.",
                confirmText = "Revoke & Remove",
                onConfirm = {
                    showRemoveConfirm = false
                    scope.launch {
                        remoteProviderManager.removeCustomSource(revokeTrust = true)
                    }
                },
                onDismiss = { showRemoveConfirm = false },
            )
        }
    }
}

private fun overscanLabel(percent: Int): String = if (percent == 0) "Off" else "$percent% each side"
