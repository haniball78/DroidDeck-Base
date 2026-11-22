package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.ComponentsManager
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h752dp-land", application = android.app.Application::class)
class ComponentsReorgTest {
    @get:Rule val compose = createRobolectricComposeRule()

    private val gameA = Library.SteamGame(42, "Game A", null, "internal")
    private val gameB = Library.SteamGame(43, "Game B", null, "internal")

    private val proton = ComponentsManager.Proton("proton-1", "Proton 8.0-5", File("/tmp/proton"), "", "8.0-5", true)
    private val view = ComponentsManager.ProtonView(
        proton = proton,
        components = mapOf(
            "dxvk" to ComponentsManager.Component("DXVK 2.3.1", "DXVK 2.3.1", "DXVK 2.3.1", "DXVK 2.3.1", null, null),
            "vkd3d" to ComponentsManager.Component("VKD3D 2.10", "VKD3D 2.10", "VKD3D 2.10", "VKD3D 2.10", null, null),
            "fex" to ComponentsManager.Component("FEX 2411", "FEX 2411", "FEX 2411", "FEX 2411", null, null),
        ),
        originals = listOf(ComponentsManager.Original("dxvk", "8.0-5", "DXVK 2.3.1 · bundled", 0)),
        inUseByGame = false,
    )
    private val snapshot = ComponentsManager.Snapshot(
        protons = listOf(view),
        packages = listOf(ComponentsManager.Package("fex-2411.wcp", "fex", "2411", "", 1048576)),
    )
    private val catalog = listOf(
        ComponentsManager.CatalogItem("dxvk-2.4.wcp", "dxvk", "2.4", "", 2097152, ""),
    )
    private val protons = ProtonsState(
        rows = listOf(ProtonRow("proton-1", "Proton 8.0-5", "8.0-5", false)),
        runtimeReady = true,
    )

    private var pickedComp by mutableStateOf(PROTON_TAB)
    private var refreshClicks = 0

    @After fun reset() { Steps.drop() }

