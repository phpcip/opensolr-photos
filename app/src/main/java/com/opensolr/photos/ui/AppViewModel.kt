package com.opensolr.photos.ui

import com.opensolr.photos.R
import com.opensolr.photos.AppText
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
import kotlinx.coroutines.sync.withLock
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
enum class Screen { SignIn, Welcome, Permissions, Folders, Setup, Search, Sync, Account, Map, Albums, Stats }

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
    /**
     * Photos that are ticked but not on the grid, because a whole group was ticked and the grid
     * only ever holds the pages that were scrolled to. Enough of each to open the file it came
     * from, so tagging, sharing and deleting reach them all the same.
     */
    val selectedOffscreen: Map<String, PhotoHit> = emptyMap(),
    /** True while a whole group's photos are being looked up after its tick was pressed. */
    val selectingGroup: Boolean = false,
    /**
     * The groups ticked whole, by heading key, with the ids each took in. A group can hold photos
     * the grid never loaded, so "is this group ticked?" cannot be answered by looking at what is on
     * screen (Cip, 2026-09-18).
     */
    val selectedGroups: Map<String, Set<String>> = emptyMap(),
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
    /** New photos given the phone's position whose files do not carry it yet. */
    val placesToWrite: Int = 0,
    /** True once Android's question about writing those positions was answered no, this session. */
    val placesDeclined: Boolean = false,
    /** Whether new photos that come without a position get the phone's own. */
    val autoPlace: Boolean = false,
    /** Whether the owner let the app know the phone's position at all. */
    val autoPlaceLocation: Boolean = false,
    /** Whether the owner let the app know the phone's position while it is in the background. */
    val autoPlaceBackground: Boolean = false,
    /** The duplicates slider, a stop of SearchRepository.DUPLICATE_FIELDS; 2 = all five words the same. */
    val duplicateLevel: Int = com.opensolr.photos.search.SearchRepository.DEFAULT_DUPLICATE_LEVEL,
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
    /**
     * The whole library laid out in groups - every year, month and day with how many photos it
     * holds - worked out from the phone's own copy of the index, so browsing shows the shape of
     * the library at once instead of only the groups the first page happened to reach
     * (Cip, 2026-09-18). Empty for a search, a filter or the duplicates: there the groups can only
     * come from the results.
     */
    val skeleton: List<com.opensolr.photos.ui.DateGroup> = emptyList(),
    /** The zones of Me that are open; all start folded and stay as left while the app runs. */
    val meZonesOpen: Set<String> = emptySet(),
    /** The sections of Stats that are folded; all start open and stay as left while the app runs. */
    val statsFolded: Set<String> = emptySet(),
    /** True while the grid shows a line opened from Stats: Back goes to Stats, where it was. */
    val returnToStats: Boolean = false,
    /** How a search's results are laid out, as the owner last chose (Cip, 2026-09-19). */
    val groupBy: GroupBy = GroupBy.RELEVANCE,
    /** A search laid out by [groupBy]: every group with all its photos' ids; empty otherwise. */
    val resultGroups: List<ResultGroup> = emptyList(),
    /** True while the photos of a group the owner has just opened are being fetched. */
    val loadingGroup: Boolean = false,
    /** Which groups of the filter sheet are open; all of them start folded (Cip, 2026-09-18). */
    val openFilterSections: Set<String> = emptySet(),
    /** Which sections of the albums screen are folded away, kept between visits. */
    val foldedAlbumSections: Set<String> = emptySet(),
    /** Bumped whenever the grid has somewhere to be taken to, so the screen scrolls exactly once. */
    val restoreGeneration: Int = 0,
    val editSaving: Boolean = false,
    val editError: String? = null,
    /** True while the owner's tags are being written into the photo files themselves. */
    val bulkTagging: Boolean = false,
    /** How far that has got: files written, and how many there are. */
    val bulkTagDone: Int = 0,
    val bulkTagTotal: Int = 0,
    /** What is in the selected photos already: the names and tags, most used first. */
    val selectionPersons: List<FacetValue> = emptyList(),
    val selectionTags: List<FacetValue> = emptyList(),
    /** True while those two are being read for the photos that are ticked. */
    val selectionWordsLoading: Boolean = false,
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
    /** The Stats screen: the library in numbers, null until first worked out. */
    val stats: com.opensolr.photos.data.LibraryStats? = null,
    val statsLoading: Boolean = false,
    /** True while the phone's copy of the index is not fully read, so the numbers are partial. */
    val statsPartial: Boolean = false,
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
    /**
     * A line that says what just happened and then goes by itself ("Saved. Your index is being
     * updated in the background."). Never a warning: those live in Me (Cip, 2026-09-17).
     */
    val flash: String? = null,
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
    private val photoCache = com.opensolr.photos.data.PhotoCache.of(application)

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

    /** Waits for a burst of photo changes to settle before one sync is asked for. */
    private var mediaChangeJob: Job? = null

    /** The pictures that changed while [mediaChangeJob] waited, to tell whether any is in a chosen folder. */
    private val changedUris = LinkedHashSet<Uri>()

    /** A photo changed while a sync was already running: one more runs when that one ends. */
    private var mediaChangedDuringSync = false

    /**
     * Listens to the phone's pictures for as long as the app is alive, open or in the
     * background (Cip, 2026-09-18). The WorkManager watch alone fires a minute after a change
     * at best, and then holds back for 15 minutes after any sync, so a photo edited in the
     * gallery was not seen on coming back to the app until a sync was run by hand.
     */
    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged(listOfNotNull(uri))
        }

        override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
            onMediaChanged(uris)
        }
    }

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
        refreshPlacesToWrite()
        ensureClone()
        context.contentResolver.registerContentObserver(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
    }

    /**
     * Stops listening to the phone's pictures when the app goes away for good.
     */
    override fun onCleared() {
        context.contentResolver.unregisterContentObserver(mediaObserver)
        super.onCleared()
    }

    /**
     * A picture on the phone was added, changed or deleted. Once the burst settles (an editor
     * saving a file reports several changes in a row), a sync is started only when a change is
     * in a folder the owner chose: a screenshot or a chat picture elsewhere starts nothing.
     */
    private fun onMediaChanged(uris: Collection<Uri>) {
        if (prefs.session == null || prefs.connection == null) return
        changedUris.addAll(uris)
        mediaChangeJob?.cancel()
        mediaChangeJob = viewModelScope.launch {
            kotlinx.coroutines.delay(MEDIA_SETTLE_MS)
            val batch = changedUris.toList()
            changedUris.clear()
            val relevant = withContext(Dispatchers.IO) { MediaScanner.touchesFolders(context, batch, prefs.folders, prefs.folderStamp) }
            if (relevant) syncForChange()
        }
    }

    /**
     * The app came to the front. If the process was gone meanwhile, nothing was listening to the
     * pictures, so the chosen folders are compared with how the last sync left them - one
     * MediaStore query - and a sync runs only when they differ.
     */
    fun onAppResumed() {
        if (prefs.session == null || prefs.connection == null) return
        viewModelScope.launch {
            val stamp = withContext(Dispatchers.IO) { MediaScanner.folderStamp(context, prefs.folders) }
            if (stamp != null && stamp != prefs.folderStamp) syncForChange()
        }
    }

    /**
     * Starts a sync for a change in the chosen folders, or queues one more after the sync that
     * is running now, which may have read the folders before the change.
     */
    private fun syncForChange() {
        if (_state.value.sync.running) mediaChangedDuringSync = true else SyncScheduler.runNow(context)
    }

    /**
     * At start, the phone makes sure it has its own copy of the index: without one the tag and
     * name suggestions have nothing to offer and the first sync would have to fetch it anyway.
     * Read once, in the background; after that it is kept in step by every write.
     */
    private fun ensureClone() {
        if (prefs.session == null || prefs.connection == null) return
        viewModelScope.launch {
            try {
                if (!withContext(Dispatchers.IO) { edits.cloneMissing() }) return@launch
                edits.readIndexIntoCache()
                // The grid was drawn before the copy existed, so it is drawn again now that it does.
                if (_state.value.screen == Screen.Search) search(reset = true, keepPosition = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next sync reads it instead; nothing on screen depends on it being there.
            }
        }
    }

    /**
     * Reads how many photos the phone could not read, for the button over the grid. Leaves the
     * skipped view when there are none left.
     */
    /** Counts the positions still to be written into their files, and what the owner allowed. */
    fun refreshPlacesToWrite() {
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) { photoCache.unwrittenPlaces().size }
            _state.update {
                it.copy(
                    placesToWrite = count,
                    autoPlace = prefs.autoPlaceSince > 0,
                    autoPlaceLocation = com.opensolr.photos.media.DevicePlace.permitted(context),
                    autoPlaceBackground = com.opensolr.photos.media.DevicePlace.permittedInBackground(context),
                )
            }
        }
    }

    /**
     * New photos that come without a position get one from now on, or no longer (Cip, 2026-09-19).
     * Only files that appear from this moment on are ever given one. Taking it from the phone's own
     * photos needs no permission; the phone's position at the moment of import needs location
     * access, which the screen asks for alongside.
     */
    fun setAutoPlace(on: Boolean) {
        if (on != (prefs.autoPlaceSince > 0)) prefs.autoPlaceSince = if (on) System.currentTimeMillis() else 0L
        refreshPlacesToWrite()
    }

    /** The photos and files of [pendingPlaceWrite], held while Android asks the owner. */
    private var pendingPlaceWrite: List<Triple<String, android.net.Uri, PhotoCache.SetPlace>> = emptyList()

    /**
     * Gets the positions given to new photos ready to go into their files: the files are found,
     * and Android's question for all of them at once is returned, for the screen to ask. Null when
     * there is nothing to write, or when this Android lets the app write without asking (it then
     * already has).
     */
    suspend fun preparePlaceWrite(): android.content.IntentSender? = withContext(Dispatchers.IO) {
        val places = photoCache.unwrittenPlaces()
        if (places.isEmpty()) return@withContext null
        val hits = photoCache.docsByIds(places.map { it.id }).map { searches.hitOf(org.json.JSONObject(it)) }
        val uris = Actions.contentUrisByPhoto(context, hits)
        val byId = places.associateBy { it.id }
        pendingPlaceWrite = hits.mapNotNull { hit ->
            val uri = uris[hit.id] ?: return@mapNotNull null
            byId[hit.id]?.let { Triple(hit.mime, uri, it) }
        }
        if (pendingPlaceWrite.isEmpty()) return@withContext null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            writePendingPlaces()
            return@withContext null
        }
        android.provider.MediaStore.createWriteRequest(context.contentResolver, pendingPlaceWrite.map { it.second }).intentSender
    }

    /** Android's answer to [preparePlaceWrite]: yes writes the positions, no is not asked again this session. */
    fun finishPlaceWrite(allowed: Boolean) {
        if (!allowed) {
            pendingPlaceWrite = emptyList()
            _state.update { it.copy(placesDeclined = true) }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { writePendingPlaces() }
            refreshPlacesToWrite()
        }
    }

    /**
     * Writes each held position into its file. The file is then a different file, so the phone's
     * copy takes its new size, time and md5: the index already has the position, and the sync must
     * not read the picture again over it.
     */
    private fun writePendingPlaces() {
        val done = ArrayList<String>(pendingPlaceWrite.size)
        pendingPlaceWrite.forEach { (mime, uri, place) ->
            val writable = PhotoReader.canWriteExif(mime)
            if (writable && PhotoReader.writeGps(context, uri, mime, place.lat, place.lon)) {
                val (size, modified) = fileStampOf(uri)
                photoCache.updateDocSize(place.id, size, modified, fileHashOf(uri))
                done += place.id
            } else if (!writable) {
                done += place.id
            }
        }
        photoCache.markPlacesWritten(done)
        pendingPlaceWrite = emptyList()
    }

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
                    outcome.isFailure -> it.copy(updateChecking = false, updateResult = AppText.s(R.string.vm_update_failed))
                    update == null -> it.copy(updateChecking = false, updateResult = AppText.s(R.string.vm_update_latest))
                    else -> it.copy(updateChecking = false, update = update, updateResult = AppText.s(R.string.vm_update_found, update.version))
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

    /** Which view the photos on screen belong to, to tell coming back from going somewhere new. */
    private var shownContext: String? = null

    /** Groups whose photos are being fetched right now, and the queue that keeps them in order. */
    private val groupsLoading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val groupLoads = kotlinx.coroutines.sync.Mutex()

    /** The word counts of the whole library, and when they were worked out. */
    private var wordCounts: Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>>? = null
    private var wordCountsAt = 0L


    /**
     * What the photos on screen answer to: the words searched for, the filters, and whether the
     * grid is showing duplicates or the photos like one photo.
     */
    private fun contextKey(s: UiState): String {
        // Each kind of view is keyed by what actually decides its photos, and by nothing else.
        // The slider stop used to be in the key even for an ordinary search, so moving the slider
        // inside the duplicates view changed the key of the search waiting behind it and losing
        // its place (Cip, 2026-09-16).
        //
        // Built once per view rather than per scrolled row: the grid reports its position on every
        // frame of a scroll, and the filters print themselves into the key (Cip, 2026-09-18).
        val cached = contextKeyCache
        if (cached != null && cached.first === s.filters && cached.second == s.duplicatesMode &&
            cached.third == "${s.similarToId}|${s.duplicateLevel}|${s.searchedQuery}|${s.freshBias}|${s.wordsOnly}|${s.groupBy}"
        ) {
            return contextKeyValue!!
        }
        val key = if (s.duplicatesMode) "duplicates|${s.similarToId}|${s.duplicateLevel}|${if (s.similarToId != null) s.groupBy else ""}"
        else "search|${s.searchedQuery}|${s.filters}|${s.freshBias}|${s.wordsOnly}|${s.groupBy}"
        contextKeyCache = Triple(s.filters, s.duplicatesMode, "${s.similarToId}|${s.duplicateLevel}|${s.searchedQuery}|${s.freshBias}|${s.wordsOnly}|${s.groupBy}")
        contextKeyValue = key
        return key
    }

    private var contextKeyCache: Triple<SearchFilters, Boolean, String>? = null
    private var contextKeyValue: String? = null

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
     * Opens or folds away one group of the filter sheet, and remembers it: the sheet comes back
     * exactly as it was left, every time (Cip, 2026-09-18).
     */
    fun toggleFilterSection(title: String) {
        val next = _state.value.openFilterSections.let { if (title in it) it - title else it + title }
        prefs.openFilterSections = next
        _state.update { it.copy(openFilterSections = next) }
    }

    /**
     * Folds one section of the albums screen away, or opens it, and remembers it: the screen comes
     * back exactly as it was left (Cip, 2026-09-18).
     */
    fun toggleAlbumSection(title: String) {
        val next = _state.value.foldedAlbumSections.let { if (title in it) it - title else it + title }
        prefs.foldedAlbumSections = next
        _state.update { it.copy(foldedAlbumSections = next) }
    }

    /**
     * Every section of the albums screen folded away at once, or all of them opened.
     */
    fun setAlbumSections(folded: Set<String>) {
        prefs.foldedAlbumSections = folded
        _state.update { it.copy(foldedAlbumSections = folded) }
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
        // A view the owner has just switched INTO - another album, a filter put on or taken off,
        // a new search, another stop of the duplicates slider - opens at the top, because that is
        // what asking for something new means. The remembered place is for coming BACK to the
        // same view: from Me, from Sync, from the picture, or after a sync reloaded it
        // (Cip, 2026-09-18).
        val changed = context != shownContext
        shownContext = context
        val at = if (changed) ScrollAt(null, 0, 0) else scrollPositions[context] ?: ScrollAt(null, 0, 0)
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
                    version > IndexManager.CONFIG_VERSION -> _state.update { it.copy(notice = AppText.s(R.string.vm_newer_index)) }
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
                withContext(Dispatchers.IO) {
                    edits.saveLocal(hit.id, tags, meaning, persons)
                    prefs.facetsJson = null
                    // The words were just written into the file too, so it is a few bytes longer.
                    // The picture is unchanged, and the sync must not read it again over that.
                    Actions.contentUris(context, listOf(hit)).firstOrNull()?.let { uri ->
                        val (size, modified) = fileStampOf(uri)
                        photoCache.updateDocSize(hit.id, size, modified, fileHashOf(uri))
                    }
                }
                // Held answers would still carry the old words, and the photo on screen shows the
                // new ones at once: the index catches up on the next sync, which starts now.
                searches.clearCache()
                val updated = hit.copy(
                    meaning = meaning?.trim()?.takeIf { it.isNotEmpty() } ?: hit.meaning,
                    persons = persons?.joinToString(", ") ?: hit.persons,
                    customTags = tags.distinctWords(),
                )
                _state.update { s ->
                    s.copy(
                        editSaving = false,
                        hits = s.hits.map { if (it.id == hit.id) updated else it },
                    )
                }
                flash(AppText.s(R.string.vm_saved))
                SyncScheduler.runNow(context)
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(editSaving = false, editError = friendlyMessage(e, AppText.s(R.string.vm_edit_failed))) }
            }
        }
    }

    /**
     * The owner put [hits] somewhere else on the map (Cip, 2026-09-19), one photo from its details or
     * many from the tagging sheet. [inFile] are the photos whose own EXIF now carries the position
     * too; the caller wrote it there, with Android's permission, before calling.
     *
     * From here it goes everywhere else, with no picture sent again: the phone keeps the place (so
     * every later reading of a photo carries it, even from a file that could not take it), the
     * phone's copy of each document moves to it, and the words queue takes it up to the index
     * fifty photos a call, where the server names the place and remakes the vector - no CLIP, no
     * OCR, no upload.
     */
    fun setPlace(hits: List<PhotoHit>, lat: Double, lon: Double, inFile: Set<String>) {
        if (hits.isEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uris = if (inFile.isEmpty()) emptyMap() else Actions.contentUrisByPhoto(context, hits.filter { it.id in inFile })
                    // The files read before the transaction, not inside it: an md5 is a read of the
                    // whole file, and the database stays locked for as long as the transaction lasts.
                    val stamps = uris.mapValues { (_, uri) -> fileStampOf(uri).let { (size, modified) -> Triple(size, modified, fileHashOf(uri)) } }
                    photoCache.inTransaction {
                        hits.forEach { hit ->
                            val written = hit.id in inFile || !PhotoReader.canWriteExif(hit.mime)
                            photoCache.putSetPlace(PhotoCache.SetPlace(hit.id, lat, lon, owner = true, written = written, synced = false))
                            photoCache.moveDocPlace(hit.id, lat, lon)
                            stamps[hit.id]?.let { (size, modified, hash) -> photoCache.updateDocSize(hit.id, size, modified, hash) }
                        }
                        photoCache.queueActions(hits.map { it.id }, PhotoCache.ACTION_WORDS)
                    }
                    prefs.facetsJson = null
                }
                searches.clearCache()
                val ids = hits.mapTo(HashSet()) { it.id }
                val location = String.format(java.util.Locale.US, "%.6f,%.6f", lat, lon)
                _state.update { s ->
                    s.copy(hits = s.hits.map { if (it.id in ids) it.copy(location = location, city = null, region = null, province = null, country = null) else it })
                }
                flash(if (hits.size == 1) AppText.s(R.string.vm_place_saved) else AppText.p(R.plurals.vm_place_saved_n, hits.size, Actions.formatCount(hits.size.toLong())))
                SyncScheduler.runNow(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                flash(friendlyMessage(e, AppText.s(R.string.vm_place_failed)))
            }
        }
    }

    /**
     * Puts many photos at one place from the tagging sheet (Cip, 2026-09-19). With [writeFiles],
     * Android has just allowed the app to write them, so the position goes into each file first -
     * off the screen's thread, a selection can be thousands - then everywhere else as [setPlace]
     * does it.
     */
    fun placePhotos(hits: List<PhotoHit>, lat: Double, lon: Double, writeFiles: Boolean) {
        if (hits.isEmpty()) return
        viewModelScope.launch {
            val written = if (!writeFiles) emptySet() else withContext(Dispatchers.IO) {
                val uris = Actions.contentUrisByPhoto(context, hits.filter { PhotoReader.canWriteExif(it.mime) })
                hits.filterTo(HashSet()) { hit -> uris[hit.id]?.let { PhotoReader.writeGps(context, it, hit.mime, lat, lon) } ?: false }
                    .mapTo(HashSet()) { it.id }
            }
            setPlace(hits, lat, lon, written)
        }
    }

    /**
     * The photos the tagging button works on: the ticked ones, wherever they are. A group's tick
     * can reach photos the grid never loaded, so those come from what was read when the group was
     * ticked, not from the screen.
     */
    fun photosToTag(): List<PhotoHit> {
        val state = _state.value
        val onScreen = state.hits.filter { it.id in state.selectedIds }
        val known = onScreen.map { it.id }.toSet()
        return onScreen + state.selectedOffscreen.filterKeys { it in state.selectedIds && it !in known }.values
    }

    /**
     * Saves the owner's tags and names on every ticked photo, on the phone.
     *
     * Nothing is sent to the index here: each photo's edit is stored on the phone, as either "these
     * and nothing else" ([tagsReplace] / [personsReplace]) or "these as well", and the sync that
     * starts right after carries them all up in batches. A null list is a field the form did not
     * touch at all.
     *
     * The photo files themselves are a different matter: Android only lets an app write them while
     * the owner is there to allow it, so that part is done now, with a progress bar, and the sheet
     * waits for it.
     */
    fun tagPhotos(
        tags: List<String>?,
        tagsReplace: Boolean,
        persons: List<String>?,
        personsReplace: Boolean,
        writeFiles: Boolean = false,
    ) {
        if (_state.value.bulkTagging) return
        val targets = photosToTag()
        val ids = targets.map { it.id }
        val clean = tags?.distinctWords()
        val names = persons?.distinctWords()
        if (ids.isEmpty() || (clean == null && names == null)) return
        _state.update { it.copy(bulkTagging = true, bulkTagError = null, bulkTagDone = 0, bulkTagTotal = if (writeFiles) ids.size else 0) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { edits.queueForAll(ids, clean, tagsReplace, names, personsReplace) }
                prefs.facetsJson = null
                // Held answers would still carry the old words.
                searches.clearCache()
                // Into the files, while the permission Android just gave is good for this batch.
                if (writeFiles) {
                    withContext(Dispatchers.IO) {
                        // Every photo's file resolved in ONE go for the whole batch, instead of a
                        // MediaStore lookup per photo inside the loop (Cip, 2026-09-18).
                        val uris = Actions.contentUrisByPhoto(context, targets)
                        // How often the bar is allowed to move. Telling the screen after every
                        // single photo redrew the whole grid behind the sheet a thousand times over
                        // (Cip, 2026-09-18).
                        var shownAt = 0L
                        targets.forEachIndexed { index, hit ->
                            uris[hit.id]?.let { uri ->
                                val keepTags = when {
                                    clean == null -> null
                                    tagsReplace -> clean
                                    else -> (PhotoReader.tagsIn(context, uri) + hit.customTags + clean).distinctWords()
                                }
                                val had = hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                val keepNames = when {
                                    names == null -> null
                                    personsReplace -> names
                                    else -> (had + names).distinctWords()
                                }
                                // No wording field on this form: the owner's own wording, when this
                                // phone keeps one, goes into the file with the tags; none, nothing
                                // is touched (Cip, 2026-09-17).
                                PhotoReader.writeXmp(context, uri, hit.mime, keepNames, keepTags, photoCache.getEdits(hit.id)?.meaning)
                                // The file is a few bytes longer now; the picture is the same one.
                                // Without this the next sync would see a changed file and send the
                                // photo up whole, five per call, instead of the words alone.
                                val (newSize, newModified) = fileStampOf(uri)
                                photoCache.updateDocSize(hit.id, newSize, newModified, fileHashOf(uri))
                            }
                            val done = index + 1
                            val at = System.currentTimeMillis()
                            if (done == targets.size || at - shownAt >= PROGRESS_MS) {
                                shownAt = at
                                _state.update { it.copy(bulkTagDone = done) }
                            }
                        }
                    }
                }
                // The photos on screen carry the new words at once; the index follows.
                val tagged = ids.toSet()
                _state.update { s ->
                    s.copy(
                        bulkTagging = false,
                        selecting = false,
                        selectedIds = emptySet(),
                        selectedOffscreen = emptyMap(),
                        selectedGroups = emptyMap(),
                        hits = s.hits.map { hit ->
                            if (hit.id !in tagged) hit else hit.copy(
                                customTags = when {
                                    clean == null -> hit.customTags
                                    tagsReplace -> clean
                                    else -> (hit.customTags + clean).distinctWords()
                                },
                                persons = when {
                                    names == null -> hit.persons
                                    personsReplace -> names.joinToString(", ")
                                    else -> (hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() } + names).distinctWords().joinToString(", ")
                                },
                            )
                        },
                    )
                }
                flash(AppText.p(R.plurals.vm_saved_n, ids.size, Actions.formatCount(ids.size.toLong())))
                SyncScheduler.runNow(context)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(bulkTagging = false, bulkTagError = friendlyMessage(e, AppText.s(R.string.vm_tags_failed))) }
            }
        }
    }

    /**
     * Reads what the ticked photos already carry - the names of people and the owner's tags, most
     * used first - so the tagging sheet can show them. One request, counted over the ticked ids
     * themselves, so it says the truth for the whole selection and not only for the screen.
     */
    fun loadSelectionWords() {
        val ids = _state.value.selectedIds.toList()
        if (ids.isEmpty()) {
            _state.update { it.copy(selectionPersons = emptyList(), selectionTags = emptyList(), selectionWordsLoading = false) }
            return
        }
        _state.update { it.copy(selectionWordsLoading = true) }
        viewModelScope.launch {
            try {
                val (tagCounts, personCounts) = withContext(Dispatchers.IO) { photoCache.wordCountsOf(ids) }
                fun sorted(counts: Map<String, Int>) = counts.entries.sortedByDescending { it.value }
                    .map { com.opensolr.photos.search.FacetValue(it.key, it.value) }
                _state.update { it.copy(selectionPersons = sorted(personCounts), selectionTags = sorted(tagCounts), selectionWordsLoading = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(selectionWordsLoading = false) }
            }
        }
    }

    /**
     * True when what is on screen can be answered without the index: nothing typed, no filters, no
     * duplicates, and the phone's copy of the index is complete.
     */
    private fun browsingLocally(): Boolean {
        val s = _state.value
        return s.query.isBlank() && s.filters.count == 0 && !s.duplicatesMode && !s.skippedMode &&
            prefs.cloneComplete && prefs.connection != null
    }

    /**
     * Fills the grid from the phone's own copy of the index: the shape of the whole library, and
     * the photos of whichever groups are open. Not one request.
     */
    private fun browseLocally(keepPosition: Boolean) {
        searchJob?.cancel()
        // Answered by the phone, so this takes no time at all - but the screen still has to see a
        // run start and finish, or the pull-to-refresh spinner is left hanging half way down with
        // nothing to tell it the work is over (Cip, 2026-09-18).
        _state.update { it.copy(searching = true, searchError = null) }
        searchJob = viewModelScope.launch {
            // Browsing by place, people or tags is laid out from the phone's copy as well, in one
            // query on its columns (Cip, 2026-09-19); by date it is the skeleton, as always.
            val how = _state.value.groupBy
            val byValue = how == GroupBy.PLACE || how == GroupBy.PEOPLE || how == GroupBy.TAGS ||
                how == GroupBy.FOLDER || how == GroupBy.CAMERA
            val groups = if (byValue) emptyList() else withContext(Dispatchers.IO) { buildSkeleton(photoCache.takenTimes()) }
            val valueGroups = if (!byValue) emptyList() else withContext(Dispatchers.IO) { buildResultGroups(photoCache.groupingRows(), how) }
            val count = withContext(Dispatchers.IO) { photoCache.docCount() }
            // Reloading the same view keeps the photos it shows, read again from the phone's copy
            // in one query: emptying the list left the grid with headings only, it lost the photo
            // it was anchored to, and every sync that wrote something threw the owner somewhere
            // else in the list (Cip, 2026-09-18). Photos gone since drop out; new ones are fetched
            // by their group, into their own place.
            val shown = _state.value.hits
            val kept = if (keepPosition && shown.isNotEmpty() && _state.value.searchedQuery.isEmpty()) {
                withContext(Dispatchers.IO) {
                    // In the order they were on screen: photos taken at the same moment have no
                    // order of their own, and re-sorting shuffled them under the owner's finger.
                    val fresh = photoCache.docsByIds(shown.map { it.id }).map { searches.hitOf(org.json.JSONObject(it)) }.associateBy { it.id }
                    shown.mapNotNull { fresh[it.id] }
                }
            } else emptyList()
            kotlinx.coroutines.delay(SPINNER_MS)
            _state.update {
                it.copy(
                    searching = false,
                    searchError = null,
                    suggestions = emptyList(),
                    duplicateGroups = emptyList(),
                    duplicatesMode = false,
                    skippedMode = false,
                    similarToId = null,
                    similarToHit = null,
                    hits = kept,
                    numFound = count.toLong(),
                    endReached = true,
                    searchedQuery = "",
                    skeleton = groups,
                    resultGroups = valueGroups,
                    facets = keptFacets() ?: it.facets,
                    searchGeneration = if (keepPosition) it.searchGeneration else it.searchGeneration + 1,
                    resultsGeneration = if (keepPosition) it.resultsGeneration else it.resultsGeneration + 1,
                )
            }
            targetScroll()
            refreshFacetsIfNeeded()
        }
    }

    /**
     * The filter lists as they were last read from the index, or null when they have to be asked
     * for again.
     */
    private fun keptFacets(): Map<String, List<FacetValue>>? {
        val json = prefs.facetsJson ?: return null
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { field ->
                val arr = obj.getJSONArray(field)
                (0 until arr.length()).map { i ->
                    val pair = arr.getJSONArray(i)
                    FacetValue(pair.getString(0), pair.getInt(1))
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Asks the index for the filter lists when the phone has none: one request, and then not again
     * until a sync writes something (Cip, 2026-09-18).
     */
    private fun refreshFacetsIfNeeded() {
        if (prefs.facetsJson != null || prefs.connection == null) return
        viewModelScope.launch {
            try {
                val facets = searches.browseFacets()
                val obj = org.json.JSONObject()
                facets.forEach { (field, values) ->
                    val arr = org.json.JSONArray()
                    values.forEach { arr.put(org.json.JSONArray().put(it.value).put(it.count)) }
                    obj.put(field, arr)
                }
                prefs.facetsJson = obj.toString()
                _state.update { it.copy(facets = facets) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The filter sheet keeps whatever it had; the next visit asks again.
            }
        }
    }

    /**
     * Lays out the whole library in years, months and days from the phone's own copy of the index.
     * Only for plain browsing: with a query or a filter the groups have to come from the results,
     * because the phone cannot tell which photos match.
     */
    private fun loadSkeleton() {
        val s = _state.value
        val plain = s.searchedQuery.isBlank() && s.filters.count == 0 && !s.duplicatesMode && !s.skippedMode
        if (!plain) {
            if (s.skeleton.isNotEmpty()) _state.update { it.copy(skeleton = emptyList()) }
            return
        }
        viewModelScope.launch {
            val groups = withContext(Dispatchers.IO) { buildSkeleton(photoCache.takenTimes()) }
            _state.update { it.copy(skeleton = groups) }
        }
    }

    /**
     * The years, months and days of [times] (newest first) with their counts, in the order the
     * grid shows them. The last few days keep the headings they have always had - Today,
     * Yesterday, a weekday - and stay outside the years.
     */
    private fun buildSkeleton(times: List<Long>): List<com.opensolr.photos.ui.DateGroup> {
        if (times.isEmpty()) return emptyList()
        val recent = LinkedHashMap<String, MutableList<Long>>()
        val years = LinkedHashMap<String, LinkedHashMap<String, MutableList<Long>>>()
        times.forEach { at ->
            if (Actions.isRecentDay(at)) {
                recent.getOrPut(Actions.dateHeading(at)) { ArrayList() } += at
            } else {
                years.getOrPut(Actions.yearHeading(at)) { LinkedHashMap() }
                    .getOrPut(Actions.monthKey(at)) { ArrayList() } += at
            }
        }
        // Today and the last few days are groups of their own, above the years. A year, a month or
        // a day older than that must therefore stop short of them, or ticking a year would take in
        // today's photos as well - they are inside that year too (Cip, 2026-09-18).
        // Found while the photos are being filed above, not by walking all of them a second time.
        val recentStart = recent.values.asSequence().flatten().minOrNull()?.let { Actions.daySpan(it).first }
        fun clamp(span: Pair<Long, Long>): Pair<Long, Long> =
            if (recentStart == null) span else span.first to minOf(span.second, recentStart - 1)

        val out = ArrayList<com.opensolr.photos.ui.DateGroup>()
        recent.forEach { (heading, list) ->
            val span = Actions.daySpan(list.first())
            out += com.opensolr.photos.ui.DateGroup(0, heading, heading, list.size, span.first, span.second)
        }
        years.forEach { (year, months) ->
            val all = months.values.sumOf { it.size }
            val span = clamp(Actions.yearSpan(months.values.first().first()))
            out += com.opensolr.photos.ui.DateGroup(0, year, year, all, span.first, span.second)
            months.forEach { (monthKey, list) ->
                val monthSpan = clamp(Actions.monthSpan(list.first()))
                out += com.opensolr.photos.ui.DateGroup(1, monthKey, Actions.monthHeading(list.first()), list.size, monthSpan.first, monthSpan.second)
                val days = LinkedHashMap<String, MutableList<Long>>()
                list.forEach { at -> days.getOrPut(Actions.dayKey(at)) { ArrayList() } += at }
                // Every month is spelled out by its days, even a month with a single one: the day
                // heading says something the month's does not, and a month that sometimes has days
                // and sometimes does not reads as broken (Cip, 2026-09-18).
                days.forEach { (dayKey, dayList) ->
                    val daySpan = clamp(Actions.daySpan(dayList.first()))
                    out += com.opensolr.photos.ui.DateGroup(2, dayKey, Actions.dayHeading(dayList.first()), dayList.size, daySpan.first, daySpan.second)
                }
            }
        }
        return out
    }

    /**
     * Fetches the photos of one group the owner has just opened, when the results on screen do not
     * hold them yet: browsing loads page by page from the newest, so opening a year from years ago
     * would otherwise mean loading everything in between. One request, that stretch of time only.
     */
    fun loadGroupPhotos(key: String, from: Long, to: Long, have: Int, count: Int) {
        if (have >= count || !groupsLoading.add(key)) return
        _state.update { it.copy(loadingGroup = true) }
        viewModelScope.launch {
            try {
                val local = browsingLocally()
                // Reading the phone's own copy is an indexed query, so those run as they come; only
                // the ones that have to ask the index queue up behind each other. Opening a dozen
                // groups at once must not leave eleven of them empty (Cip, 2026-09-18).
                val load = suspend {
                    val s = _state.value
                    val photos = if (local) {
                        withContext(Dispatchers.IO) {
                            photoCache.docsBetween(from, to, com.opensolr.photos.search.SearchRepository.GROUP_TICK_MAX).map { searches.hitOf(org.json.JSONObject(it)) }
                        }
                    } else {
                        searches.photosBetween(s.searchedQuery, s.filters, from, to, s.freshBias, s.wordsOnly, full = true)
                    }
                    _state.update { state ->
                        val seen = state.hits.mapTo(HashSet()) { it.id }
                        val added = photos.filter { seen.add(it.id) }
                        if (added.isEmpty()) state
                        else state.copy(
                            // Browsing is in date order, so photos that arrived late take their own
                            // place in the list rather than the end of it.
                            hits = (state.hits + added).sortedByDescending { it.takenMs ?: 0L },
                        )
                    }
                }
                if (local) load() else groupLoads.withLock { load() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // That group stays empty; opening it again asks once more.
            } finally {
                groupsLoading.remove(key)
                if (groupsLoading.isEmpty()) _state.update { it.copy(loadingGroup = false) }
            }
        }
    }

    /**
     * The md5 of [hit]'s file as it now stands, or null when it cannot be read.
     */
    private fun fileHashOf(uri: android.net.Uri): String? = PhotoReader.fileMd5(context, uri)

    /**
     * How large the file behind [uri] is right now and when it was last written, or zeroes when it
     * cannot be read.
     */
    private fun fileStampOf(uri: android.net.Uri): Pair<Long, Long> = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.Images.Media.SIZE, android.provider.MediaStore.Images.Media.DATE_MODIFIED),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else 0L to 0L } ?: (0L to 0L)
    } catch (e: Exception) {
        0L to 0L
    }

    /**
     * Shows [message] for a moment and then clears it by itself.
     */
    private fun flash(message: String) {
        _state.update { it.copy(flash = message) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(FLASH_MS)
            _state.update { if (it.flash == message) it.copy(flash = null) else it }
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
            openFilterSections = prefs.openFilterSections,
            foldedAlbumSections = prefs.foldedAlbumSections,
            groupBy = GroupBy.of(prefs.groupBy),
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
            _state.update { it.copy(screen = Screen.SignIn, signInError = AppText.s(R.string.vm_signin_stale)) }
            return
        }
        if (error != null) {
            _state.update { it.copy(screen = Screen.SignIn, signInError = AppText.s(R.string.vm_signin_cancelled)) }
            return
        }
        if (code == null || !CODE_PATTERN.matches(code)) {
            _state.update { it.copy(screen = Screen.SignIn, signInError = AppText.s(R.string.vm_signin_invalid)) }
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
                _state.update { it.copy(busy = false, screen = Screen.SignIn, signInError = friendlyMessage(e, AppText.s(R.string.vm_signin_failed))) }
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
            _state.update { it.copy(permissionError = AppText.s(R.string.vm_need_photos)) }
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
        // The folder filter offers the chosen folders, so its counts are asked for again.
        prefs.facetsJson = null
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
        _state.update { it.copy(setupStep = AppText.s(R.string.vm_connecting), setupError = null, setupNeedsUpgrade = false) }
        viewModelScope.launch {
            try {
                val (connection, outcome) = indexes.ensure(session) { step -> _state.update { it.copy(setupStep = step) } }
                if (outcome == IndexManager.Outcome.NEEDS_CHOICE) {
                    // The owner says which phone this is before anything is created.
                    _state.update { it.copy(deviceChoices = indexes.choices, setupStep = AppText.s(R.string.vm_which_device)) }
                    return@launch
                }
                SyncScheduler.applySchedule(context, prefs.schedule)
                SyncScheduler.watchMedia(context)
                SyncScheduler.runNow(context)
                _state.update { it.copy(screen = Screen.Search, indexName = connection.indexName, environment = connection.environment) }
                refreshAccount()
                search(reset = true)
            } catch (e: SignInRequiredException) {
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: IndexLimitException) {
                _state.update { it.copy(setupError = friendlyMessage(e, AppText.s(R.string.vm_setup_failed)), setupNeedsUpgrade = true) }
            } catch (e: Exception) {
                _state.update { it.copy(setupError = friendlyMessage(e, AppText.s(R.string.vm_setup_failed))) }
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
        withContext(Dispatchers.IO) { localWords(typed, onPhoto).second }

    /**
     * Suggestions for the tag field of the edit sheet: the owner's tags, then words from the
     * photos' meanings, matching [typed]. Empty when the index cannot be asked right now; a
     * suggestion list must never get in the way of tagging.
     */
    suspend fun tagSuggestions(typed: String, onPhoto: Collection<String>): com.opensolr.photos.search.TagSuggestions =
        withContext(Dispatchers.IO) {
            val (tags, _, meanings) = localWords(typed, onPhoto)
            val skip = (onPhoto + tags).map { com.opensolr.photos.data.Words.fold(it) }.toSet()
            com.opensolr.photos.search.TagSuggestions(tags, meanings.filter { com.opensolr.photos.data.Words.fold(it) !in skip }.take(SUGGEST_WORDS))
        }

    /**
     * The counts of every word in the phone's copy of the index, worked out at most once every
     * few seconds: a suggestion list is asked for on every keystroke and the whole library is
     * counted for it.
     */
    private fun cachedWordCounts(): Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>> {
        val now = System.currentTimeMillis()
        val held = wordCounts
        if (held != null && now - wordCountsAt < WORD_COUNTS_MS) return held
        val fresh = photoCache.wordCounts()
        wordCounts = fresh
        wordCountsAt = now
        return fresh
    }

    /**
     * The words to offer for [typed], out of the phone's own copy of the index: the owner's tags,
     * the names of the people in their photos, and the words the photos were read into, each
     * ordered by how many photos carry it. Nothing is asked of the index - the phone holds all
     * three (Cip, 2026-09-18).
     */
    private fun localWords(typed: String, exclude: Collection<String>): Triple<List<String>, List<String>, List<String>> {
        val text = com.opensolr.photos.data.Words.fold(typed.trim())
        val skip = exclude.map { com.opensolr.photos.data.Words.fold(it) }.toSet()
        val (tags, persons, meanings) = cachedWordCounts()
        fun pick(counts: Map<String, Int>, limit: Int) = counts.entries
            .filter { com.opensolr.photos.data.Words.fold(it.key).let { key -> key !in skip && (text.isEmpty() || key.contains(text)) } }
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
        val limit = if (text.isEmpty()) SUGGEST_FEW else SUGGEST_MANY
        return Triple(pick(tags, limit), pick(persons, limit), pick(meanings, SUGGEST_WORDS))
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
    fun search(reset: Boolean, keepPosition: Boolean = false, keepLoaded: Boolean = false) {
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
        // Plain browsing - no words typed, no filters - is answered by the phone itself: it holds
        // a copy of every document, so the years, the months, the days and the photos in them all
        // come from here and the index is not asked anything at all (Cip, 2026-09-18).
        if (reset && browsingLocally()) { browseLocally(keepPosition); return }
        if (groupedView(current)) {
            if (reset) searchGrouped(keepPosition)
            return
        }
        _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList(), duplicateGroups = emptyList(), duplicatesMode = false, skippedMode = false, similarToId = null, similarToHit = null, resultGroups = emptyList(), searchGeneration = if (reset && !keepPosition) it.searchGeneration + 1 else it.searchGeneration) }
        // The suggestions come from their own words-only request, next to this one.
        if (reset) loadQueryFacets(current.query, current.filters)
        searchJob = viewModelScope.launch {
            try {
                // Reloading a view the owner has already scrolled through asks for as much as was
                // on it, in one request, instead of the first page alone: otherwise a sync that
                // finishes while they are three pages down leaves them at the top of a list of
                // sixty (Cip, 2026-09-18). One request, ids and fields only, so it costs a page.
                // As many photos as were loaded, in one request. It cannot be worked out from how
                // far down the owner is: with groups folded away, a screen near the top can stand
                // over hundreds of photos that are still in the list but not drawn (Cip, 2026-09-18).
                // Plus a margin, so photos that moved down a little (an edit changes how well a
                // photo matches) are still inside the answer (Cip, 2026-09-18).
                val rows = if (keepLoaded) (current.hits.size + RELOAD_MARGIN).coerceIn(SearchRepository.PAGE, RELOAD_MAX) else SearchRepository.PAGE
                val page = searches.search(current.query, current.filters, start, rows = rows, freshBias = current.freshBias, wordsOnly = current.wordsOnly)
                _state.update {
                    // A photo already on the grid is never added twice. Consecutive pages can
                    // overlap when several photos share the value being sorted on, and the grid
                    // keys its items by photo id: a repeat used to crash the app the moment it
                    // was drawn, which is what fast scrolling produced (Cip, 2026-09-16).
                    val hits = if (reset && keepPosition && it.hits.isNotEmpty()) {
                        // The same view reloaded (a sync, coming back from the gallery): the photos
                        // stay in the order the owner was looking at, each one as the index now has
                        // it, and only photos new to the answer go after them. A photo edited in the
                        // gallery matches a little differently afterwards, and re-ranking moved it
                        // and everything after it (Cip, 2026-09-18).
                        val fresh = page.hits.associateBy { hit -> hit.id }
                        val kept = it.hits.mapNotNull { hit -> fresh[hit.id] }
                        val seen = kept.mapTo(HashSet()) { hit -> hit.id }
                        kept + page.hits.filter { hit -> seen.add(hit.id) }
                    } else if (reset) {
                        page.hits
                    } else {
                        val seen = it.hits.mapTo(HashSet()) { hit -> hit.id }
                        it.hits + page.hits.filter { hit -> seen.add(hit.id) }
                    }
                    it.copy(
                        searching = false,
                        hits = hits,
                        numFound = page.numFound,
                        // Only a first page carries the counts; a later page leaves them as they are.
                        facets = if (reset || page.facets.isNotEmpty()) page.facets else it.facets,
                        smart = page.smart,
                        searchNotice = page.notice,
                        didYouMean = if (reset) page.didYouMean else it.didYouMean,
                        resultsGeneration = if (reset && !keepPosition) it.resultsGeneration + 1 else it.resultsGeneration,
                        searchedQuery = if (reset) current.query else it.searchedQuery,
                        endReached = hits.size >= page.numFound || page.hits.isEmpty(),
                    )
                }
                // Only a first page lands somewhere; the next pages must not move the grid.
                if (reset) { targetScroll(); loadSkeleton() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, AppText.s(R.string.vm_search_failed))) }
            }
        }
    }

    /**
     * True when the results are laid out in groups of their own (date, place, people, tags): a
     * search or a filtered view, never plain browsing, duplicates or the skipped photos.
     */
    private fun groupedView(s: UiState): Boolean =
        s.groupBy != GroupBy.RELEVANCE && !s.duplicatesMode && !s.skippedMode && (s.query.isNotBlank() || s.filters.count > 0)

    /** Opens or folds one zone of Me, for the rest of the session (Cip, 2026-09-20). */
    fun toggleMeZone(key: String) {
        _state.update { it.copy(meZonesOpen = if (key in it.meZonesOpen) it.meZonesOpen - key else it.meZonesOpen + key) }
    }

    /** Folds or opens one section of Stats, for the rest of the session (Cip, 2026-09-21). */
    fun toggleStatsSection(key: String) {
        _state.update { it.copy(statsFolded = if (key in it.statsFolded) it.statsFolded - key else it.statsFolded + key) }
    }

    /** Folds every section of Stats in [keys] away, or opens them all when [keys] is empty. */
    fun setStatsFolded(keys: Set<String>) {
        _state.update { it.copy(statsFolded = keys) }
    }

    /**
     * Lays the results out by [how], and keeps the choice for the next searches (Cip, 2026-09-19).
     */
    fun setGroupBy(how: GroupBy) {
        if (how == _state.value.groupBy) return
        prefs.groupBy = how.key
        _state.update { it.copy(groupBy = how) }
        if (_state.value.duplicatesMode && _state.value.similarToId != null) loadDuplicates(debounceMs = 0) else search(reset = true)
    }

    /**
     * A search laid out in groups (Cip, 2026-09-19): one request brings every photo it finds, as its
     * id and the values it is grouped by, and the groups with their counts are made from that here.
     * Photos are only fetched for the groups that are open, from the phone's own copy of the index,
     * so a group costs no request at all. [keepPosition] reloads the same view - after a sync, back
     * from another screen - keeping the photos already on it, each as the phone now has it, and the
     * place in the list.
     */
    private fun searchGrouped(keepPosition: Boolean) {
        searchJob?.cancel()
        val current = _state.value
        _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList(), duplicateGroups = emptyList(), skeleton = emptyList(), searchGeneration = if (!keepPosition) it.searchGeneration + 1 else it.searchGeneration) }
        loadQueryFacets(current.query, current.filters)
        searchJob = viewModelScope.launch {
            try {
                val found = searches.groupedHits(current.query, current.filters, current.freshBias, current.wordsOnly)
                val groups = withContext(Dispatchers.Default) { buildResultGroups(found, current.groupBy) }
                val kept = if (keepPosition && current.hits.isNotEmpty()) {
                    val inView = found.mapTo(HashSet()) { it.id }
                    withContext(Dispatchers.IO) {
                        photoCache.docsByIds(current.hits.map { it.id }.filter { it in inView }).map { searches.hitOf(org.json.JSONObject(it)) }
                    }
                } else emptyList()
                groupsLoading.clear()
                _state.update {
                    it.copy(
                        searching = false,
                        resultGroups = groups,
                        hits = kept,
                        numFound = found.size.toLong(),
                        endReached = true,
                        searchNotice = null,
                        didYouMean = null,
                        searchedQuery = current.query,
                        resultsGeneration = if (!keepPosition) it.resultsGeneration + 1 else it.resultsGeneration,
                    )
                }
                targetScroll()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SignInRequiredException) {
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, AppText.s(R.string.vm_search_failed))) }
            }
        }
    }

    /**
     * The groups of [found] for [how], each with its photos best match first, in drawing order
     * (a parent, then its children). A photo sits in every group it belongs to - two people, two
     * tags - so the grid keys it by group and photo. Photos that have nothing to be grouped by go
     * in a last group of their own, so every result is somewhere.
     *
     * - date: year, month, day, newest first, the last few days on their own as when browsing;
     * - place: country, region, place;
     * - people, tags: one group per name or tag, in the order the best matches bring them.
     */
    private fun buildResultGroups(found: List<SearchRepository.GroupedHit>, how: GroupBy): List<ResultGroup> {
        val out = ArrayList<ResultGroup>()
        when (how) {
            GroupBy.DATE -> {
                val dated = found.filter { it.takenMs != null }.sortedByDescending { it.takenMs }
                val recent = LinkedHashMap<String, MutableList<String>>()
                val years = LinkedHashMap<String, Pair<String, LinkedHashMap<String, Pair<String, LinkedHashMap<String, Pair<String, MutableList<String>>>>>>>()
                dated.forEach { hit ->
                    val at = hit.takenMs!!
                    if (Actions.isRecentDay(at)) {
                        recent.getOrPut(Actions.dateHeading(at)) { ArrayList() } += hit.id
                    } else {
                        years.getOrPut(Actions.yearHeading(at)) { Actions.yearHeading(at) to LinkedHashMap() }.second
                            .getOrPut(Actions.monthKey(at)) { Actions.monthHeading(at) to LinkedHashMap() }.second
                            .getOrPut(Actions.dayKey(at)) { Actions.dayHeading(at) to ArrayList() }.second += hit.id
                    }
                }
                recent.forEach { (heading, ids) -> out += ResultGroup(0, "date:$heading", heading, ids) }
                years.forEach { (year, yearValue) ->
                    val months = yearValue.second
                    out += ResultGroup(0, "date:$year", yearValue.first, months.values.flatMap { m -> m.second.values.flatMap { it.second } })
                    months.forEach { (monthKey, monthValue) ->
                        out += ResultGroup(1, "date:$monthKey", monthValue.first, monthValue.second.values.flatMap { it.second })
                        monthValue.second.forEach { (dayKey, dayValue) -> out += ResultGroup(2, "date:$dayKey", dayValue.first, dayValue.second) }
                    }
                }
                val undated = found.filter { it.takenMs == null }.map { it.id }
                if (undated.isNotEmpty()) out += ResultGroup(0, "date:none", AppText.s(R.string.vm_no_date), undated)
            }
            GroupBy.PLACE -> {
                val countries = LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, MutableList<String>>>>()
                val nowhere = ArrayList<String>()
                found.forEach { hit ->
                    val country = hit.country
                    if (country == null) {
                        nowhere += hit.id
                        return@forEach
                    }
                    countries.getOrPut(country) { LinkedHashMap() }
                        .getOrPut(hit.region ?: "") { LinkedHashMap() }
                        .getOrPut(hit.city ?: "") { ArrayList() } += hit.id
                }
                val fold = com.opensolr.photos.data.Words::fold
                countries.forEach { (country, regions) ->
                    out += ResultGroup(0, "place:$country", country, regions.values.flatMap { r -> r.values.flatten() })
                    regions.forEach { (region, places) ->
                        // A region with no name, or named as its country, adds nothing: its places
                        // sit right under the country.
                        val regionShown = region.isNotEmpty() && fold(region) != fold(country)
                        val parent = if (regionShown) region else country
                        if (regionShown) out += ResultGroup(1, "place:$country/$region", region, places.values.flatten())
                        // One place named as the group above it says nothing new either - unless
                        // the country also holds other regions, and then it has to be a group of
                        // its own or its photos would have nowhere to be drawn.
                        val redundant = places.size == 1 && fold(places.keys.first()) == fold(parent) && (regionShown || regions.size == 1)
                        if (!redundant) places.forEach { (place, ids) ->
                            out += ResultGroup(if (regionShown) 2 else 1, "place:$country/$region/$place", place.ifEmpty { parent }, ids)
                        }
                    }
                }
                if (nowhere.isNotEmpty()) out += ResultGroup(0, "place:none", AppText.s(R.string.vm_no_place), nowhere)
            }
            GroupBy.PEOPLE, GroupBy.TAGS -> {
                val byValue = LinkedHashMap<String, Pair<String, MutableList<String>>>()
                val without = ArrayList<String>()
                found.forEach { hit ->
                    val values = if (how == GroupBy.PEOPLE) hit.persons else hit.tags
                    if (values.isEmpty()) without += hit.id
                    values.forEach { value ->
                        byValue.getOrPut(com.opensolr.photos.data.Words.fold(value)) { value to ArrayList() }.second += hit.id
                    }
                }
                val prefix = if (how == GroupBy.PEOPLE) "people" else "tag"
                byValue.forEach { (key, value) -> out += ResultGroup(0, "$prefix:$key", value.first, value.second.distinct()) }
                if (without.isNotEmpty()) out += ResultGroup(0, "$prefix:none", if (how == GroupBy.PEOPLE) AppText.s(R.string.vm_no_one) else AppText.s(R.string.vm_no_tags), without)
            }
            // One group per folder name or per camera, in the order the best matches bring them,
            // with everything that has none in a last group of its own (Cip, 2026-09-20). Both
            // are one value per photo, so no photo is ever drawn twice.
            GroupBy.CAMERA -> {
                val byValue = LinkedHashMap<String, Pair<String, MutableList<String>>>()
                val without = ArrayList<String>()
                found.forEach { hit ->
                    val value = hit.camera?.trim()?.ifBlank { null }
                    if (value == null) without += hit.id
                    else byValue.getOrPut(com.opensolr.photos.data.Words.fold(value)) { value to ArrayList() }.second += hit.id
                }
                byValue.forEach { (key, value) -> out += ResultGroup(0, "camera:$key", value.first, value.second) }
                if (without.isNotEmpty()) out += ResultGroup(0, "camera:none", AppText.s(R.string.vm_no_camera), without)
            }
            // The folders as they really sit, one inside another, and not as a flat list of their
            // last names (Cip, 2026-09-20): a library kept as 2019/11, 2019/07, 2024/02 showed
            // "11", "07" and "02" side by side, which says nothing about anything.
            GroupBy.FOLDER -> {
                val root = FolderNode("")
                val without = ArrayList<String>()
                found.forEach { hit ->
                    val path = hit.folder?.trim()?.trim('/')?.ifBlank { null }
                    if (path == null) {
                        without += hit.id
                        return@forEach
                    }
                    var at = root
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        at = at.children.getOrPut(com.opensolr.photos.data.Words.fold(segment)) { FolderNode(segment) }
                        at.all += hit.id
                    }
                    at.own += hit.id
                }
                root.children.values.forEach { child -> addFolderGroups(out, child, child.name, child.name, 0) }
                if (without.isNotEmpty()) out += ResultGroup(0, "folder:none", AppText.s(R.string.vm_no_folder), without)
            }
            GroupBy.RELEVANCE -> Unit
        }
        return out
    }

    /**
     * One folder while the photos are being sorted into a tree: the folders directly inside it,
     * the photos lying in it, and the photos of the whole branch under it.
     */
    private class FolderNode(val name: String) {
        val children = LinkedHashMap<String, FolderNode>()
        val own = ArrayList<String>()
        val all = ArrayList<String>()
    }

    /**
     * Writes [node] and the branch under it out as groups, headed by the folder's own name.
     *
     * A folder holding nothing but one other folder is written as a single line - *Pictures /
     * 2019* - because a level of its own for it would be a line with one arrow on it and nothing
     * else. Below [FOLDER_LEVELS] the tree stops and the deepest group simply holds everything
     * under it, so an oddly deep library never walks the grid off the side of the screen. Photos
     * lying directly in a folder that also holds folders get a line of their own under its name,
     * or they would have nowhere to be drawn: the grid only draws photos under a group with
     * nothing beneath it.
     */
    private fun addFolderGroups(out: MutableList<ResultGroup>, node: FolderNode, path: String, name: String, level: Int) {
        var at = node
        var key = path
        var label = name
        while (at.own.isEmpty() && at.children.size == 1) {
            val only = at.children.values.first()
            key = "$key/${only.name}"
            label = "$label / ${only.name}"
            at = only
        }
        out += ResultGroup(level, "folder:$key", label, at.all)
        if (at.children.isEmpty() || level >= FOLDER_LEVELS - 1) return
        if (at.own.isNotEmpty()) out += ResultGroup(level + 1, "folder:$key/.", label, at.own)
        at.children.values.forEach { child -> addFolderGroups(out, child, "$key/${child.name}", child.name, level + 1) }
    }

    /**
     * Fetches the photos of one open group of a grouped search: from the phone's own copy of the
     * index, and from the index only for any the copy does not hold. Once per group at a time.
     */
    fun loadResultGroup(name: String, ids: List<String>) {
        if (!groupsLoading.add("g:$name")) return
        viewModelScope.launch {
            try {
                val have = _state.value.hits.mapTo(HashSet()) { it.id }
                val missing = ids.filter { it !in have }
                if (missing.isEmpty()) return@launch
                val local = withContext(Dispatchers.IO) {
                    photoCache.docsByIds(missing).map { searches.hitOf(org.json.JSONObject(it)) }
                }
                val stillMissing = missing.toSet() - local.mapTo(HashSet()) { it.id }
                val remote = if (stillMissing.isEmpty()) emptyList() else searches.hitsByIds(stillMissing)
                _state.update { state ->
                    val seen = state.hits.mapTo(HashSet()) { it.id }
                    val added = (local + remote).filter { seen.add(it.id) }
                    if (added.isEmpty()) state else state.copy(hits = state.hits + added)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // That group stays empty; opening it again asks once more.
            } finally {
                groupsLoading.remove("g:$name")
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
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: Exception) {
                _state.update { it.copy(albumsLoading = false, albumsError = friendlyMessage(e, AppText.s(R.string.vm_albums_failed))) }
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
                _state.update { it.copy(albumsWorking = false, albumsError = friendlyMessage(e, AppText.s(R.string.vm_album_photos_failed))) }
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
    fun openAlbum(album: com.opensolr.photos.search.Album) = openFiltered(album.field, album.value)

    /**
     * Where the Stats list stood, as the first item shown and how far into it: kept outside the
     * UI state on purpose, since it changes with every frame of a scroll and nothing but the Stats
     * list itself reads it, when it is put back.
     */
    var statsScroll: Pair<Int, Int> = 0 to 0

    /**
     * Opens the photos grid on [value] of [field] alone, nothing typed, nothing else filtered:
     * an album, or a line of the Stats screen. The filter shows as a pill with its cross.
     */
    fun openFiltered(field: String, value: String, fromStats: Boolean = false) {
        _state.update {
            it.copy(
                screen = Screen.Search,
                returnToStats = fromStats,
                query = "",
                filters = SearchFilters().toggled(field, value),
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
     * Opens the Stats screen and works the numbers out again from the phone's copy of the index,
     * every time it opens: a sync may have added photos since. Nothing is asked of Opensolr.
     */
    fun openStats() {
        _state.update { it.copy(screen = Screen.Stats, statsLoading = true, returnToStats = false) }
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { photoCache.libraryStats() }
            _state.update { it.copy(stats = stats, statsLoading = false, statsPartial = !prefs.cloneComplete) }
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
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: Exception) {
                _state.update { it.copy(pinsLoading = false, pinsError = friendlyMessage(e, AppText.s(R.string.vm_map_failed))) }
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
     * Pulling the grid down also asks for a sync (Cip, 2026-09-18): new photos on the phone, and
     * any tagging still waiting, go up right away instead of at the next scheduled run. A sync
     * that is already running is left to finish - nothing is queued on top of it, and nothing is
     * said about it.
     */
    fun syncNow() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) return
        SyncScheduler.runNow(context)
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
                // Nothing cached describes the index any more: it is empty, and so is the phone's
                // own copy of it - every photo is written again by the sync that follows.
                searches.clearCache()
                withContext(Dispatchers.IO) { photoCache.clearDocs() }
                prefs.cloneComplete = true
            } catch (e: Exception) {
                _state.update { it.copy(notice = AppText.s(R.string.vm_empty_failed, friendlyMessage(e, AppText.s(R.string.vm_try_again)))) }
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
            // Which photos are documents is decided as the server decides what to read: by their
            // words (meaning), against the text family. The OCR filter is a different question -
            // only what text was really read out of. They go through Opensolr again, picture and
            // all, and nothing is deleted first - the new document takes the old one's place
            // (Cip, 2026-09-18).
            val ids = withContext(Dispatchers.IO) {
                photoCache.docsLikeDocuments(com.opensolr.photos.search.SearchFilters.DOCUMENT_WORDS)
            }
            if (ids.isEmpty()) {
                _state.update { it.copy(notice = AppText.s(R.string.vm_no_documents)) }
                return@launch
            }
            withContext(Dispatchers.IO) { photoCache.queueActions(ids, com.opensolr.photos.data.PhotoCache.ACTION_INDEX) }
            searches.clearCache()
            _state.update {
                it.copy(notice = AppText.p(R.plurals.vm_documents_n, ids.size, Actions.formatCount(ids.size.toLong())))
            }
            SyncScheduler.runNow(context)
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
        _state.update { it.copy(notice = AppText.s(R.string.vm_sync_stopped)) }
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
                        // Where this page really ended: a page stops on its budget of photos, so
                        // the number of groups it took is not a fixed one (Cip, 2026-09-20).
                        duplicateGroupsLoaded = from + page.groupsUsed,
                        numFound = (it.hits.size + page.hits.size).toLong(),
                        endReached = page.endReached,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, AppText.s(R.string.vm_dup_failed))) }
            }
        }
    }

    /** The selection as it stood when the finger went down, while a drag is picking photos. */
    private var dragBase: Set<String>? = null

    /** The photo the drag started on, and whether it was already ticked then. */
    private var dragAnchor: Pair<String, Boolean>? = null

    /**
     * A long press on a photo, with the finger still down: selection starts (if it had not
     * already), that photo is ticked, and the selection as it stood is kept, so that dragging
     * up and down from here only ever adds to it and never eats what was ticked before
     * (Cip, 2026-09-20).
     */
    fun beginDragSelect(id: String) {
        val current = _state.value
        val base = if (current.selecting) current.selectedIds else emptySet()
        dragBase = base
        dragAnchor = id to (id in base)
        _state.update { it.copy(selecting = true, selectedIds = base + id) }
    }

    /**
     * The finger has reached [ids] - every photo between the one the drag started on and the one
     * under it now. Set, not toggled: dragging back up unticks what the drag itself ticked, and
     * leaves everything else exactly as it was.
     */
    fun dragSelectTo(ids: Collection<String>) {
        val base = dragBase ?: return
        _state.update { it.copy(selecting = true, selectedIds = base + ids) }
    }

    /**
     * The finger is up. A long press that never left its own photo stays what it always was - a
     * tap that ticks or unticks that one photo - so a second long press on a ticked photo takes
     * it off again.
     */
    fun endDragSelect(movedAway: Boolean) {
        val anchor = dragAnchor
        dragBase = null
        dragAnchor = null
        if (movedAway || anchor == null || !anchor.second) return
        _state.update {
            val next = it.selectedIds - anchor.first
            if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
            else it.copy(selectedIds = next)
        }
    }

    /**
     * Enters or leaves photo selection on the grid.
     */
    fun setSelecting(on: Boolean) {
        _state.update { it.copy(selecting = on, selectedIds = if (on) it.selectedIds else emptySet(), selectedOffscreen = if (on) it.selectedOffscreen else emptyMap(), selectedGroups = if (on) it.selectedGroups else emptyMap()) }
    }

    /**
     * Ticks or unticks a photo while selecting. Unticking the last one leaves selection
     * altogether, so the mode is never on with nothing in it (Cip, 2026-09-18).
     */
    fun toggleSelected(id: String) {
        _state.update {
            val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
            else it.copy(selecting = true, selectedIds = next)
        }
    }

    /**
     * The tick on a heading: every photo of that group, or none of them when they are all ticked
     * already. Selection switches on by itself, as tapping a photo does.
     *
     * A group can be longer than what the grid has loaded - a month holds pages of photos - so the
     * whole group is read in one request, ids and what is needed to reach each file. The results on
     * screen are not touched: the grid keeps its pages and its place, and the ticked photos it has
     * not loaded are simply counted (Cip, 2026-09-18).
     */
    fun toggleSelectedGroup(key: String, ids: List<String>, range: Pair<Long, Long>? = null) {
        val current = _state.value
        val already = current.selectedGroups[key]
        if (already != null) {
            // Ticked whole before: everything it took in goes back out.
            _state.update {
                val next = it.selectedIds - already
                if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
                else it.copy(selectedIds = next, selectedOffscreen = it.selectedOffscreen - already, selectedGroups = it.selectedGroups - key)
            }
            return
        }
        if (range == null) {
            // A group whose photos are all on screen anyway (a search, the duplicates).
            if (ids.isEmpty()) return
            val all = current.selectedIds.containsAll(ids)
            _state.update {
                val next = if (all) it.selectedIds - ids.toSet() else it.selectedIds + ids
                if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
                else it.copy(selecting = true, selectedIds = next)
            }
            return
        }
        // Ticked at once with what is on screen, so the bar answers the finger; the rest of the
        // group joins them as soon as it has been looked up.
        _state.update { it.copy(selecting = true, selectedIds = it.selectedIds + ids, selectingGroup = true) }
        viewModelScope.launch {
            try {
                val s = _state.value
                val whole = if (browsingLocally()) {
                    withContext(Dispatchers.IO) {
                        photoCache.docsBetween(range.first, range.second, com.opensolr.photos.search.SearchRepository.GROUP_TICK_MAX).map { searches.hitOf(org.json.JSONObject(it)) }
                    }
                } else {
                    searches.photosBetween(s.searchedQuery, s.filters, range.first, range.second, s.freshBias, s.wordsOnly)
                }
                val onScreen = _state.value.hits.map { it.id }.toSet()
                _state.update { st ->
                    st.copy(
                        selecting = true,
                        selectedIds = st.selectedIds + whole.map { it.id },
                        selectedOffscreen = st.selectedOffscreen + whole.filter { it.id !in onScreen }.associateBy { it.id },
                        selectedGroups = st.selectedGroups + (key to (whole.map { it.id } + ids).toSet()),
                        selectingGroup = false,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // The photos on screen stay ticked; the rest of the group simply was not listed.
                _state.update { it.copy(selectingGroup = false) }
            }
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
     * The slider moved to [level] (a stop of DUPLICATE_FIELDS): the groups of that kind are asked for a short pause
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
    private fun loadDuplicates(debounceMs: Long, keepPages: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            val level = _state.value.duplicateLevel
            val anchor = _state.value.similarToId
            // How far down the owner had already scrolled, for a reload of the same view.
            val hadGroups = _state.value.duplicateGroupsLoaded
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
                var similarGroups = emptyList<ResultGroup>()
                if (anchor != null) {
                    val (h, g) = searches.similarTo(anchor, level)
                    hits = h; groups = g; loaded = g.size; done = true; total = g.size
                    // The photos like one photo, laid out by date, place, people or tags when the
                    // owner chose one (Cip, 2026-09-19): at most a couple of hundred, all loaded.
                    val how = _state.value.groupBy
                    if (how != GroupBy.RELEVANCE) similarGroups = withContext(Dispatchers.Default) {
                        buildResultGroups(h.map { hit ->
                            SearchRepository.GroupedHit(
                                id = hit.id,
                                takenMs = hit.takenMs,
                                country = hit.country?.trim()?.ifBlank { null },
                                region = hit.region?.trim()?.ifBlank { null },
                                city = (hit.city ?: hit.province)?.trim()?.ifBlank { null },
                                persons = hit.persons.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                tags = hit.customTags,
                                folder = com.opensolr.photos.search.SearchFilters.folderPath(hit.folder),
                                camera = com.opensolr.photos.search.SearchFilters.cameraName(hit.cameraMake, hit.cameraModel),
                            )
                        }, how)
                    }
                } else {
                    val first = searches.duplicates(level, groupsFrom = 0)
                    val allHits = ArrayList(first.hits)
                    val allSizes = ArrayList(first.sizes)
                    // The groups this page really took, not a fixed page size: it stops on its
                    // budget of photos as well (Cip, 2026-09-20).
                    var used = first.groupsUsed
                    var end = first.endReached
                    // A RELOAD of the same view brings back every page the owner had already
                    // scrolled through, not the first one alone (Cip, 2026-09-20). Deleting a
                    // photo, or a sync finishing, used to rebuild the view out of its first page,
                    // so the row the grid was standing on no longer existed and it went back to
                    // the top. Only a reload does this; opening the view, or moving the slider,
                    // is a new view and starts at one page.
                    // Bounded: a reload brings back the pages that were scrolled through, not an
                    // unbounded walk. Ten pages is deeper than anyone scrolls a view of groups,
                    // and it keeps the cost of the sync that finishes behind it in proportion.
                    var pages = 1
                    while (keepPages && !end && used < hadGroups && pages < REPAGE_MAX) {
                        pages++
                        val next = searches.duplicates(level, groupsFrom = used)
                        if (next.groupsUsed == 0) break
                        allHits += next.hits
                        allSizes += next.sizes
                        used += next.groupsUsed
                        end = next.endReached
                    }
                    hits = allHits; groups = allSizes
                    loaded = used
                    done = end; total = first.totalGroups
                }
                _state.update {
                    if (!it.duplicatesMode || it.duplicateLevel != level || it.similarToId != anchor) it
                    else it.copy(
                        searching = false,
                        hits = hits,
                        duplicateGroups = groups,
                        resultGroups = similarGroups,
                        duplicateGroupsLoaded = loaded,
                        duplicateGroupsTotal = total,
                        numFound = hits.size.toLong(),
                        endReached = done,
                        searchNotice = when {
                            hits.isNotEmpty() -> null
                            anchor != null -> AppText.s(R.string.vm_nothing_similar)
                            else -> AppText.s(R.string.vm_no_dups)
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
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, searchError = friendlyMessage(e, AppText.s(R.string.vm_dup_failed))) }
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
            // Keeping the pages already scrolled through, so the grid stays where it was.
            _state.value.duplicatesMode -> loadDuplicates(debounceMs = 0, keepPages = true)
            else -> search(reset = true, keepPosition = true, keepLoaded = true)
        }
    }

    /**
     * Swipe down on the grid: the owner is asking the index itself, not the phone, so every
     * held answer goes before the results are loaded again.
     */
    fun forceRefresh(toTop: Boolean = false) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { searches.clearCache() }
            _state.update { it.copy(cachedCount = 0) }
            // Pulling the list down is a gesture made at the top, by someone who wants to start
            // again from the top: one ordinary page, and the grid goes up with it. The reload
            // button is the opposite - it is pressed where the owner is standing (Cip, 2026-09-18).
            // Still as many photos as were loaded: the groups folded away hold them too, and a
            // pull used to leave a search that had them all with its first page only (Cip, 2026-09-18).
            if (toTop && !_state.value.duplicatesMode && !_state.value.skippedMode) search(reset = true, keepLoaded = true)
            else refresh()
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
                withContext(Dispatchers.IO) { photoCache.removeDocs(ids); photoCache.clearActions(ids) }
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
     * Places whose name begins with what was typed, for the map picker's search box
     * (Cip, 2026-09-20). Empty for an unknown name, and empty when the account cannot be
     * asked: a search box that fails quietly is better than one that shouts while typing.
     *
     * Held on the phone in the same cache as every other answer, for the number of seconds
     * the owner chose in Me (Cip, 2026-09-20): towns do not move, and typing the same name
     * again - or one letter more and then back - must not cost another request. The whole
     * answer is stored as it came, so a hit is a read from SQLite and nothing leaves the phone.
     */
    suspend fun searchPlaces(query: String): List<com.opensolr.photos.net.PlaceHit> {
        val session = prefs.session ?: return emptyList()
        // "Fântâni, Dolj, Romania" - the way the chosen place is written back into the box, and
        // the way a person writes a place - has to find what the same words without commas find
        // (Cip, 2026-09-20). No place name carries a comma, so it is a separator here.
        val q = query.replace(Regex("[,;]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.length < 2) return emptyList()

        val cache = com.opensolr.photos.data.SearchCache.of(context)
        val key = com.opensolr.photos.data.SearchCache.key(
            session.email, "/place_search", listOf("q" to q.lowercase(java.util.Locale.ROOT)),
        )
        cache.get(key, prefs.cacheSeconds)?.let { stored ->
            runCatching { com.opensolr.photos.net.PlaceHit.listFromJson(stored) }.getOrNull()?.let { return it }
        }
        return try {
            // Only an answer with places in it is kept: "nothing by that name" is as often the
            // network, or a gap that gets filled server-side, and holding it for a day would
            // keep answering nothing long after the name became findable.
            api.searchPlaces(session, q).also {
                if (it.isNotEmpty()) cache.put(key, com.opensolr.photos.net.PlaceHit.listToJson(it))
            }
        } catch (e: Exception) {
            emptyList()
        }
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
                signedOut(AppText.s(R.string.vm_signed_out))
            } catch (e: Exception) {
                _state.update { it.copy(accountRefreshing = false, accountError = friendlyMessage(e, AppText.s(R.string.vm_limits_failed))) }
            }
        }
    }

    /**
     * Signs out: stops every sync and forgets the account, the index connection and the cache.
     */
    fun signOut() {
        SyncScheduler.cancelAll(context)
        viewModelScope.launch(Dispatchers.IO) { PhotoCache.of(context).clear() }
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
            Screen.Map, Screen.Albums, Screen.Stats -> {
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
            Screen.Search -> if (current.returnToStats) openStats()
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
        // A photo changed while this sync ran: it may not have been seen, so one more sync runs.
        if (mediaChangedDuringSync) {
            mediaChangedDuringSync = false
            SyncScheduler.runNow(context)
        }
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
            signedOut(prefs.pendingNotice ?: AppText.s(R.string.vm_signed_out))
            prefs.pendingNotice = null
            return
        }
        if (report?.status == "rebuild_required") _state.update { it.copy(rebuildRequired = true) }
        if (report?.status == "device_choice") askDeviceChoice()
        if (report?.status == "update_app") _state.update { it.copy(notice = report.message) }
        refreshSkippedCount()
        refreshPlacesToWrite()
        // A sync that wrote nothing leaves the results exactly as they are, so nothing is reloaded
        // and nobody loses their place (Cip, 2026-09-18).
        val wrote = report == null || report.added > 0 || report.deleted > 0
        // Only a sync that wrote something can have changed the filter lists.
        if (wrote) prefs.facetsJson = null
        if (wrote && _state.value.screen == Screen.Search) refresh()
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

        /** Most pages a reload of the similar photos brings back, to land where it was left. */
        private const val REPAGE_MAX = 10

        /** How long a passing line ("Saved…") stays on the grid. */
        private const val FLASH_MS = 4000L

        /**
         * How deep the grid draws the folder tree, as the date grouping draws year, month and
         * day. Anything deeper is held by the group at the bottom, so its photos are all there
         * without the headings marching off the side of the screen.
         */
        private const val FOLDER_LEVELS = 3

        /**
         * The shortest a refresh is allowed to look like it took. Browsing is answered by the phone
         * in no time, and a spinner that appears and vanishes in the same frame reads as broken.
         */
        private const val SPINNER_MS = 350L

        /** How long the photo changes have to be quiet before they are looked at. */
        private const val MEDIA_SETTLE_MS = 1500L

        /** How often the bar of a long piece of work is allowed to move. */
        private const val PROGRESS_MS = 100L

        /** How long the word counts of the whole library stand before they are worked out again. */
        private const val WORD_COUNTS_MS = 3000L

        /** How many suggestions are offered with nothing typed, and while typing. */
        private const val SUGGEST_FEW = 5
        private const val SUGGEST_MANY = 8

        /** How many of the words the photos were read into are offered under the owner's tags. */
        private const val SUGGEST_WORDS = 5

        /** Most photos one reload may bring back at once, when the view had that many on it. */
        private const val RELOAD_MAX = 1000

        /** How many more than were loaded a reload asks for. */
        private const val RELOAD_MARGIN = 100

    }
}
