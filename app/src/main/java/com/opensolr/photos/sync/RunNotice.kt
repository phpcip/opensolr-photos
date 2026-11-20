package com.opensolr.photos.sync

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import com.opensolr.photos.AppText
import com.opensolr.photos.R
import com.opensolr.photos.ui.Actions
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a run tells the owner in its notification, at all times: the step at work with its count, the steps before it
 * with the time each began, a word when nothing has moved for a while, and why it ended when the system ended it.
 */
class RunNotice(private val worker: CoroutineWorker, private val title: String, private val id: Int, private val stateId: Int) {

    private val steps = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var line = ""
    private var done = 0
    private var total = 0
    private var shownAt = 0L
    @Volatile private var movedAt = System.currentTimeMillis()
    @Volatile var phase = ""
        private set

    fun info(text: String = line): ForegroundInfo {
        val notification = Notifier.progress(worker.applicationContext, title, text, done, total, id, steps.toList())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, notification)
    }

    /** A step, or a count within it: a new step shows at once, counts at most every 1.5 s. */
    suspend fun step(name: String, doneNow: Int = 0, totalNow: Int = 0) {
        val now = System.currentTimeMillis()
        movedAt = now
        val fresh = name != phase
        if (fresh) {
            steps.addLast(clock.format(Date(now)) + "  " + name)
            if (steps.size > KEPT) steps.removeFirst()
        }
        phase = name
        done = doneNow
        total = totalNow
        line = when {
            totalNow > 0 -> AppText.s(R.string.run_count, name, Actions.formatCount(doneNow.toLong()), Actions.formatCount(totalNow.toLong()))
            doneNow > 0 -> "$name: " + Actions.formatCount(doneNow.toLong())
            else -> name
        }
        if (fresh || now - shownAt > 1500) { shownAt = now; show(line) }
    }

    private suspend fun show(text: String) {
        try { worker.setForeground(info(text)) } catch (e: IllegalStateException) { }
    }

    /** Runs beside the work: when nothing has moved for a while, the notification says so, with the step it stands at. */
    suspend fun watch() {
        while (true) {
            delay(WATCH_MS)
            val quiet = System.currentTimeMillis() - movedAt
            if (quiet >= QUIET_MS) show(AppText.s(R.string.run_quiet, (quiet / 60000L).coerceAtLeast(1).toString(), phase.ifBlank { title }))
        }
    }

    /** The run begins: what an earlier one left said is gone. */
    fun began() = Notifier.clearState(worker.applicationContext, stateId)

    /** The system ended the run before it was done: said, with the reason and the step it was at. */
    fun stopped() {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason else WorkInfo.STOP_REASON_UNKNOWN
        Notifier.state(worker.applicationContext, stateId, title, AppText.s(R.string.run_stopped, reasonText(reason), phase.ifBlank { title }))
    }

    /** It could not finish and comes back by itself. */
    fun retrying(why: String) = Notifier.state(
        worker.applicationContext, stateId, title,
        AppText.s(R.string.run_retry, why.ifBlank { AppText.s(R.string.why_other, "?") }, (worker.runAttemptCount + 1).toString(), MAX_TRIES.toString()),
    )

    /** Too many tries in a row: it is left alone, never round and round. */
    fun gaveUp() = Notifier.state(worker.applicationContext, stateId, title, AppText.s(R.string.run_gave_up, MAX_TRIES.toString()))

    val triesUsedUp: Boolean get() = worker.runAttemptCount >= MAX_TRIES

    companion object {
        const val MAX_TRIES = 5
        private const val KEPT = 7
        private const val WATCH_MS = 20_000L
        private const val QUIET_MS = 90_000L

        fun reasonText(reason: Int): String = when (reason) {
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> AppText.s(R.string.why_net)
            WorkInfo.STOP_REASON_TIMEOUT -> AppText.s(R.string.why_time)
            WorkInfo.STOP_REASON_QUOTA -> AppText.s(R.string.why_quota)
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION -> AppText.s(R.string.why_restricted)
            WorkInfo.STOP_REASON_APP_STANDBY -> AppText.s(R.string.why_standby)
            WorkInfo.STOP_REASON_DEVICE_STATE -> AppText.s(R.string.why_device)
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> AppText.s(R.string.why_fgs)
            WorkInfo.STOP_REASON_CANCELLED_BY_APP -> AppText.s(R.string.why_replaced)
            else -> AppText.s(R.string.why_other, reason.toString())
        }
    }
}
