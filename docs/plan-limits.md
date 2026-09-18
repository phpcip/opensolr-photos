# Plan limits

The app shows your plan's numbers right after sign-in, and keeps showing them, with what is used, on the
**Opensolr account** screen. This page explains what each one means for your photos.

## What counts

| Limit | What uses it | Notes |
|---|---|---|
| **AI requests per month** | `photos_ingest`: 1 per 10 new photos read (words, vector and the text printed in the photo together, a tenth of a request each). `photos_words`: a change of tags, names or wording, up to 50 photos in one call with no picture attached and one embedding for the whole batch, so the photos themselves cost nothing. `embed`: 1 per typed search. | A request is only counted when the model actually ran. The same picture, or the same query, answered from Opensolr's cache is free. Photos in the phone's cache are never sent again. Nothing is read at all on a plan without vector search. |
| **Vector search** | Search by meaning | Without it nothing is sent to be read: photos are indexed by date, camera, place, file name and your own tags, search is lexical over those, and indexing costs no AI requests. |
| **Disk space** | The documents in the index | Descriptions only, never pictures. |
| **Search bandwidth per month** | The searches you type, the one read of the whole index into the phone's copy after an install or a reinstall, and the syncs that actually write something | Resets monthly. The phone keeps its own copy of every document in its index, so a sync with nothing to do and all plain browsing (years, months, days, their counts and the photos in them) make no requests at all; tag and name suggestions and the *already on these photos* list in the tagging sheet are answered from that copy too. The filter lists are asked for once and kept until a sync writes something. Searches, duplicate groups and map photos are cached on the phone and reused for as long as you set on the account screen, so a repeated question is not paid for twice. See [Search](search.md#search-cache). |
| **Indexes** | One per phone | Creating the phone's index fails when the account has no room left. |

**Photos per month** on the account screen is the monthly AI allowance times ten, because one request
covers ten photos; **photos left this month** does the same with what is left of the allowance. A plan
with no monthly cap shows *No monthly cap*. On a plan without vector search indexing spends no AI
requests at all, so the figure does not apply to it.

AI requests and search bandwidth have come apart. Browsing, filtering and tagging can run all month
against none of either: only the searches you type and the photos newly read spend anything. A reinstall
reads the whole index back into the phone's copy once, and that one read is all it costs.

## What the app does at a limit

The app warns at 90% of disk space, search bandwidth and AI requests, and again at the limit: one
notification per situation (`PlanWatch`, keys remembered in `AppPrefs.warnedKeys`, monthly ones keyed by
month) and the same text at the top of the account screen. A plan without vector search is said there too,
as *Photos are not recognised on your plan*. That is the normal mode of such a plan, not a limit — it is
how the plan always works, as above.

| Limit reached | Effect | What you see |
|---|---|---|
| AI requests | Nothing stops: new photos are indexed with date, camera, place, file name and tags, without words or a vector, and read into words at the first sync after the reset. Search by meaning falls back to words. A photo already read keeps the words and the printed text read out of it earlier, as long as it is the same file, checked by its md5, so a pass that cannot read it takes nothing away. | A notification and a line on the Sync screen saying how many photos wait for their words |
| Disk space or bandwidth | Opensolr closes the index to requests until it is back under the limit. Sync stops. | A notification with an **Upgrade** button, the Sync screen says *Paused: the index reached its disk space or bandwidth* |
| Indexes | The phone's index cannot be created | The setup screen says so, with a link to the plans |

Nothing is lost. After an upgrade, or when the month rolls over, the next Re-Sync continues where the last
one stopped.

Every upgrade link opens [opensolr.com/pricing](https://opensolr.com/pricing) in your browser.
