# Project structure

This page is for a developer who has just cloned the repository and wants to know what everything is, how the pieces talk to each other, and where to go to change something. It stays high level: after reading it you should be able to open the right file for any change.

## The big picture in one minute

Opensolr Photos is a single Android app written in **Kotlin**, with its screens built in **Jetpack Compose**. There is no server of its own: everything it needs on the server side is an Opensolr service it calls over HTTPS (see [every call it makes](https://opensolr.com/opensolr-photos-docs/api-calls)). Inside the app there are five paths, and almost every file belongs to one of them:

- **Sign-in**: Open the browser, come back with a code, swap it for the account key. Lives in `auth/`, finishes in `ui/AppViewModel.kt`.

- **Sync**: Find photos, compare them with the phone's own copy of the index, send the new ones to be read and written. Lives in `sync/`, using `media/`, `net/`, `index/`, `search/` and `data/`.

- **Browse**: Draw the grid from the phone's own copy of the index, without a request. Lives in `ui/AppViewModel.kt` and `data/PhotoCache.kt`.

- **Search**: Turn a typed query or a filter into one Solr request and draw the results. Lives in `search/` and `ui/screens/SearchScreen.kt`.

- **Tagging**: Save tags, names and wording on the phone, and let the next sync carry them up. Lives in `search/EditRepository.kt`, `ui/screens/EditSheet.kt` and `ui/screens/BulkTagSheet.kt`.

From 2.5 the app is offline-first. The phone keeps a copy of every document its index holds, so a sync with nothing to do and a plain browse both make **zero requests**. The network is only touched for a typed search or a filter, for a photo that has to be read, for a write, and for the one-off read of the index after an install or a reset.

## The repository, top to bottom

| Path | What it is |
|---|---|
| `app/` | The Android application module: all code, resources and the manifest. |
| `app/build.gradle.kts` | How the app is built: package name, minimum and target Android versions, version number, dependencies, release signing, and the task that zips the Solr configuration into the APK. |
| `app/src/main/AndroidManifest.xml` | Permissions, the two activities (the app, and the sign-in callback with its App Link), and the foreground service type for sync. |
| `app/src/main/java/com/opensolr/photos/` | All Kotlin code, one folder per responsibility (section 03). |
| `app/src/main/res/` | Resources: strings, colours, launcher and notification icons, the bundled Space Grotesk font, and two XML files that switch off cleartext traffic and backups. |
| `solr/conf/` | The configuration uploaded to every phone's index: `schema.xml`, `solrconfig.xml`, stop words, synonyms, protected words, the accent mapping. |
| `docs/` | The Markdown documentation shown on GitHub, and `docs/images/` with every diagram as an SVG file. |
| `gradle/libs.versions.toml` | The one place every library and plugin version is written. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew` | The Gradle project around the app module, and the wrapper that downloads the right Gradle version. |
| `third_party/` | Licences of bundled third-party material (the font). |
| `README.md`, `SECURITY.md`, `LICENSE` | The front page, how to report a vulnerability, and the MIT licence. |
| `signing.properties`, `local.properties` | Not in the repository, on purpose: your signing key settings and your Android SDK path. Both are git-ignored. |

## The phone's copy of the index

Since 2.5 the phone holds its own copy of every document its index holds: everything except the search vector and the duplicate keys — the id, the path, the file name, the size, the dates, the camera and EXIF, the place, the words the photo was read into, the printed text, the people, the owner's tags and the md5 of the file.

- It is read from the index **once**: at install, at reinstall, and after a reset. From then on it is kept in step by every write, and the index is never walked again.
- Two files own it. `data/PhotoCache.kt` is the store (the SQLite `docs` table and the queue of writes still to go up). `search/EditRepository.kt` fills it and pushes from it: `cloneMissing()`, `readIndexIntoCache()`, `storeDoc()` and the `CLONE_FIELDS` list of what a copy holds.
- What makes it safe is one invariant: **this app is the only writer of that index**. Nothing else can change a document behind the phone's back, so a copy brought in step by every write cannot drift.
- `AppPrefs.cloneComplete` says the copy is whole, and `KEY_CLONE_FORMAT` says which shape it has. Changing the shape forces the copy to be read again.

Because of that copy, the sync diff, the browse grid, the tag and name suggestions and the counts under the tagging sheet are all answered on the phone.

## The Kotlin packages

Everything below lives under `app/src/main/java/com/opensolr/photos/`. The packages are listed from the bottom of the stack (things that know nothing about screens) to the top (the screens).

### `data/` — what the app remembers

- `Models.kt` — the plain data classes the rest of the app passes around: the signed-in `Session`, the `IndexConnection` (address and password of the index), `AccountLimits` (with the photos-per-month arithmetic), `SyncSchedule` and `SyncReport`.
- `AppPrefs.kt` — every setting and saved value, in one private preferences file. If you need to remember something new between runs, add a property here. The state 2.5 added lives here too: `cloneComplete` and `KEY_CLONE_FORMAT` (the phone's copy of the index and its shape), `facetsJson` (the filter lists, kept until a sync writes something), `collapsedHeadings`, `openFilterSections` and `foldedAlbumSections`.
- `SecureStore.kt` — encrypts and decrypts the two secrets with a Keystore key. `AppPrefs` calls it; nothing else should store a secret any other way.
- `PhotoCache.kt` — the biggest file in the package, and the app's local database: the phone's copy of the index plus the queue of writes still to go up. The `docs` table (`putDoc`, `doc`, `docSizes`, `docFileHash`, `takenTimes`, `docsBetween`, `docCount`, `docsWithoutWords`, `docsLikeDocuments`, `docsIndexedBefore`, `updateDocSize`, `removeDocs`, `clearDocs`); the word counts the suggestions are built from (`wordCounts`, `wordCountsOf`); the queue (`queueAction`, `queueActions`, `actionOf`, `actions`, `clearActions`, with the kinds `ACTION_INDEX`, `ACTION_WORDS` and `ACTION_DELETE`); and the owner's edits, the skipped photos, the word-retry stamps and the places. It also still holds the vectors already paid for, so a photo is never read twice.
- `Words.kt` — `Words.fold()`, `Words.tidy()` and `List<String>.distinctWords()`: the one place two spellings of a tag or a name are decided to be the same word. Everything that saves tags or people goes through it.
- `SearchCache.kt` — a SQLite table of answers the index already gave, keyed by the request itself and reused for as many seconds as the owner chose (`AppPrefs.cacheSeconds`). Only the reads in `SearchRepository` go through it; writes, the schema checks and the one-off read of the index into the phone's copy never do. See [the search cache](https://opensolr.com/opensolr-photos-docs/search#search-cache).

### `net/` — talking to Opensolr

- `Http.kt` — the one shared HTTP client, with its timeouts and no redirects.
- `OpensolrApi.kt` — one function per Opensolr call: token exchange, index list, create, config upload, connection details, account summary, `photos_ingest` (`photosIngest()`, the call a sync sends photos with), `photos_words` (`photosWords()`, words only, fifty photos per call and no pictures), `image_index`, `batch_embed`, `embed`. It also turns the platform's refusals into typed errors.
- `SolrClient.kt` — talks straight to the phone's index: search, add, delete, empty, commit, check the schema and its configuration version, autocomplete, duplicate groups. The method that matters in 2.5 is `forEachDoc(fields, ...)`, the one-off read of the whole index into the phone's copy of it (and the read that keeps the owner's edits before a reset). `allIds()` is no longer part of a sync.
- `UpdateCheck.kt` — compares the app with the latest release on GitHub: once a day in the background, and on demand from the account screen. `check()` returns a `Result`, so a failed check is never reported as "up to date".
- `Errors.kt` — the exceptions, named after what the app has to do about them (sign in again, quota used up, plan limit, rate limited, photo rejected...).

### `media/` — photos on the phone

- `MediaScanner.kt` — lists folders, scans the chosen ones, and holds `photoId()`, the one place a photo's id is computed.
- `PhotoReader.kt` — reads EXIF metadata and makes the upright 640 px copy sent to be read.

### `index/` — the phone's index

- `IndexManager.kt` — the index name, finding it, creating it, choosing its environment, uploading the configuration, and re-reading its password. The rule “only create when certainly missing” lives here.

### `auth/` — signing in

- `AuthFlow.kt` — builds the PKCE request and opens the browser.
- `AuthCallbackActivity.kt` — a tiny invisible screen Android opens at the end of the sign-in; it only forwards the address to the main screen.

### `sync/` — keeping the index in step

- `SyncEngine.kt` — the sync algorithm itself, from “is the index there” to the final commit, including every stop condition. This is the heart of the app. In 2.5 the comparison is entirely local: `MediaScanner.scan()` against `PhotoCache.docSizes()`, with `EditRepository.readIndexIntoCache()` run once when `cloneMissing()` is true. A file that looks touched is weighed by its md5 before anything is sent, so a file this app rewrote itself is not read again. Every document the server answers with goes straight into the phone's copy through `EditRepository.storeDoc()`, and after the commit the engine calls `EditRepository.sendWords()` to carry queued word changes up. A plan without vector search sends no picture at all: photos are indexed from what the phone knows.
- `SyncWorker.kt` — runs the engine as a background job with a progress notification, and makes sure only one runs at a time.
- `SyncScheduler.kt` — starts a sync now, sets the weekly or monthly schedule, and exposes the live status the screens show.
- `Notifier.kt` — every notification the app posts.
- `PlanWatch.kt` — the warnings at 90% and at a plan limit, each posted once.

### `search/` — finding photos

- `SearchRepository.kt` — builds the Solr request from the text and the filters (words, meaning, facets) and parses the answer into results; also the albums facet and the duplicate groups. It is only reached by a typed query or a filter. Tag, name and wording suggestions no longer come from here: they are counted on the phone, in `AppViewModel.localWords()` / `cachedWordCounts()` over `PhotoCache.wordCounts()` and `wordCountsOf(ids)`. `browseFacets()` is asked once and kept in `AppPrefs.facetsJson` until a sync writes something. Every read it makes passes through `SearchCache` first, and anything the app writes empties that cache.
- `EditRepository.kt` — two jobs, despite the name. It saves the owner's words without touching the index: `saveLocal()` for one photo and `queueForAll()` for many write the edits, bring the phone's copy of the document in step and queue `ACTION_WORDS`; `sendWords()`, called by the sync, drains that queue through `photos_words`, fifty photos per call and no pictures. It is also the owner of the phone's copy of the index: `cloneMissing()`, `readIndexIntoCache()`, `storeDoc()` and `CLONE_FIELDS`. Nothing here reads a document back from the index and nothing here computes a vector.

### `ui/` — the screens

- `AppViewModel.kt` — the brain of the interface: one `UiState` value holding everything the screens draw, and one function per thing the user can do (sign in, save folders, search, force a re-sync, sign out...).
- `AppRoot.kt` — picks which screen to draw from `UiState.screen`.
- `screens/` — the screens: sign-in, welcome, permissions, folders and setup in `OnboardingScreens.kt`; `SearchScreen.kt` with the header, the filter and details sheets, the duplicates view and the selection bar; `EditSheet.kt` for one photo; `BulkTagSheet.kt` for many at once (People and My tags, each with its Add or Replace switch, and what the ticked photos already carry underneath); `AlbumsScreen.kt`; `MapScreen.kt`; `SyncScreen.kt`; `AccountScreen.kt`.
- `map/PhotoClusterOverlay.kt` — groups the map's photos into thumbnail markers.
- `Components.kt` — the shared building blocks: buttons, notices, labelled rows, usage bars, headers.
- `Haptics.kt` — the taps the fast scroller gives back while a finger drags through the months and days.
- `OutsideTap.kt` — one rule for every field with a suggestion list: a tap outside the field closes the list.
- `Actions.kt` — things handed to other apps (open a photo, a map, a web page) and the date and number formats.
- `theme/Theme.kt` — colours, typography and shapes, light and dark.

### At the top

- `MainActivity.kt` — the one real screen of the app: it hosts the Compose UI and passes sign-in callbacks and notification taps to the view model.
- `PhotosApp.kt` — runs once when the app starts and creates the notification channels.

## How the five paths move through the code

### Sign-in

- `SignInScreen` button → `AppViewModel.beginSignIn()` → `AuthFlow.start()` saves the verifier and state in `AppPrefs` and opens the browser.
- Android opens `AuthCallbackActivity`, which forwards the address to `MainActivity`.
- `AppViewModel.completeSignIn()` checks the state and calls `OpensolrApi.exchangeCode()`.
- The session and plan limits go into `AppPrefs`; the welcome screen shows the limits.

### Sync

- `SyncScheduler.runNow()` (or the schedule) starts `SyncWorker`, which runs `SyncEngine.run()`.
- `IndexManager.ensure()` makes sure the index exists, using `OpensolrApi`.
- On a fresh install, a reinstall or after a reset, `EditRepository.cloneMissing()` is true, so `readIndexIntoCache()` walks the index once through `SolrClient.forEachDoc(CLONE_FIELDS)`.
- `MediaScanner.scan()` lists the photos; `PhotoCache.docSizes()` is the phone's copy of what the index holds. The two are compared on the phone — no request. A photo that looks changed is checked against `PhotoCache.docFileHash()` and `PhotoReader.fileMd5()` first.
- Ids gone from the phone go to `SolrClient.delete()`; ids to be indexed are queued as `PhotoCache.ACTION_INDEX`.
- For each queued photo: `PhotoReader.copyForIngest()` → `OpensolrApi.photosIngest()`, where the server reads the picture, embeds it, builds the document and writes it → `EditRepository.storeDoc()` puts that document into the phone's copy. Without vector search on the plan nothing is sent to be read; the photo is indexed from its date, camera, place, file name and the owner's words.
- `SolrClient.commit()`, then `EditRepository.sendWords()` drains the `ACTION_WORDS` queue through `photos_words`.
- The `SyncReport` is saved; `SyncScheduler.status()` lets the screens redraw.
- A sync with nothing to do makes no request at all.

### Browse

- Nothing typed, no filter, no duplicates, and the phone's copy is whole → `AppViewModel.browsingLocally()` is true.
- `AppViewModel.browseLocally()` → `PhotoCache.takenTimes()` → `buildSkeleton()` builds the Year > Month > Day headings and their counts.
- Opening a group calls `loadGroupPhotos()`, which reads that stretch of time from `PhotoCache.docsBetween()`.
- `SearchScreen` draws it. Not one request; the filter lists come from `AppPrefs.facetsJson` and are only asked for again once a sync has written something.

### Search

- Typing and pressing search, or turning a filter on → `AppViewModel.search()`.
- `SearchRepository.search()` may call `OpensolrApi.embedQuery()`, then `SolrClient.select()`. On a plan without vector search the query stays lexical.
- The results and facets go into `UiState`; `SearchScreen` draws them; a tap calls `Actions.openPhoto()`.

### Tagging

- `EditSheet` (one photo) or `BulkTagSheet` (the ticked ones) → `AppViewModel.saveEdits()` / `tagPhotos()`.
- `EditRepository.saveLocal()` or `queueForAll()` writes the edits, brings the phone's copy of each document in step and queues `PhotoCache.ACTION_WORDS`. The save is finished there, on the phone.
- A sync starts straight after and `EditRepository.sendWords()` carries the change up through `photos_words`.
- Writing the words into the photo files themselves is a separate, synchronous branch: `PhotoReader.writeXmp()` called from `AppViewModel`, with Android's permission dialog and a progress bar, and `PhotoCache.updateDocSize()` afterwards so the next sync does not take the rewritten file for a changed picture.
- Suggestions and the "already on these photos" counts under the form come from `AppViewModel.localWords()` over `PhotoCache.wordCounts()`.

## Where to go to change something

| You want to | Touch |
|---|---|
| Store a new piece of photo information | `solr/conf/schema.xml` (the field) and the server side of `photos_ingest`, which builds the document; usually `PhotoReader` (read it off the phone). The phone's copy needs it too: add it to `EditRepository.CLONE_FIELDS` and to `PhotoCache.Doc` with its column, and raise the clone format so every phone reads its copy again. Document it in `docs/`. Existing indexes pick up the new schema only if `IndexManager` detects it is missing, so change its schema check too. |
| Change what the phone's copy of the index holds | `EditRepository.CLONE_FIELDS`, `PhotoCache.Doc` and the `docs` table, and the clone format version in `AppPrefs` — raising it forces the copy to be read from the index again. |
| Add a kind of queued write | A new `PhotoCache.ACTION_*` value, the merge rule in `queueAction()`, and the place it is drained: `EditRepository.sendWords()` or the `ACTION_INDEX` branch of `SyncEngine.run()`. |
| Change what browsing draws without the network | `AppViewModel.buildSkeleton()` for the headings, `PhotoCache.takenTimes()` and `docsBetween()` for what feeds them. |
| Change a suggestion list | `AppViewModel.localWords()` / `cachedWordCounts()` and `PhotoCache.wordCounts()` — not `SearchRepository`. |
| Add a filter | `SearchFilters` and `SearchRepository` (the `fq` and the facet), then the filter sheet in `SearchScreen.kt`. |
| Add an album section | `SearchRepository.albums()` (one more facet in the JSON facet request), then `AlbumsScreen.kt`. |
| Add a duplicates stop | The key on the server, a `*_hash` field name in `SearchRepository`'s duplicate fields, and the slider in `SearchScreen.kt`. |
| Change how search ranks | The `lex` and `vec` parameters in `SearchRepository`. |
| Add a screen | A new value in `Screen`, a composable in `ui/screens/`, a branch in `AppRoot.kt`, and the actions in `AppViewModel`. |
| Remember a new setting | A property in `AppPrefs`, its default in `UiState`, and the control on the screen. |
| Call a new Opensolr endpoint | A function in `OpensolrApi`, any new refusal in `Errors.kt`, and a row in `docs/how-it-works.md`. |
| Change how a sync reacts to an error | The `catch` blocks at the end of `SyncEngine.run()`, and the status wording in `SyncScreen.kt`. |
| Add a notification | A function in `Notifier.kt`. |
| Change colours or fonts | `ui/theme/Theme.kt`. |
| Upgrade a library | `gradle/libs.versions.toml`, then build. |
| Release a new version | `versionCode` and `versionName` in `app/build.gradle.kts`, a signed release build, and a GitHub release with the APK. See [building](https://opensolr.com/opensolr-photos-docs/building). |

## Before you start

- Read [building from source](https://opensolr.com/opensolr-photos-docs/building) to get a debug build running on your phone.
- Read [contributing](https://opensolr.com/opensolr-photos-docs/contributing) for the conventions and the security rules every change keeps.
- Ask for contributor access at [support@opensolr.com](mailto:support@opensolr.com?subject=Contributor%20request%3A%20Opensolr%20Photos).

---

Want to work on it with us? Write to [support@opensolr.com](mailto:support@opensolr.com?subject=Contributor%20request%3A%20Opensolr%20Photos) to become a contributor to Opensolr Photos or any other Opensolr open source project.
