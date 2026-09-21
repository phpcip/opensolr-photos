package com.opensolr.photos.sync

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import android.content.Context
import com.opensolr.photos.data.AccountLimits
import com.opensolr.photos.data.AppPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PlanWatch {

    data class Warning(val key: String, val title: String, val text: String, val urgent: Boolean)

    private const val WARN_AT = 0.9

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

    fun notifyNew(context: Context, prefs: AppPrefs, limits: AccountLimits): List<Warning> {
        val warnings = evaluate(limits)
        val seen = prefs.warnedKeys
        val fresh = warnings.filter { it.key !in seen }
        fresh.forEach { Notifier.planLimit(context, it.title, it.text, it.key) }
        if (fresh.isNotEmpty()) prefs.warnedKeys = seen + fresh.map { it.key }
        return warnings
    }

    private fun percent(share: Double): String = "${(share * 100).toInt()}%"
}
