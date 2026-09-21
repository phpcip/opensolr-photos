package com.opensolr.photos.net

import com.opensolr.photos.R
import com.opensolr.photos.AppText
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed class OpensolrException(message: String) : Exception(message)

class SignInRequiredException : OpensolrException(AppText.s(R.string.err_sign_in_invalid))

class SignInFailedException(reason: String) : OpensolrException(AppText.s(R.string.err_sign_in_failed, reason))

class QuotaExceededException(val resetsAt: String?) : OpensolrException(AppText.s(R.string.err_quota))

class VectorNotAllowedException : OpensolrException(AppText.s(R.string.err_no_vector))

class PlanLimitException : OpensolrException(AppText.s(R.string.err_plan_limit))

class RateLimitedException(val retryAfterSeconds: Long) : OpensolrException(AppText.s(R.string.err_rate))

class IndexLimitException(message: String) : OpensolrException(message)

class IndexMissingException : OpensolrException(AppText.s(R.string.err_index_missing))

class SolrAuthException : OpensolrException(AppText.s(R.string.err_solr_auth))

class PhotoRejectedException(message: String) : OpensolrException(message)

class ServiceException(message: String) : OpensolrException(message)

class RetryLaterException(val afterSeconds: Long) : OpensolrException(AppText.s(R.string.err_retry))

fun friendlyMessage(e: Throwable, fallback: String): String = when (e) {
    is OpensolrException -> e.message ?: fallback
    is UnknownHostException -> AppText.s(R.string.err_no_connection)
    is SocketTimeoutException -> AppText.s(R.string.err_timeout)
    is SSLException -> AppText.s(R.string.err_ssl)
    is InterruptedIOException -> AppText.s(R.string.err_slow)
    is IOException -> AppText.s(R.string.err_io)
    else -> fallback
}
