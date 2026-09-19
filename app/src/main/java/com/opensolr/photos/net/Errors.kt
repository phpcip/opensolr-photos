package com.opensolr.photos.net

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Everything that can go wrong talking to Opensolr, sorted by what the app has to do about it.
 */
sealed class OpensolrException(message: String) : Exception(message)

/**
 * The saved API key was refused: the key was regenerated or the account is gone. The only fix
 * is to sign in again.
 */
class SignInRequiredException : OpensolrException(AppText.s(R.string.err_sign_in_invalid))

/**
 * The sign-in page handed back a code the server would not exchange (expired, reused, or the
 * sign-in was started on another attempt).
 */
class SignInFailedException(reason: String) : OpensolrException(AppText.s(R.string.err_sign_in_failed, reason))

/**
 * The plan's monthly AI requests are used up. Resumes when the allowance resets or the plan grows.
 */
class QuotaExceededException(val resetsAt: String?) : OpensolrException(AppText.s(R.string.err_quota))

/**
 * The plan does not include vector search.
 */
class VectorNotAllowedException : OpensolrException(AppText.s(R.string.err_no_vector))

/**
 * The index refused a request with 403: disk space or search bandwidth of the plan is used up.
 */
class PlanLimitException : OpensolrException(AppText.s(R.string.err_plan_limit))

/**
 * The per-minute or per-hour request rate was hit. Safe to retry after [retryAfterSeconds].
 */
class RateLimitedException(val retryAfterSeconds: Long) : OpensolrException(AppText.s(R.string.err_rate))

/**
 * The account cannot hold another index.
 */
class IndexLimitException(message: String) : OpensolrException(message)

/**
 * get_core_info says the index is not in this account.
 */
class IndexMissingException : OpensolrException(AppText.s(R.string.err_index_missing))

/**
 * The index answered 401: its HTTP password changed. Re-reading get_core_info fixes it.
 */
class SolrAuthException : OpensolrException(AppText.s(R.string.err_solr_auth))

/**
 * The server refused one photo (for example a format it cannot decode). Only that photo is skipped.
 */
class PhotoRejectedException(message: String) : OpensolrException(message)

/**
 * Anything else: a server error, an unexpected answer, a platform message the app does not know.
 */
class ServiceException(message: String) : OpensolrException(message)

/**
 * The run should stop now and be started again a little later by the scheduler: the API is
 * rate limiting, and waiting inside a background job keeps the phone awake for nothing.
 */
class RetryLaterException(val afterSeconds: Long) : OpensolrException(AppText.s(R.string.err_retry))

/**
 * What a person is told when something goes wrong, from whatever was thrown.
 *
 * The app's own errors are already written for them and are passed through. Everything else
 * carries a message written for a developer - "Unable to resolve host ...", "Software caused
 * connection abort", an SSL class name - which must never reach the screen; the common network
 * failures become one plain sentence each, and anything unrecognised falls back to whatever the
 * caller would have said anyway (Cip, 2026-09-16).
 */
fun friendlyMessage(e: Throwable, fallback: String): String = when (e) {
    is OpensolrException -> e.message ?: fallback
    is UnknownHostException -> AppText.s(R.string.err_no_connection)
    is SocketTimeoutException -> AppText.s(R.string.err_timeout)
    is SSLException -> AppText.s(R.string.err_ssl)
    is InterruptedIOException -> AppText.s(R.string.err_slow)
    is IOException -> AppText.s(R.string.err_io)
    else -> fallback
}
