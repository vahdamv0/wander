package com.wander.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The parsing, which is where all the risk in this feature lives.
 *
 * Four upstreams, each with its own idea of a payload, and none of them stable
 * enough to trust from memory — the same reason `NominatimClientTest` exists. No
 * network: these are the recorded shapes, including the awkward ones Commons
 * actually returns.
 */
class WikiEnrichmentClientTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private JsonNode parse(String body) {
        return json.readTree(body);
    }

    @ParameterizedTest
    @CsvSource({
        "node/240109189, N240109189",
        "way/34633854, W34633854",
        "relation/1234, R1234",
    })
    void aReferenceBecomesTheLookupsIdForm(String ref, String expected) {
        assertThat(WikiEnrichmentClient.nominatimId(ref)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({ "''", "node", "node/", "node/abc", "point/1234", "34633854" })
    void anythingElseIsRefusedRatherThanGuessedAt(String ref) {
        // A malformed reference must not become a request; the alternative is
        // asking Nominatim about nonsense on somebody's behalf.
        assertThat(WikiEnrichmentClient.nominatimId(ref)).isNull();
    }

    @Test
    void theCallersWikipediaIsPreferredAndEnglishIsTheFallback() {
        JsonNode entity = parse("""
                {"sitelinks":{
                   "enwiki":{"title":"Fushimi Inari-taisha"},
                   "jawiki":{"title":"伏見稲荷大社"}}}
                """);

        assertThat(WikiEnrichmentClient.sitelinkTitle(entity, "ja")).isEqualTo("伏見稲荷大社");
        assertThat(WikiEnrichmentClient.sitelinkTitle(entity, "en")).isEqualTo("Fushimi Inari-taisha");
        // No Welsh article, so English rather than nothing.
        assertThat(WikiEnrichmentClient.sitelinkTitle(entity, "cy")).isEqualTo("Fushimi Inari-taisha");
    }

    @Test
    void aPlaceWithNoArticleHasNoTitle() {
        assertThat(WikiEnrichmentClient.sitelinkTitle(parse("{\"sitelinks\":{}}"), "en")).isNull();
        assertThat(WikiEnrichmentClient.sitelinkTitle(parse("{}"), "en")).isNull();
        assertThat(WikiEnrichmentClient.sitelinkTitle(null, "en")).isNull();
    }

    @Test
    void theImageClaimIsReadWhenThereIsOne() {
        JsonNode entity = parse("""
                {"claims":{"P18":[{"mainsnak":{"datavalue":{"value":"Fushimi Inari.jpg"}}}]}}
                """);
        assertThat(WikiEnrichmentClient.claimValue(entity, "P18")).isEqualTo("Fushimi Inari.jpg");

        // Absent, empty, and present-but-not-a-string all mean "no image".
        assertThat(WikiEnrichmentClient.claimValue(parse("{\"claims\":{}}"), "P18")).isNull();
        assertThat(WikiEnrichmentClient.claimValue(parse("{\"claims\":{\"P18\":[]}}"), "P18")).isNull();
        assertThat(WikiEnrichmentClient.claimValue(
                parse("{\"claims\":{\"P18\":[{\"mainsnak\":{}}]}}"), "P18")).isNull();
    }

    @Test
    void aCommonsCreditIsReadOutOfExtmetadata() {
        // The realistic shape: Artist is HTML, and the short licence name is the
        // one worth showing.
        List<PlaceFacts.PhotoCandidate> photos = WikiEnrichmentClient.parseCandidates(parse("""
                {"query":{"pages":{"-1":{"imageinfo":[{
                   "url":"https://upload.wikimedia.org/full.jpg",
                   "thumburl":"https://upload.wikimedia.org/thumb.jpg",
                   "descriptionurl":"https://commons.wikimedia.org/wiki/File:Full.jpg",
                   "extmetadata":{
                     "Artist":{"value":"<a href=\\"/wiki/User:Someone\\">Someone</a>"},
                     "LicenseShortName":{"value":"CC BY-SA 4.0"}}}]}}}}
                """), 4);

        assertThat(photos).singleElement().satisfies(photo -> {
            assertThat(photo.author()).isEqualTo("Someone");
            assertThat(photo.licence()).isEqualTo("CC BY-SA 4.0");
            assertThat(photo.thumbUrl()).endsWith("thumb.jpg");
            assertThat(photo.sourceUrl()).contains("commons.wikimedia.org");
        });
    }

    @Test
    void theLongerLicenceFieldIsUsedWhenTheShortOneIsMissing() {
        List<PlaceFacts.PhotoCandidate> photos = WikiEnrichmentClient.parseCandidates(parse("""
                {"query":{"pages":{"1":{"imageinfo":[{
                   "url":"https://upload.wikimedia.org/a.jpg",
                   "extmetadata":{"Artist":{"value":"A Photographer"},
                                  "License":{"value":"cc-by-sa-3.0"}}}]}}}}
                """), 4);

        assertThat(photos).singleElement()
                .satisfies(photo -> assertThat(photo.licence()).isEqualTo("cc-by-sa-3.0"));
    }

    @Test
    void aPhotoWithNoReadableCreditIsDroppedRatherThanShown() {
        // The compliance case, and the reason this is a test rather than a
        // comment: an image whose author or licence cannot be read is one this
        // project has no right to display, so it must not survive parsing.
        String noArtist = """
                {"query":{"pages":{"1":{"imageinfo":[{
                   "url":"https://upload.wikimedia.org/a.jpg",
                   "extmetadata":{"LicenseShortName":{"value":"CC BY 4.0"}}}]}}}}
                """;
        String noLicence = """
                {"query":{"pages":{"1":{"imageinfo":[{
                   "url":"https://upload.wikimedia.org/a.jpg",
                   "extmetadata":{"Artist":{"value":"Someone"}}}]}}}}
                """;
        String noMetadata = """
                {"query":{"pages":{"1":{"imageinfo":[{
                   "url":"https://upload.wikimedia.org/a.jpg"}]}}}}
                """;

        assertThat(WikiEnrichmentClient.parseCandidates(parse(noArtist), 4)).isEmpty();
        assertThat(WikiEnrichmentClient.parseCandidates(parse(noLicence), 4)).isEmpty();
        assertThat(WikiEnrichmentClient.parseCandidates(parse(noMetadata), 4)).isEmpty();
    }

    @Test
    void nonsenseFromCommonsIsNotAnException() {
        assertThat(WikiEnrichmentClient.parseCandidates(parse("{}"), 4)).isEmpty();
        assertThat(WikiEnrichmentClient.parseCandidates(parse("{\"query\":{}}"), 4)).isEmpty();
        assertThat(WikiEnrichmentClient.parseCandidates(
                parse("{\"query\":{\"pages\":{\"1\":{}}}}"), 4)).isEmpty();
        assertThat(WikiEnrichmentClient.parseCandidates(null, 4)).isEmpty();
    }

    @Test
    void theCandidateLimitIsRespected() {
        StringBuilder images = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            images.append(i == 0 ? "" : ",").append("""
                    {"url":"https://upload.wikimedia.org/%d.jpg",
                     "extmetadata":{"Artist":{"value":"Someone"},
                                    "LicenseShortName":{"value":"CC BY 4.0"}}}
                    """.formatted(i));
        }
        JsonNode root = parse("{\"query\":{\"pages\":{\"1\":{\"imageinfo\":[" + images + "]}}}}");

        assertThat(WikiEnrichmentClient.parseCandidates(root, 2)).hasSize(2);
    }

    @Test
    void anArtistsNameIsPulledOutOfWhateverMarkupItArrivedIn() {
        assertThat(WikiEnrichmentClient.plainText("<a href=\"/wiki/User:X\">Jane Doe</a>"))
                .isEqualTo("Jane Doe");
        assertThat(WikiEnrichmentClient.plainText("<span>A &amp; B</span>")).isEqualTo("A & B");
        assertThat(WikiEnrichmentClient.plainText("  spaced   out  ")).isEqualTo("spaced out");
        assertThat(WikiEnrichmentClient.plainText("<span></span>")).isNull();
        assertThat(WikiEnrichmentClient.plainText(null)).isNull();
    }
}
