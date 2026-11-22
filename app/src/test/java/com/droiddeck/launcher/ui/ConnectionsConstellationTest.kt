package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class ConnectionsConstellationTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val selected = mutableListOf<String>()

    private fun show(narrow: Boolean = false) {
        selected.clear()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(
                    LocalFrontFocus provides FrontFocus(),
                    LocalNarrowPane provides narrow,
                ) {
                    FocusGlideHost {
                        ConnectionsPage(onSelect = { selected.add(it) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun headerShowsConnectionsTitle() {
        show()
        compose.onNodeWithText("Connections").assertExists()
    }

    @Test fun allNodesExist() {
        show()
        compose.onNodeWithTag("conn:center").assertExists()
        compose.onNodeWithTag("conn:computer").assertExists()
        compose.onNodeWithTag("conn:tv").assertExists()
        compose.onNodeWithTag("conn:second_player").assertExists()
    }

    @Test fun centerNodeIsDecorative() {
        show()
        compose.onNodeWithTag("conn:center").assertExists().assertHasNoClickAction()
    }

    @Test fun nodesHaveClickActions() {
        show()
        compose.onNodeWithTag("conn:computer").assertExists().assertHasClickAction()
        compose.onNodeWithTag("conn:tv").assertExists().assertHasClickAction()
        compose.onNodeWithTag("conn:second_player").assertExists().assertHasClickAction()
    }

    @Test fun nodesRouteToTheirPages() {
        show()
        compose.onNodeWithTag("conn:computer").performClick()
        compose.onNodeWithTag("conn:tv").performClick()
        compose.onNodeWithTag("conn:second_player").performClick()
        assertEquals(listOf("computer", "tv", "second_player"), selected)
    }

    @Test fun nodeLabelsShowTitles() {
        show()
        compose.onNodeWithText("Computer settings").assertExists()
        compose.onNodeWithText("TV settings").assertExists()
        compose.onNodeWithText("Second player").assertExists()
    }

    @Test fun narrowStackKeepsAllNodesAndRoutes() {
        show(narrow = true)
        compose.onNodeWithTag("conn:center").assertExists().assertHasNoClickAction()
        compose.onNodeWithTag("conn:computer").assertExists()
        compose.onNodeWithTag("conn:tv").assertExists()
        compose.onNodeWithTag("conn:second_player").assertExists()
        compose.onNodeWithTag("conn:computer").performClick()
        assertEquals(listOf("computer"), selected)
    }
}
