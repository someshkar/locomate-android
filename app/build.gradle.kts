plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
    id 'org.jetbrains.kotlin.plugin.compose'
}

android {
    namespace 'app.locomate'
    compileSdk 36

    defaultConfig {
        applicationId "app.locomate"
        minSdk 31
        targetSdk 36
        versionCode 1
        versionName "0.1.0"
    }
    buildTypes {
        release { minifyEnabled false }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
    buildFeatures { compose true }
}

dependencies {
    implementation platform('androidx.compose:compose-bom:2024.12.01')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.activity:activity-compose:1.9.3'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7'
    // Google Maps for Compose
    implementation 'com.google.maps.android:maps-compose:6.4.1'
    implementation 'com.google.android.gms:play-services-maps:19.0.0'
    testImplementation 'junit:junit:4.13.2'
}
