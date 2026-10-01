# Locomate for Android

Native Kotlin and Jetpack Compose railway journey app. MapLibre renders an interactive dark rail map with a source-labeled train marker and route geometry above the draggable journey sheet. Explore groups dense train markers by zoom while preserving the count and source of individual services. The primary surfaces are Journeys, Search, Explore, Passport, and Passport settings, with floating navigation.

The Doop design's Android typography uses bundled Space Grotesk for the interface and IBM Plex Mono for times and source details. Their SIL Open Font License texts are in [`licenses/`](licenses/).

The [Doop canvas](https://doop.design/c/ha6YK6QvsY) is the visual reference. Its dark palette uses `#060708` ground, `#009DFA` signal blue, and `#5FAEF5` route blue. The Journey sheet has 28dp top corners; Android uses a stronger dark scrim because the MapLibre view behind it is not blurred. The current map uses dark vector tiles, while the Journey frame depicts satellite imagery.

`LOCOMATE_MAP_STYLE_URL` can select an HTTPS MapLibre style from a licensed satellite/hybrid provider for both Journey and Explore. Put this in local Gradle properties or pass `-PLOCOMATE_MAP_STYLE_URL=...` when building. The default is OpenFreeMap's dark vector style; no satellite provider configuration was supplied, so satellite parity is still unverified. Style URLs are bundled in the APK: use only client-safe provider tokens with appropriate restrictions. MapLibre's actual source credits remain available through Map attribution.

## Build and test

Requirements: JDK 17, Android SDK 36, and an Android 12 (API 31) or newer device or emulator. The Gradle wrapper is included.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app was exercised on an Android 36 emulator: route selection changes the native map and Passport survives restart. On 2026-10-01, the fixed SmartRail gateway, run locally against the public RailRadar feed, supplied search, a 54-stop 12137 dated run, and clustered network markers; a saved run reopened after restart. A contract-shaped local gateway also exercised cached-run fallback.

Instrumented accessibility checks cover tab names/actions/selection, Journey expansion without dragging, boarding/alighting selection, persistent search labels, specific saved-run removal names, Settings toggles, and alert controls. `CoreAccessibilityTest` uses Compose's Accessibility Test Framework without suppressions and a test-only system font-scale rule for actual 200% text, including separate Android Dialog windows. The class rule saves the emulator setting before Activity launch and restores it after all test Activities close, and each checked text layout must report the 2.0 scale. It verifies painted text bounds and ellipsis, scrolls to lower actions, and checks the text equivalent of network markers. Run `./gradlew :app:connectedDebugAndroidTest` for the full device suite, or add `-Pandroid.testInstrumentationRunnerArguments.class=app.locomate.ui.CoreAccessibilityTest` for the accessibility gate. CI compiles the test APK.

On 2026-10-01, **11 accessibility tests, 13 existing instrumented behavior tests, and 28 unit tests passed** across the full and focused review runs on the Android 36 emulator. The final integration test uses the production navigation scaffold, checks that map attribution stays above the measured dock at 200% text, and runs the same unsuppressed accessibility and text-layout checks. Screenshots of the Journey, Explore, and alert controls were also inspected.

On 2026-10-01, the recovery/freshness gate passed **33 unit tests and 12 focused instrumented tests**. A subsequent focused run passed **all 3 `JourneyRecoveryTest` cases**, including the new foreground Status card end test; two cases overlap the earlier 12. The dated-link case then passed again after adding same-URL repeat-tap navigation coverage. These checks cover real Search selection followed by Activity recreation with the gateway offline, source-scoped reference export/deletion, explicit dated-link precedence, notification draft validation, card end/expiry/re-enable/tap behavior, and network freshness deadlines. Activity recreation and fresh storage/controller instances were exercised; a physical process-kill/permission-dialog or FCM delivery test is not implied.

The Explore-to-journey gate on 2026-10-01 passed **37 unit tests and 6 distinct focused instrumented cases** across the initial five passing cases and a corrected two-case rerun (one overlap). Coverage includes native selection policy, opaque provider ID reuse, service-date identity, newest eligible duplicate selection, live/cache navigation with no new consent, expiring open-list actions, and the new list action at 200% text. The first expiry test timed out because Compose’s virtual clock advanced only 3 seconds while 18 seconds passed on the device; it now waits for the fixed snapshot’s wall deadline and explicitly advances the Compose scheduler before checking automatic removal. Production expiry logic was unchanged.

The natural timing and Passport filter gate passed **44 unit tests and 3 focused instrumented cases** on 2026-10-01. Coverage includes year-filter restoration and removal, the selected boarding stop's future departure after the origin has departed, preview countdown suppression, and qualified stale delay wording. The two large-text cases use Compose's 200% font-scale override; they do not establish a physical TalkBack pass. Countdown and Passport screenshots use deterministic test fixtures.

The network map has a scrollable “Trains in view” list with dated runs, source/observation time, delay, coordinates, and explicit open-journey actions. A single marker’s native info window also opens its dated journey; cluster info windows inspect their member list. Both paths recheck current membership and freshness at the tap, then use the same live/cache loader and persisted dated selection as Search. General lists follow all current trains; cluster lists retain their selected member identities. Provider IDs are combined with validated train/date fields, and the newest eligible duplicate wins. No display label is parsed as a journey identity, and selection grants no notification or contribution consent. Rail data loading starts from native viewport bounds independently of basemap download success. Journey station rows group their source and time for assistive reading. A reachable 48dp “Map attribution” button opens MapLibre's actual source credits; it replaces the SDK's small icon hidden beneath the sheet. Physical TalkBack reading order, focus during live map updates, map-credit link navigation, and switch/keyboard access still require a device review; automated semantics and emulator checks do not establish those passes.

To repeat the current-feed check, start the SmartRail gateway locally after setting up its development secrets and D1 migrations. Then connect the emulator and build a loopback Debug app:

```sh
adb reverse tcp:8787 tcp:8787
./gradlew :app:assembleDebug -PLOCOMATE_RAIL_API_URL=http://127.0.0.1:8787
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions runs the unit tests and APK build for each pull request. The Android adaptive icon and themed monochrome icon use the same route-shaped L as iOS.

On 2026-10-01, the completion fixes passed all **53 unit tests**, Debug and Release builds, and **four focused emulator tests**: a canceled real HTTP search cannot leave loading or stale result actions behind; undated/invalid Passport entries explain their disabled open action while removal remains usable; a dated run restores from cache after Activity recreation; and platform boxes stay readable at normal and 200% Compose font scale, hide in preview/unknown states, and qualify cached values as last known. Dated sharing includes the origin date and canonical journey link; preview sharing stays explicitly historical and link-free. These focused checks do not establish a full accessibility or physical-device release pass.

The separate Macrobenchmark module measures ten cold launches, ten Search-sheet open/close interactions, and ten Journey-sheet drag/scroll cycles over the map. It builds a locally signed, non-debuggable app variant and captures startup, frame, and (for Journey) peak app-memory metrics. Run it on an Android 12 or newer **physical device** with a stable refresh rate. Supply a real dated run available from the configured HTTPS gateway:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.locomate.journeyUrl=locomate://journeys/TRAIN?date=YYYY-MM-DD'
```

Replace `TRAIN` and `YYYY-MM-DD` with the real train number and origin date. The Journey case requires a loaded run and map style, rejects preview/cached data outside dry-run mode, and measures sheet expansion, content scrolling, and collapse. Initial network work occurs in setup; tile caches are retained between iterations. These measurements describe this workload, not a cold network fetch or total device memory.

Results and Perfetto traces are copied under `macrobenchmark/build/outputs/connected_android_test_additional_output/`. CI compiles the benchmark but does not treat an emulator run as a performance pass. The preview interaction can be checked on an emulator with:

```sh
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -PLOCOMATE_RAIL_API_URL= \
  '-Pandroid.testInstrumentationRunnerArguments.class=app.locomate.macrobenchmark.LocomateBenchmark#journeySheetOverMap' \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

Emulator timing cannot establish the 400ms cold-start or 120Hz targets.

On 2026-10-01, the new Journey drag/scroll/collapse dry run passed on the Android 36 emulator. Its gestures avoid the floating dock, and the test checks changed visible content rather than treating UiAutomator's “can still scroll” result as evidence of movement. This verifies the workload path only; physical frame, startup, and memory baselines remain unmeasured.

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

Passport offers All-Time and train-origin-year filters. The selected period applies to both rows and summary totals. Preview and undated legacy entries appear only in All-Time; previews remain excluded from real-run totals. Removing the final entry in a selected year returns the view to All-Time.

Saved personal segments use their boarding stop's dated scheduled departure and alighting stop's dated scheduled arrival for duration, including journeys across multiple midnights. Missing or invalid segment times remain unavailable; they do not inherit the full run's duration. Scheduled totals retain minutes (for example, `10m` or `1h 30m`) and show `—` when no duration is known. Earlier saved entries with unknown duration remain unknown until saved again with valid timing. All **49 unit tests** and the Debug build passed after this change on 2026-10-01.

Settings offers **Export my data** and **Delete my data**. Export combines the current gateway installation record with all local `locomate.*` preferences (including alert choices, pending mutations and deduplication IDs), private cached runs, and community contribution files across gateway scopes, then shares the JSON through a narrow FileProvider grant. Deletion stops contribution and pauses both push consumers before requesting gateway erasure, then cancels notifications, removes the Firebase installation when configured, clears local storage, and rotates the app's installation identity. A durable deletion latch remains through gateway failures or partial device cleanup; it blocks new ordinary requests and device sessions, collection, status cards, and alert registration until explicit deletion retry completes. Settings explains how to retry. Both actions are installation scoped; the app cannot access records created on another device.

The current dated journey is restored after Activity or process recreation through the same live/cache loader. Only its run reference and gateway scope are persisted; an explicit notification or dated deep link takes precedence. Source changes and data deletion clear that reference. Restoring it does not enable contribution, a Status card, or journey alerts. Permission requests preserve the explicitly selected run and alert choices through saved instance state and validate the source and consent notice before using a returned grant.

Cached runs, Passport entries, and personal stop choices are scoped to the configured gateway origin. Debug loopback fixtures cannot be reopened as production data. A gateway failure only uses a cached run from that same origin.

The journey timeline displays scheduled, observed, predicted, and stale stop times with explicit source labels. Available stop forecasts show their P10/P50/P90 band and fallback reason. A train position marker requires a recent observation from an observed source; timetable progress and predicted positions cannot become green live markers. Explore refreshes the visible bounds every minute while active and discards late completions for superseded bounds. Markers disappear when the response expires or their evidence exceeds ten minutes, even if a refresh fails; malformed coordinates, clocks, or observed provenance are hidden. Snapshot timestamps are parsed once off the UI thread, and an expiry timer updates visibility at the next deadline or resume. Map annotations are updated only when the route, marker content, or zoom changes.

Boarding and alighting stops can be selected for each preview route or dated run. The selection is private to the device and is restored from a saved Passport segment. Passport uses known station distance for a partial journey and leaves unknown distance or duration unavailable. Native haptics mark tab and search actions, Passport changes, plan saves, and sheet release.

A future known departure at the selected boarding stop shows a full-word countdown ending “until scheduled departure”. It follows the active screen's clock, identifies a cached timetable, and disappears for passed/departed stops, unknown departures, and historical previews. Delay text uses “minutes early/late” with observed, predicted, or last-known qualifiers.

Dated journeys can be shared through Android's native share sheet or inserted into a calendar with scheduled departure and arrival times. Preview journeys can be shared with their preview label but cannot be added to a calendar.

The opt-in **Status card** posts a quiet Android notification for one dated production run. Android asks for notification permission when the traveller taps it. The card refreshes when the app fetches new run data, opens the run after an app restart, and stops when the traveller turns it off, changes runs, or the run becomes stale. It expires at most ten minutes after the displayed route was received. Automatic refresh cannot recreate a card ended by a push, dismissal, or expiry; only another explicit Status card action can start it again. A preference invalidation signal updates the foreground control to Off after an end event without waiting for the next journey fetch. Its tap always opens the exact dated run. Its saved run reference is scoped to the gateway origin and stays on this device. Scheduled arrivals are labeled as scheduled; the card does not claim a live ETA when none exists.

**Journey alerts** are a separate explicit opt-in for station transitions, delay changes of at least five minutes, platform changes, departure, and arrival. A journey's alert dialog lets the traveller choose these events and daily quiet hours in India time; events during quiet hours are suppressed. Android asks for notification permission after the app's consent explanation. The ordinary `journey-alerts` notification channel opens the exact train and service date on tap, including a cold start. Preview journeys cannot enable alerts, and a build without Firebase configuration says push delivery is unavailable.

Alert choices and pending enables, edits, token changes, and removals are stored atomically per gateway origin. WorkManager retries transient errors; app launch also recovers pending work. Monotonic revisions and server tombstones prevent a late enable from undoing withdrawal. Alerts expire at the earlier of scheduled arrival plus 24 hours or five days after enabling. Each incoming data payload must match the local enabled run, revision, event kind, train, date, strict deep link, and ten-minute freshness window; duplicate event IDs never ring twice. Removing notification permission or disabling the alert channel turns subscriptions off locally and queues gateway removal; granting permission again does not silently re-enable them. Settings lists active subscriptions and pending removals, and can stop or retry them. Corrupt saved choices are preserved and surfaced as an error.

Community location contribution is separately opt-in. Settings records versioned consent with the gateway before enabling it, then Android asks for precise location. A location foreground service runs only for a current production journey, shows an ongoing notification, rejects mock, stale, inaccurate, stationary, implausible, and off-route fixes, and uploads compact, idempotent batches using the gateway's delta tuple contract. While active, it retries queued observations every 30 seconds even without another GPS fix; retries remain serial and discard observations older than nine minutes. The service stops when the app backgrounds unless the traveller enables the separate background option. Revocation stops collection immediately, clears pending observations, and stores a withdrawal for gateway retry. Consent, observations, and withdrawals are scoped to the gateway origin. Android service behavior and revocation still need physical-device verification.

With Firebase configured, a native `FirebaseMessagingService` can refresh or end the card and deliver ordinary journey alerts while the app is backgrounded. FCM registration starts only for an explicitly enabled consumer; Analytics collection stays disabled. The client registers its Firebase Installation ID with the gateway. Turning off the Status card keeps FCM registration when journey alerts are still enabled, and vice versa. A registered Android app with package `app.locomate` is required. Supply these client values from that app's `google-services.json` as Gradle properties: `LOCOMATE_FCM_PROJECT_ID` (`project_info.project_id`), `LOCOMATE_FCM_APP_ID` (`client_info.mobilesdk_app_id`), `LOCOMATE_FCM_API_KEY` (`api_key.current_key`), and `LOCOMATE_FCM_SENDER_ID` (`project_info.project_number`). The build remains usable without them, with foreground-only card refresh. The service-account private key belongs only in the gateway Worker secret. Gateway delivery flags are disabled by default; real FCM delivery, token rotation, background display, and cancellation still need a Google Play services device test. Local notification instrumentation does not establish successful FCM delivery.

This repository is still under active implementation. Physical background contribution, TalkBack and keyboard review, and physical-device performance profiling remain to be verified.
