import com.android.build.OutputFile
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.baselineprofile)
}

val diagnosticBuildSha = providers.environmentVariable("GITHUB_SHA").orNull
    ?.take(12)
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-parse", "--short=12", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown").ifBlank { "unknown" }
val appVersionCode = 96
val appVersionName = "0.1.84"
val openSubtitlesApiKey = providers.gradleProperty("openSubtitlesApiKey").orNull
    ?: providers.environmentVariable("OPEN_SUBTITLES_API_KEY").orNull
    ?: ""
val tmdbReadToken = providers.gradleProperty("tmdbReadToken").orNull
    ?: providers.environmentVariable("TMDB_READ_TOKEN").orNull
    ?: ""

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.anilili"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.miruronative"
        // Fire OS 5 devices (including the 1st/2nd-gen Fire TV Sticks) report API 22.
        minSdk = 22
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$diagnosticBuildSha\"")
        // Register a free API consumer at opensubtitles.com and provide its key through the
        // private Gradle user properties or OPEN_SUBTITLES_API_KEY. Never put it in this repo.
        buildConfigField("String", "OPEN_SUBTITLES_API_KEY", "\"$openSubtitlesApiKey\"")
        // TMDB v4 read token, for the episode stills the Konoha CDN has not caught up with.
        // Same rule as above: private Gradle user properties or TMDB_READ_TOKEN, never this repo.
        // Absent, the app simply keeps whatever episode art Konoha supplied.
        buildConfigField("String", "TMDB_READ_TOKEN", "\"$tmdbReadToken\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            // Deliberate and permanent, despite having been labelled TEMP since v0.1.19.
            //
            // Debug builds carry the debug signing key and release builds the real one, and
            // Android will not replace an installed app with one signed by a different key. Were
            // both to share com.miruronative, putting a test build on a device that already has
            // the release would mean uninstalling it first — taking the LibraryStore history,
            // watchlist, downloads and saved tokens with it.
            //
            // So debug installs as its own app instead. The cost is that it starts empty: an
            // install of this has no history and no watchlist, which reads as a bug in whatever
            // feature is being tested until you remember it is a different app. Test anything
            // that depends on real library data with a release build.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // One codebase, two shipped apps. The split is a build variant rather than a fork: every
    // defect found so far (the download-scheduler crash, the progress-attribution race, the
    // AniList pacing regression) lived in shared logic and would have had to be fixed twice in
    // two repositories. What genuinely differs is what each form factor should even contain —
    // Google Cast is meaningless on a TV that *is* the cast target, and on Fire TV its Play
    // Services shell throws on every service start ("No acceptable module ... dynamite").
    //
    // applicationId is deliberately identical across flavors: changing it would orphan every
    // existing install from its updates and its saved library.
    flavorDimensions += "form"
    productFlavors {
        create("mobile") {
            dimension = "form"
            isDefault = true
        }
        create("tv") {
            dimension = "form"
        }
    }

    // libxul.so is 143 MB and would otherwise be STORED uncompressed for mmap. This app is
    // sideloaded and re-downloaded whole on every update, so download size is the cost that
    // matters: compressing it takes an arm64 split from ~212 MB to ~124 MB.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Production code logs through DiagnosticsLog, which reads SystemClock. Without this,
            // any unit test that walks a logging path dies on "not mocked" instead of asserting.
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // Fire TV hardware is ARM. Publish a small APK for each ARM generation and retain a
    // universal APK for users who do not know which one their device needs.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output.getFilter(OutputFile.ABI)
            val buildTypeSuffix = if (buildType.name == "release") "" else "-${buildType.name}"
            // Updaters in v0.1.32 and earlier install the release's first .apk asset, and
            // GitHub orders assets by name: '.' sorts before '_', so "Anilili.apk" (universal,
            // runs on every ABI) must precede "Anilili_<abi>.apk". Never name splits with '-';
            // '-' sorts before '.' and legacy TVs would fetch an incompatible split.
            //
            // The mobile flavor keeps the historical names exactly, because every install out
            // there is already looking for them. TV assets take an extra "_tv" segment, which
            // sorts after all of the mobile names, so a legacy updater still lands on the
            // universal mobile APK rather than a build meant for a different form factor.
            val formSuffix = if (flavorName == "tv") "_tv" else ""
            output.outputFileName = if (abi == null) {
                "Anilili$buildTypeSuffix$formSuffix.apk"
            } else {
                "Anilili$buildTypeSuffix${formSuffix}_$abi.apk"
            }
        }
    }
}

