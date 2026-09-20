# Opensolr Photos documentation

<p align="center">
  <img src="images/how-it-works.svg" alt="How a photo becomes searchable" width="100%">
</p>

| Page | What is in it |
|---|---|
| [How it works](how-it-works.md) | The whole picture: components, hosts, every call the app makes |
| [Sign-in](sign-in.md) | OAuth 2.0 authorization code flow with PKCE, step by step |
| [Sync and Re-Sync](sync.md) | The algorithm, photo ids, schedule, Force Re-Sync, index recreation and reset |
| [Search](search.md) | The header, query building, grouping, filters, autocomplete, deleting, editing tags, the search cache |
| [Map](map.md) | Markers, groups, Search this area, what the map sends |
| [Albums](albums.md) | The sections, the one facet request, covers and names |
| [Similar photos](duplicates.md) | The 7 slider stops, the keys behind them, the caps on a group, Select 1 of each group |
| [Index schema](index-schema.md) | Every field of the index, the analyzers, the vector field, the duplicate keys |
| [Plan limits](plan-limits.md) | AI requests, disk space, bandwidth, indexes, and what happens at a limit |
| [Privacy and security](privacy-and-security.md) | What is stored where, what travels, how it is protected |
| [Project structure](project-structure.md) | A developer tour: every folder, every package, where to change what |
| [Building from source](building.md) | Toolchain, debug and release builds, signing |
| [Contributing](../CONTRIBUTING.md) | How changes get in, conventions, and how to become a contributor |
| [Troubleshooting](troubleshooting.md) | Common situations and what to do about them |

Website: [opensolr.com/opensolr-photos](https://opensolr.com/opensolr-photos)
