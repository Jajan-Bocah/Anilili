# DELIVERABLE REPORT 3: Deep Technical Remediation Report
**Date**: 2026-11-11  
**Project**: Anilili Hybrid Remote Provider Engine v1.0  
**Branch**: `feature/hybrid-remote-provider-engine`  

---

## 1. Overview
This report provides granular technical documentation of all compilation, runtime, and architectural remediations applied to achieve full stability and test compliance for the Hybrid Remote Provider Engine.

---

## 2. Granular Remediation Details

### Remediation 1: Polymorphic Deserializer for WorkflowStepConfig.params
- **Source File**: `app/src/main/java/com/anilili/data/provider/remote/config/RemoteProviderConfig.kt`
- **Issue**: Workflow step definitions in `anichin.json` and `kuramanime.json` supply complex data structures (nested maps such as `{"query": {"type": "css", "value": ".animepost"}}`) within the `params` field. The default serializer required `Map<String, String>`, throwing `JsonDecodingException: Expected beginning of the string, but got {`.
- **Solution**:
  ```kotlin
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
  ```
  Annotated `WorkflowStepConfig.params` with `@Serializable(with = WorkflowParamsSerializer::class)`.

### Remediation 2: Domain vs Origin Decoupling in Validator
- **Source File**: `app/src/main/java/com/anilili/data/provider/remote/config/RemoteProviderConfigValidator.kt`
- **Issue**: `validateSecurity` compared provider endpoints against `allowedOrigins` (intended for config download origins), rejecting valid assets that fetch from `raw.githubusercontent.com`.
- **Solution**:
  - Validated `originalUrl` against `allowedOrigins` only if specified.
  - Extracted clean domain names via `getHost(url)` rather than protocol+host+port origins.
  - Compared endpoint domains against `securityConfig.allowedDomains` separately, supporting domain and wildcard subdomain matching (`endpointHost.endsWith(".$it")`).

### Remediation 3: Method Signature Backward Compatibility
- **Source File**: `app/src/main/java/com/anilili/data/MiruroRepository.kt`
- **Issue**: Unit tests relied on legacy overload signatures:
  `providerAttemptOrder(preferred, providers, fallbacks)`
  `languageGatedAttemptOrder(targetLanguage, providers, fallbacks, preferred)`
- **Solution**: Added overload methods preserving legacy signatures with default delegation to `ProviderCatalog`:
  ```kotlin
  fun providerAttemptOrder(
      preferred: String?,
      providers: List<String>,
      fallbacks: List<String>,
  ): List<String> = providerAttemptOrder(preferred, providers, fallbacks, UnifiedProviderRegistry.fromCatalog())

  fun languageGatedAttemptOrder(
      targetLanguage: String,
      providers: List<String>,
      fallbacks: List<String>,
      preferred: String? = null,
  ): List<String> = languageGatedAttemptOrder(targetLanguage, providers, fallbacks, preferred, UnifiedProviderRegistry.fromCatalog())
  ```

### Remediation 4: Thread-Safe Non-Suspend Config Store
- **Source File**: `app/src/main/java/com/anilili/data/provider/remote/config/RemoteProviderConfigStore.kt`
- **Issue**: Coroutine suspension in UI and catalog queries caused context crashes when resolving providers.
- **Solution**: Refactored `getCurrentConfig()`, `getLastKnownGood()`, and `hasConfig()` to directly read internal `AtomicReference<RemoteProviderConfig?>`, providing zero-latency, thread-safe, non-suspend access.

### Remediation 5: Canonical SecurityConfig Definition
- **Source File**: `app/src/main/java/com/anilili/data/provider/remote/engine/security/RemoteRequestPolicy.kt`
- **Issue**: Duplicate `SecurityConfig` declarations in companion objects caused conflicting types across config and runtime packages.
- **Solution**: Promoted `@Serializable data class SecurityConfig` to a top-level declaration in `RemoteRequestPolicy.kt`, unifying all consumers.

---

## 3. Residual Verification
- All remediated components pass automated testing.
- No regression detected across built-in provider catalogs, fallback chains, or settings screens.
