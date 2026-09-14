package com.opensolr.photos.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.opensolr.photos.auth.AuthFlow
import com.opensolr.photos.data.AccountLimits
import com.opensolr.photos.data.AppPrefs
import com.opensolr.photos.data.PhotoCache
import com.opensolr.photos.data.SyncReport
import com.opensolr.photos.data.SyncSchedule
import com.opensolr.photos.index.IndexManager
import com.opensolr.photos.media.MediaScanner
import com.opensolr.photos.media.PhotoFolder
import com.opensolr.photos.net.IndexLimitException
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.SignInRequiredException
import com.opensolr.photos.search.FacetValue
import com.opensolr.photos.search.NearFilter
import com.opensolr.photos.search.PhotoPin
import com.opensolr.photos.search.PhotoHit
import com.opensolr.photos.search.EditRepository
import com.opensolr.photos.search.SearchFilters
import com.opensolr.photos.search.SearchRepository
import com.opensolr.photos.sync.PlanWatch
import com.opensolr.photos.sync.SyncScheduler
import com.opensolr.photos.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * The screens of the app.
 */
enum class Screen { SignIn, Welcome, Permissions, Folders, Setup, Search, Sync, Account, Map }

/**
 * Everything the UI draws, in one immutable value.
 */
data class UiState(
    val screen: Screen,
    val busy: Boolean = false,
    val signInError: String? = null,
    val notice: String? = null,
    val email: String? = null,
    val account: AccountLimits? = null,
    val folders: List<PhotoFolder> = emptyList(),
    val selectedFolders: Set<String> = emptySet(),
    val foldersLoading: Boolean = false,
    val permissionError: String? = null,
    val setupStep: String = "",
    val setupError: String? = null,
    val setupNeedsUpgrade: Boolean = false,
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val didYouMean: String? = null,
    val filters: SearchFilters = SearchFilters(),
    val hits: List<PhotoHit> = emptyList(),
    val numFound: Long = 0,
    val facets: Map<String, List<FacetValue>> = emptyMap(),
    val searching: Boolean = false,
    val searchNotice: String? = null,
    val searchError: String? = null,
    val smart: Boolean = false,
    val endReached: Boolean = false,
    val schedule: SyncSchedule = SyncSchedule.WEEKLY,
    val lastReport: SyncReport? = null,
    val indexName: String? = null,
    val environment: String? = null,
    val sync: SyncStatus = SyncStatus(false, false, "", 0, 0),
    val showBusyDialog: Boolean = false,
    val accountRefreshing: Boolean = false,
    val accountError: String? = null,
    val foldersReturnTo: Screen = Screen.Setup,
    val pins: List<PhotoPin> = emptyList(),
    val pinsLoading: Boolean = false,
    val pinsError: String? = null,
    val mapFocus: MapFocus? = null,
    val selecting: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val rebuildRequired: Boolean = false,
    /** Counts fresh searches, so the grid scrolls back to the top for each. */
    val searchGeneration: Int = 0,
    /** Counts fresh result pages that arrived, so the grid scrolls to the top once they are in. */
    val resultsGeneration: Int = 0,
    val editSaving: Boolean = false,
    val editError: String? = null,
    /** What the plan's limits mean right now, for the account screen. */
    val planWarnings: List<PlanWatch.Warning> = emptyList(),
)

/**
 * Where the map opens: a point and a zoom level, or null to fit every pin.
 */
data class MapFocus(val lat: Double, val lon: Double, val zoom: Double)

