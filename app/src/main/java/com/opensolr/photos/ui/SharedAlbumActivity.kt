package com.opensolr.photos.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.opensolr.photos.BuildConfig
import com.opensolr.photos.R
import com.opensolr.photos.net.Http
import com.opensolr.photos.ui.theme.LocalPalette
import com.opensolr.photos.ui.theme.OpensolrPhotosTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * An album link (https://photos.opensolr.com/s/<token>) opened on a phone that has the app: the album of
 * whoever shared it, read only, through the link's own public calls. The reader's own account plays no part.
 */
class SharedAlbumActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val token = data?.pathSegments?.takeIf { it.size >= 2 && it[0] == "s" }?.get(1)?.takeIf { TOKEN.matches(it) }
        if (token == null) {
            // not an album address the app knows: the browser opens it
            data?.let { runCatching { startActivity(Intent(Intent.ACTION_VIEW, it).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browserPackage(it))) } }
            finish()
            return
        }
        setContent { OpensolrPhotosTheme { SharedAlbum(SharedAlbumSource(token), onClose = { finish() }) } }
    }

    /** A browser that opens [uri], never this app again. */
    private fun browserPackage(uri: Uri): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/"))
        return packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName?.takeIf { it != packageName && it != "android" }
            ?: packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, uri), 0).map { it.activityInfo.packageName }.firstOrNull { it != packageName }
    }

    companion object {
        val TOKEN = Regex("^[A-Za-z0-9]{12}$")
    }
}

/** One cell of the album: a photo, a clip, or a PDF by its first page. */
internal data class SharedItem(
    val id: String,
    val name: String,
    val mime: String,
    val kind: String,
    val takenAt: String?,
    val durationMs: Long,
    val pages: Int,
    val parent: String?,
    val r: String?,
    val o: String?,
    val t: String?,
) {
    val isVideo get() = kind == "video"
    val isPdf get() = kind == "pdf_page"
}

/** The link's own calls on photos.opensolr.com, the same ones its web page makes. */
internal class SharedAlbumSource(val token: String) {

    val base = "https://photos.opensolr.com/s/$token"

    class Gone(val expired: Boolean) : Exception()

    private fun post(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder().url("$base/$path")
            .header("X-Photos", "1")
            .header("User-Agent", "OpensolrPhotos/${BuildConfig.VERSION_NAME} (Android)")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 404 || response.code == 410) throw Gone(response.code == 410)
            if (response.code != 200) throw java.io.IOException("Opensolr answered HTTP ${response.code}")
            return JSONObject(text)
        }
    }

    /** The album's name and how many items it shows now. */
    suspend fun info(): Pair<String, Int> = withContext(Dispatchers.IO) {
        val j = post("info", JSONObject())
        j.optString("title") to j.optInt("count")
    }

    /** [rows] items from [start], newest first; a PDF is one item, by its first page. */
    suspend fun page(start: Int, rows: Int): Pair<List<SharedItem>, Int> = withContext(Dispatchers.IO) {
        select(listOf("q" to "*:*", "fq" to "*:* -(+kind_s:pdf_page -page_i:1)", "sort" to "taken_at desc, id asc", "start" to start.toString(), "rows" to rows.toString()))
    }

    /** Every page of the PDF [parent] that is in the album, in order. */
    suspend fun pdfPages(parent: String): List<SharedItem> = withContext(Dispatchers.IO) {
        select(listOf("q" to "*:*", "fq" to "parent_s:$parent", "sort" to "page_i asc", "rows" to "2000")).first
    }

    private fun select(params: List<Pair<String, String>>): Pair<List<SharedItem>, Int> {
        val pairs = JSONArray()
        (params + ("fl" to FIELDS)).forEach { (k, v) -> pairs.put(JSONArray(listOf(k, v))) }
        val j = post("select", JSONObject().put("params", pairs))
        val response = j.optJSONObject("response") ?: throw java.io.IOException("The album sent no answer")
        val links = j.optJSONObject("pw")
        val docs = response.optJSONArray("docs") ?: JSONArray()
        val out = ArrayList<SharedItem>(docs.length())
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val id = d.optString("id").takeIf { ID.matches(it) } ?: continue
            val l = links?.optJSONObject(id)
            out += SharedItem(
                id = id,
                name = d.optString("file_name"),
                mime = d.optString("mime"),
                kind = d.optString("kind_s"),
                takenAt = d.optString("taken_at").takeIf { it.isNotEmpty() },
                durationMs = d.optLong("duration_l"),
                pages = d.optInt("pages_i"),
                parent = d.optString("parent_s").takeIf { ID.matches(it) },
                // no signed links (a WebDAV backend): the link's own address reads the files from it
                r = l?.optString("r")?.takeIf { it.isNotEmpty() } ?: "$base/media/$id?v=r",
                o = l?.optString("o")?.takeIf { it.isNotEmpty() } ?: "$base/media/$id?v=o",
                t = l?.optString("t")?.takeIf { it.isNotEmpty() },
            )
        }
        return out to response.optInt("numFound")
    }

    /** The small grid pictures of [ids] (made the first time on the owner's bucket): id → address. */
    suspend fun thumbs(ids: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyMap()
        val t = post("thumbs", JSONObject().put("ids", JSONArray(ids))).optJSONObject("t") ?: return@withContext emptyMap()
        t.keys().asSequence().associateWith { t.optString(it) }.filterValues { it.isNotEmpty() }
    }

    companion object {
        val ID = Regex("^[a-f0-9]{32}$")
        const val FIELDS = "id,file_name,folder,mime,taken_at,kind_s,duration_l,page_i,pages_i,parent_s"
        const val ROWS = 60
    }
}

