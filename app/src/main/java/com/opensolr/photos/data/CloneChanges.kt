package com.opensolr.photos.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Counts every change to the clone that did not come from the screen itself, so what is shown is read again. */
object CloneChanges {

    private val counter = MutableStateFlow(0L)

    val tick: StateFlow<Long> = counter

    fun changed() {
        counter.value = counter.value + 1
    }
}
