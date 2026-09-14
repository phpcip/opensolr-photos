# Plan limits

The app shows your plan's numbers right after sign-in, and keeps showing them, with what is used, on the
**Opensolr account** screen. This page explains what each one means for your photos.

## What counts

| Limit | What uses it | Notes |
|---|---|---|
| **AI requests per month** | `image_clip`: 1 per new photo read. `batch_embed`: 1 per photo embedded. `embed`: 1 per typed search. | A request is only counted when the model actually ran. The same picture, or the same query, answered from Opensolr's cache is free. Photos in the phone's cache are never sent again. |
| **Vector search** | Search by meaning | Without it, photos are still read into words (1 request per photo) and searchable by those words. |
| **Disk space** | The documents in the index | Descriptions only, never pictures. |
| **Search bandwidth per month** | Every search and every sync request to the index | Resets monthly. |
| **Indexes** | One per phone | Creating the phone's index fails when the account has no room left. |

**Photos per month** on the account screen is the monthly AI allowance divided by the requests one photo
costs: 2 with vector search, 1 without. **Photos left this month** uses what is left of the allowance.

## What the app does at a limit

The app warns at 90% of disk space, search bandwidth and AI requests, and again at the limit: one
notification per situation (`PlanWatch`, keys remembered in `AppPrefs.warnedKeys`, monthly ones keyed by
month) and the same text at the top of the account screen. A plan without vector search gets *Photos are
not recognised on your plan*: photos are then indexed by date, camera, place, file name and tags only.

| Limit reached | Effect | What you see |
|---|---|---|
| AI requests | Nothing stops: new photos are indexed with date, camera, place, file name and tags, without words or a vector, and read into words at the first sync after the reset. Search by meaning falls back to words. | A notification and a line on the Sync screen saying how many photos wait for their words |
| Disk space or bandwidth | Opensolr closes the index to requests until it is back under the limit. Sync stops. | A notification with an **Upgrade** button, the Sync screen says *Paused: the index reached its disk space or bandwidth* |
| Indexes | The phone's index cannot be created | The setup screen says so, with a link to the plans |

Nothing is lost. After an upgrade, or when the month rolls over, the next Re-Sync continues where the last
one stopped.

Every upgrade link opens [opensolr.com/pricing](https://opensolr.com/pricing) in your browser.
