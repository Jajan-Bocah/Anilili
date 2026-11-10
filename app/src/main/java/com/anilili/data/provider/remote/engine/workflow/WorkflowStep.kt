package com.anilili.data.provider.remote.engine.workflow

import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkflowStep {
    val id: String
    val saveAs: String?
}

@Serializable
data class HttpStep(
    override val id: String,
    val method: HttpMethod = HttpMethod.GET,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: String? = null,
    override val saveAs: String? = "response",
    val timeoutMs: Int = 15000,
    val followRedirects: Boolean = true,
    val maxRedirects: Int = 5,
) : WorkflowStep

@Serializable
enum class HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}

@Serializable
data class ParseHtmlStep(
    override val id: String,
    val input: String,
    override val saveAs: String? = "document",
) : WorkflowStep

@Serializable
data class SelectStep(
    override val id: String,
    val input: String,
    val selector: String,
    val attribute: String? = null,
    val mode: SelectMode = SelectMode.FIRST,
    override val saveAs: String? = "selection",
) : WorkflowStep

@Serializable
enum class SelectMode {
    FIRST, ALL, TEXT, HTML, ATTRIBUTE
}

@Serializable
data class ParseJsonStep(
    override val id: String,
    val input: String,
    val jsonPath: String? = null,
    override val saveAs: String? = "json",
) : WorkflowStep

@Serializable
data class RegexStep(
    override val id: String,
    val input: String,
    val pattern: String,
    val group: Int = 0,
    val mode: RegexMode = RegexMode.FIRST,
    override val saveAs: String? = "match",
) : WorkflowStep

@Serializable
enum class RegexMode {
    FIRST, ALL, REPLACE
}

@Serializable
data class MapStep(
    override val id: String,
    val input: String,
    val mappings: Map<String, String>,
    override val saveAs: String? = "mapped",
) : WorkflowStep

@Serializable
data class NormalizeStep(
    override val id: String,
    val input: String,
    val operations: List<NormalizeOperation>,
    override val saveAs: String? = "normalized",
) : WorkflowStep

@Serializable
sealed interface NormalizeOperation {
    val type: String
}

@Serializable
data class TrimOp(override val type: String = "trim") : NormalizeOperation
@Serializable
data class LowerCaseOp(override val type: String = "lowercase") : NormalizeOperation
@Serializable
data class UpperCaseOp(override val type: String = "uppercase") : NormalizeOperation
@Serializable
data class UrlResolveOp(override val type: String = "url_resolve", val baseUrl: String) : NormalizeOperation
@Serializable
data class EpisodeNumberOp(override val type: String = "episode_number") : NormalizeOperation
@Serializable
data class LanguageNormalizeOp(override val type: String = "language_normalize") : NormalizeOperation
@Serializable
data class JoinOp(override val type: String = "join", val separator: String) : NormalizeOperation
@Serializable
data class DefaultOp(override val type: String = "default", val value: String) : NormalizeOperation

@Serializable
data class AssertStep(
    override val id: String,
    val input: String,
    val condition: AssertCondition,
    val message: String = "Assertion failed",
) : WorkflowStep {
    override val saveAs: String? = null
}

@Serializable
sealed interface AssertCondition

@Serializable
data class NotNullCondition(override val type: String = "not_null") : AssertCondition
@Serializable
data class NotEmptyCondition(override val type: String = "not_empty") : AssertCondition
@Serializable
data class EqualsCondition(override val type: String = "equals", val value: String) : AssertCondition
@Serializable
data class ContainsCondition(override val type: String = "contains", val value: String) : AssertCondition
@Serializable
data class MatchesCondition(override val type: String = "matches", val pattern: String) : AssertCondition
@Serializable
data class SizeCondition(override val type: String = "size", val min: Int? = null, val max: Int? = null) : AssertCondition

@Serializable
data class FilterStep(
    override val id: String,
    val input: String,
    val predicate: FilterPredicate,
    override val saveAs: String? = "filtered",
) : WorkflowStep

@Serializable
sealed interface FilterPredicate

@Serializable
data class NotNullPredicate(override val type: String = "not_null") : FilterPredicate
@Serializable
data class NotEmptyPredicate(override val type: String = "not_empty") : FilterPredicate
@Serializable
data class ContainsPredicate(override val type: String = "contains", val value: String) : FilterPredicate
@Serializable
data class MatchesPredicate(override val type: String = "matches", val pattern: String) : FilterPredicate