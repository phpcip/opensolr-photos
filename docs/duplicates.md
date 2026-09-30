# Similar photos

The icon on the count line of the photos screen switches the grid to groups of photos that are alike.
How alike is chosen with a slider. Even the tightest stops are only ever *similar photos*: the app never
calls a group a duplicate, because only the last stop can prove one.

## The slider

6 stops, from 0 to 5, with the name of the kind under it. Stop 0 needs a photo to compare with, so it is
offered only in *Similar to this photo*, which opens there; the library-wide view starts at stop 1
(`SearchRepository.FIRST_LIBRARY_LEVEL`).

| Stop | Name | Photos are grouped when… | Field |
|---|---|---|---|
| 0 | *Same faces* | the photo shows the same people as the one being compared (only in *Similar to this photo*; worked out on the phone) | `faces`, [below](#similar-to-one-photo) |
| 1 | *Same first 2 words, same camera* | the image model's first 2 labels match, in any order, and `camera_model` is the same | `dup_w2_hash` + `camera_model` |
| 2 | *Same first 3 words, same camera* | the first 3 labels match, and the camera is the same; a group of more than 10 photos is dropped (`MAX_WORD_GROUP`): at that size three labels describe a theme, not a repeat | `dup_w3_hash` + `camera_model` |
| 3 | *Same description, same camera* | the whole reading of the photo and the camera that took it: lowercased, split on commas, repeats dropped, sorted; never your own wording | `dup_desc_hash` |
| 4 | *Same photo (EXIF)* | EXIF: time taken, camera make, camera model, lens, ISO, exposure, f-number, focal length, GPS position, altitude | `dup_exif_hash` |
| 5 | *Same file (exact copy)* | the md5 of the original file, worked out on the phone — the server only ever sees the 1024 px copy | `file_hash` |

There are no stops past three words: an image model that names two or three things has nothing to say at
"first 4", and the server writes no key for them. The stops on file name and file size are gone.

The EXIF key leaves out file size, pixel size, orientation and modification time, so a photo that went
through a simple edit keeps it.

The slider's colour follows the stop: the loosest tone at the first stop, through green at the EXIF stop;
the exact-copy stop is neutral. The scale has one set of colours for the light theme and another for the dark
one (`DUPLICATE_*_LIGHT` / `DUPLICATE_*_DARK`).

## How the groups are found

The keys are written by the server when a photo is indexed (`photos_ingest`) or its words change
(`photos_words`), never from your own tags or wording alone. The schema stores them as `*_hash` string
fields with docValues, not stored ([index schema](index-schema.md)).

The server writes only these keys (`Api_lib::_photos_duplicate_hashes`): `dup_w2_hash` and `dup_w3_hash`, the
labels lowercased, repeats dropped, sorted, md5; `dup_desc_hash`, the whole reading with the camera model;
and `dup_exif_hash`. The same-faces stop has no key: it is answered on the phone. The
labels are only the object names the image model finds, as many as it finds, never a sentence, and a key
exists only when the photo really has that many labels: a photo with two labels has no *first 3*.

The library-wide view answers **inside what is on screen**: the typed words (as words, without the vector)
and every active filter narrow the groups. *Similar to this photo* drops the typed words and the filters when
it opens: the comparison is about the photo alone.

Each stop is **one facet request** on its field, sent 300 ms after the slider settles; values seen more than
once are the groups, biggest first. The answer is held for the cache's lifetime, so walking the slider back
over a stop already seen costs nothing.

Two bounds keep the view a view of photos rather than of the library (`SearchRepository.MAX_GROUP`,
`MAX_GROUPS`):

- a value held by **more than 50 photos is dropped**, by its count, before its ids are even read: at that
  size the key is describing a category, not a set of copies;
- at most **2000 values** come back per request, the biggest first, which is far more than the grid pages
  through.

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
`fq={!field f=<field> v=$anchorKey}` for everything carrying it, newest first, up to 200 photos.

It opens at stop 0, *Same faces*, rather than inheriting wherever the slider was left.

**Same faces**
(`SearchRepository.sameFaces`, `FACES_FIELD`), which asks which photos show the same people. A face with a
name stands for the person: a photo has them when it names them (`idsWithPerson`) or when one of its faces
scores as them against the person's references (`FaceMatcher.score` ≥ `SAME`). A face nobody named is looked
for as itself (≥ `SURE`). Every person of the anchor must be in a result. It is answered entirely from the
phone's copy, faces table and documents alike, with no request and no cap on the number of results.

The anchor photo is ringed in the grid and labelled *This one*; **Back to search** above the slider leaves
the view for the plain photo grid (`AppViewModel.backToSearch` → `clearDuplicates`): the words and filters
that were in force were dropped when the view opened. A photo's details are reopened with a long press, as everywhere else. The count line
reads *N like IMG_1234.jpg*, and *Select 1 of each group* is not drawn at all, since there is a single
group. A stop where nothing else carries the key says *Nothing else in your index is like this photo at this
setting.*

Leaving the view — a typed search, a filter, the similar-photos icon, an index reset — drops the
anchor with it, so neither the count line nor the way back can outlive it.

## Staying and leaving

The view stays on the same stop after pull to refresh, the reload button, coming back from the
Sync or account screen, a delete, and a sync.

It is left with the icon again, a typed search, or a filter.
