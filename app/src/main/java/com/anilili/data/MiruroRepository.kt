package com.anilili.data

import com.anilili.data.model.AiringDayCount
import com.anilili.data.model.AiringSchedule
import com.anilili.data.model.Category
import com.anilili.data.model.SubtitleItem
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodeMetadata
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.alignMetadataToSeason
import com.anilili.data.model.mergeEpisodeMetadataSources
import com.anilili.data.model.streamingEpisodeMetadata
import com.anilili.data.model.streamingRowsFitSeason
import com.anilili.data.model.DiscoverFilters
import com.anilili.data.model.Media
import com.anilili.data.model.MediaPage
import com.anilili.data.model.StudioNode
import com.anilili.data.model.HomeCollections
import com.anilili.data.model.ScheduleDayCount
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.SourceVariant
import com.anilili.data.cache.AppCache
import com.anilili.data.remote.AiringSeason
import com.anilili.data.remote.searchHanimeCatalogue
import com.anilili.data.remote.ONE_PACE_MEDIA_ID
import com.anilili.data.remote.isOnePaceMediaId
import com.anilili.data.remote.matchesOnePaceQuery
import com.anilili.data.remote.normalizeHanimeTitle
import com.anilili.data.remote.onePaceMedia
import com.anilili.data.auth.AccountService
import com.anilili.data.auth.AuthManager
import com.anilili.data.model.DiscoverOptions
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.AnimeStat
import com.anilili.data.model.MediaListEntry
import com.anilili.data.model.UserAvatar
import com.anilili.data.model.Viewer
import com.anilili.data.model.ViewerStatistics
import com.anilili.data.library.WatchedTitles
import com.anilili.data.model.WatchedFilter
import com.anilili.data.provider.registry.UnifiedProviderRegistry
import com.anilili.data.provider.registry.UnifiedSourceType
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AniZipClient
import com.anilili.data.remote.AniSkipClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import com.anilili.data.remote.AniSkipRelationRule
import com.anilili.data.remote.redirect
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.JikanClient
import com.anilili.data.remote.KonohaAiring
import com.anilili.data.remote.KonohaClient
import com.anilili.data.remote.KonohaEpisode
import com.anilili.data.remote.toAiringSchedule
import com.anilili.data.remote.TmdbClient
import com.anilili.data.remote.TvdbRef
import com.anilili.data.remote.toMedia
import com.anilili.data.remote.MalClient
import com.anilili.data.remote.MalProgressSyncResult
import com.anilili.data.remote.MalProgressSyncSkipReason
import com.anilili.data.remote.MediaListProgressSnapshot
import com.anilili.data.remote.PipeClient
import com.anilili.data.remote.PipeBridge
import com.anilili.data.remote.malProgressSkipReason
import com.anilili.data.remote.planMediaListProgressUpdate
import com.anilili.data.remote.planRewatchMove
import com.anilili.data.remote.RewatchMove
import com.anilili.data.settings.SettingsStore
import com.anilili.data.settings.PreferredAudioLanguage
import com.anilili.data.settings.PreferredSubtitleLanguage
import com.anilili.data.settings.DEFAULT_PREFERRED_PROVIDER
import com.anilili.diagnostics.DiagnosticsLog
import com.anilili.errors.AnililiError
import com.anilili.errors.platformErrorCode
import com.anilili.errors.raise
import com.anilili.errors.report
import com.anilili.ui.rethrowIfCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.ConcurrentHashMap
import com.anilili.util.cached

/**
 * Keeps fallback attempts spread across independent backends. Providers from one backend often
 * fail together, so exhausting the attempt budget on adjacent aliases defeats fallback entirely.
 */
/**
 * The order servers are tried in for one episode.
 *
 * [preferred] goes first, then any [fallbacks] the user picked in Settings, and only then the
 * catalog's own alternating order. Entries that are blank, "auto", duplicated, or equal to
 * [preferred] are dropped, so a half-filled pair of fallback slots behaves like none at all.
 */
internal fun providerAttemptOrder(
    preferred: String,
    providerNames: List<String>,
    fallbacks: List<String> = emptyList(),
): List<String> {
    val chosen = buildList {
        add(preferred)
        fallbacks.forEach { fallback ->
            val name = fallback.trim().lowercase()
            if (name.isNotBlank() && name != DEFAULT_PREFERRED_PROVIDER && name !in this) add(name)
        }
    }
    val available = (chosen + providerNames.sortedBy { ProviderCatalog.sortKey(it) }).distinct()
    val preferredSource = ProviderCatalog.sourceOf(preferred)
    val rest = available.filterNot { it in chosen }
    val sameSource = rest.filter { ProviderCatalog.sourceOf(it) == preferredSource }
    val otherSource = rest.filter { ProviderCatalog.sourceOf(it) != preferredSource }

    return buildList {
        addAll(chosen)
        repeat(maxOf(sameSource.size, otherSource.size)) { index ->
            otherSource.getOrNull(index)?.let(::add)
            sameSource.getOrNull(index)?.let(::add)
        }
    }
}

internal fun providerAttemptOrder(
    preferred: String,
    providerNames: List<String>,
    providerRegistry: UnifiedProviderRegistry,
    fallbacks: List<String> = emptyList(),
): List<String> = providerAttemptOrder(preferred, providerNames, fallbacks)

/**
 * [ordered] with the servers that are not in English ([ProviderCatalog.declaredLanguage]) held to
 * what the viewer asked for.
 *
 * Such a server is always listed in the picker, but an automatic walk must not land an English
 * viewer on Spanish subtitles because it happened to answer first. So it stays in the walk only
 * when it was picked by hand — it is [preferred], or ranked in Settings' server priority — or
 * when [wantedLanguage] (the viewer's preferred subtitle language for a sub, audio language for a
 * dub) is its language. In that last case it moves to the front, behind only a server picked by
 * hand, because a viewer who asked for Spanish would otherwise be given English first.
 */
internal fun languageGatedAttemptOrder(
    ordered: List<String>,
    preferred: String,
    userRanked: Collection<String>,
    wantedLanguage: String?,
    declaredLanguage: (String) -> String? = ProviderCatalog::declaredLanguage,
): List<String> {
    val handPicked = (userRanked + preferred)
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && it != DEFAULT_PREFERRED_PROVIDER }
        .toSet()
    fun isWanted(name: String): Boolean =
        wantedLanguage != null && declaredLanguage(name)?.equals(wantedLanguage, ignoreCase = true) == true
    val kept = ordered.filter { name ->
        declaredLanguage(name) == null || name.lowercase() in handPicked || isWanted(name)
    }
    val wanted = kept.filter(::isWanted)
    if (wanted.isEmpty()) return kept
    val lead = kept.firstOrNull()?.takeIf { it.lowercase() in handPicked && it.equals(preferred, ignoreCase = true) }
    return listOfNotNull(lead) + wanted.filterNot { it == lead } + kept.filterNot { it == lead || it in wanted }
}

internal fun languageGatedAttemptOrder(
    ordered: List<String>,
    preferred: String,
    providerRegistry: UnifiedProviderRegistry,
    userRanked: Collection<String>,
    wantedLanguage: String?,
    declaredLanguage: (String) -> String? = ProviderCatalog::declaredLanguage,
): List<String> = languageGatedAttemptOrder(ordered, preferred, userRanked, wantedLanguage, declaredLanguage)

/**
 * The language the viewer asked for on a [category] row, by the name [ProviderCatalog] declares
 * languages under ("Spanish"): the subtitle preference for a sub, the audio one for a dub. Null
 * when it is left on Automatic.
 */
internal fun preferredLanguageFor(category: Category): String? = when (category) {
    Category.DUB -> SettingsStore.preferredAudioLanguage.value
        .takeUnless { it == PreferredAudioLanguage.AUTOMATIC }?.label
    else -> SettingsStore.preferredSubtitleLanguage.value
        .takeUnless { it == PreferredSubtitleLanguage.AUTOMATIC }?.label
}

internal data class ProviderSourceCandidate(
    val provider: String,
    val pipeId: String,
    val variant: SourceVariant = SourceVariant.GENERIC_SUB,
)

data class ProviderSourceIdentity(
    val provider: String,
    val variant: SourceVariant,
)

internal data class ProviderSourceResult(
    val sources: SourcesResult,
    val provider: String,
    val variant: SourceVariant,
)

internal data class ProviderCandidateResolution(
    val resolved: ProviderSourceResult?,
    val unavailableSources: Set<ProviderSourceIdentity>,
) {
    val unavailableProviders: Set<String>
        get() = unavailableSources.mapTo(linkedSetOf(), ProviderSourceIdentity::provider)
}

internal enum class SourceFailureKind {
    FAILURE,
    TIMEOUT,
    EMPTY,
    PLAYBACK,
}

/**
 * Which cooling pairs are allowed to stop a resolve.
 *
 * All of them, when the resolve is choosing a server on the viewer's behalf — that is what a
 * cooldown is for. None of them for the pair the viewer picked by hand, because the picker no
 * longer removes a failed server (see `visibleSourceOptions`): it fades it and leaves it there,
 * and a row that can be pressed but quietly plays a different server is worse than the vanishing
 * row it replaced. The pair is still excluded within one resolve walk by the caller's own
 * `failedSources`, so exempting it here cannot retry the same dead pair twice in a row.
 *
 * Name matching is case-insensitive because the picked name travels through UI state while the
 * candidate names come out of the catalogue.
 */
internal fun coolingSourcesToHonor(
    cooling: Set<ProviderSourceIdentity>,
    preferred: String,
    preferredVariant: SourceVariant,
    honorCooldownForPreferred: Boolean,
): Set<ProviderSourceIdentity> = if (honorCooldownForPreferred) {
    cooling
} else {
    cooling.filterNotTo(linkedSetOf()) { source ->
        source.variant == preferredVariant && source.provider.equals(preferred, ignoreCase = true)
    }
}

/** What a *cut off* Anivexa catalogue is worth, as against a full one's two hours. */
internal const val PARTIAL_EPISODES_TTL = 10L * 60 * 1000

/**
 * How long to believe one Anivexa catalogue: its full lifetime, or a short one if it is incomplete.
 *
 * `MIN_CATALOG_COVERAGE` was meant to stop a thin catalogue being pinned for two hours, and it
 * cannot, because it counts heads and the head count is not the question. A provider genuinely has
 * nothing for a title all the time, so "eight of sixteen answered" is not evidence of anything —
 * and measured on two devices on 2026-09-11 it is what the fan-out produces *every* time:
 * `asked=16 answered=8 tookMs≈15000`, the budget expiring rather than the providers replying.
 * Eight clears `(16 * 0.5).toInt()` by nothing at all, so the gate waved through the exact case it
 * exists for: the same title cached 10 providers on one device and 8 on another within the hour,
 * and the thin one stayed thin for two hours however many times the viewer came back.
 * [EpisodesResult.cutOff] answers the question the head count could not.
 *
 * Short rather than not stored. Refusing outright refetches on every entry to the watch screen,
 * and a cut-off pass is not wasted: the providers it lost go to the front of the lane queue next
 * time, and their per-provider answers live for weeks, so the retry is mostly disk reads. Ten
 * minutes is long enough that browsing one title does not re-run the fan-out, and short enough
 * that coming back to it later does.
 */
internal fun anivexaCatalogTtlMs(
    result: EpisodesResult,
    fullTtlMs: Long,
    partialTtlMs: Long = PARTIAL_EPISODES_TTL,
): Long = if (result.cutOff.isEmpty()) {
    fullTtlMs
} else {
    // Never lengthen. A measurement run deliberately sets a catalogue TTL shorter than this, and
    // an incomplete pass must not be the thing that puts it back up.
    minOf(fullTtlMs, partialTtlMs)
}

/** Compiled once: a television was measured parsing the same page 30-60x slower on uncached regexes. */
private val SERVER_OUTAGE = Regex("""\bhttp\s+5\d\d\b""")

/**
 * Whether a failure message carries the server's own admission that it is broken, rather than a
 * verdict this app reached about it.
 *
 * A 5xx is the host answering about itself, so it says the same thing to the next episode and to
 * every other title — which makes it worth holding against the provider and not just against the
 * one stream. A timeout or a reset is not: those are as likely to be the viewer's link, and a
 * couple of bad seconds on a train should not cost them a server.
 *
 * Takes an already-lowercased message.
 */
internal fun serverOutageMessage(message: String): Boolean = SERVER_OUTAGE.containsMatchIn(message)

/**
 * The same question asked of a player's own code instead of its prose: `h503` is the host admitting
 * to an outage, exactly as "HTTP 503 from the video server" was.
 *
 * Only the HTTP half of the vocabulary can answer it — a Media3 `m2004` says a status was bad
 * without saying which, so it is not an admission of anything.
 */
internal fun serverOutageStatus(platformCode: String?): Boolean =
    platformCode != null && SERVER_OUTAGE_STATUS.matches(platformCode)

/** Compiled once, for the same reason [SERVER_OUTAGE] is. */
private val SERVER_OUTAGE_STATUS = Regex("""^h5\d\d$""")

/** A refusal that will still be a refusal in a minute: the link is gone or was never ours. */
private val HTTP_GONE_CODES = setOf("h403", "h404")

/**
 * The host asking for fewer requests, rather than refusing this one.
 *
 * Written twice before this — once to pick the episode cooldown, once to open the provider-wide
 * breaker — so the same failure could have been called rate limiting by one and not the other.
 * They agreed only because both spelled the test identically; nothing made them.
 *
 * The prose half stays because the resolver path has nothing else: a provider that met a 429 throws
 * its own `IllegalStateException`, and the status survives only in the sentence it wrote. The
 * player path supplies `h429` structurally and does not depend on wording.
 */
internal fun rateLimitedStatus(platformCode: String?, message: String): Boolean =
    platformCode == "h429" || "429" in message

/**
 * Prevents repeated retries of failed providers. Episode failures stay scoped to one title and
 * episode, while repeated network-level failures briefly open a provider-wide circuit breaker.
 * Playback failures live longer than resolver failures and are not cleared merely because the
 * provider can still return the same broken stream URL.
 */
internal class SourceFailureCooldowns(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class EpisodeCooldown(
        val untilMs: Long,
        val kind: SourceFailureKind,
    )

    private data class ProviderFailureState(
        val count: Int,
        val windowStartedMs: Long,
        val unavailableUntilMs: Long,
    )

    private val episodeCooldowns = ConcurrentHashMap<String, EpisodeCooldown>()
    private val providerFailures = ConcurrentHashMap<String, ProviderFailureState>()

    fun coolingProviders(
        anilistId: Int,
        episode: Double,
        providers: Collection<String>,
    ): Set<String> = coolingSources(
        anilistId = anilistId,
        episode = episode,
        sources = providers.map { ProviderSourceIdentity(it, SourceVariant.GENERIC_SUB) },
    ).mapTo(linkedSetOf(), ProviderSourceIdentity::provider)

    fun coolingSources(
        anilistId: Int,
        episode: Double,
        sources: Collection<ProviderSourceIdentity>,
    ): Set<ProviderSourceIdentity> {
        val now = nowMs()
        return sources.filterTo(linkedSetOf()) { source ->
            val key = key(source.provider, source.variant, anilistId, episode)
            val episodeCooldown = episodeCooldowns[key]
            val episodeCooling = episodeCooldown?.untilMs?.let { until ->
                if (until > now) true else {
                    episodeCooldowns.remove(key, episodeCooldown)
                    false
                }
            } ?: false
            episodeCooling || providerIsCooling(source.provider, now)
        }
    }

    fun record(
        provider: String,
        anilistId: Int,
        episode: Double,
        kind: SourceFailureKind,
        error: Exception? = null,
        variant: SourceVariant = SourceVariant.GENERIC_SUB,
        /**
         * What the player said, for a [SourceFailureKind.PLAYBACK] with no exception to carry it.
         *
         * The playback path had one and dropped it on the floor, so every branch below that reads a
         * status — the ten minutes for a 429, the five for a 403 — was unreachable from playback and
         * a rate-limited stream host got the flat five-minute default instead.
         */
        playbackMessage: String = "",
        /**
         * The player's own code for a [SourceFailureKind.PLAYBACK], as `h429` / `m2004`.
         *
         * The status used to be findable only by searching the message for "429", which worked
         * exactly as long as whoever wrote the message kept putting it there. Read structurally it
         * cannot be lost by a rewording, and the string checks below stay for the resolver path,
         * where what there is to read really is an exception's prose.
         */
        platformCode: String? = null,
    ): Long {
        val message = error?.message.orEmpty().ifEmpty { playbackMessage }
        val durationMs = when {
            rateLimitedStatus(platformCode, message) -> 10 * 60_000L
            platformCode in HTTP_GONE_CODES ||
                Regex.cached("""\bHTTP\s+(?:403|404)\b""", RegexOption.IGNORE_CASE).containsMatchIn(message) ->
                5 * 60_000L
            "no native HLS server" in message || "episode page is unavailable" in message ->
                10 * 60_000L
            kind == SourceFailureKind.PLAYBACK -> 5 * 60_000L
            kind == SourceFailureKind.TIMEOUT -> 2 * 60_000L
            kind == SourceFailureKind.EMPTY -> 2 * 60_000L
            else -> 60_000L
        }
        val now = nowMs()
        episodeCooldowns[key(provider, variant, anilistId, episode)] = EpisodeCooldown(
            untilMs = now + durationMs,
            kind = kind,
        )
        recordProviderFailure(provider, kind, message, now, platformCode)
        return durationMs
    }

    fun clearResolved(
        provider: String,
        anilistId: Int,
        episode: Double,
        variant: SourceVariant = SourceVariant.GENERIC_SUB,
    ) {
        val key = key(provider, variant, anilistId, episode)
        episodeCooldowns.computeIfPresent(key) { _, cooldown ->
            cooldown.takeIf { it.kind == SourceFailureKind.PLAYBACK }
        }
        providerFailures.remove(provider.lowercase())
    }

    private fun recordProviderFailure(
        provider: String,
        kind: SourceFailureKind,
        message: String,
        now: Long,
        platformCode: String? = null,
    ) {
        val normalizedMessage = message.lowercase()
        val rateLimited = rateLimitedStatus(platformCode, normalizedMessage)
        val serverOutage = serverOutageStatus(platformCode) || serverOutageMessage(normalizedMessage)
        // A playback failure is normally a statement about one file, not about a server, which is
        // why it is kept out of the provider-wide breaker below. A 429 or a 5xx from the stream
        // host is the exception: it is the host talking about itself, and it will say the same to
        // the next episode. AniDB proved it — `pewe` resolved every time while `hls.anidb.app`
        // answered 429 to every stream, so the server kept being offered and kept playing nothing.
        if (kind == SourceFailureKind.PLAYBACK && !rateLimited && !serverOutage) return
        val networkOutage = kind == SourceFailureKind.TIMEOUT ||
            serverOutage ||
            listOf(
                "failed to connect",
                "unable to resolve host",
                "connection reset",
                "timed out",
                "timeout",
            ).any(normalizedMessage::contains)
        if (!rateLimited && !networkOutage) return

        val providerKey = provider.lowercase()
        providerFailures.compute(providerKey) { _, previous ->
            if (rateLimited) {
                ProviderFailureState(
                    count = GLOBAL_PROVIDER_FAILURE_THRESHOLD,
                    windowStartedMs = now,
                    unavailableUntilMs = now + GLOBAL_RATE_LIMIT_COOLDOWN_MS,
                )
            } else {
                val activeWindow = previous?.takeIf {
                    now - it.windowStartedMs <= GLOBAL_PROVIDER_FAILURE_WINDOW_MS
                }
                val count = (activeWindow?.count ?: 0) + 1
                ProviderFailureState(
                    count = count,
                    windowStartedMs = activeWindow?.windowStartedMs ?: now,
                    unavailableUntilMs = if (count >= GLOBAL_PROVIDER_FAILURE_THRESHOLD) {
                        now + GLOBAL_PROVIDER_COOLDOWN_MS
                    } else {
                        0L
                    },
                )
            }
        }
    }

    private fun providerIsCooling(provider: String, now: Long): Boolean {
        val providerKey = provider.lowercase()
        val state = providerFailures[providerKey] ?: return false
        if (state.unavailableUntilMs > now) return true
        if (now - state.windowStartedMs > GLOBAL_PROVIDER_FAILURE_WINDOW_MS) {
            providerFailures.remove(providerKey, state)
        }
        return false
    }

    private fun key(
        provider: String,
        variant: SourceVariant,
        anilistId: Int,
        episode: Double,
    ): String = "${provider.lowercase()}:${variant.route}:$anilistId:$episode"

    private companion object {
        const val GLOBAL_PROVIDER_FAILURE_THRESHOLD = 2
        const val GLOBAL_PROVIDER_FAILURE_WINDOW_MS = 2 * 60_000L
        const val GLOBAL_PROVIDER_COOLDOWN_MS = 2 * 60_000L
        const val GLOBAL_RATE_LIMIT_COOLDOWN_MS = 10 * 60_000L
    }
}

