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
5. **Add** every photo that is on the phone but not in the index, plus every photo that must be written
   again — edited in place (same id, different size or modification time than the cache holds), chosen for
   *Re-sync selected*, or carrying a position without place words yet — 20 per round:
   - make the 640 px copy carrying the original's EXIF, and take your tags and words for the photo
     from the phone's edits when there are any;
   - one `photos_ingest` call per 5 copies: the server reads the EXIF, asks CLIP and the embedder, finds
     the place, keeps the tags and words already in the index, builds the complete document and writes
     it into your index. A photo the allowance cannot cover is indexed without words and read again at
     a later sync;
   - one `batch_embed` call for photos whose tags or words you edited (their vector is made from your
     words);
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
- a photo **edited in place** keeps its id; the sync notices that its size or modification time differs
  from what the cache holds, reads it again and rewrites its document.

## The index

<p align="center">
  <img src="images/index-lifecycle.svg" alt="Reuse, create or recreate the index" width="100%">
</p>

- **Found** in the account: its address and password are read and the configuration version the index
  reports (`GET /opensolr-photos-config`, `config_version` in `solrconfig.xml`) is compared with
  `IndexManager.CONFIG_VERSION`. Equal: Re-Sync. Older: the sync stops with `rebuild_required` and the app
  asks the owner (*Your index must be rebuilt*, Rebuild now / Later); once approved
  (`AppPrefs.rebuildApproved`) the next sync copies every document of the index into the cache, uploads the
  configuration, empties the index (`delete *:*`) and writes every photo again from the cache: no CLIP, a
  vector only where the cache had none. Newer: `update_app`, the index is left alone. Raise both version
  numbers whenever anything in `solr/conf` changes.
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

Reading a photo and embedding its words both count against the plan. The phone keeps, per photo, the
document that was written and its vector (SQLite, `photo_cache.db`), keyed by id and valid while the file's
size and modification time are unchanged. The same database holds the owner's `edits` (tags and wording)
and the `places` already looked up.

**The cache is rebuilt from the index.** At every sync, each photo the index knows and the cache does not
(a reinstall, a lost cache) is copied back from the index (`SolrClient.allDocs`, all stored fields, no
vector) when its `modified_at` and `size_bytes` still match the file; otherwise the photo is read again. A
reinstall therefore never costs a CLIP request, and the owner's tags come back with the documents.

So when an index is emptied, deleted or recreated, **the next sync refills it from the cache without a
single AI request**. Only photos the phone has never read cost anything.

## When syncs run

| Trigger | Details |
|---|---|
| First setup | Right after the index is created or found |
| Changing folders | Saving a new folder choice starts a sync |
| **Force Re-Sync** | Button on the Sync screen |
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
