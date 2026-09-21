# Similar photos

The icon on the count line of the photos screen switches the grid to groups of photos that are alike.
How alike is chosen with a slider. Even the tightest stops are only ever *similar photos*: the app never
calls a group a duplicate, because only the last stop can prove one.

## The slider

7 stops, from 0 to 6, with the name of the kind under it. The default is stop 2.

| Stop | Name | Photos are grouped when they have the same… | Field |
|---|---|---|---|
| 0 | *Same first 3 words* | first 3 words CLIP gave them | `dup_w3_hash` |
| 1 | *Any 4 words the same* | any 4 of CLIP's first five words, whatever their rank | `dup_any4_ss` |
| 2 | *All 5 words the same* | all five words | `dup_w5_hash` |
| 3 | *Same photo (EXIF)* | EXIF: time taken, camera make, camera model, lens, ISO, exposure, f-number, focal length, GPS position, altitude | `dup_exif_hash` |
| 4 | *Same file name* | file name only, without the folder, since several folders can be indexed | `file_name` |
| 5 | *Same file size* | size in bytes. Not the same as the same file: a camera pads its files to whole blocks, so unrelated photos share a size exactly | `size_bytes` |
| 6 | *Same file (exact copy)* | the md5 of the original file, worked out on the phone — the server only ever sees the 640 px copy | `file_hash` |

The words key is made of CLIP's labels, lower-cased, de-duplicated, sorted, and hashed with md5. The EXIF
key leaves out file size, pixel size, orientation and modification time, so a photo that went through a
simple edit keeps it.

Nothing looser than three words is offered (Cip, 2026-09-20). The *any 2* and *any 3 words* stops went
first: two photos that share two of five generic CLIP words — *sky*, *outdoor*, *person* — are not alike in
any useful sense, and on a real library half the photos share them. *Same first 2 words* went with them for
the same reason: still noise, whatever the order of the words.

The slider's colour follows the stop: the loosest tone at 0, through green at the EXIF stop; the three file
stops are neutral, being on a scale of their own. The scale has one set of colours for the light theme and
another for the dark one (`DUPLICATE_*_LIGHT` / `DUPLICATE_*_DARK`), because the loose end used to be drawn
in the colour of the dark background, which took the thumb, the track and the name under it with it.

## How the groups are found

The keys are written by the server when a photo is indexed (`photos_ingest`, from CLIP's labels and the
EXIF), never from your own tags or wording. The schema stores them as `*_hash` string fields with docValues,
not stored ([index schema](index-schema.md)).

Each stop is **one facet request** on its field, sent 300 ms after the slider settles; values seen more than
once are the groups, biggest first. The answer is held for the cache's lifetime, so walking the slider back
over a stop already seen costs nothing.

Two bounds keep the view a view of photos rather than of the library (`SearchRepository.MAX_GROUP`,
`MAX_GROUPS`):

- a value held by **more than 50 photos is dropped**, by its count, before its ids are even read: at that
  size the key is describing a category, not a set of copies. At the loosest stop (*Same first 3 words*) the
  cap is **5**;
- at most **2000 values** come back per request, the biggest first, which is far more than the grid pages
  through.

The loosest stop asks for one more thing: the photos of a group must have been taken by the **same camera**
as well (`camera_model`, as a sub-facet of the key, so it is still one request). Three CLIP words on their
own pair photos by arithmetic rather than by likeness — the vocabulary the model really uses on a phone's
photos is a few hundred words, so any two photos have a fair chance of landing on the same three — and the
camera turns those pairs back into photos taken by one person, of one thing, with one device. Photos with no
camera in their EXIF (screenshots, downloads, scans) are not grouped at that stop at all.

With the multi-valued key (*any 4 words*) a photo carries several keys and so turns up in several groups.
The first group it appears in keeps it and the later ones skip it; a group left with fewer than two photos
of its own is dropped. The groups are **not** joined into one — that was single-linkage chaining, where
A–B and B–C make A, B and C one group and the chain runs through the whole library, which is how one group
came to hold eight thousand of nine thousand photos.

The photos themselves are then fetched **one page per request**: up to 20 groups, and at most 300 photos,
whichever comes first, with the rest following as the grid is scrolled (a group whose photos are no longer
all found needs at least two to stay). Each group is headed *N of the same · k*.

A stop with nothing to show says *No similar photos of this kind in your index.* An index whose
configuration reset was postponed has no keys yet; the app then says the index needs its reset first.

## Picking one of each

*Select 1 of each group*, a small link under the stop's name, is enabled when there are groups. It turns
selection on and ticks one photo of every group (the last of each group; the first stays unticked) so you
can look them over. The selection bar then shares, deletes or re-syncs them as usual
([deleting photos](search.md#deleting-photos)).

## Similar to one photo

*Similar*, in the row of actions of a photo's details, opens the same slider anchored to that photo
(`AppViewModel.showSimilar`, `SearchRepository.similarTo`). Each stop is two requests instead of one facet:
the photo's own key for that stop (`fl=<field>`, which a schema of version 1.6 returns from docValues), then
`fq={!field f=<field> v=$anchorKey}` for everything carrying it, newest first, up to 200 photos. `{!field}`
rather than `{!term}`, because `size_bytes` is a `plong` and `{!term}` does not read a points field.

It opens at stop 0, the loosest one, rather than inheriting wherever the slider was left.

The anchor photo is ringed in the grid and labelled *This one*; **Back to search** above the slider leaves
the view and runs the search that was in force again, with its query and filters (`AppViewModel.backToSearch`
→ `clearDuplicates`). A photo's details are reopened with a long press, as everywhere else. The count line
reads *N like IMG_1234.jpg*, and *Select 1 of each group* is not drawn at all, since there is a single
group. A stop where nothing else carries the key says *Nothing else in your index is like this photo at this
setting.*

Leaving the view — a typed search, a filter, the similar-photos icon, an index reset — drops the
anchor with it, so neither the count line nor the way back can outlive it.

## Staying and leaving

The view stays on the same stop after pull to refresh, the reload button, coming back from the
Sync or account screen, a delete, and a sync.

It is left with the icon again, a typed search, or a filter.
