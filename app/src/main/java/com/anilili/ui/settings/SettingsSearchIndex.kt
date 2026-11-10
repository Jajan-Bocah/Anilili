package com.anilili.ui.settings

import com.anilili.BuildConfig

/**
 * One setting as the search field knows it: what it is called, which category holds it, and the
 * words somebody might reach for instead of its name.
 *
 * [keywords] carry the vocabulary the titles do not. Nobody types "Prefer dubbed audio" — they type
 * "dub". Nobody looks for "Blur episode images" by name; they look for "spoilers", which is the
 * thing the setting is actually for.
 */
internal data class SettingsSearchEntry(
    val sectionId: String,
    val sectionTitle: String,
    val title: String,
    val keywords: List<String> = emptyList(),
)

/**
 * Every setting the page can open to, in the order the sections appear on the index.
 *
 * Hand-written rather than derived, because the rows are Compose calls scattered across two
 * thousand lines and there is nothing to enumerate at runtime. The cost is that it can drift from
 * the screen; `SettingsSearchIndexTest` pins the section ids against the real ones so at least a
 * renamed or removed *section* cannot go unnoticed.
 *
 * [isTv] follows the rows: Interface size and Spoken feedback are television-only, because a phone
 * reaches both through Android's own settings, and a television has no bars to keep on screen.
 */
