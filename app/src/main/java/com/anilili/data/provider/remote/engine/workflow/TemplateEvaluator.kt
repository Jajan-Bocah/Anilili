package com.anilili.data.provider.remote.engine.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.regex.Pattern

object TemplateEvaluator {
    private val VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\s*[a-zA-Z_][a-zA-Z0-9_.-]*\\s*)\\}\\}")

    fun evaluate(template: String, context: WorkflowContext): String {
        if (template.isBlank()) return template

        val matcher = VARIABLE_PATTERN.matcher(template)
        val buffer = StringBuffer()

        while (matcher.find()) {
            val varName = matcher.group(1).trim()
            val value = resolveVariable(varName, context)
            matcher.appendReplacement(buffer, value?.replace("\\", "\\\\")?.replace("$", "\\$") ?: "")
        }
        matcher.appendTail(buffer)

        return buffer.toString()
    }

    fun evaluateMap(map: Map<String, String>, context: WorkflowContext): Map<String, String> {
        return map.mapValues { (_, value) -> evaluate(value, context) }
    }

    fun resolveRawVariable(path: String, context: WorkflowContext): Any? {
        val cleanPath = path.removePrefix("{{").removeSuffix("}}").trim()
        val parts = cleanPath.split(".")
        var current: Any? = context.variables[parts[0]]

        if (current == null && parts.size == 1) {
            return context.metadata[parts[0]]
        }

        for (i in 1 until parts.size) {
            val part = parts[i]
            current = when (current) {
                is HttpResponse -> {
                    when (part.lowercase()) {
                        "body" -> current.body
                        "statuscode", "status" -> current.statusCode
                        "headers" -> current.headers
                        else -> current.headers[part] ?: current.headers.entries.firstOrNull { it.key.equals(part, ignoreCase = true) }?.value
                    }
                }
                is Map<*, *> -> {
                    current[part] ?: (current as? Map<String, *>)?.entries?.firstOrNull { it.key.equals(part, ignoreCase = true) }?.value
                }
                is JsonObject -> {
                    current[part]
                }
                is JsonArray -> {
                    val index = part.toIntOrNull() ?: return null
                    if (index in 0 until current.size) current[index] else null
                }
                is List<*> -> {
                    val index = part.toIntOrNull() ?: return null
                    if (index in current.indices) current[index] else null
                }
                else -> null
            }
            if (current == null) return null
        }

        return current
    }

    fun resolveVariable(path: String, context: WorkflowContext): String? {
        val raw = resolveRawVariable(path, context) ?: return null
        return when (raw) {
            is JsonPrimitive -> raw.content
            is String -> raw
            else -> raw.toString()
        }
    }

    fun evaluateUrl(url: String, context: WorkflowContext): String {
        return evaluate(url, context)
    }
}
