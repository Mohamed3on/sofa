import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// Secrets come only from env vars (CI) or ~/.gradle/gradle.properties (local), never a repo file.
fun secret(name: String): String? =
    providers.environmentVariable(name).orElse(providers.gradleProperty(name)).orNull?.takeIf { it.isNotBlank() }

val serverUrl = secret("IPTV_SERVER_URL") ?: ""
val appToken = secret("IPTV_APP_TOKEN") ?: ""
val keystoreFile = secret("IPTV_KEYSTORE_FILE")

// The baseline profile plugin's own build types (generation, benchmarks) talk to a local fixture
// server (baselineprofile/fixture-server.ts) on the emulator's host instead: its feed points the
// provider route back at itself (one local test clip), so an emulator plays without ever taking the
// account's one stream slot, and its events never reach the box's log. -Piptv.profileServer= (empty)
// uses the real server instead.
val profilingBuildTypes = setOf("nonMinifiedRelease", "benchmarkRelease")
val profilingServerUrl = providers.gradleProperty("iptv.profileServer").orElse("http://10.0.2.2:8791").get()

// Same number for a local build and the CI build of one commit; later commits are always higher (KTD11).
// -Piptv.versionCode= overrides it: dev.sh uses that to install over a newer build already on the stick.
val commitCount = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()
val appVersion = providers.gradleProperty("iptv.versionCode").orNull?.toInt()
    ?: (commitCount + providers.gradleProperty("iptv.versionOffset").get().toInt())

android {
    namespace = "com.mohamed3on.iptv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mohamed3on.iptv"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersion
        versionName = "1.$appVersion"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "APP_TOKEN", "\"$appToken\"")
    }

    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = secret("IPTV_KEYSTORE_PASSWORD")
                keyAlias = secret("IPTV_KEY_ALIAS")
                keyPassword = secret("IPTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // R8: Compose runs much slower on the stick's CPU without it. Libraries ship their own keep rules.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// The server's domain can't live in the public repo, so the network security config is
// generated under build/: cleartext allowed (provider streams redirect to http-only nodes)
// except for the server's own host (KTD10).
abstract class NetworkSecurityConfigTask : DefaultTask() {
    @get:Input abstract val serverUrl: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val host = serverUrl.get().takeIf { it.isNotEmpty() }?.let { URI(it).host }
        val serverDomain = host?.let {
            """
            |    <domain-config cleartextTrafficPermitted="false">
            |        <domain includeSubdomains="true">$it</domain>
            |    </domain-config>
            |""".trimMargin()
        } ?: ""
        val file = outputDir.file("xml/network_security_config.xml").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<network-security-config>
            |    <base-config cleartextTrafficPermitted="true" />
            |$serverDomain</network-security-config>
            |""".trimMargin()
        )
    }
}

// Release ships a baseline profile (src/main/generated/baselineProfiles), so the stick runs the list,
// zaps and panels compiled from the first frame after an update. Regenerate: baselineprofile/generate.sh.
baselineProfile {
    mergeIntoMain = true
    automaticGenerationDuringBuild = false
}

androidComponents {
    onVariants { variant ->
        val profiling = variant.buildType in profilingBuildTypes && profilingServerUrl.isNotEmpty()
        if (profiling) variant.buildConfigFields?.put("SERVER_URL", com.android.build.api.variant.BuildConfigField("String", "\"$profilingServerUrl\"", null))
        // The fixture server is plain http on a local address: no https-only rule for it.
        val url = if (profiling) "" else serverUrl
        val task = tasks.register<NetworkSecurityConfigTask>("generate${variant.name.replaceFirstChar(Char::uppercase)}NetworkSecurityConfig") {
            serverUrl.set(url)
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, NetworkSecurityConfigTask::outputDir)
    }
}

// Checked only when a release APK is packaged, so unit tests can run with no secrets (KTD14).
tasks.matching { it.name == "packageRelease" }.configureEach {
    val url = serverUrl
    doFirst {
        if (!url.startsWith("https://") || URI(url).host.isNullOrEmpty()) {
            throw GradleException("IPTV_SERVER_URL must be an https:// URL (set it as an env var or in ~/.gradle/gradle.properties)")
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}
