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

enum class Screen { SignIn, Welcome, Permissions, Folders, Setup, Search, Sync, Account, Map, Albums, Stats }

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

    val selectedOffscreen: Map<String, PhotoHit> = emptyMap(),

    val selectingGroup: Boolean = false,

    val selectedGroups: Map<String, Set<String>> = emptyMap(),

    val freshBias: Boolean = false,

    val wordsOnly: Boolean = false,

    val queryFacets: Map<String, List<FacetValue>> = emptyMap(),

    val duplicateGroups: List<Int> = emptyList(),

    val duplicateGroupsLoaded: Int = 0,

    val duplicateGroupsTotal: Int = 0,

    val duplicatesMode: Boolean = false,

    val skippedMode: Boolean = false,

    val skippedCount: Int = 0,

    val placesToWrite: Int = 0,

    val placesDeclined: Boolean = false,

    val autoPlace: Boolean = false,

    val autoPlaceLocation: Boolean = false,

    val autoPlaceBackground: Boolean = false,

    val duplicateLevel: Int = com.opensolr.photos.search.SearchRepository.DEFAULT_DUPLICATE_LEVEL,

    val similarToId: String? = null,

    val similarToHit: PhotoHit? = null,
    val rebuildRequired: Boolean = false,

    val searchGeneration: Int = 0,

    val resultsGeneration: Int = 0,

    val gridKey: String? = null,
    val gridIndex: Int = 0,
    val gridOffset: Int = 0,

    val collapsedHeadings: Set<String> = emptySet(),

    val skeleton: List<com.opensolr.photos.ui.DateGroup> = emptyList(),

    val meZonesOpen: Set<String> = emptySet(),

    val statsOpen: Set<String> = emptySet(),

    val returnToStats: Boolean = false,

    val groupBy: GroupBy = GroupBy.RELEVANCE,

    val resultGroups: List<ResultGroup> = emptyList(),

    val loadingGroup: Boolean = false,

    val openFilterSections: Set<String> = emptySet(),

    val foldedAlbumSections: Set<String> = emptySet(),

    val restoreGeneration: Int = 0,
    val editSaving: Boolean = false,
    val editError: String? = null,

    val bulkTagging: Boolean = false,

    val bulkTagDone: Int = 0,
    val bulkTagTotal: Int = 0,

    val selectionPersons: List<FacetValue> = emptyList(),
    val selectionTags: List<FacetValue> = emptyList(),

    val selectionWordsLoading: Boolean = false,

    val bulkTagError: String? = null,

    val planWarnings: List<PlanWatch.Warning> = emptyList(),

    val update: UpdateCheck.Update? = null,

    val updateChecking: Boolean = false,

    val updateProgress: Int? = null,

    val updateInstallError: String? = null,

    val updateResult: String? = null,

    val cacheSeconds: Int = com.opensolr.photos.data.SearchCache.DEFAULT_SECONDS,

    val lexicalWeight: Float = AppPrefs.DEFAULT_LEXICAL_WEIGHT,

    val hapticsEnabled: Boolean = true,

    val cachedCount: Int = 0,

    val deviceChoices: List<AccountIndex> = emptyList(),

    val stats: com.opensolr.photos.data.LibraryStats? = null,
    val statsLoading: Boolean = false,

    val statsPartial: Boolean = false,

    val albums: List<com.opensolr.photos.search.AlbumSection> = emptyList(),
    val albumsLoading: Boolean = false,
    val albumsError: String? = null,

    val selectedAlbums: Set<String> = emptySet(),

    val selectedSections: Set<String> = emptySet(),

    val albumsWorking: Boolean = false,

    val flash: String? = null,
)

