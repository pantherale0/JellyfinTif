import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.FilterConfiguration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.plugin.serialization)
}

val isCI = System.getenv("CI")?.toBoolean() == true
val shouldSign = isCI && System.getenv("KEY_ALIAS") != null
val ffmpegModuleExists = project.file("libs/lib-decoder-ffmpeg-release.aar").exists()

val gitTags =
    providers
        .exec {
            commandLine("git", "tag", "--list", "v*")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .getOrElse("")

val gitDescribe =
    providers
        .exec {
            commandLine("git", "describe", "--tags", "--long", "--match=v*")
            isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().ifBlank { "v0.0.0" } }
        .getOrElse("v0.0.0")

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

configure<ApplicationExtension> {
    namespace = "com.github.pantherale0.jellyfintif"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.github.pantherale0.jellyfintif"
        minSdk = 23
        targetSdk = 36
        versionCode = gitTags.trim().lines().filter { it.isNotBlank() }.size.coerceAtLeast(1)
        versionName = gitDescribe.trim().removePrefix("v").ifBlank { "0.0.0" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    signingConfigs {
        if (shouldSign) {
            create("ci") {
                file("ci.keystore").writeBytes(
                    Base64.getDecoder().decode(System.getenv("SIGNING_KEY")),
                )
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                storePassword = System.getenv("KEY_STORE_PASSWORD")
                storeFile = file("ci.keystore")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            isDebuggable = false
            if (shouldSign) {
                signingConfig = signingConfigs.getByName("ci")
            } else {
                val localPropertiesFile = project.rootProject.file("local.properties")
                if (localPropertiesFile.exists()) {
                    val properties = Properties()
                    properties.load(localPropertiesFile.inputStream())
                    val signingConfigName = properties["release.signing.config"]?.toString()
                    if (signingConfigName != null) {
                        signingConfig = signingConfigs.getByName(signingConfigName)
                    }
                }
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs
            .map { it as com.android.build.api.variant.impl.VariantOutputImpl }
            .forEach { output ->
                val abi =
                    output
                        .getFilter(FilterConfiguration.FilterType.ABI)
                        .let { if (it != null) "-${it.identifier}" else "" }
                output.outputFileName =
                    "JellyfinTif-${variant.buildType}-${output.versionName.get()}-${output.versionCode.get()}$abi.apk"
            }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.tv.material)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)

    implementation(libs.jellyfin.core)
    implementation(libs.jellyfin.api)
    implementation(libs.jellyfin.api.okhttp)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.datasource.okhttp)

    if (ffmpegModuleExists) {
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    }

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
