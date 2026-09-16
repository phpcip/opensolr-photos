# Duplicates

The duplicates icon on the count line of the photos screen switches the grid to groups of photos that are
alike. How alike is chosen with a slider.

## The slider

13 stops, from 0 to 12, with the name of the kind under it. The default is stop 4.

| Stop | Name | Photos are grouped when they have the same… | Field |
|---|---|---|---|
| 0–4 | *Same first word* … *Same first 5 words* | first 1 to 5 words CLIP gave them | `dup_w1_hash` … `dup_w5_hash` |
| 5 | *Same photo (EXIF)* | EXIF: time taken, camera make, camera model, lens, ISO, exposure, f-number, focal length, GPS position, altitude | `dup_exif_hash` |
| 6–10 | *Same photo + first word* … *Same photo + first 5 words* | EXIF as at stop 5, and the first 1 to 5 words | `dup_exif_w1_hash` … `dup_exif_w5_hash` |
| 11 | *Same file name* | file name only, without the folder, since several folders can be indexed | `file_name` |
| 12 | *Same file size* | size in bytes | `size_bytes` |

The words key is made of CLIP's first 1 to 5 labels, lower-cased, de-duplicated, sorted, and hashed with
md5. The EXIF key leaves out file size, pixel size, orientation and modification time, so a photo that went
through a simple edit keeps it. Stops 6 to 10 hash the EXIF key together with the words key.

The slider's colour follows the stop: the loosest tone at 0, through green at 5, to red at 10; stops 11 and
12 are neutral. The scale has one set of colours for the light theme and another for the dark one
(`DUPLICATE_*_LIGHT` / `DUPLICATE_*_DARK`), because the loose end used to be drawn in the colour of the dark
background, which took the thumb, the track and the name under it with it.

## How the groups are found

The keys are written by the server when a photo is indexed (`photos_ingest`, from CLIP's labels and the
EXIF), never from your own tags or wording. The schema stores them as `*_hash` string fields with docValues,
not stored ([index schema](index-schema.md)).

Each stop is **one facet request** on its field, sent 300 ms after the slider settles; values seen more than
once are the groups. Then only the photos of those groups are fetched and drawn (a group whose photos are
no longer all found needs at least two to stay). Each group is headed *N of the same · k*.

A stop with nothing to show says *No duplicates of this kind in your index.* An index whose configuration
reset was postponed has no duplicate keys yet; the app then says the index needs its reset first.

## Picking one of each

*Select 1 of each duplicate*, a small link under the stop's name, is enabled when there are groups. It turns
selection on and ticks one photo of every group (the last of each group; the first stays unticked) so you
can look them over. The selection bar then shares, deletes or re-syncs them as usual
([deleting photos](search.md#deleting-photos)).

## Similar to one photo

*Similar*, in the row of actions of a photo's details, opens the same slider anchored to that photo
(`AppViewModel.showSimilar`, `SearchRepository.similarTo`). Each stop is two requests instead of one facet:
the photo's own key for that stop (`fl=<field>`, which a schema of version 1.6 returns from docValues), then
`fq={!field f=<field> v=$anchorKey}` for everything carrying it, newest first, up to 200 photos. `{!field}`
rather than `{!term}`, because `size_bytes` is a `plong` and `{!term}` does not read a points field.

It opens at stop 0, the loosest one, rather than inheriting wherever the duplicates slider was left.

The anchor photo is ringed in the grid and labelled *This one*; **Back to search** above the slider leaves
the view and runs the search that was in force again, with its query and filters (`AppViewModel.backToSearch`
→ `clearDuplicates`). A photo's details are reopened with a long press, as everywhere else. The count line
reads *N like IMG_1234.jpg*, and *Select 1 of each duplicate* is not drawn at all, since there is a single
group. A stop where nothing else carries the key says *Nothing else in your index is like this photo at this
setting.*

Leaving the view — a typed search, a filter, an album, the duplicates icon, an index reset — drops the
anchor with it, so neither the count line nor the way back can outlive it.

## Staying and leaving

The duplicates view stays on the same stop after pull to refresh, the reload button, coming back from the
Sync or account screen, a delete, and a sync.

It is left with the duplicates icon again, a typed search, a filter, or opening an [album](albums.md).
