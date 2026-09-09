# Extra KItinerary extractors

Extractor scripts this project carries itself, loaded by passing
`--additional-search-path` to `kitinerary-extractor`. The image copies this
directory to `/app/extractors` and points
`wander.booking-import.extractor-search-path` at it; blank means none are
loaded, which is what a jar run outside the container gets unless the operator
sets `WANDER_IMPORT_EXTRACTOR_SEARCH_PATH`.

## Why this exists at all

CLAUDE.md says the parsing is borrowed, not written, and that per-vendor parsers
of our own would be a treadmill that fails silently when a template changes.
That still holds, and this directory is the narrow exception rather than the
start of a collection: **a fix to one of the 349 upstream extractors, kept in
the shape upstream would accept it**, not a parser for a vendor upstream has
never heard of. `ana-fullyear.js` is a copy of KItinerary's own `ana.js` with a
four-digit year, because an ANA SKY WEB India ticket prints `31OCT2026` where
the upstream regex wants `31OCT26`.

The bar for adding another file here:

- **Upstream already has an extractor for this vendor and it nearly works.** If
  nothing upstream matches the document, the answer is still an empty draft
  list and a booking added by hand. Writing the first parser for a vendor is the
  treadmill; correcting a year format is not.
- **It is disjoint from the extractor it patches.** Both are loaded and both are
  offered every matching document, so two scripts that can match the same
  ticket import every leg of it twice. `ana-fullyear.js` matches a four-digit
  year *only*, which is why it can sit beside the built-in one safely.
- **It carries its upstream licence header.** These are derived from KDE's
  LGPL-2.0-or-later scripts and stay under that licence — one of the reasons the
  extractor is a subprocess and nothing here is linked.
- **It is reported upstream.** A file here is a patch waiting to be accepted; the
  day it lands in a released KItinerary, delete it.

## The shape of a file

A pair, sharing a basename, which is the extractor's name in
`kitinerary-extractor --list-extractors`:

- `<name>.json` — the filter that decides which documents reach the script, the
  entry-point function, and `"script": "<name>.js"`.
- `<name>.js` — the script.

`ExtractorManifestTest` checks the pairing and that the JSON parses, because
getting it wrong is silent: an extractor that fails to load is indistinguishable
from a document nothing recognised.

## Checking one by hand

```sh
docker compose exec wander sh -c \
  '/usr/lib/libexec/kf6/kitinerary-extractor --additional-search-path /app/extractors \
     --list-extractors | grep app/extractors'
```

and against a document, with the scripts that ran logged to stderr:

```sh
docker compose cp ticket.pdf wander:/tmp/t.pdf
docker compose exec wander sh -c \
  'QT_QPA_PLATFORM=offscreen XDG_CACHE_HOME=/tmp/kf6-cache QT_LOGGING_RULES="org.kde.kitinerary*=true" \
     /usr/lib/libexec/kf6/kitinerary-extractor --additional-search-path /app/extractors \
     -c 2026-09-05 /tmp/t.pdf'
```

If a script runs and still returns nothing, dump what it was given —
`pdf.pages[barcode.location].text` is empty when poppler cannot read the
document's fonts, which is a missing `poppler-data` rather than a bad regex, and
looks exactly like a regex that does not match. That is the mistake this
directory was born from; see the Dockerfile comment beside `poppler-data`.
