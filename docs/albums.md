# Albums

**Albums** in the header of the photos screen opens your photos gathered by what they have in common. No
album is created or stored anywhere: every album is a value that already sits in your index.

## Where they come from

The whole screen is built from **one JSON-facet request** on the phone's index, sent each time the screen
opens and again on pull-to-refresh. So an album is always as current as the index. Albums are the whole
library: the current search and filters do not apply to them.

| Section | Field | What is in it |
|---|---|---|
| **My tags** | `custom_tags` | Every tag you gave a photo |
| **Things** | `labels` | The 12 most used CLIP labels |
| **Places** | `city`, then `country` | Cities first, then countries |
| **Cameras** | `camera_model` | Each camera model, shown with its make |
| **Years** | `year` | One album per year |

An album appears as soon as one photo carries its value.

## How an album looks

- **Cover**: the album's 3 newest photos (by `taken_at`), stacked and slightly turned, the newest on top.
  The thumbnails are decoded from the photos on the phone.
- **Name**: shown with the first letter of every word capitalised, so the tag *my wife* shows as *My
  Wife*. Only the display changes; the value stored in the index stays as it was written.

## Opening an album

Tapping an album opens the photos grid filtered to that value only (`fq={!term f=custom_tags v=$f_tag}`
and the like, see [search](search.md#filters)). Opening an album also leaves the
[duplicates](duplicates.md) view.
