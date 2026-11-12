package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.extender.AlgoliaPageRequestExtender;
import com.algolia.connector.core.extender.ConfigurableRequestExtender;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds Algolia's {@code _geoloc} attribute to a record so the index can rank and
 * filter by distance.
 *
 * <p>Algolia requires a nested {@code _geoloc} object holding numeric {@code lat}
 * and {@code lng}. The indexer writes only whitelisted JCR properties, and each
 * attribute it writes is a flat value, so no combination of Index Attributes can
 * produce that object. This extension closes that gap for any site: an author
 * selects it on the Cloud Services configuration and names the properties that
 * hold the coordinates or the address.
 *
 * <p>Two resolution paths, tried in order:
 * <ol>
 *   <li><b>Coordinates</b> — read the configured latitude and longitude
 *       properties. Used by SW Financial, whose advisor fragments already store
 *       both.</li>
 *   <li><b>Geocoding</b> — join the configured address properties into one line,
 *       look it up with the configured provider, and use the returned
 *       coordinates. Used by sites such as SW Healthcare whose fragments carry
 *       an address but no coordinates.</li>
 * </ol>
 *
 * <p>Lookups are cached in memory per address string for {@code cacheTtlMinutes}
 * so a reindex of an unchanged site makes no network calls. Failures are
 * non-fatal: the resource still indexes, without {@code _geoloc}.
 *
 * @since 1.3.0
 */
