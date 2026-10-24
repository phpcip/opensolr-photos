# Map

The map shows every photo of the current search that carries a GPS position, grouped into markers. It is
drawn with [osmdroid](https://github.com/osmdroid/osmdroid) on OpenStreetMap tiles; no Google account and no
map key are involved.

## Opening it

- **Map** in the header of the photos screen opens it framed around every photo of the current search and filters
  that has a position. Opened again while the app is still open, it comes back to the view it was left on.
- *Show on map*, in the row of actions under a photo opened full screen, opens it centred on that photo at
  street level.
- The positions come from the index in **one request per view**: every time the map stops moving, one `/select`
  with the same query and filters, `fq=has_location:true`, the area on screen rounded out to whole map cells, and
  results grouped on the photo's map cell at that zoom (`group.field=tile_z<zoom+1>_s`, `group.limit=1`). Each
  group comes back with its exact count and one photo as its cover. The cells are fixed on the globe (written
  for zooms 5 to 20 when a photo is indexed), so a small move shows the same groups with the same counts.
- The answer is held for the cache time you set, so going back to a view soon after costs nothing. When the
  index cannot answer, the map draws the photos from the phone's copy of the index instead.
- The reload icon asks the index again for the view on screen.

## Markers

`PhotoClusterOverlay` merges the cells that fall into the same 84 dp screen cell at the current zoom and draws
each group as one 56 dp marker: the thumbnail of a cover photo, drawn through the app's own image loader (so a
clip shows its picture, and a photo kept only in the backend its 720 px copy), and a badge with the exact sum
of the counts of its cells (1.2k, 15k, 1.1M above a thousand). Zooming re-groups.

- Tapping a group opens a sheet with **every** photo of its cells, whatever its size, a page of 60 at a time as
  you scroll (`{!terms}` on the cells' values, under the same query and filters). A tap there opens the photo full
  screen in the app, swiped through the photos loaded in the sheet. Zooming stays yours: pinch, double tap, the
  +/- buttons.
- The sheet can be dragged up to fill the whole screen.
- Above the thumbnails the sheet says how many photos are here and where "here" is: the commonest city (or
  region) and country of the photos loaded, read from what those photos carry in the index. Nothing is looked
  up for it, and the line is left out when none of the photos knows its place.
- A photo deleted from the map leaves the open sheet at once, and the pins of the view are asked for again.

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
- Nothing else: the pins come from your index in one request per view, the photos of an open group a page at
  a time, the thumbnails from the phone, and the city and country of a group from the photos already loaded.

In dark mode the tiles are dimmed instead of turned inside out: a `ColorMatrixColorFilter` on the tiles
overlay, saturation at 0.85 and brightness at about two thirds, with the blues kept a little stronger, so the
sea stays blue and the land stays land.