/**
 * Runs source providers in preference order without allowing one dead host to stop fallback.
 * The loader must cooperate with cancellation; Anivexa's HTTP bridge does so by cancelling the
 * underlying OkHttp call, while its legacy synchronous providers also have a short call timeout.
 *
 * **A cancelled attempt is not a failed one**, and telling them apart takes more than catching
 * [CancellationException]. The synchronous providers block inside `runInterruptible`, so cancelling
 * one interrupts its socket read — and OkHttp reports that as an ordinary `InterruptedIOException`,
 * which arrives here looking exactly like a dead host. Recording it cools a healthy server for
 * minutes because the viewer left the screen, or because the watch screen stopped its own
 * source-validation pass, and the next episode then quietly starts somewhere else. The empty answer
 * a provider gives when it catches that interrupt itself reads the same way.
 *
 * So an outcome is only believed while this coroutine is still active. `withContext` already covers
 * the value returned through `runInterruptible`; the exception it propagates, and the pipe-backed
 * providers that return without one, are what these checks are for.
 */
internal suspend fun resolveProviderCandidates(
    candidates: List<ProviderSourceCandidate>,
    excludedProviders: Set<String>,
    excludedSources: Set<ProviderSourceIdentity> = emptySet(),
    maxAttempts: Int,
    attemptTimeoutMs: Long,
    attemptTimeoutMsFor: (String) -> Long = { attemptTimeoutMs },
    onAttempt: (String) -> Unit = {},
    onFailure: (String, Exception) -> Unit = { _, _ -> },
    onTimeout: (String) -> Unit = {},
    onEmpty: (String) -> Unit = {},
    /** Per-attempt result and how long it took, for the aggregate resolve telemetry. */
    onOutcome: (provider: String, outcome: SourceAttemptOutcome, durationMs: Long) -> Unit = { _, _, _ -> },
    onSourceFailure: (ProviderSourceCandidate, Exception) -> Unit = { _, _ -> },
    onSourceTimeout: (ProviderSourceCandidate) -> Unit = {},
    onSourceEmpty: (ProviderSourceCandidate) -> Unit = {},
    onSourceOutcome: (candidate: ProviderSourceCandidate, outcome: SourceAttemptOutcome, durationMs: Long) -> Unit =
        { _, _, _ -> },
    load: suspend (ProviderSourceCandidate) -> SourcesResult,
): ProviderCandidateResolution {
    require(maxAttempts >= 0) { "maxAttempts must not be negative" }
    require(attemptTimeoutMs > 0L) { "attemptTimeoutMs must be positive" }

    val unavailable = linkedSetOf<ProviderSourceIdentity>()
    var attempts = 0
    for (candidate in candidates) {
        if (attempts >= maxAttempts) break
        val identity = ProviderSourceIdentity(candidate.provider, candidate.variant)
        if (candidate.provider in excludedProviders || identity in excludedSources) continue
        attempts++
        onAttempt(candidate.provider)
        val providerTimeoutMs = attemptTimeoutMsFor(candidate.provider)
        require(providerTimeoutMs > 0L) { "provider timeout must be positive" }
        val startedAt = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - startedAt

        val result = try {
            withTimeoutOrNull(providerTimeoutMs) { load(candidate) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            unavailable += identity
            onOutcome(candidate.provider, SourceAttemptOutcome.FAILED, elapsed())
            onSourceOutcome(candidate, SourceAttemptOutcome.FAILED, elapsed())
            onFailure(candidate.provider, e)
            onSourceFailure(candidate, e)
            continue
        }
        if (result == null) {
            unavailable += identity
            onOutcome(candidate.provider, SourceAttemptOutcome.TIMEOUT, elapsed())
            onSourceOutcome(candidate, SourceAttemptOutcome.TIMEOUT, elapsed())
            onTimeout(candidate.provider)
            onSourceTimeout(candidate)
            continue
        }

        if (result.streams.isNotEmpty()) {
            onOutcome(candidate.provider, SourceAttemptOutcome.OK, elapsed())
            onSourceOutcome(candidate, SourceAttemptOutcome.OK, elapsed())
            return ProviderCandidateResolution(
                resolved = ProviderSourceResult(result, candidate.provider, candidate.variant),
                unavailableSources = unavailable,
            )
        }
        currentCoroutineContext().ensureActive()
        unavailable += identity
        onOutcome(candidate.provider, SourceAttemptOutcome.EMPTY, elapsed())
        onSourceOutcome(candidate, SourceAttemptOutcome.EMPTY, elapsed())
        onEmpty(candidate.provider)
        onSourceEmpty(candidate)
    }
    return ProviderCandidateResolution(resolved = null, unavailableSources = unavailable)
}

/**
 * [resolveProviderCandidates], but racing: the first candidate starts at once, and if nothing has
 * produced a stream within [hedgeDelayMs] the next one starts beside it, up to [maxInFlight] at a
 * time. The first stream wins and every other attempt is cancelled.
 *
 * Used only when the viewer has not pinned a server. A sequential walk makes them wait out the
 * whole of a slow first choice before the second is asked; a hedge costs one extra request on the
 * episodes where the first choice is slow, and nothing on the ones where it is quick.
 *
 * Everything the sequential walk promises holds here too, because the outcome is decided in one
 * place — the loop below — rather than in the racing children:
 *
 *  - a failure starts the next candidate immediately instead of waiting out the hedge;
 *  - a child that is cancelled (it lost the race, or the viewer left) sends nothing, so it is
 *    never recorded as a failure — the same guard the sequential walk has against an interrupted
 *    socket read looking like a dead host;
 *  - [maxAttempts], the exclusions and every callback behave as they do there.
 *
 * Resolvers that need a browser ([isExclusive]) share one engine, so one is never started beside
 * another attempt: it waits until it can run alone, and nothing is hedged beside it.
 */
internal suspend fun resolveProviderCandidatesHedged(
    candidates: List<ProviderSourceCandidate>,
    excludedProviders: Set<String>,
    excludedSources: Set<ProviderSourceIdentity> = emptySet(),
    maxAttempts: Int,
    hedgeDelayMs: Long,
    maxInFlight: Int = 2,
    attemptTimeoutMsFor: (String) -> Long,
    isExclusive: (String) -> Boolean = { false },
    onAttempt: (String) -> Unit = {},
    onSourceFailure: (ProviderSourceCandidate, Exception) -> Unit = { _, _ -> },
    onSourceTimeout: (ProviderSourceCandidate) -> Unit = {},
    onSourceEmpty: (ProviderSourceCandidate) -> Unit = {},
    onSourceOutcome: (candidate: ProviderSourceCandidate, outcome: SourceAttemptOutcome, durationMs: Long) -> Unit =
        { _, _, _ -> },
    load: suspend (ProviderSourceCandidate) -> SourcesResult,
): ProviderCandidateResolution {
    require(maxAttempts >= 0) { "maxAttempts must not be negative" }
    require(hedgeDelayMs > 0L) { "hedgeDelayMs must be positive" }
    require(maxInFlight >= 1) { "maxInFlight must be at least one" }

    class Finished(
        val candidate: ProviderSourceCandidate,
        val result: SourcesResult?,
        val error: Exception?,
        val durationMs: Long,
    )

    val eligible = candidates.filter { candidate ->
        candidate.provider !in excludedProviders &&
            ProviderSourceIdentity(candidate.provider, candidate.variant) !in excludedSources
    }
    val unavailable = linkedSetOf<ProviderSourceIdentity>()
    return kotlinx.coroutines.coroutineScope {
        val finished = kotlinx.coroutines.channels.Channel<Finished>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val running = mutableMapOf<ProviderSourceCandidate, kotlinx.coroutines.Job>()
        var nextIndex = 0
        var attempts = 0

        fun exclusiveRunning() = running.keys.any { isExclusive(it.provider) }

        /** Starts the next candidate if the rules allow one more; returns whether it did. */
        fun startNext(): Boolean {
            if (attempts >= maxAttempts || nextIndex >= eligible.size) return false
            if (running.size >= maxInFlight || exclusiveRunning()) return false
            val candidate = eligible[nextIndex]
            if (isExclusive(candidate.provider) && running.isNotEmpty()) return false
            nextIndex++
            attempts++
            onAttempt(candidate.provider)
            val timeoutMs = attemptTimeoutMsFor(candidate.provider)
            require(timeoutMs > 0L) { "provider timeout must be positive" }
            running[candidate] = launch {
                val startedAt = SystemClock.elapsedRealtime()
                val outcome = try {
                    Finished(candidate, withTimeoutOrNull(timeoutMs) { load(candidate) }, null, 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Believed only while still wanted; see resolveProviderCandidates.
                    currentCoroutineContext().ensureActive()
                    Finished(candidate, null, e, 0)
                }
                currentCoroutineContext().ensureActive()
                finished.send(
                    Finished(outcome.candidate, outcome.result, outcome.error, SystemClock.elapsedRealtime() - startedAt),
                )
            }
            return true
        }

        startNext()
        try {
            while (running.isNotEmpty()) {
                val canHedge = running.size < maxInFlight && nextIndex < eligible.size &&
                    attempts < maxAttempts && !exclusiveRunning()
                val done = if (canHedge) {
                    withTimeoutOrNull(hedgeDelayMs) { finished.receive() }
                } else {
                    finished.receive()
                }
                if (done == null) {
                    startNext()
                    continue
                }
                running.remove(done.candidate)
                val identity = ProviderSourceIdentity(done.candidate.provider, done.candidate.variant)
                when {
                    done.error != null -> {
                        unavailable += identity
                        onSourceOutcome(done.candidate, SourceAttemptOutcome.FAILED, done.durationMs)
                        onSourceFailure(done.candidate, done.error)
                    }
                    done.result == null -> {
                        unavailable += identity
                        onSourceOutcome(done.candidate, SourceAttemptOutcome.TIMEOUT, done.durationMs)
                        onSourceTimeout(done.candidate)
                    }
                    done.result.streams.isNotEmpty() -> {
                        onSourceOutcome(done.candidate, SourceAttemptOutcome.OK, done.durationMs)
                        return@coroutineScope ProviderCandidateResolution(
                            resolved = ProviderSourceResult(done.result, done.candidate.provider, done.candidate.variant),
                            unavailableSources = unavailable,
                        )
                    }
                    else -> {
                        unavailable += identity
                        onSourceOutcome(done.candidate, SourceAttemptOutcome.EMPTY, done.durationMs)
                        onSourceEmpty(done.candidate)
                    }
                }
                // A refusal hands its slot straight to the next candidate rather than waiting out a
                // hedge for it — one replacement, not a burst, so a run of refusals cannot open
                // several requests at once.
                startNext()
            }
            ProviderCandidateResolution(resolved = null, unavailableSources = unavailable)
        } finally {
            running.values.forEach { it.cancel() }
            finished.close()
        }
    }
}

/** Why a server did or did not produce a stream, for aggregation across devices. */
internal enum class SourceAttemptOutcome { OK, EMPTY, TIMEOUT, FAILED }

/**
 * Single entry point the UI talks to. Combines AniList metadata with two streaming backends —
 * the Miruro pipe (WebView bridge) and Anivexa-API (HTTP) — and caches episode lists per source.
 */
class MiruroRepository(
    private val aniList: AniListClient,
    private val pipe: PipeClient,
    private val anivexa: AnivexaClient,
    private val jikan: JikanClient,
    private val aniSkip: AniSkipClient,
    private val mal: MalClient,
    private val konoha: KonohaClient,
    private val tmdb: TmdbClient,
    private val cache: AppCache,
    private val localCatalog: LocalCatalog,
    private val aniZip: AniZipClient,
    private val providerRegistry: UnifiedProviderRegistry,
) {
    /** User preference: keep hentai out of every browsing surface. */
    private val hideAdult: Boolean get() = SettingsStore.hideAdultContent.value

    // Bounds the related-seasons walk's AniList fan-out so it can't monopolise the shared,
    // rate-limited connection pool (5/host) and stall playback-critical metadata/catalog lookups.
    private val seriesFetchGate = Semaphore(SERIES_FETCH_CONCURRENCY)
    private val sourceFailureCooldowns = SourceFailureCooldowns()

    /**
     * Which TheTVDB series an AniList id belongs to, and which season of it that entry is.
     *
     * Read straight off the bundled cross-reference, so it costs a map lookup and never a request.
     * The detail page builds its season chain from this because AniList has no season concept of
     * its own — see `tvdbSeasonChain`.
     */
    fun tvdbRefFor(anilistId: Int): TvdbRef? = konoha.tvdbRefFor(anilistId)

    // ---- discovery (AniList) ----
    /** [maxAgeMs]: see [AppCache.getOrFetch]; Home passes it to swap cached rows for live ones. */
    suspend fun homeCollections(force: Boolean = false, maxAgeMs: Long? = null): HomeCollections {
        val adultHidden = hideAdult
        // The season is in the key so a cached "this season" row cannot outlive the season.
        val season = AiringSeason.current()
        val collections = try {
            cache.getOrFetch(
                key = "home:v4:${season.season}-${season.year}:${if (adultHidden) "sfw" else "all"}",
                serializer = HomeCollections.serializer(),
                ttlMs = HOME_TTL,
                forceRefresh = force,
                maxAgeMs = maxAgeMs,
            ) { aniList.homeCollections(season, adultHidden) }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            throw e
        }
        if (!adultHidden) return collections
        return collections.copy(
            spotlight = collections.spotlight.filterNot { it.isAdult },
            newest = collections.newest.filterNot { it.isAdult },
            popular = collections.popular.filterNot { it.isAdult },
            movies = collections.movies.filterNot { it.isAdult },
            topRated = collections.topRated.filterNot { it.isAdult },
            upcoming = collections.upcoming.filterNot { it.isAdult },
        )
    }

    /**
     * Home rows from the catalogue snapshot in the APK, for when [homeCollections] has nothing.
     *
     * Deliberately its own call rather than a catch inside [homeCollections]: the caller puts a
     * network timeout around that one, and parsing four megabytes of JSON inside that window blew
     * it — the first attempt at this shipped a "Home request timed out" where the offline rows
     * should have been. Null when the snapshot is unreadable, so the caller can still show the real
     * error rather than an empty screen.
     */
    suspend fun offlineHomeCollections(): HomeCollections? {
        val offline = localCatalog.homeCollections()
        if (offline.popular.isEmpty()) return null
        if (!hideAdult) return offline
        return offline.copy(
            spotlight = offline.spotlight.filterNot { it.isAdult },
            newest = offline.newest.filterNot { it.isAdult },
            popular = offline.popular.filterNot { it.isAdult },
            movies = offline.movies.filterNot { it.isAdult },
            topRated = offline.topRated.filterNot { it.isAdult },
            upcoming = offline.upcoming.filterNot { it.isAdult },
        )
    }

    suspend fun trending(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("trending:$page", COLLECTION_TTL, force) {
        aniList.collection("TRENDING_DESC", page = page, perPage = 30, hideAdult = hideAdult)
    }
    /** The most popular titles of the current broadcast season; Home's "Popular this season". */
    suspend fun popular(page: Int = 1, force: Boolean = false): MediaPage {
        val season = AiringSeason.current()
        return mediaPage("popular:${season.season}-${season.year}:$page", COLLECTION_TTL, force) {
            aniList.collection("POPULARITY_DESC", season = season, page = page, perPage = 30, hideAdult = hideAdult)
        }
    }
    suspend fun upcoming(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("upcoming:$page", COLLECTION_TTL, force) {
        aniList.collection("POPULARITY_DESC", status = "NOT_YET_RELEASED", page = page, perPage = 30, hideAdult = hideAdult)
    }
    suspend fun topRated(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("top:$page", COLLECTION_TTL, force) {
        aniList.collection("SCORE_DESC", page = page, perPage = 30, hideAdult = hideAdult)
    }
    suspend fun movies(page: Int = 1, force: Boolean = false): MediaPage = mediaPage("movies:$page", COLLECTION_TTL, force) {
        aniList.collection("POPULARITY_DESC", format = "MOVIE", page = page, perPage = 30, hideAdult = hideAdult)
    }
    suspend fun recentlyReleased(page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("recent:$page", AIRING_TTL, force) {
            aniList.collection("START_DATE_DESC", status = "RELEASING", page = page, perPage = 30, hideAdult = hideAdult)
        }

    suspend fun airing(page: Int = 1, force: Boolean = false): MediaPage =
        mediaPage("airing:$page", AIRING_TTL, force) {
            aniList.collection("POPULARITY_DESC", status = "RELEASING", page = page, perPage = 40, hideAdult = hideAdult)
        }

    /**
     * One day's episodes, from the published month when there is one and from AniList when there
     * is not.
     *
     * The metadata tree carries a file per month (see [KonohaClient.schedule]), so a viewer walking
     * a season through the calendar pays one CDN read for the whole month rather than one AniList
     * request per day opened. AniList stays underneath it for months the tree has not published —
     * anything past the generator's forward window, and everything before it was first run.
     */
    suspend fun schedule(day: java.time.LocalDate, force: Boolean = false): List<AiringSchedule> {
        val window = dayWindow(day)
        val published = publishedMonth(day, force)
        // Both paths cache unfiltered and filter here, so toggling the setting applies instantly.
        val schedules = if (published != null) {
            published.filter { it.airingAt in window }.map(KonohaAiring::toAiringSchedule)
        } else {
            cache.getOrFetch(
                key = "schedule:$day",
                serializer = ListSerializer(AiringSchedule.serializer()),
                ttlMs = SCHEDULE_TTL,
                forceRefresh = force,
            ) { aniList.airingSchedule(window.first, window.last + 1) }
        }
        return if (hideAdult) schedules.filterNot { it.media?.isAdult == true } else schedules
    }

    /**
     * Every airing from the start of [from] through the end of the day [days] - 1 after it.
     *
     * For Continue Watching, which needs to know when each followed title's next episode lands —
     * a week or more out for a weekly show — and asks once a day. Served from the published month
     * files, which a fortnight crosses at most one boundary of, so this is one or two CDN reads and
     * no AniList request at all. A day whose month the tree has not published is asked of AniList
     * only when it is [from] through the day after today: those are the days a row is about to come
     * back on, and the rest is not worth a burst of requests against AniList's limit. Skipping a day
     * only means a row is not hidden for it.
     */
    suspend fun upcomingAirings(
        from: java.time.LocalDate,
        days: Int,
        force: Boolean = false,
    ): List<AiringSchedule> {
        val range = (0 until days).map { from.plusDays(it.toLong()) }
        if (range.isEmpty()) return emptyList()
        val months = range.map(java.time.YearMonth::from).distinct()
            .associateWith { month -> publishedMonth(month.atDay(1), force) }
        val start = dayWindow(range.first()).first
        val end = dayWindow(range.last()).last
        // A month's file is padded with a week of its neighbours, so two files can carry one airing.
        val fromFiles = months.values.filterNotNull().flatten()
            .filter { it.airingAt in start..end }
            .distinctBy { it.id to it.episode }
            .map(KonohaAiring::toAiringSchedule)
        val lastLiveDay = java.time.LocalDate.now().plusDays(1)
        val live = range
            .filter { months[java.time.YearMonth.from(it)] == null && it <= lastLiveDay }
            .flatMap { day ->
                try {
                    schedule(day, force)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagnosticsLog.throwable("Upcoming airings unavailable for $day", e)
                    emptyList()
                }
            }
        val all = fromFiles + live
        return if (hideAdult) all.filterNot { it.media?.isAdult == true } else all
    }

    /**
     * AniList's own answer for [day], for a day that was served from a published month — or null
     * when it was not, because then it is already the live answer and re-asking would be a second
     * request for what is on screen.
     *
     * A published month is rebuilt once a day, which is right for planning and wrong for tonight: a
     * broadcaster who moves an episode after the build leaves a time that is not merely decorative,
     * because [com.anilili.data.reminder.ReminderManager] schedules its alarm from exactly this
     * field. So the day a viewer is about to act on is confirmed against AniList behind the render
     * that already happened. One day, one request, and the ordinary cache lifetime underneath it —
     * so returning to today repeatedly costs nothing.
     */
    suspend fun scheduleRevalidated(
        day: java.time.LocalDate,
        force: Boolean = false,
    ): List<AiringSchedule>? {
        // Already cached by the read this follows, so this is a cache hit rather than a re-download.
        if (publishedMonth(day, force = false) == null) return null
        val window = dayWindow(day)
        val live = cache.getOrFetch(
            key = "schedule:$day",
            serializer = ListSerializer(AiringSchedule.serializer()),
            ttlMs = SCHEDULE_TTL,
            forceRefresh = force,
        ) { aniList.airingSchedule(window.first, window.last + 1) }
        return if (hideAdult) live.filterNot { it.media?.isAdult == true } else live
    }

    /**
     * How many episodes air on each of [days], for the calendar grid's badges. Days nothing
     * answered for are absent from the map rather than present as a zero.
     *
     * A grid's own month covers every cell it draws — the neighbouring days that finish its first
     * and last weeks included, because a published month carries a week of padding either side — so
     * reading the middle day's month is what makes a whole calendar one file instead of three. Days
     * outside that reach, which a calendar grid never has, fall through to AniList with the rest.
     */
    suspend fun scheduleCounts(
        days: List<java.time.LocalDate>,
        force: Boolean = false,
    ): Map<java.time.LocalDate, ScheduleDayCount> {
        if (days.isEmpty()) return emptyMap()
        val anchor = days[days.size / 2]
        val published = publishedMonth(anchor, force)
        val covered: List<java.time.LocalDate> =
            if (published == null) emptyList() else days.filter { it in monthCoverage(anchor) }
        val fromFile: Map<java.time.LocalDate, ScheduleDayCount> = if (published == null) {
            emptyMap()
        } else {
            val zone = java.time.ZoneId.systemDefault()
            val byDay: Map<java.time.LocalDate, List<KonohaAiring>> = published.groupBy { airing ->
                java.time.Instant.ofEpochSecond(airing.airingAt).atZone(zone).toLocalDate()
            }
            covered.associateWith { day ->
                // Deliberately not `orEmpty()`: this class declares a private `Any?.orEmpty(): String`
                // for its log lines, and a member extension outranks the stdlib one — on a list it
                // silently yields that list's `toString()` rather than the list.
                val rows: List<KonohaAiring> = byDay[day] ?: emptyList()
                // No `atLeast` here: a published month holds every row for its days, so unlike the
                // single page AniList is asked for, there is no ceiling for a count to sit against.
                ScheduleDayCount(episodes = if (hideAdult) rows.count { !it.adult } else rows.size)
            }
        }
        val remaining = days - covered.toSet()
        return if (remaining.isEmpty()) fromFile else fromFile + aniListCounts(remaining, force)
    }

    /** The AniList fallback for [scheduleCounts]: one aliased request for the days given. */
    private suspend fun aniListCounts(
        days: List<java.time.LocalDate>,
        force: Boolean,
    ): Map<java.time.LocalDate, ScheduleDayCount> {
        // Cached as one entry for the whole run, because it is fetched as one request: the key
        // carries both ends so a different month — or the same month drawn with a different first
        // day of the week — cannot read back a list that was aligned to other dates.
        val counts = cache.getOrFetch(
            key = "schedule-counts:${days.first()}:${days.last()}:${days.size}",
            serializer = ListSerializer(AiringDayCount.serializer().nullable),
            ttlMs = SCHEDULE_TTL,
            forceRefresh = force,
        ) { aniList.airingCounts(days.map(::dayWindow)) }
        return days.withIndex().mapNotNull { (index, day) ->
            val count = counts.getOrNull(index) ?: return@mapNotNull null
            day to ScheduleDayCount(
                episodes = if (hideAdult) count.nonAdult else count.total,
                atLeast = count.truncated,
            )
        }.toMap()
    }

    /**
     * The published month covering [day], or null when the tree has none — which is the signal to
     * ask AniList instead. An empty file cannot be told from a missing one and is treated as
     * missing; a month with no airings at all does not occur.
     */
    private suspend fun publishedMonth(
        day: java.time.LocalDate,
        force: Boolean,
    ): List<KonohaAiring>? = runCatching { konoha.schedule(java.time.YearMonth.from(day).toString(), force) }
        .onFailure { DiagnosticsLog.throwable("Published schedule unavailable, falling back to AniList", it) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

    /**
     * The days a published month answers for. Mirrors `SCHEDULE_PAD_DAYS` in
     * `scripts/konoha_build.py`, which is the other half of this contract: the padding is what lets
     * one file serve a calendar grid whose first and last weeks reach into the months either side.
     */
    private fun monthCoverage(day: java.time.LocalDate): ClosedRange<java.time.LocalDate> {
        val month = java.time.YearMonth.from(day)
        return month.atDay(1).minusDays(SCHEDULE_PAD_DAYS)..month.atEndOfMonth().plusDays(SCHEDULE_PAD_DAYS)
    }

    /** [day] as the half-open epoch-second range it covers in the device's own zone. */
    private fun dayWindow(day: java.time.LocalDate): LongRange {
        val zone = java.time.ZoneId.systemDefault()
        // A day is not always 24 hours: both ends are resolved through the calendar so a clock
        // change neither drops an hour of airings nor borrows one from the neighbouring day.
        return day.atStartOfDay(zone).toEpochSecond() until day.plusDays(1).atStartOfDay(zone).toEpochSecond()
    }

    /**
     * hanime's library is held on the device, so its hits are folded into ordinary search results
     * rather than living behind a section of their own. They are tagged adult, which is why this
     * only runs once the viewer has turned adult content on, and only on the first page — the
     * catalogue is searched whole, so there is nothing further to page through.
     *
     * Folded outside [mediaPage] on purpose: what the device already knows should not depend on
     * what AniList answered, which for an adult title while signed in is routinely nothing.
     */
    private suspend fun withHanimeResults(query: String, remote: List<Media>, page: Int): List<Media> {
        if (hideAdult || page != 1 || query.isBlank()) return remote
        val catalogue = runCatching { anivexa.hanimeCatalogue() }.getOrElse {
            DiagnosticsLog.throwable("Hanime catalogue unavailable for search", it)
            return remote
        }
        val hits = searchHanimeCatalogue(query, catalogue)
        if (hits.isEmpty()) return remote
        DiagnosticsLog.event("Hanime search hits=${hits.size} query=${query.take(40)}")
        // AniList first: a plain title search should still lead with the mainstream match.
        return (remote + hits).distinctBy { it.id }
    }

    suspend fun discover(filters: DiscoverFilters, page: Int = 1, force: Boolean = false): MediaPage {
        // Read at request time, like the dub index below: a title finished a minute ago is already
        // watched. Its hash joins the cache key so a changed library is never answered from a page
        // cached before it changed.
        val watchedIds = WatchedTitles.excludedIds(filters.hideWatched)
        val watchedKey = if (watchedIds.isEmpty()) "" else "${filters.hideWatched.storedValue}${watchedIds.hashCode()}"
        val remote = try {
            mediaPage("discover:${discoverCacheKey(filters, hideAdult)}:$watchedKey:$page", COLLECTION_TTL, force) {
                // Read at request time rather than held: the CDN top-up lands mid-session, and a
                // filter that kept the startup copy would go on hiding titles the badges show.
                aniList.discover(
                    filters,
                    page,
                    hideAdult = hideAdult,
                    dubbedIds = if (filters.dubbedOnly) DubIndex.ids else emptySet(),
                    excludedIds = watchedIds,
                )
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            val offline = localCatalog.discover(filters, page, hideAdult)
            if (offline.items.isEmpty()) throw e
            DiagnosticsLog.event(
                "Browse falling back to bundled catalogue: ${e.message ?: e.javaClass.simpleName}",
            )
            offline
        }
        val padded = remote.copy(items = withLocalTitleMatches(filters, remote.items, page))
        // A plain title search is the one question the device-side hanime catalogue can answer.
        // Once a year, format or studio is also set, the viewer asked AniList something it has no
        // fields for, and appending unfiltered hits would answer a different question. Hiding
        // watched titles is not such a question — it is answered here, below, for every source.
        val answered = if (filters.copy(hideWatched = WatchedFilter.OFF).activeCount > 0) {
            padded
        } else {
            padded.copy(
                items = withOnePaceResult(filters.query, withHanimeResults(filters.query, padded.items, page), page),
            )
        }
        // AniList already left the account's titles out; this catches what it could not know — the
        // bundled and device-side results, and titles played through on this device.
        if (filters.hideWatched == WatchedFilter.OFF) return answered
        return answered.copy(items = answered.items.filterNot { WatchedTitles.hides(it, filters.hideWatched) })
    }

    /**
     * One page of the random pick's pool. Cached like any catalogue page, so a run of rolls is
     * served from one request; the pick shuffles the page itself, which is where the randomness is.
     */
    suspend fun randomPool(page: Int): MediaPage =
        mediaPage("random:v1:$page", COLLECTION_TTL) { aniList.randomPool(page, hideAdult = hideAdult) }

    /**
     * One Pace, when the query names it or One Piece. It has no AniList record for a search to
     * find, so without this it could only be reached through a link.
     *
     * Asking for it by name puts it first; a One Piece search keeps AniList's own entry on top and
     * offers the recut straight after it, since that is the title the viewer typed. Built without a
     * network request, so it costs a search nothing.
     */
    private fun withOnePaceResult(query: String, items: List<Media>, page: Int): List<Media> {
        if (page != 1 || !matchesOnePaceQuery(query) || items.any { isOnePaceMediaId(it.id) }) return items
        val onePace = onePaceMedia(episodeCount = null)
        val byName = normalizeHanimeTitle(query).replace(" ", "").let { "onepace".startsWith(it) || it.contains("onepace") }
        return if (byName || items.isEmpty()) listOf(onePace) + items else listOf(items.first(), onePace) + items.drop(1)
    }

    /**
     * Fill a thin title search out of the bundled snapshot.
     *
     * AniList matches whole words and nothing less: `search: "narut"` returns an empty page, and
     * `"nar"` returns *Omoide no Marnie*. So a viewer spelling a title out — on a remote, the only
     * way to spell one — watched the grid empty as they typed and read it as search being broken,
     * with the results arriving only on the last letter of the word. The snapshot matches prefixes,
     * so it can cover exactly the keystrokes AniList cannot.
     *
     * Only when the live answer came back thin, and only on the first page. A query AniList
     * answered properly is already the better answer — it knows what aired this week and the
     * snapshot was fixed at release — so a finished word ("naruto") fills a page on its own and
     * nothing is appended. Padding is for the half-typed word that returned two rows or none.
     */
    private suspend fun withLocalTitleMatches(
        filters: DiscoverFilters,
        remote: List<Media>,
        page: Int,
    ): List<Media> {
        if (!shouldPadFromSnapshot(filters.query, page, remote.size)) return remote
        // The snapshot answers the filters it holds fields for and returns nothing for the rest,
        // so passing them through cannot contradict the chips that are on screen.
        val local = runCatching { localCatalog.discover(filters, page, hideAdult) }.getOrElse {
            it.rethrowIfCancellation()
            DiagnosticsLog.throwable("Local title padding failed", it)
            return remote
        }
        if (local.items.isEmpty()) return remote
        DiagnosticsLog.event(
            "Local title padding remote=${remote.size} local=${local.items.size} " +
                "query=${filters.query.take(40)}",
        )
        // AniList first, always: what it did find is current, and these are a release-time snapshot.
        return (remote + local.items).distinctBy(Media::id)
    }

    /** Titles for the search box's autocomplete; see [LocalCatalog.titleSuggestions]. */
    suspend fun titleSuggestions(query: String): List<TitleSuggestion> {
        val bundled = withMisspelledTitles(query, localCatalog.titleSuggestions(query, hideAdult))
        // The snapshot is AniList's, so it cannot suggest One Pace by itself.
        if (!matchesOnePaceQuery(query) || bundled.any { isOnePaceMediaId(it.id) }) return bundled
        return listOf(TitleSuggestion(ONE_PACE_MEDIA_ID, "One Pace")) + bundled
    }

    /**
     * Tops a short autocomplete list up with the titles a misspelling was most likely aiming at, so
     * "naurto" still offers Naruto under the box. Exact matches stay first: a viewer who typed
     * something real meant that before they meant anything near it.
     */
    private suspend fun withMisspelledTitles(query: String, exact: List<TitleSuggestion>): List<TitleSuggestion> {
        if (exact.size >= TITLE_SUGGESTION_LIMIT || query.trim().length < MIN_SUGGESTION_CHARS) return exact
        val near = runCatching { localCatalog.spelling(query, hideAdult) }.getOrElse {
            it.rethrowIfCancellation()
            null
        } ?: return exact
        return (exact + near.titles).distinctBy(TitleSuggestion::id).take(TITLE_SUGGESTION_LIMIT)
    }

    /**
     * What a title search that found little or nothing was most likely meant to be, or null when it
     * reads like something the catalogue holds. See [TitleSpeller].
     */
    suspend fun spellingSuggestion(query: String): String? =
        runCatching { localCatalog.spelling(query, hideAdult)?.text }.getOrElse {
            it.rethrowIfCancellation()
            DiagnosticsLog.throwable("Spelling suggestion failed", it)
            null
        }

    suspend fun searchStudios(query: String): List<StudioNode> = aniList.searchStudios(query)

    /**
     * Wide pictures of [anilistId]'s show for the background picker, best first: TMDB's (text-free
     * ones leading), then TheTVDB's fanart through AniZip, then AniList's banner. The banner is a
     * 1900x400 strip, so it is offered last and only because some titles have nothing else.
     *
     * Each source fails on its own — a 429 from AniZip or no TMDB match just leaves the others.
     */
    suspend fun animeBackdrops(anilistId: Int): List<AnimeBackdropChoice> = coroutineScope {
        val info = animeInfo(anilistId) ?: return@coroutineScope emptyList()
        val fromTmdb = async { tmdb.backdrops(info, konoha.tmdbRefFor(anilistId)) }
        val fromTvdb = async { aniZip.fanart(anilistId) }
        val choices = fromTmdb.await().map { AnimeBackdropChoice(it, tmdbThumbnail(it), "TMDB") } +
            fromTvdb.await().map { AnimeBackdropChoice(it, it, "TheTVDB") } +
            listOfNotNull(info.bannerImage).map { AnimeBackdropChoice(it, it, "AniList banner") }
        choices.distinctBy { it.url }
    }

    /**
     * A landscape picture for the TV hero, or null where the title has none and keeps its poster.
     *
     * AniList's banner when there is one; most titles without one are new seasons, which AniList
     * gives a cover long before a banner. The metadata tree's `backdrops.json` comes next: one
     * cached file for every title, already chosen offline to avoid pictures with text on them (see
     * `pick_backdrop` in `scripts/konoha_build.py`). It covers only what the offline AniZip pass has
     * crawled, so a title it misses is asked about live: TheTVDB's fanart through AniZip first,
     * one keyless request per AniList id with no matching — of 30 trending titles on 2026-10-03, 14
     * had no banner and AniZip had fanart for 8 — and TMDB last, whose search-and-match costs
     * several requests per title. Both live answers are cached for a week, failures are not.
     */
    suspend fun heroBackdrop(media: Media): String? {
        media.bannerImage?.let { return it }
        val published = try {
            konoha.heroBackdrops()[media.id]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DiagnosticsLog.throwable("Hero backdrops index failed", e)
            null
        }
        published?.let { return it }
        aniZip.fanart(media.id).firstOrNull()?.let { return it }
        // The id map is an asset read on first use, so it stays off the caller's thread.
        val hint = withContext(Dispatchers.IO) { konoha.tmdbRefFor(media.id) }
        return tmdb.backdrops(media, hint).firstOrNull()
    }

    suspend fun discoverOptions(): DiscoverOptions {
        val adultHidden = hideAdult
        return cache.getOrFetch(
            key = "discover-options:${if (adultHidden) "sfw" else "all"}",
            serializer = DiscoverOptions.serializer(),
            ttlMs = OPTIONS_TTL,
        ) { aniList.discoverOptions(adultHidden) }
    }

    // ---- authenticated (AniList login) ----
    suspend fun viewer() = aniList.viewer()

    /**
     * Whether the signed-in AniList account permits adult content. Null when nobody is signed in.
     *
     * This is not a second copy of [hideAdult] — it is the account's own switch, which AniList
     * enforces on its side and this app cannot argue with. Read only to say so.
     */
    suspend fun aniListDisplaysAdultContent(): Boolean? = aniList.viewerDisplaysAdultContent()

    /**
     * Fetch saved-title release metadata in AniList's native 50-item batches. This replaces the
     * old one-request-per-title startup sync and keeps successful batches cached for a day.
     */
    suspend fun mediaByIds(ids: Collection<Int>, force: Boolean = false): List<Media> {
        val media = mutableListOf<Media>()
        ids.asSequence().filter { it > 0 }.distinct().sorted().chunked(50).forEach { chunk ->
            media += cache.getOrFetch(
                    key = batchCacheKey("anilist_batch:v2:", chunk),
                    serializer = ListSerializer(Media.serializer()),
                    ttlMs = INFO_TTL,
                    forceRefresh = force,
                ) { aniList.mediaByIds(chunk) }
        }
        return media
    }

    suspend fun mediaByMalIds(malIds: List<Int>) = aniList.mediaByMalIds(malIds)
    suspend fun notifications(markAllRead: Boolean = false) = aniList.notifications(markAllRead)
    suspend fun favouriteAnime() = aniList.favouriteAnime()
    suspend fun userAnimeList(userId: Int) = aniList.userAnimeList(userId)
    suspend fun aniListActivityHistory(userId: Int) = aniList.activityHistory(userId)
    suspend fun saveAniListProgress(mediaId: Int, progress: Int, totalEpisodes: Int?) =
        aniList.syncMediaListProgress(mediaId, progress, totalEpisodes)
    suspend fun syncSavedAnime(mediaId: Int, saved: Boolean) = aniList.syncSavedAnime(mediaId, saved)

    /** Move one title on whichever anime-list service is currently signed in. */
    suspend fun updateAnimeListStatus(anilistId: Int, status: String) {
        require(status in LIST_STATUSES) { "Unsupported anime list status: $status" }
        val service = AccountService.active ?: AnililiError.ACCOUNT_SIGN_IN_REQUIRED.raise()
        when (service) {
            AccountService.ANILIST -> aniList.updateMediaListStatus(anilistId, status)
            AccountService.MAL -> {
                val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
                    ?: error("This anime could not be matched to MyAnimeList")
                // Starting or finishing a rewatch is the only move that depends on the entry.
                val rewatch = if (status == "REPEATING" || status == "COMPLETED") {
                    mal.listStatus(malId)
                        ?.let { planRewatchMove(MalClient.anilistStatus(it), it.numTimesRewatched, status) }
                        ?: RewatchMove()
                } else {
                    RewatchMove()
                }
                mal.updateListStatus(
                    malId,
                    progress = 0.takeIf { rewatch.resetProgress },
                    status = MalClient.malStatus(status),
                    isRewatching = status == "REPEATING",
                    numTimesRewatched = rewatch.repeat,
                    strictProgress = !rewatch.resetProgress,
                )
            }
        }
        DiagnosticsLog.event("${service.label} status moved id=$anilistId status=$status")
        com.anilili.data.remote.SimklTracker.status(anilistId, status)
    }

    /**
     * The viewer's own score for one title, out of ten, on whichever service is signed in; 0 clears it.
     *
     * Both services score an entry, and an entry is one season on each — AniList and MyAnimeList
     * both list every season as its own title — so this is a season's score on either. Neither API
     * takes a score for a single episode: MyAnimeList's episode ratings are polls in its forum
     * threads, not list data.
     *
     * Whole numbers only, because MyAnimeList holds nothing finer and the two should never disagree
     * about what was given. Callers offer it only for a title already on the account, so a score can
     * never invent an entry on a list the viewer did not choose.
     */
    suspend fun updateAnimeListScore(anilistId: Int, score: Int) {
        require(score in 0..10) { "Unsupported anime list score: $score" }
        val service = AccountService.active ?: AnililiError.ACCOUNT_SIGN_IN_REQUIRED.raise()
        when (service) {
            AccountService.ANILIST -> aniList.updateMediaListScore(anilistId, score)
            AccountService.MAL -> {
                val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
                    ?: error("This anime could not be matched to MyAnimeList")
                mal.updateListStatus(malId, score = score)
            }
        }
        DiagnosticsLog.event("${service.label} score set id=$anilistId score=$score")
    }

    /**
     * The viewer's episode count for one title, read live from whichever service is signed in, with
     * the most episodes that service will accept. Null when the title is not on the account.
     *
     * Live rather than from the library snapshot: the snapshot only keeps counts for Watching, and
     * this is read just before the viewer changes the number, so it has to be the account's own.
     * The ceiling is MyAnimeList's count on MAL, which refuses anything above it — AniList's and
     * MAL's idea of a season's length differ often enough for that to matter.
     */
    suspend fun animeListProgress(anilistId: Int): AnimeListProgress? {
        val service = AccountService.active ?: AnililiError.ACCOUNT_SIGN_IN_REQUIRED.raise()
        return when (service) {
            AccountService.ANILIST -> aniList.mediaListProgress(anilistId)?.let { progress ->
                AnimeListProgress(progress, animeInfo(anilistId)?.episodes?.takeIf { it > 0 })
            }
            AccountService.MAL -> {
                val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
                    ?: error("This anime could not be matched to MyAnimeList")
                val remote = mal.animeStatus(malId)
                remote.listStatus?.let { AnimeListProgress(it.numEpisodesWatched, remote.totalEpisodes) }
            }
        }
    }

    /**
     * Set one title's episode count to exactly [progress] on whichever service is signed in, lower
     * as well as higher. The status is left as it is — it has its own row in the same menu.
     *
     * Playback's push ([saveMalProgress], [saveAniListProgress]) only ever moves forward, so a count
     * that ran ahead — a mis-tap on an episode's eye, a stray import — could only be lowered on the
     * service's own website. This is the hand correction for that. Callers offer it only for a title
     * already on the account, for the same reason the score is: neither service should be handed an
     * entry on a list the viewer never chose.
     */
    suspend fun setAnimeListProgress(anilistId: Int, progress: Int) {
        require(progress >= 0) { "Unsupported episode count: $progress" }
        val service = AccountService.active ?: AnililiError.ACCOUNT_SIGN_IN_REQUIRED.raise()
        when (service) {
            AccountService.ANILIST -> aniList.setMediaListProgress(anilistId, progress)
            AccountService.MAL -> {
                val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
                    ?: error("This anime could not be matched to MyAnimeList")
                mal.updateListStatus(malId, progress = progress)
            }
        }
        DiagnosticsLog.event("${service.label} episode count set id=$anilistId progress=$progress")
    }

    /**
     * Take one title off whichever anime-list service is signed in — the entry itself, not a status.
     *
     * The status moves above can only ever relabel a row; nothing in the app could delete one, so a
     * title added by mistake (or a stray import) was permanent from here and had to be removed on the
     * website. Progress, score and dates go with the entry on both services and neither offers an
     * undo, which is why every caller confirms first.
     *
     * Returns false when the account had no entry to begin with. Both services need a read before
     * they can delete — AniList's mutation is keyed by entry id, MAL 404s on an anime the viewer
     * never added — so that read is also what separates "removed" from "was never there", and the UI
     * says so rather than claiming a removal that did not happen.
     */
    suspend fun deleteAnimeListEntry(anilistId: Int): Boolean {
        val service = AccountService.active ?: AnililiError.ACCOUNT_SIGN_IN_REQUIRED.raise()
        val deleted = when (service) {
            AccountService.ANILIST -> aniList.deleteMediaListEntry(anilistId)
            AccountService.MAL -> {
                val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
                    ?: error("This anime could not be matched to MyAnimeList")
                if (mal.listStatus(malId) == null) {
                    false
                } else {
                    mal.deleteListEntry(malId)
                    true
                }
            }
        }
        DiagnosticsLog.event("${service.label} entry removed id=$anilistId deleted=$deleted")
        // Simkl mirrors the removal whether or not this account had the entry: the viewer asked for
        // the title to be off their list, and Simkl may well have it from an earlier episode.
        com.anilili.data.remote.SimklTracker.removed(anilistId)
        return deleted
    }

    /**
     * Mark every episode watched, or undo that back to Watching, on the active list service.
     *
     * Marking writes full progress plus COMPLETED in one call. Unmarking restores only the
     * status: the episode count is deliberately left where it is, because lowering someone's
     * progress behind their back is worse than a finished count on a Watching row — and both
     * services let the viewer trim it themselves from their own UI. A null [totalEpisodes]
     * (airing or announced titles that don't know their count yet) marks with status alone.
     */
    suspend fun setAnimeWatched(anilistId: Int, watched: Boolean, totalEpisodes: Int?) {
        val service = AccountService.active ?: AnililiError.ACCOUNT_SIGN_IN_REQUIRED.raise()
        if (!watched) {
            updateAnimeListStatus(anilistId, "CURRENT")
            return
        }
        when (service) {
            AccountService.ANILIST -> aniList.setWatched(anilistId, totalEpisodes)
            AccountService.MAL -> {
                val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
                    ?: error("This anime could not be matched to MyAnimeList")
                // MAL clamps num_watched_episodes to its own episode count, and updateListStatus
                // verifies what came back — so AniList's count fails that check outright whenever
                // the two services disagree about the length of a series, after the status has
                // already been written. Ask MAL how long it thinks the show is. The same read says
                // whether this finishes a rewatch, which is then counted.
                val remote = runCatching { mal.animeStatus(malId) }.getOrNull()
                val rewatch = remote?.listStatus
                    ?.let { planRewatchMove(MalClient.anilistStatus(it), it.numTimesRewatched, "COMPLETED") }
                mal.updateListStatus(
                    malId,
                    progress = remote?.totalEpisodes ?: totalEpisodes,
                    status = MalClient.malStatus("COMPLETED"),
                    isRewatching = false,
                    numTimesRewatched = rewatch?.repeat,
                )
            }
        }
        DiagnosticsLog.event(
            "${service.label} marked watched id=$anilistId episodes=${totalEpisodes ?: "unknown"}",
        )
        com.anilili.data.remote.SimklTracker.status(anilistId, "COMPLETED")
    }

    suspend fun syncSavedAnime(mediaIds: Collection<Int>) {
        val viewerId = AuthManager.viewerId() ?: aniList.viewer()?.id ?: return
        aniList.syncSavedAnime(mediaIds, viewerId)
    }

    // ---- custom lists (AniList lists, MyAnimeList tags) ----

    /** The signed-in AniList account's whole collection, read fresh for a custom-list push. */
    suspend fun aniListCollectionForCustomLists(): com.anilili.data.model.MediaListCollection {
        val viewerId = AuthManager.viewerId() ?: aniList.viewer()?.id
            ?: error("Couldn't load your AniList account")
        return aniList.userAnimeList(viewerId) ?: error("Couldn't load your AniList library")
    }

    suspend fun setAniListCustomListNames(names: List<String>) = aniList.setCustomListNames(names)

    suspend fun saveAniListCustomLists(writes: List<com.anilili.data.remote.CustomListWrite>) =
        aniList.saveCustomListMemberships(writes)

    suspend fun deleteAniListEntries(entryIds: List<Int>) = aniList.deleteMediaListEntries(entryIds)

    /** The MyAnimeList id for an AniList title, or null when AniList knows of none. */
    suspend fun malIdFor(anilistId: Int): Int? = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }

    /**
     * Give a MyAnimeList entry exactly [tags]. MAL cannot hold a title with no status, so one it
     * does not have yet is added as Plan to Watch — the one status that claims nothing was watched.
     */
    suspend fun malSetTags(malId: Int, tags: List<String>, create: Boolean) {
        mal.updateListStatus(malId, status = "plan_to_watch".takeIf { create }, tags = tags)
        DiagnosticsLog.event("MAL tags written malId=$malId tags=${tags.size} created=$create")
    }

    // ---- authenticated (MyAnimeList login) ----

    /** The MAL account shaped like an AniList [Viewer] so the profile UI stays shared. */
    suspend fun malViewer(): Viewer {
        val user = mal.viewer()
        val stats = user.animeStatistics
        return Viewer(
            id = user.id,
            name = user.name,
            avatar = UserAvatar(large = user.picture),
            bannerImage = null,
            createdAt = user.joinedAt?.let { raw ->
                runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
            },
            statistics = ViewerStatistics(
                anime = AnimeStat(
                    count = stats?.numItems ?: 0,
                    minutesWatched = ((stats?.numDaysWatched ?: 0.0) * 1440).toLong(),
                    meanScore = stats?.meanScore ?: 0.0,
                ),
            ),
        )
    }

    /**
     * The MAL anime list as AniList-shaped [MediaListEntry]s. MAL ids are joined back to
     * AniList media in day-cached batches of 50; the odd title AniList doesn't know is dropped.
     */
    suspend fun malAnimeList(): List<MediaListEntry> = coroutineScope {
        val entries = mal.animeList()
        val malIds = entries.map { it.malId }.distinct()
        val mapGate = Semaphore(10)

        // 1. Resolve MAL IDs to AniList IDs via Konoha sharded CDN mappings in parallel
        val deferredMappings = malIds.map { malId ->
            async {
                mapGate.withPermit {
                    val anilistId = runCatching { konoha.resolveAnilistIdFromMal(malId) }.getOrNull()
                    malId to anilistId
                }
            }
        }
        val resolvedMap = deferredMappings.awaitAll().toMap()
        
        val mappedAnilistIds = resolvedMap.values.filterNotNull().distinct()
        val unmappedMalIds = resolvedMap.filter { it.value == null }.keys.toList()

        val byMalId = mutableMapOf<Int, Media>()

        // 2. Fetch Media details for resolved AniList IDs in chunks
        if (mappedAnilistIds.isNotEmpty()) {
            mappedAnilistIds.sorted().chunked(50).forEach { chunk ->
                cache.getOrFetch(
                    key = batchCacheKey("anilist_batch:v3:", chunk),
                    serializer = ListSerializer(Media.serializer()),
                    ttlMs = MAL_MAP_TTL,
                ) { aniList.mediaByIds(chunk) }
                    .forEach { media ->
                        media.idMal?.let { byMalId[it] = media }
                        
                        // Fallback mapping matching in case Media's internal idMal is incorrect/missing
                        val malId = resolvedMap.filter { it.value == media.id }.keys.firstOrNull()
                        if (malId != null) {
                            byMalId[malId] = media
                        }
                    }
            }
        }

        // 3. Fallback: Query AniList directly for any IDs that failed to map in Konoha
        if (unmappedMalIds.isNotEmpty()) {
            unmappedMalIds.sorted().chunked(50).forEach { chunk ->
                cache.getOrFetch(
                    key = batchCacheKey("malmap:v2:", chunk),
                    serializer = ListSerializer(Media.serializer()),
                    ttlMs = MAL_MAP_TTL,
                ) { aniList.mediaByMalIds(chunk) }
                    .forEach { media -> media.idMal?.let { byMalId[it] = media } }
            }
        }

        // 4. Return reconstructed entries
        var filledFromMal = 0
        val reconstructed = entries.mapNotNull { entry ->
            val mapped = byMalId[entry.malId] ?: return@mapNotNull null
            // MyAnimeList and AniList do not agree about where one season ends and the next
            // begins, and the id mapping above is a best effort across three fallbacks. Between
            // them a neighbouring or combined MAL entry can land on this AniList season carrying
            // its own, larger, watched count -- which is how a 24-episode season came to report
            // "Episode 25 of 24, 100%". A count past the end of the season is not a fact about
            // this title, so it is held at the finale rather than published as-is.
            val total = remoteEpisodeCeiling(mapped.episodes, entry.totalEpisodes)
            if (total != null && total != mapped.episodes) filledFromMal++
            // The ceiling has to ride on the media, not merely bound the figure here: the Continue
            // Watching seed and the row reconciler both read their own limit off `episodes`, and an
            // absent count is what let both of them wave a finished season through in the first
            // place.
            val media = if (total == mapped.episodes) mapped else mapped.copy(episodes = total)
            val progress = clampRemoteProgress(entry.progress, total)
            if (progress != entry.progress) {
                DiagnosticsLog.event(
                    "MAL progress clamped malId=${entry.malId} anilistId=${media.id} " +
                        "reported=${entry.progress} episodes=$total",
                )
            }
            MediaListEntry(
                id = entry.malId,
                progress = progress,
                score = entry.score,
                status = entry.status,
                updatedAt = entry.updatedAt,
                media = media,
                // Every tag, hidden ones included: the list store filters for display, and a
                // tag write has to carry the hidden ones back or it would erase them.
                customLists = entry.tags,
            )
        }
        if (filledFromMal > 0) {
            DiagnosticsLog.event("MAL episode counts taken from MyAnimeList entries=$filledFromMal")
        }
        reconstructed
    }

    /** Mirrors the device Save button on MAL without damaging an existing list state. */
    suspend fun malSyncSavedAnime(anilistId: Int, saved: Boolean) {
        val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 }
        if (malId == null) {
            DiagnosticsLog.event("MAL saved sync skipped id=$anilistId: no MAL id on AniList")
            return
        }
        val current = mal.listStatus(malId)
        when {
            saved && current == null -> {
                mal.updateListStatus(malId, status = "plan_to_watch")
                DiagnosticsLog.event("MAL saved sync added malId=$malId (id=$anilistId)")
            }
            !saved && current?.status == "plan_to_watch" -> {
                mal.deleteListEntry(malId)
                DiagnosticsLog.event("MAL saved sync removed malId=$malId (id=$anilistId)")
            }
            else -> DiagnosticsLog.event(
                "MAL saved sync no-op malId=$malId (id=$anilistId) saved=$saved currentStatus=${current?.status}",
            )
        }
    }

    /** Batch push: one list fetch, then add only the titles MAL doesn't have yet. */
    suspend fun malSyncSavedAnime(anilistIds: Collection<Int>) {
        if (anilistIds.isEmpty()) return
        val onMal = mal.animeList().map { it.malId }.toSet()
        anilistIds.forEach { id ->
            runCatching {
                val malId = animeInfo(id)?.idMal?.takeIf { it > 0 }
                when {
                    malId == null -> DiagnosticsLog.event("MAL saved sync skipped id=$id: no MAL id on AniList")
                    malId !in onMal -> {
                        mal.updateListStatus(malId, status = "plan_to_watch")
                        DiagnosticsLog.event("MAL saved sync added malId=$malId (id=$id)")
                    }
                }
            }.onFailure { DiagnosticsLog.throwable("MAL saved sync failed id=$id", it) }
        }
    }

    /**
     * Update MAL watched progress with the same non-regression policy as the AniList sync.
     * Every no-op is explicit so callers can distinguish a missing mapping from already-synced
     * progress, and [MalClient] verifies the PATCH response before this reports [Updated].
     */
    suspend fun saveMalProgress(
        anilistId: Int,
        progress: Int,
        totalEpisodes: Int?,
    ): MalProgressSyncResult {
        if (progress < 1) {
            return MalProgressSyncResult.Skipped(
                anilistId = anilistId,
                targetProgress = progress,
                reason = MalProgressSyncSkipReason.INVALID_PROGRESS,
            )
        }
        val media = animeInfo(anilistId)
        val malId = media?.idMal?.takeIf { it > 0 } ?: return MalProgressSyncResult.Skipped(
            anilistId = anilistId,
            targetProgress = progress,
            reason = MalProgressSyncSkipReason.MISSING_MAL_ID,
        )
        val remote = mal.animeStatus(malId)
        val malCurrent = remote.listStatus
        val current = malCurrent?.let {
            MediaListProgressSnapshot(
                id = malId,
                status = MalClient.anilistStatus(it),
                progress = it.numEpisodesWatched,
                repeat = it.numTimesRewatched,
            )
        }
        // MAL will not store a count above its own episode total, and MalClient verifies what the
        // PATCH confirmed — so an unclamped target threw, the worker spent every retry it had, and
        // that title never synced again. The two services disagree often enough for this to matter:
        // a split cour AniList counts as one 24-episode entry can be two 12-episode entries on MAL.
        val target = remote.totalEpisodes?.let { minOf(progress, it) } ?: progress
        if (target != progress) {
            DiagnosticsLog.event(
                "MAL progress clamped id=$anilistId malId=$malId requested=$progress " +
                    "malEpisodes=${remote.totalEpisodes}",
            )
        }
        // MAL's own count decides whether this finishes the series on MAL, for the same reason.
        val total = remote.totalEpisodes ?: totalEpisodes ?: media.episodes
        val update = planMediaListProgressUpdate(current, target, total)
            ?: return MalProgressSyncResult.Skipped(
                anilistId = anilistId,
                targetProgress = progress,
                reason = malProgressSkipReason(current?.status, current?.progress, target),
                malId = malId,
                remoteProgress = current?.progress,
            )
        val rewatching = current?.status == "REPEATING"
        val confirmed = mal.updateListStatus(
            malId,
            progress = update.progress,
            status = update.status?.let(MalClient::malStatus),
            // Finishing a rewatch takes the flag off and counts it; mid-rewatch, the flag stays.
            isRewatching = false.takeIf { update.repeat != null },
            numTimesRewatched = update.repeat,
            strictProgress = !rewatching || update.repeat != null,
        )
        return MalProgressSyncResult.Updated(
            anilistId = anilistId,
            targetProgress = progress,
            malId = malId,
            previousProgress = malCurrent?.numEpisodesWatched,
            confirmedProgress = confirmed.numEpisodesWatched,
            status = update.status,
            confirmedStatus = confirmed.status,
        )
    }

    /**
     * Filler episode numbers for a title: the published filler index first, MAL via Jikan for any
     * title the index has never answered. Empty with neither. Throws when Jikan fails.
     */
    /**
     * When each of [anilistId]'s episodes that aired in the last
     * [com.anilili.data.model.EPISODE_WATCHABLE_AFTER_SEC] went out, keyed by episode number, from
     * the published airing calendar — the file the Schedule screen reads, so it costs AniList
     * nothing. Empty for anything not airing, and on failure.
     */
    suspend fun recentAirings(anilistId: Int, nowSec: Long = System.currentTimeMillis() / 1000): Map<Int, Long> {
        val window = (nowSec - com.anilili.data.model.EPISODE_WATCHABLE_AFTER_SEC)..nowSec
        val today = java.time.Instant.ofEpochSecond(nowSec).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        // A month's file carries a week either side of it, so today's month covers the window.
        val month = publishedMonth(today, force = false) ?: return emptyMap()
        return month
            .filter { it.id == anilistId && it.airingAt in window }
            .associate { it.episode to it.airingAt }
    }

    suspend fun fillerEpisodes(anilistId: Int?, malId: Int?): Set<Int> {
        if (anilistId != null && anilistId > 0) {
            val index = runCatching { konoha.fillerIndex() }
                .onFailure { it.rethrowIfCancellation(); DiagnosticsLog.throwable("Filler index failed", it) }
                .getOrNull()
            index?.get(anilistId)?.let { return it }
        }
        return malFillerEpisodes(malId)
    }

    /** MAL filler episode numbers via Jikan; cached a week, empty with no MAL id. Throws on failure. */
    private suspend fun malFillerEpisodes(malId: Int?): Set<Int> {
        if (malId == null || malId <= 0) return emptySet()
        // Every caller waits at most a few seconds, and Naruto's list is three Jikan pages while One
        // Piece's is twelve — so a fetch tied to the caller was cancelled by its timeout every time,
        // nothing was ever cached, and the next visit started from page one again. The fetch runs
        // on its own scope instead: the caller stops waiting, the fetch finishes and is cached for
        // the next open. One fetch per title at a time.
        val fetch = fillerFetches.getOrPut(malId) {
            fillerScope.async(start = CoroutineStart.LAZY) {
                cache.getOrFetch(
                    key = "filler:$malId",
                    serializer = SetSerializer(Int.serializer()),
                    ttlMs = OPTIONS_TTL,
                ) { jikan.fillerEpisodes(malId) }
            }.also { deferred -> deferred.invokeOnCompletion { fillerFetches.remove(malId, deferred) } }
        }
        fetch.start()
        return fetch.await()
    }

    private val fillerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fillerFetches = ConcurrentHashMap<Int, Deferred<Set<Int>>>()

    /** Community intro/outro markers via AniSkip; cached when known, with service failures exposed. */
    suspend fun skipTimes(
        anilistId: Int,
        episode: Double,
        episodeLengthSeconds: Double = 0.0,
    ): SkipTimes? {
        if (episode % 1.0 != 0.0 || episode < 1) return null
        if (!episodeLengthSeconds.isFinite() || episodeLengthSeconds < 0.0) return null
        val malId = animeInfo(anilistId)?.idMal?.takeIf { it > 0 } ?: return null
        val durationMs = (episodeLengthSeconds * 1000.0).roundToLong()
        return cache.getOrFetch(
            // v3: entries now carry recap windows and the segment ids voting needs, so a v2 row
            // would decode into timings a viewer cannot report.
            key = "aniskip:v3:$malId:${episode.toInt()}:$durationMs",
            serializer = SkipTimes.serializer().nullable,
            ttlMs = OPTIONS_TTL,
            cacheIf = { it != null },
        ) {
            withContext(Dispatchers.IO) {
                val number = episode.toInt()
                mirroredSkipTimes(anilistId, malId, number, episodeLengthSeconds)
                    ?: aniSkip.skipTimes(malId, number, episodeLengthSeconds)
                    ?: redirectedSkipTimes(malId, number, episodeLengthSeconds)
            }
        }
    }

    /**
     * The episode's timings from the tree's mirror of AniSkip, when it holds any.
     *
     * An episode the mirror lacks — aired since it was built, or never submitted then — is not taken
     * as "none": AniSkip is still asked, because a submission may have arrived since.
     */
    private suspend fun mirroredSkipTimes(
        anilistId: Int,
        malId: Int,
        episode: Int,
        episodeLengthSeconds: Double,
    ): SkipTimes? {
        val file = konoha.skipTimes(anilistId)?.takeIf { it.mal == malId } ?: return null
        val candidates = (file.episodes[episode.toString()] ?: emptyList()).mapNotNull { row ->
            runCatching {
                AniSkipClient.AniSkipSegment(
                    type = row[0].jsonPrimitive.content,
                    start = row[1].jsonPrimitive.double,
                    end = row[2].jsonPrimitive.double,
                    episodeLength = row[3].jsonPrimitive.doubleOrNull?.takeIf { it > 0.0 },
                    skipId = row.getOrNull(4)?.jsonPrimitive?.contentOrNull,
                )
            }.getOrNull()
        }
        if (candidates.isEmpty()) return null
        return aniSkip.skipTimesFrom(candidates, episodeLengthSeconds)
    }

    /**
     * The same lookup again under the MAL entry AniSkip files this episode's timings against.
     *
     * A show numbered straight through by its provider — episode 50 of Attack on Titan — is filed by
     * MAL as episode 1 of a separate per-cour entry, so the direct lookup 404s on exactly the
     * long-running series where providers are least likely to carry timings of their own. Reached
     * only after that 404, so a title whose numbering already agrees never spends the request.
     */
    private suspend fun redirectedSkipTimes(
        malId: Int,
        episode: Int,
        episodeLengthSeconds: Double,
    ): SkipTimes? {
        val rules = relationRules(malId)
        val (targetMalId, targetEpisode) = rules.redirect(malId, episode) ?: return null
        DiagnosticsLog.event(
            "AniSkip redirect malId=$malId episode=$episode -> malId=$targetMalId episode=$targetEpisode",
        )
        return aniSkip.skipTimes(targetMalId, targetEpisode, episodeLengthSeconds)
    }

    /** AniSkip's redirection table for one MAL entry. Cached even when empty: absence is stable. */
    private suspend fun relationRules(malId: Int): List<AniSkipRelationRule> = cache.getOrFetch(
        key = "aniskip-rules:v1:$malId",
        serializer = ListSerializer(AniSkipRelationRule.serializer()),
        ttlMs = OPTIONS_TTL,
    ) {
        withContext(Dispatchers.IO) { aniSkip.relationRules(malId) }
    }

    /** Votes on one AniSkip segment. Reported outcomes are the only way a bad timing gets fixed. */
    suspend fun voteSkipTime(skipId: String, upvote: Boolean) {
        withContext(Dispatchers.IO) { aniSkip.vote(skipId, upvote) }
    }

    /**
     * [maxAgeMs]: see [AppCache.getOrFetch]. A settled title's tree record is still returned as is,
     * since nothing on it can change.
     */
    suspend fun animeInfo(id: Int, force: Boolean = false, maxAgeMs: Long? = null): Media? = if (isOnePaceMediaId(id)) {
        // Not on AniList at all: the record is built from One Pace's own catalogue.
        anivexa.onePaceMedia()
    } else if (!force && id > 0) {
        settledTreeRecord(id) ?: liveAnimeInfo(id, force, maxAgeMs)
    } else {
        liveAnimeInfo(id, force, maxAgeMs)
    }

    /**
     * The tree's copy of a title's AniList record, when nothing on it can still change.
     *
     * The generator walks AniList's whole catalogue once a day, so a finished or cancelled title's
     * record on the CDN is the one AniList would send — and reading it costs AniList nothing, which
     * is the whole point: a television opening a franchise used to spend one rate-limited request
     * per season. Anything still airing, announced or on hiatus keeps asking AniList, because its
     * next episode, count and dates are exactly what a day-old copy gets wrong.
     */
    /** Whether nothing on [media]'s record can change any more — no episode still to air. */
    fun isSettled(media: Media): Boolean = media.status in SETTLED_STATUSES

    private suspend fun settledTreeRecord(id: Int): Media? {
        val record = konoha.details(id)?.anilist ?: return null
        return record.takeIf { it.id == id && it.status in SETTLED_STATUSES }
    }

    private suspend fun liveAnimeInfo(id: Int, force: Boolean, maxAgeMs: Long? = null): Media? = try {
        cache.getOrFetch(
            key = "anime:v4:$id",
            serializer = Media.serializer().nullable,
            ttlMs = INFO_TTL,
            forceRefresh = force,
            // The episode list is counted from `nextAiringEpisode`, and a day-long lifetime plus
            // the week the cache serves stale values for kept a just-aired episode off the page
            // for a day or more — a viewer found it only through Schedule. The record says when
            // it stops being true, so once that episode's air time has passed since the fetch,
            // fetch again. Comparing with the fetch time rather than the clock is what stops a
            // refetch loop when AniList is a minute late to move on to the next episode.
            outdated = { media, fetchedAtMs ->
                val airsAtMs = media?.nextAiringEpisode?.airingAt?.times(1000)
                airsAtMs != null && airsAtMs > fetchedAtMs && airsAtMs <= System.currentTimeMillis()
            },
            maxAgeMs = maxAgeMs,
        ) { aniList.animeInfo(id) }
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        // Konoha aggregates AniList onto a static CDN, so a title page can still be built while
        // AniList itself is refusing requests. Thinner than the live record — no relations or
        // trailer — but it is the difference between a title page and an error.
        val fromKonoha = konoha.details(id)?.toMedia(id)
            ?: localCatalog.media(id)
            ?: throw e
        DiagnosticsLog.event(
            "Anime info fallback id=$id source=${if (konohaDetailWasUsed(fromKonoha)) "konoha" else "bundled"}",
        )
        fromKonoha
    }

    /** A bundled-catalogue row has no description; a Konoha record does. Only used for logging. */
    private fun konohaDetailWasUsed(media: Media): Boolean = media.description != null

    /**
     * The series' own typography for [anilistId] — a transparent logo PNG — or null where the
     * metadata tree has none.
     *
     * Read from the tree's detail record rather than from [animeInfo], because AniList has no such
     * artwork at all: it offers a cover and a banner, both of which are pictures of the show rather
     * than its name. The record is already cached by the client for a day, so a title page that
     * shows the logo pays for it once and a page that does not show it never asks.
     *
     * Failure is null, not an exception. A page whose header falls back to setting the title in
     * the app's own type is a page; one that refused to open because a logo could not be fetched
     * is not, and every caller here has the title already.
     */
    suspend fun seriesLogo(anilistId: Int): String? {
        // Catalogue-native entries (hanime) use negative ids and have no record in the tree.
        if (anilistId < 0) return null
        return runCatching { konoha.details(anilistId)?.images?.logo?.takeIf { it.isNotBlank() } }
            .onFailure(Throwable::rethrowIfCancellation)
            .getOrNull()
    }

    /**
     * Konoha CDN episode metadata (episode titles, TMDB stills); empty when unknown or on failure.
     *
     * Skipped for adult titles. Konoha's data is TMDB-derived and TMDB does not index hentai, so a
     * hit on an adult AniList id is a false positive: it returns some unrelated show's episodes and
     * cover stills — a 1930s period drama's, in one measured case — which then decorate a hentai
     * episode list. Better no metadata than another title's. Non-adult titles are unaffected.
     */
    suspend fun konohaEpisodes(anilistId: Int): List<KonohaEpisode> {
        // Catalogue-native entries (hanime) use negative ids: no AniList record to check and no
        // Konoha entry, so skip the lookup — and the doomed animeInfo call a negative id would make.
        if (anilistId < 0) return emptyList()
        if (animeInfo(anilistId)?.isAdult == true) {
            DiagnosticsLog.event("Konoha skipped for adult title id=$anilistId (TMDB has no hentai)")
            return emptyList()
        }
        return runCatching { konoha.episodes(anilistId) }
            .onFailure { DiagnosticsLog.throwable("Konoha episodes failed id=$anilistId", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Episode titles and stills for a season: Konoha's answer, then TMDB, then AniList's own
     * streaming rows for anything still missing a picture.
     *
     * Konoha leads because it is one request against a CDN with no key and no rate limit, and for
     * anything that finished airing before its backfill stalled it is complete. TMDB is only asked
     * when that answer has a hole in it, so a well-covered title costs exactly what it costs today
     * and the request is spent on the titles that actually need it — the current season, where
     * Konoha is either thin or absent.
     *
     * AniList comes last and costs nothing: the rows are already in [info], which this function has
     * fetched anyway. It goes last because it is the only one of the three that does not state an
     * episode number — see [streamingEpisodeMetadata] — so it is the one most likely to hang a
     * still on the wrong row, and it should only ever reach an episode the other two left blank.
     * It is still worth having, because it covers the airing season Konoha does not and does so
     * without a TMDB id mapping that may not exist yet.
     *
     * The adult guard covers all three: TMDB does not index hentai, so a hit on an adult AniList id
     * is some unrelated show whose stills would then illustrate this one.
     */
    suspend fun episodeMetadata(anilistId: Int): List<EpisodeMetadata> {
        if (isOnePaceMediaId(anilistId)) {
            return runCatching { anivexa.onePaceMetadata() }
                .onFailure { DiagnosticsLog.throwable("One Pace episode metadata failed", it) }
                .getOrDefault(emptyList())
        }
        if (anilistId < 0) return emptyList()
        val info = runCatching { animeInfo(anilistId) }.getOrNull()
        if (info?.isAdult == true) {
            DiagnosticsLog.event("Episode metadata skipped for adult title id=$anilistId (TMDB has no hentai)")
            return emptyList()
        }
        val konohaRun = runCatching { konoha.episodes(anilistId) }
            .onFailure { DiagnosticsLog.throwable("Konoha episodes failed id=$anilistId", it) }
            .getOrDefault(emptyList())
            .mapNotNull { episode ->
                episode.number?.let {
                    EpisodeMetadata(
                        number = it,
                        title = episode.title,
                        still = episode.still,
                        airDate = episode.air_date,
                        overview = episode.overview,
                        runtime = episode.runtime?.takeIf { minutes -> minutes > 0 },
                        rating = episode.rating?.takeIf { score -> score > 0.0 },
                    )
                }
            }
        if (info == null) return konohaRun
        // Konoha answers for the whole broadcast run a season belongs to, which for a series AniList
        // splits is not the season that was asked for — see [alignMetadataToSeason].
        val fromKonoha = alignMetadataToSeason(konohaRun, info)
        if (fromKonoha.size != konohaRun.size) {
            DiagnosticsLog.event(
                "Episode metadata cut to season id=$anilistId konoha=${konohaRun.size} season=${fromKonoha.size}",
            )
        }
        if (fromKonoha.isNotEmpty() && fromKonoha.none { it.still.isNullOrBlank() }) return fromKonoha
        val fromTmdb = if (tmdb.isConfigured) {
            tmdb.episodeMetadata(info, konoha.tmdbRefFor(anilistId))
        } else {
            emptyList()
        }
        if (fromTmdb.isNotEmpty()) {
            DiagnosticsLog.event(
                "Episode metadata filled from TMDB id=$anilistId konoha=${fromKonoha.size} tmdb=${fromTmdb.size}",
            )
        }
        val merged = mergeEpisodeMetadataSources(fromKonoha, fromTmdb)
        if (merged.isNotEmpty() && merged.none { it.still.isNullOrBlank() }) return merged
        val aniListRows = streamingEpisodeMetadata(info.streamingEpisodes)
        // The licensor's listing is numbered for whatever run it streams, which for a series AniList
        // splits is not this season — see [streamingRowsFitSeason].
        val fromAniList = if (streamingRowsFitSeason(info, aniListRows, merged)) aniListRows else emptyList()
        if (fromAniList.isNotEmpty()) {
            DiagnosticsLog.event(
                "Episode metadata filled from AniList id=$anilistId merged=${merged.size} anilist=${fromAniList.size}",
            )
        } else if (aniListRows.isNotEmpty()) {
            DiagnosticsLog.event(
                "Episode metadata from AniList refused id=$anilistId rows=${aniListRows.size} " +
                    "numbered ${aniListRows.first().number}..${aniListRows.last().number} " +
                    "season=${info.episodes} (not this season's numbering)",
            )
        }
        return mergeEpisodeMetadataSources(merged, fromAniList)
    }

    /**
     * AniList's community recommendations for a title, best-rated first.
     *
     * Cached for as long as the info record, and empty on failure: the Recommended tab still has
     * the series entries to show, so a refused AniList request must not turn into an error there.
     */
    suspend fun animeRecommendations(id: Int): List<Media> {
        // Catalogue-native entries (hanime) use negative ids and have no AniList record.
        if (id < 0) return emptyList()
        val hideAdult = hideAdult
        // The generator stores AniList's recommendations with each title, refreshed daily; they
        // change slowly enough that the day-old list is the same list, and it costs AniList nothing.
        runCatching { konoha.details(id)?.recommendations }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { fromTree -> return if (hideAdult) fromTree.filterNot { it.isAdult } else fromTree }
        val recommended = runCatching {
            cache.getOrFetch(
                key = "recommendations:v1:$id:${if (hideAdult) "sfw" else "all"}",
                serializer = ListSerializer(Media.serializer()),
                ttlMs = INFO_TTL,
                // An empty answer is as likely to be a throttled request as a title nobody has
                // recommended anything for; don't spend a day's cache entry on it.
                cacheIf = { it.isNotEmpty() },
            ) { aniList.recommendations(id, hideAdult = hideAdult) }
        }
            .onFailure { error ->
                error.rethrowIfCancellation()
                DiagnosticsLog.throwable("Recommendations failed id=$id", error)
            }
            .getOrDefault(emptyList())
        return if (hideAdult) recommended.filterNot { it.isAdult } else recommended
    }

    /** Walks AniList's PREQUEL/SEQUEL chain so every season is reachable from one detail page. */
    suspend fun animeSeries(root: Media): List<Media> {
        // Season chains change rarely, so the walked chain's ids are cached under EVERY member
        // id: whichever season's page the user opens, the full series hydrates instantly from
        // the animeInfo cache (filled during the walk) instead of re-walking AniList relations.
        val idsSerializer = ListSerializer(Int.serializer())
        cache.getIfFresh("$SERIES_KEY_PREFIX${root.id}", idsSerializer)?.let { ids ->
            val members = ids.mapNotNull { id ->
                if (id == root.id) root else runCatching { animeInfo(id) }.getOrNull()
            }
            if (members.isNotEmpty()) {
                val withRoot = if (members.none { it.id == root.id }) members + root else members
                // Two cached ids can resolve to one entry (AniList merges duplicate media), and
                // the detail screens key their lazy rows by media id — a repeat is not a cosmetic
                // problem there, it throws "Key ... was already used" and takes the app down.
                return withRoot.distinctBy(Media::id).sortedWith(SERIES_AIRING_ORDER)
            }
        }

        val chain = treeSeriesChain(root) ?: walkSeriesChain(root).distinctBy(Media::id)
        val payload = kotlinx.serialization.json.Json.encodeToString(idsSerializer, chain.map(Media::id))
        cache.putBatch(chain.associate { "$SERIES_KEY_PREFIX${it.id}" to payload }, INFO_TTL)
        return chain
    }

    /**
     * The season chain the generator already walked, its members fetched side by side.
     *
     * The walk below goes one hop at a time — each season's relations have to arrive before the next
     * can be asked for — so an eight-season franchise was eight round trips in a row even when every
     * one of them was cached. The tree holds the finished chain, so every member is asked for at
     * once. Null when the tree has no chain for this title, which leaves the walk to answer.
     */
    private suspend fun treeSeriesChain(root: Media): List<Media>? = coroutineScope {
        val detail: com.anilili.data.remote.KonohaDetail? = runCatching { konoha.details(root.id) }.getOrNull()
        // Not `.orEmpty()`: this class has a private `Any?.orEmpty(): String` that would win.
        val ids: List<Int> = detail?.series ?: emptyList()
        if (root.id !in ids) return@coroutineScope null
        val hideAdult = hideAdult
        val members = ids.map { id ->
            async {
                if (id == root.id) root else seriesFetchGate.withPermit { runCatching { animeInfo(id) }.getOrNull() }
            }
        }.awaitAll().filterNotNull()
        members
            .filterNot { hideAdult && it.isAdult && it.id != root.id }
            .distinctBy(Media::id)
            .sortedWith(SERIES_AIRING_ORDER)
    }

    private suspend fun walkSeriesChain(root: Media): List<Media> = coroutineScope {
        val found = linkedMapOf(root.id to root)
        val expanded = mutableSetOf<Int>()
        var frontier = listOf(root)
        var depth = 0

        while (frontier.isNotEmpty() && found.size < MAX_SERIES_ENTRIES && depth < MAX_SERIES_DEPTH) {
            val batch = frontier
                .filter { expanded.add(it.id) }
                .take(MAX_SERIES_ENTRIES - found.size + 1)
            if (batch.isEmpty()) break

            val detailed = batch.map { media ->
                async {
                    if (media.id == root.id && media.relations.edges.isNotEmpty()) {
                        media
                    } else {
                        seriesFetchGate.withPermit { runCatching { animeInfo(media.id) }.getOrNull() } ?: media
                    }
                }
            }.awaitAll()

            val next = mutableListOf<Media>()
            detailed.forEach { media ->
                found[media.id] = media
                media.seasonNeighbors().forEach { neighbor ->
                    if (hideAdult && neighbor.isAdult) return@forEach
                    if (neighbor.id !in found && found.size < MAX_SERIES_ENTRIES) {
                        found[neighbor.id] = neighbor
                    }
                    if (neighbor.id !in expanded) next += neighbor
                }
            }
            frontier = next.distinctBy(Media::id)
            depth++
        }

        found.values.sortedWith(SERIES_AIRING_ORDER)
    }

    // ---- streaming (two backends, cached per source) ----
    /** Fast source — the Miruro pipe. */
    suspend fun miruroEpisodes(anilistId: Int, force: Boolean = false): EpisodesResult {
        // Miruro is AniList-keyed and has never heard of One Pace; its one server is native.
        if (isOnePaceMediaId(anilistId)) return EpisodesResult(emptyList())
        return cache.getOrFetch(
            // v5: the catalog API's episode ids replaced the pipe's, which it cannot resolve.
            key = "episodes:v5:miruro:$anilistId",
            serializer = EpisodesResult.serializer(),
            ttlMs = episodesTtlMs(),
            forceRefresh = force,
        ) {
            pipe.getEpisodes(anilistId).also {
                check(!it.isEmpty) { "Miruro returned no episode providers" }
            }
        }.withoutDisabledProviders().withFillerMarks(anilistId)
    }

    /**
     * Drops the servers the viewer switched off in Settings.
     *
     * Applied to the answer here, and outside the cache rather than inside it, because the whole
     * Miruro set arrives in one pipe call: there is no lookup to save by asking for fewer, and
     * storing the narrowed list would write a catalogue that is only true for the settings of the
     * moment, under a key that does not say so. The Anivexa side does the opposite for the opposite
     * reason — see [anivexaEpisodes], where each provider costs a lane and a round trip.
     */
    private fun EpisodesResult.withoutDisabledProviders(): EpisodesResult {
        val disabled = SettingsStore.disabledProviders.value
        if (disabled.isEmpty()) return this
        return EpisodesResult(providers.filterNot { it.name.lowercase() in disabled })
    }

    /** Extra sources — Anivexa-API (can be slower; loaded in the background by the detail screen). */
    suspend fun anivexaEpisodes(anilistId: Int, force: Boolean = false): EpisodesResult {
        // One Pace's episodes come from its own catalogue alone; see [AnivexaClient.onePaceEpisodes].
        if (isOnePaceMediaId(anilistId)) return anivexa.onePaceEpisodes()
        @Suppress("NAME_SHADOWING") val force = force || ProviderBenchmark.forceCatalogRefreshEnabled()
        // Fetched here (outside the episodes cache lock — the striped mutexes are not reentrant) so
        // the Anivexa catalog reuses the shared AniList Media instead of re-requesting it itself.
        val seed = runCatching { animeInfo(anilistId) }.getOrNull()
        // Narrowed with the same rule the client applies, so the cache key names the servers that
        // were actually asked and the coverage floor below is not measured against a provider that
        // was never going to answer. A server the viewer switched off drops out here rather than
        // from the result: every member of this list costs a semaphore lane and a round trip, and
        // not spending them is most of the point of turning one off.
        val asked = providerRegistry.providersForCountry(
            providerRegistry.enabledProviders(
                providerRegistry.anivexaProvidersFor(hideAdult),
                SettingsStore.disabledProviders.value,
            ),
            seed?.countryOfOrigin,
        )
        // Nothing left to ask. Returning empty keeps the fetch below from failing its own emptiness
        // check and storing a coverage-floor decision about a question nobody asked.
        if (asked.isEmpty()) return EpisodesResult(emptyList())
        val expected = asked.size
        return cache.getOrFetch(
            // Keyed by the provider list itself, not by a hand-kept version. A catalogue is only
            // an answer to the question "what do *these* servers have", and the key said only
            // "v4", so adding a provider left every install answering the new question out of
            // the old answer: the newcomer was invisible for the cache's lifetime and — measured
            // here — never asked at all. Turning off "hide adult content" changed the list the
            // same way and was ignored the same way. A list that changes now misses, refetches,
            // and the new server appears on the next load rather than two hours later.
            //
            // Switching a server off in Settings narrows the same list, so it needs no
            // invalidation of its own: the catalogue without it is a different entry from the
            // catalogue with it, and switching it back on lands on the entry it already had.
            key = "episodes:v5:anivexa:${providerRegistry.fingerprint(asked)}:$anilistId",
            serializer = EpisodesResult.serializer(),
            ttlMs = episodesTtlMs(),
            forceRefresh = force,
            // A catalog assembled while the network was struggling is missing the providers that
            // timed out, and caching it pinned that thin list for two hours — users reported "no
            // sources" on titles with plenty, and clearing app data was the only way out. Use the
            // result now, but only remember it if enough of the catalog actually answered.
            cacheIf = { it.providers.size >= (expected * MIN_CATALOG_COVERAGE).toInt() },
            ttlMsFor = { anivexaCatalogTtlMs(it, episodesTtlMs()) },
        ) {
            anivexa.getEpisodes(anilistId, seed, providers = asked, refresh = force).also {
                check(!it.isEmpty) { "Anivexa returned no episode providers" }
            }
        }.withFillerMarks(anilistId)
    }

    /**
     * Applies MAL filler flags to every provider's episode list. Providers rarely carry filler
     * data themselves; Jikan is authoritative and cached, and a timeout keeps a slow first
     * fetch from delaying episode loading.
     */
    private suspend fun EpisodesResult.withFillerMarks(anilistId: Int): EpisodesResult {
        if (isEmpty) return this
        val fillers = kotlinx.coroutines.withTimeoutOrNull(FILLER_FETCH_TIMEOUT_MS) {
            runCatching { fillerEpisodes(anilistId, animeInfo(anilistId)?.idMal) }.getOrDefault(emptySet())
        } ?: return this
        if (fillers.isEmpty()) return this
        fun mark(episodes: List<EpisodeItem>): List<EpisodeItem> = episodes.map { episode ->
            val isFiller = episode.number % 1.0 == 0.0 && episode.number.toInt() in fillers
            if (isFiller && !episode.filler) episode.copy(filler = true) else episode
        }
        return EpisodesResult(providers.map { it.copy(sub = mark(it.sub), dub = mark(it.dub)) })
    }

    /**
     * Quick partial Anivexa catalog: just the fast API-backed providers (plus [extraProviders]
     * when the user's preferred server is a slower one), so the watch screen can start playback
     * without waiting for every scraper. The full catalog still loads separately and replaces
     * these entries via [mergeProviders].
     */
    suspend fun fastAnivexaEpisodes(
        anilistId: Int,
        extraProviders: Set<String> = emptySet(),
        force: Boolean = false,
        /**
         * Everything that has answered so far, each time another provider answers. Lets the watch
         * screen offer the quickest server without waiting on the slowest member of this list —
         * anikoto's binary search takes 1.3-2.4s where anipm answers in ~0.2s. Not called when the
         * whole answer comes from cache, because then the return value is already immediate.
         */
        onPartial: ((EpisodesResult) -> Unit)? = null,
    ): EpisodesResult {
        if (isOnePaceMediaId(anilistId)) return anivexa.onePaceEpisodes()
        val providers = providerRegistry.enabledProviders(
            (
                providerRegistry.fastAnivexaProviders +
                    extraProviders.filter { it in providerRegistry.anivexaProvidersFor(false) }
                ).distinct(),
            SettingsStore.disabledProviders.value,
        )
        if (providers.isEmpty()) return EpisodesResult(emptyList())
        val seed = runCatching { animeInfo(anilistId) }.getOrNull()
        return cache.getOrFetch(
            key = "episodes:v4:anivexa-fast:${providers.sorted().joinToString(",")}:$anilistId",
            serializer = EpisodesResult.serializer(),
            ttlMs = episodesTtlMs(),
            forceRefresh = force,
        ) {
            val answered = ConcurrentHashMap<String, com.anilili.data.model.ProviderData>()
            anivexa.getEpisodes(
                anilistId = anilistId,
                seedMedia = seed,
                providers = providers,
                providerTimeoutMs = FAST_CATALOG_PROVIDER_TIMEOUT_MS,
                onProvider = onPartial?.let { deliver ->
                    { row ->
                        answered[row.name] = row
                        deliver(EpisodesResult(answered.values.sortedBy { ProviderCatalog.sortKey(it.name) }))
                    }
                },
            ).also {
                check(!it.isEmpty) { "Fast providers returned no episodes" }
            }
        }.withFillerMarks(anilistId)
    }

    /** Merged view of all sources — used where the full provider list is needed (watch screen). */
    suspend fun episodes(anilistId: Int): EpisodesResult = coroutineScope {
        val miruro = async {
            runCatching { miruroEpisodes(anilistId) }
                .onFailure { DiagnosticsLog.throwable("Miruro episodes failed id=$anilistId", it) }
                .getOrDefault(EpisodesResult(emptyList()))
        }
        val anivexa = async {
            runCatching { anivexaEpisodes(anilistId) }
                .onFailure { DiagnosticsLog.throwable("Anivexa episodes failed id=$anilistId", it) }
                .getOrDefault(EpisodesResult(emptyList()))
        }
        val remote = async {
            runCatching {
                val mediaInfo = runCatching { animeInfo(anilistId) }.getOrNull()
                val animeTitle = mediaInfo?.title?.preferred ?: mediaInfo?.title?.english ?: mediaInfo?.title?.romaji
                val runtimes = providerRegistry.getWorkflowRuntimes()
                val providerDataList = mutableListOf<com.anilili.data.model.ProviderData>()
                for (rt in runtimes) {
                    val epRes = rt.getEpisodes(anilistId, animeTitle)
                    epRes?.providers?.let { providerDataList.addAll(it) }
                }
                EpisodesResult(providerDataList)
            }.onFailure { DiagnosticsLog.throwable("Remote episodes failed id=$anilistId", it) }
                .getOrDefault(EpisodesResult(emptyList()))
        }
        val baseMerged = mergeProviders(miruro.await(), anivexa.await())
        mergeProviders(baseMerged, remote.await())
    }

    /** Providers present in both lists keep [b]'s entry (the fresher, fuller catalog). */
    fun mergeProviders(a: EpisodesResult, b: EpisodesResult): EpisodesResult {
        val replaced = b.providerNames.toSet()
        return EpisodesResult(
            (a.providers.filterNot { it.name in replaced } + b.providers)
                .sortedBy { ProviderCatalog.sortKey(it.name) },
        )
    }

    suspend fun sources(
        pipeId: String,
        provider: String,
        variant: SourceVariant,
        anilistId: Int,
        allowInteractiveChallenges: Boolean = true,
    ): SourcesResult {
        if (providerRegistry.isRemoteProvider(provider)) {
            val workflowRuntime = providerRegistry.getWorkflowRuntime(provider)
            if (workflowRuntime != null) {
                val res = workflowRuntime.getSourcesForPipeId(pipeId, anilistId, variant)
                if (res != null) return res
            }
        }

        return when (ProviderCatalog.sourceOf(provider)) {
            ProviderCatalog.Source.ANIVEXA, ProviderCatalog.Source.MIRURO -> when (ProviderCatalog.sourceOf(provider)) {
                ProviderCatalog.Source.ANIVEXA -> anivexa.getSources(pipeId, animeInfo(anilistId), allowInteractiveChallenges)
                ProviderCatalog.Source.MIRURO -> pipe.getSources(pipeId, provider, variant, anilistId)
            }
            else -> throw IllegalArgumentException("Unknown provider source type for $provider")
        }
    }

    data class ResolvedSources(
        val sources: SourcesResult,
        val provider: String,
        val variant: SourceVariant,
    )

    /** Includes catalog matches that were checked but returned no playable streams. */
    data class SourceResolution(
        val resolved: ResolvedSources?,
        val unavailableSources: Set<ProviderSourceIdentity>,
    ) {
        val unavailableProviders: Set<String>
            get() = unavailableSources.mapTo(linkedSetOf(), ProviderSourceIdentity::provider)
    }

    /**
     * Resolve a playable stream for [number] within the supplied [episodes] catalog, trying
     * [preferred] first and then other providers (fast Miruro ones before slower Anivexa
     * scrapers) until one actually returns a stream. This is the multi-source fallback: picking
     * a server that has no copy silently rolls over. The caller owns the catalog so playback can
     * start on the fast Miruro set before the Anivexa providers have finished loading.
     */
    suspend fun resolveSources(
        anilistId: Int,
        number: Double,
        preferred: String,
        category: Category,
        sourceVariant: SourceVariant = SourceVariant.defaultFor(category),
        episodes: EpisodesResult,
        excludedProviders: Set<String> = emptySet(),
        excludedSources: Set<ProviderSourceIdentity> = emptySet(),
        maxAttempts: Int = 5,
        allowInteractiveChallenges: Boolean = true,
        /**
         * Whether a cooldown may veto [preferred] itself.
         *
         * A cooldown is advice for the automatic walk — "do not spend the viewer's time here again
         * for a minute" — and that is the right answer when this resolve is choosing on their
         * behalf. It is the wrong answer when they have just tapped the server: the picker now
         * keeps a failed row visible (see `visibleSourceOptions`), and a visible row that silently
         * plays a different server is worse than the vanishing row it replaced. So a hand-picked
         * pair is always attempted; everything else in the walk still honours its cooldown, and a
         * pair that has failed *within this resolve* is excluded by the caller regardless.
         */
        honorCooldownForPreferred: Boolean = true,
    ): SourceResolution {
        val interactiveProvider = preferred.lowercase().takeIf {
            allowInteractiveChallenges && it in INTERACTIVE_CHALLENGE_PROVIDERS
        }
        fun attemptTimeoutFor(provider: String): Long = when {
            provider == interactiveProvider -> INTERACTIVE_SOURCE_TIMEOUT_MS
            ProviderCatalog.requiresResolverWebView(provider) -> WEBVIEW_SOURCE_ATTEMPT_TIMEOUT_MS
            else -> PROVIDER_SOURCE_ATTEMPT_TIMEOUT_MS
        }
        // Debug-only measurement mode. A benchmark cannot use the normal path: the route's
        // provider is advisory (stored priority still wins), and every probe that fails puts its
        // provider into a 1-10 minute cooldown, so the next run silently times whichever server
        // was not already blacklisted. Pinning restricts the attempt list to exactly one provider
        // and ignores cooldowns, so an outcome always names the server it came from.
        val pinned = ProviderBenchmark.pinnedProviderOrNull()
        // No server chosen by anyone: race the ones this device has measured quickest. A server
        // the viewer picked — in the route, in Settings, or by hand in the picker — is still asked
        // first and alone, exactly as before.
        val autoRace = pinned == null && preferred.equals(DEFAULT_PREFERRED_PROVIDER, ignoreCase = true)
        val userRanked = SettingsStore.serverPriority.value.drop(1)
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && it != DEFAULT_PREFERRED_PROVIDER }
        val ordered = providerAttemptOrder(
            preferred = pinned ?: preferred,
            providerNames = episodes.providerNames,
            providerRegistry = providerRegistry,
            // Everything the user ranked behind their first choice, in their order.
            fallbacks = SettingsStore.serverPriority.value.drop(1),
        ).let { names -> if (pinned != null) names.filter { it.equals(pinned, true) } else names }
            .let { names ->
                if (!autoRace) {
                    names
                } else {
                    // A hand-made ranking in Settings still leads, then the catalogue's default
                    // priority; only what both leave unranked is ordered by measurement.
                    val ranked = userRanked.filter { it in names }
                    val defaults = ProviderCatalog.defaultPriority.filter { it in names && it !in ranked }
                    ranked + defaults + ProviderSpeed.rank(names.filterNot { it in ranked || it in defaults })
                }
            }
            .let { names ->
                if (pinned != null) {
                    names
                } else {
                    languageGatedAttemptOrder(names, preferred, providerRegistry, userRanked, preferredLanguageFor(category))
                }
            }
        val candidates = ordered.flatMap { name ->
            val provider = episodes.provider(name) ?: return@flatMap emptyList()
            // A multi-audio server lists the episode only under sub even though the file carries
            // an English track too. Searching it under sub keeps it in the running for a dub
            // request instead of silently handing the viewer over to a different server.
            val lookup = providerRegistry.dubCapableCategory(name, category)
            val episode = provider.episodes(lookup).firstOrNull { it.number == number }
                ?: return@flatMap emptyList()
            val variants = providerRegistry.sourceVariants(name, category)
            val orderedVariants = if (name == preferred && sourceVariant in variants) {
                listOf(sourceVariant) + variants.filterNot { it == sourceVariant }
            } else {
                variants
            }
            // Asking the server the viewer actually chose for its other subtitle shape before
            // walking away from it. A hard-sub-only server refused with 444 is Miruro saying "not
            // this episode in that variant", not "not this server", and the servers that publish
            // both prove the soft sub can answer the same episode. Only for the requested name: an
            // automatic walk would pay one dead round trip per server for the same insurance.
            // Nothing is added when the catalogue already lists both, so bonk and twin are
            // untouched, and a success on a published variant returns before this is ever reached.
            val withLastResort = if (name.equals(preferred, ignoreCase = true)) {
                orderedVariants +
                    listOfNotNull(providerRegistry.unpublishedSubtitleVariant(name, category))
            } else {
                orderedVariants
            }
            withLastResort.map { variant ->
                ProviderSourceCandidate(name, episode.pipeId, variant)
            }
        }
        val coolingSources = if (pinned != null) {
            emptySet()
        } else {
            coolingSourcesToHonor(
                cooling = sourceFailureCooldowns.coolingSources(
                    anilistId,
                    number,
                    candidates.map { ProviderSourceIdentity(it.provider, it.variant) },
                ),
                preferred = preferred,
                preferredVariant = sourceVariant,
                honorCooldownForPreferred = honorCooldownForPreferred,
            )
        }
        if (coolingSources.isNotEmpty()) {
            DiagnosticsLog.event(
                "Source resolve cooldown id=$anilistId episode=$number " +
                    "sources=${coolingSources.joinToString(",") { "${it.provider}:${it.variant.route}" }}",
            )
        }
        // One structured record per resolve, so uploaded bundles can be aggregated into "which
        // server actually serves this audience, how fast, and when it doesn't — why". The
        // per-attempt events below stay for reading a single session by eye; this is the row a
        // query can group by.
        val attemptLog = mutableListOf<String>()
        val resolveStartedAt = SystemClock.elapsedRealtime()
        // Captured before the first attempt: a Miruro-backed server resolving slowly from a cold
        // bridge is a resolver-lifecycle cost, the same server resolving slowly from a warm one
        // is the provider itself. Without this the two are the same number.
        val bridgeWarmAtStart = PipeBridge.isWarm()
        val onSourceOutcome: (ProviderSourceCandidate, SourceAttemptOutcome, Long) -> Unit =
            { candidate, outcome, durationMs ->
                attemptLog += "${candidate.provider}:${candidate.variant.route}:" +
                    "${outcome.name.lowercase()}:$durationMs"
                // What the ranking learns from. A pinned debug run is a measurement of one server
                // under artificial conditions, so it is kept out.
                if (pinned == null) {
                    ProviderSpeed.record(candidate.provider, outcome == SourceAttemptOutcome.OK, durationMs)
                }
            }
        val onAttempt: (String) -> Unit = { provider ->
            val timeoutMs = attemptTimeoutFor(provider)
            DiagnosticsLog.event(
                "Source resolve attempt provider=$provider id=$anilistId episode=$number " +
                    "timeoutMs=$timeoutMs interactiveChallenge=${provider == interactiveProvider}",
            )
        }
        val onSourceFailure: (ProviderSourceCandidate, Exception) -> Unit = { candidate, error ->
            val cooldownMs = sourceFailureCooldowns.record(
                candidate.provider,
                anilistId,
                number,
                SourceFailureKind.FAILURE,
                error,
                candidate.variant,
            )
            // Filed under its own code as well as the stack below, because "throttled" and
            // "broken" are the same line in a bundle otherwise — both arrive as an untyped
            // throwable whose only clue is whatever sentence the provider happened to write.
            // The stack stays: it is the only thing that says *where* in the provider it gave
            // up, and SRC-09 deliberately carries no such detail.
            if (rateLimitedStatus(error.platformErrorCode(), error.message.orEmpty())) {
                AnililiError.SOURCE_RATE_LIMITED.report(
                    cause = error,
                    attributes = mapOf(
                        "provider" to candidate.provider,
                        "variant" to candidate.variant.route,
                        "anilistId" to anilistId,
                        "episode" to number,
                        "cooldownMs" to cooldownMs,
                    ),
                )
            }
            DiagnosticsLog.throwable(
                "Source resolve failed provider=${candidate.provider} " +
                    "variant=${candidate.variant.route} id=$anilistId episode=$number cooldownMs=$cooldownMs",
                error,
            )
        }
        val onSourceTimeout: (ProviderSourceCandidate) -> Unit = { candidate ->
            val timeoutMs = attemptTimeoutFor(candidate.provider)
            val cooldownMs = sourceFailureCooldowns.record(
                candidate.provider,
                anilistId,
                number,
                SourceFailureKind.TIMEOUT,
                variant = candidate.variant,
            )
            DiagnosticsLog.event(
                "Source resolve timeout provider=${candidate.provider} " +
                    "variant=${candidate.variant.route} id=$anilistId episode=$number " +
                    "afterMs=$timeoutMs cooldownMs=$cooldownMs",
            )
        }
        val onSourceEmpty: (ProviderSourceCandidate) -> Unit = { candidate ->
            val cooldownMs = sourceFailureCooldowns.record(
                candidate.provider,
                anilistId,
                number,
                SourceFailureKind.EMPTY,
                variant = candidate.variant,
            )
            DiagnosticsLog.event(
                "Source resolve empty provider=${candidate.provider} " +
                    "variant=${candidate.variant.route} id=$anilistId episode=$number cooldownMs=$cooldownMs",
            )
        }
        val load: suspend (ProviderSourceCandidate) -> SourcesResult = { candidate: ProviderSourceCandidate ->
            takePreheatedSources(anilistId, number, candidate.provider, candidate.variant)
                ?: sources(
                    candidate.pipeId,
                    candidate.provider,
                    candidate.variant,
                    anilistId,
                    allowInteractiveChallenges = candidate.provider == interactiveProvider,
                )
        }
        // A pinned run measures one named server, so nothing may quietly skip it. Clearing the
        // cooldowns alone was not enough: the watch screen also passes what it has already given up
        // on this episode, and a pinned provider sitting in that set produced a resolve with one
        // candidate and zero attempts — a silent no-measurement that looked identical to a server
        // the catalogue does not carry.
        val excludedForResolve = if (pinned != null) emptySet() else excludedProviders
        val excludedSourcesForResolve = if (pinned != null) emptySet() else excludedSources + coolingSources
        val resolution = if (autoRace) {
            resolveProviderCandidatesHedged(
                candidates = candidates,
                excludedProviders = excludedForResolve,
                excludedSources = excludedSourcesForResolve,
                maxAttempts = maxAttempts,
                hedgeDelayMs = AUTO_RACE_HEDGE_MS,
                attemptTimeoutMsFor = ::attemptTimeoutFor,
                isExclusive = { it in providerRegistry.webViewResolverProviders },
                onAttempt = onAttempt,
                onSourceFailure = onSourceFailure,
                onSourceTimeout = onSourceTimeout,
                onSourceEmpty = onSourceEmpty,
                onSourceOutcome = onSourceOutcome,
                load = load,
            )
        } else {
            resolveProviderCandidates(
                candidates = candidates,
                excludedProviders = excludedForResolve,
                excludedSources = excludedSourcesForResolve,
                maxAttempts = maxAttempts,
                attemptTimeoutMs = PROVIDER_SOURCE_ATTEMPT_TIMEOUT_MS,
                attemptTimeoutMsFor = ::attemptTimeoutFor,
                onSourceOutcome = onSourceOutcome,
                onAttempt = onAttempt,
                onSourceFailure = onSourceFailure,
                onSourceTimeout = onSourceTimeout,
                onSourceEmpty = onSourceEmpty,
                load = load,
            )
        }
        resolution.resolved?.let { resolved ->
            sourceFailureCooldowns.clearResolved(
                resolved.provider,
                anilistId,
                number,
                resolved.variant,
            )
        }
        DiagnosticsLog.event(
            category = "source",
            name = "resolve.summary",
            attributes = mapOf(
                "anilistId" to anilistId,
                "episode" to number,
                "category" to category.api,
                "variant" to sourceVariant.route,
                "preferred" to preferred,
                "chosen" to (resolution.resolved?.provider ?: "none"),
                "streams" to (resolution.resolved?.sources?.streams?.size ?: 0),
                "totalMs" to (SystemClock.elapsedRealtime() - resolveStartedAt),
                "attempts" to attemptLog.size,
                // "kiwi:ok:412,bonk:timeout:8000" — provider, outcome, milliseconds.
                "outcomes" to attemptLog.joinToString(","),
                "cooling" to coolingSources.joinToString(",") { "${it.provider}:${it.variant.route}" },
                "candidates" to candidates.size,
                "bridge" to if (bridgeWarmAtStart) "warm" else "cold",
            ),
        )
        return SourceResolution(
            resolved = resolution.resolved?.let { ResolvedSources(it.sources, it.provider, it.variant) },
            unavailableSources = resolution.unavailableSources + coolingSources,
        )
    }

    // ---- source preheating ----
    //
    // Resolving a stream is the slow half of starting an episode (a couple of seconds; on a cold
    // pipe far worse), and it is entirely predictable: the next episode is almost always what
    // gets played next. Preheating resolves it while the current one plays, so pressing Next
    // finds the answer already in hand.
    //
    // Note this caches a stream *address*, not video data — the cost is one provider request, not
    // a download, which is why it is safe on mobile data. Entries are deliberately short-lived:
    // provider URLs are frequently signed or IP-bound, so a stale one is worse than none. A miss
    // just resolves live exactly as before, and an entry is consumed on read so a URL that turns
    // out to be dead is never handed out twice.

    private data class PreheatKey(
        val anilistId: Int,
        val number: Double,
        val provider: String,
        val variant: SourceVariant,
    )

    private data class PreheatEntry(val sources: SourcesResult, val storedAtMs: Long)

    private val preheatedSources = ConcurrentHashMap<PreheatKey, PreheatEntry>()

    private fun takePreheatedSources(
        anilistId: Int,
        number: Double,
        provider: String,
        variant: SourceVariant,
    ): SourcesResult? {
        val key = PreheatKey(anilistId, number, provider, variant)
        val entry = preheatedSources.remove(key) ?: return null
        val ageMs = System.currentTimeMillis() - entry.storedAtMs
        if (ageMs > PREHEAT_TTL_MS) {
            DiagnosticsLog.event("Source preheat expired provider=$provider episode=$number ageMs=$ageMs")
            return null
        }
        DiagnosticsLog.event(
            "Source preheat hit provider=$provider id=$anilistId episode=$number ageMs=$ageMs",
        )
        return entry.sources
    }

    /**
     * Resolves [number] on [preferred] only and keeps the result for a later [resolveSources].
     *
     * Deliberately single-provider: a preheat that walked the whole fallback chain would spend
     * the failure budget (and the cooldowns) of an episode the viewer may never reach. Failures
     * are swallowed — this is speculative work, and the real resolve will report properly.
     */
    suspend fun preheatSources(
        anilistId: Int,
        number: Double,
        preferred: String,
        category: Category,
        sourceVariant: SourceVariant = SourceVariant.defaultFor(category),
        episodes: EpisodesResult,
    ) {
        // Providers that resolve through the one shared hidden browser are never preheated. The
        // engine is single and the episode on screen has first claim on it; worse, hanime's page
        // builds real video elements as it mounts, so a speculative resolve during playback is a
        // second decoder client for an episode nobody has asked for yet. Same reasoning as
        // `validatesDuringPlayback`, which declines these for the background sweep — and the same
        // narrow set, deliberately, rather than `requiresResolverWebView`: that also answers true
        // for every Miruro provider, which is most of the catalogue and the bulk of what preheating
        // is for.
        if (preferred.lowercase() in providerRegistry.webViewResolverProviders) return
        val provider = episodes.provider(preferred) ?: return
        val lookup = providerRegistry.dubCapableCategory(preferred, category)
        val episode = provider.episodes(lookup).firstOrNull { it.number == number } ?: return
        val variant = providerRegistry.sourceVariants(preferred, category).let { variants ->
            sourceVariant.takeIf { it in variants } ?: variants.firstOrNull()
        } ?: return
        val key = PreheatKey(anilistId, number, preferred, variant)
        if (preheatedSources.containsKey(key)) return
        // Never let speculative work outlive its usefulness or crowd the heap.
        if (preheatedSources.size >= MAX_PREHEATED_EPISODES) {
            preheatedSources.entries
                .minByOrNull { it.value.storedAtMs }
                ?.let { preheatedSources.remove(it.key) }
        }
        val started = System.currentTimeMillis()
        val resolved = try {
            withTimeoutOrNull(PREHEAT_TIMEOUT_MS) {
                sources(episode.pipeId, preferred, variant, anilistId, allowInteractiveChallenges = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Leaving the episode cancels this, and the provider reports that as an ordinary
            // interrupted read. Logging it as an empty server would blame the viewer's navigation
            // on whichever provider happened to be preheating at the time.
            currentCoroutineContext().ensureActive()
            null
        }
        if (resolved == null || resolved.streams.isEmpty()) {
            DiagnosticsLog.event("Source preheat empty provider=$preferred id=$anilistId episode=$number")
            return
        }
        preheatedSources[key] = PreheatEntry(resolved, System.currentTimeMillis())
        DiagnosticsLog.event(
            "Source preheat stored provider=$preferred id=$anilistId episode=$number " +
                "streams=${resolved.streams.size} tookMs=${System.currentTimeMillis() - started}",
        )
    }

    /**
     * Subtitle tracks for a provider that publishes none of its own, fetched behind playback.
     *
     * Only XFDM needs this — its picture has Chinese subtitles burned in and carries no track — and
     * the lender is AniChan, keyed on the same AniList id so it cannot return another show's. The
     * resolve deliberately does not wait for it; see `AnivexaClient.borrowedSubtitles`.
     */
    suspend fun borrowedSubtitles(anilistId: Int, episode: Int): List<SubtitleItem> =
        runCatching {
            val media = animeInfo(anilistId) ?: return emptyList()
            anivexa.borrowedSubtitles(media, episode)
        }.getOrDefault(emptyList())

    /**
     * A provider may resolve successfully and still return media that fails in the platform player.
     * Keep that provider out of this episode's fallback order across WatchViewModel recreation.
     */
    fun recordPlaybackFailure(
        anilistId: Int,
        number: Double,
        provider: String,
        variant: SourceVariant = SourceVariant.GENERIC_SUB,
        streamHost: String?,
        message: String,
        /** The player's own code for this failure — `h429`, `m2004` — when it had one. */
        platformCode: String? = null,
    ): Long {
        // A provider that precomputes its URLs has to learn that one has gone stale, and this is
        // the only place the app finds out: the resolve succeeded, so nothing upstream saw a
        // failure. Retiring it here is what makes the precomputed set self-healing.
        anivexa.forgetShippedStream(provider, anilistId, number.toInt())
        val cooldownMs = sourceFailureCooldowns.record(
            provider = provider,
            anilistId = anilistId,
            episode = number,
            kind = SourceFailureKind.PLAYBACK,
            variant = variant,
            playbackMessage = message,
            platformCode = platformCode,
        )
        DiagnosticsLog.event(
            "Source playback cooldown provider=$provider variant=${variant.route} " +
                "id=$anilistId episode=$number platformCode=${platformCode ?: "none"} " +
                "host=${streamHost ?: "unknown"} cooldownMs=$cooldownMs message=${message.take(160)}",
        )
        return cooldownMs
    }

    private suspend fun mediaPage(
        key: String,
        ttlMs: Long,
        force: Boolean = false,
        fetch: suspend () -> MediaPage,
    ): MediaPage {
        // The adult preference is part of the cache key: filtered and unfiltered pages differ.
        val hideAdult = hideAdult
        val page = cache.getOrFetch(
            key = "media:v2:$key:${if (hideAdult) "sfw" else "all"}",
            serializer = MediaPage.serializer(),
            ttlMs = ttlMs,
            forceRefresh = force,
            // Never keep an empty page. Whether a query has matches is not a property of the query
            // alone: AniList answers the Hentai genre with nothing while the signed-in account
            // hides adult content, and this key knows only about *this app's* switch. Storing that
            // emptiness meant turning the account's switch on changed nothing here for four hours,
            // without a request being made that could have noticed. The cost of not storing it is
            // one request per visit to a filter combination that genuinely matches nothing.
            cacheIf = { it.items.isNotEmpty() },
            fetch = fetch,
        )
        // Safety net over the server-side isAdult filter (covers surfaces AniList can't filter).
        return if (hideAdult) page.copy(items = page.items.filterNot { it.isAdult }) else page
    }

    private fun Any?.orEmpty(): String = this?.toString() ?: ""

    private companion object {
        val LIST_STATUSES =
            setOf("CURRENT", "REPEATING", "PLANNING", "PAUSED", "COMPLETED", "DROPPED")
        /** Statuses whose AniList record can no longer change, so the tree's copy is as good. */
        val SETTLED_STATUSES = setOf("FINISHED", "CANCELLED")
        const val MAX_SERIES_ENTRIES = 16
        const val MAX_SERIES_DEPTH = 8
        const val SERIES_FETCH_CONCURRENCY = 2
        const val SCHEDULE_TTL = 15L * 60 * 1000
        /** Days of padding either side of a published month. Mirrors the generator's own constant. */
        const val SCHEDULE_PAD_DAYS = 7L
        const val HOME_TTL = 30L * 60 * 1000
        /** MAL id → AniList media join; ids never remap, so a day is conservative. */
        const val MAL_MAP_TTL = 24L * 60 * 60 * 1000
        const val AIRING_TTL = 30L * 60 * 1000
        const val COLLECTION_TTL = 4L * 60 * 60 * 1000
        const val EPISODES_TTL = 2L * 60 * 60 * 1000

        /**
         * How long a fetched catalogue stays good. Constant in a release build; a measurement run
         * may shorten it so the same title can be opened twice and actually refetch — see
         * [ProviderBenchmark.catalogTtlMs].
         */
        internal fun episodesTtlMs(): Long = ProviderBenchmark.catalogTtlOrNull() ?: EPISODES_TTL

        /**
         * Fraction of the Anivexa catalog that must answer before the result is worth caching.
         * Half is deliberately lenient: providers genuinely lack titles all the time, and the
         * target is the degraded-network case where nearly everything times out at once.
         */
        const val MIN_CATALOG_COVERAGE = 0.5
        const val FILLER_FETCH_TIMEOUT_MS = 3_500L
        const val FAST_CATALOG_PROVIDER_TIMEOUT_MS = 6_000L
        const val PROVIDER_SOURCE_ATTEMPT_TIMEOUT_MS = 8_000L

        /**
         * How long an unpinned resolve gives its first choice before asking the next server beside
         * it. Measured resolves on the fast servers sit between ~100ms (bee) and ~900ms (anipm,
         * anikoto) warm, so this lets a healthy first choice win alone nearly every time and only
         * pays for a second request when the first is genuinely slow.
         */
        const val AUTO_RACE_HEDGE_MS = 700L

        /**
         * What a provider gets when it has to build a browser before it can ask anything.
         *
         * Eight seconds is a budget for an HTTP request, and a cold WebView spends most of that
         * getting a page on screen: measured, a browser-backed source was cut off at 8s having loaded
         * its page and started work. The cost of the larger number is a slower failover when one
         * of these genuinely cannot answer, which is the right trade for the two providers that
         * cannot be reached any other way.
         */
        const val WEBVIEW_SOURCE_ATTEMPT_TIMEOUT_MS = 25_000L
        const val INTERACTIVE_SOURCE_TIMEOUT_MS = 120_000L
        val INTERACTIVE_CHALLENGE_PROVIDERS = setOf("allanime")
        const val INFO_TTL = 24L * 60 * 60 * 1000
        const val OPTIONS_TTL = 7L * 24 * 60 * 60 * 1000
        const val SERIES_KEY_PREFIX = "series:v1:"

        /** Provider stream URLs are short-lived; a stale preheat is worse than a live resolve. */
        const val PREHEAT_TTL_MS = 5L * 60 * 1000
        /** Speculative work gets a tighter budget than a resolve the viewer is waiting on. */
        const val PREHEAT_TIMEOUT_MS = 12_000L
        const val MAX_PREHEATED_EPISODES = 4
    }
}

/** Chronological airing order shared by the series walk and the instant relation-based seed. */
internal val SERIES_AIRING_ORDER: Comparator<Media> = compareBy(
    { it.startDate?.year ?: it.seasonYear ?: Int.MAX_VALUE },
    { it.startDate?.month ?: Int.MAX_VALUE },
    { it.startDate?.day ?: Int.MAX_VALUE },
    { it.id },
)

/**
 * Cache key for one batch of ids. The ids go in the key literally, never their `hashCode()`:
 * `List<Int>.hashCode()` collides on inputs this code really produces — `[1, 63]` and `[2, 32]`
 * are both 1055 — and a collision serves one batch's cached media in place of another's, silently
 * and for the whole TTL.
 */
internal fun batchCacheKey(prefix: String, ids: List<Int>): String = "$prefix${ids.joinToString(",")}"

internal fun Media.seasonNeighbors(): List<Media> = relations.edges
    .asSequence()
    .filter { it.relationType == "PREQUEL" || it.relationType == "SEQUEL" }
    .mapNotNull { it.node }
    .distinctBy(Media::id)
    .toList()

/**
 * The episode ceiling shared by imported progress and Continue Watching reconciliation.
 *
 * AniList remains authoritative when its batch response contains a real count. Its cached response
 * can instead predate that count (or encode the absence as zero), while MAL supplies the same
 * title's total with every list row. Falling back to it gives every downstream consumer a ceiling
 * without replacing a count AniList already knows.
 */
internal fun remoteEpisodeCeiling(anilistEpisodes: Int?, malEpisodes: Int?): Int? =
    anilistEpisodes?.takeIf { it > 0 } ?: malEpisodes?.takeIf { it > 0 }

/**
 * A watched-episode count from another service, held to what this title can actually contain.
 *
 * [totalEpisodes] is null whenever the count is unknown — an ongoing show AniList has not given a
 * length yet — and then the reported figure is all there is to go on and passes through. A known
 * length is a hard ceiling: no season has an episode after its finale, so a larger number is
 * evidence the two services disagree about the season, not that the viewer is further along.
 */
internal fun clampRemoteProgress(progress: Int, totalEpisodes: Int?): Int = when {
    totalEpisodes == null || totalEpisodes <= 0 -> progress
    else -> progress.coerceAtMost(totalEpisodes)
}

/**
 * Whether a title search should be filled out from the bundled snapshot.
 *
 * True only where AniList has left a hole it cannot fill itself. It matches whole words and nothing
 * less — `search: "narut"` returns an empty page — so the hole is always a half-typed word, always
 * on the first page, and always looks like a live answer far short of the 30 a page holds.
 *
 * A browse with no query is not padded: with nothing typed AniList was asked for a *catalogue*, and
 * a short answer there means the filters are narrow, not that anything is missing.
 */
internal fun shouldPadFromSnapshot(query: String, page: Int, remoteCount: Int): Boolean =
    query.isNotBlank() && page == 1 && remoteCount < LOCAL_PAD_BELOW

/**
 * Below this many live results, a title search is padded from the bundled snapshot. A page holds
 * 30, so this is "AniList barely answered" rather than "AniList answered briefly": a real query
 * with a dozen hits is left exactly as it came back.
 */
private const val LOCAL_PAD_BELOW = 10

/** The autocomplete list's length; matches [LocalCatalog]'s own. */
private const val TITLE_SUGGESTION_LIMIT = 8

/** One picture the background picker offers: [url] to use, [thumbnail] to show, and where it is from. */
data class AnimeBackdropChoice(val url: String, val thumbnail: String, val source: String)

/** One title's episode count on the account, and the most the service will take (null: unknown). */
data class AnimeListProgress(val progress: Int, val maxEpisodes: Int?)

/** TMDB serves any width from the same path; the picker grid needs only `w300`. */
internal fun tmdbThumbnail(url: String): String = url.replace("/t/p/w1280/", "/t/p/w300/")

/**
 * The cache key for one Browse page: every filter [com.anilili.data.remote.discoverVariables] sends
 * to AniList, plus the 18+ switch it sends as `isAdult`.
 *
 * A field left out of this key is a filter whose change is answered from the page cached for the
 * filters before it — for up to the collection lifetime. That shipped: excluded genres and tags, the
 * decade range, country, source and the episode buckets were all missing, so picking "Not Action"
 * or "2010–2019" alone brought back the unfiltered results. `DiscoverFilterCacheKeyTest` changes each
 * field in turn and fails if the key does not. [DiscoverFilters.hideWatched] is keyed by the caller,
 * with the library it was answered from.
 */
internal fun discoverCacheKey(filters: DiscoverFilters, hideAdult: Boolean): String = with(filters) {
    listOf(
        query.trim().lowercase(),
        genres.sorted().joinToString(","),
        tags.sorted().joinToString(","),
        excludedGenres.sorted().joinToString(","),
        excludedTags.sorted().joinToString(","),
        studioId?.toString().orEmpty(),
        year?.toString().orEmpty(),
        season.orEmpty(),
        yearFrom?.toString().orEmpty(),
        yearTo?.toString().orEmpty(),
        status.orEmpty(),
        format.orEmpty(),
        country.orEmpty(),
        source.orEmpty(),
        episodesMin?.toString().orEmpty(),
        episodesMax?.toString().orEmpty(),
        minimumScore?.toString().orEmpty(),
        if (dubbedOnly) "dub" else "",
        if (hideAdult) "sfw" else "",
        sort,
    ).joinToString("|")
}
