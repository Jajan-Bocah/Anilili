package com.anilili.data.provider.remote.runtime

import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.engine.workflow.WorkflowDefinition
import com.anilili.data.provider.remote.engine.workflow.WorkflowExecutor
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class WorkflowRemoteProviderRuntime(
    private val baseRuntime: RemoteProviderRuntime,
    private val workflows: Map<String, WorkflowDefinition>,
    private val executor: WorkflowExecutor,
    private val json: Json,
    private val slugStore: RemoteSlugStore = RemoteSlugStore(),
) : RemoteProviderRuntime {
    override val entry: RemoteProviderEntry get() = baseRuntime.entry
    override val resolvedCapabilities: ResolvedCapabilities get() = baseRuntime.resolvedCapabilities

    suspend fun search(query: String): List<SearchResult> {
        val workflow = workflows["search"] ?: return emptyList()
        val result = executor.execute(workflow, mapOf("query" to query), "${entry.id}_search")

        return if (result.success) {
            parseSearchResults(result.output)
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime search failed: provider=${entry.id} error=${result.error}")
            emptyList()
        }
    }

    suspend fun getEpisodes(anilistId: Int, title: String? = null): EpisodesResult? {
        val workflow = workflows["episodes"] ?: return null

        var animeSlug = slugStore.getAnimeSlug(entry.id, anilistId)

        // If anime slug is not cached and title is provided, perform transparent matching
        if (animeSlug.isNullOrBlank() && !title.isNullOrBlank()) {
            val searchResults = search(title)
            if (searchResults.isNotEmpty()) {
                val bestCandidate = searchResults
                    .map { it to AnimeTitleMatcher.calculateMatchScore(title, it.title) }
                    .maxByOrNull { it.second }

                if (bestCandidate != null && bestCandidate.second >= 0.75) {
                    animeSlug = bestCandidate.first.slugOrUrl
                    slugStore.putAnimeSlug(entry.id, anilistId, animeSlug)
                    DiagnosticsLog.event(
                        "AnimeTitleMatcher matched: provider=${entry.id} anilistId=$anilistId title='$title' -> candidate='${bestCandidate.first.title}' score=${bestCandidate.second}"
                    )
                } else {
                    DiagnosticsLog.event(
                        "AnimeTitleMatcher rejected all candidates: provider=${entry.id} anilistId=$anilistId title='$title' bestScore=${bestCandidate?.second ?: 0.0}"
                    )
                }
            }
        }

        val variables = mutableMapOf<String, Any>(
            "anilistId" to anilistId,
            "title" to (title ?: ""),
        )
        if (!animeSlug.isNullOrBlank()) {
            variables["animeSlug"] = animeSlug
            variables["animeUrl"] = animeSlug
            variables["slug"] = animeSlug
            resolveUrl(animeSlug)?.let { variables["url"] = it }
        }

        val result = executor.execute(
            workflow,
            variables,
            "${entry.id}_episodes_$anilistId"
        )
        println("DIAG getEpisodes: workflow=${entry.id}_episodes_$anilistId success=${result.success} error=${result.error} output=${result.output}")

        return if (result.success) {
            val episodesResult = parseEpisodesResult(result.output, anilistId)
            // Cache the raw episode href (not the pipeId). First wins: a later episode that parses to
            // the same number must not overwrite the genuine one.
            episodesResult?.providers?.forEach { providerData ->
                val seen = mutableSetOf<Double>()
                (providerData.sub + providerData.dub).forEach { ep ->
                    val href = parseRemotePipeId(ep.pipeId)
                    if (href != null && seen.add(ep.number)) slugStore.putEpisodeSlug(entry.id, anilistId, ep.number, href)
                }
            }
            episodesResult
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getEpisodes failed: provider=${entry.id} anilistId=$anilistId error=${result.error}")
            null
        }
    }

    suspend fun getSources(anilistId: Int, episodeNumber: Double, category: Category): SourcesResult? {
        val workflow = workflows["sources"] ?: return null

        val episode = episodeVariables(slugStore.getEpisodeSlug(entry.id, anilistId, episodeNumber)) ?: run {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getSources: no episode url provider=${entry.id} anilistId=$anilistId episode=$episodeNumber")
            return null
        }

        val variables = mutableMapOf<String, Any>(
            "anilistId" to anilistId,
            "episodeNumber" to episodeNumber,
            "category" to category.name,
        )
        variables.putAll(episode)
        putAnimeVariables(variables, anilistId)

        val result = executor.execute(
            workflow,
            variables,
            "${entry.id}_sources_${anilistId}_$episodeNumber"
        )

        return if (result.success) {
            parseSourcesResult(result.output, category)
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getSources failed: provider=${entry.id} anilistId=$anilistId episode=$episodeNumber error=${result.error}")
            null
        }
    }

    suspend fun getSourcesForPipeId(pipeId: String, anilistId: Int, variant: SourceVariant): SourcesResult? {
        // pipeId format: remote/{providerId}/{anilistId}/{category}/{episodeHrefOrNumber}; the href may contain '/'
        val workflow = workflows["sources"] ?: return null

        val raw = parseRemotePipeId(pipeId)
        val epNumber = raw?.toDoubleOrNull()
        // A bare number is the legacy form: look the href up in the cache. Anything else is the href itself.
        val href = if (epNumber != null) slugStore.getEpisodeSlug(entry.id, anilistId, epNumber) else raw
        val episode = episodeVariables(href) ?: run {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getSourcesForPipeId: no episode url provider=${entry.id} pipeId=$pipeId")
            return null
        }

        val variables = mutableMapOf<String, Any>(
            "pipeId" to pipeId,
            "anilistId" to anilistId,
            "episodeNumber" to (epNumber ?: 1.0),
            "variant" to variant.api,
            "category" to variant.category.name,
        )
        variables.putAll(episode)
        putAnimeVariables(variables, anilistId)

        val result = executor.execute(
            workflow,
            variables,
            "${entry.id}_sources_${anilistId}_${epNumber ?: 1.0}"
        )

        return if (result.success) {
            parseSourcesResult(result.output, variant.category)
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getSourcesForPipeId failed: provider=${entry.id} pipeId=$pipeId error=${result.error}")
            null
        }
    }

    private fun putAnimeVariables(variables: MutableMap<String, Any>, anilistId: Int) {
        val animeSlug = slugStore.getAnimeSlug(entry.id, anilistId)
        if (!animeSlug.isNullOrBlank()) {
            variables["animeSlug"] = animeSlug
            variables["animeUrl"] = animeSlug
        }
    }

    /** `url`/`episodeUrl` = absolute episode URL, `episodeSlug` = its path without leading/trailing '/'. */
    private fun episodeVariables(rawHref: String?): Map<String, String>? {
        val url = rawHref?.let(::resolveUrl) ?: return null
        return mapOf("url" to url, "episodeUrl" to url, "episodeSlug" to url.toHttpUrl().encodedPath.trim('/'))
    }

    /** Resolves a relative or absolute href against the endpoint; refuses other hosts (HTML is untrusted). */
    private fun resolveUrl(raw: String): String? {
        val base = entry.endpoint.toHttpUrlOrNull() ?: return null
        val url = base.resolve(raw.trim())
        if (url == null || url.host.removePrefix("www.") != base.host.removePrefix("www.")) {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime refused href: provider=${entry.id} href=$raw")
            return null
        }
        return url.toString()
    }

    suspend fun getServers(anilistId: Int, episodeNumber: Double): List<ServerInfo> {
        val workflow = workflows["servers"] ?: return emptyList()
        val episodeUrl = slugStore.getEpisodeSlug(entry.id, anilistId, episodeNumber)

        val variables = mutableMapOf<String, Any>(
            "anilistId" to anilistId,
            "episodeNumber" to episodeNumber,
        )
        if (!episodeUrl.isNullOrBlank()) {
            variables["episodeUrl"] = episodeUrl
        }

        val result = executor.execute(
            workflow,
            variables,
            "${entry.id}_servers_${anilistId}_$episodeNumber"
        )

        return if (result.success) {
            parseServers(result.output)
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getServers failed: provider=${entry.id} error=${result.error}")
            emptyList()
        }
    }

    private fun parseSearchResults(output: Any?): List<SearchResult> {
        if (output == null) return emptyList()

        val list = when (output) {
            is List<*> -> output
            is Map<*, *> -> {
                // Standard list wrappers
                val standardList = output["results"] as? List<*>
                    ?: output["animes"] as? List<*>
                    ?: output["data"] as? List<*>
                if (standardList != null) standardList
                // Separate field lists (titles, urls, images) from workflow output mapping
                else if (output.containsKey("titles") || output.containsKey("urls")) {
                    val titles = output["titles"] as? List<*>
                    val urls = output["urls"] as? List<*>
                    val images = output["images"] as? List<*>
                    val size = listOfNotNull(titles?.size, urls?.size, images?.size).maxOrNull() ?: 0
                    (0 until size).map { i ->
                        mapOf(
                            "title" to (titles?.getOrNull(i) as? String ?: ""),
                            "slug" to (urls?.getOrNull(i) as? String ?: ""),
                            "image" to (images?.getOrNull(i) as? String ?: ""),
                        )
                    }
                } else {
                    output.values.firstOrNull() as? List<*>
                }
            }
            is JsonObject -> {
                val array = output["results"] ?: output["animes"] ?: output["data"]
                (array as? JsonArray)?.toList()
            }
            is JsonArray -> output.toList()
            else -> null
        } ?: return emptyList()

        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val title = (item["title"] ?: item["name"])?.toString()?.trim() ?: return@mapNotNull null
                    val slug = (item["slug"] ?: item["url"] ?: item["link"] ?: item["id"])?.toString() ?: ""
                    val image = (item["image"] ?: item["poster"] ?: item["thumbnail"] ?: item["image_portrait_url"])?.toString()
                    val year = (item["year"] ?: item["release_year"])?.toString()?.toIntOrNull()
                    val format = item["format"]?.toString()
                    SearchResult(
                        anilistId = 0,
                        title = title,
                        image = image,
                        year = year,
                        format = format,
                        slugOrUrl = slug,
                    )
                }
                is JsonObject -> {
                    val title = (item["title"] ?: item["name"])?.let { if (it is JsonPrimitive) it.content else it.toString() }?.trim() ?: return@mapNotNull null
                    val slug = (item["slug"] ?: item["url"] ?: item["link"] ?: item["id"])?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: ""
                    val image = (item["image"] ?: item["poster"] ?: item["image_portrait_url"])?.let { if (it is JsonPrimitive) it.content else it.toString() }
                    val year = (item["year"] as? JsonPrimitive)?.content?.toIntOrNull()
                    val format = (item["format"] as? JsonPrimitive)?.content
                    SearchResult(
                        anilistId = 0,
                        title = title,
                        image = image,
                        year = year,
                        format = format,
                        slugOrUrl = slug,
                    )
                }
                else -> null
            }
        }
    }

    private fun parseEpisodesResult(output: Any?, anilistId: Int): EpisodesResult? {
        if (output == null) return null

        // Handle parallel-list format produced by workflow output mapping
        // (episodeUrls / episodeNumbers / episodeTitles as separate lists)
        val parallelEpisodes: List<Map<String, Any?>>? = when (output) {
            is Map<*, *> -> {
                val urls = output["episodeUrls"] as? List<*>
                    ?: output["episode_urls"] as? List<*>
                    ?: output["urls"] as? List<*>
                if (urls != null) {
                    val numbers = output["episodeNumbers"] as? List<*>
                        ?: output["episode_numbers"] as? List<*>
                        ?: output["numbers"] as? List<*>
                    val titles = output["episodeTitles"] as? List<*>
                        ?: output["episode_titles"] as? List<*>
                        ?: output["titles"] as? List<*>
                    val size = listOfNotNull(urls.size, numbers?.size, titles?.size).maxOrNull() ?: 0
                    (0 until size).map { i ->
                        mapOf(
                            "url" to (urls.getOrNull(i) as? String ?: ""),
                            "number" to (numbers?.getOrNull(i) as? String ?: (i + 1).toString()),
                            "title" to (titles?.getOrNull(i) as? String),
                        )
                    }
                } else null
            }
            else -> null
        }

        val list: List<*>? = when (output) {
            is List<*> -> output
            is Map<*, *> -> (output["episodes"] ?: output["data"]) as? List<*>
            is JsonObject -> {
                val array = output["episodes"] ?: output["data"]
                (array as? JsonArray)?.toList()
            }
            is JsonArray -> output.toList()
            else -> null
        }

        val episodeItems = (parallelEpisodes ?: list)?.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val numRaw = item["number"] ?: item["episode"] ?: item["ep"] ?: item["num"]
                    val epNumber = numRaw?.toString()?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: return@mapNotNull null
                    val title = item["title"]?.toString()?.trim()
                    val url = (item["url"] ?: item["link"] ?: item["slug"] ?: item["id"])?.toString() ?: epNumber.toString()
                    val image = item["image"]?.toString()
                    val airDate = item["airDate"]?.toString()

                    val pipeId = "remote/${entry.id}/$anilistId/sub/$url"
                    EpisodeItem(
                        pipeId = pipeId,
                        number = epNumber,
                        title = title,
                        image = image,
                        filler = false,
                        airDate = airDate,
                    )
                }
                is JsonObject -> {
                    val numRaw = (item["number"] ?: item["episode"] ?: item["ep"])?.let { if (it is JsonPrimitive) it.content else it.toString() }
                    val epNumber = numRaw?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull() ?: return@mapNotNull null
                    val title = (item["title"] as? JsonPrimitive)?.content
                    val url = (item["url"] ?: item["slug"])?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: epNumber.toString()
                    val image = (item["image"] as? JsonPrimitive)?.content
                    val airDate = (item["airDate"] as? JsonPrimitive)?.content

                    val pipeId = "remote/${entry.id}/$anilistId/sub/$url"
                    EpisodeItem(
                        pipeId = pipeId,
                        number = epNumber,
                        title = title,
                        image = image,
                        filler = false,
                        airDate = airDate,
                    )
                }
                else -> null
            }
        }?.sortedBy { it.number } ?: return null

        if (episodeItems.isEmpty()) return null

        val providerData = ProviderData(
            name = entry.id,
            sub = episodeItems,
            dub = emptyList(),
        )

        return EpisodesResult(
            providers = listOf(providerData),
            cutOff = emptyList(),
        )
    }

    private fun parseSourcesResult(output: Any?, category: Category): SourcesResult? {
        if (output == null) return null

        val streamList = mutableListOf<StreamItem>()
        val subtitleList = mutableListOf<SubtitleItem>()

        // Detect mirror dropdown pattern: workflow output has both "streams" (mirror URLs) and "servers" (mirror labels)
        val isMirrorDropdown = output is Map<*, *> && output.containsKey("streams") && output.containsKey("servers")
        // requiresWebView=true means the provider's sources workflow yields iframe/embed pages, not
        // direct media files. Plain string URLs from such a workflow must be typed "embed" regardless
        // of whether the URL path happens to contain the word "embed" — that word-sniff is unreliable
        // for URLs like https://player.example/watch/123.
        val providerRequiresWebView = resolvedCapabilities.requiresWebView

        val streamsRaw = when (output) {
            is Map<*, *> -> output["streams"] ?: output["sources"] ?: output["video"]
            is JsonObject -> output["streams"] ?: output["sources"]
            else -> output
        }

        val rawList = when (streamsRaw) {
            is List<*> -> streamsRaw
            is JsonArray -> streamsRaw.toList()
            is Map<*, *> -> listOf(streamsRaw)
            is JsonObject -> listOf(streamsRaw)
            else -> null
        }

        rawList?.forEach { item ->
            when (item) {
                is Map<*, *> -> {
                    val url = (item["url"] ?: item["file"] ?: item["src"])?.toString() ?: return@forEach
                    val typeRaw = item["type"]?.toString()?.lowercase() ?: ""
                    val isHls = url.contains(".m3u8") || typeRaw == "hls"
                    // Explicit type wins; then provenance (provider requires WebView or mirror
                    // dropdown); URL substring is last-resort only when nothing else says embed.
                    val isIframe = typeRaw == "embed" || item["isEmbed"] == true ||
                        (!isHls && (providerRequiresWebView || isMirrorDropdown || url.contains("embed")))
                    val type = if (isHls) "hls" else if (isIframe) "embed" else "mp4"

                    val quality = item["quality"]?.toString() ?: item["label"]?.toString()
                    val referer = item["referer"]?.toString() ?: entry.endpoint
                    val headers = (item["headers"] as? Map<*, *>)?.entries?.associate {
                        it.key.toString() to it.value.toString()
                    } ?: emptyMap()

                    streamList.add(
                        StreamItem(
                            url = url,
                            type = type,
                            quality = quality,
                            audio = category.api,
                            referer = referer,
                            isActive = true,
                            width = null,
                            height = quality?.replace(Regex("[^0-9]"), "")?.toIntOrNull(),
                            headers = headers,
                        )
                    )
                }
                is JsonObject -> {
                    val url = (item["url"] ?: item["file"] ?: item["src"])?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: return@forEach
                    val typeRaw = (item["type"] as? JsonPrimitive)?.content?.lowercase() ?: ""
                    val isHls = url.contains(".m3u8") || typeRaw == "hls"
                    // Also reject DASH (.mpd) and known media file extensions from being typed embed,
                    // even when requiresWebView=true. These are native streams, not iframe pages.
                    val isDash = url.contains(".mpd") || typeRaw == "dash" || typeRaw == "mpd"
                    val isMediaFile = listOf("mp4", "m4v", "webm", "mkv", "mov").any { url.lowercase().endsWith("." + it) }
                    val isIframe = typeRaw == "embed" ||
                        (!isHls && !isDash && !isMediaFile && (providerRequiresWebView || isMirrorDropdown || url.contains("embed")))
                    val type = if (isHls) "hls" else if (isDash) "dash" else if (isIframe) "embed" else "mp4"

                    val quality = (item["quality"] as? JsonPrimitive)?.content
                    val referer = (item["referer"] as? JsonPrimitive)?.content ?: entry.endpoint

                    streamList.add(
                        StreamItem(
                            url = url,
                            type = type,
                            quality = quality,
                            audio = category.api,
                            referer = referer,
                            isActive = true,
                            width = null,
                            height = quality?.replace(Regex("[^0-9]"), "")?.toIntOrNull(),
                        )
                    )
                }
                is String -> {
                    // Plain URL string from select attribute (e.g., mirror option value or iframe src)
                    val url = item
                    if (url.isBlank()) return@forEach
                    val isHls = url.contains(".m3u8")
                    val isDash = url.contains(".mpd")
                    val isMediaFile = listOf("mp4", "m4v", "webm", "mkv", "mov").any { url.lowercase().endsWith("." + it) }
                    // providerRequiresWebView is the reliable signal; isMirrorDropdown and URL
                    // substring are retained as fallbacks for configs that predate the flag.
                    val isIframe = !isHls && !isDash && !isMediaFile && (providerRequiresWebView || isMirrorDropdown || url.contains("embed"))
                    val type = if (isHls) "hls" else if (isDash) "dash" else if (isIframe) "embed" else "mp4"

                    streamList.add(
                        StreamItem(
                            url = url,
                            type = type,
                            quality = null,
                            audio = category.api,
                            referer = entry.endpoint,
                            isActive = true,
                            width = null,
                            height = null,
                        )
                    )
                }
            }
        }

        if (streamList.isEmpty()) return null

        return SourcesResult(
            streams = streamList,
            subtitles = subtitleList,
            skip = null,
            download = null,
        )
    }

    private fun parseServers(output: Any?): List<ServerInfo> {
        if (output == null) return emptyList()

        val list = when (output) {
            is List<*> -> output
            is Map<*, *> -> (output["servers"] ?: output["mirrors"] ?: output.values.firstOrNull()) as? List<*>
            is JsonArray -> output.toList()
            else -> null
        } ?: return emptyList()

        return list.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val name = (item["name"] ?: item["title"] ?: item["server"])?.toString() ?: return@mapNotNull null
                    val url = (item["url"] ?: item["link"] ?: item["embed"])?.toString() ?: ""
                    val quality = item["quality"]?.toString()
                    ServerInfo(
                        name = name,
                        url = url,
                        quality = quality,
                        language = "sub",
                        variant = SourceVariant.GENERIC_SUB,
                    )
                }
                else -> null
            }
        }
    }

    data class SearchResult(
        val anilistId: Int,
        val title: String,
        val image: String?,
        val year: Int?,
        val format: String?,
        val slugOrUrl: String = "",
    )

    data class ServerInfo(
        val name: String,
        val url: String,
        val quality: String?,
        val language: String?,
        val variant: SourceVariant,
    )
}

/**
 * Raw episode href inside `remote/{provider}/{anilistId}/{category}/{href}`. The href may itself
 * contain `/` (leading, trailing, absolute URL), so only the first four slashes are delimiters.
 */
internal fun parseRemotePipeId(pipeId: String): String? =
    (if (pipeId.startsWith("remote/")) pipeId.split("/", limit = 5).getOrNull(4) else pipeId)
        ?.takeIf { it.isNotBlank() && it != "/" }
