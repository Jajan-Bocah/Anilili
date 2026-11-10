# Morphe Patch Integration Analysis

## Overview

The Morphe patch (`thegibbonn/morphe-patches-anilili`) fixes a "recycled bitmap crash" on Android 14/15+ during media playback metadata updates and notification creation.

## Root Cause

On some Android 14/15 ROMs (notably crDroid), the framework's `MediaSession.setMetadata()` rescales artwork bitmaps on the main thread. If the notification provider's copy gets recycled during this process, the media session's subsequent rescale throws `IllegalArgumentException: cannot use a recycled source in createBitmap`.

The issue occurs because:
1. Both the platform media session and the media notification share the same `BitmapLoader`
2. Media3's `CacheBitmapLoader` caches one decoded bitmap
3. The framework recycles the notification's copy after scaling it
3. The media session then tries to scale the same (now recycled) bitmap

## Current Implementation (Native Fix)

Anilili already implements a native fix in `ArtworkBitmapLoader.kt` (added in v0.1.84):

### Key Protection Mechanisms

1. **Per-consumer private copies** (`privateCopyOf` method):
   - Each call to `loadBitmap`, `decodeBitmap`, or `loadBitmapFromMetadata` gets its own `ListenableFuture<Bitmap>`
   - The future transforms the source bitmap into a private copy before returning

2. **Pre-scaling to platform cap** (`privateCopy` method):
   - Scales bitmap to `config_mediaMetadataBitmapMaxSize` (default 320dp) before the framework can
   - This prevents the framework's own rescale from running on the main thread

3. **Recycled bitmap detection**:
   - Checks `bitmap.isRecycled` before copying
   - Fails the future gracefully instead of crashing the main thread

4. **Copy above cache**:
   - The copy step sits ABOVE `CacheBitmapLoader`, so the cache keeps the original
   - Each consumer gets a fresh copy of the cached original

### Implementation Location

- `app/src/main/java/com/anilili/playback/ArtworkBitmapLoader.kt`
- Used in `PlaybackService.kt` via `ArtworkBitmapLoader.default(this)`

### Test Coverage

- `ArtworkBitmapLoaderTest.kt` validates:
  - Each consumer gets its own instance (`everyConsumerGetsItsOwnInstanceFromOneDecode`)
  - Recycling one copy leaves the other usable (`recyclingOneConsumersCopyLeavesTheOtherUsable`)
  - Bitmap respects platform dimension cap (`artworkArrivesWithinThePlatformCap`)

## NotificationArtwork Path

The app also has a separate path for reminder/release notifications in `NotificationArtwork.kt`:
- Uses Coil's `imageLoader` directly
- Coil already returns independent bitmap copies
- No shared cache with MediaSession path

## Comparison with Morphe Patch

| Aspect | Morphe Patch | Anilili Native Fix |
|--------|--------------|-------------------|
| Approach | Runtime patching via Xposed/LSPosed | Native implementation in app code |
| Scope | System-wide | App-only |
| Copy location | Above Media3 cache | Above Media3 cache |
| Per-consumer copies | Yes | Yes |
| Pre-scaling | Yes | Yes |
| Recycled detection | Yes | Yes |
| License | GPL-3.0 | Apache-2.0 (app license) |

## Conclusion

**The Morphe patch fix is already implemented natively in Anilili.** The `ArtworkBitmapLoader` class provides equivalent (and arguably better) protection by:

1. Being part of the app's codebase (no external dependency)
2. Having test coverage verifying the fix
3. Being licensed under the app's license (Apache-2.0)
4. Not requiring root/LSPosed

No additional Morphe integration is needed. The recycled bitmap crash path is covered by the existing implementation.

## Additional Safety (Optional)

The `NotificationArtwork.notificationPoster()` function uses Coil which creates independent copies. For extra safety on the notification path, we could add similar recycled bitmap checks, but Coil's architecture already ensures isolation.