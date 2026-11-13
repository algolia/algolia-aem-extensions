package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderSettings;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers both coordinate paths of the geo-location extractor: stored coordinates
 * and the geocoding fallback, plus the response parsers and endpoint guards.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultAlgoliaGeoLocationExtractorTest {

    private HttpClient httpClient;
    private CryptoSupport cryptoSupport;
    private DefaultAlgoliaGeoLocationExtractor extractor;

    @BeforeEach
    void setUp() {
        this.httpClient = mock(HttpClient.class);
        this.cryptoSupport = mock(CryptoSupport.class);
        this.extractor = new DefaultAlgoliaGeoLocationExtractor(new StubConfig(), this.httpClient,
                this.cryptoSupport);
    }

    // ------------------------------------------------------- stored coordinates

    @Test
    void writesGeolocFromContentFragmentVariation() {
        assertEquals(40.7128, resolve(fragment(Map.of("latitude", 40.7128, "longitude", -74.006)))
                .lat());
        assertEquals(-74.006, resolve(fragment(Map.of("latitude", 40.7128, "longitude", -74.006)))
                .lng());
    }

    @Test
    void acceptsCoordinatesStoredAsStrings() {
        final DefaultAlgoliaGeoLocationExtractor.Coordinates coordinates =
                resolve(fragment(Map.of("latitude", "37.7749", "longitude", "-122.4194")));

        assertEquals(37.7749, coordinates.lat());
        assertEquals(-122.4194, coordinates.lng());
    }

    @Test
    void honoursAuthorRenamedCoordinateProperties() {
        final Resource resource = fragment(Map.of("geoLat", 42.3601, "geoLng", -71.0589));
        final DefaultAlgoliaGeoLocationExtractor.Coordinates coordinates =
                this.extractor.resolveCoordinates(resource, newRecord(), settings(Map.of(
                        "latitudeProperty", "geoLat", "longitudeProperty", "geoLng")));

        assertEquals(42.3601, coordinates.lat());
        assertEquals(-71.0589, coordinates.lng());
    }

    @Test
    void outOfRangeCoordinatesFallThroughToTheLookup() {
        final Resource resource = fragmentWithAddress(
                Map.of("latitude", 200.0, "longitude", -74.006), "Seattle, WA");

        // 200 is not a latitude, so the address path takes over and, with no
        // network available in a unit test, resolves nothing.
        assertNull(this.extractor.resolveCoordinates(resource, newRecord(), settings(Map.of())));
    }

    // ------------------------------------------------------- record fallback

    @Test
    void readsCoordinatesFromTheRecordWhenNotStoredOnJcr() {
        final AlgoliaRecord record = new AlgoliaRecord("/content/site/en/page");
        record.addAttribute("latitude", 47.6062);
        record.addAttribute("longitude", -122.3321);

        final DefaultAlgoliaGeoLocationExtractor.Coordinates coordinates =
                this.extractor.resolveCoordinates(plainResource(Map.of()), record, settings(Map.of()));

        assertEquals(47.6062, coordinates.lat());
        assertEquals(-122.3321, coordinates.lng());
    }

    // ------------------------------------------------------- address composition

    @Test
    void joinsAddressPropertiesInConfiguredOrderSkippingBlanks() {
        final Resource resource = fragmentWithAddress(Map.of("city", "Boston", "country", "USA"), null);

        assertEquals("Boston, USA", this.extractor.buildAddress(resource, newRecord(),
                settings(Map.of("addressProperties", "street, city ,state,postalCode,country"))));
    }

    @Test
    void blankAddressStopsTheLookup() {
        assertTrue(this.extractor.buildAddress(fragment(Map.of()), newRecord(),
                settings(Map.of("addressProperties", "street,city"))).isEmpty());
    }

    // ------------------------------------------------------- response parsing

    @Test
    void parsesNominatimResponse() {
        final DefaultAlgoliaGeoLocationExtractor.Coordinates coordinates =
                DefaultAlgoliaGeoLocationExtractor.parse(
                        "[{\"lat\":\"40.7127281\",\"lon\":\"-74.0060152\"}]", "nominatim");

        assertEquals(40.7127281, coordinates.lat());
        assertEquals(-74.0060152, coordinates.lng());
    }

    @Test
    void parsesGoogleResponse() {
        final DefaultAlgoliaGeoLocationExtractor.Coordinates coordinates =
                DefaultAlgoliaGeoLocationExtractor.parse(
                        "{\"results\":[{\"geometry\":{\"location\":{\"lat\":47.6062,\"lng\":-122.3321}}}]}",
                        "google");

        assertEquals(47.6062, coordinates.lat());
        assertEquals(-122.3321, coordinates.lng());
    }

    @Test
    void parsesCensusResponse() {
        final DefaultAlgoliaGeoLocationExtractor.Coordinates coordinates =
                DefaultAlgoliaGeoLocationExtractor.parse(
                        "{\"result\":{\"addressMatches\":[{\"coordinates\":{\"lat\":37.77,\"lng\":-122.42}}]}}",
                        "census");

        assertEquals(37.77, coordinates.lat());
        assertEquals(-122.42, coordinates.lng());
    }

    @Test
    void returnsNullWhenProviderFoundNothing() {
        assertNull(DefaultAlgoliaGeoLocationExtractor.parse("[]", "nominatim"));
        assertNull(DefaultAlgoliaGeoLocationExtractor.parse("[]", "google"));
        assertNull(DefaultAlgoliaGeoLocationExtractor.parse("{\"result\":{\"addressMatches\":[]}}", "census"));
        assertNull(DefaultAlgoliaGeoLocationExtractor.parse("not json", "nominatim"));
    }

    @Test
    void rejectsOutOfRangeCoordinatesFromProvider() {
        assertNull(DefaultAlgoliaGeoLocationExtractor.parse(
                "[{\"lat\":\"999.0\",\"lon\":\"-74.0\"}]", "nominatim"));
    }

    // ------------------------------------------------------- endpoint guards

    @Test
    void refusesAPlainHttpEndpoint() {
        this.extractor.clearCache();
        assertNull(this.extractor.lookup("Boston", "", settings(Map.of(
                "geocodeEndpoint", "http://nominatim.openstreetmap.org/search"))));
    }

    @Test
    void refusesAHostOutsideTheAllowList() {
        this.extractor.clearCache();
        assertNull(this.extractor.lookup("Boston", "", settings(Map.of(
                "geocodeEndpoint", "https://evil.example.com/search"))));
    }

    @Test
    void refusesALocalEndpointEvenWhenListed() {
        this.extractor.clearCache();
        assertNull(this.extractor.lookup("Boston", "", settings(Map.of(
                "geocodeEndpoint", "https://localhost:8443/search"))));
    }

    // ------------------------------------------------------- helpers

    private DefaultAlgoliaGeoLocationExtractor.Coordinates resolve(Resource resource) {
        return this.extractor.resolveCoordinates(resource, newRecord(), settings(Map.of()));
    }

    private static AlgoliaRecord newRecord() {
        return new AlgoliaRecord("/content/dam/site/advisors/jane");
    }

    /** No declared fields, so every lookup falls back to the OSGi default. */
    private static ExtenderSettings settings(Map<String, String> values) {
        return new ExtenderSettings(values, List.of());
    }

    /**
     * Real values, not a mock: a mocked annotation returns null and zero instead of
     * the defaults declared on the annotation, which would hide parsing bugs.
     */
    private static final class StubConfig implements DefaultAlgoliaGeoLocationExtractor.Config {

        public String latitudeProperty() { return "latitude"; }
        public String longitudeProperty() { return "longitude"; }
        public String geocode_endpoint() { return "https://nominatim.openstreetmap.org/search"; }
        public String response_format() { return "nominatim"; }
        public String query_parameter() { return "q"; }
        public String extra_query() { return "format=jsonv2" + "&" + "limit=1"; }
        public String api_key_parameter() { return ""; }
        public String allowed_hosts() { return "nominatim.openstreetmap.org"; }
        public int timeout_ms() { return 1000; }
        public long min_interval_ms() { return 0L; }
        public int cache_ttl_minutes() { return 60; }
        public String user_agent() { return "test"; }
        public boolean write_flat_attributes() { return false; }

        @Override
        public Class<? extends java.lang.annotation.Annotation> annotationType() {
            return DefaultAlgoliaGeoLocationExtractor.Config.class;
        }
    }

    /** A content fragment whose coordinates live on jcr:content/data/master. */
    private static Resource fragment(Map<String, Object> masterValues) {
        return fragmentWithAddress(masterValues, null);
    }

    private static Resource fragmentWithAddress(Map<String, Object> masterValues, String city) {
        final Map<String, Object> master = new HashMap<>(masterValues);
        if (city != null) {
            master.put("city", city);
        }

        final ValueMap masterMap = valueMap(master);
        final ValueMap emptyMap = valueMap(Map.of());

        final Resource variation = mock(Resource.class);
        when(variation.getValueMap()).thenReturn(masterMap);

        final Resource data = mock(Resource.class);
        when(data.listChildren()).thenReturn(new Children(variation));

        final Resource asset = mock(Resource.class);
        when(asset.getChild("jcr:content/data")).thenReturn(data);
        when(asset.getChild("jcr:content/metadata")).thenReturn(null);
        when(asset.getValueMap()).thenReturn(emptyMap);
        when(asset.getPath()).thenReturn("/content/dam/site/advisors/jane");
        return asset;
    }

    private static Resource plainResource(Map<String, Object> values) {
        final ValueMap map = valueMap(values);
        final Resource resource = mock(Resource.class);
        when(resource.getChild("jcr:content/data")).thenReturn(null);
        when(resource.getChild("jcr:content/metadata")).thenReturn(null);
        when(resource.getValueMap()).thenReturn(map);
        when(resource.getPath()).thenReturn("/content/site/en/page");
        return resource;
    }

    private static ValueMap valueMap(Map<String, Object> values) {
        final ValueMap map = mock(ValueMap.class);
        values.forEach((key, value) -> when(map.get(key)).thenReturn(value));
        return map;
    }

    /** Iterator over a fixed set of child resources. */
    private static final class Children implements Iterator<Resource> {

        private final List<Resource> items = new ArrayList<>();
        private int cursor;

        Children(Resource... resources) {
            this.items.addAll(List.of(resources));
        }

        @Override
        public boolean hasNext() {
            return this.cursor < this.items.size();
        }

        @Override
        public Resource next() {
            return this.items.get(this.cursor++);
        }
    }
}