private sealed interface AlbumState {
    data object Loading : AlbumState
    data class Gone(val expired: Boolean) : AlbumState
    data class Failed(val message: String) : AlbumState
    data object Ready : AlbumState
}

@Composable
private fun SharedAlbum(source: SharedAlbumSource, onClose: () -> Unit) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<AlbumState>(AlbumState.Loading) }
    var title by remember { mutableStateOf("") }
    var total by remember { mutableStateOf(0) }
    val items = remember { mutableStateListOf<SharedItem>() }
    val thumbs = remember { mutableStateMapOf<String, String>() }
    var found by remember { mutableStateOf(Int.MAX_VALUE) }
    var loadingMore by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Int?>(null) }
    var attempt by remember { mutableStateOf(0) }

    // the next page of the album, with the small pictures its items still lack
    suspend fun more() {
        if (loadingMore || items.size >= found) return
        loadingMore = true
        try {
            val (got, n) = source.page(items.size, SharedAlbumSource.ROWS)
            found = if (got.isEmpty()) items.size else n
            items += got
            got.forEach { it.t?.let { t -> thumbs[it.id] = t } }
            val missing = got.filter { it.t == null }.map { it.id }
            if (missing.isNotEmpty()) runCatching { source.thumbs(missing) }.getOrNull()?.let { thumbs.putAll(it) }
        } finally {
            loadingMore = false
        }
    }

    LaunchedEffect(attempt) {
        state = AlbumState.Loading
        items.clear(); thumbs.clear(); found = Int.MAX_VALUE
        state = try {
            val (t, c) = source.info()
            title = t; total = c
            more()
            AlbumState.Ready
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: SharedAlbumSource.Gone) {
            AlbumState.Gone(e.expired)
        } catch (e: Exception) {
            AlbumState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Box(Modifier.fillMaxSize().background(p.paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cp_back), tint = p.ink) }
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title.ifBlank { stringResource(R.string.sa_title) }, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state == AlbumState.Ready) Text(pluralStringResource(R.plurals.sa_items, total, Actions.formatCount(total.toLong())) + " · " + stringResource(R.string.sa_read_only), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                }
            }
            when (val s = state) {
                AlbumState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                is AlbumState.Gone -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Notice(stringResource(if (s.expired) R.string.sa_expired_text else R.string.sa_gone_text), title = stringResource(if (s.expired) R.string.sa_expired else R.string.sa_gone))
                }
                is AlbumState.Failed -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Notice(stringResource(R.string.sa_failed, s.message))
                    Spacer(Modifier.size(16.dp))
                    AccentButton(stringResource(R.string.sa_retry), onClick = { attempt++ }, modifier = Modifier.fillMaxWidth())
                }
                AlbumState.Ready -> AlbumGrid(items, thumbs, loadingMore, onOpen = { open = it }, onNearEnd = { scope.launch { runCatching { more() } } })
            }
        }
        open?.let { start ->
            BackHandler { open = null }
            AlbumViewer(source, items, start, onNearEnd = { scope.launch { runCatching { more() } } }, onClose = { open = null })
        }
    }
}

