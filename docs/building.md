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
./gradlew assembleRelease
```

The APK is in `app/build/outputs/apk/release/app-release.apk`, shrunk with R8 and signed. Without
`signing.properties` the release APK is built unsigned.

## The Solr configuration

`solr/conf` is zipped into the APK's assets by the `solrConfigZip` Gradle task on every build, as
`opensolr-photos-conf.zip`. Change the schema there; the app uploads it to any index that does not yet
carry it.

## Dependencies

AndroidX (Core, Activity, Lifecycle, WorkManager, Browser, ExifInterface), Jetpack Compose with Material 3,
OkHttp, Coil. Nothing else, and nothing that reports to a third party.
