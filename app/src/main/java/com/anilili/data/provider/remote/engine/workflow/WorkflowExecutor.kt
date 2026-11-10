package com.anilili.data.provider.remote.engine.workflow

import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WorkflowExecutor(
    private val okHttpClient: OkHttpClient,
    private val requestPolicy: RemoteRequestPolicy,
    private val htmlParser: HtmlParser = JsoupHtmlParser(),
    private val jsonParser: JsonParser = KotlinxJsonParser(),
    private val regexEngine: RegexEngine = KotlinRegexEngine(),
) {

    suspend fun execute(
        workflow: WorkflowDefinition,
        initialVariables: Map<String, Any> = emptyMap(),
        workflowId: String = "workflow_${System.currentTimeMillis()}",
    ): WorkflowExecutionResult {
        val startTime = System.currentTimeMillis()
        val context = WorkflowContext().apply {
            putAll(initialVariables)
        }

        val stepResults = mutableListOf<StepResult>()
        requestPolicy.resetRequestCount(workflowId)

        for (step in workflow.steps) {
            val stepStartTime = System.currentTimeMillis()
            val stepResult = executeStep(step, context, workflowId)
            val stepTime = System.currentTimeMillis() - stepStartTime

            val resultWithTime = stepResult.copy(executionTimeMs = stepTime)
            stepResults.add(resultWithTime)

            if (!stepResult.success) {
                val totalTime = System.currentTimeMillis() - startTime
                DiagnosticsLog.event(
                    "WorkflowExecutor step failed: workflow=$workflowId step=${step.id} error=${stepResult.error} totalTimeMs=$totalTime"
                )
                return WorkflowExecutionResult.failure(
                    stepResult.error ?: "Step ${step.id} failed",
                    stepResults,
                    totalTime,
                    context.variables.toMap(),
                )
            }

            stepResult.output?.let { output ->
                step.saveAs?.let { key ->
                    context.put(key, output)
                }
            }

            requestPolicy.incrementRequestCount(workflowId)
        }

        val totalTime = System.currentTimeMillis() - startTime
        val output = extractOutput(workflow.output, context)

        DiagnosticsLog.event(
            "WorkflowExecutor completed: workflow=$workflowId steps=${workflow.steps.size} totalTimeMs=$totalTime"
        )

        return WorkflowExecutionResult.success(
            output,
            stepResults,
            totalTime,
            context.variables.toMap(),
        )
    }

    private suspend fun executeStep(
        step: WorkflowStep,
        context: WorkflowContext,
        workflowId: String,
    ): StepResult {
        return when (step) {
            is HttpStep -> executeHttpStep(step, context, workflowId)
            is ParseHtmlStep -> executeParseHtmlStep(step, context)
            is SelectStep -> executeSelectStep(step, context)
            is ParseJsonStep -> executeParseJsonStep(step, context)
            is RegexStep -> executeRegexStep(step, context)
            is MapStep -> executeMapStep(step, context)
            is NormalizeStep -> executeNormalizeStep(step, context)
            is AssertStep -> executeAssertStep(step, context)
            is FilterStep -> executeFilterStep(step, context)
            else -> StepResult.failure("Unknown step type: ${step.javaClass.simpleName}")
        }
    }

    private suspend fun executeHttpStep(
        step: HttpStep,
        context: WorkflowContext,
        workflowId: String,
    ): StepResult {
        val url = TemplateEvaluator.evaluateUrl(step.url, context)
        val validation = requestPolicy.validateRequest(url, workflowId)

        if (!validation.allowed) {
            return StepResult.failure(validation.error ?: "Request validation failed")
        }

        val headers = TemplateEvaluator.evaluateMap(step.headers, context)
        val query = TemplateEvaluator.evaluateMap(step.query, context)

        val urlWithQuery = if (query.isNotEmpty()) {
            val urlObj = URL(url)
            val queryString = query.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            "${urlObj.protocol}://${urlObj.host}${if (urlObj.port != -1) ":${urlObj.port}"}${urlObj.path}?$queryString"
        } else {
            url
        }

        val body = step.body?.let { TemplateEvaluator.evaluate(it, context) }

        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(urlWithQuery)

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            when (step.method) {
                HttpMethod.GET -> requestBuilder.get()
                HttpMethod.POST -> requestBuilder.post(body?.let { RequestBody.create(it.toByteArray(Charsets.UTF_8), MediaType.get("application/json")) }!!)
                HttpMethod.PUT -> requestBuilder.put(body?.let { RequestBody.create(it.toByteArray(Charsets.UTF_8), MediaType.get("application/json")) }!!)
                HttpMethod.PATCH -> requestBuilder.patch(body?.let { RequestBody.create(it.toByteArray(Charsets.UTF_8), MediaType.get("application/json")) }!!)
                HttpMethod.DELETE -> requestBuilder.delete()
                HttpMethod.HEAD -> requestBuilder.head()
                HttpMethod.OPTIONS -> requestBuilder.method("OPTIONS", null)
            }

            val request = requestBuilder.build()

            val call = okHttpClient.newBuilder()
                .connectTimeout(requestPolicy.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(requestPolicy.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .followRedirects(requestPolicy.isRedirectsAllowed())
                .build()
                .newCall(request)

            val response = runCatching { call.execute() }
                .onFailure { StepResult.failure("HTTP request failed: ${it.message}") }
                .getOrNull()
                ?: return@withContext StepResult.failure("HTTP request returned null")

            try {
                if (!response.isSuccessful) {
                    return@withContext StepResult.failure("HTTP ${response.code}: ${response.message}")
                }

                val responseBody = response.body?.use { it.string() } ?: ""
                val contentLength = response.body?.contentLength() ?: responseBody.length.toLong()

                if (contentLength > requestPolicy.getMaxResponseBytes()) {
                    return@withContext StepResult.failure("Response size exceeds limit: $contentLength > ${requestPolicy.getMaxResponseBytes()}")
                }

                val result = HttpResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                    body = responseBody,
                )

                StepResult.success(result)
            } finally {
                response.close()
            }
        }
    }

    private fun executeParseHtmlStep(step: ParseHtmlStep, context: WorkflowContext): StepResult {
        val input = TemplateEvaluator.evaluate(step.input, context)
        if (input.isBlank()) {
            return StepResult.failure("ParseHtmlStep: input is empty")
        }

        return try {
            val document = htmlParser.parse(input)
            StepResult.success(document)
        } catch (e: Exception) {
            StepResult.failure("HTML parsing failed: ${e.message}")
        }
    }

    private fun executeSelectStep(step: SelectStep, context: WorkflowContext): StepResult {
        val input = context.getString(step.input) ?: return StepResult.failure("SelectStep: input variable '${step.input}' not found or not a string")

        return try {
            val result = when (step.mode) {
                SelectMode.FIRST -> htmlParser.selectFirst(input, step.selector, step.attribute)
                SelectMode.ALL -> htmlParser.selectAll(input, step.selector, step.attribute)
                SelectMode.TEXT -> htmlParser.selectText(input, step.selector)
                SelectMode.HTML -> htmlParser.selectHtml(input, step.selector)
                SelectMode.ATTRIBUTE -> htmlParser.selectAttribute(input, step.selector, step.attribute ?: "")
            }
            StepResult.success(result)
        } catch (e: Exception) {
            StepResult.failure("Selection failed: ${e.message}")
        }
    }

    private fun executeParseJsonStep(step: ParseJsonStep, context: WorkflowContext): StepResult {
        val input = context.getString(step.input) ?: return StepResult.failure("ParseJsonStep: input variable '${step.input}' not found or not a string")
        if (input.isBlank()) {
            return StepResult.failure("ParseJsonStep: input is empty")
        }

        return try {
            val result = step.jsonPath?.let { path ->
                jsonParser.extractByPath(input, path)
            } ?: jsonParser.parse(input)
            StepResult.success(result)
        } catch (e: Exception) {
            StepResult.failure("JSON parsing failed: ${e.message}")
        }
    }

    private fun executeRegexStep(step: RegexStep, context: WorkflowContext): StepResult {
        val input = context.getString(step.input) ?: return StepResult.failure("RegexStep: input variable '${step.input}' not found or not a string")
        if (input.isBlank()) {
            return StepResult.failure("RegexStep: input is empty")
        }

        return try {
            val result = when (step.mode) {
                RegexMode.FIRST -> regexEngine.findFirst(input, step.pattern, step.group)
                RegexMode.ALL -> regexEngine.findAll(input, step.pattern, step.group)
                RegexMode.REPLACE -> regexEngine.replace(input, step.pattern, "")
            }
            StepResult.success(result)
        } catch (e: Exception) {
            StepResult.failure("Regex execution failed: ${e.message}")
        }
    }

    private fun executeMapStep(step: MapStep, context: WorkflowContext): StepResult {
        val input = context.getString(step.input) ?: return StepResult.failure("MapStep: input variable '${step.input}' not found or not a string")
        if (input.isBlank()) {
            return StepResult.failure("MapStep: input is empty")
        }

        try {
            val parsed = jsonParser.parse(input)
            val mapped = jsonParser.mapFields(parsed, step.mappings)
            StepResult.success(mapped)
        } catch (e: Exception) {
            StepResult.failure("Mapping failed: ${e.message}")
        }
    }

    private fun executeNormalizeStep(step: NormalizeStep, context: WorkflowContext): StepResult {
        var value = context.getString(step.input) ?: return StepResult.failure("NormalizeStep: input variable '${step.input}' not found or not a string")

        for (op in step.operations) {
            value = when (op) {
                is TrimOp -> value.trim()
                is LowerCaseOp -> value.lowercase()
                is UpperCaseOp -> value.uppercase()
                is UrlResolveOp -> resolveUrl(value, op.baseUrl, context)
                is EpisodeNumberOp -> normalizeEpisodeNumber(value)
                is LanguageNormalizeOp -> normalizeLanguage(value)
                is JoinOp -> {
                    val list = context.getList(step.input)?.joinToString(op.separator) ?: value
                    list
                }
                is DefaultOp -> if (value.isBlank()) op.value else value
            }
        }

        return StepResult.success(value)
    }

    private fun resolveUrl(value: String, baseUrl: String, context: WorkflowContext): String {
        val resolvedBase = TemplateEvaluator.evaluateUrl(baseUrl, context)
        return try {
            URL(URL(resolvedBase), value).toString()
        } catch (e: Exception) {
            value
        }
    }

    private fun normalizeEpisodeNumber(value: String): String {
        return value.trim()
            .replace(Regex("(?i)^ep\\.?\\s*"), "")
            .replace(Regex("(?i)^episode\\s*"), "")
            .trim()
    }

    private fun normalizeLanguage(value: String): String {
        return when (value.lowercase().trim()) {
            "en", "eng", "english" -> "eng"
            "ja", "jpn", "japanese" -> "jpn"
            "zh", "zho", "chinese" -> "zho"
            "ko", "kor", "korean" -> "kor"
            "es", "spa", "spanish" -> "spa"
            "pt", "por", "portuguese" -> "por"
            "fr", "fra", "french" -> "fra"
            "de", "deu", "german" -> "deu"
            "ar", "ara", "arabic" -> "ara"
            "id", "ind", "indonesian" -> "ind"
            "hi", "hin", "hindi" -> "hin"
            "th", "tha", "thai" -> "tha"
            "vi", "vie", "vietnamese" -> "vie"
            else -> value.lowercase().trim()
        }
    }

    private fun executeAssertStep(step: AssertStep, context: WorkflowContext): StepResult {
        val input = context.getString(step.input) ?: return StepResult.failure("AssertStep: input variable '${step.input}' not found")

        val passed = when (step.condition) {
            is NotNullCondition -> input.isNotBlank()
            is NotEmptyCondition -> input.isNotBlank()
            is EqualsCondition -> input == step.condition.value
            is ContainsCondition -> input.contains(step.condition.value, ignoreCase = true)
            is MatchesCondition -> Regex(step.condition.pattern).matches(input)
            is SizeCondition -> {
                val size = input.length
                (step.condition.min == null || size >= step.condition.min!!) &&
                (step.condition.max == null || size <= step.condition.max!!)
            }
        }

        return if (passed) {
            StepResult.success(true)
        } else {
            StepResult.failure(step.message)
        }
    }

    private fun executeFilterStep(step: FilterStep, context: WorkflowContext): StepResult {
        val list = context.getList(step.input) ?: return StepResult.failure("FilterStep: input variable '${step.input}' not found or not a list")

        val filtered = list.filter { item ->
            val str = item.toString()
            when (step.predicate) {
                is NotNullPredicate -> str.isNotBlank()
                is NotEmptyPredicate -> str.isNotBlank()
                is ContainsPredicate -> str.contains(step.predicate.value, ignoreCase = true)
                is MatchesPredicate -> Regex(step.predicate.pattern).matches(str)
            }
        }

        return StepResult.success(filtered)
    }

    private fun extractOutput(output: WorkflowOutput, context: WorkflowContext): Any? {
        return when (output.type) {
            OutputType.RAW -> context.variables
            else -> output.mapping.mapValues { (key, varName) ->
                key to context.getString(varName)
            }
        }
    }
}

