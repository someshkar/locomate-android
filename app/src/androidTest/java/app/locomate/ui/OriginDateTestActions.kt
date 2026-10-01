package app.locomate.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/** Operates the actual native calendar dialog, including its optional keyboard entry mode. */
internal fun openOriginDateInput(compose: ComposeContentTestRule, beforeInput: () -> Unit = {}): SemanticsNodeInteraction {
    compose.onNodeWithContentDescription("Choose origin date").performScrollTo().performClick()
    beforeInput()
    compose.onNodeWithText("Enter date").performScrollTo().performClick()
    return compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))
}

internal fun chooseOriginDate(compose: ComposeContentTestRule, value: String) {
    openOriginDateInput(compose).performTextReplacement(value)
    compose.onNodeWithText("Use date").assertIsEnabled().performClick()
    compose.onNodeWithContentDescription("Choose origin date")
        .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, value))
}
