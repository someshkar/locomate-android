package app.locomate.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assert
import androidx.compose.material3.Text
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.locomate.data.RailGateway
import app.locomate.data.SavedJourney
import app.locomate.ui.theme.LocomateTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletionActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test fun searchPageKeepsNavigationAboveKeyboardAndOpensTheSelectedDate() {
        SearchGateway().use { server ->
            val gateway = RailGateway(compose.activity, server.url)
            var open by mutableStateOf(true)
            var tab by mutableStateOf(Tab.Passport)
            var selected: Pair<String, String>? = null
            val today = java.time.LocalDate.parse(RailGateway.indiaToday())
            val calendarDate = today.withDayOfMonth(if (today.dayOfMonth <= 10) 20 else 1)
            var calendarDayDescription = ""
            compose.setContent { LocomateTheme {
                calendarDayDescription = androidx.compose.material3.DatePickerDefaults.dateFormatter().formatDate(
                    calendarDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
                    compose.activity.resources.configuration.locales[0], forContentDescription = true).orEmpty()
                NavigationScaffold(tab, { tab = it; open = false }, { open = true }, searchActive = open) { inset ->
                    if (open) SearchScreen(emptyList(), gateway, {}, { train, date ->
                        selected = train.number to date; open = false
                    }, bottomInset = inset)
                    else Text("Returned to $tab")
                }
            } }
            compose.onNodeWithContentDescription("Search trains").assertIsDisplayed().assertIsSelected()
            screenshot("search-normal")
            val field = compose.onNodeWithText("Train name or number").performScrollTo()
            field.performClick().performTextInput("12951")
            compose.waitUntil(5_000) { keyboardHeight() > 0 }
            for (label in listOf("Journeys", "Explore", "Passport", "Search trains")) {
                val dock = compose.onNodeWithContentDescription(label).assertIsDisplayed()
                assertTrue("Navigation is covered by keyboard", dock.fetchSemanticsNode().boundsInRoot.bottom
                    <= compose.activity.window.decorView.height - keyboardHeight())
            }
            screenshot("search-keyboard")
            field.performImeAction()
            compose.waitUntil(5_000) { keyboardHeight() == 0 }
            compose.onNodeWithContentDescription("Yest").performScrollTo().performClick().assertIsSelected()
            val priorDate = java.time.LocalDate.parse(RailGateway.indiaToday()).minusDays(1).toString()
            openOriginDateInput(compose).performTextReplacement("2026-02-30")
            compose.onNodeWithText("Use date").assertIsNotEnabled()
            screenshot("search-invalid-calendar")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithContentDescription("Choose origin date").assert(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription, priorDate))
            compose.onNodeWithContentDescription("Today").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithContentDescription("Choose origin date").performScrollTo().performClick()
            assertTrue(calendarDayDescription.isNotBlank())
            // Material exposes a day's full spoken date as Text, rather than ContentDescription.
            compose.onNodeWithText(calendarDayDescription, substring = true).assertIsEnabled().performClick()
            screenshot("search-native-calendar")
            compose.onNodeWithText("Use date").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { keyboardHeight() == 0 }
            awaitText("First Express")
            val result = compose.onNodeWithText("First Express").performScrollTo().assertIsDisplayed()
            assertTrue("Result overlaps navigation", result.fetchSemanticsNode().boundsInRoot.bottom
                < compose.onNodeWithContentDescription("Search trains").fetchSemanticsNode().boundsInRoot.top)
            screenshot("search-result")
            result.performClick()
            compose.runOnIdle {
                assertEquals("12951" to calendarDate.toString(), selected)
                assertEquals(listOf("12951"), server.completedQueries.toList())
            }
            compose.onNodeWithText("Returned to Passport").assertExists()
            compose.onNodeWithContentDescription("Search trains").performClick()
            compose.onNodeWithContentDescription("Explore").performClick().assertIsSelected()
            compose.onNodeWithText("Returned to Explore").assertExists()
            compose.onNodeWithText("Train name or number").assertDoesNotExist()
        }
    }

    private fun keyboardHeight(): Int = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "search-page/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun replacingAndClearingSearchRemovesOldActionsAndCanceledLoading() {
        SearchGateway().use { server ->
            val gateway = RailGateway(compose.activity, server.url)
            var selected: String? = null
            compose.setContent { LocomateTheme {
                SearchScreen(emptyList(), gateway, {}, { train, _ -> selected = train.number })
            } }
            enterQuery("1")
            compose.onNodeWithText("Searching…").assertDoesNotExist()
            compose.onNodeWithText("No matching trains found.").assertDoesNotExist()
            enterQuery("12951")
            awaitText("First Express")
            enterQuery("12137")
            compose.waitUntil(5_000) { server.heldRequest.count == 0L }
            compose.onNodeWithText("Searching…").assertExists()
            compose.onNodeWithText("First Express").assertDoesNotExist()
            compose.runOnIdle { assertNull(selected) }

            enterQuery("")
            compose.onNodeWithText("Searching…").assertDoesNotExist()
            compose.onNodeWithText("First Express").assertDoesNotExist()
            // Finish the canceled request after a third query has taken ownership.
            enterQuery("54321")
            server.releaseRequest.countDown()
            awaitText("Current Express")
            assertTrue(server.completedQueries.contains("12137"))
            compose.onNodeWithText("Held Express").assertDoesNotExist()
            compose.onNodeWithText("First Express").assertDoesNotExist()
            compose.onNodeWithText("Searching…").assertDoesNotExist()
            compose.onNodeWithText("Current Express").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals("54321", selected)
                assertFalse(server.completedQueries.contains("1"))
            }
        }
    }

    @Test fun failedSearchRetriesTheSameQueryAndRetainsCatalogueProvenance() {
        SearchGateway(failFirstSearch = true).use { server ->
            val gateway = RailGateway(compose.activity, server.url)
            var selected: app.locomate.data.TrainSearchResult? = null
            compose.setContent { LocomateTheme {
                SearchScreen(emptyList(), gateway, {}, { train, _ -> selected = train })
            } }
            enterQuery("12951")
            awaitText("The rail feed is temporarily unavailable. Try again shortly.")
            compose.onNodeWithText("First Express").assertDoesNotExist()
            compose.onNodeWithText("Try again").performScrollTo().assertIsEnabled().performClick()
            awaitText("First Express")
            compose.onNodeWithText("Historical railway snapshot").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("1,384 km").assertExists()
            compose.onNodeWithText("Try again").assertDoesNotExist()
            screenshot("search-recovered-source")
            compose.onNodeWithText("First Express").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(listOf("12951", "12951"), server.completedQueries.toList())
                assertEquals("12951", selected?.number)
                assertEquals("Historical railway snapshot", selected?.sourceLabel)
                assertEquals(1384.0, selected?.distanceKm)
            }
        }
    }

    @Test fun invalidSavedRowsExplainWhyTheyCannotOpenButCanStillBeRemoved() {
        val undated = saved("Undated", "12951", null)
        val invalid = saved("Invalid date", "12137", "2026-02-30")
        val valid = saved("Valid run", "54321", "2026-10-01")
        var routes by mutableStateOf(listOf(undated, invalid, valid))
        var opened: SavedJourney? = null
        compose.setContent { LocomateTheme {
            PassportScreen(routes, onOpen = { opened = it }, onSettings = {},
                onRemove = { key -> routes = routes.filterNot { it.key == key } })
        } }
        compose.onNodeWithText("Undated").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Origin date missing. Find this train in Search and choose its origin date.").assertExists()
        compose.onNodeWithContentDescription("Remove 12951, A to C, dated run")
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Undated").assertDoesNotExist()
        compose.onNodeWithText("Invalid date").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Saved run details are invalid. Find this train in Search and choose its origin date.").assertExists()
        compose.onNodeWithContentDescription("Remove 12137, A to C, 2026-02-30")
            .performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertNull(opened) }
        compose.onNodeWithText("Valid run").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(valid, opened) }
    }

    private fun enterQuery(query: String) = compose.onNodeWithText("Train name or number")
        .performScrollTo().performTextReplacement(query)

    private fun awaitText(text: String) = compose.waitUntil(8_000) {
        // Search rows are lazy. Reveal actual list content before testing its native action.
        if (compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)) }
        }
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun saved(name: String, number: String, date: String?) = SavedJourney(name, number, name,
        "A", "Origin", "C", "Destination", date, 0.0, 0, false)
}

