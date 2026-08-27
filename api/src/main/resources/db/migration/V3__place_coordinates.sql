-- Coordinates on a place, so a search result keeps the point it was found at.
--
-- All three columns are nullable: a place typed by hand has no location, and it
-- is still a perfectly good itinerary entry. The map will simply not draw a pin
-- for it.
--
-- `address` holds the geocoder's own formatted line (Nominatim's display_name)
-- rather than parsed components. Parsing it into street/city/country would mean
-- keeping a schema in step with a foreign API's idea of an address, and nothing
-- in this project queries by them.

ALTER TABLE places
    ADD COLUMN latitude  DOUBLE PRECISION,
    ADD COLUMN longitude DOUBLE PRECISION,
    ADD COLUMN address   VARCHAR(500),
    -- Either both coordinates or neither: half a point is not a location, and
    -- the map would silently drop it.
    ADD CONSTRAINT ck_places_coordinates CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT ck_places_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_places_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180);
