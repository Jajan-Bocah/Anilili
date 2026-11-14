# P10: Generator Contract Sync Report (P9.1–P9.6)

**Date:** 2026-11-14
**Generator Baseline:** P8 (commit before P9 changes)
**Android Target:** `feature/hybrid-remote-provider-engine` at `b274123`
**Kotlin Contract:** `requiresWebView=true` = iframe/embed → WebView; HLS/DASH/MP4 → native player

---

## Summary

Generator output (`provider.json`) is now synced with Android P9.1–P9.6 contracts. Key capabilities (`supportsSearch`, `requiresWebView`) are inferred from workflows with manual overrides respected. Security controls (SSRF protection, `allowedDomains` conservative) maintained.

---

## Changes Made

### 1. `src/lib/generator/providerGenerator.js` — `inferCapabilities()`

**Added `requiresWebView` inference from sources workflow:**

```javascript
// requiresWebView: infer ketika sources workflow menggunakan iframe (stream-iframe rule aktif)
// dan iframe src BUKAN direct media (m3u8/mpd/mp4/webm).
// Bukti: workflow sources dipilih dari rule 'stream-iframe' (bukan 'stream-source').
if (workflows && workflows.sources && Array.isArray(workflows.sources.steps) && workflows.sources.steps.length > 0) {
  const streamsStep = workflows.sources.steps.find(
    (s) => s.op === 'select' && workflows.sources.output?.mapping?.streams === s.saveAs
  )
  if (streamsStep) {
    const selector = streamsStep.params?.selector || ''
    const sampleUrl = streamsStep.params?.sample?.[0] || ''
    const isIframeSelector = /iframe/i.test(selector)
    const isDirectMedia = /\.(m3u8|mpd|mp4|webm)(\?|#|$)/i.test(sampleUrl)
    if (isIframeSelector && !isDirectMedia) {
      inferred.requiresWebView = true
    }
  }
}
```

**Logic:**
- `supportsSearch=true` when search workflow has ≥1 step
- `requiresWebView=true` when sources workflow uses `stream-iframe` rule (iframe selector, not direct media)
- User overrides always win (including explicit `false`)
- URL substring "embed" is unreliable — explicit type or capability flag takes precedence

### 2. `src/lib/generator/workflowInference.js` — `buildInferredWorkflow('sources')`

Sources workflow prioritizes `stream-iframe` over `stream-source` when both detected, ensuring iframe-based sources are correctly mapped.

---

## Anichin Provider.json Output (Generated)

**SHA-256:** `77b3d56c469722e2e27d4f9ea2adae4919c95eb58ace34eae1d9a2d1d5076add`

```json
{
  "schemaVersion": 1,
  "providers": [{
    "id": "anichin",
    "name": "Anichin",
    "endpoint": "https://anichin.biz",
    "source": "generic_html",
    "capabilities": {
      "supportsEpisodes": true,
      "supportsSources": true,
      "supportsSubtitles": false,
      "supportsSkipTimes": false,
      "supportsDownload": false,
      "supportsSearch": true,
      "requiresWebView": true,
      "multiAudio": false,
      "shortLivedStreamUrls": false,
      "countryLocked": null,
      "adultOnly": false
    },
    ...
  }],
  "workflows": {
    "search": { ... },
    "episodes": { ... },
    "sources": {
      "steps": [{
        "op": "select",
        "params": {
          "selector": "div#pembed > iframe",
          "attribute": "src",
          "mode": "ALL"
        }
      }],
      "output": { "type": "SOURCES", "mapping": { "streams": "sources_streams" } }
    }
  },
  "security": {
    "allowedDomains": ["anichin.biz"],
    ...
  }
}
```

**Verification:**
| Metric | Value | Expected |
|--------|-------|----------|
| `supportsSearch` | `true` | ✅ |
| `requiresWebView` | `true` | ✅ |
| Search items | 2 | ✅ |
| Episodes | 166 | ✅ |
| Sources | iframe `https://ok.ru/videoembed/...` | ✅ |
| `allowedDomains` | `["anichin.biz"]` only | ✅ |

---

## Regression Tests Added

**File:** `tests/unit/capabilityInference.test.js`

| Test | Purpose |
|------|---------|
| `manual override requiresWebView=false menang atas inferensi iframe` | Explicit override wins |
| `iframe selector (stream-iframe) → requiresWebView=true` | Iframe detection works |
| `MP4 direct media selector (stream-source) → requiresWebView=false` | MP4 → native player |
| `DASH (.mpd) direct media → requiresWebView=false` | DASH → native player |
| `HLS (.m3u8) direct media → requiresWebView=false` | HLS → native player |
| `webm direct media → requiresWebView=false` | WebM → native player |
| `mixed sources: iframe + direct media → requiresWebView=true` | Iframe dominates |
| `override eksplisit requiresWebView=true tanpa workflow sources` | Override without workflow |
| `allowedDomains hanya dari endpoint, bukan domain iframe` | ok.ru NOT in allowedDomains |
| `SSRF protection: loopback endpoint ditolak` | localhost blocked |
| `SSRF protection: private IP endpoint ditolak tanpa trust` | 192.168.x blocked |

---

## Test Results

```
Test Files: 22 passed (22)
Tests:      370 passed (370)  ← was 356, +14 regression tests
Duration:   ~12.8s
```

---

## Security Controls Verified

1. **SSRF Protection** — Loopback and private IP endpoints rejected unless explicitly trusted
2. **`allowedDomains` Conservative** — Only endpoint hostname; embed domains (ok.ru) never auto-added
3. **Kotlin Strict Decode** — Unknown capability keys rejected by `validateProviderJson`
4. **User Overrides Respected** — Explicit `false` wins over inference

---

## Contract Compliance (P9.1–P9.6)

| Contract | Status | Notes |
|----------|--------|-------|
| P9.1 `supportsSearch` inference | ✅ | ≥1 search step → true |
| P9.2 `requiresWebView` inference | ✅ | iframe selector + non-direct-media URL |
| P9.3 Manual override priority | ✅ | Explicit false/true always wins |
| P9.4 `allowedDomains` conservative | ✅ | Endpoint only, no embed domains |
| P9.5 Mixed sources handling | ✅ | Iframe dominates, no misclassification |
| P9.6 SSRF protection maintained | ✅ | Loopback/private IP blocked |

---

## Files Modified

| File | Change |
|------|--------|
| `src/lib/generator/providerGenerator.js` | Added `requiresWebView` inference in `inferCapabilities()` |
| `tests/unit/capabilityInference.test.js` | Added 14 regression tests |
| `docs/P10_GENERATOR_CONTRACT_SYNC_REPORT.md` | This report |

---

## Next Steps

1. **Android Integration Test** — Verify generated `provider.json` loads and plays on `feature/hybrid-remote-provider-engine` branch
2. **WebView Playback** — Confirm iframe sources (`ok.ru`) play in WebView, HLS/DASH/MP4 play natively
3. **Monitor** — Watch for any edge cases in production providers

---

**Signed off:** Generator contract sync complete. Ready for Android integration testing.