/** Delays one actual HTTP result so the Compose cancellation path is deterministic. */
private class SearchGateway(private val failFirstSearch: Boolean = false) : AutoCloseable {
    val heldRequest = CountDownLatch(1)
    val releaseRequest = CountDownLatch(1)
    val completedQueries = CopyOnWriteArrayList<String>()
    private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${socket.localPort}"
    private val worker = Thread({
        while (!socket.isClosed) {
            try {
                socket.accept().use { connection ->
                    connection.soTimeout = 5_000
                    val input = connection.getInputStream().bufferedReader()
                    val target = input.readLine().split(' ')[1]
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    val query = target.substringAfter("?q=", "")
                    val failing = failFirstSearch && target.startsWith("/v1/trains/search") && completedQueries.isEmpty()
                    if (query == "12137") {
                        heldRequest.countDown()
                        releaseRequest.await(10, TimeUnit.SECONDS)
                    }
                    val payload = if (target.startsWith("/v1/auth/device-session"))
                        """{"accessToken":"fixture-only","expiresIn":3600}"""
                    else {
                        completedQueries += query
                        val name = when (query) { "12951" -> "First Express"; "12137" -> "Held Express"; else -> "Current Express" }
                        if (failing) """{"error":{"code":"provider_unavailable","message":"The railway feed is temporarily unavailable."}}"""
                        else """{"trains":[{"number":"$query","name":"$name","originCode":"A","originName":"Origin","destinationCode":"C","destinationName":"Destination","live":false,"sourceLabel":"Historical railway snapshot","distanceKm":1384}]}"""
                    }.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 ${if (failing) "503 Service Unavailable" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(payload)
                        flush()
                    }
                }
            } catch (_: SocketException) {
                // A canceled client or teardown may close its connection before the response.
            }
        }
    }, "search-cancellation-fixture").apply { isDaemon = true; start() }

    override fun close() { releaseRequest.countDown(); socket.close(); worker.join(1_000) }
}
