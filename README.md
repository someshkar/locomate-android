# Locomate for Android

Native Kotlin and Jetpack Compose railway journey app. MapLibre renders an interactive dark rail map. The four primary surfaces are Journeys, Search, Explore, and Passport, with a draggable journey sheet and floating navigation.

## Build and test

Requirements: JDK 17, Android SDK 36, and an Android 12 (API 31) or newer device or emulator. The Gradle wrapper is included.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app was exercised on an Android 36 emulator: route selection changes the native map, Passport survives restart, and a contract-shaped local gateway was used to test search, dated journeys, network markers, and cached-run fallback.

## Rail data modes

With no gateway URL, the app opens in an explicit historical route-preview mode. Preview packs never claim a live position or ETA. Configure production with a Gradle property:

```sh
./gradlew :app:assembleDebug -PLOCOMATE_RAIL_API_URL=https://your-gateway.example
```

The client obtains a short-lived device session from the gateway and sends no provider credentials. Production failures never activate the preview catalogue. A previously fetched run can be shown from private local cache, marked **STALE · LAST KNOWN** with the last forecast identified as such. Search and Explore show gateway errors when no valid data is available. Debug builds can use an HTTP loopback gateway through `adb reverse`; release builds accept HTTPS only.

Passport stores a local summary of a saved run, without PNR, seat, or personal location. Historical previews are listed separately and excluded from saved-run distance. Missing route distance remains unavailable rather than being reported as zero.

The journey timeline displays scheduled, observed, predicted, and stale stop times with explicit source labels. Available stop forecasts show their P10/P50/P90 band and fallback reason.

This repository is still under active implementation. Android notifications, background contribution, accessibility review, and performance profiling remain to be completed and verified.
