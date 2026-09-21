# Building from source

## Toolchain

| | Version |
|---|---|
| JDK | 17 or newer (release builds are made with 22) |
| Android SDK platform | 36 |
| Android Gradle Plugin | 8.13.1 |
| Gradle | 8.13 (wrapper included) |
| Kotlin | 2.0.21 |
| Minimum Android | 8.0, API 26 |

Point Gradle at the SDK with `ANDROID_HOME`, or a `local.properties` file containing
`sdk.dir=/path/to/Android/sdk`.

## Debug build

```bash
./gradlew assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`. A debug build is signed with your local debug key, which
opensolr.com's `assetlinks.json` does not list, so after approving the sign-in Android shows the
**Return to Opensolr Photos** page; tap its button and the sign-in finishes through `opensolr-photos://auth`.

## Release build

Create `signing.properties` in the project root (it is git-ignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```bash
./gradlew assembleGithubRelease      # the APK for GitHub and sideloading
./gradlew bundlePlayRelease          # the app bundle for Google Play
```

The APK is in `app/build/outputs/apk/github/release/app-github-release.apk`, the bundle in
`app/build/outputs/bundle/playRelease/app-play-release.aab`, both shrunk with R8 and signed. Without
`signing.properties` they are built unsigned.

There are two flavors, `github` and `play`, with the same code and the same version. The `github` build
updates itself from the latest GitHub release; the `play` build leaves updating to Google Play and does not
declare `REQUEST_INSTALL_PACKAGES` (`app/src/play/AndroidManifest.xml`).

## The Solr configuration

`solr/conf` is zipped into the APK's assets by the `solrConfigZip` Gradle task on every build, as
`opensolr-photos-conf.zip`. Change the schema there, and raise `config_version` in `solrconfig.xml`
together with `IndexManager.CONFIG_VERSION` (both 9 now): a new index gets the configuration at creation,
and an existing one on an older version is reset and fully re-synced, with the owner's consent
([sync](sync.md#the-index)).

## Dependencies

AndroidX (Core, Activity, Lifecycle, WorkManager, Browser, ExifInterface), Jetpack Compose with Material 3,
OkHttp, Coil. Nothing else, and nothing that reports to a third party.
