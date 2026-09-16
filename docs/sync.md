# Sync and Re-Sync

<p align="center">
  <img src="images/sync-flow.svg" alt="The five steps of every sync" width="100%">
</p>

There is one algorithm. A **first Sync** and a **Re-Sync** only differ in what the index already holds.

## The steps

1. **Make sure the index is there** ([below](#the-index)).
2. **Scan the chosen folders** through MediaStore. MediaStore already leaves out files still being
   written and files in the trash.
3. **Walk the index's side of the diff**: every id it holds with the file size it was indexed with
   (`q=*:*`, `fl=id,size_bytes`, `sort=id asc`, `rows=1000`, `cursorMark` paging). Each page is compared
   with the phone's files as it arrives, and its deletions and re-reads are handled while the next page
   downloads; what the phone has and the index does not is known once the last page has been seen.
   The whole index is never held in memory, and nothing else is ever pulled from it.
4. **Delete** every id that is in the index but not on the phone, 500 per request.
5. **Hand to Opensolr** every photo that is on the phone but not in the index, every photo whose file size
   differs from the index's (a changed photo is read again from scratch, whatever the index still holds
   about it), and every photo chosen for *Re-sync selected*, indexed without words while the plan had no
   AI, or carrying a position without place words yet. Five per call:
   - make the 640 px copy carrying the original's EXIF, and add your tags and words for the photo
     from the phone's edits when there are any;
   - one `photos_ingest` call: the server reads the EXIF, asks CLIP and the embedder, finds the place,
     keeps the tags and words already in the index for photos the phone did not speak for, builds the
     complete document and writes it into your index. A photo the allowance cannot cover is indexed
     without words and read again at a later sync.

The server also writes each photo's duplicate keys ([duplicates](duplicates.md)). Its documents are posted
with `commitWithin=10000`, so photos become searchable within about 10 seconds while the sync goes on.

Then a hard commit, and the plan usage is refreshed.

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
- a photo **edited in place** keeps its id; the sync notices that its size or modification time differs
  from what the cache holds, reads it again and rewrites its document.

## The index

<p align="center">
  <img src="images/index-lifecycle.svg" alt="Reuse, create or recreate the index" width="100%">
</p>

- **Found** in the account: its address and password are read and the configuration version the index
  reports (`GET /opensolr-photos-config`, `config_version` in `solrconfig.xml`) is compared with
  `IndexManager.CONFIG_VERSION`. Equal: Re-Sync. Older: the sync stops with `rebuild_required` and the app
  asks the owner first (*Your index will be reset*, Reset and re-sync / Later). Once approved
  (`AppPrefs.rebuildApproved`), the next sync, in this order: keeps the owner's tags and wording found only
  in the index as local edits, empties the index (`delete *:*`), only **then** uploads the new
  configuration, and re-syncs every photo through `photos_ingest`. Search keeps working while the photos
  are added back. With more than 500 photos and no charger, it waits for the charger before anything is
  emptied. Newer: `update_app`, the index is left alone. Raise both version numbers whenever anything in
  `solr/conf` changes.
- **Not found, this phone never had one, and the account holds photo indexes of other phones:**
  `Outcome.NEEDS_CHOICE`. The app asks *which one of these is your device?* with the phones' names
  (`device_name` from `get_index_list`, sent at `create_index` as maker + model + the phone's own name);
  the pick is stored in `AppPrefs.chosenIndexName` and used from then on, or *none of these* stores the
  phone's own name and creates it. The name is never derived again once chosen.
- **Not found, and this phone never had one:** first setup. The app picks the nearest environment: with the
  optional coarse-location permission, a phone in the Americas (longitude between -170 and -30) gets
  `CHICAGO-96`, everyone else `FINLAND9`; without a position the time zone decides the same way. If the
  chosen environment is not offered, the newest vector environment on the same continent is used. Then it
  creates the index and uploads the configuration.
- **Not found, but this phone had one** (deleted from the control panel, removed as unused, anything):
  created again the same way, and a notification says the index was emptied and is being filled again.

An index is only ever created after the account's index list was **read successfully** and did not contain
it. A network error, a server error or a refused key never lead to a create; they end the run, and the next
sync simply tries again.

## The cache

The phone keeps only what the index cannot: your `edits` (tags and wording per photo, written the moment
you save them) in SQLite, `photo_cache.db`. Nothing about the photos themselves is cached and nothing is
pulled from the index. A reinstall therefore changes nothing: an unchanged photo is in the index and is not
touched, a changed one is read again. Reading a photo again is free when Opensolr's caches have seen it.

## When syncs run

| Trigger | Details |
|---|---|
| First setup | Right after the index is created or found |
| Changing folders | Saving a new folder choice starts a sync |
| **Force Re-Sync** | Button on the Sync screen |
| **Stop** | Ends the run that is going. `SyncWorker.stopRequested` is checked between batches, because WorkManager cancellation cannot interrupt a batch already uploading; then only the requested runs are cancelled (`NOW`, `LATER`, `CHARGING`, `MEDIA`) — never the periodic work, which would come back with its period elapsed and start a sync on the spot |
| **Rebuild OCR documents** | Button under *Reset index*. One delete-by-query on `ocr_t:*` drops the documents, then a sync puts them back: the diff finds them missing and writes them afresh, with the reading cleaned on the way out of the server's cache. Free — a photo read once is never read again |
| **Photos changed** | `SyncScheduler.watchMedia`: a WorkManager content-URI trigger on MediaStore images, 60 s after the first change and at most 5 min later; one-shot by design, re-armed after every run and at every app start |
| **Re-sync selected** | Chosen photo ids go to `AppPrefs.resyncIds` and a sync starts; those photos bypass the cache |
| **Kind to the battery** | The watch only starts a sync when a changed picture is in one of your folders; a screenshot or a chat picture elsewhere is ignored without a single request. Two watch-started syncs stay at least 15 minutes apart (the change waits, it is not lost). More than 500 photos to read at once waits for the charger. The app paces its calls under your account's API rate limits, and if Opensolr still asks it to slow down, the run stops and is started again a little later instead of waiting with the phone awake. |
| **Scheduled** | Every day, week or month, your choice on the Sync screen; the safety net for what the watcher cannot see (words after the allowance resets, places to retry, an index changed on the server); only with a network connection and a battery that is not low |

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
| Monthly AI requests used up | Carries on: from the first refused request, photos are written without CLIP words and without vectors (`quotaHit`), kept out of the cache and without `clip_model`; `SolrClient.idsWithoutWords()` puts them back into the next sync once the allowance is back. The report says how many, with a notification |
| Index over its disk space or bandwidth (HTTP 403) | Stops, notification with a link to pricing |
| Plan lost vector search mid-run | Continues without vectors |
| Index password changed | Reads the new one from the account, retries once |
| API key refused | Stops, asks you to sign in again |
| Anything else | Commits what was written, stops; the next sync continues from where this one got to |

A run never has to start over: the next Re-Sync compares ids again and only adds what is still missing.

## The Sync screen

- **Status**: running with phase and progress, waiting for network, or the outcome of the last run.
- **Last sync**: what the run did (*3 synced · 1 removed*) for a few seconds after it finishes, then the
  finish time (mm/dd/yyyy hh:mm:ss), the result, the photos in your folders, the photos in your index as
  they are now (`indexAfter`), and a note if the index had to be recreated.
- **Force Re-Sync.**
- **Automatic Re-Sync**: every week or every month.
- **Folders being indexed**, and Change folders.
