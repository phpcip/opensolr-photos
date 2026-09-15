package com.opensolr.photos.net

/**
 * Everything that can go wrong talking to Opensolr, sorted by what the app has to do about it.
 */
sealed class OpensolrException(message: String) : Exception(message)

/**
 * The saved API key was refused: the key was regenerated or the account is gone. The only fix
 * is to sign in again.
 */
class SignInRequiredException : OpensolrException("Your Opensolr sign-in is no longer valid.")

/**
 * The sign-in page handed back a code the server would not exchange (expired, reused, or the
 * sign-in was started on another attempt).
 */
class SignInFailedException(reason: String) : OpensolrException("Sign-in could not be completed ($reason).")

/**
 * The plan's monthly AI requests are used up. Resumes when the allowance resets or the plan grows.
 */
class QuotaExceededException(val resetsAt: String?) : OpensolrException("The monthly AI requests of your plan are used up.")

/**
 * The plan does not include vector search.
 */
class VectorNotAllowedException : OpensolrException("Your Opensolr plan does not include vector search.")

/**
 * The index refused a request with 403: disk space or search bandwidth of the plan is used up.
 */
class PlanLimitException : OpensolrException("Your index reached the disk space or search bandwidth of your plan.")

/**
 * The per-minute or per-hour request rate was hit. Safe to retry after [retryAfterSeconds].
 */
class RateLimitedException(val retryAfterSeconds: Long) : OpensolrException("Too many requests, slowing down.")

/**
 * The account cannot hold another index.
 */
class IndexLimitException(message: String) : OpensolrException(message)

/**
 * get_core_info says the index is not in this account.
 */
class IndexMissingException : OpensolrException("This phone's index is not in your Opensolr account.")

/**
 * The index answered 401: its HTTP password changed. Re-reading get_core_info fixes it.
 */
class SolrAuthException : OpensolrException("The index refused its saved password.")

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
class RetryLaterException(val afterSeconds: Long) : OpensolrException("Opensolr asked to slow down; the sync continues shortly.")
