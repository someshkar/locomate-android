package app.locomate.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.locomate.data.RailGateway
import app.locomate.data.RoutePreview
import app.locomate.ui.theme.LM
import app.locomate.ui.theme.LocomateTheme
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TrainReliabilityCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun historyKeepsOnlyCurrentTrainAndSourceWithTruthfulRetryAndZeroSampleAt200Percent() {
        HistoryGateway(holdFirstTrain = true).use { first ->
            HistoryGateway(malformedFirstResult = true).use { second ->
                var gateway by mutableStateOf(RailGateway(compose.activity, first.url))
                var selectedRoute by mutableStateOf(route("12951"))
                compose.setContent {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                        LocomateTheme {
                            val (state, retry) = rememberTrainReliability(selectedRoute, gateway)
                            Column(Modifier.fillMaxSize().background(LM.Ground).statusBarsPadding()
                                .verticalScroll(rememberScrollState()).padding(24.dp)) {
                                TrainReliabilityCard(state, retry)
                            }
                        }
                    }
                }
                compose.enableAccessibilityChecks()
                compose.waitUntil(5_000) { first.held.count == 0L }
                compose.onNodeWithText("Loading recorded destination arrivals…").assertIsDisplayed()
                // A dated train replacement owns the UI before the noncooperative HTTP reply finishes.
                compose.runOnIdle { selectedRoute = route("12137") }
                awaitText("On time · 70%")
                first.release.countDown()
                compose.waitUntil(5_000) { first.completed.contains("12951") }
                compose.onNodeWithText("On time · 70%").assertIsDisplayed()
                compose.onNodeWithText("On time · 10%").assertDoesNotExist()

                // Same train, different source: clear its old statistic; fractional counts are invalid.
                compose.runOnIdle { gateway = RailGateway(compose.activity, second.url) }
                awaitText("Reliability history is unavailable. Try again.")
                compose.onNodeWithText("On time · 70%").assertDoesNotExist()
                compose.onNodeWithText("Retry history").performScrollTo().assertIsDisplayed()
                    .tryPerformAccessibilityChecks()
                compose.onNodeWithText("Retry history").performClick()
                awaitText("On time · 80%")
                compose.onNodeWithText("On time · 80%").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("10 recorded destination arrivals.").assertExists()
                assertTextFitsAt200Percent()
                screenshot("summary-200")
                val disclosure = "Partial recorded history, not a complete operating history or a prediction for this journey."
                compose.onNodeWithText(disclosure).performScrollTo().assertIsDisplayed()
                    .tryPerformAccessibilityChecks()
                assertTextFitsAt200Percent()
                assertEquals(2, second.count("12137")) // Scrolling never refetches.

                // The deployed service can have history but no classifiable destination arrivals.
                compose.runOnIdle { selectedRoute = route("54321") }
                awaitText("Arrival timing percentages are unavailable for the recorded runs.")
                compose.onNodeWithText("On time · 0%").assertDoesNotExist()
                compose.onNodeWithText("Excluded from percentages: 0 cancelled · 4 unknown.")
                    .performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("Service dates: 2026-08-23 to 2026-09-08.").performScrollTo().assertIsDisplayed()
                assertTextFitsAt200Percent()
                screenshot("unknown-200")
                val beforePreview = second.paths.size
                compose.runOnIdle { selectedRoute = selectedRoute.copy(isPreview = true) }
                compose.onNodeWithText("Reliability history requires a production journey. Preview data is never used to estimate real performance.")
                    .performScrollTo().assertIsDisplayed()
                compose.runOnIdle { assertEquals(beforePreview, second.paths.size) }
                assertTrue((first.paths + second.paths).all {
                    it == "/v1/auth/device-session" || it.matches(Regex("/v1/trains/[0-9]{4,6}/history\\?limit=1"))
                }) // No consent/subscription requests and no history browser fetch.
            }
        }
    }

    private fun awaitText(text: String) = compose.waitUntil(8_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun assertTextFitsAt200Percent() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes().forEach { node ->
                val results = mutableListOf<TextLayoutResult>()
                compose.onNode(SemanticsMatcher("same text node") { it.id == node.id }, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                results.forEach { result ->
                    assertEquals(2f, result.layoutInput.density.fontScale, 0.01f)
                    val width = (0 until result.lineCount).maxOfOrNull { result.getLineRight(it) - result.getLineLeft(it) } ?: 0f
                    assertFalse("Overflow: ${result.layoutInput.text}", result.multiParagraph.didExceedMaxLines ||
                        (0 until result.lineCount).any { result.isLineEllipsized(it) } ||
                        result.multiParagraph.height > result.size.height + 1f || width > result.size.width + 1f)
                }
            }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "reliability/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun route(number: String) = RoutePreview(number, "Test Express", "A", "Origin", "C", "Destination",
        "10:00", "14:00", 1, emptyList(), emptyList(), isPreview = false, runDate = "2026-10-01")
}

