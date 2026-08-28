-- The identifier a search result already carried and this application threw away.
--
-- `PlaceSuggestion.ref` has always held the upstream's own reference —
-- `node/240109189` — and `CreatePlaceRequest` did not accept it, so a saved place
-- kept only a name, a point and an address. That is enough to draw a pin and not
-- enough to ask anybody about the place afterwards: matching back by name and
-- coordinates is a guess, and a wrong guess describes the wrong building.
--
-- Nullable, and permanently so. A place typed by hand never had a reference and
-- never will; the column being empty is that place's honest answer, not missing
-- data to be backfilled.
ALTER TABLE places ADD COLUMN osm_ref VARCHAR(40);

-- The photo somebody chose from the candidates, kept whole.
--
-- The attribution columns are not decoration: a Wikimedia Commons image is CC BY,
-- CC BY-SA or public domain *per image*, and the licence that applies to one says
-- nothing about the next. Showing the picture without its author and terms is the
-- one part of this feature that would be a licensing failure rather than a bug,
-- so the credit is stored with the URL and travels with it everywhere it is
-- drawn. All five are written together or not at all.
ALTER TABLE places ADD COLUMN photo_url        VARCHAR(500);
ALTER TABLE places ADD COLUMN photo_thumb_url  VARCHAR(500);
ALTER TABLE places ADD COLUMN photo_author     VARCHAR(300);
ALTER TABLE places ADD COLUMN photo_licence    VARCHAR(120);
ALTER TABLE places ADD COLUMN photo_source_url VARCHAR(500);