// Crash reports arrive obfuscated, so every shipped build's mapping has to be kept. With the
// mobile/TV flavors there are two of them per release, and the flavor has to be in the filename
// or the second one silently overwrites the first — a report from a TV would then be deobfuscated
// against the phone build and produce plausible, wrong class names.
listOf("mobile", "tv").forEach { flavor ->
    val variant = "${flavor}Release"
    val archiveTask = tasks.register<Copy>("archive${variant.replaceFirstChar(Char::uppercase)}Mapping") {
        dependsOn("minify${variant.replaceFirstChar(Char::uppercase)}WithR8")
        // Gradle does not treat a rename closure as an input, so a build whose only change was the
        // commit sha found the same mapping.txt, decided it was up to date, and left the archive
        // under the *previous* commit's name. 0.1.57 shipped that way: the APK reported
        // 9aec933dfb93 while its mapping was filed under ec4ddc3acec9, which would have
        // deobfuscated a real crash into plausible, wrong names. Declaring the parts of the
        // filename makes a changed sha rerun the copy.
        inputs.property("buildSha", diagnosticBuildSha)
        inputs.property("versionName", appVersionName)
        inputs.property("versionCode", appVersionCode)
        from(layout.buildDirectory.file("outputs/mapping/$variant/mapping.txt"))
        into(layout.buildDirectory.dir("outputs/mapping-archive"))
        rename(
            "mapping.txt",
            "mapping-$flavor-v$appVersionName-$appVersionCode-$diagnosticBuildSha.txt",
        )
    }
    tasks.matching { it.name == "assemble${variant.replaceFirstChar(Char::uppercase)}" }
        .configureEach { finalizedBy(archiveTask) }
}

// The parity corpus: cases the desktop app (../anilili-desktop) and this one must answer
// identically, kept there as plain JSON. A committed copy lives in the unit-test resources so the
// tests never depend on the sibling checkout; when the sibling is there, every build refreshes the
// copy first, so a case added on the desktop side runs here on the next test run and shows up in
// `git status` to be committed alongside whatever made it pass. The search walks up from the root
// because an agent's git worktree (.claude/worktrees/<name>) is not itself next to the sibling.
val parityCorpus: File? = generateSequence(rootProject.projectDir.parentFile) { it.parentFile }
    .map { File(it, "anilili-desktop/fixtures/parity") }
    .firstOrNull { it.isDirectory }
val syncParityCorpus = tasks.register<Sync>("syncParityCorpus") {
    description = "Refreshes src/test/resources/parity from ../anilili-desktop/fixtures/parity."
    onlyIf("the desktop checkout is next to this one") { parityCorpus != null }
    parityCorpus?.let { from(it) }
    into(layout.projectDirectory.dir("src/test/resources/parity"))
}
// preBuild rather than the test-resource tasks: everything that reads src/test (resource
// processing, the lint models) runs after it, so Gradle never sees a reader racing the write.
tasks.named("preBuild") { dependsOn(syncParityCorpus) }

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.material3)
    implementation(libs.material.color.utilities)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.cronet)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.database)
    // Cast is mobile-only. It also drags in play-services-cast-framework and mediarouter, so
    // scoping it here is what actually makes the TV build smaller, not just tidier.
    "mobileImplementation"(libs.androidx.media3.cast)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.play.services.cronet)
    implementation(libs.androidx.appcompat)
    // Pinned, and not by accident. It arrived held back from GeckoView's own requirement when the
    // pipe ran on that engine: 147 asked for lifecycle 2.10 / core 1.17, and that uplift changes
    // Compose's stickyHeader signature out from under IndexedSettingsContent.kt. The engine is
    // gone (see OkHttpPipe) but the Compose problem is not, so raising this still costs that fix.
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.qrcodegen)
    implementation(libs.jsoup)
    implementation(libs.haze)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.profileinstaller)
    // Schnorr (BIP-340) verification for the Nostr update-manifest fallback in UpdateManager.
    implementation(libs.acinq.secp256k1.android)
    baselineProfile(project(":benchmark"))
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.acinq.secp256k1.jvm)
    // A real org.json on the unit-test classpath. The one in android.jar is a stub whose methods
    // return null under `returnDefaultValues`, so anything that builds a JSONObject — the Discord
    // presence payload — could be executed in a test but never asserted against.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
