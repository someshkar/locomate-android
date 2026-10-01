import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
    }

    buildTypes {
        getByName("debug") {
            val railApiUrl = providers.gradleProperty("LOCOMATE_RAIL_API_URL").orNull.orEmpty()
            buildConfigField("String", "RAIL_API_URL", "\"$railApiUrl\"")
        }
        release {
            isMinifyEnabled = false
            val railApiUrl = providers.gradleProperty("LOCOMATE_RAIL_API_URL").orNull
                ?: "https://rail-intelligence-gateway.rail-intelligence-gateway.workers.dev"
            buildConfigField("String", "RAIL_API_URL", "\"$railApiUrl\"")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
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
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