    private fun show(narrow: Boolean = false, comp: String = PROTON_TAB, phoneWidth: Boolean = false) {
        pickedComp = comp
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            DroidDeckTheme {
                CompositionLocalProvider(LocalNarrowPane provides narrow) {
                    ComponentsPage(
                        snapshot = snapshot,
                        catalog = catalog,
                        catalogAt = 0,
                        protonId = "proton-1",
                        comp = pickedComp,
                        checking = false,
                        busy = null,
                        downloads = emptyMap(),
                        onProton = {},
                        onComp = { pickedComp = it },
                        onSwap = {},
                        onRestore = {},
                        onCancelQueued = {},
                        onDeletePackage = {},
                        onDeleteOriginal = {},
                        onDownload = {},
                        onRefresh = { refreshClicks++ },
                        onImport = {},
                        onBack = {},
                        games = listOf(gameA, gameB),
                        requestInitialFocus = false,
                        protons = protons,
                    )
                }
            }
        }
        compose.setContent {
            if (phoneWidth) Box(Modifier.size(360.dp, 800.dp)) { content() } else content()
        }
    }

    private fun tapText(text: String) {
        val bounds = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(bounds.center) }
    }

    @Test fun wideTopRailHoldsAllFiveLayersWithGpuApart() {
        val context = RuntimeEnvironment.getApplication()
        show()
        compose.onNodeWithTag("components-top-rail").assertExists()
        compose.onNodeWithTag("components-proton-bracket").assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_device).uppercase()).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_gpu_drivers)).assertExists()
        compose.onAllNodesWithText(context.getString(R.string.comp_proton)).assertCountEquals(2)
        compose.onNodeWithText("DXVK").assertExists()
        compose.onNodeWithText("VKD3D-Proton").assertExists()
        compose.onNodeWithText("FEX").assertExists()
        val gpu = compose.onNodeWithText(context.getString(R.string.comp_gpu_drivers)).fetchSemanticsNode().boundsInRoot
        val bracket = compose.onNodeWithTag("components-proton-bracket").fetchSemanticsNode().boundsInRoot
        assertTrue(gpu.right <= bracket.left)
    }

    @Test fun wideDetailBenchSitsLeftOfPerGameBenchInSixtyFortySplit() {
        show()
        val detail = compose.onNodeWithTag("components-detail-bench").fetchSemanticsNode().boundsInRoot
        val bench = compose.onNodeWithTag("components-pergame-bench").fetchSemanticsNode().boundsInRoot
        assertTrue(detail.left < bench.left)
        assertTrue(detail.right <= bench.left)
        assertTrue(detail.width > bench.width)
    }

    @Test fun wideDetailBenchKeepsInstalledAvailableAndAboutForProton() {
        val context = RuntimeEnvironment.getApplication()
        show()
        compose.onNodeWithText(context.getString(R.string.comp_installed_count, 1).uppercase()).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_available_count, 0).uppercase()).assertExists()
        compose.onAllNodesWithText("Proton 8.0-5").assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.comp_for_every_proton).uppercase()).assertExists()
        compose.onNodeWithText("Select an installed row to swap it in", substring = true).assertExists()
    }

    @Test fun wideFexLayerKeepsPresetInstalledRowAndImport() {
        val context = RuntimeEnvironment.getApplication()
        show(comp = "fex")
        compose.onAllNodesWithText(context.getString(R.string.fex_preset_title)).assertCountEquals(2)
        compose.onNodeWithText(context.getString(R.string.fex_preset_title).uppercase()).assertExists()
        compose.onNodeWithText("2411").assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_import_wcp)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_nightlies_count, 0).uppercase()).assertExists()
    }

    @Test fun widePerGameBenchShowsGeneralSummaryWhenNoGameSelected() {
        val context = RuntimeEnvironment.getApplication()
        show()
        compose.onNodeWithTag("components-general-summary").assertExists()
        compose.onAllNodesWithText("Proton 8.0-5").assertCountEquals(2)
        compose.onNodeWithText(context.getString(FexPreset.byId("").label)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general_detail)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).assertExists()
    }

    @Test fun widePerGameBenchShowsOverridesNextToGeneralDetail() {
        val context = RuntimeEnvironment.getApplication()
        show()
        compose.onNodeWithText(context.getString(R.string.comp_scope_general)).performClick()
        compose.onNodeWithText("Game A").performClick()
        compose.onNodeWithText(context.getString(R.string.comp_game_hint)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_game_reset)).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_this_proton).uppercase()).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_installed_count, 1).uppercase()).assertExists()
        compose.onNodeWithTag("components-detail-bench").assertExists()
        compose.onNodeWithTag("components-pergame-bench").assertExists()
    }

    @Test fun wideRailCardSwitchesLayerToDxvk() {
        val context = RuntimeEnvironment.getApplication()
        show()
        tapText("DXVK")
        compose.runOnIdle { assertTrue(pickedComp == "dxvk") }
        compose.onNodeWithText(context.getString(R.string.comp_installed_count, 1).uppercase()).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_nightlies_count, 1).uppercase()).assertExists()
        compose.onNodeWithText(context.getString(R.string.comp_get)).assertExists()
    }

    @Test fun wideRefreshStillReachesItsHandler() {
        val context = RuntimeEnvironment.getApplication()
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.comp_check_all)).performClick()
        compose.runOnIdle { assertTrue(refreshClicks == 1) }
    }

    @Test fun narrowBenchesStackVertically() {
        show(narrow = true)
        compose.onNodeWithTag("components-top-rail").assertExists()
        val detail = compose.onNodeWithTag("components-detail-bench").fetchSemanticsNode().boundsInRoot
        val bench = compose.onNodeWithTag("components-pergame-bench").fetchSemanticsNode().boundsInRoot
        assertTrue(detail.top < bench.top)
        assertTrue(detail.bottom <= bench.top)
        assertTrue(detail.left == bench.left)
    }

    @Test fun narrowLayerTapKeepsFloodPage() {
        show(narrow = true)
        compose.onAllNodesWithText("DXVK").assertCountEquals(1)
        tapText("DXVK")
        compose.onAllNodesWithText("DXVK").assertCountEquals(2)
        compose.onNodeWithTag("components-detail-bench").assertExists()
        compose.onAllNodesWithText("DXVK")[1].performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onAllNodesWithText("DXVK").assertCountEquals(1)
    }

    @Test fun phoneWidthRailStillHoldsAllFiveLayers() {
        show(narrow = true, phoneWidth = true)
        compose.onNodeWithTag("components-top-rail").assertExists()
        compose.onNodeWithTag("components-proton-bracket").assertExists()
        compose.onNodeWithText("DXVK").assertExists()
        compose.onNodeWithText("VKD3D-Proton").assertExists()
        compose.onNodeWithText("FEX").assertExists()
        compose.onNodeWithTag("components-detail-bench").assertExists()
        compose.onNodeWithTag("components-pergame-bench").assertExists()
    }
}
