# Search

<p align="center">
  <img src="images/search-flow.svg" alt="How a search is built" width="100%">
</p>

Search goes straight from the app to the phone's index, in one `POST /select`.

The search box is not on screen until it is asked for: the magnifier in the header opens one compact
line with the filters button on it, and tapping the magnifier again puts it away and clears the query.
Active filters keep the line on screen, with their chips under it.

## Empty search box

Every photo, newest first: `q=*:*`, `sort=taken_at desc, id asc`.

## Typed search

What you type is trimmed to 300 characters and sent **only as the bound parameter `uq`**:

```
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
An index still on an older configuration (rebuild postponed) answers 400 to the newest fields; the app
then retries once without them.

`meaning` holds the labels CLIP gave the photo. `text` also collects the file name, the folder and the
camera, so *pixel* or *screenshots* find what you would expect.

## Filters

Every filter is a set of chosen values per field, OR-ed within the field (`{!terms f=year tag=year
separator=| v=$f_year}` with `f_year=2025|2026`) and AND-ed across fields. Values travel as bound
parameters, so no value can change the query. Each field's facet excludes that field's own filter
(`facet.field={!ex=year key=year}year`), so a section keeps offering all its values. Taps on the filter
sheet apply at once; long lists show the twelve most frequent values with *Show all*.

| Filter | Parameters |
|---|---|
| Year | `fq={!term f=year v=$f_year}` and `f_year=2024` |
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
*Photos nearby* (5 km), and adjusted on the sheet (0.5 to 100 km).

## Autocomplete

From the second character, the app POSTs `suggest.q` to the index's `/suggest` handler and shows up to
eight distinct terms under the search box. The suggester (`AnalyzingInfixLookupFactory`, dictionary = the
stored `suggest` field: labels, camera make and model, city, region, province, country) matches anywhere in
a term and is rebuilt at every commit. Tapping a suggestion searches for it.

## Did you mean

A typed search also sends `spellcheck=true` and `spellcheck.q=<text>`. The `/select` handler runs
`DirectSolrSpellChecker` over the `spell` field (labels, file name, camera, places, unstemmed) and returns a
collation; when it differs from what was typed, *Did you mean …?* is shown above the results, one tap away.

## Results

Pages of 60, loaded as you scroll. Thumbnails are decoded from the photos on the phone; nothing is
downloaded to draw the grid.

- **Tap** opens the photo in the phone's gallery (`ACTION_VIEW` on its MediaStore URI, with read
  permission granted). If the MediaStore id stored in the index went stale, the app finds the photo again
  by its path. If the photo is gone from the phone, it says so: the next Re-Sync removes it from the index.
- **Press and hold** shows the details: date, camera, lens, settings, size, folder, location as *City,
  Country* (with *Show on map*, which opens the app's [map](map.md) on the photo, and *Photos nearby*, a
  5 km radius search), and the words Opensolr read the photo into.
- **Reload**: swipe down on the grid, tap the reload icon next to the count, or come back from another
  screen; the results are read again from the index.
- **Select**: the tick icon turns selection on; tick photos and press *Re-sync N photos* to have them read
  again by CLIP regardless of the cache (each counts as new AI requests).

## Editing tags and words

*Edit tags and words* in the details sheet (`EditSheet.kt`, `EditRepository.kt`):

- **My tags**: one per entry (commas split), removed with a tap. Stored in `custom_tags`; `custom_tags_text`
  is its tokenised copy, first in `qf` with boost 5.
- **What the photo shows**: `meaning` as free text; *Reset* restores CLIP's labels.
- **Save**: the edit goes into the cache's `edits` table (id → tags, wording), the current document is read
  back from the index, the edits go in, the vector is computed again from `meaning + tags` on vector plans
  (`SyncEngine.embeddingText`), and the document is replaced with a commit.

`SyncEngine.applyEdits` puts the edits over CLIP's words every time a photo is written again, so the owner's
words always win. With no local edits (a reinstall) the document copied back from the index keeps the tags
it carried.

## Why the request is a POST

A query vector is 1024 numbers, too long for a URL. A POST body also keeps what you search for out of
server access logs.
