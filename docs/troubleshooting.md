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

## Sync

**Photos are missing from search.**
Open the Sync screen. If a sync is running, they are on their way. Otherwise check that the photo's folder
is ticked under **Folders being indexed**, then **Force Re-Sync**.

**The sync stops when I leave the app (Xiaomi, Redmi, POCO, Huawei, OnePlus, Oppo, Vivo).**
These phones kill background apps to save battery. The sync resumes where it stopped the next time it
runs, so nothing is lost, but it only advances while the app is open. Fix it once: phone settings for
the app, battery set to **No restrictions** (Xiaomi: *Battery saver* → *No restrictions*, and *Autostart*
on). Details per brand: https://dontkillmyapp.com

**"A sync is already running."**
Only one sync runs at a time. It finishes on its own; watch it on the Sync screen.

**Sync paused: AI requests used up.**
New photos wait for next month's allowance or an upgrade. Everything already indexed stays searchable.

**Sync paused: disk space or bandwidth.**
The index is over a limit of the plan and Opensolr closes it until it is back under.
[Upgrade](https://opensolr.com/pricing), or wait for the monthly bandwidth reset.

**"Your photo index was emptied."**
The phone's index had disappeared from the account and was created again. It is refilled automatically,
from the phone's cache, without using AI requests for photos read before.

**Some photos are counted as skipped.**
They could not be decoded on the phone or were refused by the reader (damaged files, unusual formats).
They are tried again at every Re-Sync.

## Search

**Search matches words only.**
Your plan does not include vector search, or this month's AI requests are used up. The note above the
results says which.

**The location filter or "Show on map" is missing.**
The photo has no GPS data, or photo-location access was not allowed when it was indexed. Allow it in
Android settings → Apps → Opensolr Photos → Permissions. Photos indexed from then on carry their place;
photos already indexed keep the details they were read with.

**"This photo is no longer on your phone."**
It was deleted or moved. The next Re-Sync removes it from the index.
