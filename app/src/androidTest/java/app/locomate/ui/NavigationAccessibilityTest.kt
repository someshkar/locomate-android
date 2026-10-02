package app.locomate.ui

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import app.locomate.ui.theme.LocomateTheme
import org.junit.Rule
import org.junit.Test

class NavigationAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tabsExposeNameActionAndSelection() {
        compose.setContent {
            LocomateTheme {
                CapsuleNavBar(tab = Tab.Journeys, onTab = {}, onSearch = {})
            }
        }

        compose.onNodeWithContentDescription("Journeys").assertIsSelected()
        compose.onNodeWithContentDescription("Explore").assertHasClickAction()
        compose.onNodeWithContentDescription("Passport").assertHasClickAction()
        compose.onNodeWithContentDescription("Search trains").assertHasClickAction()
    }
}
