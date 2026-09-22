package com.opensolr.photos.ui.screens

import com.opensolr.photos.ui.tapClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.photos.R
import com.opensolr.photos.data.LibraryStats
import com.opensolr.photos.data.StatRow
import com.opensolr.photos.ui.AppViewModel
import com.opensolr.photos.ui.Haptics
import com.opensolr.photos.ui.Notice
import com.opensolr.photos.ui.ScreenHeader
import com.opensolr.photos.ui.UiState
import com.opensolr.photos.ui.theme.LocalPalette
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

private val Corner = RoundedCornerShape(2.dp)

private val STAT_SECTIONS = listOf("overview", "years", "months", "weekdays", "hours", "people", "tags", "things", "countries", "cities", "cameras")

private class Folds(val keys: Set<String>, val toggle: (String) -> Unit)

private const val TABLE_ROWS = 10

private const val THINGS_ROWS = 50

private val COUNT_WIDTH = 72.dp

private val SHARE_WIDTH = 76.dp

private const val CHART_BARS = 10

@Composable
fun StatsScreen(state: UiState, viewModel: AppViewModel) {
    val p = LocalPalette.current
    val view = LocalView.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val folded = Folds(STAT_SECTIONS.toSet() - state.statsOpen) { viewModel.toggleStatsSection(it) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val open: (StatRow) -> Unit = { row ->
        val field = row.field
        val value = row.value
        if (field != null && value != null) {
            Haptics.tick(view, strong = false)
            viewModel.openFiltered(field, value, fromStats = true)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(start = 8.dp)) { ScreenHeader(stringResource(R.string.st_title), onBack = { viewModel.back() }) }
        if (state.statsLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.chip)
        }
        val stats = state.stats
        if (stats == null || (stats.total == 0 && !state.statsLoading)) {
            if (!state.statsLoading) {
                Text(
                    stringResource(R.string.st_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
            return@Column
        }
        val anyFolded = STAT_SECTIONS.any { it !in state.statsOpen }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
            IconAction(
                icon = if (anyFolded) R.drawable.ic_expand_all else R.drawable.ic_collapse_all,
                label = if (anyFolded) stringResource(R.string.al_expand_all) else stringResource(R.string.al_collapse_all),
                onClick = {
                    Haptics.tick(view, strong = false)
                    viewModel.setStatsOpen(if (anyFolded) STAT_SECTIONS.toSet() else emptySet())
                },
            )
        }
        val listState = rememberLazyListState(viewModel.statsScroll.first, viewModel.statsScroll.second)
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { viewModel.statsScroll = it }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
        ) {
            if (state.statsPartial) {
                item(key = "partial") { Notice(stringResource(R.string.st_partial), modifier = Modifier.padding(bottom = 8.dp)) }
            }
            section("overview", R.string.st_overview, folded)
            if ("overview" !in folded.keys) {
                item(key = "tiles") { Overview(stats, locale) }
            }
            timeSections(stats, locale, folded, expanded, open)
            ranked("people", R.string.st_sec_people, R.string.st_col_person, stats.people, stats.total, locale, folded, expanded, open)
            ranked("tags", R.string.st_sec_tags, R.string.st_col_tag, stats.tags, stats.total, locale, folded, expanded, open)
            ranked("things", R.string.st_sec_things, R.string.st_col_thing, stats.things.take(THINGS_ROWS), stats.total, locale, folded, expanded, open, all = true)
            ranked("countries", R.string.st_sec_countries, R.string.st_col_country, stats.countries, stats.total, locale, folded, expanded, open)
            ranked("cities", R.string.st_sec_cities, R.string.st_col_city, stats.cities, stats.total, locale, folded, expanded, open)
            ranked("cameras", R.string.st_sec_cameras, R.string.st_col_camera, stats.cameras, stats.total, locale, folded, expanded, open)
        }
    }
}

private fun LazyListScope.section(key: String, titleRes: Int, folded: Folds) {
    item(key = "h:$key") {
        FilterGroup(stringResource(titleRes), active = 0, open = key !in folded.keys, onToggle = { folded.toggle(key) }) {}
    }
}

private fun LazyListScope.timeSections(
    stats: LibraryStats,
    locale: Locale,
    folded: Folds,
    expanded: MutableMap<String, Boolean>,
    open: (StatRow) -> Unit,
) {
    if (stats.years.isNotEmpty()) {
        section("years", R.string.st_sec_years, folded)
        if ("years" !in folded.keys) {
            val ascending = stats.years.reversed()
            item(key = "c:years") {
                ColumnChart(
                    values = ascending.map { it.count },
                    labels = ascending.map { it.label },
                    locale = locale,
                    description = stringResource(R.string.st_sec_years),
                    onTap = { open(ascending[it]) },
                )
            }
            table("years", R.string.st_col_year, stats.years, stats.total, locale, expanded, open)
        }
    }
    val dated = stats.total - stats.undated
    if (dated <= 0) return

    val symbols = DateFormatSymbols.getInstance(locale)
    val monthFormat = java.text.SimpleDateFormat("LLL", locale)
    val day = Calendar.getInstance()
    val monthNames = (0 until 12).map { m -> monthFormat.format(day.apply { clear(); set(2001, m, 1) }.time) }
    section("months", R.string.st_sec_months, folded)
    if ("months" !in folded.keys) {
        item(key = "c:months") {
            ColumnChart(stats.months.toList(), monthNames, locale, stringResource(R.string.st_sec_months), onTap = null)
        }
        val fullMonths = symbols.months
        table("months", R.string.st_col_month, stats.months.mapIndexed { i, n -> StatRow(fullMonths[i], n) }.filter { it.count > 0 }, stats.total, locale, expanded, open, all = true)
    }

    val first = Calendar.getInstance(locale).firstDayOfWeek - 1
    val order = (0 until 7).map { (first + it) % 7 }
    section("weekdays", R.string.st_sec_weekdays, folded)
    if ("weekdays" !in folded.keys) {
        item(key = "c:weekdays") {
            ColumnChart(order.map { stats.weekdays[it] }, order.map { symbols.shortWeekdays[it + 1] }, locale, stringResource(R.string.st_sec_weekdays), onTap = null)
        }
        table("weekdays", R.string.st_col_day, order.map { StatRow(symbols.weekdays[it + 1], stats.weekdays[it]) }.filter { it.count > 0 }, stats.total, locale, expanded, open, all = true)
    }

    section("hours", R.string.st_sec_hours, folded)
    if ("hours" !in folded.keys) {
        item(key = "c:hours") {
            ColumnChart(stats.hours.toList(), (0 until 24).map { it.toString() }, locale, stringResource(R.string.st_sec_hours), onTap = null)
        }
        table("hours", R.string.st_col_hour, stats.hours.mapIndexed { h, n -> StatRow(String.format(locale, "%02d:00", h), n) }.filter { it.count > 0 }, stats.total, locale, expanded, open, all = true)
    }
}

private fun LazyListScope.ranked(
    key: String,
    titleRes: Int,
    column: Int,
    rows: List<StatRow>,
    total: Int,
    locale: Locale,
    folded: Folds,
    expanded: MutableMap<String, Boolean>,
    open: (StatRow) -> Unit,
    all: Boolean = false,
) {
    if (rows.isEmpty()) return
    section(key, titleRes, folded)
    if (key in folded.keys) return
    item(key = "c:$key") { BarChart(rows.take(CHART_BARS), locale, open) }
    table(key, column, rows, total, locale, expanded, open, all)
}

private fun LazyListScope.table(
    key: String,
    column: Int,
    rows: List<StatRow>,
    total: Int,
    locale: Locale,
    expanded: MutableMap<String, Boolean>,
    open: (StatRow) -> Unit,
    all: Boolean = false,
) {
    val full = all || expanded[key] == true
    val shown = if (full) rows else rows.take(TABLE_ROWS)
    item(key = "th:$key") { TableHeader(stringResource(column)) }
    shown.forEachIndexed { i, row ->
        item(key = "t:$key:$i") { TableRow(row, total, locale, open) }
    }
    if (!all && rows.size > TABLE_ROWS) {
        item(key = "more:$key") {
            val p = LocalPalette.current
            val count = NumberFormat.getIntegerInstance(locale).format(rows.size)
            Text(
                if (full) stringResource(R.string.st_show_fewer) else stringResource(R.string.st_show_all, count),
                style = MaterialTheme.typography.labelLarge,
                color = p.accent,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(Corner)
                    .clickable { expanded[key] = !full }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
    }
    item(key = "gap:$key") { Spacer(Modifier.height(12.dp)) }
}

@Composable
private fun Overview(stats: LibraryStats, locale: Locale) {
    val context = LocalContext.current
    val number = NumberFormat.getIntegerInstance(locale)
    val tiles = buildList {
        add(Triple(number.format(stats.total), stringResource(R.string.st_photos), null))
        add(Triple(android.text.format.Formatter.formatShortFileSize(context, stats.bytes), stringResource(R.string.st_size), null))
        add(Triple(number.format(stats.tagged), stringResource(R.string.st_tagged), share(stats.tagged, stats.total, locale)))
        add(Triple(number.format(stats.withPeople), stringResource(R.string.st_with_people), share(stats.withPeople, stats.total, locale)))
        add(Triple(number.format(stats.withPlace), stringResource(R.string.st_with_place), share(stats.withPlace, stats.total, locale)))
        add(Triple(number.format(stats.withText), stringResource(R.string.st_with_text), share(stats.withText, stats.total, locale)))
        if (stats.unread > 0) add(Triple(number.format(stats.unread), stringResource(R.string.st_unread), share(stats.unread, stats.total, locale)))
        if (stats.undated > 0) add(Triple(number.format(stats.undated), stringResource(R.string.st_undated), share(stats.undated, stats.total, locale)))
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (value, label, part) -> Tile(value, label, part, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Tile(value: String, label: String, part: String?, modifier: Modifier) {
    val p = LocalPalette.current
    Column(
        modifier
            .clip(Corner)
            .background(p.band)
            .border(1.dp, p.hairline, Corner)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = p.muted)
        if (part != null) Text(part, style = MaterialTheme.typography.labelSmall, color = p.accent)
    }
}

@Composable
private fun ColumnChart(values: List<Int>, labels: List<String>, locale: Locale, description: String, onTap: ((Int) -> Unit)?) {
    if (values.isEmpty()) return
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall.copy(color = p.muted)
    val layouts = remember(labels, style) { labels.map { measurer.measure(it, style) } }
    val maxValue = values.max().coerceAtLeast(1)
    val maxLayout = remember(maxValue, style, locale) { measurer.measure(NumberFormat.getIntegerInstance(locale).format(maxValue), style) }
    val tap = if (onTap == null) Modifier else Modifier.pointerInput(values, labels) {
        detectTapGestures { at ->
            val index = (at.x / (size.width.toFloat() / values.size)).toInt()
            if (index in values.indices && values[index] > 0) onTap(index)
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .padding(top = 4.dp, bottom = 8.dp)
            .semantics { contentDescription = description }
            .then(tap)
    ) {
        val gapUnder = 6.dp.toPx()
        val labelHeight = layouts.maxOf { it.size.height }.toFloat()
        val top = maxLayout.size.height + 4.dp.toPx()
        val bottom = size.height - labelHeight - gapUnder
        val column = size.width / values.size
        val gap = (column * 0.25f).coerceAtMost(8.dp.toPx())
        drawLine(p.hairline, Offset(0f, top), Offset(size.width, top), 1.dp.toPx())
        drawText(maxLayout, topLeft = Offset(size.width - maxLayout.size.width, 0f))
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val h = ((bottom - top) * v / maxValue).coerceAtLeast(2.dp.toPx())
            drawRect(p.accent, topLeft = Offset(i * column + gap / 2, bottom - h), size = Size(column - gap, h))
        }
        drawLine(p.hairline, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
        val widest = layouts.maxOf { it.size.width } + 8.dp.toPx()
        val step = labelStep(widest / column)
        var lastEnd = -Float.MAX_VALUE
        layouts.forEachIndexed { i, layout ->
            if (i % step != 0) return@forEachIndexed
            val w = layout.size.width.toFloat()
            if (w > size.width) return@forEachIndexed
            val x = (i * column + column / 2 - w / 2).coerceIn(0f, size.width - w)
            if (x < lastEnd + 4.dp.toPx()) return@forEachIndexed
            drawText(layout, topLeft = Offset(x, bottom + gapUnder))
            lastEnd = x + w
        }
    }
}

private fun labelStep(columnsPerLabel: Float): Int {
    val needed = kotlin.math.ceil(columnsPerLabel.toDouble()).toInt().coerceAtLeast(1)
    return listOf(1, 2, 3, 4, 6, 8, 12).firstOrNull { it >= needed } ?: needed
}

@Composable
private fun BarChart(rows: List<StatRow>, locale: Locale, open: (StatRow) -> Unit) {
    val p = LocalPalette.current
    val number = NumberFormat.getIntegerInstance(locale)
    val max = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: return
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Corner)
                    .tapClickable(enabled = row.field != null) { open(row) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = p.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.36f),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth(row.count.toFloat() / max)
                            .height(16.dp)
                            .background(p.accent, Corner)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(number.format(row.count), style = MaterialTheme.typography.labelSmall, color = p.ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TableHeader(name: String) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.band)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.labelSmall, color = p.muted, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.st_col_photos),
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = COUNT_WIDTH).padding(start = 8.dp),
        )
        Text(
            stringResource(R.string.st_col_share),
            style = MaterialTheme.typography.labelSmall,
            color = p.muted,
            maxLines = 1,
            modifier = Modifier.width(SHARE_WIDTH).padding(start = 12.dp),
        )
        Spacer(Modifier.width(20.dp))
    }
}

@Composable
private fun TableRow(row: StatRow, total: Int, locale: Locale, open: (StatRow) -> Unit) {
    val p = LocalPalette.current
    val tappable = row.field != null
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .tapClickable(enabled = tappable) { open(row) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium, color = p.ink, modifier = Modifier.weight(1f))
            Text(
                NumberFormat.getIntegerInstance(locale).format(row.count),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = p.ink,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = COUNT_WIDTH).padding(start = 8.dp),
            )
            Text(
                share(row.count, total, locale),
                style = MaterialTheme.typography.bodySmall,
                color = p.muted,
                maxLines = 1,
                modifier = Modifier.width(SHARE_WIDTH).padding(start = 12.dp),
            )
            if (tappable) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.st_open), tint = p.accent, modifier = Modifier.size(20.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
    }
}

private fun share(count: Int, total: Int, locale: Locale): String {
    val format = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }
    if (total <= 0 || count <= 0) return format.format(0)
    val part = count.toDouble() / total
    return if (part < 0.005) "<" + format.format(0.01) else format.format(part)
}