@Serializable
data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: String,
)

interface HtmlParser {
    fun parse(html: String): Any
    fun selectFirst(html: String, selector: String, attribute: String?): String?
    fun selectAll(html: String, selector: String, attribute: String?): List<String>
    fun selectText(html: String, selector: String): String?
    fun selectHtml(html: String, selector: String): String?
    fun selectAttribute(html: String, selector: String, attribute: String): String?
}

interface JsonParser {
    fun parse(json: String): Any
    fun extractByPath(json: String, path: String): Any
    fun mapFields(source: Any, mappings: Map<String, String>): Any
}

interface RegexEngine {
    fun findFirst(input: String, pattern: String, group: Int): String?
    fun findAll(input: String, pattern: String, group: Int): List<String>
    fun replace(input: String, pattern: String, replacement: String): String
}

class JsoupHtmlParser : HtmlParser {
    override fun parse(html: String): Any = org.jsoup.Jsoup.parse(html)

    override fun selectFirst(html: String, selector: String, attribute: String?): String? {
        val doc = org.jsoup.Jsoup.parse(html)
        val el = doc.selectFirst(selector) ?: return null
        return if (attribute != null) el.attr(attribute) else el.text()
    }

    override fun selectAll(html: String, selector: String, attribute: String?): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.select(selector).map { if (attribute != null) it.attr(attribute) else it.text() }
    }

    override fun selectText(html: String, selector: String): String? {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst(selector)?.text()
    }

    override fun selectHtml(html: String, selector: String): String? {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst(selector)?.html()
    }

    override fun selectAttribute(html: String, selector: String, attribute: String): String? {
        val doc = org.jsoup.Jsoup.parse(html)
        return doc.selectFirst(selector)?.attr(attribute)
    }
}

