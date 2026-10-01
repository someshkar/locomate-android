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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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

    @Test fun searchPageKeepsNavigationAboveKeyboardAndOpensTheSelectedDate() {
        SearchGateway().use { server ->
            val gateway = RailGateway(compose.activity, server.url)
            var open by mutableStateOf(true)
            var tab by mutableStateOf(Tab.Passport)
            var selected: Pair<String, String>? = null
            compose.setContent { LocomateTheme {
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
            val dateField = compose.onNodeWithText("Origin date · India time").performScrollTo()
            dateField.performTextReplacement("2026-09-30")
            dateField.performImeAction()
            compose.waitUntil(5_000) { keyboardHeight() == 0 }
            awaitText("12951 · First Express")
            val result = compose.onNodeWithText("12951 · First Express").performScrollTo().assertIsDisplayed()
            assertTrue("Result overlaps navigation", result.fetchSemanticsNode().boundsInRoot.bottom
                < compose.onNodeWithContentDescription("Search trains").fetchSemanticsNode().boundsInRoot.top)
            screenshot("search-result")
            result.performClick()
            compose.runOnIdle { assertEquals("12951" to "2026-09-30", selected) }
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
            enterQuery("12951")
            awaitText("12951 · First Express")
            enterQuery("12137")
            compose.waitUntil(5_000) { server.heldRequest.count == 0L }
            compose.onNodeWithText("Searching…").assertExists()
            compose.onNodeWithText("12951 · First Express").assertDoesNotExist()
            compose.runOnIdle { assertNull(selected) }

            enterQuery("")
            compose.onNodeWithText("Searching…").assertDoesNotExist()
            compose.onNodeWithText("12951 · First Express").assertDoesNotExist()
            // Finish the canceled request after a third query has taken ownership.
            enterQuery("54321")
            server.releaseRequest.countDown()
            awaitText("54321 · Current Express")
            assertTrue(server.completedQueries.contains("12137"))
            compose.onNodeWithText("12137 · Held Express").assertDoesNotExist()
            compose.onNodeWithText("12951 · First Express").assertDoesNotExist()
            compose.onNodeWithText("Searching…").assertDoesNotExist()
            compose.onNodeWithText("54321 · Current Express").performScrollTo().performClick()
            compose.runOnIdle { assertEquals("54321", selected) }
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
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun saved(name: String, number: String, date: String?) = SavedJourney(name, number, name,
        "A", "Origin", "C", "Destination", date, 0.0, 0, false)
}

/** Delays one actual HTTP result so the Compose cancellation path is deterministic. */
private class SearchGateway : AutoCloseable {
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
                    if (query == "12137") {
                        heldRequest.countDown()
                        releaseRequest.await(10, TimeUnit.SECONDS)
                    }
                    val payload = if (target.startsWith("/v1/auth/device-session"))
                        """{"accessToken":"fixture-only","expiresIn":3600}"""
                    else {
                        completedQueries += query
                        val name = when (query) { "12951" -> "First Express"; "12137" -> "Held Express"; else -> "Current Express" }
                        """{"trains":[{"number":"$query","name":"$name","originCode":"A","originName":"Origin","destinationCode":"C","destinationName":"Destination","live":false}]}"""
                    }.toByteArray()
                    connection.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
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
