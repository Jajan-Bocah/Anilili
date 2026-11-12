package com.anilili.data.provider.remote.engine.workflow

import com.anilili.data.provider.remote.engine.security.PolicyEnforcingDns
import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy
import com.anilili.diagnostics.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.URL
import java.util.concurrent.TimeUnit

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
            // Request counting is strictly performed inside executeHttpStep for real HTTP requests
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

        // Increment request count only on actual HTTP requests
        requestPolicy.incrementRequestCount(workflowId)

        val headers = TemplateEvaluator.evaluateMap(step.headers, context)
        val query = TemplateEvaluator.evaluateMap(step.query, context)

        val urlWithQuery = if (query.isNotEmpty()) {
            val urlObj = URL(url)
            val queryString = query.entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
            "${urlObj.protocol}://${urlObj.host}${if (urlObj.port != -1) ":${urlObj.port}" else ""}${urlObj.path}?$queryString"
        } else {
            url
        }

        val body = step.body?.let { TemplateEvaluator.evaluate(it, context) }

        return withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(urlWithQuery)

            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val jsonMediaType = "application/json".toMediaType()
            val requestBody = (body ?: "").toRequestBody(jsonMediaType)

            when (step.method) {
                HttpMethod.GET -> requestBuilder.get()
                HttpMethod.POST -> requestBuilder.post(requestBody)
                HttpMethod.PUT -> requestBuilder.put(requestBody)
                HttpMethod.PATCH -> requestBuilder.patch(requestBody)
                HttpMethod.DELETE -> requestBuilder.delete()
                HttpMethod.HEAD -> requestBuilder.head()
                HttpMethod.OPTIONS -> requestBuilder.method("OPTIONS", null)
            }

            val request = requestBuilder.build()

            val connectTimeout = requestPolicy.getConnectTimeoutMs().toLong()
            val readTimeout = requestPolicy.getReadTimeoutMs().toLong()

            // Build specialized OkHttpClient with connection-time DNS policy enforcement and safe redirect handling
            val client = okHttpClient.newBuilder()
                .dns(PolicyEnforcingDns(requestPolicy))
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS)
                .followRedirects(requestPolicy.isRedirectsAllowed())
                .addNetworkInterceptor { chain ->
                    val origRequest = chain.request()
                    val response = chain.proceed(origRequest)

                    // Safe redirect handling: if redirecting cross-host, strip sensitive headers
                    if (response.isRedirect) {
                        val location = response.header("Location")
                        if (!location.isNullOrBlank()) {
                            val targetUrl = try { URL(origRequest.url.toUrl(), location) } catch (e: Exception) { null }
                            if (targetUrl != null && !targetUrl.host.equals(origRequest.url.host, ignoreCase = true)) {
                                val redirectValidation = requestPolicy.validateRedirect(origRequest.url.toString(), targetUrl.toString(), workflowId)
                                if (!redirectValidation.allowed) {
                                    throw java.io.IOException("Blocked unsafe redirect to ${targetUrl}: ${redirectValidation.error}")
                                }
                            }
                        }
                    }
                    response
                }
                .build()

            val call = client.newCall(request)

            val response = runCatching { call.execute() }
                .onFailure { StepResult.failure("HTTP request failed: ${it.message}") }
                .getOrNull()
                ?: return@withContext StepResult.failure("HTTP request returned null")

            try {
                if (!response.isSuccessful) {
                    return@withContext StepResult.failure("HTTP ${response.code}: ${response.message}")
                }

                val maxBytes = requestPolicy.getMaxResponseBytes()
                val declaredContentLength = response.body?.contentLength() ?: -1L
                if (declaredContentLength > maxBytes) {
                    return@withContext StepResult.failure("Response content-length exceeds limit: $declaredContentLength > $maxBytes")
                }

                // Stream bounded read to prevent heap OOM before parsing
                val bodySource = response.body?.source() ?: return@withContext StepResult.failure("Null response body")
                val buffer = okio.Buffer()
                var totalBytesRead = 0L
                while (!bodySource.exhausted()) {
                    val toRead = minOf(8192L, maxBytes - totalBytesRead + 1L)
                    val read = bodySource.read(buffer, toRead)
                    if (read == -1L) break
                    totalBytesRead += read
                    if (totalBytesRead > maxBytes) {
                        return@withContext StepResult.failure("Response stream size exceeded policy limit of $maxBytes bytes")
                    }
                }
                val responseBody = buffer.readUtf8()

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

    private fun resolveInputString(inputExpr: String, context: WorkflowContext): String? {
        if (inputExpr.startsWith("{{") && inputExpr.endsWith("}}")) {
            return TemplateEvaluator.evaluate(inputExpr, context).takeIf { it.isNotBlank() }
        }
        val direct = context.variables[inputExpr]
        if (direct != null) {
            return when (direct) {
                is HttpResponse -> direct.body
                is String -> direct
                is kotlinx.serialization.json.JsonPrimitive -> direct.content
                else -> direct.toString()
            }
        }
        val resolved = TemplateEvaluator.resolveVariable(inputExpr, context)
        if (resolved != null) return resolved

        return TemplateEvaluator.evaluate(inputExpr, context).takeIf { it.isNotBlank() }
    }

    private fun resolveInputRaw(inputExpr: String, context: WorkflowContext): Any? {
        if (inputExpr.startsWith("{{") && inputExpr.endsWith("}}")) {
            return TemplateEvaluator.resolveRawVariable(inputExpr, context)
        }
        val direct = context.variables[inputExpr]
        if (direct != null) {
            return if (direct is HttpResponse) direct.body else direct
        }
        return TemplateEvaluator.resolveRawVariable(inputExpr, context)
    }

    private fun executeParseHtmlStep(step: ParseHtmlStep, context: WorkflowContext): StepResult {
        val input = resolveInputString(step.input, context)
        if (input.isNullOrBlank()) {
            return StepResult.failure("ParseHtmlStep: input '${step.input}' is empty or not resolved")
        }

        return try {
            val document = htmlParser.parse(input)
            StepResult.success(document)
        } catch (e: Exception) {
            StepResult.failure("HTML parsing failed: ${e.message}")
        }
    }

    private fun executeSelectStep(step: SelectStep, context: WorkflowContext): StepResult {
        val rawInput = resolveInputRaw(step.input, context)
        val input = when (rawInput) {
            is String -> rawInput
            is HttpResponse -> rawInput.body
            is org.jsoup.nodes.Document -> rawInput.outerHtml()
            is org.jsoup.nodes.Element -> rawInput.outerHtml()
            else -> rawInput?.toString()
        } ?: return StepResult.failure("SelectStep: input variable '${step.input}' not found")

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
        val input = resolveInputString(step.input, context)
            ?: return StepResult.failure("ParseJsonStep: input variable '${step.input}' not found or empty")

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
        val input = resolveInputString(step.input, context)
            ?: return StepResult.failure("RegexStep: input variable '${step.input}' not found or empty")

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
        val rawInput = resolveInputRaw(step.input, context)
        val sourceToMap = when (rawInput) {
            is kotlinx.serialization.json.JsonElement -> rawInput
            is List<*> -> rawInput
            is Map<*, *> -> rawInput
            is String -> runCatching { jsonParser.parse(rawInput) }.getOrElse { rawInput }
            else -> rawInput
        } ?: return StepResult.failure("MapStep: input variable '${step.input}' not found")

        return try {
            val mapped = jsonParser.mapFields(sourceToMap, step.mappings)
            StepResult.success(mapped)
        } catch (e: Exception) {
            StepResult.failure("Mapping failed: ${e.message}")
        }
    }

    private fun executeNormalizeStep(step: NormalizeStep, context: WorkflowContext): StepResult {
        var value = resolveInputString(step.input, context)
            ?: return StepResult.failure("NormalizeStep: input variable '${step.input}' not found")

        for (op in step.operations) {
            value = when (op) {
                is TrimOp -> value.trim()
                is LowerCaseOp -> value.lowercase()
                is UpperCaseOp -> value.uppercase()
                is UrlResolveOp -> resolveUrl(value, op.baseUrl, context)
                is EpisodeNumberOp -> normalizeEpisodeNumber(value)
                is LanguageNormalizeOp -> normalizeLanguage(value)
                is JoinOp -> {
                    val list = (resolveInputRaw(step.input, context) as? List<*>)
                        ?: context.getList(step.input)
                    val joined = list?.joinToString(op.separator) ?: value
                    return StepResult.success(joined)
                }
                is DefaultOp -> if (value.isBlank()) op.value else value
                is Base64DecodeOp -> Base64Codec.decodeToString(value)
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
            "id", "ind", "indonesia", "indonesian" -> "ind"
            else -> value.lowercase().trim()
        }
    }

    private fun executeAssertStep(step: AssertStep, context: WorkflowContext): StepResult {
        val input = resolveInputString(step.input, context) ?: ""

        val passed = when (step.condition) {
            is NotNullCondition -> input.isNotEmpty()
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
        val raw = resolveInputRaw(step.input, context)
        val list = when (raw) {
            is List<*> -> raw
            is kotlinx.serialization.json.JsonArray -> raw.map { (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString() }
            else -> context.getList(step.input)
        } ?: return StepResult.failure("FilterStep: input variable '${step.input}' not found or not a list")

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
            else -> output.mapping.mapValues { (_, varName) ->
                context.variables[varName] ?: context.getString(varName)
            }
        }
    }
}

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
    fun extractByPath(json: String, path: String): Any?
    fun mapFields(source: Any, mappings: Map<String, String>): Any
}

interface RegexEngine {
    fun findFirst(input: String, pattern: String, group: Int): String?
    fun findAll(input: String, pattern: String, group: Int): List<String>
    fun replace(input: String, pattern: String, replacement: String): String
}

class JsoupHtmlParser : HtmlParser {
    override fun parse(html: String): Any {
        return org.jsoup.Jsoup.parse(html)
    }

    override fun selectFirst(html: String, selector: String, attribute: String?): String? {
        val doc = org.jsoup.Jsoup.parse(html)
        val element = doc.selectFirst(selector) ?: return null
        return if (attribute != null) element.attr(attribute) else element.text()
    }

    override fun selectAll(html: String, selector: String, attribute: String?): List<String> {
        val doc = org.jsoup.Jsoup.parse(html)
        val elements = doc.select(selector)
        return elements.map { element ->
            if (attribute != null) element.attr(attribute) else element.text()
        }
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
    }

    override fun parse(jsonString: String): Any {
        return json.parseToJsonElement(jsonString)
    }

    override fun extractByPath(jsonString: String, path: String): Any? {
        val element = json.parseToJsonElement(jsonString)
        val parts = path.split(".")
        var current: kotlinx.serialization.json.JsonElement? = element

        for (part in parts) {
            current = when {
                part.startsWith("[") && part.endsWith("]") -> {
                    val index = part.removePrefix("[").removeSuffix("]").toIntOrNull() ?: return null
                    val array = current as? kotlinx.serialization.json.JsonArray ?: return null
                    if (index in 0 until array.size) array[index] else null
                }
                current is kotlinx.serialization.json.JsonObject -> current[part]
                else -> null
            }
            if (current == null) return null
        }

        return when (current) {
            is kotlinx.serialization.json.JsonPrimitive -> current.content
            is kotlinx.serialization.json.JsonArray -> current.map {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
            }
            else -> current?.toString()
        }
    }

    override fun mapFields(source: Any, mappings: Map<String, String>): Any {
        return when (source) {
            is kotlinx.serialization.json.JsonArray -> {
                kotlinx.serialization.json.JsonArray(
                    source.map { element ->
                        if (element is kotlinx.serialization.json.JsonObject) {
                            mapJsonObject(element, mappings)
                        } else {
                            element
                        }
                    }
                )
            }
            is kotlinx.serialization.json.JsonObject -> {
                mapJsonObject(source, mappings)
            }
            is List<*> -> {
                source.map { item ->
                    if (item != null) mapFields(item, mappings) else null
                }
            }
            is Map<*, *> -> {
                source.entries.associate { (key, value) ->
                    val newKey = mappings[key.toString()] ?: key.toString()
                    newKey to value
                }
            }
            else -> source
        }
    }

    private fun mapJsonObject(
        obj: kotlinx.serialization.json.JsonObject,
        mappings: Map<String, String>,
    ): kotlinx.serialization.json.JsonObject {
        return kotlinx.serialization.json.JsonObject(
            obj.entries.associate { (key, value) ->
                (mappings[key] ?: key) to value
            }
        )
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
