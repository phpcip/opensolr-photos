# Project structure

This page is for a developer who has just cloned the repository and wants to know what everything is, how the pieces talk to each other, and where to go to change something. It stays high level: after reading it you should be able to open the right file for any change.

## The big picture in one minute

Opensolr Photos is a single Android app written in **Kotlin**, with its screens built in **Jetpack Compose**. There is no server of its own: everything it needs on the server side is an Opensolr service it calls over HTTPS (see [every call it makes](https://opensolr.com/opensolr-photos-docs/api-calls)). Inside the app there are three flows, and almost every file belongs to one of them:

- **Sign-in**: Open the browser, come back with a code, swap it for the account key. Lives in `auth/`, finishes in `ui/AppViewModel.kt`.

- **Sync**: Find photos, compare with the index, read the new ones, write them. Lives in `sync/`, using `media/`, `net/`, `index/` and `data/`.

- **Search**: Turn what you typed into one Solr request and draw the results. Lives in `search/` and `ui/screens/SearchScreen.kt`.

## The repository, top to bottom

| Path | What it is |
|---|---|
| `app/` | The Android application module: all code, resources and the manifest. |
| `app/build.gradle.kts` | How the app is built: package name, minimum and target Android versions, version number, dependencies, release signing, and the task that zips the Solr configuration into the APK. |
| `app/src/main/AndroidManifest.xml` | Permissions, the two activities (the app, and the sign-in callback with its App Link), and the foreground service type for sync. |
| `app/src/main/java/com/opensolr/photos/` | All Kotlin code, one folder per responsibility (section 03). |
| `app/src/main/res/` | Resources: strings, colours, launcher and notification icons, the bundled Space Grotesk font, and two XML files that switch off cleartext traffic and backups. |
| `solr/conf/` | The configuration uploaded to every phone's index: `schema.xml`, `solrconfig.xml`, stop words, synonyms, protected words. |
| `docs/` | The Markdown documentation shown on GitHub, and `docs/images/` with every diagram as an SVG file. |
| `gradle/libs.versions.toml` | The one place every library and plugin version is written. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew` | The Gradle project around the app module, and the wrapper that downloads the right Gradle version. |
| `third_party/` | Licences of bundled third-party material (the font). |
| `README.md`, `SECURITY.md`, `LICENSE` | The front page, how to report a vulnerability, and the MIT licence. |
| `signing.properties`, `local.properties` | Not in the repository, on purpose: your signing key settings and your Android SDK path. Both are git-ignored. |

## The Kotlin packages

Everything below lives under `app/src/main/java/com/opensolr/photos/`. The packages are listed from the bottom of the stack (things that know nothing about screens) to the top (the screens).

### `data/` — what the app remembers

- `Models.kt` — the plain data classes the rest of the app passes around: the signed-in `Session`, the `IndexConnection` (address and password of the index), `AccountLimits` (with the photos-per-month arithmetic), `SyncSchedule` and `SyncReport`.
- `AppPrefs.kt` — every setting and saved value, in one private preferences file. If you need to remember something new between runs, add a property here.
- `SecureStore.kt` — encrypts and decrypts the two secrets with a Keystore key. `AppPrefs` calls it; nothing else should store a secret any other way.
- `PhotoCache.kt` — the SQLite table of documents and vectors already paid for, so a photo is never read twice.

### `net/` — talking to Opensolr

- `Http.kt` — the one shared HTTP client, with its timeouts and no redirects.
- `OpensolrApi.kt` — one function per Opensolr call: token exchange, index list, create, config upload, connection details, account summary, `image_clip`, `batch_embed`, `embed`. It also turns the platform's refusals into typed errors.
- `SolrClient.kt` — talks straight to the phone's index: search, list every id, add, delete, commit, check the schema.
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

- `SyncEngine.kt` — the sync algorithm itself, from “is the index there” to the final commit, including every stop condition. This is the heart of the app.
- `SyncWorker.kt` — runs the engine as a background job with a progress notification, and makes sure only one runs at a time.
- `SyncScheduler.kt` — starts a sync now, sets the weekly or monthly schedule, and exposes the live status the screens show.
- `Notifier.kt` — every notification the app posts.

### `search/` — finding photos

- `SearchRepository.kt` — builds the Solr request from the text and the filters (words, meaning, facets) and parses the answer into results.

### `ui/` — the screens

- `AppViewModel.kt` — the brain of the interface: one `UiState` value holding everything the screens draw, and one function per thing the user can do (sign in, save folders, search, force a re-sync, sign out...).
- `AppRoot.kt` — picks which screen to draw from `UiState.screen`.
- `screens/` — the screens: sign-in, welcome, permissions, folders and setup in `OnboardingScreens.kt`; `SearchScreen.kt` with the filter and details sheets; `SyncScreen.kt`; `AccountScreen.kt`.
- `Components.kt` — the shared building blocks: buttons, notices, labelled rows, usage bars, headers.
- `Actions.kt` — things handed to other apps (open a photo, a map, a web page) and the date and number formats.
- `theme/Theme.kt` — colours, typography and shapes, light and dark.

### At the top

- `MainActivity.kt` — the one real screen of the app: it hosts the Compose UI and passes sign-in callbacks and notification taps to the view model.
- `PhotosApp.kt` — runs once when the app starts and creates the notification channels.

## How the three flows move through the code

### Sign-in

- `SignInScreen` button → `AppViewModel.beginSignIn()` → `AuthFlow.start()` saves the verifier and state in `AppPrefs` and opens the browser.
- Android opens `AuthCallbackActivity`, which forwards the address to `MainActivity`.
- `AppViewModel.completeSignIn()` checks the state and calls `OpensolrApi.exchangeCode()`.
- The session and plan limits go into `AppPrefs`; the welcome screen shows the limits.

### Sync

- `SyncScheduler.runNow()` (or the schedule) starts `SyncWorker`, which runs `SyncEngine.run()`.
- `IndexManager.ensure()` makes sure the index exists, using `OpensolrApi`.
- `MediaScanner.scan()` lists the photos; `SolrClient.allIds()` lists the index.
- For new photos: `PhotoCache` or `PhotoReader` + `OpensolrApi.imageClip()`, then `batchEmbed()`, then `SolrClient.add()`.
- The `SyncReport` is saved; `SyncScheduler.status()` lets the screens redraw.

### Search

- Typing and pressing search → `AppViewModel.search()`.
- `SearchRepository.search()` may call `OpensolrApi.embedQuery()`, then `SolrClient.select()`.
- The results and facets go into `UiState`; `SearchScreen` draws them; a tap calls `Actions.openPhoto()`.

## Where to go to change something

| You want to | Touch |
|---|---|
| Store a new piece of photo information | `solr/conf/schema.xml` (the field), `SyncEngine.buildDocument()` (fill it), usually `PhotoReader` (read it); document it in `docs/`. Existing indexes pick up the new schema only if `IndexManager` detects it is missing, so change its schema check too. |
| Add a filter | `SearchFilters` and `SearchRepository` (the `fq` and the facet), then the filter sheet in `SearchScreen.kt`. |
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
