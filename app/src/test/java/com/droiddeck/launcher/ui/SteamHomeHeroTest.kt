package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class SteamHomeHeroTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var launchedAppId = -1
    private var steamStarts = 0
    private var settingsOpens = 0
    private val shown = mutableListOf<String>()

    private fun hoursAgo(h: Long) = System.currentTimeMillis() / 1000 - h * 3600
    private fun game(appId: Int, name: String, lastPlayed: Long) =
        Library.SteamGame(appId, name, null, Library.ADDED, lastPlayed = lastPlayed)

    @Test fun heroShowsLastPlayedGameAndLaunchesIt() {
        show(listOf(game(440, "World of Sea Battle", hoursAgo(12)), game(441, "Microworks", hoursAgo(30)), game(442, "Reanimal Demo", hoursAgo(50))))
        compose.onNodeWithText("Resume playing").assertExists()
        compose.onNodeWithText("Last played", substring = true).assertExists()
        compose.onAllNodesWithText("World of Sea Battle", substring = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription("DroidDeck").assertCountEquals(0)
        compose.onNodeWithTag("hero-play").performClick()
        assertEquals(440, launchedAppId)
        assertEquals(0, steamStarts)
    }

    @Test fun shelfBubblesOpenGamesInTheLibraryWithoutLaunching() {
        show(listOf(
            game(440, "World of Sea Battle", hoursAgo(12)), game(441, "Microworks", hoursAgo(30)),
            game(442, "Reanimal Demo", hoursAgo(50)), game(443, "Never Played", 0L),
        ))
        compose.onNodeWithText("Recently played").assertExists()
        compose.onNodeWithTag("shelf-bubble:440").assertExists()
        compose.onNodeWithTag("shelf-bubble:441").assertExists()
        compose.onNodeWithTag("shelf-bubble:442").assertExists()
        compose.onAllNodesWithTag("shelf-bubble:443").assertCountEquals(0)
        compose.onNodeWithTag("shelf-bubble:441").performClick()
        assertEquals(listOf("app:441"), shown)
        assertEquals(-1, launchedAppId)
    }

    @Test fun pillsKeepPlaySteamAndSettingsAndNavigate() {
        show(listOf(game(440, "World of Sea Battle", hoursAgo(12)), game(441, "Microworks", hoursAgo(30))))
        compose.onNodeWithTag("play-steam").performClick()
        assertEquals(1, steamStarts)
        assertEquals(-1, launchedAppId)
        compose.onNodeWithText("Desktop").performClick()
        compose.onNodeWithText("Second player").performClick()
        assertEquals(listOf("desktop", "second_player"), shown)
        compose.onNodeWithTag("play-steam").requestFocus()
        settle()
        compose.onNodeWithTag("sheet-open").assertExists()
        compose.onNodeWithTag("sheet-open").performClick()
        settle()
        assertEquals(1, settingsOpens)
    }

    @Test fun noPlayedGameKeepsTheOldEmptyState() {
        show(listOf(game(443, "Never Played", 0L)))
        compose.onAllNodesWithText("Resume playing").assertCountEquals(0)
        compose.onAllNodesWithText("Recently played").assertCountEquals(0)
        compose.onNodeWithTag("play-steam").assertExists()
        compose.onAllNodesWithContentDescription("DroidDeck").assertCountEquals(0)
    }

    @Test fun narrowPaneStillRendersHeroAndShelf() {
        show(listOf(game(440, "World of Sea Battle", hoursAgo(12)), game(441, "Microworks", hoursAgo(30))), narrow = true)
        compose.onNodeWithText("Resume playing").assertExists()
        compose.onNodeWithTag("shelf-bubble:441").assertExists()
        compose.onNodeWithTag("play-steam").assertExists()
        assertTrue(compose.onNodeWithTag("hero-play").fetchSemanticsNode().boundsInRoot.width > 0f)
    }

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    private fun show(games: List<Library.SteamGame>, narrow: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(
                    LocalFrontFocus provides FrontFocus(),
                    LocalShowSection provides { key -> shown.add(key) },
                    LocalNarrowPane provides narrow,
                ) {
                    FocusGlideHost {
                        SteamHome(FrontEndState(
                            installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = games, emulators = emptyList(), running = null,
                        ), FrontEndActions(
                            onPlay = { steamStarts++ }, onPlaySteamVr = {}, onPlayDesktopUi = {}, onSteamGame = { launchedAppId = it.appId }, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = { settingsOpens++ }, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onSetPhantomProcessLimit = { _, _ -> },
                        ), Modifier.fillMaxSize())
                    }
                }
            }
        }
        settle()
    }
}
