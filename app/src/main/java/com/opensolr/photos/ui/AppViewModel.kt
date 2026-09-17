package com.opensolr.photos.ui

import com.opensolr.photos.data.distinctWords

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
import com.opensolr.photos.media.PhotoReader
import com.opensolr.photos.net.AccountIndex
import com.opensolr.photos.net.IndexLimitException
import com.opensolr.photos.net.OpensolrApi
import com.opensolr.photos.net.UpdateCheck
import com.opensolr.photos.net.friendlyMessage
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
enum class Screen { SignIn, Welcome, Permissions, Folders, Setup, Search, Sync, Account, Map, Albums }

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
    /**
     * The query of the search that produced the photos on screen. The grid's headings follow
     * this, never the text still being typed (Cip, 2026-09-16): they change on Enter only.
     */
    val searchedQuery: String = "",
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
    /** "Fresh": recency multiplies the score, so recent photos rise without anything being lost. */
    val freshBias: Boolean = false,
    /**
     * Words only: the vector leg is left out and the search is purely lexical, the way the AI
     * switch works on search.opensolr.com (Cip, 2026-09-16).
     */
    val wordsOnly: Boolean = false,
    /** Facet values of the words alone, for the suggestions above a typed search's results. */
    val queryFacets: Map<String, List<FacetValue>> = emptyMap(),
    /** Looking at photos of the same thing: the size of each group, laid out in order over [hits]. */
    val duplicateGroups: List<Int> = emptyList(),
    /** How many duplicate groups have had their photos fetched so far; the rest follow on scroll. */
    val duplicateGroupsLoaded: Int = 0,
    /** How many groups of this kind exist in all, so the count line does not report a page. */
    val duplicateGroupsTotal: Int = 0,
    /** True while the grid shows duplicates (even a kind with no groups at all). */
    val duplicatesMode: Boolean = false,
    /** True while the grid shows the photos the phone could not read (never sent to Opensolr). */
    val skippedMode: Boolean = false,
    /** How many photos the phone could not read; the button over the grid shows only above 0. */
    val skippedCount: Int = 0,
    /** The duplicates slider, 0..10 over SearchRepository.DUPLICATE_FIELDS; 4 = first five words. */
    val duplicateLevel: Int = 4,
    /** With "Show similar photos", the id of the photo the slider is anchored to; null for plain duplicates. */
    val similarToId: String? = null,
    /**
     * That photo itself, kept so the grid can mark it and the way back can open its details
     * again, even at a stop where the index returns nothing.
     */
    val similarToHit: PhotoHit? = null,
    val rebuildRequired: Boolean = false,
    /** Counts fresh searches, so the grid scrolls back to the top for each. */
    val searchGeneration: Int = 0,
    /** Counts fresh result pages that arrived, so the grid scrolls to the top once they are in. */
    val resultsGeneration: Int = 0,
    /** Where the grid should stand: the row that was at the top, by its key, then by position. */
    val gridKey: String? = null,
    val gridIndex: Int = 0,
    val gridOffset: Int = 0,
    /** The group headings folded away in the view on screen. */
    val collapsedHeadings: Set<String> = emptySet(),
    /** Bumped whenever the grid has somewhere to be taken to, so the screen scrolls exactly once. */
    val restoreGeneration: Int = 0,
    val editSaving: Boolean = false,
    val editError: String? = null,
    /** True while tags are being put on every photo of the view at once. */
    val bulkTagging: Boolean = false,
    /** How far that has got: photos written, and how many there are. */
    val bulkTagDone: Int = 0,
    val bulkTagTotal: Int = 0,
    /** Why it did not finish, for the bulk tagging screen. */
    val bulkTagError: String? = null,
    /** What the plan's limits mean right now, for the account screen. */
    val planWarnings: List<PlanWatch.Warning> = emptyList(),
    /** A newer release on GitHub, when the daily check found one and it was not dismissed. */
    val update: UpdateCheck.Update? = null,
    /** True while "Check for updates" on the account screen is asking GitHub. */
    val updateChecking: Boolean = false,
    /** What that check answered, so the account screen says something even when nothing is new. */
    val updateResult: String? = null,
    /** How long an answer from the index may be reused, in seconds; the owner sets it in Me. */
    val cacheSeconds: Int = com.opensolr.photos.data.SearchCache.DEFAULT_SECONDS,
    /** Semantic (0) to lexical (1) balance of a search by meaning, from Me. */
    val lexicalWeight: Float = AppPrefs.DEFAULT_LEXICAL_WEIGHT,
    /** Whether the app answers gestures with a tap you can feel. */
    val hapticsEnabled: Boolean = true,
    /** How many answers are held right now, shown beside the button that clears them. */
    val cachedCount: Int = 0,
    /** Photo indexes of other phones, offered when this phone has none: "which one is your device?" */
    val deviceChoices: List<AccountIndex> = emptyList(),
    /** The albums screen: its sections, whether they are loading, and why they failed. */
    val albums: List<com.opensolr.photos.search.AlbumSection> = emptyList(),
    val albumsLoading: Boolean = false,
    val albumsError: String? = null,
    /** Albums picked with a long press, by "field:value", for deleting or sharing their photos. */
    val selectedAlbums: Set<String> = emptySet(),
    /** Album sections picked with a long press, by title: every album under them. */
    val selectedSections: Set<String> = emptySet(),
    /** True while the photos of the picked albums are being looked up. */
    val albumsWorking: Boolean = false,
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
    private val photoCache = com.opensolr.photos.data.PhotoCache(application)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var facetsJob: Job? = null

    /**
     * Where the grid stood, one place per search (Cip, 2026-09-16). A single remembered position
     * was not enough: opening an album and then clearing its filter, or looking at similar photos
     * and coming back, returns to a search that had its own place in the list. The key is what
     * produced the photos on screen, so each of them is returned to its own.
     */
    private val scrollPositions = HashMap<String, ScrollAt>()

    /**
     * Which group headings are folded away, per view, loaded from the phone so they survive the
     * app being closed. Remembered the same way as the place in the list.
     */
    private val collapsedHeadings = HashMap<String, Set<String>>(prefs.collapsedHeadings)
        .also { com.opensolr.photos.ui.Haptics.enabled = prefs.hapticsEnabled }

    // Both maps are declared ABOVE init on purpose. init starts the first search, and a search
    // answered from the cache finishes without ever suspending - so it reached targetScroll()
    // while these were still null and the app opened saying "Search failed" (Cip, 2026-09-16).
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
        checkForUpdate()
        refreshSkippedCount()
    }

    /**
     * Reads how many photos the phone could not read, for the button over the grid. Leaves the
     * skipped view when there are none left.
     */
    private fun refreshSkippedCount() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { photoCache.skippedCount() }
            _state.update { it.copy(skippedCount = count) }
            if (count == 0 && _state.value.skippedMode) showSkipped()
        }
    }

    /**
     * The skipped photos on or off. On, the grid lists every photo the phone could not open or
     * decode, straight from the phone (they are not in the index); tapping again goes back to
     * the ordinary results.
     */
    fun showSkipped() {
        if (_state.value.skippedMode) {
            _state.update { it.copy(skippedMode = false, searchNotice = null) }
            search(reset = true)
            return
        }
        loadSkipped()
    }

    /**
     * Fills the grid with the skipped photos, as hits built from what the phone knows of them.
     */
    private fun loadSkipped() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val skipped = withContext(Dispatchers.IO) { photoCache.skipped() }
            val hits = skipped.map { s ->
                PhotoHit(
                    id = s.id, mediaId = s.mediaId, path = s.path, fileName = s.fileName, folder = s.folder, mime = s.mime,
                    takenAt = if (s.takenMs > 0) java.time.Instant.ofEpochMilli(s.takenMs).toString() else null,
                    cameraMake = null, cameraModel = null, lens = null, iso = null, exposure = null, fNumber = null, focalLength = null,
                    width = null, height = null, sizeBytes = s.sizeBytes, meaning = s.reason, location = null,
                )
            }
            _state.update {
                it.copy(
                    skippedMode = true, skippedCount = hits.size, duplicatesMode = false, duplicateGroups = emptyList(),
                    similarToId = null, similarToHit = null, hits = hits, numFound = hits.size.toLong(), searching = false,
                    searchError = null, endReached = true, searchedQuery = "", searchGeneration = it.searchGeneration + 1,
                )
            }
        }
    }

    /**
     * Once a day, asks GitHub whether a newer release exists and shows it on the Photos screen.
     * The notice only says where to download; the app never installs anything itself.
     */
    private fun checkForUpdate() {
        if (System.currentTimeMillis() - prefs.updateCheckedAt < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            val update = UpdateCheck.check().getOrNull() ?: return@launch
            // Stamped only when GitHub actually answered: a failed check is retried, not skipped
            // for a whole day.
            prefs.updateCheckedAt = System.currentTimeMillis()
            if (prefs.updateDismissed == update.version) return@launch
            _state.update { it.copy(update = update) }
        }
    }

    /**
     * "Check for updates" on the account screen: asks GitHub straight away, ignoring both the
     * once-a-day gate and an earlier "Not now", and always says what came back.
     */
    fun checkForUpdateNow() {
        if (_state.value.updateChecking) return
        _state.update { it.copy(updateChecking = true, updateResult = null) }
        viewModelScope.launch {
            val outcome = UpdateCheck.check()
            prefs.updateCheckedAt = System.currentTimeMillis()
            val update = outcome.getOrNull()
            _state.update {
                when {
                    // The check itself failed: say so, never claim the app is up to date.
                    outcome.isFailure -> it.copy(updateChecking = false, updateResult = "The check could not reach GitHub. Try again in a moment.")
                    update == null -> it.copy(updateChecking = false, updateResult = "You are on the latest version.")
                    else -> it.copy(updateChecking = false, update = update, updateResult = "Version ${update.version} is available.")
                }
            }
            // Asked for on purpose, so a version hidden with "Not now" is offered again.
            if (update != null) prefs.updateDismissed = null
        }
    }

    /**
     * Reads what the cache holds right now into the state, for the account screen.
     */
    fun refreshCacheInfo() {
        viewModelScope.launch {
            val held = withContext(Dispatchers.IO) { searches.cachedCount() }
            _state.update { it.copy(cacheSeconds = prefs.cacheSeconds, cachedCount = held, hapticsEnabled = prefs.hapticsEnabled, lexicalWeight = prefs.lexicalWeight) }
        }
    }

    /**
     * The owner sets how long an answer from the index may be reused. Never below a minute;
     * [AppPrefs.cacheSeconds] keeps it inside what the cache allows.
     */
    /** The semantic / lexical balance of a search by meaning; the next search uses it. */
    fun setLexicalWeight(value: Float) {
        prefs.lexicalWeight = value
        _state.update { it.copy(lexicalWeight = prefs.lexicalWeight) }
    }

    /** The owner's choice about taps you can feel; taken up at once, everywhere. */
    fun setHaptics(on: Boolean) {
        prefs.hapticsEnabled = on
        com.opensolr.photos.ui.Haptics.enabled = on
        _state.update { it.copy(hapticsEnabled = on) }
    }

    fun setCacheSeconds(seconds: Int) {
        prefs.cacheSeconds = seconds
        _state.update { it.copy(cacheSeconds = prefs.cacheSeconds) }
    }

    /**
     * "Clear cache": throws away every answer held on this phone. Nothing leaves the phone and
     * no one else is affected; the next search asks the index again.
     */
    fun clearSearchCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { searches.clearCache() }
            _state.update { it.copy(cachedCount = 0) }
        }
    }

    /**
     * Where a view was left: which row was at the top, how far it had been scrolled past, and
     * which row that was by position.
     *
     * The key matters more than the position (Cip, 2026-09-16): deleting photos, or folding a
     * group away, makes the list shorter, and a remembered row number then points at something
     * else entirely - which is exactly how a delete used to throw the grid somewhere random. The
     * number is only the fallback, for when that row is no longer there at all.
     */
    private data class ScrollAt(val key: String?, val index: Int, val offset: Int)


    /**
     * What the photos on screen answer to: the words searched for, the filters, and whether the
     * grid is showing duplicates or the photos like one photo.
     */
    private fun contextKey(s: UiState): String =
        // Each kind of view is keyed by what actually decides its photos, and by nothing else.
        // The slider stop used to be in the key even for an ordinary search, so moving the slider
        // inside the duplicates view changed the key of the search waiting behind it and losing
        // its place (Cip, 2026-09-16).
        if (s.duplicatesMode) "duplicates|${s.similarToId}|${s.duplicateLevel}"
        else "search|${s.searchedQuery}|${s.filters}|${s.freshBias}|${s.wordsOnly}"

    /**
     * Remembers where the grid stands for the search it is showing: the row at the top by its
     * own key, and its position as a fallback.
     */
    fun rememberGridPosition(key: String?, index: Int, offset: Int) {
        scrollPositions[contextKey(_state.value)] = ScrollAt(key, index, offset)
    }

    /**
     * Folds a group heading away, or opens it again, for the view on screen.
     */
    fun toggleHeading(key: String) {
        val context = contextKey(_state.value)
        val next = (collapsedHeadings[context] ?: defaultCollapsed(_state.value)).let { if (key in it) it - key else it + key }
        collapsedHeadings[context] = next
        prefs.collapsedHeadings = collapsedHeadings
        _state.update { it.copy(collapsedHeadings = next) }
    }

    /**
     * What a view folds away before the owner has touched it: after a typed search "Also
     * similar" starts closed and "Best matches" open (Cip, 2026-09-17); nothing otherwise.
     */
    private fun defaultCollapsed(s: UiState): Set<String> =
        if (!s.duplicatesMode && !s.skippedMode && s.searchedQuery.isNotBlank()) setOf("h:Also similar") else emptySet()

    /**
     * Folds away every heading in [keys] at once, or opens them all when [keys] is empty: the
     * expand all / collapse all button over the grid, for the view on screen only (Cip, 2026-09-17).
     */
    fun setAllHeadings(keys: Set<String>) {
        val context = contextKey(_state.value)
        collapsedHeadings[context] = keys
        prefs.collapsedHeadings = collapsedHeadings
        _state.update { it.copy(collapsedHeadings = keys) }
    }

    /**
     * Hands the screen the place this search was last left at, or the top when it has never been
     * seen. Called whenever the photos on screen change: results arriving, duplicates arriving,
     * and coming back from the map or the albums, where nothing is reloaded.
     */
    private fun targetScroll() {
        val context = contextKey(_state.value)
        val at = scrollPositions[context] ?: ScrollAt(null, 0, 0)
        _state.update {
            it.copy(
                gridKey = at.key,
                gridIndex = at.index,
                gridOffset = at.offset,
                // The folded groups of this view travel with it, so they survive leaving and
                // coming back exactly as the place in the list does.
                collapsedHeadings = collapsedHeadings[context] ?: defaultCollapsed(it),
                restoreGeneration = it.restoreGeneration + 1,
            )
        }
    }

    /**
     * "Back to search" above the slider: leaves the duplicates or similar view and runs the
     * search that was in force again, with its query and filters, which were never cleared.
     */
    fun backToSearch() = clearDuplicates()

    /** "Not now" on the update notice: hides it until a newer version than this one appears. */
    fun dismissUpdate() {
        prefs.updateDismissed = _state.value.update?.version
        _state.update { it.copy(update = null) }
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
    fun saveEdits(hit: PhotoHit, tags: List<String>, meaning: String?, persons: List<String>? = null, onDone: () -> Unit) {
        _state.update { it.copy(editSaving = true, editError = null) }
        viewModelScope.launch {
            try {
                val doc = edits.save(hit.id, tags, meaning, persons)
                // The index just changed: no cached answer may outlive the owner's own edit.
                searches.clearCache()
                val updated = hit.copy(
                    meaning = doc.optString("meaning"),
                    persons = doc.optString("persons_t"),
                    customTags = doc.optJSONArray("custom_tags")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                )
                _state.update { s -> s.copy(editSaving = false, hits = s.hits.map { if (it.id == hit.id) updated else it }) }
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(editSaving = false, editError = friendlyMessage(e, "The edit could not be saved.")) }
            }
        }
    }

    /**
     * The photos the tagging button works on: the ticked ones, or every photo the view shows
     * when nothing is ticked (Cip, 2026-09-16).
     */
    fun photosToTag(): List<PhotoHit> {
        val state = _state.value
        return if (state.selectedIds.isEmpty()) state.hits else state.hits.filter { it.id in state.selectedIds }
    }

    /**
     * Puts [tags] on those photos, keeping the tags each already has and leaving what each photo
     * shows untouched.
     *
     * Never the whole index: only the photos named above. The work carries on in the background
     * after the sheet is closed - hundreds of photos take a while, and nothing is gained by
     * making the owner watch (Cip, 2026-09-16). Written in batches, so it costs one AI request
     * per batch rather than one per photo.
     */
    fun tagPhotos(tags: List<String>, persons: List<String> = emptyList(), writeFiles: Boolean = false) {
        // One at a time: a second run started over the first would write the same photos twice
        // and the count on screen would mean nothing.
        if (_state.value.bulkTagging) return
        val targets = photosToTag()
        val ids = targets.map { it.id }
        val clean = tags.distinctWords()
        val names = persons.distinctWords()
        if (ids.isEmpty() || (clean.isEmpty() && names.isEmpty())) return
        _state.update { it.copy(bulkTagging = true, bulkTagError = null, bulkTagDone = 0, bulkTagTotal = ids.size) }
        viewModelScope.launch {
            try {
                edits.addTagsToAll(ids, clean, names) { done, total ->
                    _state.update { it.copy(bulkTagDone = done, bulkTagTotal = total) }
                }
                // The index just changed: no cached answer may outlive the owner's own tags.
                searches.clearCache()
                // The tags go into each photo file too, on the phone, added to the keywords it
                // already carries (Cip, 2026-09-17). Only when Android allowed the change.
                if (writeFiles) {
                    withContext(Dispatchers.IO) {
                        targets.forEach { hit ->
                            Actions.contentUris(context, listOf(hit)).firstOrNull()?.let { uri ->
                                val keepTags = if (clean.isEmpty()) null else (PhotoReader.tagsIn(context, uri) + hit.customTags + clean).distinctWords()
                                val keepNames = if (names.isEmpty()) null else (hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() } + names).distinctWords()
                                // No wording field on this form: the owner's own wording, when this
                                // phone keeps one, goes into the file with the tags; none, nothing
                                // is touched (Cip, 2026-09-17).
                                PhotoReader.writeXmp(context, uri, hit.mime, keepNames, keepTags, photoCache.getEdits(hit.id)?.meaning)
                            }
                        }
                    }
                }
                // The photos on screen carry the new tags without asking the index again.
                val tagged = ids.toSet()
                _state.update { s ->
                    s.copy(
                        bulkTagging = false,
                        selecting = false,
                        selectedIds = emptySet(),
                        hits = s.hits.map { hit ->
                            if (hit.id !in tagged) hit else hit.copy(
                                customTags = (hit.customTags + clean).distinctWords(),
                                persons = if (names.isEmpty()) hit.persons else (hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() } + names).distinctWords().joinToString(", "),
                            )
                        },
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(bulkTagging = false, bulkTagError = friendlyMessage(e, "The tags could not be saved.")) }
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
                _state.update { it.copy(busy = false, screen = Screen.SignIn, signInError = friendlyMessage(e, "Sign-in failed. Tap Sign in again.")) }
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
            val next = if (path in it.selectedFolders) {
                it.selectedFolders - path
            } else {
                // A chosen folder already covers everything under it (MediaScanner matches on
                // the prefix), so the folders it swallows are dropped instead of kept alongside.
                it.selectedFolders.filterNot { chosen -> chosen.startsWith(path, ignoreCase = true) }.toSet() + path
            }
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
                val (connection, outcome) = indexes.ensure(session) { step -> _state.update { it.copy(setupStep = step) } }
                if (outcome == IndexManager.Outcome.NEEDS_CHOICE) {
                    // The owner says which phone this is before anything is created.
                    _state.update { it.copy(deviceChoices = indexes.choices, setupStep = "Which one of these is your device?") }
                    return@launch
                }
                SyncScheduler.applySchedule(context, prefs.schedule)
                SyncScheduler.watchMedia(context)
                SyncScheduler.runNow(context)
                _state.update { it.copy(screen = Screen.Search, indexName = connection.indexName, environment = connection.environment) }
                refreshAccount()
                search(reset = true)
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: IndexLimitException) {
                _state.update { it.copy(setupError = friendlyMessage(e, "The index could not be set up."), setupNeedsUpgrade = true) }
            } catch (e: Exception) {
                _state.update { it.copy(setupError = friendlyMessage(e, "The index could not be set up.")) }
            }
        }
    }

    /**
     * A sync found that this phone has no index while the account has photo indexes: fetch
     * them and ask which one is this device.
     */
    private fun askDeviceChoice() {
        val session = prefs.session ?: return
        viewModelScope.launch {
            try {
                val others = api.indexes(session).filter { it.isPhotos }
                if (others.isNotEmpty()) _state.update { it.copy(deviceChoices = others) } else chooseNewDevice()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
            }
        }
    }

    /**
     * "This device is that one": the phone carries on with the photos of the chosen index.
     */
    fun chooseDevice(index: AccountIndex) {
        prefs.chosenIndexName = index.name
        prefs.connection = null
        _state.update { it.copy(deviceChoices = emptyList(), screen = Screen.Setup) }
        runSetup()
    }

    /**
     * "None of these": a new index for this phone.
     */
    fun chooseNewDevice() {
        prefs.chosenIndexName = indexes.ownIndexName
        prefs.connection = null
        _state.update { it.copy(deviceChoices = emptyList(), screen = Screen.Setup) }
        runSetup()
    }

    /**
     * Updates the search text without searching yet.
     */
    /** Names already in the index for the People field; empty when the index cannot be asked. */
    suspend fun personSuggestions(typed: String, onPhoto: Collection<String>): List<String> =
        try {
            searches.personSuggestions(typed, onPhoto)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }

    /**
     * Suggestions for the tag field of the edit sheet: the owner's tags, then words from the
     * photos' meanings, matching [typed]. Empty when the index cannot be asked right now; a
     * suggestion list must never get in the way of tagging.
     */
    suspend fun tagSuggestions(typed: String, onPhoto: Collection<String>): com.opensolr.photos.search.TagSuggestions =
        try {
            searches.tagSuggestions(typed, onPhoto)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            com.opensolr.photos.search.TagSuggestions(emptyList(), emptyList())
        }

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
     *
     * [keepPosition] reloads the same search without counting as a new one: the generation
     * counters stay put, so the grid is not thrown back to the top. Coming back from the account
     * or the Sync screen reloads the photos this way, and landing at the top there was exactly
     * the bug (Cip, 2026-09-16). A search the owner starts - Enter, a suggestion, a filter, an
     * album - is a new one and does begin at the top.
     */
    fun search(reset: Boolean, keepPosition: Boolean = false) {
        val current = _state.value
        // Scrolling to the end of the duplicates asks for the next groups, not for another page
        // of a search: the grid is the same, what fills it is not.
        if (!reset && current.duplicatesMode) { loadMoreDuplicates(); return }
        if (!reset && current.skippedMode) return
        if (!reset && (current.searching || current.endReached)) return
        searchJob?.cancel()
        val start = if (reset) 0 else current.hits.size
        suggestJob?.cancel()
        // Leaving the duplicates view drops the photo it was anchored to as well: the line above
        // the grid and the way back read the anchor, not the mode (Cip, 2026-09-16).
        _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList(), duplicateGroups = emptyList(), duplicatesMode = false, skippedMode = false, similarToId = null, similarToHit = null, searchGeneration = if (reset && !keepPosition) it.searchGeneration + 1 else it.searchGeneration) }
        // The suggestions come from their own words-only request, next to this one.
        if (reset) loadQueryFacets(current.query, current.filters)
        searchJob = viewModelScope.launch {
            try {
                val page = searches.search(current.query, current.filters, start, freshBias = current.freshBias, wordsOnly = current.wordsOnly)
                _state.update {
                    // A photo already on the grid is never added twice. Consecutive pages can
                    // overlap when several photos share the value being sorted on, and the grid
                    // keys its items by photo id: a repeat used to crash the app the moment it
                    // was drawn, which is what fast scrolling produced (Cip, 2026-09-16).
                    val hits = if (reset) {
                        page.hits
                    } else {
                        val seen = it.hits.mapTo(HashSet()) { hit -> hit.id }
                        it.hits + page.hits.filter { hit -> seen.add(hit.id) }
                    }
                    it.copy(
                        searching = false,
                        hits = hits,
                        numFound = page.numFound,
                        facets = if (reset || it.facets.isEmpty()) page.facets else it.facets,
                        smart = page.smart,
                        searchNotice = page.notice,
                        didYouMean = if (reset) page.didYouMean else it.didYouMean,
                        resultsGeneration = if (reset && !keepPosition) it.resultsGeneration + 1 else it.resultsGeneration,
                        searchedQuery = if (reset) current.query else it.searchedQuery,
                        endReached = hits.size >= page.numFound || page.hits.isEmpty(),
                    )
                }
                // Only a first page lands somewhere; the next pages must not move the grid.
                if (reset) targetScroll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, "Search failed.")) }
            }
        }
    }

    /**
     * Opens the albums screen and loads the albums, one request to the index, every time it
     * opens: a sync may have added photos since. [force] is the swipe-down gesture: the owner
     * is asking the index itself, so whatever is held for it is dropped first.
     */
    fun openAlbums(force: Boolean = false) {
        _state.update { it.copy(screen = Screen.Albums, albumsLoading = true, albumsError = null) }
        viewModelScope.launch {
            try {
                if (force) withContext(Dispatchers.IO) { searches.clearCache() }
                val sections = searches.albums()
                _state.update { it.copy(albums = sections, albumsLoading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(albumsLoading = false, albumsError = friendlyMessage(e, "The albums could not be loaded.")) }
            }
        }
    }

    /** Picks or unpicks one album on the albums screen. */
    fun toggleAlbumSelected(album: com.opensolr.photos.search.Album) {
        val key = "${album.field}:${album.value}"
        _state.update { it.copy(selectedAlbums = if (key in it.selectedAlbums) it.selectedAlbums - key else it.selectedAlbums + key) }
    }

    /** Picks or unpicks a whole album section on the albums screen. */
    fun toggleSectionSelected(title: String) {
        _state.update { it.copy(selectedSections = if (title in it.selectedSections) it.selectedSections - title else it.selectedSections + title) }
    }

    /**
     * Ticks every photo the view has loaded (a search, an album, the duplicates), or none when
     * [all] is false, so a whole result set can be tagged at once (Cip, 2026-09-17).
     */
    fun selectAllPhotos(all: Boolean) {
        _state.update {
            if (all) it.copy(selecting = true, selectedIds = it.hits.map { h -> h.id }.toSet())
            else it.copy(selecting = false, selectedIds = emptySet())
        }
    }

    /** Picks every album section at once, or none when [all] is false. */
    fun selectAllAlbums(all: Boolean) {
        _state.update {
            it.copy(selectedAlbums = emptySet(), selectedSections = if (all) it.albums.map { s -> s.title }.toSet() else emptySet())
        }
    }

    /** Leaves album selection. */
    fun clearAlbumSelection() {
        _state.update { it.copy(selectedAlbums = emptySet(), selectedSections = emptySet()) }
    }

    /**
     * The photos of everything picked on the albums screen (whole sections and single albums),
     * looked up in the index and handed to [then] on the main thread.
     */
    fun withSelectedAlbumPhotos(then: (List<PhotoHit>) -> Unit) {
        val s = _state.value
        val albums = s.albums.flatMap { section ->
            if (section.title in s.selectedSections) section.albums
            else section.albums.filter { "${it.field}:${it.value}" in s.selectedAlbums }
        }.distinctBy { "${it.field}:${it.value}" }
        if (albums.isEmpty()) return
        _state.update { it.copy(albumsWorking = true) }
        viewModelScope.launch {
            try {
                val photos = searches.albumPhotos(albums)
                _state.update { it.copy(albumsWorking = false) }
                then(photos)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(albumsWorking = false, albumsError = friendlyMessage(e, "The photos of those albums could not be looked up.")) }
            }
        }
    }

    /**
     * The photos of picked albums were deleted from the phone: they go from the index too, and
     * the albums are asked for again.
     */
    fun albumPhotosDeleted(ids: Set<String>) {
        clearAlbumSelection()
        removeDeleted(ids)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            openAlbums(force = true)
        }
    }

    /**
     * Opens one album: the photos grid with that album's filter alone, nothing typed, nothing
     * else filtered. The filter shows as a pill with its cross, like any other.
     */
    fun openAlbum(album: com.opensolr.photos.search.Album) {
        _state.update {
            it.copy(
                screen = Screen.Search,
                query = "",
                filters = SearchFilters().toggled(album.field, album.value),
                selecting = false,
                selectedIds = emptySet(),
                duplicateGroups = emptyList(),
                duplicatesMode = false,
                similarToId = null,
                similarToHit = null,
            )
        }
        search(reset = true)
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
                _state.update { it.copy(pinsLoading = false, pinsError = friendlyMessage(e, "The map could not be loaded.")) }
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
     * Force Re-Sync: stops whatever is queued, waiting or running and starts a sync now.
     *
     * It used to refuse while anything was queued or running and ask the owner to be patient -
     * which is the one moment it is needed, because a run stuck waiting for conditions it can
     * never meet leaves no other way out (Cip, 2026-09-16). Only a run that is actually working
     * is protected: interrupting it mid-flight would waste what it has paid for.
     */
    fun forceResync() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        SyncScheduler.restartNow(context)
    }

    /**
     * "Re-read all photos" (Cip, 2026-09-17): every photo goes through Opensolr again, as if
     * picked for Re-sync, without emptying the index and without touching a file. The words,
     * places, people and vectors are made again with whatever Opensolr does now; the owner's
     * tags and wording are kept (from the phone, the files, or the index). Resumable: only
     * photos written before now are read, so a stopped run carries on where it was.
     */
    fun rereadAll() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        // A few minutes back, so a phone clock slightly ahead of the server's never makes a
        // photo just written look old again.
        prefs.rereadAllSince = System.currentTimeMillis() - 10 * 60 * 1000L
        SyncScheduler.restartNow(context)
    }

    /**
     * Empties the index and syncs from nothing: the only way, from the phone, to have every
     * photo read again after Opensolr got better at reading them (Cip, 2026-09-15 - otherwise
     * a person stays on the old words for good, short of resetting the index on the website).
     * The photos themselves and the tags the owner wrote are untouched; the words, the places
     * and the vectors are made again, which counts as new AI requests.
     */
    fun resetIndex() {
        // Only a run that is actually working stops this; one stuck in the queue must not, or
        // there is no way out of it (Cip, 2026-09-16).
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        viewModelScope.launch {
            try {
                val connection = prefs.connection ?: return@launch
                com.opensolr.photos.net.SolrClient(connection).deleteAll()
                // Nothing cached describes the index any more: it is empty.
                searches.clearCache()
            } catch (e: Exception) {
                _state.update { it.copy(notice = "The index could not be emptied: ${friendlyMessage(e, "try again")}") }
                return@launch
            }
            // No search follows this one, so the anchor has to go with the mode here.
            _state.update { it.copy(hits = emptyList(), numFound = 0, duplicateGroups = emptyList(), duplicatesMode = false, similarToId = null, similarToHit = null) }
            SyncScheduler.runNow(context)
        }
    }

    /**
     * Reads the documents again: every photo that carries printed text is dropped from the index
     * and a sync started, which finds them missing and puts them back (Cip, 2026-09-16).
     *
     * Costs nothing on the plan. What the reader and the models said about a photo is kept
     * against the picture itself, so a photo read once is never read again - the rebuild simply
     * writes the documents afresh, with the reading cleaned up as it comes out.
     *
     * Tags and wording this phone knows about are put back with them. Tags that live only in the
     * index, added from another phone, go with the documents.
     */
    fun rebuildOcr() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        viewModelScope.launch {
            val gone = try {
                val connection = prefs.connection ?: return@launch
                val count = com.opensolr.photos.net.SolrClient(connection).deleteWithOcr()
                searches.clearCache()
                count
            } catch (e: Exception) {
                _state.update { it.copy(notice = "The documents could not be dropped: ${friendlyMessage(e, "try again")}") }
                return@launch
            }
            _state.update {
                it.copy(notice = if (gone == 0) "No photos with printed text to read again."
                else "$gone photo${if (gone == 1) "" else "s"} with printed text will be read again.")
            }
            if (gone > 0) {
                refresh()
                SyncScheduler.runNow(context)
            }
        }
    }

    /**
     * Stops the sync that is running or waiting. Nothing already written is lost, and the next
     * sync starts on its own as it would have: this is a stop, not a switch-off (Cip, 2026-09-16).
     */
    fun stopSync() {
        // The flag first: WorkManager's cancellation cannot interrupt a batch already uploading,
        // so the run is asked to give up between batches, and only then is the work cancelled.
        com.opensolr.photos.sync.SyncWorker.stopRequested.set(true)
        SyncScheduler.stopNow(context)
        _state.update { it.copy(notice = "Sync stopped. It starts again on its own, or press Force Re-Sync.") }
    }

    /**
     * The next page of duplicate groups, appended as the grid reaches its end. The groups
     * themselves came back in the one facet request the level already made and are held in the
     * cache, so this costs a single request for the photos of the groups it adds.
     */
    private fun loadMoreDuplicates() {
        val current = _state.value
        if (current.searching || current.endReached || current.similarToId != null) return
        val level = current.duplicateLevel
        val from = current.duplicateGroupsLoaded
        _state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            try {
                val page = searches.duplicates(level, groupsFrom = from)
                _state.update {
                    if (!it.duplicatesMode || it.duplicateLevel != level || it.duplicateGroupsLoaded != from) it
                    else it.copy(
                        searching = false,
                        hits = it.hits + page.hits,
                        duplicateGroups = it.duplicateGroups + page.sizes,
                        duplicateGroupsLoaded = from + com.opensolr.photos.search.SearchRepository.GROUPS_PAGE,
                        numFound = (it.hits.size + page.hits.size).toLong(),
                        endReached = page.endReached,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, "Looking for duplicates failed.")) }
            }
        }
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
     * The tick on a heading: selects every photo of that group, or drops them all when they
     * are already selected. Selection switches on by itself, as tapping a photo does.
     */
    fun toggleSelectedGroup(ids: List<String>) {
        if (ids.isEmpty()) return
        _state.update {
            val all = it.selectedIds.containsAll(ids)
            val next = if (all) it.selectedIds - ids.toSet() else it.selectedIds + ids
            it.copy(selecting = true, selectedIds = next)
        }
    }

    /**
     * Duplicates on or off. On, the grid shows the groups of the kind the slider is on
     * ([UiState.duplicateLevel]); tapping it again (or searching) goes back to the ordinary
     * results.
     */
    fun showDuplicates() {
        if (_state.value.duplicatesMode) { clearDuplicates(); return }
        // The duplicates icon always means the whole index, never one photo's neighbours.
        _state.update { it.copy(duplicatesMode = true, skippedMode = false, similarToId = null, similarToHit = null) }
        loadDuplicates(debounceMs = 0)
    }

    /**
     * "Show similar photos" in a photo's details: the same slider, anchored to [hit] instead of
     * the whole index, so every stop asks what else carries this photo's key.
     */
    fun showSimilar(hit: PhotoHit) {
        _state.update {
            // Starts at the loosest stop (the same first word): anchored to one photo, the point
            // is to see anything like it and tighten from there (Cip, 2026-09-16).
            it.copy(screen = Screen.Search, duplicatesMode = true, duplicateLevel = 0, similarToId = hit.id, similarToHit = hit)
        }
        loadDuplicates(debounceMs = 0)
    }

    /**
     * The slider moved to [level] (0..10): the groups of that kind are asked for a short pause
     * after the last move, so dragging across the slider does not send a request per step.
     */
    fun setDuplicateLevel(level: Int) {
        val clamped = level.coerceIn(0, com.opensolr.photos.search.SearchRepository.DUPLICATE_FIELDS.size - 1)
        if (clamped == _state.value.duplicateLevel && _state.value.duplicateGroups.isNotEmpty()) return
        _state.update { it.copy(duplicateLevel = clamped) }
        if (_state.value.duplicatesMode) loadDuplicates(debounceMs = 300)
    }

    /**
     * One facet request for the groups of the current kind, replacing whatever is on the grid.
     */
    private fun loadDuplicates(debounceMs: Long) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            val level = _state.value.duplicateLevel
            val anchor = _state.value.similarToId
            _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList()) }
            try {
                // Anchored to a photo, or over the whole index: the same slider, the same grid.
                // Anchored to one photo the whole answer is small; over the index it arrives a
                // page of groups at a time.
                val hits: List<PhotoHit>
                val groups: List<Int>
                val loaded: Int
                val done: Boolean
                val total: Int
                if (anchor != null) {
                    val (h, g) = searches.similarTo(anchor, level)
                    hits = h; groups = g; loaded = g.size; done = true; total = g.size
                } else {
                    val page = searches.duplicates(level, groupsFrom = 0)
                    hits = page.hits; groups = page.sizes
                    loaded = com.opensolr.photos.search.SearchRepository.GROUPS_PAGE
                    done = page.endReached; total = page.totalGroups
                }
                _state.update {
                    if (!it.duplicatesMode || it.duplicateLevel != level || it.similarToId != anchor) it
                    else it.copy(
                        searching = false,
                        hits = hits,
                        duplicateGroups = groups,
                        duplicateGroupsLoaded = loaded,
                        duplicateGroupsTotal = total,
                        numFound = hits.size.toLong(),
                        endReached = done,
                        searchNotice = when {
                            hits.isNotEmpty() -> null
                            anchor != null -> "Nothing else in your index is like this photo at this setting."
                            else -> "No duplicates of this kind in your index."
                        },
                        resultsGeneration = it.resultsGeneration + 1,
                    )
                }
                // Duplicates and the similar photos are searches of their own, each with its own
                // place: coming back to a stop already looked at returns to where it was left.
                targetScroll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut("Your Opensolr sign-in stopped working. Sign in again.")
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, "Looking for duplicates failed.")) }
            }
        }
    }

    /**
     * "Select 1 of each duplicate": selection on, with one photo of every group ticked - the
     * last of each group, so the first stays - for the owner to review before sharing,
     * deleting or re-syncing them from the selection dock.
     */
    fun selectOneOfEachDuplicate() {
        val s = _state.value
        if (!s.duplicatesMode || s.duplicateGroups.isEmpty()) return
        val picked = LinkedHashSet<String>()
        var at = 0
        for (size in s.duplicateGroups) {
            if (size >= 2 && at + size <= s.hits.size) picked += s.hits[at + size - 1].id
            at += size
        }
        _state.update { it.copy(selecting = true, selectedIds = picked) }
    }

    /**
     * A reload of what the grid shows (swipe down, the reload button, a finished sync, coming
     * back to the photos): while duplicates are on, the duplicates of the slider's current kind
     * again, never the ordinary results (Cip, 2026-09-16); otherwise the search.
     */
    fun refresh() {
        // A reload of the same search, so the grid stays where the owner left it.
        when {
            _state.value.skippedMode -> loadSkipped()
            _state.value.duplicatesMode -> loadDuplicates(debounceMs = 0)
            else -> search(reset = true, keepPosition = true)
        }
    }

    /**
     * Swipe down on the grid: the owner is asking the index itself, not the phone, so every
     * held answer goes before the results are loaded again.
     */
    fun forceRefresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { searches.clearCache() }
            _state.update { it.copy(cachedCount = 0) }
            refresh()
        }
    }

    /** Back to the ordinary results after looking at duplicates. */
    private fun clearDuplicates() {
        if (!_state.value.duplicatesMode) return
        _state.update { it.copy(duplicatesMode = false, duplicateGroups = emptyList(), similarToId = null, similarToHit = null, searchNotice = null) }
        search(reset = true)
    }

    /**
     * "Fresh" on the results: recency boosts the score instead of replacing the order, so the
     * matches stay the same and the recent ones rise. Runs the search again.
     */
    fun setFreshBias(on: Boolean) {
        if (_state.value.freshBias == on) return
        _state.update { it.copy(freshBias = on) }
        search(reset = true)
    }

    /**
     * The AI switch: off leaves the vector leg out and the search matches words only. Runs the
     * search again, since it is the query itself that changes.
     */
    fun setWordsOnly(on: Boolean) {
        if (_state.value.wordsOnly == on) return
        _state.update { it.copy(wordsOnly = on) }
        search(reset = true)
    }

    /**
     * The words-only facets behind the suggestions, fetched next to a fresh search and dropped
     * as soon as the box is empty (browsing uses the facets of the results themselves).
     */
    private fun loadQueryFacets(query: String, filters: SearchFilters) {
        facetsJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(queryFacets = emptyMap()) }
            return
        }
        facetsJob = viewModelScope.launch {
            val facets = searches.queryFacets(query, filters)
            _state.update { if (it.query == query) it.copy(queryFacets = facets) else it }
        }
    }

    /**
     * Drops from the results the photos the phone no longer has, after a delete went through,
     * and takes them out of the index as well.
     */
    fun removeDeleted(ids: Set<String>) {
        if (ids.isEmpty()) return
        _state.update {
            it.copy(
                hits = it.hits.filterNot { hit -> hit.id in ids },
                selectedIds = it.selectedIds - ids,
                selecting = false,
                numFound = (it.numFound - ids.size).coerceAtLeast(0),
            )
        }
        viewModelScope.launch {
            try {
                val connection = prefs.connection ?: return@launch
                // Committed on the spot: with the usual ten-second commit the photo came
                // straight back on the next refresh (Cip, 2026-09-15).
                com.opensolr.photos.net.SolrClient(connection).delete(ids, now = true)
            } catch (e: Exception) {
                // The next sync notices the photos are gone and deletes them anyway.
            }
            // Deleted photos must not come back from a cached answer.
            searches.clearCache()
            // The index has changed: reload what is on screen from it (duplicates stay duplicates).
            if (_state.value.screen == Screen.Search) refresh()
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
        // As above: a run that is working is left alone, a run stuck in the queue is replaced.
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        SyncScheduler.restartNow(context)
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
                _state.update { it.copy(accountRefreshing = false, accountError = friendlyMessage(e, "The plan limits could not be read.")) }
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
            // Nothing is reloaded on the way back from these, so the grid is put back by hand.
            Screen.Map, Screen.Albums -> {
                if (current.selectedAlbums.isNotEmpty() || current.selectedSections.isNotEmpty()) {
                    clearAlbumSelection()
                    return
                }
                _state.update { it.copy(screen = Screen.Search) }
                targetScroll()
            }
            // Back to the photos always reloads them: a sync may have finished meanwhile.
            Screen.Sync, Screen.Account -> {
                _state.update { it.copy(screen = Screen.Search) }
                // Duplicates stay duplicates, on the same slider stop.
                refresh()
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
        // Only a sync that actually wrote something makes the held answers wrong. Almost every
        // sync of an ordinary day writes nothing at all - it wakes on a photo somewhere, finds
        // the index already in step, and ends - and emptying the cache on those defeated the
        // whole point of having one (Cip, 2026-09-16). The owner can still empty it by hand,
        // from Me.
        if (report == null || report.added > 0 || report.deleted > 0) {
            searches.clearCache()
        }
        _state.update { it.copy(lastReport = report, account = prefs.account, planWarnings = prefs.account?.let { a -> PlanWatch.evaluate(a) } ?: emptyList(), indexName = prefs.connection?.indexName, environment = prefs.connection?.environment) }
        if (report?.status == "sign_in_required") {
            signedOut(prefs.pendingNotice ?: "Your Opensolr sign-in stopped working. Sign in again.")
            prefs.pendingNotice = null
            return
        }
        if (report?.status == "rebuild_required") _state.update { it.copy(rebuildRequired = true) }
        if (report?.status == "device_choice") askDeviceChoice()
        if (report?.status == "update_app") _state.update { it.copy(notice = report.message) }
        refreshSkippedCount()
        if (_state.value.screen == Screen.Search) refresh()
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
        /** How often the latest release is asked for. */
        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private val CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")
    }
}
