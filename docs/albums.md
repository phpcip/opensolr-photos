# Albums

**Albums** in the header of the photos screen opens your photos gathered by what they have in common. No
album is created or stored anywhere: every album is a value that already sits in your index.

## Where they come from

The whole screen is built from **one JSON-facet request** on the phone's index, sent each time the screen
opens and again on pull-to-refresh. So an album is always as current as the index. Albums are the whole
library: the current search and filters do not apply to them.

| Section | Field | What is in it |
|---|---|---|
| **People** | `persons_ss` | Every person named on your photos |
| **My tags** | `custom_tags` | Every tag you gave a photo |
| **Years** | `year` | One album per year |
| **Places** | `city`, then `country` | Cities first, then countries |
| **Things** | `labels` | The 12 most used CLIP labels |
| **Cameras** | `camera_model` | Each camera model, shown with its make |

An album appears as soon as one photo carries its value. Each section title is a band like a month on the
photos grid: a tap folds it, and the button at the top right folds or opens them all.

## Selecting, deleting, sharing

A long press on a section title or on an album starts selecting; while selecting, a tap picks or unpicks.
The check all / check none button at the top picks every section at once. The bar at the bottom offers:

- **Delete**: every photo in the picked albums, and in every album of a picked section, removed from the
  phone and from the index. A warning says exactly what goes first ("every photo in every album of: Years,
  My tags", "every photo in the album Summer"), then Android asks once more.
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
and the like, see [search](search.md#filters)). Opening an album also leaves the
[duplicates](duplicates.md) view.