/** The album by day, newest first, three or more to a row as the screen allows. */
@Composable
private fun AlbumGrid(items: List<SharedItem>, thumbs: Map<String, String>, loadingMore: Boolean, onOpen: (Int) -> Unit, onNearEnd: () -> Unit) {
    val p = LocalPalette.current
    val grid = rememberLazyGridState()
    val days = remember(items.size) { items.map { dayOf(it.takenAt) } }
    LaunchedEffect(grid, items.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 20) onNearEnd()
        }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { Notice(stringResource(R.string.sa_empty)) }
        return
    }
    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        state = grid,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }, span = { _, _ -> androidx.compose.foundation.lazy.grid.GridItemSpan(1) }) { i, item ->
            Column {
                Box(Modifier.aspectRatio(1f).background(p.chip).clickable { onOpen(i) }) {
                    AsyncImage(model = thumbs[item.id] ?: item.r, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    val badge = when {
                        item.isVideo -> Images.duration(item.durationMs)
                        item.isPdf -> if (item.pages > 1) pluralStringResource(R.plurals.sa_pages, item.pages, item.pages) else "PDF"
                        else -> null
                    }
                    if (badge != null) Text(
                        badge, color = Color.White, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent, modifier = Modifier.size(24.dp)) }
        }
    }
    // the day of the item at the top, over the grid
    val top = grid.firstVisibleItemIndex.coerceIn(0, days.size - 1)
    days.getOrNull(top)?.takeIf { it.isNotEmpty() }?.let { day ->
        Text(day, style = MaterialTheme.typography.labelLarge, color = p.ink, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(p.band).padding(horizontal = 8.dp, vertical = 4.dp))
    }
    }
}

/** One item at a time, swipe for the next: a photo zooms, a clip plays, a PDF scrolls through its pages. */
@Composable
private fun AlbumViewer(source: SharedAlbumSource, items: List<SharedItem>, start: Int, onNearEnd: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start) { items.size }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { if (it >= items.size - 5) onNearEnd() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, key = { items[it].id }, modifier = Modifier.fillMaxSize()) { i ->
            val item = items[i]
            when {
                item.isVideo -> if (pager.currentPage == i) ExternalClip(Uri.parse(item.o)) else AsyncImage(model = item.r, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                item.isPdf -> PdfPages(source, item)
                else -> ExternalPhoto(item.o ?: item.r ?: "")
            }
        }
        val item = items.getOrNull(pager.currentPage)
        Row(Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cp_back), tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(item?.name.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                item?.takenAt?.let { Text(whenOf(it), color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
            }
            // the file itself (the PDF, the clip, the photo at full size), in the app the phone opens it with
            item?.o?.let { o ->
                IconButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(o), item.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(o))) } }
                }) { Icon(painterResource(R.drawable.ic_open), contentDescription = stringResource(R.string.sa_open_file), tint = Color.White, modifier = Modifier.size(24.dp)) }
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** The pages of a PDF in the album, one under the other. */
@Composable
private fun PdfPages(source: SharedAlbumSource, item: SharedItem) {
    var pages by remember(item.id) { mutableStateOf(listOf(item)) }
    LaunchedEffect(item.id) {
        val parent = item.parent ?: return@LaunchedEffect
        runCatching { source.pdfPages(parent) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { pages = it }
    }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(pages, key = { it.id }) { page ->
            AsyncImage(model = page.r, contentDescription = null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth().background(Color.White))
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

private val DAY = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

/** The day an item was taken, in the reader's own time zone. */
private fun dayOf(iso: String?): String =
    iso?.let { runCatching { Instant.parse(it).atZone(ZoneId.systemDefault()).format(DAY) }.getOrNull() }.orEmpty()

/** Date and time an item was taken, in the reader's own time zone and format. */
private fun whenOf(iso: String): String =
    runCatching { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(java.util.Date.from(Instant.parse(iso))) }.getOrDefault("")
