package com.anilili.data.provider.remote.engine.workflow

import kotlinx.serialization.Serializable

@Serializable
data class WorkflowDefinition(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val steps: List<WorkflowStep>,
    val output: WorkflowOutput,
)

@Serializable
data class WorkflowOutput(
    val type: OutputType,
    val mapping: Map<String, String> = emptyMap(),
)

@Serializable
enum class OutputType {
    EPISODES,
    SOURCES,
    SEARCH,
    SERVERS,
    RAW,
}

data class WorkflowContext(
    val variables: MutableMap<String, Any> = mutableMapOf(),
    val metadata: MutableMap<String, String> = mutableMapOf(),
) {
    fun getString(key: String): String? {
        return variables[key]?.toString()
    }

    fun getStringOrThrow(key: String): String {
        return variables[key]?.toString() ?: throw IllegalStateException("Missing required variable: $key")
    }

    fun getList(key: String): List<Any>? {
        return (variables[key] as? List<*>)?.filterIsInstance<Any>()
    }

    fun put(key: String, value: Any) {
        variables[key] = value
    }

    fun putAll(map: Map<String, Any>) {
        variables.putAll(map)
    }

    fun has(key: String): Boolean = variables.containsKey(key)
}

data class StepResult(
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null,
    val executionTimeMs: Long = 0,
) {
    companion object {
        fun success(output: Any? = null, executionTimeMs: Long = 0) = StepResult(true, output, null, executionTimeMs)
        fun failure(error: String, executionTimeMs: Long = 0) = StepResult(false, null, error, executionTimeMs)
    }
}

data class WorkflowExecutionResult(
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null,
    val stepResults: List<StepResult> = emptyList(),
    val totalTimeMs: Long = 0,
    val variables: Map<String, Any> = emptyMap(),
) {
    companion object {
        fun success(output: Any? = null, stepResults: List<StepResult> = emptyList(), totalTimeMs: Long = 0, variables: Map<String, Any> = emptyMap()) =
            WorkflowExecutionResult(true, output, null, stepResults, totalTimeMs, variables)
        fun failure(error: String, stepResults: List<StepResult> = emptyList(), totalTimeMs: Long = 0, variables: Map<String, Any> = emptyMap()) =
            WorkflowExecutionResult(false, null, error, stepResults, totalTimeMs, variables)
    }
}