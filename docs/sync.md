# Sync and Re-Sync

<p align="center">
  <img src="images/sync-flow.svg" alt="The five steps of every sync" width="100%">
</p>

There is one algorithm. A **first Sync** and a **Re-Sync** only differ in what the index already holds.

## The steps

1. **Make sure the index is there** ([below](#the-index)).
2. **Scan the chosen folders** through MediaStore. MediaStore already leaves out files still being
   written and files in the trash.
3. **Download every id the index holds**: `q=*:*`, `fl=id`, `sort=id asc`, `rows=1000`, `start` advancing
   by 1000 until a page comes back shorter than 1000. The sort keeps consecutive pages lined up.
4. **Delete** every id that is in the index but not on the phone, 500 per request.
5. **Add** every photo that is on the phone but not in the index, 20 per round:
   - take the document from the phone's cache when the file is unchanged, otherwise read the EXIF, make
     the 640 px copy and send it to `image_clip`;
   - one `batch_embed` call for the round's labels (plans with vector search);
   - one `POST /update` with the round's documents (`commitWithin=10000`, so they appear in search within
     ten seconds while the rest continue).

Then a hard commit, the cache forgets photos that are no longer on the phone, and the plan usage is
refreshed.

<p align="center">
  <img src="images/resync-diff.svg" alt="Re-Sync compares the ids on the phone with the ids in the index" width="100%">
</p>

## Photo ids

A photo's id is the lower-case hex **md5 of its absolute path**, for example
`md5("/storage/emulated/0/DCIM/Camera/PXL_20260801_101500.jpg")`. It is computed in exactly one place,
`MediaScanner.photoId()`, and used for indexing, for Re-Sync and for the scheduled runs, so the ids on the
phone and in the index are always built the same way.

Consequences worth knowing:

- a photo **moved or renamed** is a new id: the old document is deleted and the photo is added again;
- a photo **edited in place** keeps its id and is not re-read by Re-Sync, because the id is already in the
  index. The phone's cache notices the change (size or modification time) the next time the photo has to
  be written again.

## The index

<p align="center">
  <img src="images/index-lifecycle.svg" alt="Reuse, create or recreate the index" width="100%">
</p>

- **Found** in the account: its address and password are read, its schema is checked (and uploaded if the
  index does not carry this app's fields), then Re-Sync.
- **Not found, and this phone never had one:** first setup. The app picks an environment that runs vector
  search, preferring the newest Solr, in the United States for phones set to an American time zone and in
  Europe otherwise, creates the index and uploads the configuration.
- **Not found, but this phone had one** (deleted from the control panel, removed as unused, anything):
  created again the same way, and a notification says the index was emptied and is being filled again.

An index is only ever created after the account's index list was **read successfully** and did not contain
it. A network error, a server error or a refused key never lead to a create; they end the run, and the next
sync simply tries again.

## The cache

Reading a photo and embedding its words both count against the plan. The phone keeps, per photo, the
document that was written and its vector (SQLite, `photo_cache.db`), keyed by id and valid while the file's
size and modification time are unchanged.

So when an index is emptied, deleted or recreated, **the next sync refills it from the cache without a
single AI request**. Only photos the phone has never read cost anything.

## When syncs run

| Trigger | Details |
|---|---|
| First setup | Right after the index is created or found |
| Changing folders | Saving a new folder choice starts a sync |
| **Force Re-Sync** | Button on the Sync screen |
| **Scheduled** | Every week or every month, your choice on the Sync screen; only with a network connection and a battery that is not low |

**Only one sync runs at a time.** Both kinds are unique WorkManager jobs, and the worker also refuses to
start while another sync runs in the same process. Pressing Force Re-Sync while a sync is queued or running
shows *A sync is already running* and starts nothing.

While a sync runs it is a foreground data-sync job with a progress notification. The top bar of the photo
grid shows a turning sync icon; tapping it opens the Sync screen with the phase and the count.

## When a run stops early

| Situation | What the app does |
|---|---|
| A photo cannot be decoded, or the server refuses it | Skips that photo, counts it as skipped, continues |
| Rate limit (per minute or per hour) | Waits as long as the server says, retries up to six times |
| Monthly AI requests used up | Commits what was written, stops, notification with a link to [pricing](https://opensolr.com/pricing) |
| Index over its disk space or bandwidth (HTTP 403) | Stops, notification with a link to pricing |
| Plan lost vector search mid-run | Continues without vectors |
| Index password changed | Reads the new one from the account, retries once |
| API key refused | Stops, asks you to sign in again |
| Anything else | Commits what was written, stops; the next sync continues from where this one got to |

A run never has to start over: the next Re-Sync compares ids again and only adds what is still missing.

## The Sync screen

- **Status**: running with phase and progress, waiting for network, or the outcome of the last run.
- **Last sync**: finish time (mm/dd/yyyy hh:mm:ss), result, photos in your folders, added, removed,
  skipped, and a note if the index had to be recreated.
- **Force Re-Sync.**
- **Automatic Re-Sync**: every week or every month.
- **Folders being indexed**, and Change folders.
