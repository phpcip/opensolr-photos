# How it works

<p align="center">
  <img src="images/how-it-works.svg" alt="How a photo becomes searchable" width="100%">
</p>

Opensolr Photos has four moving parts: the app on the phone, the phone's own copy of the index, the
Opensolr platform, and the phone's Opensolr Index. The app talks to each remote part over HTTPS and to
nothing else.

## The parts

### The app

- Finds photos through Android's MediaStore, in the folders you chose (DCIM by default).
- Reads each photo's EXIF metadata itself: date, camera, lens, exposure, place.
- Makes a small upright JPEG copy (1024 px on the long edge) for the reader. The copy is re-encoded from
  pixels, so it carries no metadata. Without vector search on the plan nothing is read from it.
- Reads the photo's own facts from the file: its EXIF, and the names of the people in it when another app
  has already written them there (`PersonInImage` in XMP). It never writes to a photo file.
- Finds and fingerprints the faces in each photo on the phone (`FaceEngine`: YuNet and SFace on LiteRT) and
  matches them to the people the owner named (`FaceMatcher`). No face goes to Opensolr's AI servers.
- Reads from the phone's own copy of the index first. Browsing, the counts, the suggestions and the
  document read back before an edit never reach the index. Writes still go to the index, and a typed search
  still goes straight to it.
- Saves your tags, names and wording on the phone and is done with them there; the sync that starts
  straight after carries them up to your index. Nothing is written into the photo files.
- Runs sync in the background with WorkManager, one sync at a time, and watches MediaStore so a sync runs
  on its own when photos change. Pulling the grid down also reloads and starts a sync. Every button that
  starts work (Sync, Reset, Re-read, Documents, Re-sync) stops whatever runs first, then starts; nothing is
  refused as busy. **Stop** halts every sync and face job and holds them until the owner presses a button.
- Draws the map with osmdroid on OpenStreetMap tiles. Besides Opensolr, those tiles and `api.github.com`
  (the update check, only in a copy installed from GitHub) are the only hosts it ever contacts.
