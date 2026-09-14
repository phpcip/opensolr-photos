# How it works

<p align="center">
  <img src="images/how-it-works.svg" alt="How a photo becomes searchable" width="100%">
</p>

Opensolr Photos has three moving parts: the app on the phone, the Opensolr platform, and the phone's own
Opensolr Index. The app talks to each of them over HTTPS and to nothing else.

## The parts

### The app

- Finds photos through Android's MediaStore, in the folders you chose (DCIM by default).
- Reads each photo's EXIF metadata itself: date, camera, lens, exposure, place.
- Makes a small upright JPEG copy (640 px on the long edge) for the reader. The copy is re-encoded from
  pixels, so it carries no metadata.
- Writes the documents into the index and searches it directly.
- Runs sync in the background with WorkManager, one sync at a time.

### opensolr.com

Account and index management:

- the sign-in pages (`/app/authorize`) and the code exchange (`/app/token`), see [sign-in](sign-in.md);
- creating the index, uploading its configuration, reading its address and password, plan limits.

### api.opensolr.com

The AI endpoints:

- `image_clip` reads a picture into words with CLIP (the same model and vocabulary as Opensolr's image
  search, without the text-reading steps). The picture is not stored.
- `batch_embed` turns up to 50 texts into search vectors at once; `embed` turns a typed query into one.

### The phone's Opensolr Index

A regular Opensolr Index named `photos_<ANDROID_ID>__dense`, created in your account on a server that runs
vector search, with the configuration from [`solr/conf`](../solr/conf). The app reads and writes it
directly with its HTTP Basic credentials: `/select` and `/update`.

<p align="center">
  <img src="images/data-boundaries.svg" alt="Where each piece of data lives" width="100%">
</p>

## One index per phone

`ANDROID_ID` is the identifier Android gives this app on this phone. It stays the same when the app is
reinstalled and differs on every other phone. So:

- **reinstall on the same phone:** sign in, the app finds `photos_<ANDROID_ID>__dense`, and runs a Re-Sync,
  which only adds and deletes the differences;
- **a new phone on the same account:** a different name, a new index of its own.

<p align="center">
  <img src="images/index-lifecycle.svg" alt="Reuse, create or recreate the index" width="100%">
</p>

## What the app calls

| Host | Call | When |
|---|---|---|
| opensolr.com | `GET /app/authorize` (in the browser) | Sign-in |
| opensolr.com | `POST /app/token` | Sign-in: code + verifier for email, API key, plan limits |
| opensolr.com | `POST /solr_manager/api/get_index_list` | Every sync: is the phone's index in the account? |
| opensolr.com | `POST /solr_manager/api/vector_regions` | Creating the index: where vector search runs |
| opensolr.com | `POST /solr_manager/api/create_index` | Creating the index |
| opensolr.com | `POST /solr_manager/api/upload_zip_config_files` | New index, or an index without this schema |
| opensolr.com | `POST /solr_manager/api/get_core_info` | Address and credentials of the index |
| opensolr.com | `POST /solr_manager/api/get_account_summary` | Plan limits and usage |
| api.opensolr.com | `POST /solr_manager/api/image_clip` | Once per new photo |
| api.opensolr.com | `POST /solr_manager/api/batch_embed` | Once per 20 new photos (vector search plans) |
| api.opensolr.com | `POST /solr_manager/api/embed` | Once per typed search (vector search plans) |
| your index | `POST /select` | Listing ids, searching, counting |
| your index | `POST /update` | Adding, deleting, committing |

Credentials always travel in the request body, never in a URL.

## Code map

| Package | Responsibility |
|---|---|
| `auth` | `AuthFlow` builds the PKCE request and opens the Custom Tab; `AuthCallbackActivity` receives the App Link |
| `data` | `AppPrefs` (settings, session), `SecureStore` (Keystore AES-GCM), `PhotoCache` (SQLite), models |
| `index` | `IndexManager`: index name, find, create, upload config, refresh credentials |
| `media` | `MediaScanner` (folders, photos, the id function), `PhotoReader` (EXIF, the 640 px copy) |
| `net` | `OpensolrApi` (REST API), `SolrClient` (direct Solr), typed errors |
| `search` | `SearchRepository`: query, filters, facets, parsing |
| `sync` | `SyncEngine` (the algorithm), `SyncWorker`, `SyncScheduler`, `Notifier` |
| `ui` | Compose screens, `AppViewModel`, theme |
