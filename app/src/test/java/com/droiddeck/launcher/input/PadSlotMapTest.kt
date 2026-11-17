package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class PadSlotMapTest {
    @Test
    fun `base 0 assigns pads in connection order`() {
        val map = PadSlotMap(4, 0)
        assertEquals(0, map.assign(11))
        assertEquals(1, map.assign(22))
        assertEquals(2, map.assign(33))
        assertEquals(3, map.assign(44))
        assertEquals(-1, map.assign(55))
        assertEquals(0, map.assign(11))
    }

    @Test
    fun `base 1 starts at slot 1 and overflows sooner`() {
        val map = PadSlotMap(4, 1)
        assertEquals(1, map.assign(11))
        assertEquals(2, map.assign(22))
        assertEquals(3, map.assign(33))
        assertEquals(-1, map.assign(44))
    }

    @Test
    fun `base change re-maps in connection order and drops overflow`() {
        val map = PadSlotMap(4, 0)
        map.assign(11)
        map.assign(22)
        map.assign(33)
        map.assign(44)
        assertEquals(listOf(44), map.setBaseSlot(1))
        assertEquals(mapOf(11 to 1, 22 to 2, 33 to 3), map.slots())
        assertEquals(emptyList<Int>(), map.setBaseSlot(0))
        assertEquals(mapOf(11 to 0, 22 to 1, 33 to 2), map.slots())
    }

    @Test
    fun `removing a device frees its slot for the next pad`() {
        val map = PadSlotMap(4, 0)
        map.assign(11)
        map.assign(22)
        map.remove(11)
        assertEquals(0, map.assign(33))
        assertEquals(1, map.slotOf(22))
    }

    @Test
    fun `base slot computation`() {
        assertEquals(1, PadSlotMap.computeBaseSlot(true, true, false))
        assertEquals(0, PadSlotMap.computeBaseSlot(true, false, false))
        assertEquals(0, PadSlotMap.computeBaseSlot(false, true, false))
        assertEquals(1, PadSlotMap.computeBaseSlot(false, false, true))
        assertEquals(0, PadSlotMap.computeBaseSlot(false, false, false))
    }
}
