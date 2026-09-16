# Search

<p align="center">
  <img src="images/search-flow.svg" alt="How a search is built" width="100%">
</p>

Search goes straight from the app to the phone's index, in one `POST /select`.

## The header

The top of the photos screen is one row of small bordered buttons, each an icon with a label, left to right:

| Button | What it does |
|---|---|
| **Opensolr** | The app logo; opens [opensolr.com/admin/solr_manager](https://opensolr.com/admin/solr_manager) in the default browser |
| **Me** | The account screen |
| **Sync** | The Sync screen ([sync](sync.md)) |
| **Map** | The [map](map.md) of the current search |
| **Albums** | The [albums](albums.md) |
| **Select** | Turns selection on; it reads **Done** while selecting |
| **Search** | Opens the search line |

The search box is not on screen until it is asked for: the magnifier opens one compact line with the
filters button on it, and tapping the magnifier again puts it away and clears the query. A query that is in
force keeps the line on screen by itself (`searchOpen || state.query.isNotBlank()`), so coming back from
albums, duplicates or similar photos shows the words the results answer to instead of only remembering them.

## Empty search box

Every photo, newest first: `q=*:*`, `sort=taken_at desc, id asc`. The grid is grouped under date headings,
on two levels. *Today*, *Yesterday* and the weekdays of the last six days stand on their own: each of them is
already a day. Anything older is a month — *September*, or *September 2025* once the year has turned — with
the days inside it under headings of their own, *Saturday 5*, *Friday 4*. A month whose photos all fall on
one day is not split.

A bar down the right edge (`FastScroller`) drags the grid: one movement crosses months, with the month
beside the finger. Crossing a heading gives haptic feedback — the heavier constant for a month, the lighter
one for a day — played through the view (`Haptics.tick`), which needs no VIBRATE permission and obeys the
phone's own haptics setting. Its visuals fade when the grid stops, but the strip stays touchable: gated on
the same animation there would be nothing to grab from a standing start.

The grouping is done on the phone in `buildRows`, from `taken_at`, which every hit already carries; it costs
no request. A heading folds away with a tap and then says how many it hides; while selecting, a tap on a
heading ticks its whole group — a month, or one day of it — meaning the photos loaded so far.

## Typed search

What you type is trimmed to 300 characters and sent **only as the bound parameter `uq`**:

```
uq          = dog on the beach
lexicalRaw  = {!edismax qf="custom_tags_text^5 meaning^3 text file_name_text folder_text camera_text place_text" mm="2<65% 4<50% 8<40%" v=$uq}
```

On a plan with vector search, the app first asks `embed` (with `is_query=1`) for the query's vector and
hands both legs to Opensolr's `{!hybrid}` parser, the one search.opensolr.com runs:

```
vectorQuery = {!knn f=embeddings topK=500}[0.0132, -0.0481, …]
q           = {!hybrid lexical=$lexicalRaw vector=$vectorQuery mode=union alpha=0.5 topN=500}
```

Each leg is scored on its own, normalised per query (BM25 to its maximum, kNN min-max over the candidates)
and blended: `score = 0.5 * vector + 0.5 * lexical`. So a photo that matches the words (a tag, a name, a
place) always ranks above one that only resembles them in meaning, and among word matches the meaning
decides. The site search leans further toward meaning (alpha 0.85); photos carry hand-written tags, so the
app blends evenly.

Without vector search, or when the month's AI requests are used up, or when the vector service does not
answer, the same request runs with `q={!bool should=$lexicalRaw}` and the app says so above the results.
An index still on an older configuration (reset postponed) answers 400 to the newest fields; the app
then retries once without them.

`meaning` holds the labels CLIP gave the photo. `text` also collects the file name, the folder, the camera,
the place and your tags, so *pixel* or *screenshots* find what you would expect.

## The people in a photo

If something has already recognised the faces on your photos — Google Photos, Lightroom, digiKam, Apple
Photos — it writes the names onto the files themselves, in the XMP property `PersonInImage`. The app reads
them from the file and indexes them as `persons_t`, copied into `text`, so typing a name finds that
person's photos.

Nothing recognises faces here: no face is measured, compared or stored, on the phone or on Opensolr. The
names are read the way a file name is read, and a photo that carries none is indexed exactly as before.
Accent folding applies as everywhere else, so a name written with diacritics is found without them and the
other way round.

The names are read on the phone rather than carried on the 640 px copy: `ExifInterface` converts the XMP
packet to a `String` as ASCII, which turns a name with diacritics into question marks. `PhotoReader
.personsIn` decodes the raw bytes as UTF-8 and the names travel to `photos_ingest` as JSON, in the `persons`
field of the photo.

## The text printed in a photo

`text` also collects `ocr_t`: the words printed **in** the photo, read on Opensolr's side when a photo
carries any. This is what makes a phone full of paperwork searchable — a petrol receipt by the station's
name, the total or its number, an invoice by the company on it, a shelf label by its product code, a
screenshot by what it says, a business card by the person's name. Nothing new to type and nothing to turn
on: the words go into the same field the search already reads, so *petrom*, *invoice 4417* or *usa lemn*
answer straight away.

Only photos that carry text are read at all. CLIP sees the photo first, and unless one of its top 50 labels
belongs to the text family (label, document, receipt, invoice, card, ticket, menu, poster, screenshot,
number…) the photo is never sent for reading. A holiday album costs nothing and the wedding photos are not
shipped anywhere.

The reading itself happens on Opensolr's OCR servers — Solr machines that do nothing else, never on your
phone — and it does not cost extra: a photo is a tenth of an AI request whether the work was the words, the
vector, the printed text or all three ([plan limits](plan-limits.md)). A photo already read is never read
again.

### When the grid regroups

The grid's grouping follows the **last search that ran**, not the text being typed: typing alone never
regroups the grid. A search runs on Enter, on a picked suggestion, on clearing the box, and on any filter.

After a typed search the results are split into **Best matches** and **Also similar**, at the biggest fall
in score among the results below 60% of the top score. The split is only made with at least 8 results, and
*Best matches* always holds at least 3. The count line shows only *N photos*.

Suggestion pills above the grid appear only after a typed search; they come from a separate facet request
that returns words only.

## Filters

Every filter is a set of chosen values per field, OR-ed within the field (`{!terms f=year tag=year
separator=| v=$f_year}` with `f_year=2025|2026`) and AND-ed across fields. Values travel as bound
parameters, so no value can change the query. Each field's facet excludes that field's own filter
(`facet.field={!ex=year key=year}year`), so a section keeps offering all its values.

On the filter sheet every tap applies at once; long lists show the twelve most frequent values with *Show
all*. Small **Clear all** and **Done (N)** buttons, where N is the number of photos shown with the current
filters, sit both at the top and at the bottom of the sheet. Active filters show as removable pills on one
horizontally scrolling row.

| Filter | Parameters |
|---|---|
| Year | `fq={!term f=year v=$f_year}` and `f_year=2024` |
| Taken between | `fq={!lucene v=$taken_q}` and `taken_q=taken_at:[2025-07-01T00:00:00Z TO 2025-07-08T23:59:59Z]`. Both days included; the clause travels as one bound parameter, and its two ends are formatted from a Long, so they can only ever be timestamps |
| Folder | `fq={!term f=folder v=$f_folder}` and `f_folder=DCIM/Camera/` |
| Camera make | `fq={!term f=camera_make v=$f_make}` and `f_make=Google` |
| Camera model | `fq={!term f=camera_model v=$f_camera}` and `f_camera=Pixel 8` |
| City / Region / Country | `fq={!term f=city v=$f_city}` (likewise `region`, `country`) |
| My tags | `fq={!term f=custom_tags v=$f_tag}` |
| Meaning | `fq={!term f=labels v=$f_label}` |
| Orientation | `fq={!term f=orientation v=$f_orientation}` (landscape, portrait, square) |
| With location | `fq=has_location:true` |
| Within N km | `fq={!geofilt sfield=location pt=$near_pt d=$near_d}` with `near_pt=lat,lon`, `near_d=km` |

The filter sheet is built from facets of the current results (`facet.field` on `year`, `folder`,
`camera_make`, `camera_model`, `city`, `region`, `country`, `labels`, `custom_tags` and `orientation`), so
every choice offered has photos behind it. The radius filter is set from the [map](map.md) (*Search this area*) or from a photo's
*Nearby* (5 km), and adjusted on the sheet (0.5 to 100 km).

## Autocomplete

From the second character, after a 200 ms pause in typing, the app POSTs `suggest.q` to the index's
`/suggest` handler and shows up to eight distinct entries under the search box. The suggester
(`AnalyzingInfixLookupFactory`, dictionary = the stored `suggest` field: your tags, CLIP labels, camera
make and model, city, region, province, country) matches anywhere in a term and is rebuilt at every commit.
Tapping a suggestion searches for it.

## Did you mean

A typed search also sends `spellcheck=true` and `spellcheck.q=<text>`. The `/select` handler runs
`DirectSolrSpellChecker` over the `spell` field (tags, labels, file name, camera, places, unstemmed) and
returns a collation; when it differs from what was typed, *Did you mean …?* is shown above the results, one
tap away.

## Results

Pages of 60, loaded as you scroll. Thumbnails are decoded from the photos on the phone; nothing is
downloaded to draw the grid.

- **Tap** opens the photo in the phone's gallery (`ACTION_VIEW` on its MediaStore URI, with read
  permission granted). If the MediaStore id stored in the index went stale, the app finds the photo again
  by its path. If the photo is gone from the phone, it says so: the next Re-Sync removes it from the index.
