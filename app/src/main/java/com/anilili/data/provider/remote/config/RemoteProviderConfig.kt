package com.anilili.data.provider.remote.config

import com.anilili.data.provider.remote.engine.security.RemoteRequestPolicy.SecurityConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class RemoteProviderConfig(
    val schemaVersion: Int,
    val providers: List<RemoteProviderEntry>,
    val metadata: ConfigMetadata,
    val workflows: Map<String, RemoteWorkflowConfig>? = null,
    val security: SecurityConfig? = null,
    val update: UpdateConfig? = null,
)

@Serializable
data class RemoteProviderEntry(
    val id: String,
    val name: String,
    val endpoint: String,
    val source: RemoteSourceType,
    val capabilities: ProviderCapabilities,
    val category: RemoteCategory = RemoteCategory.GENERAL,
    val languages: List<String> = emptyList(),
    val subtitleVariants: List<RemoteSubtitleVariant> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true,
)

@Serializable
enum class RemoteSourceType(val value: String) {
    @SerialName("miruro")
    MIRURO("miruro"),
    @SerialName("anivexa")
    ANIVEXA("anivexa"),
    @SerialName("generic_json")
    GENERIC_JSON("generic_json"),
    @SerialName("generic_html")
    GENERIC_HTML("generic_html"),
}

@Serializable
data class ProviderCapabilities(
    val supportsEpisodes: Boolean = true,
    val supportsSources: Boolean = true,
    val supportsSubtitles: Boolean = false,
    val supportsSkipTimes: Boolean = false,
    val supportsDownload: Boolean = false,
    val supportsSearch: Boolean = false,
    val requiresWebView: Boolean = false,
    val multiAudio: Boolean = false,
    val shortLivedStreamUrls: Boolean = false,
    val countryLocked: String? = null,
    val adultOnly: Boolean = false,
)

@Serializable
enum class RemoteCategory(val value: String) {
    @SerialName("general")
    GENERAL("general"),
    @SerialName("anime")
    ANIME("anime"),
    @SerialName("donghua")
    DONGHUA("donghua"),
    @SerialName("adult")
    ADULT("adult"),
    @SerialName("special")
    SPECIAL("special"),
}

@Serializable
enum class RemoteSubtitleVariant(val value: String) {
    @SerialName("hsub")
    HARD_SUB("hsub"),
    @SerialName("ssub")
    SOFT_SUB("ssub"),
    @SerialName("sub")
    GENERIC_SUB("sub"),
    @SerialName("dub")
    DUB("dub"),
}

@Serializable
data class ConfigMetadata(
    val name: String,
    val description: String,
    val author: String?,
    val version: String,
    val sourceUrl: String,
    val fetchedAt: Long,
    val checksum: String?,
    val minAppVersion: String = "0.1.0",
    val maxAppVersion: String? = null,
)

@Serializable
data class RemoteWorkflowConfig(
    val name: String?,
    val version: String?,
    val description: String?,
    val steps: List<WorkflowStepConfig>?,
    val output: WorkflowOutputConfig?,
)

object WorkflowParamsSerializer : KSerializer<Map<String, String>> {
    private val delegate = MapSerializer(String.serializer(), JsonElement.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        if (encoder is JsonEncoder) {
            val jsonMap = value.mapValues { (_, v) ->
                try {
                    encoder.json.parseToJsonElement(v)
                } catch (_: Exception) {
                    JsonPrimitive(v)
                }
            }
            encoder.encodeSerializableValue(delegate, jsonMap)
        } else {
            encoder.encodeSerializableValue(MapSerializer(String.serializer(), String.serializer()), value)
        }
    }

    override fun deserialize(decoder: Decoder): Map<String, String> {
        return if (decoder is JsonDecoder) {
            val jsonMap = decoder.decodeSerializableValue(delegate)
            jsonMap.mapValues { (_, element) ->
                if (element is JsonPrimitive) {
                    element.content
                } else {
                    element.toString()
                }
            }
        } else {
            decoder.decodeSerializableValue(MapSerializer(String.serializer(), String.serializer()))
        }
    }
}

@Serializable
data class WorkflowStepConfig(
    val op: String,
    val id: String?,
    @Serializable(with = WorkflowParamsSerializer::class)
    val params: Map<String, String> = emptyMap(),
    val saveAs: String?,
)

@Serializable
data class WorkflowOutputConfig(
    val type: String?,
    val mapping: Map<String, String> = emptyMap(),
)

@Serializable
data class UpdateConfig(
    val mode: String = "auto",
    val intervalHours: Long = 24,
)

@Serializable
data class RemoteProviderList(
    val config: RemoteProviderConfig,
    val originalUrl: String,
    val fetchedAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)