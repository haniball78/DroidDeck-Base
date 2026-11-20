package com.droiddeck.launcher.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class GamePackJobState(
    val appId: Int,
    val name: String,
    val unpack: Boolean,
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String? = null,
    val done: Boolean = false,
    val cancelled: Boolean = false,
) {
    val fraction: Float get() = if (bytesTotal <= 0L) 0f else (bytesDone.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

object GamePackJobs {
    private val _jobs = MutableStateFlow<Map<Int, GamePackJobState>>(emptyMap())
    val jobs: StateFlow<Map<Int, GamePackJobState>> = _jobs.asStateFlow()
    private val cancelFlags = ConcurrentHashMap<Int, AtomicBoolean>()

    fun set(state: GamePackJobState) {
        _jobs.update { it + (state.appId to state) }
    }

    fun state(appId: Int): GamePackJobState? = _jobs.value[appId]

    fun isRunning(appId: Int): Boolean = _jobs.value[appId]?.let { !it.done } == true

    fun requestCancel(appId: Int) {
        cancelFlags.getOrPut(appId) { AtomicBoolean() }.set(true)
    }

    fun shouldStop(appId: Int): Boolean = cancelFlags[appId]?.get() == true

    fun clearCancel(appId: Int) {
        cancelFlags.remove(appId)
    }
}
