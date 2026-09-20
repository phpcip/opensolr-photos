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
| `taken_at` | date | EXIF DateTimeOriginal with its offset when present, else MediaStore's date. On a words-only rewrite the date already in the document is kept, so a photo with no date of its own does not land on today when its tags are written into the file |
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
| `persons_t` | text | The names of the people in the photo, as one line, read from the XMP property `PersonInImage` that whatever recognised the faces wrote on the file. No declared field of its own: it matches the `*_t` dynamic field |
| `persons_ss` | string, multi | The same names, each kept whole. No declared field of its own: it matches the `*_ss` dynamic field. The People filter, the People albums and the phone's copy read it; `persons_t` is analysed text and gives words |
| `embeddings` | dense vector, 1024, cosine | Vector of `meaning`. On a plan without vector search nothing is sent to be read at all, so `meaning`, `labels`, `ocr_t` and `embeddings` all stay empty: the document is date, camera, place, file name and the owner's own words, and search is lexical |
| `clip_model`, `embed_model` | string | What produced the labels and the vector |
| `dup_w2_hash` … `dup_w5_hash`, `dup_any4_ss`, `dup_exif_hash` | string (`*_hash`, `*_ss`) | The keys groups of alike photos are made of, written by the server ([similar photos](duplicates.md)) |
| `file_hash` | string (`*_hash`) | md5 of the original file, from the phone; the strictest duplicates stop. It is also the identity check on every sync: a photo whose size or modification time changed is weighed against the md5 the index already holds, and if it matches the picture is not read again. It is the key by which the words already in the document (`meaning`, `labels`, `ocr_t`) survive a pass that cannot read the photo |
| `indexed_at` | date | When the document was written |

Copy fields feed the search fields: `text` (meaning, the printed text `ocr_t`, the names in `persons_t`, file name, folder, camera, city, region, country, tags),
`file_name_text`, `folder_text`, `camera_text`, `place_text`, `custom_tags_text`. Two more serve typing:
`suggest` (stored, multi-valued: tags, labels, camera make and model, city, region, province, country) is the
suggester's dictionary, and `spell` (tags, labels, file name, camera, places) is the spellchecker's. Dynamic
fields (`*_s`, `*_ss`, `*_i`, `*_l`, `*_f`, `*_b`, `*_dt`, `*_t`) are there for anyone extending the app.

`suggest` serves the search box alone. The tag and name suggestions in the tagging sheet, and the list of
what the ticked photos already carry, are answered from the phone's copy of the index, not from this field.
Of the two names fields only `persons_t` is copied into `text`; `persons_ss` is copied nowhere, which is why
the People facet counts whole names. `camera_make` is still indexed and still copied into `text`,
`camera_text`, `spell` and `suggest`, but the app no longer offers a Camera make filter: the model already
reads `Nikon Z6`. The copy fields are not proof that a filter exists.

`ocr_t` is stored and copied into `text`, deliberately: stored so that reading the document back and
rewriting it (what editing a photo's tags does) keeps the printed text, and copied by Solr on every
write so the search never has to know the field exists. It is **not** copied into `suggest` or `spell` —
a receipt would fill autocomplete with its own numbers.

`*_hash` is a dynamic string field, indexed, not stored, with docValues: the duplicates view facets on it and
nothing displays it. Schema version 1.6 still returns docValues fields to `fl=*`, so a document read back and
written again (an edit) keeps its keys. The copy described below asks for a named `fl` list instead of `*`,
so the keys never come down to the phone at all. The old `meaning_hash` field and the `text_photo` type are gone.

## The copy on the phone

Since 2.5 the phone keeps a copy of every document its index holds: every field except the vector and the
duplicate keys. It is read once, at install, reinstall or after a reset, with `q=*:*`, `sort=id asc`,
`rows=1000` and `cursorMark` paging, and a named field list:

```
id,media_id,path,file_name,folder,mime,size_bytes,file_hash,
taken_at,indexed_at,modified_at,year,month,width,height,orientation,
camera_make,camera_model,lens,iso,exposure,f_number,focal_length,flash,
has_location,location,altitude,city,region,province,community,country,country_code,
labels,meaning,ocr_t,persons_t,persons_ss,custom_tags,clip_model,embed_model
```

From then on syncing and browsing never walk the index again: every write keeps the copy in step with what
the server says it wrote. Only two paths still read it whole, and neither is an ordinary sync — the
rescue of the owner's tags and wording before a reset, and taking every photo of an album at once.

The schema is what makes the copy possible. Every field in that list is `stored="true"`, so it can be read
back. The two exclusions are schema facts, not a product choice: `embeddings` is `stored="false"` and could
never be copied down, and `*_hash` is docValues-only, so the duplicate keys stay on the server where the
duplicates view facets on them.

Out of each document the copy pulls a few values into columns of their own — `id`, `size_bytes`,
`indexed_at`, `taken_at` (also as a number, `taken_ms`, indexed), `custom_tags`, the names, `meaning`,
`ocr_t`, `city`, `country`, `embed_model`, `file_hash` and the modification flag — with the whole document
alongside as JSON. Every tag, name and word of `meaning` also has a row of its own in a `doc_words` table,
so the suggestion lists are one count over it. Those columns are what answers browsing, sync and the
tagging sheet with no request at all, each from an index of its own (2.5.2), without reading the stored
documents. An update from an earlier version fills the new columns once, from the documents already on the
phone, in pages, with no request to the index.

There is no `day` field in the schema. Browsing is Year > Month > Day, and the day level is worked out on
the phone from `taken_at` in the copy. `year` and `month` remain for the filter facets, which are asked for
once and kept until a sync actually writes something.

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
`/update`. Two server endpoints write through `/update`, both with `commitWithin=10000`, so search sees a
change within about 10 seconds, even while a large library is still syncing: `photos_ingest`, which posts a
document with the 640 px copy of the picture, and `photos_words`, which posts up to 50 photos per call with
no picture at all. A `photos_words` call carries `id`, the tags, the names, `meaning` and `file_hash`; the
server reads the document, puts the words in, remakes the vector, writes it back and answers with what it
wrote. That is the read-back-and-rewrite the stored `ocr_t` and the docValues `*_hash` fields above exist
for: the printed text, the labels and the duplicate keys all survive an edit.

`/opensolr-photos-config` answers `config_version`, the version of these files (currently 11). When any of
them changes, raise it together with `IndexManager.CONFIG_VERSION`: existing indexes are then reset and
fully re-synced at their next sync, with the owner's consent ([sync](sync.md#the-index)). Remote
streaming and stream bodies are disabled. No `<lib>` directives, no script processors, no response writers
that run templates.

## Looking at the index yourself

It is a regular Opensolr Index in your account. Open it from the app (Account → Open this index on
opensolr.com) or from the Opensolr control panel to query it, back it up or empty it. Where you empty it
from matters. Emptied from the app, or rebuilt, the phone's copy is cleared with it and the next sync writes
everything again. Emptied from the Opensolr control panel, the phone's copy still lists every document, the
sync compares your folders against that copy and finds nothing to do, and the index stays empty. To recover
from that, empty it from the app or let it rebuild, so the copy goes too.