internal fun settingsSearchEntries(isTv: Boolean): List<SettingsSearchEntry> = buildList {
    fun section(id: String, title: String, rows: List<Pair<String, List<String>>>) {
        rows.forEach { (row, keywords) -> add(SettingsSearchEntry(id, title, row, keywords)) }
    }
    section(
        "player", "Player",
        listOfNotNull(
            "Play when an episode opens" to listOf("autoplay", "start", "automatic"),
            "Open episodes in external player" to
                listOf("external", "video", "player", "vlc", "mpv", "automatic"),
            "External player" to
                listOf("external", "video", "player", "app", "open with", "chooser", "default",
                    "vlc", "mpv", "mpvnova", "nova", "mx player", "just player", "vimu"),
            "Autoplay next episode" to listOf("autoplay", "next", "continue", "binge"),
            "Keep playing in a mini player" to
                listOf("mini", "pip", "picture in picture", "floating", "background", "home"),
            "Show details when paused" to listOf("pause", "description", "synopsis"),
            "Auto-skip intro, recap and outro" to listOf("skip", "opening", "ending", "op", "ed"),
            "Skip filler episodes" to listOf("skip", "filler", "canon", "next", "autoplay"),
            "Skip button" to listOf("skip", "button"),
            "Skip button length" to listOf("skip", "seconds", "length", "85"),
            "Watched at" to listOf("progress", "watched", "percent", "complete"),
            "Default quality" to listOf("quality", "resolution", "1080", "720", "480", "data"),
            "Buffer size" to listOf("buffer", "buffering", "stutter", "stall", "freeze", "lag",
                "loading", "cache", "network", "wifi", "memory"),
            "Playback speed" to listOf("speed", "fast", "slow", "2x"),
            "Hold to change speed" to listOf("hold", "long press", "gesture", "toggle"),
            "Hold speed" to listOf("hold", "long press", "fast", "slow", "0.5x", "2x", "4x"),
            "Preferred audio language" to listOf("audio", "language", "dub", "english"),
            "Second choice audio" to listOf("audio", "language", "fallback"),
            "Default screen size" to listOf("screen", "size", "zoom", "crop", "fill", "aspect"),
            "Prefer dubbed audio" to listOf("dub", "dubbed", "sub", "audio"),
            "Brightness and volume gestures" to listOf("gesture", "brightness", "volume", "swipe"),
            "Drag to seek" to listOf("gesture", "seek", "scrub", "drag", "swipe"),
            "Status bar over the player" to listOf("status", "bar", "fullscreen", "notch"),
            "Clock and finishing time" to listOf("clock", "time", "ends", "ending", "finish",
                "bedtime", "night", "late", "watch"),
            ("Press Back twice to leave" to listOf("back", "exit", "leave", "twice", "double",
                "accident", "remote")).takeIf { isTv },
        ),
    )
    section(
        "subtitles", "Subtitles",
        listOf(
            "Show subtitles by default" to listOf("subtitle", "caption", "cc", "default"),
            "Also on dubbed audio" to listOf("subtitle", "dub", "caption"),
            "Preferred subtitle language" to listOf("subtitle", "language", "english"),
            "Second choice subtitles" to listOf("subtitle", "language", "fallback"),
            "Caption appearance" to listOf(
                "caption", "subtitle", "font", "size", "colour", "color", "background", "position",
                "opacity", "transparent", "move", "drag", "place", "margin",
            ),
            "OpenSubtitles" to listOf("opensubtitles", "account", "download", "subtitle"),
            "Subtitle search language" to listOf("opensubtitles", "language", "search"),
        ),
    )
    section(
        "downloads", "Downloads",
        listOf(
            "Download resolution" to listOf("download", "quality", "resolution", "size"),
            "Where downloads go" to listOf("download", "location", "folder", "sd", "card", "storage"),
            "Save location" to listOf("download", "location", "folder", "storage"),
        ),
    )
    section(
        "servers", "Servers",
        listOf(
            "Enabled servers" to listOf(
                "server", "source", "provider", "enable", "disable", "off", "on", "hide", "block",
            ),
            "Server priority" to listOf("server", "source", "provider", "order", "priority"),
            "Preferred server languages" to
                listOf("server", "source", "language", "filter", "tag", "english", "hindi"),
            "Custom sources" to listOf("server", "source", "provider", "custom", "config", "json", "remote", "add", "manage"),
            // Nobody with a blocked source goes looking for "DNS over HTTPS" — they search for
            // what happened to them, which is their ISP blocking something.
            "DNS over HTTPS" to listOf(
                "dns", "doh", "isp", "block", "blocked", "bypass", "resolver", "unblock",
                "cloudflare", "google", "adguard", "quad9", "region", "censor",
            ),
        ),
    )
    section(
        "accounts", "Accounts & sync",
        listOfNotNull(
            ("Manage profiles" to listOf("profile", "profiles", "user", "users", "switch", "who",
                "family", "avatar", "account")).takeIf { isTv },
            ("Profile lock" to listOf("lock", "pin", "code", "password", "profile", "parental", "private"))
                .takeIf { isTv },
            "AniList" to listOf("anilist", "account", "login", "sync"),
            "MyAnimeList" to listOf("mal", "myanimelist", "account", "login", "sync"),
            ("Link a TV" to listOf("tv", "television", "pair", "pairing", "link", "connect", "sign in",
                "login", "android tv", "google tv", "fire tv", "firestick", "device"))
                .takeIf { !isTv },
            "Sync episode progress" to listOf("sync", "progress", "episode", "anilist", "mal"),
            "Sync watchlist with Planning" to listOf("sync", "watchlist", "planning", "list"),
            "Discord" to listOf("discord", "account", "login", "presence", "status", "rich"),
            "Show what you're watching" to
                listOf("discord", "presence", "status", "rich", "profile", "share"),
            "Status while watching" to
                listOf("discord", "status", "online", "idle", "dnd", "do not disturb", "away"),
        ),
    )
    section(
        "notifications", "Notifications",
        listOf(
            "Notification alerts" to listOf("notification", "alert", "release", "airing", "reminder"),
            "Announcements" to
                listOf("announcement", "broadcast", "notice", "developer", "outage", "emergency"),
        ),
    )
    section(
        "content", "Content",
        listOf(
            "Hide adult content" to listOf("adult", "nsfw", "hentai", "mature", "filter"),
            "Only episodes you can watch" to
                listOf(
                    "continue watching", "home", "episode", "airing", "tomorrow", "upcoming",
                    "hide", "schedule", "row", "week", "next episode", "caught up",
                ),
        ),
    )
    section(
        "appearance", "Appearance",
        buildList {
            add(
                "Theme presets" to listOf(
                    "theme", "preset", "look", "crimson", "toxic", "royal", "sakura", "cyber", "ember",
                    "gilded", "forest", "deep sea", "midnight", "high contrast",
                ),
            )
            add("Background" to listOf("theme", "amoled", "black", "oled", "dark", "grey", "gray", "canvas"))
            add(
                "Background image" to listOf(
                    "background", "wallpaper", "image", "picture", "photo", "backdrop", "anime", "custom",
                ),
            )
            add(
                "Accent colour" to listOf(
                    "theme", "color", "colour", "accent", "monochrome", "purple", "green", "neon",
                    "red", "blood", "blue", "cyan", "pink", "orange", "gold", "emerald",
                    "wallpaper", "material you", "dynamic",
                ),
            )
            add("Custom colour" to listOf("custom", "color", "colour", "hex", "hue", "picker", "own", "rgb"))
            add("Colour style" to listOf("theme", "color", "vivid", "tonal", "neutral", "vibrant", "material"))
            add("Contrast" to listOf("theme", "contrast", "readability", "legibility", "low vision"))
            add(
                "Colour anime pages by the show" to listOf(
                    "anime page", "detail", "show", "color", "colour", "accent", "poster", "theme",
                ),
            )
            add("Accent in the player" to listOf("player", "seek", "accent", "color", "colour"))
            // Keyed on the APK, like its row: only the mobile flavor has icon variants.
            if (BuildConfig.FLAVOR == "mobile") {
                add(
                    "Match app icon to theme" to listOf(
                        "icon", "app icon", "launcher", "home screen", "logo", "color", "colour", "accent",
                    ),
                )
            }
            add("Reset theme" to listOf("theme", "reset", "default", "restore"))
            if (isTv) add("Interface size" to listOf("size", "scale", "text", "big", "small", "zoom"))
            if (isTv) add("Overscan" to listOf("overscan", "edges", "cut off", "cropped", "bounds", "margin", "border", "fit"))
            add("Layout" to listOf("layout", "tv", "tablet", "phone", "form factor", "leanback"))
            add(
                "Keep the bars on screen" to listOf(
                    "bar", "chrome", "hide", "scroll", "menu", "top", "nav", "navigation", "fade",
                    "auto hide", "always", "pin",
                ),
            )
            add(
                "Search as you type" to listOf(
                    "search", "suggest", "suggestion", "autocomplete", "auto suggest", "typing",
                    "keyboard", "lag", "slow", "live", "instant", "firestick", "fire tv",
                ),
            )
            if (BuildConfig.FLAVOR == "tv") {
                add(
                    "Video intro" to listOf(
                        "intro", "video", "splash", "launch", "startup", "start up", "opening",
                        "animation", "logo", "sound", "skip",
                    ),
                )
            }
            add(
                "Arrange Home" to listOf(
                    "home", "rows", "row", "order", "reorder", "arrange", "layout", "customize",
                    "customise", "sections", "drag", "move", "hide", "show", "for you", "trending",
                ),
            )
            // "dub" is the one people will actually type: the setting exists because a viewer who
            // does not watch dubs reads that badge on half the catalogue as noise.
            add(
                "Card details" to listOf(
                    "poster", "card", "badge", "label", "tag", "dub", "score", "rating", "star",
                    "title", "name", "caption", "details", "cover", "artwork", "hide", "show",
                    "new", "progress", "18+", "adult", "clean", "minimal",
                ),
            )
            add("Blur episode images" to listOf("blur", "spoiler", "thumbnail", "image"))
            add("Series logos" to listOf("logo", "title", "name", "wordmark", "header", "clearlogo", "text"))
            add(
                "Time format" to
                    listOf("time", "clock", "12", "24", "hour", "am", "pm", "military", "schedule"),
            )
            if (isTv) add("Spoken feedback" to listOf("talkback", "screen reader", "accessibility", "voice"))
        },
    )
    section(
        "data", "Data",
        listOf(
            "Back up to a file" to listOf("backup", "export", "save", "transfer"),
            "Restore from a backup" to listOf("restore", "import", "backup", "transfer"),
            "Export MyAnimeList XML" to listOf("export", "mal", "myanimelist", "xml"),
            "Video cache" to listOf("cache", "storage", "space", "disk", "size", "seek"),
            "Clear cache" to listOf("cache", "clear", "storage", "space"),
            "Re-download metadata" to listOf("metadata", "refresh", "index", "catalogue", "catalog", "update", "data", "stale"),
            "Clear viewing history" to listOf("history", "clear", "watched", "continue"),
        ),
    )
    section(
        "app", "App",
        listOf(
            "Check for updates on launch" to listOf("update", "version", "upgrade"),
            "Send diagnostics" to listOf("diagnostics", "logs", "report", "bug"),
        ),
    )
    section(
        "about", "About",
        listOf(
            "Discord server" to listOf("discord", "support", "community", "help"),
        ),
    )
}