/**
 * Holds the app state and runs every user action.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()
    private val prefs = AppPrefs(application)
    private val api = OpensolrApi()
    private val indexes = IndexManager(application, prefs, api)
    private val searches = SearchRepository(application)
    private val edits = EditRepository(application)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    init {
        viewModelScope.launch {
            var wasBusy = false
            SyncScheduler.status(context).collect { status ->
                _state.update { it.copy(sync = status) }
                if (wasBusy && !status.busy) onSyncFinished()
                wasBusy = status.busy
            }
        }
        if (_state.value.screen == Screen.Search) search(reset = true)
        if (_state.value.screen == Screen.Setup) runSetup()
        // The photo watch is one-shot; make sure one is armed whenever the app is set up.
        if (prefs.session != null && prefs.connection != null) {
            SyncScheduler.watchMedia(context)
            checkConfigVersion()
        }
    }

    /**
     * Compares the configuration the index runs with the one this app ships, at start. An
     * older index asks the owner to rebuild it; a newer one asks for an app update.
     */
    private fun checkConfigVersion() {
        val connection = prefs.connection ?: return
        viewModelScope.launch {
            try {
                val version = indexes.configVersionOf(connection)
                when {
                    version < IndexManager.CONFIG_VERSION -> _state.update { it.copy(rebuildRequired = !prefs.rebuildApproved) }
                    version > IndexManager.CONFIG_VERSION -> _state.update { it.copy(notice = "Your index was set up by a newer version of Opensolr Photos. Update the app to keep syncing.") }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    /**
     * The owner agreed to rebuild the index for the new configuration: the next sync does it,
     * and it starts now.
     */
    fun approveRebuild() {
        prefs.rebuildApproved = true
        _state.update { it.copy(rebuildRequired = false) }
        SyncScheduler.runNow(context)
    }

    /**
     * "Later": search keeps working on the old configuration, syncing waits.
     */
    fun postponeRebuild() {
        _state.update { it.copy(rebuildRequired = false) }
    }

    /**
     * Saves the owner's tags and wording of a photo, on the phone and in the index, and
     * refreshes the photo in the results.
     */
    fun saveEdits(hit: PhotoHit, tags: List<String>, meaning: String?, onDone: () -> Unit) {
        _state.update { it.copy(editSaving = true, editError = null) }
        viewModelScope.launch {
            try {
                val doc = edits.save(hit.id, tags, meaning)
                val updated = hit.copy(
                    meaning = doc.optString("meaning"),
                    customTags = doc.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                )
                _state.update { s -> s.copy(editSaving = false, hits = s.hits.map { if (it.id == hit.id) updated else it }) }
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(editSaving = false, editError = e.message ?: "The edit could not be saved.") }
            }
        }
    }

    /**
     * Where the app opens: sign-in, the folder choice, index setup, or the photos.
     */
    private fun initialState(): UiState {
        val session = prefs.session
        val screen = when {
            session == null -> Screen.SignIn
            !prefs.foldersChosen -> Screen.Permissions
            prefs.connection == null -> Screen.Setup
            else -> Screen.Search
        }
        val notice = prefs.pendingNotice
        prefs.pendingNotice = null
        return UiState(
            screen = screen,
            notice = notice,
            email = session?.email,
            account = prefs.account,
            planWarnings = prefs.account?.let { PlanWatch.evaluate(it) } ?: emptyList(),
            selectedFolders = prefs.folders,
            schedule = prefs.schedule,
            lastReport = prefs.lastReport,
            indexName = prefs.connection?.indexName,
            environment = prefs.connection?.environment,
        )
    }

    /**
     * Opens the browser on the Opensolr sign-in page.
     */
    fun beginSignIn(activityContext: Context) {
        _state.update { it.copy(signInError = null, notice = null) }
        AuthFlow.start(activityContext, prefs)
    }

    /**
     * Finishes a sign-in from the callback URI: the state must be the one this app saved, then
     * the code is swapped for the account credentials.
     */
    fun completeSignIn(uri: Uri) {
        val pending = prefs.takePendingSignIn()
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")

        if (pending == null || state == null ||
            !MessageDigest.isEqual(state.toByteArray(Charsets.US_ASCII), pending.second.toByteArray(Charsets.US_ASCII))
        ) {
            _state.update { it.copy(screen = Screen.SignIn, signInError = "This sign-in was not started here or it took too long. Tap Sign in again.") }
            return
        }
        if (error != null) {
            _state.update { it.copy(screen = Screen.SignIn, signInError = "Sign-in was cancelled.") }
            return
        }
        if (code == null || !CODE_PATTERN.matches(code)) {
            _state.update { it.copy(screen = Screen.SignIn, signInError = "The sign-in answer was not valid. Tap Sign in again.") }
            return
        }

        _state.update { it.copy(busy = true, signInError = null) }
        viewModelScope.launch {
            try {
                val (session, limits) = api.exchangeCode(code, pending.first)
                prefs.session = session
                prefs.account = limits
                _state.update { it.copy(busy = false, email = session.email, account = limits, screen = Screen.Welcome) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, screen = Screen.SignIn, signInError = e.message ?: "Sign-in failed. Tap Sign in again.") }
            }
        }
    }

    /**
     * From the plan summary to the permission step.
     */
    fun continueFromWelcome() {
        _state.update { it.copy(screen = if (prefs.foldersChosen && prefs.connection != null) Screen.Search else Screen.Permissions) }
        if (_state.value.screen == Screen.Search) search(reset = true)
    }

    /**
     * Result of the permission request. Without access to photos there is nothing to do.
     */
    fun onPermissionsResult(photosGranted: Boolean) {
        if (!photosGranted) {
            _state.update { it.copy(permissionError = "Opensolr Photos needs access to your photos to index them. Allow it to continue.") }
            return
        }
        _state.update { it.copy(permissionError = null) }
        openFolders(Screen.Setup)
    }

    /**
     * Opens the folder picker. [returnTo] is where saving leads: index setup on first run, the
     * Sync screen afterwards.
     */
    fun openFolders(returnTo: Screen) {
        _state.update { it.copy(screen = Screen.Folders, foldersLoading = true, foldersReturnTo = returnTo) }
        viewModelScope.launch {
            val folders = withContext(Dispatchers.IO) { MediaScanner.listFolders(context) }
            val selected = if (prefs.foldersChosen) prefs.folders else MediaScanner.defaultFolders(folders)
            _state.update { it.copy(folders = folders, selectedFolders = selected, foldersLoading = false) }
        }
    }

    /**
     * Ticks or unticks a folder.
     */
    fun toggleFolder(path: String) {
        _state.update {
            val next = if (path in it.selectedFolders) it.selectedFolders - path else it.selectedFolders + path
            it.copy(selectedFolders = next)
        }
    }

    /**
     * Saves the folder choice. On first run this goes on to index setup; later it starts a
     * sync so the index follows the new choice.
     */
    fun saveFolders() {
        val selected = _state.value.selectedFolders
        if (selected.isEmpty()) return
        prefs.folders = selected
        if (prefs.connection == null || _state.value.foldersReturnTo == Screen.Setup) {
            _state.update { it.copy(screen = Screen.Setup) }
            runSetup()
        } else {
            SyncScheduler.runNow(context)
            _state.update { it.copy(screen = Screen.Sync) }
        }
    }

    /**
     * Finds or creates this phone's index, then starts the first sync and opens the photos.
     */
    fun runSetup() {
        val session = prefs.session ?: return signedOut()
        _state.update { it.copy(setupStep = "Connecting to Opensolr", setupError = null, setupNeedsUpgrade = false) }
        viewModelScope.launch {
            try {
                val (connection, _) = indexes.ensure(session) { step -> _state.update { it.copy(setupStep = step) } }
                SyncScheduler.applySchedule(context, prefs.schedule)
                SyncScheduler.watchMedia(context)
                SyncScheduler.runNow(context)
                _state.update { it.copy(screen = Screen.Search, indexName = connection.indexName, environment = connection.environment) }
                refreshAccount()
                search(reset = true)
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: IndexLimitException) {
                _state.update { it.copy(setupError = e.message, setupNeedsUpgrade = true) }
            } catch (e: Exception) {
                _state.update { it.copy(setupError = e.message ?: "The index could not be set up.") }
            }
        }
    }

    /**
     * Updates the search text without searching yet.
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        // Autocomplete from the labels, a short pause after the last keystroke.
        suggestJob?.cancel()
        if (query.trim().length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            val found = searches.suggest(query)
            _state.update { if (it.query == query) it.copy(suggestions = found) else it }
        }
    }

    /**
     * The user picked an autocomplete entry or the spelling suggestion: search for it.
     */
    fun applySuggestion(text: String) {
        suggestJob?.cancel()
        _state.update { it.copy(query = text, suggestions = emptyList()) }
        search(reset = true)
    }

    /**
     * Runs the search from the first page ([reset]) or loads the next page.
     */
    fun search(reset: Boolean) {
        val current = _state.value
        if (!reset && (current.searching || current.endReached)) return
        searchJob?.cancel()
        val start = if (reset) 0 else current.hits.size
        suggestJob?.cancel()
        _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList(), searchGeneration = if (reset) it.searchGeneration + 1 else it.searchGeneration) }
        searchJob = viewModelScope.launch {
            try {
                val page = searches.search(current.query, current.filters, start)
                _state.update {
                    val hits = if (reset) page.hits else it.hits + page.hits
                    it.copy(
                        searching = false,
                        hits = hits,
                        numFound = page.numFound,
                        facets = if (reset || it.facets.isEmpty()) page.facets else it.facets,
                        smart = page.smart,
                        searchNotice = page.notice,
                        didYouMean = if (reset) page.didYouMean else it.didYouMean,
                        resultsGeneration = if (reset) it.resultsGeneration + 1 else it.resultsGeneration,
                        endReached = hits.size >= page.numFound || page.hits.isEmpty(),
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = e.message ?: "Search failed.") }
            }
        }
    }

    /**
     * Opens the map with the photos of the current search that have a GPS position. With
     * [focus] the map opens on that point instead of fitting every pin.
     */
    fun openMap(focus: MapFocus? = null) {
        _state.update { it.copy(screen = Screen.Map, mapFocus = focus) }
        loadPins()
    }

    /**
     * Loads (or reloads) the pins of the map for the current query and filters.
     */
    fun loadPins() {
        val current = _state.value
        _state.update { it.copy(pinsLoading = true, pinsError = null) }
        viewModelScope.launch {
            try {
                val pins = searches.pins(current.query, current.filters)
                _state.update { it.copy(pins = pins, pinsLoading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(pinsLoading = false, pinsError = e.message ?: "The map could not be loaded.") }
            }
        }
    }

    /**
     * Radius search: keeps the query and filters, adds "within [radiusKm] of [lat],[lon]", and
     * returns to the photos.
     */
    fun searchNear(lat: Double, lon: Double, radiusKm: Double) {
        _state.update { it.copy(screen = Screen.Search, filters = it.filters.copy(near = NearFilter(lat, lon, radiusKm))) }
        search(reset = true)
    }

    /**
     * Applies a new set of filters and searches again.
     */
    fun setFilters(filters: SearchFilters) {
        _state.update { it.copy(filters = filters) }
        search(reset = true)
    }

    /**
     * Force Re-Sync: starts a sync unless one is already queued or running, in which case the
     * user is asked to be patient instead.
     */
    fun forceResync() {
        if (_state.value.sync.busy || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        SyncScheduler.runNow(context)
    }

    /**
     * Enters or leaves photo selection on the grid.
     */
    fun setSelecting(on: Boolean) {
        _state.update { it.copy(selecting = on, selectedIds = if (on) it.selectedIds else emptySet()) }
    }

    /**
     * Ticks or unticks a photo while selecting.
     */
    fun toggleSelected(id: String) {
        _state.update {
            val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            it.copy(selectedIds = next)
        }
    }

    /**
     * Re-sync selected: the chosen photos are read again by CLIP at the next sync, which
     * starts right away, regardless of what the cache knows about them.
     */
    fun resyncSelected() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        prefs.resyncIds = prefs.resyncIds + ids
        _state.update { it.copy(selecting = false, selectedIds = emptySet()) }
        if (_state.value.sync.busy || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        SyncScheduler.runNow(context)
    }

    /**
     * Closes the "a sync is already running" dialog.
     */
    fun dismissBusyDialog() {
        _state.update { it.copy(showBusyDialog = false) }
    }

    /**
     * Sets the scheduled Re-Sync to weekly or monthly.
     */
    fun setSchedule(schedule: SyncSchedule) {
        prefs.schedule = schedule
        SyncScheduler.applySchedule(context, schedule)
        _state.update { it.copy(schedule = schedule) }
    }

    /**
     * Reads the plan limits and usage again.
     */
    fun refreshAccount() {
        val session = prefs.session ?: return
        val connection = prefs.connection ?: return
        _state.update { it.copy(accountRefreshing = true, accountError = null) }
        viewModelScope.launch {
            try {
                val limits = api.accountSummary(session, connection.indexName, prefs.account)
                prefs.account = limits
                val warnings = PlanWatch.notifyNew(context, prefs, limits)
                _state.update { it.copy(account = limits, accountRefreshing = false, planWarnings = warnings) }
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(accountRefreshing = false, accountError = e.message) }
            }
        }
    }

    /**
     * Signs out: stops every sync and forgets the account, the index connection and the cache.
     */
    fun signOut() {
        SyncScheduler.cancelAll(context)
        viewModelScope.launch(Dispatchers.IO) { PhotoCache(context).clear() }
        signedOut(null)
    }

    /**
     * Opens a screen.
     */
    fun open(screen: Screen) {
        _state.update { it.copy(screen = screen) }
        if (screen == Screen.Account) refreshAccount()
    }

    /**
     * Opens a screen only when the app is past sign-in and setup (for notification taps).
     */
    fun openIfSignedIn(screen: Screen) {
        if (prefs.session != null && prefs.connection != null) open(screen)
    }

    /**
     * Back from a secondary screen to the photos.
     */
    fun back() {
        val current = _state.value
        when (current.screen) {
            Screen.Map -> _state.update { it.copy(screen = Screen.Search) }
            // Back to the photos always reloads them: a sync may have finished meanwhile.
            Screen.Sync, Screen.Account -> {
                _state.update { it.copy(screen = Screen.Search) }
                search(reset = true)
            }
            Screen.Folders -> if (current.foldersReturnTo == Screen.Sync) _state.update { it.copy(screen = Screen.Sync) }
            else -> Unit
        }
    }

    /**
     * Refreshes what depends on a finished sync: the report, the plan usage, the results, and a
     * sign-in that stopped working.
     */
    private fun onSyncFinished() {
        val report = prefs.lastReport
        _state.update { it.copy(lastReport = report, account = prefs.account, planWarnings = prefs.account?.let { a -> PlanWatch.evaluate(a) } ?: emptyList(), indexName = prefs.connection?.indexName, environment = prefs.connection?.environment) }
        if (report?.status == "sign_in_required") {
            signedOut(prefs.pendingNotice ?: "Your Opensolr sign-in stopped working. Sign in again.")
            prefs.pendingNotice = null
            return
        }
        if (report?.status == "rebuild_required") _state.update { it.copy(rebuildRequired = true) }
        if (report?.status == "update_app") _state.update { it.copy(notice = report.message) }
        if (_state.value.screen == Screen.Search) search(reset = true)
    }

    /**
     * Returns to the sign-in screen with an optional explanation.
     */
    private fun signedOut(message: String? = null) {
        prefs.signOut()
        _state.update {
            UiState(screen = Screen.SignIn, notice = message, schedule = prefs.schedule, selectedFolders = prefs.folders, sync = it.sync)
        }
    }

    companion object {
        private val CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
