package app.locomate.ui

import android.net.Uri
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.CommunityPreferences
import app.locomate.data.CurrentJourneyStore
import app.locomate.data.JourneyAlertLink
import app.locomate.data.JourneyAlertStore
import app.locomate.data.JourneyStatusNotification
import app.locomate.data.grantNotificationPermissionIfNeeded
import app.locomate.data.RailGateway
import app.locomate.ui.theme.LocomateTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Root and cache loader against a loopback contract fixture. */
@RunWith(AndroidJUnit4::class)
class JourneyRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun configureWindow() = configureEdgeToEdgeTestWindow(compose.activity)
    private var launchRevision by mutableIntStateOf(0)

    @Test fun betweenStationsUsesBoardingDateToOpenTheDerivedOriginRun() {
        RecoveryGateway(stationFeatures = true).use { server ->
            val current = CurrentJourneyStore(compose.activity, server.url)
            current.clear()
            try {
                installRoot(server.url)
                compose.onNodeWithContentDescription("Search trains").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Board at station"))
                compose.onNodeWithContentDescription("Board at station").performClick()
                compose.onNodeWithContentDescription("Choose New Delhi, NDLS").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Leave at station"))
                compose.onNodeWithContentDescription("Leave at station").performClick()
                compose.onNodeWithContentDescription("Choose Mumbai Central, MMCT").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Find trains between stations"))
                compose.onNodeWithContentDescription("Find trains between stations").performClick()
                awaitTrainResult()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Recovery Express"))
                compose.onNodeWithText("RailRadar route timetable").assertIsDisplayed()
                captureRecent("route-between-normal")
                compose.onNodeWithText("train origin", substring = true).performScrollTo().assertIsDisplayed()
                captureRecent("route-origin-date-normal")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Recovery Express"))
                compose.onNodeWithText("Recovery Express").performScrollTo().performClick()
                awaitText("SCHEDULED · NO LIVE ETA")
                val travelDate = RailGateway.indiaToday()
                val originDate = LocalDate.parse(travelDate).minusDays(1).toString()
                assertEquals(JourneyAlertLink("12951", originDate), current.read())
                assertTrue(server.rawPaths.any { it.startsWith("/v1/trains/between?") && it.contains("date=$travelDate") })
                assertTrue(server.paths.contains("/v1/runs/12951/$originDate"))
                compose.onNodeWithContentDescription("Search trains").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Choose origin date"))
                assertEquals(originDate, compose.onNodeWithContentDescription("Choose origin date")
                    .fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                assertNoConsent()
            } finally { current.clear() }
        }
    }

    @Test fun stationShortcutLookupRetryAndDatedRootSelectionUseTheOrdinaryGateway() {
        RecoveryGateway(stationFeatures = true).use { server ->
            val current = CurrentJourneyStore(compose.activity, server.url)
            current.clear()
            try {
                installRoot(server.url)
                compose.onNodeWithContentDescription("Search trains").performClick()
                val shortcut = "Find trains at New Delhi, NDLS"
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(shortcut))
                compose.onNodeWithContentDescription(shortcut).assertIsDisplayed()
                captureRecent("station-shortcuts-normal")
                compose.onNodeWithContentDescription(shortcut).performClick()
                awaitText("Station timetable temporarily unavailable")
                compose.onNodeWithText("Try again").performScrollTo().performClick()
                awaitTrainResult()
                chooseOriginDate(compose, "2019-02-15")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Recovery Express"))
                val row = compose.onNodeWithText("Recovery Express").performScrollTo().assertIsDisplayed()
                assertTrue(row.fetchSemanticsNode().boundsInRoot.bottom <=
                    compose.onNodeWithContentDescription("Search trains").fetchSemanticsNode().boundsInRoot.top)
                compose.onNodeWithText("RailRadar station timetable").assertIsDisplayed()
                captureRecent("station-services-normal")
                row.performClick(); awaitText("SCHEDULED · NO LIVE ETA")
                assertEquals(JourneyAlertLink("12951", "2019-02-15"), current.read())
                assertTrue(server.paths.contains("/v1/runs/12951/2019-02-15"))
                compose.onNodeWithContentDescription("Search trains").performClick()
                val field = compose.onNodeWithText("Train no. or station")
                field.performScrollTo().performTextInput("Delhi"); field.performImeAction()
                awaitText("Retry station search")
                compose.onNodeWithText("Retry station search").performScrollTo().performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(shortcut))
                compose.onNodeWithContentDescription(shortcut).assertIsDisplayed()
                compose.onNodeWithText("No matching trains found.").assertDoesNotExist()
                captureRecent("station-lookup-normal")
                compose.onNodeWithContentDescription(shortcut).performClick()
                awaitTrainResult()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Clear search"))
                compose.onNodeWithContentDescription("Clear search").performScrollTo().performClick()
                field.performScrollTo().performTextReplacement("Obsolete"); field.performImeAction()
                compose.waitUntil(10_000) { server.stationRequested.count == 0L }
                compose.onNodeWithContentDescription("Clear search").performScrollTo().performClick()
                server.stationRelease.countDown()
                assertTrue(server.stationReplySent.await(5, java.util.concurrent.TimeUnit.SECONDS))
                compose.waitForIdle()
                compose.onNodeWithContentDescription("Find trains at Obsolete Station, XYZ").assertDoesNotExist()
                compose.onNodeWithText("Trains at New Delhi").assertDoesNotExist()
                assertNoConsent()
                assertTrue(server.paths.none { it.contains("privacy/consent") || it.contains("journey-alerts") })
            } finally { current.clear() }
        }
    }

    @Test fun recentTrainRestoresAfterRestartAndOpensTheChosenDateWithoutSearchingAgain() {
        RecoveryGateway().use { server ->
            val current = CurrentJourneyStore(compose.activity, server.url)
            val recent = app.locomate.data.RecentTrainStore(compose.activity, server.url)
            current.clear(); recent.clear()
            try {
                installRoot(server.url)
                compose.onNodeWithContentDescription("Search trains").performClick()
                compose.onNodeWithText("Train no. or station").performTextInput("12951")
                chooseOriginDate(compose, "2026-10-01")
                awaitTrainResult()
                compose.onNodeWithText("Recovery Express").performScrollTo().performClick()
                awaitText("SCHEDULED · NO LIVE ETA")
                compose.activityRule.scenario.recreate()
                installRoot(server.url)
                awaitText("SCHEDULED · NO LIVE ETA")
                compose.onNodeWithContentDescription("Search trains").performClick()
                chooseOriginDate(compose, "2019-02-15")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(androidx.compose.ui.test.hasText("Recovery Express"))
                val row = compose.onNodeWithText("Recovery Express").performScrollTo().assertIsDisplayed()
                assertTrue(row.fetchSemanticsNode().boundsInRoot.bottom <=
                    compose.onNodeWithContentDescription("Search trains").fetchSemanticsNode().boundsInRoot.top)
                captureRecent("recent-normal")
                row.performClick()
                awaitText("SCHEDULED · NO LIVE ETA")
                assertEquals(JourneyAlertLink("12951", "2019-02-15"), current.read())
                assertTrue(server.paths.contains("/v1/runs/12951/2019-02-15"))
                assertEquals(1, server.paths.count { it == "/v1/trains/search" })
                assertEquals(listOf("12951"), recent.load().map { it.number })
                assertNoConsent()
                compose.onNodeWithContentDescription("Search trains").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(
                    androidx.compose.ui.test.hasContentDescription("Clear recent trains"))
                compose.onNodeWithContentDescription("Clear recent trains").performClick()
                assertTrue(recent.load().isEmpty())
                compose.onNodeWithText("Recovery Express").assertDoesNotExist()
                captureRecent("recent-cleared-normal")
            } finally { current.clear(); recent.clear() }
        }
    }

    @Test fun selectedDatedRunRestoresAfterActivityRecreationWhenGatewayIsOffline() {
        RecoveryGateway().use { server ->
            val store = CurrentJourneyStore(compose.activity, server.url)
            store.clear()
            try {
                installRoot(server.url)
                compose.onNodeWithContentDescription("Search trains").performClick()
                compose.onNodeWithText("Train no. or station").performTextInput("12951")
                chooseOriginDate(compose, "2026-10-01")
                awaitTrainResult()
                compose.onNodeWithText("Recovery Express").performScrollTo().performClick()
                awaitText("SCHEDULED · NO LIVE ETA")
                assertEquals(JourneyAlertLink("12951", "2026-10-01"), store.read())
                assertTrue(server.paths.contains("/v1/runs/12951/2026-10-01"))
                assertNoConsent()

                server.close()
                compose.activityRule.scenario.recreate()
                installRoot(server.url)
                awaitText("STALE · LAST KNOWN")
                compose.onNodeWithText("12951 · Recovery Express").assertExists()
                compose.onNodeWithText("SEARCH TO START").assertDoesNotExist()
                assertEquals(JourneyAlertLink("12951", "2026-10-01"), CurrentJourneyStore(compose.activity, server.url).read())
                assertNoConsent()
            } finally { store.clear() }
        }
    }

    @Test fun explicitDatedLinkWinsOverSavedReferenceAndIsRetainedForRestart() {
        RecoveryGateway().use { server ->
            val store = CurrentJourneyStore(compose.activity, server.url)
            try {
                store.select(JourneyAlertLink("12951", "2026-10-01"))
                compose.activityRule.scenario.onActivity {
                    it.intent.data = Uri.parse("locomate://journeys/12137?date=2026-10-02")
                }
                installRoot(server.url)
                awaitText("12137 · New Link Express")
                assertEquals(JourneyAlertLink("12137", "2026-10-02"), store.read())
                assertFalse(server.paths.contains("/v1/runs/12951/2026-10-01"))
                compose.runOnIdle { assertNull(compose.activity.intent.data) }
                assertNoConsent()

                compose.onNodeWithContentDescription("Passport").performClick().assertIsSelected()
                compose.runOnIdle {
                    // MainActivity.onNewIntent supplies a new revision even for the exact same URI.
                    compose.activity.intent.data = Uri.parse("locomate://journeys/12137?date=2026-10-02")
                    launchRevision++
                }
                compose.waitUntil(5_000) { compose.runOnIdle { compose.activity.intent.data == null } }
                compose.onNodeWithContentDescription("Journeys").assertIsSelected()
                assertEquals(2, server.paths.count { it == "/v1/runs/12137/2026-10-02" })
                assertNoConsent()
            } finally { store.clear() }
        }
    }

    @Test fun foregroundEndPushImmediatelyTurnsOffTheStatusControl() {
        RecoveryGateway().use { server ->
            val store = CurrentJourneyStore(compose.activity, server.url)
            val card = JourneyStatusNotification(compose.activity)
            val reference = JourneyAlertLink("12951", "2026-10-01")
            grantNotificationPermissionIfNeeded(compose.activity)
            store.clear()
            card.cancel()
            try {
                compose.activityRule.scenario.onActivity { it.intent.data = Uri.parse(reference.url) }
                installRoot(server.url)
                awaitText("SCHEDULED · NO LIVE ETA")
                compose.onNodeWithContentDescription("Turn status card on").performScrollTo().performClick()
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("Turn status card off").fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(reference.runId, card.activeRun()?.runId)
                // Another controller instance represents the Firebase service while the UI stays resumed.
                assertTrue(JourneyStatusNotification(compose.activity).applyPush(mapOf("event" to "end",
                    "runId" to reference.runId, "observedAt" to System.currentTimeMillis().toString())))
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithContentDescription("Turn status card on").fetchSemanticsNodes().isNotEmpty()
                }
                assertNull(card.activeRun())
                assertEquals(1, server.paths.count { it == "/v1/runs/12951/2026-10-01" })
            } finally { card.cancel(); store.clear() }
        }
    }

    @Test fun exploreListOpensStructuredDateAndReopensTheSameCachedRunOffline() {
        RecoveryGateway().use { server ->
            val store = CurrentJourneyStore(compose.activity, server.url)
            store.clear()
            try {
                installRoot(server.url)
                openExploreList()
                val action = "Open journey for train 12137 on 2026-10-02"
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(action))
                compose.onNodeWithContentDescription(action).performClick()
                awaitText("12137 · New Link Express")
                compose.onNodeWithContentDescription("Journeys").assertIsSelected()
                assertEquals(JourneyAlertLink("12137", "2026-10-02"), store.read())
                assertTrue(server.paths.contains("/v1/runs/12137/2026-10-02"))
                assertFalse(server.paths.contains("/v1/runs/12137/2026-10-01"))
                assertFalse(server.paths.any { it.startsWith("/v1/runs/99999") })
                assertNoConsent()

                server.failRuns = true
                openExploreList()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription(action))
                compose.onNodeWithContentDescription(action).performClick()
                awaitText("STALE · LAST KNOWN")
                compose.onNodeWithText("12137 · New Link Express").assertExists()
                assertEquals(JourneyAlertLink("12137", "2026-10-02"), store.read())
                assertNoConsent()
            } finally { store.clear() }
        }
    }

    @Test fun openExploreListRemovesExpiredRowsAndTheirJourneyActions() {
        RecoveryGateway(networkTtlMillis = 8_000).use { server ->
            val store = CurrentJourneyStore(compose.activity, server.url)
            store.clear()
            try {
                installRoot(server.url)
                openExploreList()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(
                    hasContentDescription("Open journey for train 12137 on 2026-10-02"))
                // Gateway freshness uses wall time; LaunchedEffect delays use Compose's test clock.
                // Native-map semantics queries advance that virtual clock much slower than wall time.
                compose.mainClock.autoAdvance = false
                try {
                    compose.waitUntil(15_000) { System.currentTimeMillis() > server.networkTimes.first() + 8_000 }
                    compose.mainClock.advanceTimeBy(8_001)
                } finally { compose.mainClock.autoAdvance = true }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("No current trains in this list.").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithContentDescription("Open journey for train 12137 on 2026-10-02").assertDoesNotExist()
                assertNull(store.read())
                assertFalse(server.paths.any { it.startsWith("/v1/runs/") })
                assertNoConsent()
            } finally { store.clear() }
        }
    }

    private fun openExploreList() {
        compose.onNodeWithContentDescription("Explore").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("2 fresh gateway train markers in this map view. Positions carry their own source and observation time.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Trains in view").performScrollTo().performClick()
    }

    private fun installRoot(url: String) {
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                val gateway = remember { RailGateway(activity, url) }
                LocomateTheme { RootView(launchRevision = launchRevision, railGateway = gateway) }
            }
        }
    }

    private fun captureRecent(name: String) {
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "recent-trains/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun awaitText(text: String) {
        try {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                    runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)) }.isSuccess
            }
        } catch (failure: Throwable) {
            captureRecent("missing-${text.take(12).replace(Regex("[^A-Za-z0-9-]"), "-")}")
            throw failure
        }
    }

    private fun awaitTrainResult() {
        compose.waitUntil(15_000) {
            runCatching {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Recovery Express"))
            }.isSuccess
        }
    }

    private fun assertNoConsent() {
        assertFalse(CommunityPreferences(compose.activity).enabled)
        assertNull(JourneyStatusNotification(compose.activity).activeRun())
        assertTrue(JourneyAlertStore(compose.activity).all().none { it.enabled })
    }
}

