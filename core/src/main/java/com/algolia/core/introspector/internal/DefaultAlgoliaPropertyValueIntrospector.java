package com.algolia.core.introspector.internal;

import com.algolia.connector.core.AlgoliaResourceIntrospector;
import com.algolia.connector.core.ConfigurableResourceIntrospector;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import static com.algolia.connector.core.AlgoliaResourceIntrospector.Outcome.ABORT;
import static com.algolia.connector.core.AlgoliaResourceIntrospector.Outcome.CONTINUE;

/**
 * Aborts indexing when a JCR property equals a configured string, integer, or boolean.
 * <p>
 * A bare property name is resolved on the resource, then {@code jcr:content}, asset metadata,
 * and content fragment master data. A relative path such as {@code jcr:content/cq:robotsTags}
 * is read from that node. Indexing continues when the property is missing or the values differ.
 * <p>
 * Settings — Property Name, Value Type, and Expected Value — are configured per Cloud Service
 * index on the <strong>Advanced → Extensions → Introspector Settings</strong> card. When the
 * settings are absent or invalid the introspector never aborts indexing.
 */
@ComponentServiceProperties(description = "Algolia Property Value Introspector")
@Component(service = AlgoliaResourceIntrospector.class)
public class DefaultAlgoliaPropertyValueIntrospector implements AlgoliaResourceIntrospector,
        ConfigurableResourceIntrospector {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Object ABSENT = new Object();

    private static final String[] CONTENT_PATHS = {
            "jcr:content",
            "jcr:content/metadata",
            "jcr:content/data/master"
    };

    // -------------------------------------------------------------------------
    // ConfigurableResourceIntrospector
    // -------------------------------------------------------------------------

    @Override
    public String getSettingsDescription() {
        return "Aborts indexing when a JCR property equals the configured string, integer, or boolean.";
    }

    @Override
    public List<ExtenderConfigField> getConfigFields() {
        return List.of(
                ExtenderConfigField.builder("propertyName", "Property Name")
                        .helpText("JCR property to compare. A bare name is resolved on the resource, "
                                + "jcr:content, jcr:content/metadata, and jcr:content/data/master. "
                                + "A relative path such as jcr:content/cq:robotsTags is read from that node.")
                        .build(),
                ExtenderConfigField.builder("valueType", "Value Type")
                        .type(ExtenderConfigField.Type.SELECT)
                        .options("String", "Integer", "Boolean")
                        .defaultValue("String")
                        .helpText("How to interpret the expected value and the stored property.")
                        .build(),
                ExtenderConfigField.builder("expectedValue", "Expected Value")
                        .helpText("Indexing is aborted when the property equals this value. "
                                + "Integers are whole numbers. Booleans are true or false.")
                        .build()
        );
    }

    // -------------------------------------------------------------------------
    // AlgoliaResourceIntrospector
    // -------------------------------------------------------------------------

    @Override
    public Outcome introspect(Resource resource) {
        return this.introspect(resource, ExtenderSettings.empty());
    }

    @Override
    public Outcome introspect(Resource resource, ExtenderSettings settings) {
        if (settings == null) {
            settings = ExtenderSettings.empty();
        }
        String propertyName = settings.getString("propertyName", "").trim();
        String valueTypeRaw = settings.getString("valueType", "String");
        String expectedValue = settings.getString("expectedValue", "").trim();

        ValueType valueType = ValueType.from(valueTypeRaw);
        String invalid = invalidReason(propertyName, valueType, expectedValue);
        if (invalid != null) {
            LOGGER.debug("Property value introspector will not abort indexing: {}.", invalid);
            return CONTINUE;
        }
        if (resource == null) {
            return CONTINUE;
        }

        long expectedInteger = valueType == ValueType.INTEGER ? Long.parseLong(expectedValue) : 0L;
        boolean expectedBoolean = valueType == ValueType.BOOLEAN && Boolean.parseBoolean(expectedValue);

        Object actual = readProperty(resource, propertyName);
        if (actual == ABSENT || !matches(actual, valueType, expectedValue, expectedInteger, expectedBoolean)) {
            return CONTINUE;
        }
        LOGGER.info("Aborting indexation of resource({}) because {} property '{}' equals '{}'.",
                resource.getPath(), valueType.name().toLowerCase(Locale.ROOT), propertyName, expectedValue);
        return ABORT;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static Object readProperty(Resource resource, String propertyName) {
        int slash = propertyName.lastIndexOf('/');
        if (slash >= 0) {
            String parent = propertyName.substring(0, slash);
            String name = propertyName.substring(slash + 1);
            return valueOf(resource.getChild(parent), name);
        }
        Object direct = valueOf(resource, propertyName);
        if (direct != ABSENT) {
            return direct;
        }
        for (String path : CONTENT_PATHS) {
            Object value = valueOf(resource.getChild(path), propertyName);
            if (value != ABSENT) {
                return value;
            }
        }
        return ABSENT;
    }

    private static boolean matches(Object actual, ValueType valueType, String expectedValue,
                                   long expectedInteger, boolean expectedBoolean) {
        if (actual != null && actual.getClass().isArray()) {
            int length = Array.getLength(actual);
            for (int i = 0; i < length; i++) {
                if (matchesScalar(Array.get(actual, i), valueType, expectedValue, expectedInteger, expectedBoolean)) {
                    return true;
                }
            }
            return false;
        }
        return matchesScalar(actual, valueType, expectedValue, expectedInteger, expectedBoolean);
    }

    private static boolean matchesScalar(Object actual, ValueType valueType, String expectedValue,
                                         long expectedInteger, boolean expectedBoolean) {
        if (actual == null) {
            return false;
        }
        if (valueType == ValueType.STRING) {
            return expectedValue.equals(String.valueOf(actual));
        }
        if (valueType == ValueType.INTEGER) {
            return matchesInteger(actual, expectedInteger);
        }
        return matchesBoolean(actual, expectedBoolean);
    }

    private static Object valueOf(Resource resource, String name) {
        if (resource == null || StringUtils.isEmpty(name)) {
            return ABSENT;
        }
        ValueMap properties = resource.getValueMap();
        if (properties == null || !properties.containsKey(name)) {
            return ABSENT;
        }
        return properties.get(name);
    }

    private static boolean matchesInteger(Object actual, long expected) {
        if (actual instanceof Number) {
            try {
                return new BigDecimal(actual.toString()).compareTo(BigDecimal.valueOf(expected)) == 0;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (actual instanceof String) {
            try {
                return Long.parseLong(((String) actual).trim()) == expected;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return false;
    }

    private static boolean matchesBoolean(Object actual, boolean expected) {
        if (actual instanceof Boolean) {
            return ((Boolean) actual).booleanValue() == expected;
        }
        if (actual instanceof String) {
            String text = ((String) actual).trim();
            if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
                return Boolean.parseBoolean(text) == expected;
            }
        }
        return false;
    }

    /**
     * @return why the check cannot run, or {@code null} when the configuration is usable
     */
    static String invalidReason(String propertyName, ValueType valueType, String expectedValue) {
        if (valueType == null) {
            return "value type must be String, Integer, or Boolean";
        }
        if (propertyName.isEmpty()) {
            return "property name is required";
        }
        if (expectedValue.isEmpty()) {
            return "expected value is required";
        }
        for (String segment : propertyName.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return "property name '" + propertyName + "' is not a valid relative path";
            }
        }
        if (valueType == ValueType.INTEGER) {
            try {
                Long.parseLong(expectedValue);
            } catch (NumberFormatException ex) {
                return "expected value '" + expectedValue + "' is not an integer";
            }
        } else if (valueType == ValueType.BOOLEAN
                && !"true".equalsIgnoreCase(expectedValue)
                && !"false".equalsIgnoreCase(expectedValue)) {
            return "expected value '" + expectedValue + "' is not a boolean";
        }
        return null;
    }

    enum ValueType {
        STRING, INTEGER, BOOLEAN;

        static ValueType from(String raw) {
            if (raw == null || raw.trim().isEmpty()) {
                return null;
            }
            try {
                return ValueType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }
}