class KotlinxJsonParser : JsonParser {
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun parse(jsonStr: String): Any {
        return json.parse(kotlinx.serialization.json.JsonElement.serializer(), jsonStr)
    }

    override fun extractByPath(jsonStr: String, path: String): Any {
        val element = parse(jsonStr) as kotlinx.serialization.json.JsonElement
        return extractByPath(element, path)
    }

    private fun extractByPath(element: kotlinx.serialization.json.JsonElement, path: String): Any {
        val parts = path.split(".").filter { it.isNotBlank() }
        var current: kotlinx.serialization.json.JsonElement = element

        for (part in parts) {
            current = when {
                part.matches(Regex("^\\[\\d+\\]$")) -> {
                    val index = part.substring(1, part.length - 1).toIntOrNull() ?: return null
                    (current as? kotlinx.serialization.json.JsonArray)?.getOrNull(index) ?: return null
                }
                else -> {
                    (current as? kotlinx.serialization.json.JsonObject)?.get(part) ?: return null
                }
            }
        }

        return when (current) {
            is kotlinx.serialization.json.JsonPrimitive -> current.content
            is kotlinx.serialization.json.JsonObject -> current
            is kotlinx.serialization.json.JsonArray -> current
            is kotlinx.serialization.json.JsonNull -> null
            else -> current
        }
    }

    override fun mapFields(source: Any, mappings: Map<String, String>): Any {
        val obj = source as? kotlinx.serialization.json.JsonObject ?: return source
        val result = kotlinx.serialization.json.JsonObject(
            obj.entries.map { (key, value) ->
                mappings[key]?.let { mappedKey -> mappedKey to value } ?: key to value
            }
        )
        return result
    }
}

class KotlinRegexEngine : RegexEngine {
    override fun findFirst(input: String, pattern: String, group: Int): String? {
        return Regex(pattern).find(input)?.groupValues?.getOrNull(group)
    }

    override fun findAll(input: String, pattern: String, group: Int): List<String> {
        return Regex(pattern).findAll(input).mapNotNull { it.groupValues.getOrNull(group) }.toList()
    }

    override fun replace(input: String, pattern: String, replacement: String): String {
        return input.replace(Regex(pattern), replacement)
    }
}