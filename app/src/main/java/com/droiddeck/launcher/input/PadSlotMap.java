package com.droiddeck.launcher.input;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Physical gamepad -> player slot assignment. Pure: no Android framework,
 * no native code, so it is unit-testable without PadBridge.
 * Slot 0 is the phone's own touch pad; physical pads take slots from
 * baseSlot upward, in connection order. A pad that does not fit gets no slot.
 */
public final class PadSlotMap {
    /** Physical pads start at slot 1 when the touch pad owns slot 0, else slot 0. */
    public static int computeBaseSlot(boolean physicalPadPlayer2, boolean touchActive, boolean hiddenPad) {
        return (hiddenPad || (physicalPadPlayer2 && touchActive)) ? 1 : 0;
    }

    private final int slotCount;
    private int baseSlot;
    private final LinkedHashMap<Integer, Integer> slots = new LinkedHashMap<>();

    public PadSlotMap(int slotCount, int baseSlot) {
        this.slotCount = slotCount;
        this.baseSlot = baseSlot;
    }

    public synchronized int baseSlot() {
        return baseSlot;
    }

    /** First free slot from baseSlot, or -1 when full. Re-assigning returns the existing slot. */
    public synchronized int assign(int deviceId) {
        Integer existing = slots.get(deviceId);
        if (existing != null) return existing;
        for (int slot = baseSlot; slot < slotCount; slot++) {
            if (!slots.containsValue(slot)) {
                slots.put(deviceId, slot);
                return slot;
            }
        }
        return -1;
    }

    /** Re-map every device to the new base in connection order; returns devices that no longer fit. */
    public synchronized List<Integer> setBaseSlot(int base) {
        baseSlot = base;
        List<Integer> devices = new ArrayList<>(slots.keySet());
        slots.clear();
        List<Integer> dropped = new ArrayList<>();
        for (int deviceId : devices) {
            if (assign(deviceId) < 0) dropped.add(deviceId);
        }
        return dropped;
    }

    public synchronized void remove(int deviceId) {
        slots.remove(deviceId);
    }

    /** A Joy-Con partner promoted to owner keeps the slot. */
    public synchronized void forceAssign(int deviceId, int slot) {
        slots.put(deviceId, slot);
    }

    public synchronized int slotOf(int deviceId) {
        Integer slot = slots.get(deviceId);
        return slot == null ? -1 : slot;
    }

    public synchronized Map<Integer, Integer> slots() {
        return new LinkedHashMap<>(slots);
    }

    public synchronized void clear() {
        slots.clear();
    }
}
