package com.group41.kotlinapp.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Invalidates visible lists after mutations, including background synchronization. */
object ActivityChanges {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    fun notifyChanged() { revision.update { it + 1 } }
}
