package com.opensolr.photos.sync

import com.opensolr.photos.R
import com.opensolr.photos.AppText
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
                AppText.s(R.string.pw_no_rec_title),
                AppText.s(R.string.pw_no_rec_text),
                urgent = false,
            )
        }

        if (limits.maxAiRequests > 0) {
            val share = limits.aiRequestsUsed.toDouble() / limits.maxAiRequests
            if (share >= 1.0) {
                out += Warning(
                    "ai_full_$month",
                    AppText.s(R.string.pw_ai_full_title),
                    AppText.s(R.string.pw_ai_full_text),
                    urgent = true,
                )
            } else if (share >= WARN_AT) {
                out += Warning(
                    "ai_90_$month",
                    AppText.s(R.string.pw_ai_90_title),
                    AppText.s(R.string.pw_ai_90_text, com.opensolr.photos.ui.Actions.formatCount(limits.aiRequestsUsed.toLong()), com.opensolr.photos.ui.Actions.formatCount(limits.maxAiRequests.toLong())),
                    urgent = false,
                )
            }
        }

        if (limits.diskLimitMb > 0) {
            val share = limits.diskUsedMb / limits.diskLimitMb
            if (share >= 1.0) {
                out += Warning("disk_full", AppText.s(R.string.pw_disk_full_title), AppText.s(R.string.pw_disk_full_text), urgent = true)
            } else if (share >= WARN_AT) {
                out += Warning("disk_90", AppText.s(R.string.pw_disk_90_title), AppText.s(R.string.pw_disk_90_text, percent(share)), urgent = false)
            }
        }

        if (limits.bandwidthLimitMb > 0) {
            val share = limits.bandwidthUsedMb / limits.bandwidthLimitMb
            if (share >= 1.0) {
                out += Warning("bw_full_$month", AppText.s(R.string.pw_bw_full_title), AppText.s(R.string.pw_bw_full_text), urgent = true)
            } else if (share >= WARN_AT) {
                out += Warning("bw_90_$month", AppText.s(R.string.pw_bw_90_title), AppText.s(R.string.pw_bw_90_text, percent(share)), urgent = false)
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
