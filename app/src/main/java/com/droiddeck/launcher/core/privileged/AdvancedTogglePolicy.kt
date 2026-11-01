package com.droiddeck.launcher.core.privileged

// Advanced toggles are read only at session start, so changing one
// mid-session cannot affect the running session. Disabling them
// while a session runs, instead of silently accepting a change
// that does nothing until the next session, is the honest UI.
// The master kill-switch is exempt: turning it off restores
// immediately, so it stays enabled during a session.
internal object AdvancedTogglePolicy {
    fun enabled(masterEnabled: Boolean, sessionRunning: Boolean, parentEnabled: Boolean = true): Boolean {
        return masterEnabled && parentEnabled && !sessionRunning
    }
}
