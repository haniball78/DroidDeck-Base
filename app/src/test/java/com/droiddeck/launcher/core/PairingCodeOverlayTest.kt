package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingCodeOverlayTest {

    @Test fun codeNeededWithPermissionGoesToOverlay() {
        assertEquals(
            CodeEntryTarget.OVERLAY,
            codeEntryTarget(codeNeeded = true, overlayPermitted = true),
        )
    }

    @Test fun codeNeededWithoutPermissionFallsBackToActivity() {
        assertEquals(
            CodeEntryTarget.ACTIVITY,
            codeEntryTarget(codeNeeded = true, overlayPermitted = false),
        )
    }

    @Test fun anythingButCodeNeededOpensTheApp() {
        assertEquals(
            CodeEntryTarget.APP,
            codeEntryTarget(codeNeeded = false, overlayPermitted = true),
        )
        assertEquals(
            CodeEntryTarget.APP,
            codeEntryTarget(codeNeeded = false, overlayPermitted = false),
        )
    }
}
