package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadStage
import com.droiddeck.launcher.stores.download.DownloadState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class StoresSimpleDesignTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private fun item(id: String, title: String, price: String = "$9.99") =
        CatalogItem(Store.GOG, id, title, null, hasPrice = true, finalPrice = price)

    private val games = listOf(item("g1", "Alpha Game"), item("g2", "Beta Game"), item("g3", "Gamma Game"))

    @Test fun pickFeaturedPrefersVisibleFirstItem() {
        assertEquals("g1", pickFeatured(games, listOf(item("w1", "New One")), listOf(item("t1", "Trending One")))?.id)
    }

    @Test fun pickFeaturedFallsBackToShelves() {
        assertEquals("w1", pickFeatured(emptyList(), listOf(item("w1", "New One")), listOf(item("t1", "Trending One")))?.id)
        assertEquals("t1", pickFeatured(emptyList(), emptyList(), listOf(item("t1", "Trending One")))?.id)
    }

    @Test fun pickFeaturedReturnsNullWhenEmpty() {
        assertNull(pickFeatured(emptyList(), null, null))
    }

    @Test fun headerItemsCountIncludesFeatured() {
        assertEquals(1, storeGridHeaderItems(false, false, false, false))
        assertEquals(2, storeGridHeaderItems(false, false, false, true))
        assertEquals(4, storeGridHeaderItems(true, true, false, true))
        assertEquals(5, storeGridHeaderItems(true, true, true, true))
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

    private fun show(signedIn: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DroidDeckTheme {
                CompositionLocalProvider(
                    LocalFrontFocus provides FrontFocus(),
                    LocalShowSection provides {},
                    LocalNarrowPane provides false,
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
        // The page's own refresh() marks accounts loaded; its post also wipes any
        // account seeded before it, so the account is seeded only after it lands.
        awaitRefresh()
        if (signedIn) StoresState.accounts[Store.GOG] = "tester"
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun headerTitleChipsSearchAndTabsExist() {
        seedCatalog()
        show()
        compose.onNodeWithTag("stores-title").assertExists()
        compose.onNodeWithTag("storechip:gog").assertExists()
        compose.onNodeWithTag("storechip:epic").assertExists()
        compose.onNodeWithTag("storechip:downloads").assertExists()
        compose.onNodeWithTag("storechip:settings").assertExists()
        compose.onNodeWithContentDescription("Search").assertExists()
        compose.onNodeWithText("Library (3)").assertExists()
        compose.onNodeWithText("Store").assertExists()
    }

    @Test fun featuredHeroShowsFirstItemAndGridKeepsIt() {
        seedCatalog()
        show()
        compose.onNodeWithTag("featured:${games[0].key}").assertExists()
        // Hero + catalog card: the grid is not shrunk by the featured card.
        compose.onAllNodesWithText("Alpha Game").assertCountEquals(2)
        compose.onNodeWithText("Install").assertExists()
    }

    @Test fun heroInstallRequestsInstall() {
        seedCatalog()
        show()
        compose.onNodeWithText("Install").performClick()
        assertEquals("g1", StoresState.pendingInstall?.id)
    }

    @Test fun catalogCardsStillExistWithClickActions() {
        seedCatalog()
        show()
        compose.onNodeWithTag("card:${games[0].key}").assertExists()
        compose.onNodeWithTag("card:${games[1].key}").assertExists()
        compose.onNodeWithTag("card:${games[2].key}").assertExists()
        compose.onNodeWithTag("card:${games[0].key}").assertHasClickAction()
    }

    @Test fun chipSwitchesStore() {
        seedCatalog()
        show()
        compose.onNodeWithTag("storechip:epic").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag("featured:${games[0].key}").assertCountEquals(0)
        compose.onNodeWithTag("storechip:epic").assertExists()
    }

    @Test fun downloadsStripShowsActiveDownload() {
        seedCatalog()
        StoresState.publishDownloads(listOf(DownloadEntry(
            Store.GOG, "g1", "Alpha Game",
            state = DownloadState.RUNNING, stage = DownloadStage.DOWNLOAD, bytesDone = 50, bytesTotal = 100,
        )))
        show()
        compose.onNodeWithTag("downloads-strip").assertExists()
        compose.onNodeWithText("Downloading", substring = true).assertExists()
    }

    @Test fun noDownloadsStripWhenIdle() {
        seedCatalog()
        show()
        compose.onAllNodesWithTag("downloads-strip").assertCountEquals(0)
    }

    @Test fun signedOutStoreStillShowsSignInCard() {
        show(signedIn = false)
        compose.onNodeWithText("Sign in").assertExists()
        compose.onNodeWithTag("stores-title").assertExists()
    }
}
