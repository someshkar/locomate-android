# Locomate for Android

Native Kotlin and Jetpack Compose railway journey app. MapLibre renders an interactive dark rail map with a source-labeled train marker and route geometry above the draggable journey sheet. Explore groups dense train markers by zoom while preserving the count and source of individual services. The primary surfaces are Journeys, Search, Explore, Passport, and Passport settings, with floating navigation.

The Doop design's Android typography uses bundled Space Grotesk for the interface and IBM Plex Mono for times and source details. Their SIL Open Font License texts are in [`licenses/`](licenses/).

The [Doop canvas](https://doop.design/c/ha6YK6QvsY) is the visual reference. Its dark palette uses `#060708` ground, `#009DFA` signal blue, and `#5FAEF5` route blue. The Journey sheet has 28dp top corners; Android uses a stronger dark scrim because the MapLibre view behind it is not blurred. The current map uses dark vector tiles, while the Journey frame depicts satellite imagery.

## Build and test

Requirements: JDK 17, Android SDK 36, and an Android 12 (API 31) or newer device or emulator. The Gradle wrapper is included.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app was exercised on an Android 36 emulator: route selection changes the native map and Passport survives restart. On 2026-10-01, the fixed SmartRail gateway, run locally against the public RailRadar feed, supplied search, a 54-stop 12137 dated run, and clustered network markers; a saved run reopened after restart. A contract-shaped local gateway also exercised cached-run fallback.

Instrumented accessibility checks cover tab names/actions/selection, Journey expansion without dragging, boarding/alighting selection, persistent search labels, specific saved-run removal names, Settings toggles, and alert controls. `CoreAccessibilityTest` uses Compose's Accessibility Test Framework without suppressions and a test-only system font-scale rule for actual 200% text, including separate Android Dialog windows. The class rule saves the emulator setting before Activity launch and restores it after all test Activities close, and each checked text layout must report the 2.0 scale. It verifies painted text bounds and ellipsis, scrolls to lower actions, and checks the text equivalent of network markers. Run `./gradlew :app:connectedDebugAndroidTest` for the full device suite, or add `-Pandroid.testInstrumentationRunnerArguments.class=app.locomate.ui.CoreAccessibilityTest` for the accessibility gate. CI compiles the test APK.

On 2026-10-01, **11 accessibility tests, 13 existing instrumented behavior tests, and 28 unit tests passed** across the full and focused review runs on the Android 36 emulator. The final integration test uses the production navigation scaffold, checks that map attribution stays above the measured dock at 200% text, and runs the same unsuppressed accessibility and text-layout checks. Screenshots of the Journey, Explore, and alert controls were also inspected.

The network map has a scrollable “Trains in view” list with dated runs, source/observation time, delay, and coordinates. Journey station rows group their source and time for assistive reading. A reachable 48dp “Map attribution” button opens MapLibre's actual source credits; it replaces the SDK's small icon hidden beneath the sheet. Physical TalkBack reading order, focus during live map updates, map-credit link navigation, and switch/keyboard access still require a device review; automated semantics and emulator checks do not establish those passes.

To repeat the current-feed check, start the SmartRail gateway locally after setting up its development secrets and D1 migrations. Then connect the emulator and build a loopback Debug app:

```sh
adb reverse tcp:8787 tcp:8787
./gradlew :app:assembleDebug -PLOCOMATE_RAIL_API_URL=http://127.0.0.1:8787
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the unit tests and APK build for each pull request. The Android adaptive icon and themed monochrome icon use the same route-shaped L as iOS.

The separate Macrobenchmark module measures ten cold launches and ten Search-sheet open/close interactions over the map. It builds a locally signed, non-debuggable app variant and captures startup and frame traces. Run it on an Android 12 or newer **physical device** with a stable refresh rate:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest
```

Results and Perfetto traces are copied under `macrobenchmark/build/outputs/connected_android_test_additional_output/`. CI compiles the benchmark but does not treat an emulator run as a performance pass. An emulator can validate the interaction path with `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`; its timing cannot establish the 400ms cold-start or 120Hz targets.

For live debug profiling on a device, enable JankStats before launching the Debug app:

```sh
adb shell setprop log.tag.LocomateJank DEBUG
adb logcat -s LocomateJank:D
```

Only janky frames are logged, with the current screen name. The listener is disabled in Release and Benchmark builds and while the app is paused.

## Rail data modes

Debug builds open in an explicit historical route-preview mode unless a gateway URL is supplied. Preview packs never claim a live position or ETA. Release builds target the deployed SmartRail rail gateway by default. To override either build type, use a Gradle property:

```sh
./gradlew :app:assembleDebug -PLOCOMATE_RAIL_API_URL=https://your-gateway.example
```

The client obtains a short-lived device session from the gateway and sends no provider credentials. Production failures never activate the preview catalogue. A previously fetched run can be shown from private local cache, marked **STALE · LAST KNOWN** with the last forecast identified as such. Search and Explore show gateway errors when no valid data is available. Debug builds can use an HTTP loopback gateway through `adb reverse`; release builds accept HTTPS only.

Passport stores a local summary of a saved run, without PNR, seat, or personal location. Cloud backup and device transfer exclude app data, including Passport, plans, and the installation session. Historical previews are listed separately and excluded from saved-run distance. Missing route distance remains unavailable rather than being reported as zero.

Settings offers **Export my data** and **Delete my data**. Export combines the current gateway installation record with all local `locomate.*` preferences (including alert choices, pending mutations and deduplication IDs), private cached runs, and community contribution files across gateway scopes, then shares the JSON through a narrow FileProvider grant. Deletion stops contribution and pauses both push consumers before requesting gateway erasure, then cancels notifications, removes the Firebase installation when configured, clears local storage, and rotates the app's installation identity. A durable deletion latch remains through gateway failures or partial device cleanup; it blocks new ordinary requests and device sessions, collection, status cards, and alert registration until explicit deletion retry completes. Settings explains how to retry. Both actions are installation scoped; the app cannot access records created on another device.

Cached runs, Passport entries, and personal stop choices are scoped to the configured gateway origin. Debug loopback fixtures cannot be reopened as production data. A gateway failure only uses a cached run from that same origin.

The journey timeline displays scheduled, observed, predicted, and stale stop times with explicit source labels. Available stop forecasts show their P10/P50/P90 band and fallback reason. A train position marker requires a recent observation from an observed source; timetable progress and predicted positions cannot become green live markers. Map annotations are updated only when the route, network snapshot, or zoom changes.

Boarding and alighting stops can be selected for each preview route or dated run. The selection is private to the device and is restored from a saved Passport segment. Passport uses known station distance for a partial journey and leaves unknown distance or duration unavailable. Native haptics mark tab and search actions, Passport changes, plan saves, and sheet release.

Dated journeys can be shared through Android's native share sheet or inserted into a calendar with scheduled departure and arrival times. Preview journeys can be shared with their preview label but cannot be added to a calendar.

The opt-in **Status card** posts a quiet Android notification for one dated production run. Android asks for notification permission when the traveller taps it. The card refreshes when the app fetches new run data, opens the run after an app restart, and stops when the traveller turns it off, changes runs, or the run becomes stale. It expires after ten minutes without a refresh. Its saved run reference is scoped to the gateway origin and stays on this device. Scheduled arrivals are labeled as scheduled; the card does not claim a live ETA when none exists.

**Journey alerts** are a separate explicit opt-in for station transitions, delay changes of at least five minutes, platform changes, departure, and arrival. A journey's alert dialog lets the traveller choose these events and daily quiet hours in India time; events during quiet hours are suppressed. Android asks for notification permission after the app's consent explanation. The ordinary `journey-alerts` notification channel opens the exact train and service date on tap, including a cold start. Preview journeys cannot enable alerts, and a build without Firebase configuration says push delivery is unavailable.

Alert choices and pending enables, edits, token changes, and removals are stored atomically per gateway origin. WorkManager retries transient errors; app launch also recovers pending work. Monotonic revisions and server tombstones prevent a late enable from undoing withdrawal. Alerts expire at the earlier of scheduled arrival plus 24 hours or five days after enabling. Each incoming data payload must match the local enabled run, revision, event kind, train, date, strict deep link, and ten-minute freshness window; duplicate event IDs never ring twice. Removing notification permission or disabling the alert channel turns subscriptions off locally and queues gateway removal; granting permission again does not silently re-enable them. Settings lists active subscriptions and pending removals, and can stop or retry them. Corrupt saved choices are preserved and surfaced as an error.

Community location contribution is separately opt-in. Settings records versioned consent with the gateway before enabling it, then Android asks for precise location. A location foreground service runs only for a current production journey, shows an ongoing notification, rejects mock, stale, inaccurate, stationary, implausible, and off-route fixes, and uploads compact, idempotent batches using the gateway's delta tuple contract. While active, it retries queued observations every 30 seconds even without another GPS fix; retries remain serial and discard observations older than nine minutes. The service stops when the app backgrounds unless the traveller enables the separate background option. Revocation stops collection immediately, clears pending observations, and stores a withdrawal for gateway retry. Consent, observations, and withdrawals are scoped to the gateway origin. Android service behavior and revocation still need physical-device verification.

With Firebase configured, a native `FirebaseMessagingService` can refresh or end the card and deliver ordinary journey alerts while the app is backgrounded. FCM registration starts only for an explicitly enabled consumer; Analytics collection stays disabled. The client registers its Firebase Installation ID with the gateway. Turning off the Status card keeps FCM registration when journey alerts are still enabled, and vice versa. A registered Android app with package `app.locomate` is required. Supply these client values from that app's `google-services.json` as Gradle properties: `LOCOMATE_FCM_PROJECT_ID` (`project_info.project_id`), `LOCOMATE_FCM_APP_ID` (`client_info.mobilesdk_app_id`), `LOCOMATE_FCM_API_KEY` (`api_key.current_key`), and `LOCOMATE_FCM_SENDER_ID` (`project_info.project_number`). The build remains usable without them, with foreground-only card refresh. The service-account private key belongs only in the gateway Worker secret. Gateway delivery flags are disabled by default; real FCM delivery, token rotation, background display, and cancellation still need a Google Play services device test. Local notification instrumentation does not establish successful FCM delivery.

This repository is still under active implementation. Physical background contribution, TalkBack and keyboard review, and physical-device performance profiling remain to be verified.