private class RecoveryGateway(private val networkTtlMillis: Long = 60_000, private val stationFeatures: Boolean = false) : AutoCloseable {
    @Volatile var failRuns = false
    private var failedStationBoard = false
    private var failedStationLookup = false
    val stationRequested = java.util.concurrent.CountDownLatch(1)
    val stationRelease = java.util.concurrent.CountDownLatch(1)
    val stationReplySent = java.util.concurrent.CountDownLatch(1)
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val paths = CopyOnWriteArrayList<String>()
    val rawPaths = CopyOnWriteArrayList<String>()
    val networkTimes = CopyOnWriteArrayList<Long>()
    // Repeated bounds requests must not silently renew the fixed expiry scenario.
    private val snapshotTime by lazy { Instant.now() }
    private val worker = Thread({
        while (!socket.isClosed) {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 5_000
                    val input = connection.getInputStream().bufferedReader()
                    val rawPath = input.readLine().split(' ')[1]
                    val path = rawPath.substringBefore('?')
                    paths += path
                    rawPaths += rawPath
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val failedRun = failRuns && path.startsWith("/v1/runs/")
                    val failedBoard = stationFeatures && path == "/v1/stations/NDLS/trains" && !failedStationBoard
                    if (failedBoard) failedStationBoard = true
                    val failedLookup = stationFeatures && path == "/v1/stations/search" && !failedStationLookup
                    if (failedLookup) failedStationLookup = true
                    val heldStation = stationFeatures && rawPath.contains("q=Obsolete") && path == "/v1/stations/search"
                    if (heldStation) { stationRequested.countDown(); stationRelease.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                    val payload = when {
                        failedBoard -> """{"error":{"message":"Station timetable temporarily unavailable"}}"""
                        failedLookup -> """{"error":{"message":"Station lookup temporarily unavailable"}}"""
                        path == "/v1/stations/search" -> if (heldStation)
                            """{"stations":[{"code":"XYZ","name":"Obsolete Station","sourceLabel":"Fixture catalogue","sourceUpdatedAt":null}]}"""
                            else """{"stations":[{"code":"NDLS","name":"New Delhi","sourceLabel":"RailRadar station catalogue","sourceUpdatedAt":null}]}"""
                        path == "/v1/stations/NDLS/trains" -> """{"station":{"code":"NDLS","name":"New Delhi","sourceLabel":"RailRadar station timetable","sourceUpdatedAt":null},"truncated":false,"trains":[{"number":"12951","name":"Recovery Express","originCode":"AAA","originName":"Origin","destinationCode":"BBB","destinationName":"Destination","live":false,"sourceLabel":"RailRadar station timetable","distanceKm":0}]}"""
                        path == "/v1/trains/between" -> {
                            val travelDate = rawPath.substringAfter("date=", "2026-10-02").take(10)
                            val originDate = LocalDate.parse(travelDate).minusDays(1)
                            """{"from":{"code":"NDLS","name":"New Delhi","sourceLabel":"RailRadar route timetable","sourceUpdatedAt":null},"to":{"code":"MMCT","name":"Mumbai Central","sourceLabel":"RailRadar route timetable","sourceUpdatedAt":null},"truncated":false,"trains":[{"number":"12951","name":"Recovery Express","originCode":"NDLS","originName":"New Delhi","destinationCode":"MMCT","destinationName":"Mumbai Central","departure":"16:55","arrival":"08:35","distanceKm":1388.4,"sourceLabel":"RailRadar route timetable","live":false,"originDate":"$originDate","boardingDay":2,"arrivalDay":3}]}"""
                        }
                        stationFeatures && path == "/v1/trains/search" -> """{"trains":[]}"""
                        failedRun -> """{"error":{"message":"Fixture run unavailable"}}"""
                        path == "/v1/network/trains" -> network()
                        path == "/v1/auth/device-session" -> """{"accessToken":"fixture-only","expiresIn":3600}"""
                        path == "/v1/trains/search" -> """{"trains":[{"number":"12951","name":"Recovery Express","originCode":"AAA","originName":"Origin","destinationCode":"BBB","destinationName":"Destination","live":false}]}"""
                        path.startsWith("/v1/runs/") -> journey(path.split('/')[3])
                        else -> "{}"
                    }.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 ${if (failedRun || failedBoard || failedLookup) "503 Unavailable" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                    if (heldStation) stationReplySent.countDown()
                }
            } catch (_: SocketException) {
                stationReplySent.countDown()
                if (!socket.isClosed && !stationFeatures) throw IllegalStateException("Fixture socket failed")
            }
        }
    }, "journey-recovery-fixture").apply { isDaemon = true; start() }

