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

The slider's colour follows the stop: black at 0, through green at 5, to red at 10; stops 11 and 12 are a
neutral grey.

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

## Staying and leaving

The duplicates view stays on the same stop after pull to refresh, the reload button, coming back from the
Sync or account screen, a delete, and a sync.

It is left with the duplicates icon again, a typed search, a filter, or opening an [album](albums.md).
