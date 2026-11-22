package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class StoresReorgTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private fun item(id: String, title: String, price: String = "$9.99") =
        CatalogItem(Store.GOG, id, title, null, hasPrice = true, finalPrice = price)

    private val games = listOf(item("g1", "Alpha Game"), item("g2", "Beta Game"), item("g3", "Gamma Game"))

    @Test fun headerItemsExcludeDockedChromeOnWide() {
        assertEquals(1, storeGridHeaderItems(false, false, false, false))
        assertEquals(5, storeGridHeaderItems(true, true, true, true))
        assertEquals(0, storeGridHeaderItems(false, false, false, true, inGridHeader = false))
        assertEquals(3, storeGridHeaderItems(true, true, true, true, inGridHeader = false))
    }

    @After fun reset() {
        StoresState.accounts.clear()
        StoresState.library.clear()
        StoresState.shelves.clear()
        StoresState.publishDownloads(emptyList())
        StoresState.pendingInstall = null
    }

    private fun seedCatalog(items: List<CatalogItem> = games) {
        StoresState.library[Store.GOG] = items
        StoresState.shelves[Store.GOG] = StoreShelves(whatsNew = items)
    }

    private fun awaitRefresh() {
        val looper = android.os.Looper.getMainLooper()
        repeat(20) {
            Shadows.shadowOf(looper).idle()
            if (StoresState.accountsLoaded) return
            Thread.sleep(100)
        }
    }

    private fun show(narrow: Boolean = false, signedIn: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(
                    LocalFrontFocus provides FrontFocus(),
                    LocalShowSection provides {},
                    LocalNarrowPane provides narrow,
                ) {
                    FocusGlideHost {
                        StoresPage(
                            FrontEndState(
                                installed = "r9", ready = true, available = null, busy = false, stage = "", percent = -1,
                                desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "",
                                romsDir = null, logsEnabled = false, steamGames = emptyList(), emulators = emptyList(), running = null,
                            ),
                            FrontEndActions(
                                onPlay = {}, onPlaySteamVr = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {},
                                onRom = {}, onResume = {}, onSteamSettings = {}, onDesktopSettings = {},
                                onInstallPackage = {}, onRemovePackage = {}, onRuntime = {}, onFrameGenPick = {},
                                onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {},
                                onRoms = {}, onFiles = {}, onLogs = {}, onOffline = {},
                                onSetPhantomProcessLimit = { _, _ -> },
                            ),
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        awaitRefresh()
        if (signedIn) StoresState.accounts[Store.GOG] = "tester"
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun widePaneHasRailWithTitleAndChips() {
        seedCatalog()
        show()
        compose.onNodeWithTag("stores-rail").assertExists()
        compose.onNodeWithTag("stores-rail").assertWidthIsAtLeast(199.dp)
        compose.onNodeWithTag("stores-title").assertExists()
        compose.onNodeWithTag("storechip:gog").assertExists()
        compose.onNodeWithTag("storechip:epic").assertExists()
        compose.onNodeWithTag("storechip:downloads").assertExists()
        compose.onNodeWithTag("storechip:settings").assertExists()
    }

    @Test fun widePaneHasDockedTopBarAndStage() {
        seedCatalog()
        show()
        compose.onNodeWithTag("stores-topbar").assertExists()
        compose.onNodeWithTag("stores-stage").assertExists()
        compose.onNodeWithTag("featured:${games[0].key}").assertExists()
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithText("Library (3)").assertExists()
        compose.onNodeWithText("Store").assertExists()
        compose.onNodeWithText("Install").assertExists()
    }

    @Test fun wideStageInstallRequestsInstall() {
        seedCatalog()
        show()
        compose.onNodeWithText("Install").performClick()
        assertEquals("g1", StoresState.pendingInstall?.id)
    }

    @Test fun wideRailChipSwitchesStore() {
        seedCatalog()
        show()
        compose.onNodeWithTag("storechip:epic").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag("featured:${games[0].key}").assertCountEquals(0)
        compose.onNodeWithTag("storechip:epic").assertExists()
    }

    @Test fun wideDownloadsBarIsFixedBelowGrid() {
        seedCatalog()
        StoresState.publishDownloads(listOf(DownloadEntry(
            Store.GOG, "g1", "Alpha Game",
            state = DownloadState.RUNNING, stage = DownloadStage.DOWNLOAD, bytesDone = 50, bytesTotal = 100,
        )))
        show()
        compose.onNodeWithTag("stores-downloads-bar").assertExists()
        compose.onNodeWithTag("downloads-strip").assertExists()
        compose.onNodeWithTag("downloads-progress").assertExists()
        compose.onNodeWithText("Downloading", substring = true).assertExists()
    }

    @Test fun wideNoDownloadsBarWhenIdle() {
        seedCatalog()
        show()
        compose.onAllNodesWithTag("stores-downloads-bar").assertCountEquals(0)
        compose.onAllNodesWithTag("downloads-strip").assertCountEquals(0)
    }

    @Test fun wideLibraryGridKeepsCards() {
        seedCatalog()
        show()
        compose.onNodeWithTag("card:${games[0].key}").assertExists()
        compose.onNodeWithTag("card:${games[1].key}").assertExists()
        compose.onNodeWithTag("card:${games[2].key}").assertExists()
        compose.onNodeWithTag("card:${games[0].key}").assertHasClickAction()
    }

    @Test fun wideStoreTabKeepsShelvesAndStage() {
        seedCatalog()
        show()
        compose.onNodeWithText("Store").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("stores-stage").assertExists()
        compose.onNodeWithTag("featured:${games[0].key}").assertExists()
        // The game sits on two shelves (new + library), so its card appears twice.
        compose.onAllNodesWithTag("card:${games[0].key}").assertCountEquals(2)
    }

    @Test fun narrowPaneFallsBackToVerticalLayout() {
        seedCatalog()
        show(narrow = true)
        compose.onAllNodesWithTag("stores-rail").assertCountEquals(0)
        compose.onAllNodesWithTag("stores-topbar").assertCountEquals(0)
        compose.onAllNodesWithTag("stores-stage").assertCountEquals(0)
        compose.onAllNodesWithTag("stores-downloads-bar").assertCountEquals(0)
        compose.onNodeWithTag("stores-title").assertExists()
        compose.onNodeWithTag("storechip:gog").assertExists()
        compose.onNodeWithTag("storechip:epic").assertExists()
        compose.onNodeWithTag("storechip:downloads").assertExists()
        compose.onNodeWithTag("storechip:settings").assertExists()
        compose.onNodeWithTag("featured:${games[0].key}").assertExists()
        compose.onNodeWithText("Library (3)").assertExists()
    }

    @Test fun narrowDownloadsStripStillInGrid() {
        seedCatalog()
        StoresState.publishDownloads(listOf(DownloadEntry(
            Store.GOG, "g1", "Alpha Game",
            state = DownloadState.RUNNING, stage = DownloadStage.DOWNLOAD, bytesDone = 50, bytesTotal = 100,
        )))
        show(narrow = true)
        compose.onNodeWithTag("downloads-strip").assertExists()
        compose.onAllNodesWithTag("stores-downloads-bar").assertCountEquals(0)
    }
}