- **Press and hold** shows the details: date, camera, lens, settings, size, folder, location as *City,
  Country*, and the words Opensolr read the photo into. At the bottom, one row of labelled icons:
  *Edit* (tags and words), *Gallery* (open it in the gallery app), *Similar* (photos like this one, see
  [duplicates](duplicates.md)), and, only when the photo carries a GPS position, *Map* (the app's
  [map](map.md) centred on it) and *Nearby* (a 5 km radius search).
- **Reload**: swipe down on the grid, tap the reload icon next to the count, or come back from another
  screen; the results are read again from the index. The swipe and the icon are asked for by hand
  (`AppViewModel.forceRefresh`), so they empty the [search cache](#search-cache) first and always reach
  the index.
- **Duplicates**: the duplicates icon on the count line switches the grid to groups of alike photos
  ([duplicates](duplicates.md)).
- **Select**: the *Select* button turns selection on. The bar at the bottom then works on the ticked
  photos: **Share**, **Delete**, and **Re-sync N** to have them read again by CLIP (each counts as new AI
  requests).

## Deleting photos

*Delete* always shows the app's own warning first: *Delete N photos? They are removed from this phone and
from your index. This cannot be undone.* On Android 11 and newer the system's own confirmation follows, and
Android does the deleting; on older versions the app deletes what it is allowed to. The deleted photos leave
the results at once and are deleted from the index with an immediate commit, so a refresh does not bring
them back. If that delete fails, the next sync removes them anyway.

## Editing tags and words

*Edit* in the details sheet (`EditSheet.kt`, `EditRepository.kt`) opens the editor at full height:

- **My tags**: one per entry (commas split), removed with a tap. Stored in `custom_tags`; `custom_tags_text`
  is its tokenised copy, first in `qf` with boost 5.
- **Tag suggestions**: focusing the tag field lists suggestions under it. With nothing typed, the 5 most
  used of your tags and the 5 most used CLIP words; while typing (after a 250 ms pause), up to 8 tags and 5
  words that contain the text anywhere, in any case. It is one `/select` with `rows=0`, faceting on
  `custom_tags` and `labels` with `facet.contains` and `facet.contains.ignoreCase`. Tags already on the
  photo are not offered, and a word already offered as a tag is not repeated. A thin accent line shows while
  suggestions load. Tapping a suggestion adds it; tapping outside the field and its list closes the list,
  and tapping the field again reopens it.
- **What the photo shows**: `meaning` as free text; *Reset* restores CLIP's labels.
- **Save**: the edit goes into the cache's `edits` table (id → tags, wording), the current document is read
  back from the index, the edits go in, the vector is computed again from `meaning + tags` on vector plans
  (`SyncEngine.embeddingText`), and the document is replaced with a commit.

`SyncEngine.applyEdits` puts the edits over CLIP's words every time a photo is written again, so the owner's
words always win. With no local edits (a reinstall) the document copied back from the index keeps the tags
it carried.

## Search cache

Answers from the index are kept on the phone (`data/SearchCache.kt`, its own SQLite file) and reused, so
the same question does not spend the plan's search bandwidth twice. The owner sets the seconds on the
account screen: at least 60, at most a day, 60 by default (`AppPrefs.cacheSeconds`), with a **Clear cache**
button that says how many answers are held. The cache is this phone's own; there is nothing to clear
anywhere else.

The key is the request itself: index name, handler and every parameter with its value, order-independent,
hashed. Two requests share an entry only when the index would answer them identically. At most 400 answers
are kept, the oldest dropped first, and an answer over 2 MB is served but not stored.

| Cached | Never cached |
|---|---|
| `/select` through `SearchRepository.select`: searches and their pages, the facets behind the filters, albums, the documents of duplicate groups, tag suggestions, map photos | Writing and deleting (`/update`), the document read back in `EditRepository.save` before it is written over, `IndexManager.configVersion`/`hasPhotoSchema`, and the sync's walk over the index (`forEachSizePage`, `forEachDoc`, `idsWithout*`, `count`) |
| `/suggest`: autocomplete, keyed on the lowercased prefix | |
| The duplicate-group facet, per slider stop (`cachedDuplicateGroups`) | |

The uncached paths are uncached on purpose: a stale document in `EditRepository.save` would write old
fields back over the owner's own words, and stale ids in the sync's comparison would delete the wrong
photos.

Everything the app writes clears the cache at once, whatever the seconds say: saving tags
(`saveEdits`), deleting photos, `resetIndex`, and every finished sync (`onSyncFinished`). The deliberate
gestures clear it too: `forceRefresh` (swipe down on the grid, the reload icon) and `openAlbums(force =
true)` (swipe down in Albums).

## Why the request is a POST

A query vector is 1024 numbers, too long for a URL. A POST body also keeps what you search for out of
server access logs.
