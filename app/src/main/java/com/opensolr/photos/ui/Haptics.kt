package com.opensolr.photos.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The taps the fast scroller gives back while a finger drags down the months.
 *
 * Played through the view rather than the vibrator: `performHapticFeedback` needs no VIBRATE
 * permission, and it obeys the phone's own haptics setting, so someone who turned them off feels
 * nothing. Compose's own `LocalHapticFeedback` is not used because it offers two kinds only,
 * which is not enough to tell a month from a day.
 */
object Haptics {

    /**
     * Whether anything is played at all. Set from the owner's choice in Me when the app starts
     * and whenever it changes, so every call site can stay a single line.
     */
    @Volatile
    var enabled: Boolean = true

    /**
     * One tap: [strong] for crossing a month, the lighter one for a day inside it.
     *
     * Android 14 added constants meant for exactly this - dragging through the segments of a
     * scrubber. Below it, the nearest pair that is actually felt as two different weights.
     */
    fun tick(view: View, strong: Boolean) {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (strong) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }
}
