package com.anilili.data.provider.remote.runtime

import com.anilili.data.provider.remote.config.RemoteProviderConfig
import com.anilili.data.provider.remote.config.RemoteProviderEntry
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.data.provider.remote.engine.workflow.WorkflowDefinition
import com.anilili.data.provider.remote.engine.workflow.WorkflowExecutor
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class RemoteProviderRuntimeFactory(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
) {

    fun createRuntime(entry: RemoteProviderEntry, config: RemoteProviderConfig): RemoteProviderRuntime? {
        val requestPolicy = RemoteRequestPolicy.fromSecurityConfig(config.metadata.security ?: SecurityConfig())
        val workflows = parseWorkflows(entry, config)

        if (workflows.isEmpty()) {
            DiagnosticsLog.event("RemoteProviderRuntimeFactory: no workflows defined for provider ${entry.id}")
            return null
        }

        return RemoteProviderRuntime.create(entry, workflows, okHttpClient, json, requestPolicy)
    }

    private fun parseWorkflows(entry: RemoteProviderEntry, config: RemoteProviderConfig): Map<String, WorkflowDefinition> {
        val workflows = mutableMapOf<String, WorkflowDefinition>()

        config.workflows?.forEach { (name, workflowConfig) ->
            try {
                val steps = workflowConfig.steps?.map { stepConfig ->
                    parseStep(stepConfig)
                } ?: emptyList()

                val output = parseOutput(workflowConfig.output)

                workflows[name] = WorkflowDefinition(
                    id = "${entry.id}_$name",
                    name = workflowConfig.name ?: name,
                    version = workflowConfig.version ?: "1.0",
                    description = workflowConfig.description ?: "",
                    steps = steps,
                    output = output,
                )
            } catch (e: Exception) {
                DiagnosticsLog.throwable("RemoteProviderRuntimeFactory: failed to parse workflow $name for ${entry.id}", e)
            }
        }

        return workflows
    }

    private fun parseStep(stepConfig: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.WorkflowStep {
        return when (stepConfig.op) {
            "http" -> parseHttpStep(stepConfig)
            "parse_html" -> parseParseHtmlStep(stepConfig)
            "select" -> parseSelectStep(stepConfig)
            "parse_json" -> parseParseJsonStep(stepConfig)
            "regex" -> parseRegexStep(stepConfig)
            "map" -> parseMapStep(stepConfig)
            "normalize" -> parseNormalizeStep(stepConfig)
            "assert" -> parseAssertStep(stepConfig)
            "filter" -> parseFilterStep(stepConfig)
            else -> throw IllegalArgumentException("Unknown step op: ${stepConfig.op}")
        }
    }

    private fun parseHttpStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.HttpStep {
        return com.anilili.data.provider.remote.engine.workflow.HttpStep(
            id = config.id ?: "http_${System.currentTimeMillis()}",
            method = com.anilili.data.provider.remote.engine.workflow.HttpMethod.valueOf(config.params["method"]?.uppercase() ?: "GET"),
            url = config.params["url"] ?: "",
            headers = parseStringMap(config.params["headers"]),
            query = parseStringMap(config.params["query"]),
            body = config.params["body"],
            saveAs = config.saveAs,
            timeoutMs = config.params["timeoutMs"]?.toIntOrNull() ?: 15000,
            followRedirects = config.params["followRedirects"]?.toBoolean() ?: true,
            maxRedirects = config.params["maxRedirects"]?.toIntOrNull() ?: 5,
        )
    }

    private fun parseParseHtmlStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.ParseHtmlStep {
        return com.anilili.data.provider.remote.engine.workflow.ParseHtmlStep(
            id = config.id ?: "parse_html_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            saveAs = config.saveAs,
        )
    }

    private fun parseSelectStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.SelectStep {
        return com.anilili.data.provider.remote.engine.workflow.SelectStep(
            id = config.id ?: "select_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            selector = config.params["selector"] ?: "",
            attribute = config.params["attribute"],
            mode = com.anilili.data.provider.remote.engine.workflow.SelectMode.valueOf(config.params["mode"]?.uppercase() ?: "FIRST"),
            saveAs = config.saveAs,
        )
    }

    private fun parseParseJsonStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.ParseJsonStep {
        return com.anilili.data.provider.remote.engine.workflow.ParseJsonStep(
            id = config.id ?: "parse_json_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            jsonPath = config.params["jsonPath"],
            saveAs = config.saveAs,
        )
    }

    private fun parseRegexStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.RegexStep {
        return com.anilili.data.provider.remote.engine.workflow.RegexStep(
            id = config.id ?: "regex_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            pattern = config.params["pattern"] ?: "",
            group = config.params["group"]?.toIntOrNull() ?: 0,
            mode = com.anilili.data.provider.remote.engine.workflow.RegexMode.valueOf(config.params["mode"]?.uppercase() ?: "FIRST"),
            saveAs = config.saveAs,
        )
    }

    private fun parseMapStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.MapStep {
        return com.anilili.data.provider.remote.engine.workflow.MapStep(
            id = config.id ?: "map_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            mappings = parseStringMap(config.params["mappings"]),
            saveAs = config.saveAs,
        )
    }

    private fun parseNormalizeStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.NormalizeStep {
        val operations = parseNormalizeOperations(config.params["operations"])
        return com.anilili.data.provider.remote.engine.workflow.NormalizeStep(
            id = config.id ?: "normalize_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            operations = operations,
            saveAs = config.saveAs,
        )
    }

    private fun parseAssertStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.AssertStep {
        val condition = parseAssertCondition(config.params["condition"])
        return com.anilili.data.provider.remote.engine.workflow.AssertStep(
            id = config.id ?: "assert_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            condition = condition,
            message = config.params["message"] ?: "Assertion failed",
        )
    }

    private fun parseFilterStep(config: WorkflowStepConfig): com.anilili.data.provider.remote.engine.workflow.FilterStep {
        val predicate = parseFilterPredicate(config.params["predicate"])
        return com.anilili.data.provider.remote.engine.workflow.FilterStep(
            id = config.id ?: "filter_${System.currentTimeMillis()}",
            input = config.params["input"] ?: "",
            predicate = predicate,
            saveAs = config.saveAs,
        )
    }

    private fun parseOutput(outputConfig: WorkflowOutputConfig?): com.anilili.data.provider.remote.engine.workflow.WorkflowOutput {
        return com.anilili.data.provider.remote.engine.workflow.WorkflowOutput(
            type = com.anilili.data.provider.remote.engine.workflow.OutputType.valueOf(outputConfig?.type?.uppercase() ?: "RAW"),
            mapping = parseStringMap(outputConfig?.mapping),
        )
    }

    private fun parseStringMap(jsonStr: String?): Map<String, String> {
        return jsonStr?.let { json.decodeFromString(it) } ?: emptyMap()
    }

    private fun parseNormalizeOperations(jsonStr: String?): List<com.anilili.data.provider.remote.engine.workflow.NormalizeOperation> {
        return jsonStr?.let { json.decodeFromString(it) } ?: emptyList()
    }

    private fun parseAssertCondition(jsonStr: String?): com.anilili.data.provider.remote.engine.workflow.AssertCondition {
        return jsonStr?.let { json.decodeFromString(it) } ?: com.anilili.data.provider.remote.engine.workflow.NotNullCondition()
    }

    private fun parseFilterPredicate(jsonStr: String?): com.anilili.data.provider.remote.engine.workflow.FilterPredicate {
        return jsonStr?.let { json.decodeFromString(it) } ?: com.anilili.data.provider.remote.engine.workflow.NotNullPredicate()
    }
}

@Serializable
data class SecurityConfig(
    val allowedDomains: List<String> = emptyList(),
    val maxResponseBytes: Long = 1024 * 1024,
    val maxRequestsPerRun: Int = 20,
    val connectTimeoutMs: Int = 10000,
    val readTimeoutMs: Int = 15000,
    val totalTimeoutMs: Int = 30000,
    val allowRedirects: Boolean = true,
    val maxRedirects: Int = 5,
)

@Serializable
data class RemoteWorkflowConfig(
    val name: String?,
    val version: String?,
    val description: String?,
    val steps: List<WorkflowStepConfig>?,
    val output: WorkflowOutputConfig?,
)

@Serializable
data class WorkflowStepConfig(
    val op: String,
    val id: String?,
    val params: Map<String, String> = emptyMap(),
    val saveAs: String?,
)

@Serializable
data class WorkflowOutputConfig(
    val type: String?,
    val mapping: Map<String, String> = emptyMap(),
)