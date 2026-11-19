package com.droiddeck.launcher.session

/** Which extra parts a session start adds: the pad server + group for a second player, nothing otherwise. */
internal enum class StreamPlan { NONE, PAD_AND_GROUP }

internal fun sessionStreamPlan(secondPlayer: Boolean): StreamPlan = when {
    secondPlayer -> StreamPlan.PAD_AND_GROUP
    else -> StreamPlan.NONE
}
