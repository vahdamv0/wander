package com.wander.geo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wander.config.WanderProperties;
import com.wander.geo.dto.PlaceSuggestion;

import tools.jackson.databind.json.JsonMapper;

/**
 * The mapping from Nominatim's payload to this project's contract, tested
 * without an HTTP round trip — the interesting cases are all shapes of response
 * rather than shapes of request.
 *
 * The payloads below are trimmed real `jsonv2` responses. Nominatim sends
 * coordinates as strings and omits `name` for anything that is not a named
 * feature, both of which are easy to get wrong and impossible to notice until a
 * suggestion silently has no pin.
 */
class NominatimClientTest {

    private final NominatimClient client = new NominatimClient(properties(), JsonMapper.builder().build());

    private static WanderProperties properties() {
        return new WanderProperties("test", "", true, "EUR", new WanderProperties.Admin("", ""),
                new WanderProperties.Login(10, 40, 15, 10000),
                new WanderProperties.Geocoding(true, "http://localhost:1", "", "en", 1000, 2000, 600, 500),
                new WanderProperties.Enrichment(true, "http://localhost:1", "http://localhost:1",
                        30, 4, 200, 4000),
                new WanderProperties.Weather(true, "http://localhost:1", 16, 180, "test",
                        "http://localhost:1", 200, 4000),
                new WanderProperties.MapTiles(true, "", "", "http://localhost:1/{z}/{x}/{y}.png", "test", 19));
    }

    @Test
    void mapsANamedFeature() {
        List<PlaceSuggestion> hits = client.parse("""
                [{
                  "place_id": 297166856,
                  "osm_type": "way",
                  "osm_id": 34633854,
                  "lat": "41.4034984",
                  "lon": "2.1744573",
                  "category": "building",
                  "type": "church",
                  "name": "Basílica de la Sagrada Família",
                  "display_name": "Basílica de la Sagrada Família, Carrer de Mallorca, Barcelona, Spain"
                }]
                """);

        assertThat(hits).hasSize(1);
        PlaceSuggestion hit = hits.getFirst();
        assertThat(hit.ref()).isEqualTo("way/34633854");
        assertThat(hit.name()).isEqualTo("Basílica de la Sagrada Família");
        assertThat(hit.address()).startsWith("Basílica de la Sagrada Família, Carrer de Mallorca");
        assertThat(hit.category()).isEqualTo("church");
        // Strings upstream, numbers in our contract.
        assertThat(hit.latitude()).isEqualTo(41.4034984);
        assertThat(hit.longitude()).isEqualTo(2.1744573);
    }

    @Test
    void fallsBackToTheFirstSegmentWhenAHitHasNoName() {
        List<PlaceSuggestion> hits = client.parse("""
                [{
                  "osm_type": "node",
                  "osm_id": 1,
                  "lat": "41.38",
                  "lon": "2.17",
                  "display_name": "12, Carrer de Mallorca, Barcelona, Spain"
                }]
                """);

        // An address has no short name, and "12" alone is what the user recognises.
        assertThat(hits).singleElement().extracting(PlaceSuggestion::name).isEqualTo("12");
    }

    @Test
    void skipsHitsThatCouldNotBeShownOnAMap() {
        List<PlaceSuggestion> hits = client.parse("""
                [
                  {"osm_type":"node","osm_id":1,"lon":"2.17","display_name":"No latitude at all"},
                  {"osm_type":"node","osm_id":2,"lat":"not-a-number","lon":"2.17","display_name":"Nonsense latitude"},
                  {"osm_type":"node","osm_id":3,"lat":"41.38","lon":"2.17"},
                  {"osm_type":"node","osm_id":4,"lat":"41.38","lon":"2.17","display_name":"Barcelona, Spain"}
                ]
                """);

        // Only the last one is usable: a suggestion with no point cannot be saved
        // as a location or drawn, so it is dropped rather than shown as a dud.
        assertThat(hits).singleElement().extracting(PlaceSuggestion::address).isEqualTo("Barcelona, Spain");
    }

    @Test
    void fallsBackToCoordinatesWhenThereIsNoOsmReference() {
        List<PlaceSuggestion> hits = client.parse("""
                [{"lat":"41.38","lon":"2.17","display_name":"Somewhere","name":"Somewhere"}]
                """);

        // The ref only has to be stable enough to key a list on.
        assertThat(hits).singleElement().extracting(PlaceSuggestion::ref).isEqualTo("41.38,2.17");
    }

    @Test
    void anEmptyOrAbsentBodyIsNoResults() {
        assertThat(client.parse("[]")).isEmpty();
        assertThat(client.parse("")).isEmpty();
        assertThat(client.parse(null)).isEmpty();
    }
}
