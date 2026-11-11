package com.anilili.data

import android.app.ActivityManager
import android.content.Context
import com.anilili.BuildConfig
import com.anilili.data.cache.AppCache
import com.anilili.data.provider.remote.config.RemoteProviderManager
import com.anilili.data.provider.registry.UnifiedProviderRegistry
import com.anilili.data.remote.AniListClient
import com.anilili.data.remote.AniZipClient
import com.anilili.data.remote.AniSkipClient
import com.anilili.data.remote.AnivexaClient
import com.anilili.data.remote.AppDns
import com.anilili.data.remote.JikanClient
import com.anilili.data.remote.KonohaClient
import com.anilili.data.remote.MalClient
import com.anilili.data.remote.MiruroEmbedResolver
import com.anilili.data.remote.PipeClient
import com.anilili.data.remote.OpenSubtitlesClient
import com.anilili.data.remote.TmdbClient
import com.anilili.diagnostics.DiagnosticsHttpEventListener
import com.anilili.data.device.isTelevision
import com.anilili.data.remote.LegacyTrustAnchors.withLegacyTrustAnchors
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Cache
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tiny manual DI container. Built once from [MiruroApp.onCreate] and read by ViewModels.
 * Avoids pulling in Hilt for a project this size.
 */
object AppGraph {
    lateinit var repository: MiruroRepository
        private set

    /**
     * Whether [init] has run. Unit tests build the app's value types directly and never construct
     * the graph, so anything reachable from one of those — a `DetailData` getter, say — has to ask
     * before it reads, or it throws `UninitializedPropertyAccessException` in a test that is not
     * otherwise about the graph at all.
     */
    val isReady: Boolean get() = ::repository.isInitialized
    lateinit var httpClient: OkHttpClient
        private set
    lateinit var openSubtitles: OpenSubtitlesClient
        private set

    /** Direct AniList access for the few callers outside the repository (profile avatars). */
    lateinit var aniList: AniListClient
        private set

    /** The metadata cache and the metadata tree's client, for Settings → Re-download metadata. */
    lateinit var metadataCache: AppCache
        private set
    lateinit var konoha: KonohaClient
        private set

    /** Remote provider config manager for custom sources. */
    lateinit var remoteProviderManager: RemoteProviderManager
        private set

    /** Unified registry merging built-in and remote providers. */
    lateinit var providerRegistry: UnifiedProviderRegistry
        private set

    /**
     * TV boxes are memory/CPU-starved; non-UI layers use this to throttle background work. It also
     * picks which APK the updater downloads, which is the one place a wrong answer is durable —
     * a box misread as a phone updates itself to the phone build and stays there.
     *
     * Seeded in [init] from the device's own signals, before settings have been read off disk, and
     * refreshed by `SettingsStore` once they have. That ordering is why this is a var: `init` runs
     * on the startup path and cannot wait for a disk read, but the viewer's layout override and the
     * remembered leanback-launcher observation both live in that read.
     */
    var isTv: Boolean = false
        private set

    /** See [isTv]: called once the persisted television signals are available. */
    fun refreshIsTv(value: Boolean) {
        isTv = value
    }

    /** Hardware pressure hints used to keep background work and playback conservative. */
    var isLowRamDevice: Boolean = false
        private set
    var memoryClassMb: Int = 0
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return

        isTv = isTelevision(context)
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        isLowRamDevice = activityManager?.isLowRamDevice == true
        memoryClassMb = activityManager?.memoryClass ?: 0

        // Kilobytes of integers, and every card asks it during the first grid pass, so it is read
        // here rather than deferred behind a coroutine that would land after first paint.
        DubIndex.load(context)

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        // Before the client that reads it: the resolver stays the system one until settings load,
        // but the cache directory has to be known by the time the first lookup happens.
        AppDns.attach(context)

        httpClient = OkHttpClient.Builder()
            .addInterceptor(com.anilili.data.remote.MegaPlayCdnHealthInterceptor())
            .withLegacyTrustAnchors()
            .dns(AppDns)
            .addInterceptor(AppDns.InterceptionRetry)
            .eventListenerFactory(DiagnosticsHttpEventListener.Factory)
            .cache(Cache(File(context.applicationContext.cacheDir, "http"), 50L * 1024 * 1024))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        openSubtitles = OpenSubtitlesClient(
            client = httpClient,
            json = json,
            apiKey = BuildConfig.OPEN_SUBTITLES_API_KEY,
            userAgent = "Anilili v${BuildConfig.VERSION_NAME}",
        )

        val aniList = AniListClient(httpClient, json).also { this.aniList = it }
        val cache = AppCache(context, json).also { metadataCache = it }
        // Hoisted out of the constructor call below because AnivexaClient needs it too: XFDM is
        // searched by Chinese name, and Konoha's per-title record is where those names live.
        val konoha = KonohaClient(context, httpClient, json, cache).also { this.konoha = it }
        DubIndex.refresh(context, konoha)
        com.anilili.data.recs.Recommendations.attach(context, httpClient, konoha)

        remoteProviderManager = RemoteProviderManager(context, httpClient, json).also { it.init() }

        providerRegistry = UnifiedProviderRegistry(
            remoteConfigStore = remoteProviderManager.configStore,
            okHttpClient = httpClient,
            json = json,
        )

        repository = MiruroRepository(
            tmdb = TmdbClient(httpClient, json, cache, BuildConfig.TMDB_READ_TOKEN),
            aniList = aniList,
            pipe = PipeClient(json, MiruroEmbedResolver(httpClient, json)),
            anivexa = AnivexaClient(context, httpClient, json, aniList, cache, konoha),
            jikan = JikanClient(httpClient, json),
            aniSkip = AniSkipClient(httpClient, json),
            mal = MalClient(httpClient, json),
            konoha = konoha,
            cache = cache,
            localCatalog = LocalCatalog(context, json),
            aniZip = AniZipClient(httpClient, json, cache),
            providerRegistry = providerRegistry,
        )
    }
}
