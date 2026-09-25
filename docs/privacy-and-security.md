# Privacy and security

<p align="center">
  <img src="images/data-boundaries.svg" alt="Where each piece of data lives" width="100%">
</p>

## What leaves the phone

| Data | Goes to | Kept there? |
|---|---|---|
| A 1024 px JPEG copy of each new photo, re-encoded from pixels and carrying the original's EXIF, with the file's name, folder and size, the names of any people already written on the file, and your tags and words for it when this phone has them, five per call — vector search plans only | api.opensolr.com `photos_ingest` | No: processed in memory, the document it produces goes into your index |
| The same 1024 px copy is also read for printed words — vector search plans only | opensolr.com `image_ocr`, then one Opensolr Solr server for the reading itself | No: read in memory on a temporary file that is deleted immediately; the text it produces goes into your index. The reading is cached against the picture's md5 so the same photo is never sent twice |
| The words you saved on up to 50 photos at a time — their id, tags, people, wording and the file's md5, as text, with no picture attached | api.opensolr.com `photos_words` | No: the server reads each document, puts the words in, makes the vector again and writes it back to your index |
| Your typed searches | api.opensolr.com `embed` (vector search plans), then your index `/select` | Not by the app; the query goes to your own index like any search on it |
| Labels, vector, the text printed in the photo, the people, your own tags and wording, the place, EXIF fields, path, folder, file name, size, the file's md5, duplicate keys | Your Opensolr Index | Yes, until the photo leaves the phone or you empty the index |
| Account email and API key | opensolr.com, with each API call | It is your account |

On a plan without vector search no picture is ever sent to be read: neither `photos_ingest` nor `image_ocr`
receives a 1024 px copy, and the photo is indexed from its date, camera, place, file name and your own words.
Search is then lexical.

A saved tag, name or wording is written on the phone first and is finished there. The sync that starts
straight after carries it up through `photos_words`. So your own words do reach the api box, even though on
those calls no picture does. The md5 travels with them (`file_hash`) so the server can tell it is still the
same file and keep the text it already read out of it — the printed text and the labels survive an edit, and
survive a later pass that cannot read the photo again.

**Sends nothing at all:** a sync with nothing to do, plain browsing with no words typed and no filters on,
tag and name suggestions, and the list of what the ticked photos already carry in the tagging sheet. All of
those are answered from the phone's own copy of the index. Before 2.5 a single sync of a 10,000 photo
library walked the index in about 21 requests and some 1.8 MB, every time, whether anything had changed or
not.

**Not sent anywhere:** the original files, their EXIF blocks as such, thumbnails, the phone's copy of the
index, and anything about how you use the app. The app has no analytics, no advertising, no crash reporting
and no third-party SDKs that talk to the network.

The one other host it ever contacts is `api.github.com`, for the latest release of the app: once a day, and
whenever you tap **Check for updates** on the account screen. The request is unauthenticated and carries
nothing about you or your photos.

## What stays on the phone

- **The phone's own copy of the index.** It holds every field of every document the index has, except the
  search vector and the duplicate keys: id, path, folder, file name, size, the dates, the camera, the EXIF
  fields, the place, the words the photo was read into, the printed text, the people, your own tags and
  wording, and the file's md5. It is read from the index once, at install or reinstall, and from then on
  every write keeps it in step. It is never uploaded; it is only ever rebuilt from your own index. It is a
  plain SQLite database in the app's private storage, not encrypted under the Keystore.
- The API key and the index password, each encrypted with AES-256-GCM under a key generated inside the
  Android Keystore. The key material never leaves the Keystore. These two are the only encrypted items.
- The photo cache (documents and vectors already paid for), in the app's private storage.
- The search cache: answers the index gave to typed searches, filtered searches and duplicate grouping,
  reused for as long as the owner set on the account screen. The filter lists are not governed by that
  setting: they are asked for once and held until a sync actually writes something. Tag and name
  suggestions are no longer cached answers at all — they are worked out from the copy of the index.
  See [the search cache](search.md#search-cache).
- Settings: chosen folders, schedule, last sync report.

What clears what:

- **Clear cache** on the account screen empties the search cache only. The copy of the index survives it.
- **Reset** empties the index and the copy together.
- **Signing out** forgets the account and the index connection — the API key and the index password
  go with it — and empties the photo cache, the places already looked up and the lists of skipped
  photos. It deliberately leaves two things behind: the copy of the index and the words you gave
  your photos, so signing back in on the same phone does not have to read either back.
- **Uninstalling** removes everything, the copy of the index and the credentials included.

`allowBackup` is off and the data-extraction rules exclude every domain from cloud backup and from
device-to-device transfer. That now matters for more than credentials: the excluded data includes the copy
of the index, so paths, dates, cameras, places, printed text, people's names and your tags never reach a
backup either. A restored or new phone signs in again and reads the copy back from the index once.

## Network

- HTTPS only. Cleartext traffic is disabled for the whole app in the network security configuration, and
  only the system's certificate authorities are trusted.
- Redirects are not followed for API calls, so credentials are never re-sent to a host the app did not name.
- Credentials travel in request bodies, never in URLs.
- The index is reached with its own HTTP Basic credentials over HTTPS.

## Sign-in

Browser-based OAuth 2.0 with PKCE; the password is only typed into the browser. See [sign-in](sign-in.md)
for every check made on both sides.

## Queries

What you type is only ever sent to Solr as a bound parameter (`v=$uq`), and every filter value through
`{!term}` with a bound value. No input is spliced into query syntax. See [search](search.md).

## Permissions

| Permission | Why | Required |
|---|---|---|
| Photos (`READ_MEDIA_IMAGES`, or storage on Android 12 and older) | To find and read the photos in your folders | Yes |
| Photo locations (`ACCESS_MEDIA_LOCATION`) | Android removes GPS from photos without it | No, photos are then indexed without a place |
| Location (`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`) | Asked only if you turn on *Place new photos*, so a new photo without a position takes the phone's, also in background syncs | No |
| Notifications | Sync progress and plan alerts | No |
| Internet, network state | Opensolr | Yes |
| Foreground service (data sync) | So Android does not stop a long sync half way | Yes |

The app never moves your photos and never changes the pictures. The only thing it writes into a file is the
owner's own words, in XMP (`dc:subject` and `opensolr:Tags` for tags, `PersonInImage` for names,
`opensolr:Meaning` for wording). Saving the words is finished on the phone; writing them into the files is a
separate step taken on the spot, after Android's write request, with a progress bar. It deletes one
only when you select it and press *Delete*, after its own warning; on Android 11 and newer Android
asks for confirmation again and does the deleting.

## Threat model, briefly

| If someone has… | They can | They cannot |
|---|---|---|
| A sign-in code seen in a URL | Nothing: it needs the verifier, which never leaves the app, and dies after 60 seconds or one use | Get the API key |
| Another app on the phone with the same URL scheme | Receive a code on the fallback path | Redeem it without the verifier |
| A copy of the app's private files | Read the copy of the index: paths, file names, dates, camera, place, printed text, people's names and your tags, all in clear | Decrypt the API key or the index password, which are Keystore blobs and do not open off the phone |
| Your unlocked phone | Use the app like you | Read your Opensolr password |

What protects the copy of the index is that it sits in app-private storage and that backup and
device-to-device transfer are off, not encryption. Getting at it needs root, or a debuggable build, or the
files off an unlocked phone.

Report security issues as described in [SECURITY.md](../SECURITY.md).
