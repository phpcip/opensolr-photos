# Search

<p align="center">
  <img src="images/search-flow.svg" alt="How a search is built" width="100%">
</p>

Search goes straight from the app to the phone's index, in one `POST /select`.

## Empty search box

Every photo, newest first: `q=*:*`, `sort=taken_at desc, id asc`.

## Typed search

What you type is trimmed to 300 characters and sent **only as the bound parameter `uq`**:

```
uq   = dog on the beach
lex  = {!edismax qf='meaning^3 text file_name_text folder_text camera_text' mm=1 v=$uq}
```

On a plan with vector search, the app first asks `embed` (with `is_query=1`) for the query's vector and adds:

```
vec  = {!knn f=embeddings topK=60}[0.0132, -0.0481, …]
q    = {!bool should=$lex should=$vec}
```

Without vector search, or when the month's AI requests are used up, or when the vector service does not
answer, the same request runs with `q={!bool should=$lex}` and the app says so above the results.

`meaning` holds the labels CLIP gave the photo. `text` also collects the file name, the folder and the
camera, so *pixel* or *screenshots* find what you would expect.

## Filters

Every filter value travels as a bound parameter through the `{!term}` parser, so no value can change the
query:

| Filter | Parameters |
|---|---|
| Year | `fq={!term f=year v=$f_year}` and `f_year=2024` |
| Folder | `fq={!term f=folder v=$f_folder}` and `f_folder=DCIM/Camera/` |
| Camera | `fq={!term f=camera_model v=$f_camera}` and `f_camera=Pixel 8` |
| Orientation | `fq={!term f=orientation v=$f_orientation}` (landscape, portrait, square) |
| With location | `fq=has_location:true` |

The filter sheet is built from facets of the current results (`facet.field` on `year`, `folder`,
`camera_model` and `orientation`), so every choice offered has photos behind it.

## Results

Pages of 60, loaded as you scroll. Thumbnails are decoded from the photos on the phone; nothing is
downloaded to draw the grid.

- **Tap** opens the photo in the phone's gallery (`ACTION_VIEW` on its MediaStore URI, with read
  permission granted). If the MediaStore id stored in the index went stale, the app finds the photo again
  by its path. If the photo is gone from the phone, it says so: the next Re-Sync removes it from the index.
- **Press and hold** shows the details: date, camera, lens, settings, size, folder, location (with *Show on
  map*), and the words Opensolr read the photo into.

## Why the request is a POST

A query vector is 1024 numbers, too long for a URL. A POST body also keeps what you search for out of
server access logs.
