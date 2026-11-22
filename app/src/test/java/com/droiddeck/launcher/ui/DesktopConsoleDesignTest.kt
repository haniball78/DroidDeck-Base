package com.droiddeck.launcher.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class DesktopConsoleDesignTest {
    @get:Rule val compose = createRobolectricComposeRule()
    private var desktopClicks = 0
    private var settingsClicks = 0
    private var runtimeBlocked by mutableStateOf(false)

    @After fun closeConfirmation() { Steps.drop() }

    @Test fun consoleButtonCallsOnDesktop() {
        show()
        compose.onNodeWithText("Open desktop").assertIsDisplayed().performClick()
        assertEquals(1, desktopClicks)
    }

    @Test fun consoleButtonDisabledWhileBusy() {
        show(busy = true)
        compose.onNodeWithText("Open desktop").assertIsNotEnabled()
    }

    @Test fun lipChevronStillOpensSettings() {
        show()
        // The lip appears only when the play button has focus (or touch input);
        // in tests the input mode is keyboard, so focus the button explicitly.
        compose.onNodeWithText("Open desktop").requestFocus()
        settle()
        compose.onNodeWithTag("sheet-open").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithTag("sheet-open").performClick()
        settle()
        assertEquals(1, settingsClicks)
    }

    @Test fun consoleTilesAreLarger() {
        show(emulators = listOf(Library.Emulator("rpcs3", "RPCS3", "PS3", "rpcs3", true, emptyList())))
        compose.onNodeWithContentDescription("RPCS3").assertWidthIsAtLeast(140.dp)
    }

    @Test fun dockHeaderAndGetBadge() {
        show(emulators = listOf(Library.Emulator("rpcs3", "RPCS3", "PS3", "rpcs3", false, emptyList())))
        compose.onNodeWithText("EMULATORS & APPS").assertIsDisplayed()
        compose.onNodeWithText("Get").assertIsDisplayed()
        // The Arabic values are already correct in strings.xml (verified by code read):
        // user_apps_section = "المحاكيات والتطبيقات", comp_get = "جلب".
        val app = RuntimeEnvironment.getApplication()
        val ar = app.createConfigurationContext(Configuration().apply { setLocale(Locale("ar")) })
        assertEquals("المحاكيات والتطبيقات", ar.getString(R.string.user_apps_section))
        assertEquals("جلب", ar.getString(R.string.comp_get))
    }

    @Test fun allDesktopControlsStillPresent() {
        show(emulators = listOf(Library.Emulator("rpcs3", "RPCS3", "PS3", "rpcs3", true, emptyList())))
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
                                onSetPhantomProcessLimit = { _, _ -> }, onRemoveDesktop = {},
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
