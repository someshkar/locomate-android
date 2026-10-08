import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.locomate"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.locomate"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val mapStyleUrl = providers.gradleProperty("LOCOMATE_MAP_STYLE_URL").orNull
            ?: "https://tiles.openfreemap.org/styles/dark"
        val mapStyleUri = URI(mapStyleUrl)
        require(mapStyleUri.scheme == "https" && !mapStyleUri.host.isNullOrBlank() && mapStyleUri.userInfo == null && mapStyleUri.fragment == null) {
            "LOCOMATE_MAP_STYLE_URL must be an HTTPS MapLibre style URL without embedded user credentials."
        }
        val dayStyleUrl = providers.gradleProperty("LOCOMATE_MAP_DAY_STYLE_URL").orNull
            ?: "https://tiles.openfreemap.org/styles/positron"
        val satelliteStyleUrl = providers.gradleProperty("LOCOMATE_MAP_SATELLITE_STYLE_URL").orNull.orEmpty()
        for (style in listOf(dayStyleUrl, satelliteStyleUrl).filter { it.isNotBlank() }) {
            val uri = URI(style)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Map styles must be HTTPS URLs without user credentials or fragments" }
        }
        buildConfigField("String", "MAP_DAY_STYLE_URL", "\"${dayStyleUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "MAP_SATELLITE_STYLE_URL", "\"${satelliteStyleUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "MAP_STYLE_URL", "\"${mapStyleUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${providers.gradleProperty("LOCOMATE_FCM_PROJECT_ID").orNull.orEmpty()}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${providers.gradleProperty("LOCOMATE_FCM_APP_ID").orNull.orEmpty()}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${providers.gradleProperty("LOCOMATE_FCM_API_KEY").orNull.orEmpty()}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${providers.gradleProperty("LOCOMATE_FCM_SENDER_ID").orNull.orEmpty()}\"")
    }

    val uploadStorePath = providers.environmentVariable("LOCOMATE_ANDROID_KEYSTORE_PATH").orNull
    val uploadPasswordPath = providers.environmentVariable("LOCOMATE_ANDROID_KEYSTORE_PASSWORD_FILE").orNull
    val uploadAlias = providers.environmentVariable("LOCOMATE_ANDROID_KEY_ALIAS").orNull
    val uploadKeyPasswordPath = providers.environmentVariable("LOCOMATE_ANDROID_KEY_PASSWORD_FILE").orNull
    val hasUploadSigning = listOf(uploadStorePath, uploadPasswordPath, uploadAlias, uploadKeyPasswordPath).any { it != null }
    if (hasUploadSigning) {
        require(!uploadStorePath.isNullOrBlank() && !uploadPasswordPath.isNullOrBlank() && !uploadAlias.isNullOrBlank()) {
            "Release signing requires the keystore path, password-file path and key alias environment variables"
        }
        signingConfigs.create("upload") {
            storeFile = file(requireNotNull(uploadStorePath))
            storeType = "PKCS12"
            storePassword = file(requireNotNull(uploadPasswordPath)).readText().trimEnd('\r', '\n')
            keyAlias = uploadAlias
            keyPassword = file(uploadKeyPasswordPath ?: requireNotNull(uploadPasswordPath)).readText().trimEnd('\r', '\n')
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("debug") {
            val railApiUrl = providers.gradleProperty("LOCOMATE_RAIL_API_URL").orNull.orEmpty()
            buildConfigField("String", "RAIL_API_URL", "\"$railApiUrl\"")
        }
        release {
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            val railApiUrl = providers.gradleProperty("LOCOMATE_RAIL_API_URL").orNull
                ?: "https://locomate-backend.rail-intelligence-gateway.workers.dev"
            buildConfigField("String", "RAIL_API_URL", "\"$railApiUrl\"")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            if (providers.gradleProperty("LOCOMATE_BENCHMARK_PREVIEW").orNull == "true") {
                buildConfigField("String", "RAIL_API_URL", "\"\"")
            }
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

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.maplibre.gl:android-sdk-opengl:13.5.2")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("androidx.metrics:metrics-performance:1.0.0")
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4-accessibility")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
