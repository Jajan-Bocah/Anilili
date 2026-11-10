package com.anilili.data.provider.remote.engine.workflow

import java.util.regex.Pattern

object TemplateEvaluator {
    private val VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\s*[a-zA-Z_][a-zA-Z0-9_.]*\\s*)\\}\\}")

    fun evaluate(template: String, context: WorkflowContext): String {
        if (template.isBlank()) return template

        var result = template
        val matcher = VARIABLE_PATTERN.matcher(template)
        val buffer = StringBuffer()

        while (matcher.find()) {
            val varName = matcher.group(1).trim()
            val value = resolveVariable(varName, context)
            matcher.appendReplacement(buffer, value?.replace("\\", "\\\\").replace("$", "\\$") ?: "")
        }
        matcher.appendTail(buffer)

        return buffer.toString()
    }

    fun evaluateMap(map: Map<String, String>, context: WorkflowContext): Map<String, String> {
        return map.mapValues { (_, value) -> evaluate(value, context) }
    }

    private fun resolveVariable(path: String, context: WorkflowContext): String? {
        val parts = path.split(".")
        var current: Any? = context.variables[parts[0]]

        if (current == null && parts.size == 1) {
            return context.metadata[parts[0]]
        }

        for (i in 1 until parts.size) {
            current = when {
                current is Map<*, *> -> current[parts[i]]
                current is List<*> -> {
                    val index = parts[i].toIntOrNull() ?: return null
                    if (index in current.indices) current[index] else null
                }
                else -> null
            }
            if (current == null) return null
        }

        return current?.toString()
    }

    fun evaluateUrl(url: String, context: WorkflowContext): String {
        return evaluate(url, context)
    }
}