- Keeps the answers the index did give and reuses them for as long as you set on the account screen, so a
  repeated question costs no bandwidth. Reads only. See [the search cache](search.md#search-cache).

### The phone's copy of the index

The phone holds a copy of every document its index holds, in SQLite (`photo_cache.db`, the `docs` table).
Everything is in it except the search vector and the [duplicate keys](duplicates.md): the id, the path, the
file name, the size, the dates, the camera, the EXIF, the place, the words the photo was read into, the
printed text (OCR), the people, the faces, your own tags and wording, and the md5 of the file. The exact
field list is `EditRepository.CLONE_FIELDS`. The faces themselves (fingerprints, frames, names) sit beside
it in the `faces` table.

It is read from the index **once** — at install or at reinstall, ten thousand documents at a time — and from
then on every write keeps it in step: each `photos_ingest` and each `photos_words` answer goes straight into
it. It is the source of truth: an index that has to be emptied (Reset, a new configuration) is filled back
from it, and no photo is sent again; a document the index lost is rewritten from it.

What that takes off the wire:

- **Syncing no longer walks the index.** The comparison is between your folders and the copy, both already
  on the phone, so a sync with nothing to do reads no documents from the index at all — all a quiet run
  still asks is the short checks it opens with: that the index is still in your account, that it is still on
  the configuration this version expects, and what your plan allows today. Before 2.5 a library of 10,000
  photos cost about 21 requests and about 1.8 MB on every single sync, only to find nothing had changed.
  See [sync](sync.md).
- **Plain browsing makes no request.** The years, the months, the days, their counts and the photos inside
  them all come from the copy.
- **Tag and name suggestions**, and the *already on these photos* list under the tagging sheet, are
  answered from the copy.
- **The filter lists** are asked for once and then kept until a sync actually writes something.

### opensolr.com

Account and index management:

- the sign-in pages (`/app/authorize`) and the code exchange (`/app/token`), see [sign-in](sign-in.md);
- creating the index, uploading its configuration, reading its address and password, plan limits;
- `nearby_places`: up to 50 GPS positions in, the nearest named place of each out (city, region, province,
  community, country), answered from a permanent store so a position is looked up once for everyone.

### api.opensolr.com

The AI endpoints:

- `photos_ingest` takes up to ten photos at once (the app sends five) and indexes them completely on the server: EXIF from
  the copy, what the image model reads in the photo (one sentence describing it, plus the objects it
  names), the search vector of that text, the place of the GPS position, your tags and words kept
  from the index, the [duplicate keys](duplicates.md) (from the model's reading and the EXIF, never from
  your tags or wording), and the write into your index with `commitWithin=10000`. A tenth of an AI request per
  photo that needed the models. The copies are not stored.
  What the index already held is kept rather than blanked when a later pass cannot produce it — the printed
  text and the words the photo was read into survive a pass made without vector search on the plan, or with
  the month's AI allowance spent, as long as it is the same file, which the md5 sent with it proves. The
  date is kept the same way, so a photo with no date of its own does not jump to today on a later write.
- The **text printed in the photo** is read in the same pass, on every photo: tesseract reads it on
  Opensolr's side, at the same time as the image model works, and the photo counts as carrying text only
  when the reading holds at least four real words. The same reading is offered to anyone through
  `POST /solr_manager/api/image_ocr`. It lands in the `ocr_t` field
  and is searchable with no change on the phone. It costs nothing extra: the photo is still a tenth of a
  request, whether the words, the vector or the printed text was the work.
- `photos_words` takes up to 50 photos already in the index, **with no picture attached**: your tags, the
  people in them, your own wording. The server reads each document, puts the words in, makes the vector
  again and writes it back, and answers with the written document, which goes into the phone's copy. This
  is the path behind local-first editing: a save is finished on the phone, and the sync that starts straight
  after carries it up. A photo the index does not hold yet is answered with nothing, and the app sends it
  the ordinary way, picture and all.
- `photos_select` turns a typed query into a search vector and runs the search with it on your index; only
  the photos found come back, never the vector. The words of an edited photo are turned into a vector on the
  server, inside `photos_words`.

On a plan without vector search, or with the month's AI allowance spent, nothing is read: no words and no
vector are made from the picture, and `photos_select` is never called, so search is lexical. Those photos are
indexed by date, camera, place, file name and your own words.

### The phone's Opensolr Index

A regular Opensolr Index named `photos_<ANDROID_ID>__dense`, created in your account on the server that runs
vector search nearest to you (the platform picks it), with the configuration from [`solr/conf`](../solr/conf).
It is plain Apache Solr: you can query it and export it with any Solr client, back it up from the Opensolr
control panel, and run it anywhere. The app reads and writes it
directly with its HTTP Basic credentials: `/select` and `/update`. It is still the only place a document
lives in full, vector included; the phone's copy of it answers everything that does not need the vector.

<p align="center">
  <img src="images/data-boundaries.svg" alt="Where each piece of data lives" width="100%">
</p>

## One index per phone

`ANDROID_ID` is the identifier Android gives this app on this phone. It stays the same when the app is
reinstalled and differs on every other phone. So:

- **reinstall on the same phone:** sign in, the app finds `photos_<ANDROID_ID>__dense`, reads it once into
  the phone's copy of it, and runs a Re-Sync, which only adds and deletes the differences. That one full
  read is the only time the index is walked;
- **a new phone on the same account:** when the account already holds photo indexes of other phones, the app
  offers to re-use one of them (*Re-use one of these indexes from your Opensolr account?*); otherwise, or with
  *No, start a new index*, a new index of its own. A photo's id is the md5 of its path **inside the storage volume**,
  so the same folders on another phone keep every identity, and a document follows its photo to the phone
  syncing now.

<p align="center">
  <img src="images/index-lifecycle.svg" alt="Reuse, create or recreate the index" width="100%">
</p>

## What the app calls

| Host | Call | When |
|---|---|---|
| opensolr.com | `GET /app/authorize` (in the browser) | Sign-in |
| opensolr.com | `POST /app/token` | Sign-in: code + verifier for email, API key, plan limits |
| opensolr.com | `POST /solr_manager/api/get_index_list` | Start of a sync: is the phone's index in the account? |
| opensolr.com | `POST /solr_manager/api/vector_regions` | Creating the index: where vector search runs |
| opensolr.com | `POST /solr_manager/api/create_index` | Creating the index |
| opensolr.com | `POST /solr_manager/api/upload_zip_config_files` | New index, or an index without this schema |
| opensolr.com | `POST /solr_manager/api/get_core_info` | Address and credentials of the index |
| opensolr.com | `POST /solr_manager/api/get_account_summary` | Plan limits and usage |
| opensolr.com | `POST /solr_manager/api/nearby_places` | Called by the server, per batch of photos with a position |
| api.opensolr.com | `POST /solr_manager/api/photos_ingest` | Once per 5 new or changed photos. Nothing is read from the picture without vector search on the plan |
| opensolr.com | `POST /solr_manager/api/image_ocr` | Server to server, for photos that carry text — the phone never calls it |
| api.opensolr.com | `POST /solr_manager/api/photos_words` | A sync that carries up words you edited: 50 photos per call, no pictures |
| api.opensolr.com | `POST /solr_manager/api/embed` | Once per typed search (vector search plans only); the same words reuse their vector for 30 minutes, across pages and groups |
| your index | `POST /select` | Typed search (with spellcheck), the filter lists, map pins, duplicates (one facet per slider stop), and the one full read at install or reinstall |
| your index | `GET /opensolr-photos-config` | Start of a sync: the index's configuration version |
| your index | `POST /suggest` | Autocomplete |
| tile.openstreetmap.org | `GET` tiles | Only while the map is open |
| api.github.com | `GET /repos/phpcip/opensolr-photos/releases/latest` | Only in a copy installed from GitHub: is there a newer release, once a day, and on **Check for updates**. Unauthenticated, carries nothing about you. A copy from Google Play or AppGallery is updated by that store |
| your index | `POST /update` | Deleting, committing, saving an edit, emptying the index before a configuration reset |

Credentials always travel in the request body, never in a URL.

Where the boundary now runs:

- **Never leaves the phone:** browsing and its counts, the tag and name suggestions, the *already on these
  photos* list, the document read back before an edit is written, and the sync's comparison of your folders
  with the index. All of it is answered from the phone's copy of the index.
- **Goes out once and is held:** the one full read of the index at install or reinstall, and the filter
  lists, which are fetched once and kept until a sync actually writes something.
- **Goes out every time:** a typed search by meaning (`photos_select`), `photos_ingest`, `photos_words`,
  the duplicate and map facets, `/suggest`, every `/update`, and the short checks a sync opens with
  (the index is in your account, its configuration is the one this version expects, the plan as it stands
  now).

Of the calls that do go out, the reads can still be answered from the phone's
[search cache](search.md#search-cache) instead of reaching the index. `/update`, the configuration check
and the install-time full read are never cached.

## Code map

| Package | Responsibility |
|---|---|
| `auth` | `AuthFlow` builds the PKCE request and opens the Custom Tab; `AuthCallbackActivity` receives the App Link |
| `data` | `AppPrefs` (settings, session, cache seconds, the held filter lists), `SecureStore` (Keystore AES-GCM), `PhotoCache` (SQLite: the phone's copy of the index in `docs`, your unsent edits, the queue of what still has to go up, the retry pauses, the skipped photos), `SearchCache` (the answers the index gave), `Words`, models |
| `index` | `IndexManager`: index name, find, create, upload config, refresh credentials |
| `media` | `MediaScanner` (folders, photos, the id function), `PhotoReader` (EXIF, the 1024 px copy, reading what other apps wrote in the file; it never writes), `FaceEngine` (faces found and fingerprinted on the phone) |
| `net` | `OpensolrApi` (REST API), `SolrClient` (direct Solr), typed errors |
| `search` | `SearchRepository`: query, filters, facets, suggest, spellcheck, map pins, duplicates, parsing; `FaceMatcher`: people learned from the faces the owner named; `EditRepository`: saving tags and words on the phone first, queueing them for the next sync, reading the index once into the phone's copy (`readIndexIntoCache`, `CLONE_FIELDS`) and keeping that copy in step (`storeDoc`). Tag and name suggestions are worked out in `AppViewModel` from the phone's copy |
| `ui/map` | `PhotoClusterOverlay`: grouping and drawing the markers on the osmdroid map |
| `sync` | `SyncEngine` (the algorithm: your folders compared with the phone's copy of the index, then the photos to be read and the queued word changes carried up, and the refill of an emptied index from the copy), `SyncWorker`, `FaceWorker` (faces in the photos, naming after each sync), `SyncScheduler`, `PlanWatch`, `Notifier` |
| `ui` | Compose screens (photos, stats, map, sync, account, edit sheet), `AppViewModel`, theme |
