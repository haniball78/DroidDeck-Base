package com.droiddeck.launcher.input

import android.content.Context
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GripCustomKeysTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("grip_keys", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun saveLoadRoundTripKeepsLabelKeyAndPosition() {
        GripScheme.CustomKeys.save(context, "game1", listOf(
            GripScheme.CustomKeyButton("M", KeyEvent.KEYCODE_M, 0.5f, 0.6f)))
        val loaded = GripScheme.CustomKeys.load(context, "game1")
        assertEquals(1, loaded.size)
        assertEquals("M", loaded[0].label)
        assertEquals(KeyEvent.KEYCODE_M, loaded[0].keyCode)
        assertEquals(0.5f, loaded[0].xFrac, 0f)
        assertEquals(0.6f, loaded[0].yFrac, 0f)
    }

    @Test fun keysAreIsolatedPerGame() {
        GripScheme.CustomKeys.save(context, "game1", listOf(
            GripScheme.CustomKeyButton("M", KeyEvent.KEYCODE_M, -1f, -1f)))
        GripScheme.CustomKeys.save(context, "game2", listOf(
            GripScheme.CustomKeyButton("F", KeyEvent.KEYCODE_F, 0.2f, 0.3f)))
        assertEquals("M", GripScheme.CustomKeys.load(context, "game1")[0].label)
        assertEquals("F", GripScheme.CustomKeys.load(context, "game2")[0].label)
        assertTrue(GripScheme.CustomKeys.load(context, "game3").isEmpty())
    }

    @Test fun setKeyKeepsPositionWhenChangingTheKey() {
        GripScheme.CustomKeys.save(context, "game1", listOf(
            GripScheme.CustomKeyButton("M", KeyEvent.KEYCODE_M, 0.4f, 0.7f)))
        GripScheme.CustomKeys.setKey(context, "game1", "F", KeyEvent.KEYCODE_F, -1f, -1f)
        val loaded = GripScheme.CustomKeys.load(context, "game1")
        assertEquals(1, loaded.size)
        assertEquals("F", loaded[0].label)
        assertEquals(KeyEvent.KEYCODE_F, loaded[0].keyCode)
        assertEquals(0.4f, loaded[0].xFrac, 0f)
        assertEquals(0.7f, loaded[0].yFrac, 0f)
    }

    @Test fun setKeyCreatesTheButtonWithDefaultPositionWhenNoneExists() {
        GripScheme.CustomKeys.setKey(context, "game1", "Tab", KeyEvent.KEYCODE_TAB, -1f, -1f)
        val loaded = GripScheme.CustomKeys.load(context, "game1")
        assertEquals(1, loaded.size)
        assertEquals("Tab", loaded[0].label)
        assertEquals(-1f, loaded[0].xFrac, 0f)
    }

    @Test fun movePersistsTheNewPosition() {
        GripScheme.CustomKeys.save(context, "game1", listOf(
            GripScheme.CustomKeyButton("M", KeyEvent.KEYCODE_M, -1f, -1f)))
        GripScheme.CustomKeys.move(context, "game1", 0.8f, 0.2f)
        val loaded = GripScheme.CustomKeys.load(context, "game1")
        assertEquals(0.8f, loaded[0].xFrac, 0f)
        assertEquals(0.2f, loaded[0].yFrac, 0f)
        assertEquals("M", loaded[0].label)
    }
}