data class MapFocus(val lat: Double, val lon: Double, val zoom: Double)

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

    private val scrollPositions = HashMap<String, ScrollAt>()

    private val collapsedHeadings = HashMap<String, Set<String>>(prefs.collapsedHeadings)
        .also { com.opensolr.photos.ui.Haptics.enabled = prefs.hapticsEnabled }

    private var mediaChangeJob: Job? = null

    private val changedUris = LinkedHashSet<Uri>()

    private var mediaChangedDuringSync = false

    private val mediaObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            onMediaChanged(listOfNotNull(uri))
        }

        override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
            onMediaChanged(uris)
        }
    }

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

    override fun onCleared() {
        context.contentResolver.unregisterContentObserver(mediaObserver)
        super.onCleared()
    }

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

    fun onAppResumed() {
        if (prefs.session == null || prefs.connection == null) return
        viewModelScope.launch {
            val stamp = withContext(Dispatchers.IO) { MediaScanner.folderStamp(context, prefs.folders) }
            if (stamp != null && stamp != prefs.folderStamp) syncForChange()
        }
    }

    private fun syncForChange() {
        if (_state.value.sync.running) mediaChangedDuringSync = true else SyncScheduler.runNow(context)
    }

    private fun ensureClone() {
        if (prefs.session == null || prefs.connection == null) return
        viewModelScope.launch {
            try {
                if (!withContext(Dispatchers.IO) { edits.cloneMissing() }) return@launch
                edits.readIndexIntoCache()

                if (_state.value.screen == Screen.Search) search(reset = true, keepPosition = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {

            }
        }
    }

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

    fun setAutoPlace(on: Boolean) {
        if (on != (prefs.autoPlaceSince > 0)) prefs.autoPlaceSince = if (on) System.currentTimeMillis() else 0L
        refreshPlacesToWrite()
    }

    private var pendingPlaceWrite: List<Triple<String, android.net.Uri, PhotoCache.SetPlace>> = emptyList()

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

    fun showSkipped() {
        if (_state.value.skippedMode) {
            _state.update { it.copy(skippedMode = false, searchNotice = null) }
            search(reset = true)
            return
        }
        loadSkipped()
    }

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

    private fun checkForUpdate() {
        if (System.currentTimeMillis() - prefs.updateCheckedAt < UPDATE_CHECK_INTERVAL_MS) return
        viewModelScope.launch {
            val update = UpdateCheck.check().getOrNull() ?: return@launch

            prefs.updateCheckedAt = System.currentTimeMillis()
            if (prefs.updateDismissed == update.version) return@launch
            _state.update { it.copy(update = update) }
        }
    }

    fun installUpdate(context: android.content.Context) {
        val newer = _state.value.update ?: return
        if (_state.value.updateProgress != null) return
        if (!com.opensolr.photos.net.SelfUpdate.canInstall(context)) {
            _state.update { it.copy(updateInstallError = AppText.s(R.string.acc_update_allow)) }
            com.opensolr.photos.net.SelfUpdate.openInstallPermission(context)
            return
        }
        _state.update { it.copy(updateProgress = 0, updateInstallError = null) }
        viewModelScope.launch {
            val outcome = com.opensolr.photos.net.SelfUpdate.downloadAndInstall(context.applicationContext, newer.apkUrl) { pct ->
                _state.update { it.copy(updateProgress = pct) }
            }
            _state.update {
                when (outcome) {
                    is com.opensolr.photos.net.SelfUpdate.Outcome.Started -> it.copy(updateProgress = null)
                    is com.opensolr.photos.net.SelfUpdate.Outcome.NeedsPermission -> it.copy(updateProgress = null, updateInstallError = AppText.s(R.string.acc_update_allow))
                    is com.opensolr.photos.net.SelfUpdate.Outcome.Invalid -> it.copy(updateProgress = null, updateInstallError = AppText.s(R.string.acc_update_invalid))
                    is com.opensolr.photos.net.SelfUpdate.Outcome.Failed -> it.copy(updateProgress = null, updateInstallError = AppText.s(R.string.acc_update_failed))
                }
            }
        }
    }

    fun checkForUpdateNow() {
        if (_state.value.updateChecking) return
        _state.update { it.copy(updateChecking = true, updateResult = null) }
        viewModelScope.launch {
            val outcome = UpdateCheck.check()
            prefs.updateCheckedAt = System.currentTimeMillis()
            val update = outcome.getOrNull()
            _state.update {
                when {

                    outcome.isFailure -> it.copy(updateChecking = false, updateResult = AppText.s(R.string.vm_update_failed))
                    update == null -> it.copy(updateChecking = false, updateResult = AppText.s(R.string.vm_update_latest))
                    else -> it.copy(updateChecking = false, update = update, updateResult = AppText.s(R.string.vm_update_found, update.version))
                }
            }

            if (update != null) prefs.updateDismissed = null
        }
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            val held = withContext(Dispatchers.IO) { searches.cachedCount() }
            _state.update { it.copy(cacheSeconds = prefs.cacheSeconds, cachedCount = held, hapticsEnabled = prefs.hapticsEnabled, lexicalWeight = prefs.lexicalWeight) }
        }
    }

    fun setLexicalWeight(value: Float) {
        prefs.lexicalWeight = value
        _state.update { it.copy(lexicalWeight = prefs.lexicalWeight) }
    }

    fun setHaptics(on: Boolean) {
        prefs.hapticsEnabled = on
        com.opensolr.photos.ui.Haptics.enabled = on
        _state.update { it.copy(hapticsEnabled = on) }
    }

    fun setCacheSeconds(seconds: Int) {
        prefs.cacheSeconds = seconds
        _state.update { it.copy(cacheSeconds = prefs.cacheSeconds) }
    }

    fun clearSearchCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { searches.clearCache() }
            _state.update { it.copy(cachedCount = 0) }
        }
    }

    private data class ScrollAt(val key: String?, val index: Int, val offset: Int)

    private var shownContext: String? = null

    private val groupsLoading = java.util.Collections.synchronizedSet(HashSet<String>())
    private val groupLoads = kotlinx.coroutines.sync.Mutex()

    private var wordCounts: Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>>? = null
    private var wordCountsAt = 0L

    private fun contextKey(s: UiState): String {

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

    fun rememberGridPosition(key: String?, index: Int, offset: Int) {
        scrollPositions[contextKey(_state.value)] = ScrollAt(key, index, offset)
    }

    fun toggleHeading(key: String) {
        val context = contextKey(_state.value)
        val next = (collapsedHeadings[context] ?: defaultCollapsed(_state.value)).let { if (key in it) it - key else it + key }
        collapsedHeadings[context] = next
        prefs.collapsedHeadings = collapsedHeadings
        _state.update { it.copy(collapsedHeadings = next) }
    }

    fun toggleFilterSection(title: String) {
        val next = _state.value.openFilterSections.let { if (title in it) it - title else it + title }
        prefs.openFilterSections = next
        _state.update { it.copy(openFilterSections = next) }
    }

    fun toggleAlbumSection(title: String) {
        val next = _state.value.foldedAlbumSections.let { if (title in it) it - title else it + title }
        prefs.foldedAlbumSections = next
        _state.update { it.copy(foldedAlbumSections = next) }
    }

    fun setAlbumSections(folded: Set<String>) {
        prefs.foldedAlbumSections = folded
        _state.update { it.copy(foldedAlbumSections = folded) }
    }

    private fun defaultCollapsed(s: UiState): Set<String> =
        if (!s.duplicatesMode && !s.skippedMode && s.searchedQuery.isNotBlank()) setOf("h:Also similar") else emptySet()

    fun setAllHeadings(keys: Set<String>) {
        val context = contextKey(_state.value)
        collapsedHeadings[context] = keys
        prefs.collapsedHeadings = collapsedHeadings
        _state.update { it.copy(collapsedHeadings = keys) }
    }

    private fun targetScroll() {
        val context = contextKey(_state.value)

        val changed = context != shownContext
        shownContext = context
        val at = if (changed) ScrollAt(null, 0, 0) else scrollPositions[context] ?: ScrollAt(null, 0, 0)
        _state.update {
            it.copy(
                gridKey = at.key,
                gridIndex = at.index,
                gridOffset = at.offset,

                collapsedHeadings = collapsedHeadings[context] ?: defaultCollapsed(it),
                restoreGeneration = it.restoreGeneration + 1,
            )
        }
    }

    fun backToSearch() = clearDuplicates()

    fun dismissUpdate() {
        prefs.updateDismissed = _state.value.update?.version
        _state.update { it.copy(update = null) }
    }

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

    fun approveRebuild() {
        prefs.rebuildApproved = true
        _state.update { it.copy(rebuildRequired = false) }
        SyncScheduler.runNow(context)
    }

    fun postponeRebuild() {
        _state.update { it.copy(rebuildRequired = false) }
    }

    fun saveEdits(hit: PhotoHit, tags: List<String>, meaning: String?, persons: List<String>? = null, resetWording: Boolean = false, onDone: () -> Unit) {
        _state.update { it.copy(editSaving = true, editError = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    edits.saveLocal(hit.id, tags, meaning, persons)
                    prefs.facetsJson = null
                    if (resetWording) {
                        prefs.wordingResetIds = prefs.wordingResetIds + hit.id
                        prefs.resyncIds = prefs.resyncIds + hit.id
                    }

                    Actions.contentUris(context, listOf(hit)).firstOrNull()?.let { uri ->
                        val (size, modified) = fileStampOf(uri)
                        photoCache.updateDocSize(hit.id, size, modified, fileHashOf(uri))
                    }
                }

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

    fun setPlace(hits: List<PhotoHit>, lat: Double, lon: Double, inFile: Set<String>) {
        if (hits.isEmpty()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uris = if (inFile.isEmpty()) emptyMap() else Actions.contentUrisByPhoto(context, hits.filter { it.id in inFile })

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

    fun photosToTag(): List<PhotoHit> {
        val state = _state.value
        val onScreen = state.hits.filter { it.id in state.selectedIds }
        val known = onScreen.map { it.id }.toSet()
        return onScreen + state.selectedOffscreen.filterKeys { it in state.selectedIds && it !in known }.values
    }

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

                searches.clearCache()

                if (writeFiles) {
                    withContext(Dispatchers.IO) {

                        val uris = Actions.contentUrisByPhoto(context, targets)

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

                                PhotoReader.writeXmp(context, uri, hit.mime, keepNames, keepTags, photoCache.getEdits(hit.id)?.meaning)

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

    private fun browsingLocally(): Boolean {
        val s = _state.value
        return s.query.isBlank() && s.filters.count == 0 && !s.duplicatesMode && !s.skippedMode &&
            prefs.cloneComplete && prefs.connection != null
    }

    private fun browseLocally(keepPosition: Boolean) {
        searchJob?.cancel()

        _state.update { it.copy(searching = true, searchError = null) }
        searchJob = viewModelScope.launch {

            val how = _state.value.groupBy
            val byValue = how == GroupBy.PLACE || how == GroupBy.PEOPLE || how == GroupBy.TAGS ||
                how == GroupBy.FOLDER || how == GroupBy.CAMERA
            val groups = if (byValue) emptyList() else withContext(Dispatchers.IO) { buildSkeleton(photoCache.takenTimes()) }
            val valueGroups = if (!byValue) emptyList() else withContext(Dispatchers.IO) { buildResultGroups(photoCache.groupingRows(), how) }
            val count = withContext(Dispatchers.IO) { photoCache.docCount() }

            val shown = _state.value.hits
            val kept = if (keepPosition && shown.isNotEmpty() && _state.value.searchedQuery.isEmpty()) {
                withContext(Dispatchers.IO) {

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

            }
        }
    }

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

                days.forEach { (dayKey, dayList) ->
                    val daySpan = clamp(Actions.daySpan(dayList.first()))
                    out += com.opensolr.photos.ui.DateGroup(2, dayKey, Actions.dayHeading(dayList.first()), dayList.size, daySpan.first, daySpan.second)
                }
            }
        }
        return out
    }

    fun loadGroupPhotos(key: String, from: Long, to: Long, have: Int, count: Int) {
        if (have >= count || !groupsLoading.add(key)) return
        _state.update { it.copy(loadingGroup = true) }
        viewModelScope.launch {
            try {
                val local = browsingLocally()

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

                            hits = (state.hits + added).sortedByDescending { it.takenMs ?: 0L },
                        )
                    }
                }
                if (local) load() else groupLoads.withLock { load() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {

            } finally {
                groupsLoading.remove(key)
                if (groupsLoading.isEmpty()) _state.update { it.copy(loadingGroup = false) }
            }
        }
    }

    private fun fileHashOf(uri: android.net.Uri): String? = PhotoReader.fileMd5(context, uri)

    private fun fileStampOf(uri: android.net.Uri): Pair<Long, Long> = try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.MediaStore.Images.Media.SIZE, android.provider.MediaStore.Images.Media.DATE_MODIFIED),
            null, null, null,
        )?.use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else 0L to 0L } ?: (0L to 0L)
    } catch (e: Exception) {
        0L to 0L
    }

    private fun flash(message: String) {
        _state.update { it.copy(flash = message) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(FLASH_MS)
            _state.update { if (it.flash == message) it.copy(flash = null) else it }
        }
    }

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
            statsOpen = prefs.statsOpen,
            groupBy = GroupBy.of(prefs.groupBy),
        )
    }

    fun beginSignIn(activityContext: Context) {
        _state.update { it.copy(signInError = null, notice = null) }
        AuthFlow.start(activityContext, prefs)
    }

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

    fun continueFromWelcome() {
        _state.update { it.copy(screen = if (prefs.foldersChosen && prefs.connection != null) Screen.Search else Screen.Permissions) }
        if (_state.value.screen == Screen.Search) search(reset = true)
    }

    fun onPermissionsResult(photosGranted: Boolean) {
        if (!photosGranted) {
            _state.update { it.copy(permissionError = AppText.s(R.string.vm_need_photos)) }
            return
        }
        _state.update { it.copy(permissionError = null) }
        openFolders(Screen.Setup)
    }

    fun openFolders(returnTo: Screen) {
        _state.update { it.copy(screen = Screen.Folders, foldersLoading = true, foldersReturnTo = returnTo) }
        viewModelScope.launch {
            val folders = withContext(Dispatchers.IO) { MediaScanner.listFolders(context) }
            val selected = if (prefs.foldersChosen) prefs.folders else MediaScanner.defaultFolders(folders)
            _state.update { it.copy(folders = folders, selectedFolders = selected, foldersLoading = false) }
        }
    }

    fun toggleFolder(path: String) {
        _state.update {
            val next = if (path in it.selectedFolders) {
                it.selectedFolders - path
            } else {

                it.selectedFolders.filterNot { chosen -> chosen.startsWith(path, ignoreCase = true) }.toSet() + path
            }
            it.copy(selectedFolders = next)
        }
    }

    fun saveFolders() {
        val selected = _state.value.selectedFolders
        if (selected.isEmpty()) return
        prefs.folders = selected

        prefs.facetsJson = null
        if (prefs.connection == null || _state.value.foldersReturnTo == Screen.Setup) {
            _state.update { it.copy(screen = Screen.Setup) }
            runSetup()
        } else {
            SyncScheduler.runNow(context)
            _state.update { it.copy(screen = Screen.Sync) }
        }
    }

    fun runSetup() {
        val session = prefs.session ?: return signedOut()
        _state.update { it.copy(setupStep = AppText.s(R.string.vm_connecting), setupError = null, setupNeedsUpgrade = false) }
        viewModelScope.launch {
            try {
                val (connection, outcome) = indexes.ensure(session) { step -> _state.update { it.copy(setupStep = step) } }
                if (outcome == IndexManager.Outcome.NEEDS_CHOICE) {

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

    fun chooseDevice(index: AccountIndex) {
        prefs.chosenIndexName = index.name
        prefs.connection = null
        _state.update { it.copy(deviceChoices = emptyList(), screen = Screen.Setup) }
        runSetup()
    }

    fun chooseNewDevice() {
        prefs.chosenIndexName = indexes.ownIndexName
        prefs.connection = null
        _state.update { it.copy(deviceChoices = emptyList(), screen = Screen.Setup) }
        runSetup()
    }

    suspend fun personSuggestions(typed: String, onPhoto: Collection<String>): List<String> =
        withContext(Dispatchers.IO) { localWords(typed, onPhoto).second }

    suspend fun tagSuggestions(typed: String, onPhoto: Collection<String>): com.opensolr.photos.search.TagSuggestions =
        withContext(Dispatchers.IO) {
            val (tags, _, meanings) = localWords(typed, onPhoto)
            val skip = (onPhoto + tags).map { com.opensolr.photos.data.Words.fold(it) }.toSet()
            com.opensolr.photos.search.TagSuggestions(tags, meanings.filter { com.opensolr.photos.data.Words.fold(it) !in skip }.take(SUGGEST_WORDS))
        }

    private fun cachedWordCounts(): Triple<Map<String, Int>, Map<String, Int>, Map<String, Int>> {
        val now = System.currentTimeMillis()
        val held = wordCounts
        if (held != null && now - wordCountsAt < WORD_COUNTS_MS) return held
        val fresh = photoCache.wordCounts()
        wordCounts = fresh
        wordCountsAt = now
        return fresh
    }

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

    fun applySuggestion(text: String) {
        suggestJob?.cancel()
        _state.update { it.copy(query = text, suggestions = emptyList()) }
        search(reset = true)
    }

    fun search(reset: Boolean, keepPosition: Boolean = false, keepLoaded: Boolean = false) {
        val current = _state.value

        if (!reset && current.duplicatesMode) { loadMoreDuplicates(); return }
        if (!reset && current.skippedMode) return
        if (!reset && (current.searching || current.endReached)) return
        searchJob?.cancel()
        val start = if (reset) 0 else current.hits.size
        suggestJob?.cancel()

        if (reset && browsingLocally()) { browseLocally(keepPosition); return }
        if (groupedView(current)) {
            if (reset) searchGrouped(keepPosition)
            return
        }
        _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList(), duplicateGroups = emptyList(), duplicatesMode = false, skippedMode = false, similarToId = null, similarToHit = null, resultGroups = emptyList(), searchGeneration = if (reset && !keepPosition) it.searchGeneration + 1 else it.searchGeneration) }

        if (reset) loadQueryFacets(current.query, current.filters)
        searchJob = viewModelScope.launch {
            try {

                val rows = if (keepLoaded) (current.hits.size + RELOAD_MARGIN).coerceIn(SearchRepository.PAGE, RELOAD_MAX) else SearchRepository.PAGE
                val page = searches.search(current.query, current.filters, start, rows = rows, freshBias = current.freshBias, wordsOnly = current.wordsOnly)
                _state.update {

                    val hits = if (reset && keepPosition && it.hits.isNotEmpty()) {

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

                        facets = if (reset || page.facets.isNotEmpty()) page.facets else it.facets,
                        smart = page.smart,
                        searchNotice = page.notice,
                        didYouMean = if (reset) page.didYouMean else it.didYouMean,
                        resultsGeneration = if (reset && !keepPosition) it.resultsGeneration + 1 else it.resultsGeneration,
                        searchedQuery = if (reset) current.query else it.searchedQuery,
                        endReached = hits.size >= page.numFound || page.hits.isEmpty(),
                    )
                }

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

    private fun groupedView(s: UiState): Boolean =
        s.groupBy != GroupBy.RELEVANCE && !s.duplicatesMode && !s.skippedMode && (s.query.isNotBlank() || s.filters.count > 0)

    fun toggleMeZone(key: String) {
        _state.update { it.copy(meZonesOpen = if (key in it.meZonesOpen) it.meZonesOpen - key else it.meZonesOpen + key) }
    }

    fun toggleStatsSection(key: String) {
        val next = _state.value.statsOpen.let { if (key in it) it - key else it + key }
        prefs.statsOpen = next
        _state.update { it.copy(statsOpen = next) }
    }

    fun setStatsOpen(keys: Set<String>) {
        prefs.statsOpen = keys
        _state.update { it.copy(statsOpen = keys) }
    }

    fun setGroupBy(how: GroupBy) {
        if (how == _state.value.groupBy) return
        prefs.groupBy = how.key
        _state.update { it.copy(groupBy = how) }
        if (_state.value.duplicatesMode && _state.value.similarToId != null) loadDuplicates(debounceMs = 0) else search(reset = true)
    }

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

                        val regionShown = region.isNotEmpty() && fold(region) != fold(country)
                        val parent = if (regionShown) region else country
                        if (regionShown) out += ResultGroup(1, "place:$country/$region", region, places.values.flatten())

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

    private class FolderNode(val name: String) {
        val children = LinkedHashMap<String, FolderNode>()
        val own = ArrayList<String>()
        val all = ArrayList<String>()
    }

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

            } finally {
                groupsLoading.remove("g:$name")
            }
        }
    }

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

    fun toggleAlbumSelected(album: com.opensolr.photos.search.Album) {
        val key = "${album.field}:${album.value}"
        _state.update { it.copy(selectedAlbums = if (key in it.selectedAlbums) it.selectedAlbums - key else it.selectedAlbums + key) }
    }

    fun toggleSectionSelected(title: String) {
        _state.update { it.copy(selectedSections = if (title in it.selectedSections) it.selectedSections - title else it.selectedSections + title) }
    }

    fun selectAllAlbums(all: Boolean) {
        _state.update {
            it.copy(selectedAlbums = emptySet(), selectedSections = if (all) it.albums.map { s -> s.title }.toSet() else emptySet())
        }
    }

    fun clearAlbumSelection() {
        _state.update { it.copy(selectedAlbums = emptySet(), selectedSections = emptySet()) }
    }

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

    fun albumPhotosDeleted(ids: Set<String>) {
        clearAlbumSelection()
        removeDeleted(ids)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            openAlbums(force = true)
        }
    }

    fun openAlbum(album: com.opensolr.photos.search.Album) = openFiltered(album.field, album.value)

    var statsScroll: Pair<Int, Int> = 0 to 0

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

    fun openStats() {
        _state.update { it.copy(screen = Screen.Stats, statsLoading = true, returnToStats = false) }
        viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { photoCache.libraryStats() }
            _state.update { it.copy(stats = stats, statsLoading = false, statsPartial = !prefs.cloneComplete) }
        }
    }

    fun openMap(focus: MapFocus? = null) {
        _state.update { it.copy(screen = Screen.Map, mapFocus = focus) }
        loadPins()
    }

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

    fun searchNear(lat: Double, lon: Double, radiusKm: Double) {
        _state.update { it.copy(screen = Screen.Search, filters = it.filters.copy(near = NearFilter(lat, lon, radiusKm))) }
        search(reset = true)
    }

    fun setFilters(filters: SearchFilters) {
        _state.update { it.copy(filters = filters) }
        search(reset = true)
    }

    fun forceResync() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        SyncScheduler.restartNow(context)
    }

    fun syncNow() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) return
        SyncScheduler.runNow(context)
    }

    fun rereadAll() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }

        prefs.rereadAllSince = System.currentTimeMillis() - 10 * 60 * 1000L
        SyncScheduler.restartNow(context)
    }

    fun resetIndex() {

        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        viewModelScope.launch {
            try {
                val connection = prefs.connection ?: return@launch
                com.opensolr.photos.net.SolrClient(connection).deleteAll()

                searches.clearCache()
                withContext(Dispatchers.IO) { photoCache.clearDocs() }
                prefs.cloneComplete = true
            } catch (e: Exception) {
                _state.update { it.copy(notice = AppText.s(R.string.vm_empty_failed, friendlyMessage(e, AppText.s(R.string.vm_try_again)))) }
                return@launch
            }

            _state.update { it.copy(hits = emptyList(), numFound = 0, duplicateGroups = emptyList(), duplicatesMode = false, similarToId = null, similarToHit = null) }
            SyncScheduler.runNow(context)
        }
    }

    fun rebuildOcr() {
        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        viewModelScope.launch {

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

    fun stopSync() {

        com.opensolr.photos.sync.SyncWorker.stopRequested.set(true)
        SyncScheduler.stopNow(context)
        _state.update { it.copy(notice = AppText.s(R.string.vm_sync_stopped)) }
    }

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

    private var dragBase: Set<String>? = null

    private var dragAnchor: Pair<String, Boolean>? = null

    fun beginDragSelect(id: String) {
        val current = _state.value
        val base = if (current.selecting) current.selectedIds else emptySet()
        val unselecting = id in base
        dragBase = base
        dragAnchor = id to unselecting
        applyDragSelection(base, listOf(id), unselecting)
    }

    fun dragSelectTo(ids: Collection<String>) {
        val base = dragBase ?: return
        applyDragSelection(base, ids, dragAnchor?.second == true)
    }

    fun endDragSelect(movedAway: Boolean) {
        dragBase = null
        dragAnchor = null
        _state.update {
            if (it.selectedIds.isEmpty()) it.copy(selecting = false, selectedOffscreen = emptyMap(), selectedGroups = emptyMap()) else it
        }
    }

    // A drag that starts on a ticked photo unticks the range it covers; on an unticked one it ticks it.
    private fun applyDragSelection(base: Set<String>, ids: Collection<String>, unselecting: Boolean) {
        val next = if (unselecting) base - ids.toSet() else base + ids
        _state.update { it.copy(selecting = true, selectedIds = next) }
    }

    fun setSelecting(on: Boolean) {
        _state.update { it.copy(selecting = on, selectedIds = if (on) it.selectedIds else emptySet(), selectedOffscreen = if (on) it.selectedOffscreen else emptyMap(), selectedGroups = if (on) it.selectedGroups else emptyMap()) }
    }

    fun toggleSelected(id: String) {
        _state.update {
            val next = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id
            if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
            else it.copy(selecting = true, selectedIds = next)
        }
    }

    fun toggleSelectedGroup(key: String, ids: List<String>, range: Pair<Long, Long>? = null) {
        val current = _state.value
        val already = current.selectedGroups[key]
        if (already != null) {

            _state.update {
                val next = it.selectedIds - already
                if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
                else it.copy(selectedIds = next, selectedOffscreen = it.selectedOffscreen - already, selectedGroups = it.selectedGroups - key)
            }
            return
        }
        if (range == null) {

            if (ids.isEmpty()) return
            val all = current.selectedIds.containsAll(ids)
            _state.update {
                val next = if (all) it.selectedIds - ids.toSet() else it.selectedIds + ids
                if (next.isEmpty()) it.copy(selecting = false, selectedIds = emptySet(), selectedOffscreen = emptyMap(), selectedGroups = emptyMap())
                else it.copy(selecting = true, selectedIds = next)
            }
            return
        }

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

                _state.update { it.copy(selectingGroup = false) }
            }
        }
    }

    fun showDuplicates() {
        if (_state.value.duplicatesMode) { clearDuplicates(); return }

        _state.update {
            it.copy(
                duplicatesMode = true, skippedMode = false, similarToId = null, similarToHit = null,
                duplicateLevel = it.duplicateLevel.coerceAtLeast(com.opensolr.photos.search.SearchRepository.FIRST_LIBRARY_LEVEL),
            )
        }
        loadDuplicates(debounceMs = 0)
    }

    fun showSimilar(hit: PhotoHit) {
        _state.update {

            it.copy(screen = Screen.Search, duplicatesMode = true, duplicateLevel = 0, similarToId = hit.id, similarToHit = hit)
        }
        loadDuplicates(debounceMs = 0)
    }

    fun setDuplicateLevel(level: Int) {
        val first = com.opensolr.photos.search.SearchRepository.FIRST_LIBRARY_LEVEL
        val clamped = level.coerceIn(first, com.opensolr.photos.search.SearchRepository.DUPLICATE_FIELDS.size - 1)
        if (clamped == _state.value.duplicateLevel && _state.value.duplicateGroups.isNotEmpty()) return
        _state.update { it.copy(duplicateLevel = clamped) }
        if (_state.value.duplicatesMode) loadDuplicates(debounceMs = 300)
    }

    private fun loadDuplicates(debounceMs: Long, keepPages: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounceMs > 0) kotlinx.coroutines.delay(debounceMs)
            val level = _state.value.duplicateLevel
            val anchor = _state.value.similarToId

            val hadGroups = _state.value.duplicateGroupsLoaded
            _state.update { it.copy(searching = true, searchError = null, suggestions = emptyList()) }
            try {

                val hits: List<PhotoHit>
                val groups: List<Int>
                val loaded: Int
                val done: Boolean
                val total: Int
                var similarGroups = emptyList<ResultGroup>()
                if (anchor != null) {
                    val (h, g) = searches.similarTo(anchor, level)
                    hits = h; groups = g; loaded = g.size; done = true; total = g.size

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

                    var used = first.groupsUsed
                    var end = first.endReached

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

    fun refresh() {

        when {
            _state.value.skippedMode -> loadSkipped()

            _state.value.duplicatesMode -> loadDuplicates(debounceMs = 0, keepPages = true)
            else -> search(reset = true, keepPosition = true, keepLoaded = true)
        }
    }

    fun forceRefresh(toTop: Boolean = false) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { searches.clearCache() }
            _state.update { it.copy(cachedCount = 0) }

            if (toTop && !_state.value.duplicatesMode && !_state.value.skippedMode) search(reset = true, keepLoaded = true)
            else refresh()
        }
    }

    private fun clearDuplicates() {
        if (!_state.value.duplicatesMode) return
        _state.update { it.copy(duplicatesMode = false, duplicateGroups = emptyList(), similarToId = null, similarToHit = null, searchNotice = null) }
        search(reset = true)
    }

    fun setFreshBias(on: Boolean) {
        if (_state.value.freshBias == on) return
        _state.update { it.copy(freshBias = on) }
        search(reset = true)
    }

    fun setWordsOnly(on: Boolean) {
        if (_state.value.wordsOnly == on) return
        _state.update { it.copy(wordsOnly = on) }
        search(reset = true)
    }

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

                com.opensolr.photos.net.SolrClient(connection).delete(ids, now = true)
                withContext(Dispatchers.IO) { photoCache.removeDocs(ids); photoCache.clearActions(ids) }
            } catch (e: Exception) {

            }

            searches.clearCache()

            if (_state.value.screen == Screen.Search) refresh()
        }
    }

    fun resyncSelected() {
        val ids = _state.value.selectedIds
        if (ids.isEmpty()) return
        prefs.resyncIds = prefs.resyncIds + ids
        _state.update { it.copy(selecting = false, selectedIds = emptySet()) }

        if (_state.value.sync.running || com.opensolr.photos.sync.SyncWorker.running.get()) {
            _state.update { it.copy(showBusyDialog = true) }
            return
        }
        SyncScheduler.restartNow(context)
    }

    fun dismissBusyDialog() {
        _state.update { it.copy(showBusyDialog = false) }
    }

    fun setSchedule(schedule: SyncSchedule) {
        prefs.schedule = schedule
        SyncScheduler.applySchedule(context, schedule)
        _state.update { it.copy(schedule = schedule) }
    }

    suspend fun searchPlaces(query: String): List<com.opensolr.photos.net.PlaceHit> {
        val session = prefs.session ?: return emptyList()

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

            api.searchPlaces(session, q).also {
                if (it.isNotEmpty()) cache.put(key, com.opensolr.photos.net.PlaceHit.listToJson(it))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

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

    fun signOut() {
        SyncScheduler.cancelAll(context)
        viewModelScope.launch(Dispatchers.IO) { PhotoCache.of(context).clear() }
        signedOut(null)
    }

    fun open(screen: Screen) {
        _state.update { it.copy(screen = screen) }
        if (screen == Screen.Account) refreshAccount()
    }

    fun openIfSignedIn(screen: Screen) {
        if (prefs.session != null && prefs.connection != null) open(screen)
    }

    fun back() {
        val current = _state.value
        when (current.screen) {

            Screen.Map, Screen.Albums, Screen.Stats -> {
                if (current.selectedAlbums.isNotEmpty() || current.selectedSections.isNotEmpty()) {
                    clearAlbumSelection()
                    return
                }
                _state.update { it.copy(screen = Screen.Search) }
                targetScroll()
            }

            Screen.Sync, Screen.Account -> {
                _state.update { it.copy(screen = Screen.Search) }

                refresh()
            }
            Screen.Search -> if (current.returnToStats) openStats()
            Screen.Folders -> if (current.foldersReturnTo == Screen.Sync) _state.update { it.copy(screen = Screen.Sync) }
            else -> Unit
        }
    }

    private fun onSyncFinished() {
        val report = prefs.lastReport

        if (mediaChangedDuringSync) {
            mediaChangedDuringSync = false
            SyncScheduler.runNow(context)
        }

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

        val wrote = report == null || report.added > 0 || report.deleted > 0

        if (wrote) prefs.facetsJson = null
        if (wrote && _state.value.screen == Screen.Search) refresh()
    }

    private fun signedOut(message: String? = null) {
        prefs.signOut()
        _state.update {
            UiState(screen = Screen.SignIn, notice = message, schedule = prefs.schedule, selectedFolders = prefs.folders, sync = it.sync)
        }
    }

    companion object {

        private const val UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private val CODE_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

        private const val REPAGE_MAX = 10

        private const val FLASH_MS = 4000L

        private const val FOLDER_LEVELS = 3

        private const val SPINNER_MS = 350L

        private const val MEDIA_SETTLE_MS = 1500L

        private const val PROGRESS_MS = 100L

        private const val WORD_COUNTS_MS = 3000L

        private const val SUGGEST_FEW = 5
        private const val SUGGEST_MANY = 8

        private const val SUGGEST_WORDS = 5

        private const val RELOAD_MAX = 1000

        private const val RELOAD_MARGIN = 100

    }
}
