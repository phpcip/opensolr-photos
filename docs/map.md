# Map

The map shows every photo of the current search that carries a GPS position, grouped into markers. It is
drawn with [osmdroid](https://github.com/osmdroid/osmdroid) on OpenStreetMap tiles; no Google account and no
map key are involved.

## Opening it

- **Map** in the header of the photos screen opens it framed around every photo of the current search and filters
  that has a position (`/select` with the same query, `fq=has_location:true`, `rows=1000`).
- *Show on map* in a photo's details opens it centred on that photo at zoom 15.

## Markers

`PhotoClusterOverlay` groups the pins that fall into the same 84 dp screen cell at the current zoom and draws
each group as one 56 dp marker: the thumbnail of its first photo (decoded from the phone through Coil,
cached in memory) and a count badge. Zooming re-groups.

- Tapping a group of up to 12 photos, or any group at zoom 17 or more, opens a sheet with its photos; a tap
  there opens the photo in the gallery.
- Tapping a larger group zooms 2.5 levels into it.

## Search this area

The button at the bottom takes the map's centre and the distance to its north-east corner (rounded up to
0.1 km, at least 0.5 km) and returns to the photos with `NearFilter(lat, lon, km)`:

```
fq      = {!geofilt sfield=location pt=$near_pt d=$near_d}
near_pt = 44.426800,26.102500
near_d  = 12.400
```

The radius can be changed on the filter sheet (0.5, 1, 5, 25, 100 km) or dropped with *Anywhere*.

## What the map sends

- Tile requests to `tile.openstreetmap.org`, with the app's user agent, only while the map screen is open;
  tiles are cached in the app's private cache directory (`Configuration.osmdroidTileCache`).
- Nothing else: the pins come from your index, the thumbnails from the phone.

In dark mode the tiles overlay is drawn with `TilesOverlay.INVERT_COLORS`.
