package app.locomate.ui

import android.Manifest
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
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.CommunityPreferences
import app.locomate.data.CurrentJourneyStore
import app.locomate.data.JourneyAlertLink
import app.locomate.data.JourneyAlertStore
import app.locomate.data.JourneyStatusNotification
import app.locomate.data.RailGateway
import app.locomate.ui.theme.LocomateTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Root and cache loader against a loopback contract fixture. */
@RunWith(AndroidJUnit4::class)
class JourneyRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var launchRevision by mutableIntStateOf(0)

    @Test fun recentTrainRestoresAfterRestartAndOpensTheChosenDateWithoutSearchingAgain() {
        RecoveryGateway().use { server ->
            val current = CurrentJourneyStore(compose.activity, server.url)
            val recent = app.locomate.data.RecentTrainStore(compose.activity, server.url)
            current.clear(); recent.clear()
            try {
                installRoot(server.url)
                compose.onNodeWithContentDescription("Search trains").performClick()
                compose.onNodeWithText("Train name or number").performTextInput("12951")
                chooseOriginDate(compose, "2026-10-01")
                awaitText("Recovery Express")
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
                compose.onNodeWithText("Train name or number").performTextInput("12951")
                chooseOriginDate(compose, "2026-10-01")
                awaitText("Recovery Express")
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
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
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
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertNoConsent() {
        assertFalse(CommunityPreferences(compose.activity).enabled)
        assertNull(JourneyStatusNotification(compose.activity).activeRun())
        assertTrue(JourneyAlertStore(compose.activity).all().none { it.enabled })
    }
}

private class RecoveryGateway(private val networkTtlMillis: Long = 60_000) : AutoCloseable {
    @Volatile var failRuns = false
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    val paths = CopyOnWriteArrayList<String>()
    val networkTimes = CopyOnWriteArrayList<Long>()
    // Repeated bounds requests must not silently renew the fixed expiry scenario.
    private val snapshotTime by lazy { Instant.now() }
    private val worker = Thread({
        while (!socket.isClosed) {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 5_000
                    val input = connection.getInputStream().bufferedReader()
                    val path = input.readLine().split(' ')[1].substringBefore('?')
                    paths += path
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val failedRun = failRuns && path.startsWith("/v1/runs/")
                    val payload = when {
                        failedRun -> """{"error":{"message":"Fixture run unavailable"}}"""
                        path == "/v1/network/trains" -> network()
                        path == "/v1/auth/device-session" -> """{"accessToken":"fixture-only","expiresIn":3600}"""
                        path == "/v1/trains/search" -> """{"trains":[{"number":"12951","name":"Recovery Express","originCode":"AAA","originName":"Origin","destinationCode":"BBB","destinationName":"Destination","live":false}]}"""
                        path.startsWith("/v1/runs/") -> journey(path.split('/')[3])
                        else -> "{}"
                    }.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 ${if (failedRun) "503 Unavailable" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                }
            } catch (_: SocketException) {
                if (!socket.isClosed) throw IllegalStateException("Fixture socket failed")
            }
        }
    }, "journey-recovery-fixture").apply { isDaemon = true; start() }

    override fun close() { socket.close(); worker.join(1_000) }

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