    override fun close() { stationRelease.countDown(); socket.close(); worker.join(1_000) }

    private fun network(): String {
        val now = snapshotTime
        networkTimes += now.toEpochMilli()
        fun train(date: String, observed: Instant) = """{
          "runId":"opaque-shared-id","trainNumber":"12137","name":"99999 · Misleading display name",
          "originDate":"$date","coordinate":{"latitude":23.7,"longitude":76.0},
          "observedAt":"$observed","positionKind":"observed","source":"official","delayMinutes":0
        }"""
        return """{"generatedAt":"$now","freshUntil":"${now.plusMillis(networkTtlMillis)}","trains":[
          ${train("2026-10-02", now.minusSeconds(601))},${train("2026-10-01", now)},
          ${train("2026-10-02", now)},${train("2026-10-02", now)},${train("2026-02-30", now)}
        ]}"""
    }

    private fun journey(number: String) = """{
      "journey": {
        "trainNumber":"$number","trainName":"${if (number == "12137") "New Link Express" else "Recovery Express"}",
        "originCode":"AAA","originName":"Origin","destinationCode":"BBB","destinationName":"Destination",
        "departureTime":"12:00","scheduledArrival":"15:00","scheduledDurationMinutes":180,"distanceKm":120,
        "stops":[{"code":"AAA","name":"Origin","scheduledDeparture":"12:00"},{"code":"BBB","name":"Destination","scheduledArrival":"15:00"}],
        "prediction":{"source":"scheduled"},"provenance":{"freshness":"scheduled","providerLabel":"Local recovery fixture"}
      }
    }"""
}
