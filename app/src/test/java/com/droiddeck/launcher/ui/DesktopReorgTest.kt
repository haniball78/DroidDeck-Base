package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import com.droiddeck.launcher.frontend.Library
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun reorgEmulators(installed: Boolean) = listOf(
    Library.Emulator("rpcs3", "RPCS3", "PS3", "rpcs3", installed, emptyList()),
    Library.Emulator("duckstation", "DuckStation", "PS1", "duckstation", installed, emptyList()),
    Library.Emulator("dolphin", "Dolphin", "GC", "dolphin", installed, emptyList()),
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class DesktopReorgTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var desktopClicks = 0
    private var settingsClicks = 0
    private var terminalClicks = 0
    private var runtimeBlocked by mutableStateOf(false)

    @After fun closeConfirmation() { Steps.drop() }

    @Test fun dockColumnSitsLeftOfStage() {
        show(emulators = reorgEmulators(true))
        val dock = compose.onNodeWithTag("desktop-dock").fetchSemanticsNode().boundsInRoot
        val stage = compose.onNodeWithTag("desktop-stage").fetchSemanticsNode().boundsInRoot
        assertTrue(dock.left < stage.left)
        assertTrue(dock.right <= stage.left)
    }

    @Test fun dockGridPutsTwoTilesOnFirstRow() {
        show(emulators = reorgEmulators(true))
        val topFirst = compose.onNodeWithContentDescription("RPCS3").fetchSemanticsNode().boundsInRoot.top
        val topSecond = compose.onNodeWithContentDescription("DuckStation").fetchSemanticsNode().boundsInRoot.top
        val topThird = compose.onNodeWithContentDescription("Dolphin").fetchSemanticsNode().boundsInRoot.top
        assertEquals(topFirst, topSecond, 1f)
        assertTrue(topThird > topFirst + 10f)
    }

    @Test fun stageKeepsButtonIconsUninstallAndLip() {
        show(emulators = reorgEmulators(true))
        compose.onNodeWithText("Open desktop").assertIsDisplayed().performClick()
        assertEquals(1, desktopClicks)
        compose.onNodeWithContentDescription("Flathub").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dev Terminal").assertIsDisplayed().performClick()
        assertEquals(1, terminalClicks)
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
        compose.onNodeWithTag("sheet-open").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag("sheet-open").performClick()
        settle()
        assertEquals(1, settingsClicks)
    }

    @Test fun tabStripShowsAllTabsAndFilesTabOpensFilesContent() {
        show(emulators = reorgEmulators(true))
        compose.onNodeWithText("Display").assertIsDisplayed()
        compose.onNodeWithText("Controls").assertIsDisplayed()
        compose.onNodeWithText("Session").assertIsDisplayed()
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onAllNodesWithText("Uninstall").assertCountEquals(1)
        compose.onNodeWithText("Files").performClick()
        settle()
        compose.onNodeWithText("ROMs folder").assertIsDisplayed()
        compose.onAllNodesWithText("Uninstall").assertCountEquals(2)
    }

    @Test fun availableEmulatorKeepsGetBadgeInColumn() {
        show(emulators = reorgEmulators(false))
        compose.onNodeWithText("EMULATORS & APPS").assertIsDisplayed()
        compose.onAllNodesWithText("Get").assertCountEquals(3)
        compose.onNodeWithContentDescription("RPCS3").assertIsDisplayed()
    }

    @Test fun allDesktopControlsStillPresent() {
        show(emulators = reorgEmulators(true))
        compose.onNodeWithText("Desktop").assertIsDisplayed()
        compose.onNodeWithText("Open desktop").assertIsDisplayed()
        compose.onNodeWithContentDescription("Flathub").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dev Terminal").assertIsDisplayed()
        compose.onNodeWithText("Uninstall").assertIsDisplayed()
        compose.onNodeWithText("EMULATORS & APPS").assertIsDisplayed()
        compose.onNodeWithText("RPCS3").assertIsDisplayed()
    }

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    private fun show(
        installed: Boolean = true,
        blocked: Boolean = false,
        present: Boolean = true,
        busy: Boolean = false,
        emulators: List<Library.Emulator> = emptyList(),
    ) {
        runtimeBlocked = blocked
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost {
                        Box(Modifier.fillMaxSize()) {
                            DesktopPage(FrontEndState(
                                installed = "r9", ready = true, available = null, busy = busy, stage = "", percent = -1,
                                desktopInstalled = installed, desktopPresent = present, runtimeActionsBlocked = runtimeBlocked,
                                offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
                                steamGames = emptyList(), emulators = emulators, running = null,
                            ), FrontEndActions(
                                onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = { desktopClicks++ }, onEmulator = {}, onRom = {}, onResume = {},
                                onSteamSettings = {}, onDesktopSettings = { settingsClicks++ }, onInstallPackage = {}, onRemovePackage = {},
                                onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
                                onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                                onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {}, onDevTerminal = { terminalClicks++ },
                            ), Modifier.fillMaxSize(), onSelect = {})
                            StepsLayer(Offset.Zero)
                        }
                    }
                }
            }
        }
        settle()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class DesktopReorgNarrowTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var desktopClicks = 0
    private var settingsClicks = 0

    @After fun closeConfirmation() { Steps.drop() }

    @Test fun narrowKeepsOldStackingAndNoWideDivision() {
        show(emulators = reorgEmulators(true))
        compose.onNodeWithText("Desktop").assertIsDisplayed()
        compose.onNodeWithText("Open desktop").assertIsDisplayed().performClick()
        assertEquals(1, desktopClicks)
        compose.onNodeWithText("EMULATORS & APPS").assertIsDisplayed()
        compose.onNodeWithContentDescription("RPCS3").assertIsDisplayed()
        compose.onNodeWithTag("desktop-dock").assertDoesNotExist()
        compose.onNodeWithTag("desktop-stage").assertDoesNotExist()
        compose.onNodeWithText("Files").assertDoesNotExist()
    }

    @Test fun narrowTilesStayInOneHorizontalRow() {
        show(emulators = reorgEmulators(true))
        val topFirst = compose.onNodeWithContentDescription("RPCS3").fetchSemanticsNode().boundsInRoot.top
        val topSecond = compose.onNodeWithContentDescription("DuckStation").fetchSemanticsNode().boundsInRoot.top
        assertEquals(topFirst, topSecond, 1f)
    }

    @Test fun narrowLipStillOpensSettings() {
        show()
        compose.onNodeWithText("Open desktop").requestFocus()
        settle()
        compose.onNodeWithTag("sheet-open").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag("sheet-open").performClick()
        settle()
        assertEquals(1, settingsClicks)
    }

    private fun settle() = compose.mainClock.advanceTimeBy(2_000)

    private fun show(emulators: List<Library.Emulator> = emptyList()) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(LocalFrontFocus provides FrontFocus()) {
                    FocusGlideHost {
                        Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalNarrowPane provides true) {
                                DesktopPage(FrontEndState(
                                    installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                                    desktopInstalled = true, desktopPresent = true, runtimeActionsBlocked = false,
                                    offlineAccount = null, offline = false, frameGenLabel = "", romsDir = null, logsEnabled = false,
                                    steamGames = emptyList(), emulators = emulators, running = null,
                                ), FrontEndActions(
                                    onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = { desktopClicks++ }, onEmulator = {}, onRom = {}, onResume = {},
                                    onSteamSettings = {}, onDesktopSettings = { settingsClicks++ }, onInstallPackage = {}, onRemovePackage = {},
                                    onRuntime = {}, onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {},
                                    onPerformance = {}, onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                                    onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
                                ), Modifier.fillMaxSize(), onSelect = {})
                            }
                            StepsLayer(Offset.Zero)
                        }
                    }
                }
            }
        }
        settle()
    }
}