/**
 * The settings a typed query should offer, best first.
 *
 * Ranked rather than merely filtered, because the useful answer to "sub" is the subtitle settings
 * and not the first row that happens to contain those three letters. A title the query starts
 * outranks a title that merely contains it, which outranks a match found only in the keywords or in
 * the category's own name.
 *
 * Matching is per-word so "skip length" finds "Skip button length"; every word has to land
 * somewhere, or a second word could only ever widen the result.
 */
internal fun searchSettings(
    query: String,
    entries: List<SettingsSearchEntry>,
    limit: Int = 8,
): List<SettingsSearchEntry> {
    val words = query.trim().lowercase().split(' ').filter(String::isNotBlank)
    if (words.isEmpty()) return emptyList()
    return entries
        .mapNotNull { entry ->
            val title = entry.title.lowercase()
            val section = entry.sectionTitle.lowercase()
            var best = Int.MAX_VALUE
            for (word in words) {
                val rank = when {
                    title.startsWith(word) -> 0
                    title.split(' ', ',').any { it.startsWith(word) } -> 1
                    title.contains(word) -> 2
                    entry.keywords.any { it.startsWith(word) } -> 3
                    entry.keywords.any { it.contains(word) } -> 4
                    section.contains(word) -> 5
                    else -> return@mapNotNull null
                }
                best = minOf(best, rank)
            }
            entry to best
        }
        .sortedBy { it.second }
        .take(limit)
        .map { it.first }
}
