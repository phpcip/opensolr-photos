# Albums

> Since 3.5 the header carries [Stats](stats.md) instead of Albums. Every album was one filter value, so
> the same photos are one filter, or one line of Stats, away.

**Albums** opens your photos gathered by what they have in common. No
album object is created or stored anywhere: every album is a value that already sits in your index (and in
the phone's copy of it).

## Where they come from

An album is a count over your whole library, and the index is what counts it: this is one of the few
screens that still asks the index, with **one JSON-facet request**, while syncing and plain browsing ask it
for nothing. The answer can be the one already held from before; pull-to-refresh
drops it and asks again. Albums are the whole library: the current search and filters do not apply to them.

| Section | Field | What is in it |
|---|---|---|
| **People** | `persons_ss` | Every person named on your photos |
| **My tags (Albums)** | `custom_tags` | Every tag you gave a photo |
| **Things** | `labels` | The 12 most used CLIP labels. On a plan without vector search no photo is sent to be read, so `labels` is never written and the Things section never appears |
| **Years** | `year` | One album per year |
| **Places** | `city`, then `country` | Cities first, then countries |
| **Cameras** | `camera_model` | Each camera model. The make is put in front only when the model does not already start with it, so *Nikon Z6* stays as it is. This is the only place make and model appear together, since the Camera make filter is gone |

Tags and people are saved on the phone first and are finished there. The sync that starts straight after
carries them up, 50 photos a call and no pictures attached. So a tag you have just given becomes an album
only once that sync has landed and you refresh this screen.

An album appears as soon as one photo carries its value. Each section title is a band like a month on the
photos grid: a tap folds it, and the button at the top right folds or opens them all. The title also
carries how many albums are under it, and folded sections are remembered between visits. With nothing to
show, the screen reads "No albums yet: albums come from your tags, the words photos were read into, places,
cameras and years."

## Selecting, deleting, sharing

A long press on a section title or on an album starts selecting; while selecting, a tap picks or unpicks,
and the check all / check none button in the top bar picks or drops every section at once. Picking ends
by itself when the last tick goes, and the bar at the bottom has a Cancel. The photos grid has no such
button — a tick there takes a whole year, month or day — but here check all is what picks whole sections,
so it stays. The bar at the bottom offers:

- **Delete**: every photo in the picked albums, and in every album of a picked section, removed from the
  phone, from the index and from the phone's copy of the index. A warning says exactly what goes first
  ("every photo in every album of: Years, My tags", "every photo in the album Summer") and that a photo
  sitting in several albums goes from all of them, then Android asks once more.
- **Share**: every photo of one album. Greyed out when a section, or more than one album, is picked.

The photos are looked up in one walk over the index: the picked values of each field go in a `terms` query
as a bound parameter, the fields are joined with `{!bool should=...}`.

## How an album looks

- **Cover**: the album's 3 newest photos (by `taken_at`), stacked and slightly turned, the newest on top.
  The thumbnails are decoded from the photos on the phone.
- **Name**: shown with the first letter of every word capitalised, so the tag *my wife* shows as *My
  Wife*. Only the display changes; the value stored in the index stays as it was written.

## Opening an album

Tapping an album opens the photos grid filtered to that value only (`fq={!term f=custom_tags v=$f_tag}`
and the like, see [search](search.md#filters)). Opening an album clears whatever you had typed, every other
filter and "similar to this photo", and leaves the [duplicates](duplicates.md) view.
