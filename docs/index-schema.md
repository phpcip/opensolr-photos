# Index schema

The app uploads the files in [`solr/conf`](../solr/conf) to the phone's index: `schema.xml`,
`solrconfig.xml`, `stopwords.txt`, `synonyms.txt`, `protwords.txt` and `mapping-ISOLatin1Accent.txt`. The build zips that folder into the
APK, so the app always uploads exactly what is in the repository.

## Fields

| Field | Type | Content |
|---|---|---|
| `id` | string | md5 of the absolute path, lower-case hex |
| `path` | string | Absolute path on the phone |
| `folder` | string | MediaStore folder, e.g. `DCIM/Camera/` |
| `file_name` | string | File name |
| `media_id` | long | MediaStore id, to open the photo |
| `mime` | string | e.g. `image/jpeg` |
| `size_bytes` | long | File size |
| `width`, `height` | int | Pixels, as the photo is displayed (EXIF rotation applied) |
| `orientation` | string | `landscape`, `portrait` or `square` |
| `taken_at` | date | EXIF DateTimeOriginal with its offset when present, else MediaStore's date |
| `year`, `month` | int | From `taken_at`, in the phone's time zone |
| `modified_at` | date | File modification time |
| `camera_make`, `camera_model`, `lens` | string | EXIF |
| `iso` | int | EXIF |
| `exposure` | string | EXIF, e.g. `1/250` |
| `f_number`, `focal_length` | float | EXIF |
| `flash` | boolean | EXIF, whether the flash fired |
| `has_location` | boolean | Whether the photo has usable GPS coordinates |
| `location` | location | `lat,lon`, a spatial field: radius searches with `{!geofilt}` |
| `city`, `region`, `province`, `community`, `country`, `country_code` | string | The nearest named place, from `nearby_places` |
| `altitude` | float | EXIF, metres |
| `meaning` | text | The CLIP labels joined with commas |
| `ocr_t` | text | The text printed **in** the photo, read with tesseract on Opensolr's OCR servers: a petrol receipt, an invoice, a shelf label, a screenshot. Separate from `meaning`, which is what the photo *shows* |
| `labels` | string, multi | The CLIP labels one by one |
| `custom_tags` | string, multi | The owner's tags; `custom_tags_text` is their tokenised copy for search |
| `embeddings` | dense vector, 1024, cosine | Vector of `meaning` (plans with vector search) |
| `clip_model`, `embed_model` | string | What produced the labels and the vector |
| `dup_w1_hash` … `dup_w5_hash`, `dup_exif_hash`, `dup_exif_w1_hash` … `dup_exif_w5_hash` | string (`*_hash`) | Duplicate keys, written by the server ([duplicates](duplicates.md)) |
| `indexed_at` | date | When the document was written |

Copy fields feed the search fields: `text` (meaning, the printed text `ocr_t`, file name, folder, camera, city, region, country, tags),
`file_name_text`, `folder_text`, `camera_text`, `place_text`, `custom_tags_text`. Two more serve typing:
`suggest` (stored, multi-valued: tags, labels, camera make and model, city, region, province, country) is the
suggester's dictionary, and `spell` (tags, labels, file name, camera, places) is the spellchecker's. Dynamic
fields (`*_s`, `*_ss`, `*_i`, `*_l`, `*_f`, `*_b`, `*_dt`, `*_t`) are there for anyone extending the app.

`ocr_t` is stored and copied into `text`, deliberately: stored so that reading the document back and
rewriting it (what editing a photo's tags does) keeps the printed text, and copied by Solr on every
write so the search never has to know the field exists. It is **not** copied into `suggest` or `spell` —
a receipt would fill autocomplete with its own numbers.

`*_hash` is a dynamic string field, indexed, not stored, with docValues: the duplicates view facets on it and
nothing displays it. Schema version 1.6 still returns docValues fields to `fl=*`, so a document read back and
written again (an edit) keeps its keys. The old `meaning_hash` field and the `text_photo` type are gone.

## Text analysis

Every text field (`meaning`, `text`, `custom_tags_text`, `place_text`, `file_name_text`, `folder_text`,
`camera_text`, `spell` and the dynamic `*_t`) uses `text_general`:

- `HTMLStripCharFilter`, `MappingCharFilter` (`mapping-ISOLatin1Accent.txt`);
- `ICUTokenizer`;
- `CJKWidth`, `EnglishPossessive`, `ASCIIFolding`, `StopFilter`, `WordDelimiterGraph` (with `FlattenGraph`
  at index time), `LowerCase`, `Length` 1–500, `RemoveDuplicates`;
- `SynonymGraph` at query time only (*photo, picture, image, pic*, *sea, ocean* and a few more).

There is no stemming.

`text_spell`, the analyzer of the suggester: `HTMLStrip`, `Mapping`, `ICUTokenizer`, `CJKWidth`,
`EnglishPossessive`, `ASCIIFolding`, `FlattenGraph`, `LowerCase`, `Length`, `RemoveDuplicates`.

## Vector field

```xml
<fieldType name="knn_vector" class="solr.DenseVectorField" vectorDimension="1024" similarityFunction="cosine"/>
```

Vectors come from Opensolr's embedding service, the same one every Opensolr vector index uses.

## solrconfig.xml

Deliberately small: `luceneMatchVersion` 9.0, classic schema (`schema.xml` is the schema), soft commit
every 10 seconds, hard commit every 60 without opening a searcher, `/select` (JSON, 60 rows by default, spellcheck component attached
and off unless asked), `/suggest` (`AnalyzingInfixLookupFactory` over `suggest`, `buildOnCommit`) and
`/update`. `photos_ingest` posts its documents with `commitWithin=10000`, so search sees newly indexed
photos within about 10 seconds, even while a large library is still syncing.

`/opensolr-photos-config` answers `config_version`, the version of these files (currently 9). When any of
them changes, raise it together with `IndexManager.CONFIG_VERSION`: existing indexes are then reset and
fully re-synced at their next sync, with the owner's consent ([sync](sync.md#the-index)). Remote
streaming and stream bodies are disabled. No `<lib>` directives, no script processors, no response writers
that run templates.

## Looking at the index yourself

It is a regular Opensolr Index in your account. Open it from the app (Account → Open this index on
opensolr.com) or from the Opensolr control panel to query it, back it up or empty it. Emptying it is safe:
the next sync fills it again, from the phone's cache.
