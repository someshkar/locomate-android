# Locomate for Android

Native Kotlin and Jetpack Compose railway journey app. MapLibre renders an interactive dark rail map with a source-labeled train marker and route geometry above the draggable journey sheet. Explore groups dense train markers by zoom while preserving the count and source of individual services. The primary surfaces are Journeys, Search, Explore, Passport, and Passport settings, with floating navigation.

The Doop design's Android typography uses bundled Space Grotesk for the interface and IBM Plex Mono for times and source details. Their SIL Open Font License texts are in [`licenses/`](licenses/).

## Build and test

Requirements: JDK 17, Android SDK 36, and an Android 12 (API 31) or newer device or emulator. The Gradle wrapper is included.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app was exercised on an Android 36 emulator: route selection changes the native map, Passport survives restart, and a contract-shaped local gateway was used to test search, dated journeys, network markers, and cached-run fallback.

GitHub Actions runs the unit tests and APK build for each pull request. The Android adaptive icon and themed monochrome icon use the same route-shaped L as iOS.

## Rail data modes

With no gateway URL, the app opens in an explicit historical route-preview mode. Preview packs never claim a live position or ETA. Configure production with a Gradle property:

```sh
./gradlew :app:assembleDebug -PLOCOMATE_RAIL_API_URL=https://your-gateway.example
```

The client obtains a short-lived device session from the gateway and sends no provider credentials. Production failures never activate the preview catalogue. A previously fetched run can be shown from private local cache, marked **STALE · LAST KNOWN** with the last forecast identified as such. Search and Explore show gateway errors when no valid data is available. Debug builds can use an HTTP loopback gateway through `adb reverse`; release builds accept HTTPS only.

Passport stores a local summary of a saved run, without PNR, seat, or personal location. Historical previews are listed separately and excluded from saved-run distance. Missing route distance remains unavailable rather than being reported as zero.

Cached runs, Passport entries, and personal stop choices are scoped to the configured gateway origin. Debug loopback fixtures cannot be reopened as production data. A gateway failure only uses a cached run from that same origin.

The journey timeline displays scheduled, observed, predicted, and stale stop times with explicit source labels. Available stop forecasts show their P10/P50/P90 band and fallback reason. A train position marker requires a recent observation from an observed source; timetable progress and predicted positions cannot become green live markers. Map annotations are updated only when the route, network snapshot, or zoom changes.

Boarding and alighting stops can be selected for each preview route or dated run. The selection is private to the device and is restored from a saved Passport segment. Passport uses known station distance for a partial journey and leaves unknown distance or duration unavailable.

Dated journeys can be shared through Android's native share sheet or inserted into a calendar with scheduled departure and arrival times. Preview journeys can be shared with their preview label but cannot be added to a calendar.

This repository is still under active implementation. Android notifications, background contribution, accessibility review, and performance profiling remain to be completed and verified.
