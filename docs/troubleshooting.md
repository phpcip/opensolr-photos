# Troubleshooting

## Sign-in

**The browser shows "Return to Opensolr Photos" instead of going back to the app.**
Tap **Open Opensolr Photos**. This happens when Android has not verified the app link yet (right after
installing) or with a debug build.

**"This sign-in was not started here or it took too long."**
The sign-in has to finish within 15 minutes and on the phone that started it. Tap **Sign in with
Opensolr** again.

**"Your Opensolr sign-in stopped working."**
The account's API key changed or the account is not active. Sign in again; your index is untouched.

## Setup

**"Your Opensolr plan cannot hold another index."**
Every phone uses one index. Delete an index you no longer use in the Opensolr control panel, or
[upgrade](https://opensolr.com/pricing), then tap **Try again**.

**"An index named photos_…__dense already exists in another Opensolr account."**
This phone's index belongs to a different Opensolr account. Sign in with that account, or delete that
index from it first.

## Updates

**"Version X is available" on the Photos screen.**
Once a day the app compares itself with the latest release on GitHub. **Download** opens the release
page in the browser; install the APK over the current one. Same signing key, so the index, the sign-in
and your edits stay. **Not now** hides the notice until a newer version comes out. Phones that got the
app from IzzyOnDroid or Obtainium update through those instead.

## Sync

**Photos are missing from search.**
First check the grid: group headings fold and stay folded between visits, so a year or a month can look
empty when it is only closed. Then open the Sync screen. If a sync is running, they are on their way.
Otherwise check that the photo's folder is ticked under **Folders being indexed**, then **Force Re-Sync**.
Syncing compares your folders with the phone's own copy of the index, so a photo can also be missing when
that copy and the index have drifted apart. See [sync](sync.md).

**The first sync after installing or reinstalling is slow, and the grid is empty at first.**
Once, at install or reinstall, the phone reads a copy of every document in its index. Until that finishes
there is nothing on the phone to browse. It happens only that once; every later write keeps the copy in
step.

**The sync finished at once and made no requests.**
That is normal. A sync with nothing to do makes zero requests, because the comparison happens on the phone
against its copy of the index. Earlier versions spent about 21 requests and 1.8 MB on every sync of a
10,000 photo library, with nothing to show for it.

**Tags or photos changed in the index itself do not show on the phone.**
The app is the only thing meant to write to this index. The phone's copy is read from the index once, at
install or reinstall, and after that it is kept in step only by what the app itself writes, so a change made
somewhere else — in the Opensolr control panel, or by another tool — never reaches it. **Force Re-Sync** does
not help: it compares your folders with the copy the phone already holds. *Reset and re-sync* is worse here,
because it empties the index and writes it again from the phone, so the outside change would be lost. The one
way to pick it up is to uninstall the app and install it again: you sign in, the app finds the same index and
reads it once more, whole. Nothing in the index is lost by that.

**The sync stops when I leave the app (Xiaomi, Redmi, POCO, Huawei, OnePlus, Oppo, Vivo).**
These phones kill background apps to save battery. The sync resumes where it stopped the next time it
runs, so nothing is lost, but it only advances while the app is open. Fix it once: phone settings for
the app, battery set to **No restrictions** (Xiaomi: *Battery saver* → *No restrictions*, and *Autostart*
on). Details per brand: https://dontkillmyapp.com

**"A sync is already running."**
Only one sync runs at a time. It finishes on its own; watch it on the Sync screen. Pulling the grid down
also starts a sync, and that pull is ignored while one is already running.

**Sync paused: AI requests used up.**
New photos wait for next month's allowance or an upgrade. Everything already indexed stays searchable, and
new photos are still indexed by date, camera, place, file name and your own words; only the reading of the
picture waits. Printed text and words already read out of a photo are kept when a later pass cannot read
it, as long as it is the same file, checked by its md5.

**Sync paused: disk space or bandwidth.**
The index is over a limit of the plan and Opensolr closes it until it is back under.
[Upgrade](https://opensolr.com/pricing), or wait for the monthly bandwidth reset.

**"Your photo index was emptied."**
The phone's index had disappeared from the account and was created again. It is refilled automatically from
the copy the phone holds of every document in the index. Photos read before do not use AI requests again:
the file is recognised by its md5 and the words and printed text already read out of it are put back as
they were.

**"Your index will be reset."**
This version of the app comes with a new index configuration. **Reset and re-sync** keeps your tags and
words, empties the index, uploads the new configuration and syncs every photo again; search keeps working
while the photos are added back. **Later** leaves the index as it is, but syncing waits until you agree.

**Some photos are counted as skipped.**
They could not be opened or decoded on the phone (damaged files, unusual formats). The red icon next to the
duplicates icon on the Photos screen lists them, with the file name and the reason. They are not tried again
until the file changes or you pick them for Re-sync.

## Search

**Search matches words only.**
Your plan does not include vector search, or this month's AI requests are used up. The note above the
results says which. Without vector search on the plan, no photo is sent to be read at all; photos are
still indexed by date, camera, place, file name and your own tags and names, and search matches those.

**A filter group looks missing.**
Every filter group is folded when you open the Filters screen, and each one remembers whether you left it
open. The badge on a heading counts how many filters are on inside that group. **Camera make** was removed
on purpose: the model already reads "Nikon Z6". A group with more than 50 values shows a small search field
instead of its values: tap it for the 50 most frequent, or type to find the rest.

**A photo I edited in the gallery no longer shows up in the same search.**
Expected. An edit saved as a copy is a new file, so a new photo to the app (its id is the md5 of its path),
read from scratch. It does not carry the tags, people or wording of the original either: gallery editors
re-encode the picture and drop the XMP those live in. A filter or a crop can also change what the photo is
read as, so a kiss in the woods may still be found by "kiss" and no longer by "woods". Search for what the
edited photo shows, or tag it again. An edit that overwrites the original keeps your own words.

**A photo's details show no *Map* and no *Nearby*, or the location filter has nothing in it.**
The photo has no GPS data, or photo-location access was not allowed when it was indexed. Allow it in
Android settings → Apps → Opensolr Photos → Permissions. Photos indexed from then on carry their place;
photos already indexed keep the details they were read with.

**"This photo is no longer on your phone."**
It was deleted or moved. The next Re-Sync removes it from the index.

**Newly synced photos do not show up in a typed search yet.**
The index makes new photos searchable within about 10 seconds. Browsing does not wait for that, because it
is answered from the phone. Pull down on the grid to reload it and to start a sync at the same time.

**The results look out of date.**
It depends on what you are looking at. Plain browsing, with nothing typed and no filter on, is answered
from the phone's copy of the index: it is never stale and it changes the moment a sync writes something.
Text and filtered searches are asked of the index, and their answers are kept on the phone and reused for
as long as you set on the account screen (at least a minute); pull down on the grid, or tap the reload
icon, to ask again. The lists of values on the Filters screen are asked for once and kept until a sync
actually writes. Your own edits, the photos you delete and every finished sync clear what is held straight
away, and **Clear cache** on the account screen throws away everything. See
[the search cache](search.md#search-cache).

**"No duplicates of this kind in your index."**
No two photos share the key of this slider stop. Move the slider to a looser stop, such as *Same first
word*. See [duplicates](duplicates.md).

**Finding duplicates asks for an index reset.**
The duplicate keys come with the newest index configuration. Approve *Reset and re-sync* on the photos
screen.

## Browsing and selecting

**A year or a month looks empty.**
The grid goes Year, then Month, then Day, and every heading folds. A folded heading is remembered between
visits, so a group you closed once stays closed until you open it again. Every month is spelled out by its
days, even a month with a single day in it.

**I cannot find Select or Check all.**
Both buttons are gone. Press and hold a photo to start selecting, then tap the others. Selecting ends by
itself when you take the last tick off.

**Ticking a heading selected more photos than I can see.**
That is what it does. A tick on a year, a month or a day takes the whole group, not only the photos loaded
on screen. In a text search, **Best matches** and **Also similar** have no tick on the heading, because
where one ends and the other starts moves as more results arrive.

## Tags and people

**I saved tags and nothing reached the index.**
Tags, names and wording are saved on the phone first and the save is finished there. The sync that starts
straight after carries them up, 50 photos to a call and with no pictures attached. So tagging works with no
signal and while a sync is paused. If that sync cannot run, the words are on the phone and not yet in the
index; they go up with the next sync that does run.

**Writing the words into the photo files stopped, or Android asked for permission.**
Writing your words into the photo files themselves is a separate step from the index, and it happens on the
spot: Android asks you to allow the change and a progress bar follows it. If you refuse the dialog or the
write is interrupted, the tags are still saved on the phone and still go to the index; only the files are
left as they were. Tag the photos again to be asked again. See [search](search.md).

**Replace wiped tags I wanted to keep.**
In the sheet for several photos at once, People and **My tags (Albums)** each have an **Add** or **Replace**
switch. Add puts what you type on top of whatever each photo already carries. Replace makes what you type
the whole of that field on every ticked photo. Under the form the sheet lists what the ticked photos carry
already, with counts, so check that list before using Replace.

**A photo without a date of its own jumped to today after I tagged it.**
Fixed in 2.5. The index keeps the date the photo had when its tags are written into the file.

**A tag I had just saved disappeared from the grid until the sync finished.**
Fixed in 2.5.2. A save now updates the whole document the grid draws from, not only the tag columns.

**The pull-to-refresh spinner stayed half way down.**
Fixed in 2.5.2. Browsing is answered by the phone, so the refresh finished before the spinner could see it
start; it now always sees the run begin and end.

**Tagging, sharing or deleting a large selection froze the screen.**
Fixed in 2.5.2. The files of the whole selection are found in one query, off the screen's thread, instead
of one lookup per photo.
