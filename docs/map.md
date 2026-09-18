# Map

The map shows every photo of the current search that carries a GPS position, grouped into markers. It is
drawn with [osmdroid](https://github.com/osmdroid/osmdroid) on OpenStreetMap tiles; no Google account and no
map key are involved.

## Opening it

- **Map** in the header of the photos screen opens it framed around every photo of the current search and filters
  that has a position. The positions are asked of the index, not read from the phone's copy of it: one
  `/select` with the same query, `fq=has_location:true`, `rows=1000`. Browsing the grid makes no requests in 2.5;
  the map still makes this one, held for the cache time you set, so reopening it soon after costs nothing.
- *Show on map*, in the row of actions under a photo opened full screen, opens it centred on that photo at
  zoom 15.
- The reload icon in the header asks the index for the pins again and frames them anew; it is the only way
  the map costs a second request while it is open.

## Markers

`PhotoClusterOverlay` groups the pins that fall into the same 84 dp screen cell at the current zoom and draws
each group as one 56 dp marker: the thumbnail of its first photo (decoded from the phone through Coil,
cached in memory) and a count badge. Zooming re-groups.

- Tapping a group opens a sheet with its photos, whatever its size; a tap there opens the photo full screen
  in the app, swiped through the photos of that group. Zooming stays yours: pinch, double tap, the +/- buttons.
- The sheet can be dragged up to fill the whole screen.
- Above the thumbnails the sheet says how many photos are here and where "here" is: the commonest city and
  country of the group, read from what those photos carry in the index. Nothing is looked up for it, and the
  line is left out when none of the photos knows its place.

## Search this area

The magnifier in the header, beside the count of photos with a place, takes the map's centre and the
distance to its north-east corner (rounded up to 0.1 km, at least 0.5 km) and returns to the photos with
`NearFilter(lat, lon, km)`:

```
fq      = {!geofilt sfield=location pt=$near_pt d=$near_d}
near_pt = 44.426800,26.102500
near_d  = 12.400
```

The radius can be changed on the filter sheet (0.5, 1, 5, 25, 100 km) or dropped with *Anywhere*.

## What the map sends

- Tile requests to `tile.openstreetmap.org`, with the app's user agent, only while the map screen is open;
  tiles are cached in the app's private cache directory (`Configuration.osmdroidTileCache`).
- Nothing else: the pins come from your index in that one request (a second only if you tap reload), the
  thumbnails from the phone, and the city and country of a group from the pins already loaded.

In dark mode the tiles are dimmed instead of turned inside out: a `ColorMatrixColorFilter` on the tiles
overlay, saturation at 0.85 and brightness at about two thirds, with the blues kept a little stronger, so the
sea stays blue and the land stays land.
