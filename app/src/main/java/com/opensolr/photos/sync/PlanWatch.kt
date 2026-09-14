package com.opensolr.photos.sync

import android.content.Context
import com.opensolr.photos.data.AccountLimits
import com.opensolr.photos.data.AppPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the plan's limits mean for the app right now, in words, and one notification per
 * situation.
 *
 * Every warning has a key; a notification for a key is posted once, and the monthly ones
 * (AI requests, bandwidth) carry the month in the key so they come back every month. The
 * same warnings are shown on the account screen, so nothing depends on a notification
 * being seen.
 */
object PlanWatch {

    /**
     * One thing the owner should know.
     *
     * @property key    what it is about; notifications are posted once per key
     * @property title  short, for the notification and the account screen
     * @property text   what it means and what to do
     * @property urgent true at a hard limit, false at the 90% warning
     */
    data class Warning(val key: String, val title: String, val text: String, val urgent: Boolean)

    /** Share of a limit from which the owner is warned. */
    private const val WARN_AT = 0.9

    /**
     * The warnings [limits] call for. Nothing is posted here.
     */
    fun evaluate(limits: AccountLimits): List<Warning> {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        val out = ArrayList<Warning>()

        if (!limits.vectorAllowed) {
            out += Warning(
                "no_recognition",
                "Photos are not recognised on your plan",
                "New photos are indexed by their date, camera, place, file name and your tags, and found by those words only. Opensolr does not read what is in them, and search by meaning is off. Upgrade at opensolr.com/pricing to have every photo read.",
                urgent = false,
            )
        }

        if (limits.maxAiRequests > 0) {
            val share = limits.aiRequestsUsed.toDouble() / limits.maxAiRequests
            if (share >= 1.0) {
                out += Warning(
                    "ai_full_$month",
                    "Monthly AI requests used up",
                    "New photos are indexed by date, camera, place, file name and your tags only, without being read into words, and search matches words only. They are read at the first sync after the allowance resets, or now after an upgrade.",
                    urgent = true,
                )
            } else if (share >= WARN_AT) {
                out += Warning(
                    "ai_90_$month",
                    "AI requests almost used up",
                    "${limits.aiRequestsUsed} of ${limits.maxAiRequests} monthly AI requests are used. When they run out, new photos are indexed without being read into words until the allowance resets.",
                    urgent = false,
                )
            }
        }

        if (limits.diskLimitMb > 0) {
            val share = limits.diskUsedMb / limits.diskLimitMb
            if (share >= 1.0) {
                out += Warning("disk_full", "Your photo index is out of disk space", "Opensolr closed the index: syncing and searching stop until you free space or upgrade your plan.", urgent = true)
            } else if (share >= WARN_AT) {
                out += Warning("disk_90", "Disk space almost used up", "The index uses ${percent(share)} of its disk space. When it is full, syncing and searching stop until you upgrade.", urgent = false)
            }
        }

        if (limits.bandwidthLimitMb > 0) {
            val share = limits.bandwidthUsedMb / limits.bandwidthLimitMb
            if (share >= 1.0) {
                out += Warning("bw_full_$month", "Your photo index is out of search bandwidth", "Opensolr closed the index for the rest of the month: syncing and searching stop until the month resets or you upgrade.", urgent = true)
            } else if (share >= WARN_AT) {
                out += Warning("bw_90_$month", "Search bandwidth almost used up", "The index used ${percent(share)} of this month's search bandwidth. When it runs out, syncing and searching stop until the month resets.", urgent = false)
            }
        }
        return out
    }

    /**
     * Evaluates [limits] and posts a notification for every warning not posted before.
     */
    fun notifyNew(context: Context, prefs: AppPrefs, limits: AccountLimits): List<Warning> {
        val warnings = evaluate(limits)
        val seen = prefs.warnedKeys
        val fresh = warnings.filter { it.key !in seen }
        fresh.forEach { Notifier.planLimit(context, it.title, it.text, it.key) }
        if (fresh.isNotEmpty()) prefs.warnedKeys = seen + fresh.map { it.key }
        return warnings
    }

    /**
     * "93%" of a share.
     */
    private fun percent(share: Double): String = "${(share * 100).toInt()}%"
}
