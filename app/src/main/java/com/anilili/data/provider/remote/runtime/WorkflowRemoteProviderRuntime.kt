package com.anilili.data.provider.remote.runtime

import com.anilili.data.model.Category
import com.anilili.data.model.EpisodeItem
import com.anilili.data.model.EpisodesResult
import com.anilili.data.model.ProviderData
import com.anilili.data.model.SkipTimes
import com.anilili.data.model.SourceVariant
import com.anilili.data.model.SourcesResult
import com.anilili.data.model.StreamItem
import com.anilili.data.model.SubtitleItem
import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.workflow.WorkflowDefinition
import com.anilili.data.provider.remote.engine.workflow.WorkflowExecutor
import com.anilili.data.provider.remote.engine.workflow.WorkflowExecutionResult
import com.anilili.data.provider.remote.engine.workflow.WorkflowOutput
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WorkflowRemoteProviderRuntime(
    private val baseRuntime: RemoteProviderRuntime,
    private val workflows: Map<String, WorkflowDefinition>,
    private val executor: WorkflowExecutor,
    private val json: Json,
) {
    val entry: RemoteProviderEntry get() = baseRuntime.entry
    val resolvedCapabilities: ResolvedCapabilities get() = baseRuntime.resolvedCapabilities

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
        val result = executor.execute(
            workflow,
            mapOf("anilistId" to anilistId, "title" to title ?: ""),
            "${entry.id}_episodes_$anilistId"
        )

        return if (result.success) {
            parseEpisodesResult(result.output, anilistId)
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getEpisodes failed: provider=${entry.id} anilistId=$anilistId error=${result.error}")
            null
        }
    }

    suspend fun getSources(anilistId: Int, episodeNumber: Double, category: Category): SourcesResult? {
        val workflow = workflows["sources"] ?: return null
        val result = executor.execute(
            workflow,
            mapOf(
                "anilistId" to anilistId,
                "episodeNumber" to episodeNumber,
                "category" to category.name,
            ),
            "${entry.id}_sources_${anilistId}_$episodeNumber"
        )

        return if (result.success) {
            parseSourcesResult(result.output, category)
        } else {
            DiagnosticsLog.event("WorkflowRemoteProviderRuntime getSources failed: provider=${entry.id} anilistId=$anilistId episode=$episodeNumber error=${result.error}")
            null
        }
    }

    suspend fun getServers(anilistId: Int, episodeNumber: Double): List<ServerInfo> {
        val workflow = workflows["servers"] ?: return emptyList()
        val result = executor.execute(
            workflow,
            mapOf("anilistId" to anilistId, "episodeNumber" to episodeNumber),
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
        // Implementation depends on workflow output structure
        return emptyList()
    }

    private fun parseEpisodesResult(output: Any?, anilistId: Int): EpisodesResult? {
        // Map workflow output to EpisodesResult
        return null
    }

    private fun parseSourcesResult(output: Any?, category: Category): SourcesResult? {
        // Map workflow output to SourcesResult
        return null
    }

    private fun parseServers(output: Any?): List<ServerInfo> {
        return emptyList()
    }

    data class SearchResult(
        val anilistId: Int,
        val title: String,
        val image: String?,
        val year: Int?,
        val format: String?,
    )

    data class ServerInfo(
        val name: String,
        val url: String,
        val quality: String?,
        val language: String?,
        val variant: SourceVariant,
    )

    companion object {
        fun create(
            entry: RemoteProviderEntry,
            workflows: Map<String, WorkflowDefinition>,
            okHttpClient: OkHttpClient,
            json: Json,
            requestPolicy: RemoteRequestPolicy,
        ): WorkflowRemoteProviderRuntime {
            val baseRuntime = entry.toRuntime()
            val executor = WorkflowExecutor(okHttpClient, requestPolicy)
            return WorkflowRemoteProviderRuntime(baseRuntime, workflows, executor, json)
        }
    }
}