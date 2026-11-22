package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import com.droiddeck.launcher.frontend.Library
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class FrontEndGamesTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private val selectedState = mutableStateOf("")
    private val selectedIds = mutableListOf<String>()
    private var launchedAppId = -1
    private var steamStarts = 0
    private var gameComponentsGame: Library.SteamGame? = null
    private var defaultComponentsOpens = 0
    private var mappingOpens = 0
    private var removedGame: Library.SteamGame? = null

    private fun hoursAgo(h: Long) = System.currentTimeMillis() / 1000 - h * 3600
    private fun game(appId: Int, name: String, lastPlayed: Long) =
        Library.SteamGame(appId, name, null, "Steam library", lastPlayed = lastPlayed)

    private val three = listOf(
        game(440, "World of Sea Battle", hoursAgo(12)),
        game(441, "Microworks", hoursAgo(30)),
        game(442, "Reanimal Demo", hoursAgo(50)),
    )

    @Test fun headerShowsGameCountAndAddButton() {
        show(three)
        compose.onNodeWithText("Games").assertExists()
        compose.onNodeWithText("3").assertExists()
        compose.onNodeWithContentDescription("Add game").assertExists()
    }

    @Test fun addButtonOpensPickerDialog() {
        show(three)
        compose.onNodeWithContentDescription("Add game").performClick()
        settle()
        compose.onNodeWithText("Add a game:").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        settle()
        compose.onAllNodesWithText("Add a game:").assertCountEquals(0)
    }

    @Test fun searchFiltersListByNameAndClearRestoresIt() {
        show(three)
        compose.onNodeWithTag("games-search").requestFocus()
        compose.onNodeWithTag("games-search").performTextInput("micro")
        compose.waitForIdle()
        settle()
        compose.onNodeWithTag("games-search").assertTextEquals("micro")
        compose.onAllNodesWithText("Microworks").assertCountEquals(2)
        compose.onAllNodesWithText("World of Sea Battle").assertCountEquals(0)
        compose.onAllNodesWithText("Reanimal Demo").assertCountEquals(0)
        compose.onNodeWithContentDescription("Close").performClick()
        settle()
        compose.onAllNodesWithText("World of Sea Battle").assertCountEquals(2)
        compose.onNodeWithText("Reanimal Demo").assertExists()
    }

    @Test fun searchWithNoMatchShowsEmptyNote() {
        show(three)
        compose.onNodeWithTag("games-search").requestFocus()
        compose.onNodeWithTag("games-search").performTextInput("zzz")
        compose.waitForIdle()
        settle()
        compose.onNodeWithTag("games-search").assertTextEquals("zzz")
        compose.onNodeWithText("No games match", substring = true).assertExists()
        compose.onNodeWithTag("games-search").assertExists()
        compose.onAllNodesWithText("Microworks").assertCountEquals(0)
        compose.onAllNodesWithText("World of Sea Battle").assertCountEquals(0)
    }

    @Test fun sortTogglesRecencyToNameAndBack() {
        show(three)
        compose.onNodeWithText("Recently played").assertExists()
        assertTrue("recency: World of Sea Battle first", rowTop("World of Sea Battle") < rowTop("Microworks"))
        assertTrue("recency: Microworks before Reanimal Demo", rowTop("Microworks") < rowTop("Reanimal Demo"))
        compose.onNodeWithText("Recently played").performClick()
        settle()
        compose.onNodeWithText("Title A–Z").assertExists()
        assertTrue("name: Microworks first", rowTop("Microworks") < rowTop("Reanimal Demo"))
        assertTrue("name: Reanimal Demo before World of Sea Battle", rowTop("Reanimal Demo") < rowTop("World of Sea Battle"))
        compose.onNodeWithText("Title A–Z").performClick()
        settle()
        compose.onNodeWithText("Recently played").assertExists()
        assertTrue("back to recency", rowTop("World of Sea Battle") < rowTop("Microworks"))
    }

    @Test fun tappingRowSelectsGameThenLaunchLaunchesIt() {
        show(three)
        compose.onNode(hasClickAction() and hasText("Microworks")).performClick()
        settle()
        assertEquals(listOf("app:441"), selectedIds)
        compose.onNodeWithText("Launch").performClick()
        assertEquals(441, launchedAppId)
    }

    @Test fun launchButtonLaunchesMostRecentGame() {
        show(three)
        compose.onNodeWithText("Launch").performClick()
        assertEquals(440, launchedAppId)
    }

    @Test fun heroRendersForCurrentGame() {
        show(three)
        val hero = compose.onNodeWithTag("game-hero")
        hero.assertExists()
        assertTrue(hero.fetchSemanticsNode().boundsInRoot.height > 0f)
        compose.onAllNodesWithText("World of Sea Battle").assertCountEquals(2)
    }

    @Test fun allSixLaunchSettingsCardsRender() {
        show(three, controller = mappingActions())
        compose.onNodeWithTag("card:components").assertExists()
        compose.onNodeWithTag("card:fg").assertExists()
        compose.onNodeWithTag("card:controls").assertExists()
        compose.onNodeWithTag("card:second-player").assertExists()
        compose.onNodeWithTag("card:game-settings").assertExists()
        compose.onNodeWithTag("card:wincomp").assertExists()
    }

    @Test fun secondPlayerCardShowsToggleAndFlips() {
        show(three, controller = mappingActions())
        compose.onNodeWithTag("card:second-player").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off")).assertExists()
        compose.onNodeWithText("Add another player").assertExists()
        compose.onNodeWithTag("card:second-player").performClick()
        settle()
        compose.onNodeWithText("On — a second phone can join as player 2").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "On")).assertExists()
    }

    @Test fun controlsCardCallsMappingHandler() {
        show(three, controller = mappingActions())
        compose.onNodeWithTag("card:controls").performClick()
        assertEquals(1, mappingOpens)
    }

    @Test fun frameGenCardOpensMenu() {
        show(three, controller = mappingActions())
        compose.onNodeWithTag("card:fg").performClick()
        settle()
        compose.onNodeWithText("Needs Lossless Scaling on Steam").assertExists()
    }

    @Test fun gameSettingsCardOpensMenu() {
        show(three, controller = mappingActions())
        compose.onNodeWithTag("card:game-settings").performClick()
        settle()
        compose.onNodeWithText("WORLD OF SEA BATTLE SETTINGS").assertExists()
    }

    @Test fun componentsCardCallsGameComponents() {
        show(three, controller = mappingActions())
        compose.onNodeWithTag("card:components").performClick()
        assertEquals(440, gameComponentsGame?.appId)
        assertEquals(0, defaultComponentsOpens)
    }

    @Test fun removeButtonAsksAndRemovesGame() {
        val dir = Files.createTempDirectory("dd-game").toFile()
        val added = Library.SteamGame(441, "Microworks", null, Library.ADDED, gameFiles = dir, source = Library.ADDED)
        show(listOf(added))
        compose.onNodeWithContentDescription("Remove").performClick()
        settle()
        compose.onNodeWithText("Remove Microworks?").assertExists()
        compose.onNodeWithText("Remove").performClick()
        settle()
        assertEquals(441, removedGame?.appId)
        compose.onAllNodesWithText("Remove Microworks?").assertCountEquals(0)
    }

    @Test fun emptyLibraryShowsEmptyStateAndPlaySteam() {
        show(emptyList())
        compose.onNodeWithText("Games you install in Steam show up here after the session.").assertExists()
        compose.onNodeWithTag("play-steam").assertExists()
        compose.onNodeWithTag("play-steam").performClick()
        assertEquals(1, steamStarts)
    }

    @Test fun singleGameLayoutShowsHeroAndLaunchSettingsWithoutListRow() {
        show(listOf(game(440, "World of Sea Battle", hoursAgo(12))))
        compose.onNodeWithTag("game-hero").assertExists()
        compose.onNodeWithTag("card:components").assertExists()
        compose.onAllNodesWithText("World of Sea Battle").assertCountEquals(2)
        compose.onNodeWithText("Launch").performClick()
        assertEquals(440, launchedAppId)
    }

    private fun rowTop(name: String) =
        compose.onNode(hasClickAction() and hasText(name)).fetchSemanticsNode().boundsInRoot.top

    private fun mappingActions() = ControllerActions(
        onOsc = {}, onTint = {}, onOpacity = {}, onSize = {}, onStickClick = {}, onAdaptiveSticks = {},
        onRumble = {}, onSteamButton = {}, onQamButton = {}, onKeyboardButton = {}, onSelectSteam = {},
        onJoyConVr = {}, onEditLayout = {}, onResetLayout = {}, onMapping = { mappingOpens++ }, onResetAll = {},
    )

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    private fun show(games: List<Library.SteamGame>, controller: ControllerActions? = null, narrow: Boolean = false) {
        selectedState.value = ""
        selectedIds.clear()
        launchedAppId = -1
        steamStarts = 0
        gameComponentsGame = null
        defaultComponentsOpens = 0
        mappingOpens = 0
        removedGame = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(
                    LocalFrontFocus provides FrontFocus(),
                    LocalNarrowPane provides narrow,
                ) {
                    FocusGlideHost {
                        GamesPage(FrontEndState(
                            installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                            desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
                            romsDir = null, logsEnabled = false, steamGames = games, emulators = emptyList(), running = null,
                        ), FrontEndActions(
                            onPlay = { steamStarts++ }, onPlaySteamVr = {}, onPlayDesktopUi = {}, onSteamGame = { launchedAppId = it.appId }, onDesktop = {}, onEmulator = {},
                            onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                            onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                            onImportLossless = {}, onProtons = {}, onComponents = { defaultComponentsOpens++ },
                            onGameComponents = { gameComponentsGame = it }, onPerformance = {},
                            onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                            onSetPhantomProcessLimit = { _, _ -> },
                            onGameRemoved = { removedGame = it },
                            controller = controller,
                        ), selected = selectedState.value, onSelect = { selectedState.value = it; selectedIds.add(it) }, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        settle()
    }
}