@ComponentServiceProperties(description = "Algolia Geo-Location Extractor")
@Designate(ocd = DefaultAlgoliaGeoLocationExtractor.Config.class)
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class
        }
)
public class DefaultAlgoliaGeoLocationExtractor
        implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender, ConfigurableRequestExtender {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** Attribute Algolia reads for geo ranking and geo filtering. */
    static final String ATTR_GEOLOC = "_geoloc";

    private static final String ATTR_LAT = "geolatitude";
    private static final String ATTR_LNG = "geolongitude";

    private static final String CF_DATA_NODE = "jcr:content/data";
    private static final String CF_METADATA_NODE = "jcr:content/metadata";

    /** Providers whose response shape this class understands. */
    static final String PROVIDER_NOMINATIM = "nominatim";
    static final String PROVIDER_MAPS_CO = "mapscopy";
    static final String PROVIDER_GOOGLE = "google";
    static final String PROVIDER_CENSUS = "census";

    private static final String DEFAULT_ENDPOINT = "https://nominatim.openstreetmap.org/search";
    private static final String DEFAULT_ALLOWED_HOSTS =
            "nominatim.openstreetmap.org,geocode.maps.co,maps.googleapis.com,geocoding.geo.census.gov";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Config config;
    private final HttpClient httpClient;
    private final CryptoSupport cryptoSupport;
    private final Map<String, CachedLookup> cache = new ConcurrentHashMap<>();
    private final Object rateLimitMonitor = new Object();

    /** Timestamp of the last outbound lookup; guarded by {@code rateLimitMonitor}. */
    private long lastLookupAt;

    /** One address's resolved coordinates, with the time it was looked up. */
    private record CachedLookup(Double lat, Double lng, long expiresAt) {
    }

    @ObjectClassDefinition(name = "Algolia Geo-Location Extractor",
            description = "Adds the _geoloc attribute from coordinate properties or an address geocoding lookup.")
    @interface Config {

        @AttributeDefinition(name = "Latitude Property",
                description = "JCR property holding the latitude. Content fragments are read from their "
                        + "variation node, pages from jcr:content, and assets from jcr:content/metadata.")
        String latitudeProperty() default "latitude";

        @AttributeDefinition(name = "Longitude Property",
                description = "JCR property holding the longitude.")
        String longitudeProperty() default "longitude";

        @AttributeDefinition(name = "Geocoding Endpoint",
                description = "GET endpoint used when no coordinates are stored. Must be https and its host "
                        + "must appear in Allowed Hosts.")
        String geocode_endpoint() default DEFAULT_ENDPOINT;

        @AttributeDefinition(name = "Response Format",
                description = "nominatim: [{lat,lon}]. maps.co: same shape. google: "
                        + "results[0].geometry.location.{lat,lng}. census: "
                        + "result.addressMatches[0].coordinates.{lat,lng}.")
        String response_format() default PROVIDER_NOMINATIM;

        @AttributeDefinition(name = "Query Parameter",
                description = "Name of the query-string parameter carrying the address.")
        String query_parameter() default "q";

        @AttributeDefinition(name = "Extra Query Parameters",
                description = "Additional query parameters as key=value pairs, comma-separated, "
                        + "for example format=jsonv2&limit=1.")
        String extra_query() default "format=jsonv2&limit=1";

        @AttributeDefinition(name = "API Key Query Parameter",
                description = "Query parameter the provider expects the key in, such as apiKey or key. "
                        + "Leave blank when the provider needs none.")
        String api_key_parameter() default "";

        @AttributeDefinition(name = "Allowed Hosts",
                description = "Hosts the geocoder may call, comma-separated. Supports a leading *.",
                defaultValue = DEFAULT_ALLOWED_HOSTS)
        String allowed_hosts() default DEFAULT_ALLOWED_HOSTS;

        @AttributeDefinition(name = "Timeout (ms)", description = "Connect and request timeout.",
                defaultValue = "8000")
        int timeout_ms() default 8000;

        @AttributeDefinition(name = "Minimum Interval Between Lookups (ms)",
                description = "Public geocoders rate-limit per second. This paces calls across indexing "
                        + "threads; 1100 suits nominatim's 1 request per second. 0 disables pacing.",
                defaultValue = "1100")
        long min_interval_ms() default 1100L;

        @AttributeDefinition(name = "Cache TTL (minutes)",
                description = "How long a resolved address is reused in memory. 0 disables caching.",
                defaultValue = "1440")
        int cache_ttl_minutes() default 1440;

        @AttributeDefinition(name = "User-Agent",
                description = "Sent on every lookup. Public geocoders reject generic agents.",
                defaultValue = "algolia-aem-extensions/1.3")
        String user_agent() default "algolia-aem-extensions/1.3";

        @AttributeDefinition(name = "Write Flat Coordinate Attributes",
                description = "Also write geolatitude and geolongitude as flat numbers, so hit templates "
                        + "can show them without reading _geoloc.")
        boolean write_flat_attributes() default false;
    }

    @Activate
    public DefaultAlgoliaGeoLocationExtractor(@Reference CryptoSupport cryptoSupport, Config config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, config.timeout_ms())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), cryptoSupport);
    }

    /** Test seam: supply a client that returns canned responses. */
    DefaultAlgoliaGeoLocationExtractor(Config config, HttpClient httpClient, CryptoSupport cryptoSupport) {
        this.config = config;
        this.httpClient = httpClient;
        this.cryptoSupport = cryptoSupport;
    }

    @Override
    public String getSettingsDescription() {
        return "Writes _geoloc from coordinate properties, or by geocoding an address built from "
                + "content properties.";
    }

    @Override
    public List<ExtenderConfigField> getConfigFields() {
        return List.of(
                ExtenderConfigField.builder("latitudeProperty", "Latitude Property")
                        .defaultValue(this.config.latitudeProperty())
                        .helpText("Content property holding the latitude. Used when it is present and "
                                + "numeric, so no lookup is made. Default latitude.")
                        .build(),
                ExtenderConfigField.builder("longitudeProperty", "Longitude Property")
                        .defaultValue(this.config.longitudeProperty())
                        .helpText("Content property holding the longitude. Default longitude.")
                        .build(),
                ExtenderConfigField.builder("addressProperties", "Address Properties")
                        .defaultValue("address,street,city,state,postalCode,country")
                        .helpText("Comma-separated properties joined into the geocoding query, in this "
                                + "order. Values that are missing or blank are skipped. Tried only when "
                                + "the coordinate properties above did not yield a pair.")
                        .build(),
                ExtenderConfigField.builder("responseFormat", "Response Format")
                        .type(ExtenderConfigField.Type.SELECT)
                        .defaultValue(this.config.response_format())
                        .options(PROVIDER_NOMINATIM, PROVIDER_MAPS_CO, PROVIDER_GOOGLE, PROVIDER_CENSUS)
                        .helpText("How to read the provider response. nominatim and maps.co return "
                                + "[{lat,lon}]; google returns results[0].geometry.location; census "
                                + "returns result.addressMatches[0].coordinates.")
                        .build(),
                ExtenderConfigField.builder("geocodeEndpoint", "Geocoding Endpoint")
                        .defaultValue(this.config.geocode_endpoint())
                        .authorOnly(true)
                        .helpText("GET endpoint for the lookup. Must be https, and its host must be on "
                                + "the bundle's Allowed Hosts list.")
                        .build(),
                ExtenderConfigField.builder("queryParameter", "Query Parameter")
                        .defaultValue(StringUtils.defaultIfBlank(this.config.query_parameter(), "q"))
                        .authorOnly(true)
                        .helpText("Query-string parameter the provider reads the address from.")
                        .build(),
                ExtenderConfigField.builder("extraQuery", "Extra Query Parameters")
                        .defaultValue(StringUtils.trimToEmpty(this.config.extra_query()))
                        .authorOnly(true)
                        .helpText("Raw query parameters appended to every lookup, for example "
                                + "format=jsonv2&limit=1.")
                        .build(),
                ExtenderConfigField.builder("apiKey", "API Key")
                        .type(ExtenderConfigField.Type.PASSWORD)
                        .authorOnly(true)
                        .helpText("Sent as the bundle's API Key Query Parameter. Leave blank for "
                                + "providers that need none, such as nominatim.")
                        .build(),
                ExtenderConfigField.builder("writeFlatAttributes", "Write Flat Coordinate Attributes")
                        .type(ExtenderConfigField.Type.BOOLEAN)
                        .defaultValue(String.valueOf(this.config.write_flat_attributes()))
                        .helpText("Also write geolatitude and geolongitude so hit templates can show "
                                + "coordinates without reading the nested _geoloc object.")
                        .build());
    }

    @Override
    public void augmentAlgoliaRequest(final AlgoliaRequest request, final Page page) {
        augment(request, settingsOf(request));
    }

    @Override
    public void augmentAlgoliaRequest(final AlgoliaRequest request, final Asset asset) {
        augment(request, settingsOf(request));
    }

    // ---------------------------------------------------------------------- resolve

    private void augment(final AlgoliaRequest request, final ExtenderSettings settings) {
        final List<AlgoliaRecord> records = request.getAlgoliaRecords();
        if (records == null || records.isEmpty()) {
            LOGGER.debug("No Algolia record on request for {} — nothing to geo-locate", request.getPath());
            return;
        }
        final Resource resource = request.getResource();
        if (resource == null) {
            return;
        }

        final Coordinates coordinates = resolveCoordinates(resource, records.get(0), settings);
        if (coordinates == null) {
            LOGGER.debug("No coordinates resolved for {} — indexing without {}",
                    request.getPath(), ATTR_GEOLOC);
            return;
        }

        for (final AlgoliaRecord record : records) {
            record.remove(ATTR_GEOLOC);
            record.addAttribute(ATTR_GEOLOC, coordinates.toGeoloc());
            if (settings.getBoolean("writeFlatAttributes", this.config.write_flat_attributes())) {
                record.addAttribute(ATTR_LAT, coordinates.lat());
                record.addAttribute(ATTR_LNG, coordinates.lng());
            }
        }
        LOGGER.debug("Wrote {} {} for {}", ATTR_GEOLOC, coordinates, request.getPath());
    }

    /**
     * Reads stored coordinates, then falls back to geocoding the composed address.
     *
     * @return the coordinates, or null when neither path produced a usable pair
     */
    Coordinates resolveCoordinates(final Resource resource, final AlgoliaRecord record,
                                   final ExtenderSettings settings) {
        final List<ValueMap> sources = valueMaps(resource);

        final String latProperty = settings.getString("latitudeProperty", this.config.latitudeProperty());
        final String lngProperty = settings.getString("longitudeProperty", this.config.longitudeProperty());

        final Double lat = firstNumber(sources, record, latProperty);
        final Double lng = firstNumber(sources, record, lngProperty);
        if (isLatitude(lat) && isLongitude(lng)) {
            return new Coordinates(lat, lng);
        }
        if (lat != null || lng != null) {
            LOGGER.warn("Incomplete coordinates on {} — property {}={}, property {}={}. Falling back to "
                    + "the address lookup.", resource.getPath(), latProperty, lat, lngProperty, lng);
        }

        final String address = buildAddress(resource, record, settings);
        if (StringUtils.isBlank(address)) {
            return null;
        }
        return lookup(address, apiKey(settings), settings);
    }

    /**
     * Decrypts the author's API key. The configuration stores PASSWORD fields as
     * AEM-protected text.
     *
     * @return the key, or an empty string when none is configured
     */
    private String apiKey(final ExtenderSettings settings) {
        final String stored = settings.getString("apiKey");
        if (StringUtils.isBlank(stored) || this.cryptoSupport == null) {
            return StringUtils.EMPTY;
        }
        try {
            return AlgoliaUtil.unprotectText(stored, this.cryptoSupport);
        } catch (RuntimeException ex) {
            LOGGER.error("Could not decrypt the geocoder API key — looking up without it: {}",
                    ex.getMessage());
            return StringUtils.EMPTY;
        }
    }

    /**
     * Value maps that can carry a location property, most specific first: the
     * content fragment variations, the asset metadata, then the resource itself.
     */
    private static List<ValueMap> valueMaps(final Resource resource) {
        final List<ValueMap> maps = new ArrayList<>();
        final Resource data = resource.getChild(CF_DATA_NODE);
        if (data != null) {
            final Iterator<Resource> variations = data.listChildren();
            while (variations.hasNext()) {
                maps.add(variations.next().getValueMap());
            }
        }
        final Resource metadata = resource.getChild(CF_METADATA_NODE);
        if (metadata != null) {
            maps.add(metadata.getValueMap());
        }
        maps.add(resource.getValueMap());
        return maps;
    }

    /**
     * Reads the property as a number from each source, then from the record.
     *
     * <p>The record is checked last because the indexer lowercases attribute
     * names, and it holds the value only when the author whitelisted the
     * property in Index Attributes.
     */
    private static Double firstNumber(final List<ValueMap> sources, final AlgoliaRecord record,
                                      final String property) {
        if (StringUtils.isBlank(property)) {
            return null;
        }
        for (final ValueMap map : sources) {
            final Double value = toDouble(map.get(property));
            if (value != null) {
                return value;
            }
        }
        if (record != null) {
            for (final String key : List.of(property, property.toLowerCase(Locale.ROOT))) {
                if (record.containsKey(key)) {
                    final Double value = toDouble(record.get(key));
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /** Joins the configured address properties into one geocodable line. */
    String buildAddress(final Resource resource, final AlgoliaRecord record, final ExtenderSettings settings) {
        final List<String> properties = settings.getList("addressProperties");
        if (properties.isEmpty()) {
            return StringUtils.EMPTY;
        }
        final List<ValueMap> sources = valueMaps(resource);
        final List<String> parts = new ArrayList<>();
        for (final String property : properties) {
            final String value = firstText(sources, record, property.trim());
            if (StringUtils.isNotBlank(value) && !containsAny(parts, value)) {
                parts.add(value.trim());
            }
        }
        return String.join(", ", parts);
    }

    private static String firstText(final List<ValueMap> sources, final AlgoliaRecord record,
                                    final String property) {
        if (StringUtils.isBlank(property)) {
            return null;
        }
        for (final ValueMap map : sources) {
            final Object value = map.get(property);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        if (record != null) {
            for (final String key : List.of(property, property.toLowerCase(Locale.ROOT))) {
                final Object value = record.get(key);
                if (value instanceof String text && StringUtils.isNotBlank(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private static boolean containsAny(final List<String> parts, final String value) {
        return parts.stream().anyMatch(part -> part.equalsIgnoreCase(value.trim()));
    }

    // ---------------------------------------------------------------------- geocoder

    /**
     * Looks an address up, reusing a cached answer when it is still fresh.
     *
     * @return the coordinates, or null when the lookup failed or found nothing
     */
    Coordinates lookup(final String address, final String apiKey, final ExtenderSettings settings) {
        final String format = StringUtils.defaultIfBlank(
                settings.getString("responseFormat"), this.config.response_format());
        final String key = lookupKey(format, address);
        final CachedLookup cached = this.cache.get(key);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return new Coordinates(cached.lat(), cached.lng());
        }

        final URI uri;
        try {
            uri = buildUri(address, apiKey, settings);
        } catch (IllegalArgumentException ex) {
            LOGGER.error("Geocoding endpoint rejected — {}: {}", address, ex.getMessage());
            return null;
        }

        awaitRateLimit();

        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.max(500, this.config.timeout_ms())))
                .header("Accept", "application/json")
                .header("User-Agent", StringUtils.defaultIfBlank(this.config.user_agent(),
                        "algolia-aem-extensions/1.3"))
                .GET();

        try {
            final HttpResponse<String> response =
                    this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.error("Geocoding lookup failed for {} — HTTP {}", address, response.statusCode());
                return null;
            }
            final Coordinates coordinates = parse(response.body(), format);
            if (coordinates == null) {
                LOGGER.warn("Geocoding returned no usable coordinates for {}", address);
                return null;
            }
            remember(key, coordinates);            return coordinates;
        } catch (IOException ex) {
            LOGGER.error("Geocoding lookup could not complete for {} — {}", address, ex.getMessage());
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Geocoding lookup interrupted for {}", address);
            return null;
        }
    }

    private void remember(final String key, final Coordinates coordinates) {
        final int ttlMinutes = this.config.cache_ttl_minutes();
        if (ttlMinutes <= 0) {
            return;
        }
        this.cache.put(key, new CachedLookup(coordinates.lat(), coordinates.lng(),
                System.currentTimeMillis() + Duration.ofMinutes(ttlMinutes).toMillis()));
    }

    /** Blocks so calls stay at least {@code min_interval_ms} apart. */
    private void awaitRateLimit() {
        final long interval = this.config.min_interval_ms();
        if (interval <= 0) {
            return;
        }
        synchronized (this.rateLimitMonitor) {
            final long now = System.currentTimeMillis();
            final long since = now - this.lastLookupAt;
            if (since < interval) {
                try {
                    Thread.sleep(interval - since);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            this.lastLookupAt = System.currentTimeMillis();
        }
    }

    /** Clears the in-memory address cache. */
    public void clearCache() {
        this.cache.clear();
    }

    /**
     * Cache key: two configurations that read the same address with different
     * providers must not share one entry.
     */
    private static String lookupKey(final String format, final String address) {
        return format.toLowerCase(Locale.ROOT) + "|" + address.toLowerCase(Locale.ROOT);
    }

    private URI buildUri(final String address, final String apiKey, final ExtenderSettings settings) {
        final String endpoint = StringUtils.defaultIfBlank(
                settings.getString("geocodeEndpoint"), this.config.geocode_endpoint());
        final URI base;
        try {
            base = URI.create(endpoint.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("malformed endpoint " + endpoint, ex);
        }
        if (!"https".equalsIgnoreCase(base.getScheme())) {
            throw new IllegalArgumentException("endpoint must use https: " + endpoint);
        }
        assertHostAllowed(base.getHost());

        final String parameter = StringUtils.defaultIfBlank(
                settings.getString("queryParameter"), this.config.query_parameter());
        final StringBuilder query = new StringBuilder()
                .append(encode(parameter)).append('=').append(encode(address));

        final String extra = StringUtils.defaultIfBlank(
                settings.getString("extraQuery"), StringUtils.trimToEmpty(this.config.extra_query()));
        if (StringUtils.isNotBlank(extra)) {
            query.append('&').append(extra);
        }
        final String keyParameter = StringUtils.trimToEmpty(this.config.api_key_parameter());
        if (StringUtils.isNotBlank(keyParameter) && StringUtils.isNotBlank(apiKey)) {
            query.append('&').append(encode(keyParameter)).append('=').append(encode(apiKey));
        }
        return URI.create(base + (base.getRawQuery() == null ? "?" : "&") + query);
    }

    private void assertHostAllowed(final String host) {
        if (StringUtils.isBlank(host)) {
            throw new IllegalArgumentException("endpoint has no host");
        }
        final String lower = host.toLowerCase(Locale.ROOT);
        if (isLocalHost(lower)) {
            throw new IllegalArgumentException("refusing to call a local address: " + host);
        }
        final List<String> patterns = Arrays.stream(StringUtils.split(
                        StringUtils.defaultIfBlank(this.config.allowed_hosts(), DEFAULT_ALLOWED_HOSTS), ','))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(StringUtils::isNotBlank)
                .toList();
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("Allowed Hosts is empty, so no lookup is permitted");
        }
        final boolean allowed = patterns.stream().anyMatch(pattern -> matches(pattern, lower));
        if (!allowed) {
            throw new IllegalArgumentException("host " + host + " is not on the Allowed Hosts list");
        }
    }

    private static boolean matches(final String pattern, final String host) {
        if (pattern.startsWith("*.")) {
            final String suffix = pattern.substring(1); // ".example.com"
            return host.endsWith(suffix) || host.equals(pattern.substring(2));
        }
        return pattern.equals(host);
    }

    private static boolean isLocalHost(final String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0.0.0.0") || host.startsWith("169.254.") || host.startsWith("192.168.")
                || host.startsWith("10.") || host.endsWith(".internal") || host.endsWith(".local");
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Reads coordinates out of a provider response.
     *
     * @param format one of the {@code PROVIDER_*} names
     * @return the coordinates, or null when the body holds no valid pair
     */
    static Coordinates parse(final String body, final String format) {
        if (StringUtils.isBlank(body)) {
            return null;
        }
        final JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException ex) {
            LOGGER.debug("Geocoder response is not JSON: {}", ex.getMessage());
            return null;
        }
        final JsonNode node = switch (StringUtils.trimToEmpty(format).toLowerCase(Locale.ROOT)) {
            case PROVIDER_GOOGLE -> root.path("results").path(0).path("geometry").path("location");
            case PROVIDER_CENSUS -> root.path("result").path("addressMatches").path(0).path("coordinates");
            // nominatim and maps.co both return an array of {lat, lon}
            default -> root.isArray() ? root.path(0) : root;
        };
        final Double lat = toDouble(node.path("lat").asText(null));
        Double lng = toDouble(node.path("lng").asText(null));
        if (lng == null) {
            lng = toDouble(node.path("lon").asText(null));
        }
        if (isLatitude(lat) && isLongitude(lng)) {
            return new Coordinates(lat, lng);
        }
        return null;
    }

    // ---------------------------------------------------------------------- values

    /** Accepts a number or a numeric string; JSON providers quote their values. */
    static Double toDouble(final Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        final String text = StringUtils.trimToNull(String.valueOf(value));
        if (text == null) {
            return null;
        }
        try {
            return new BigDecimal(text).doubleValue();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static boolean isLatitude(final Double value) {
        return value != null && value >= -90 && value <= 90;
    }

    static boolean isLongitude(final Double value) {
        return value != null && value >= -180 && value <= 180;
    }

    /** A validated coordinate pair, written to Algolia with 4-decimal precision. */
    record Coordinates(double lat, double lng) {

        Map<String, Object> toGeoloc() {
            final Map<String, Object> geoloc = new LinkedHashMap<>();
            geoloc.put("lat", lat);
            geoloc.put("lng", lng);
            return geoloc;
        }
    }

    private static ExtenderSettings settingsOf(final AlgoliaRequest request) {
        final ExtenderSettings settings = request.getExtenderSettings();
        return settings != null ? settings : ExtenderSettings.empty();
    }
}