private class HistoryGateway(private val holdFirstTrain: Boolean = false,
                             private val malformedFirstResult: Boolean = false) : AutoCloseable {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val paths = CopyOnWriteArrayList<String>()
    val completed = CopyOnWriteArrayList<String>()
    private val counts = ConcurrentHashMap<String, Int>()
    private val connections = CopyOnWriteArrayList<Thread>()
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    private val worker = Thread({
        while (!socket.isClosed) {
            try {
                val connection = socket.accept()
                val thread = Thread({
                    try {
                        connection.use {
                            connection.soTimeout = 5_000
                            val input = connection.getInputStream().bufferedReader()
                            val path = input.readLine().split(' ')[1]
                            paths += path
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: break
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                            }
                            repeat(length) { input.read() }
                            val number = path.substringAfter("/v1/trains/", "").substringBefore('/')
                            val count = if (number.isEmpty()) 0 else counts.merge(number, 1, Int::plus)!!
                            if (holdFirstTrain && number == "12951") { held.countDown(); release.await(10, TimeUnit.SECONDS) }
                            val text = if (path == "/v1/auth/device-session") """{"accessToken":"fixture-only","expiresIn":3600}"""
                                else payload(number, malformedFirstResult && count == 1 && number == "12137")
                            val bytes = text.toByteArray()
                            connection.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                            if (number.isNotEmpty()) completed += number
                        }
                    } catch (_: SocketException) { /* A superseded client can close its response. */ }
                }, "history-response").apply { isDaemon = true }
                connections += thread
                thread.start()
            } catch (_: SocketException) { /* Fixture shutdown. */ }
        }
    }, "history-fixture").apply { isDaemon = true; start() }

    fun count(number: String) = counts[number] ?: 0

    private fun payload(number: String, malformed: Boolean): String {
        val empty = number == "54321"
        val onTime = if (number == "12951") 1 else if (malformedFirstResult) 8 else 7
        val early = 9 - onTime
        val counts = if (empty) """{"early":0,"onTime":0,"late":0,"cancelled":0,"unknown":4,"total":4}"""
            else """{"early":${if (malformed) "1.5" else early},"onTime":$onTime,"late":1,"cancelled":2,"unknown":3,"total":15}"""
        val percentages = if (empty) """{"early":null,"onTime":null,"late":null}"""
            else """{"early":${early * 10},"onTime":${onTime * 10},"late":10}"""
        return """{"trainNumber":"$number","summary":{"counts":$counts,"denominator":${if (empty) 0 else 10},
            "percentages":$percentages,"coverage":{"from":"2026-08-23","to":"2026-09-08"},"lowSample":$empty},
            "policy":{"version":"destination-arrival-delay-v1","earlyBelowMinutes":-5,"onTimeFromMinutes":-5,"onTimeThroughMinutes":5,"lateAboveMinutes":5},
            "generatedAt":"2026-10-01T13:56:39.852Z","provenance":{"source":"canonical-intelligence-tables","comprehensiveCoverage":false,
            "disclosure":"Derived only from recorded runs; coverage is partial."},"runs":[{"delayMinutes":1.5}],"pagination":{"limit":1,"nextCursor":null}}"""
    }

    override fun close() {
        release.countDown(); socket.close(); worker.join(1_000)
        connections.forEach { it.join(1_000) }
    }
}
