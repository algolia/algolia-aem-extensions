package com.algolia.core.introspector.internal;

import com.algolia.connector.core.AlgoliaResourceIntrospector.Outcome;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.algolia.connector.core.AlgoliaResourceIntrospector.Outcome.ABORT;
import static com.algolia.connector.core.AlgoliaResourceIntrospector.Outcome.CONTINUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link DefaultAlgoliaPropertyValueIntrospector}
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlgoliaPropertyValueIntrospectorTest {

    @Mock
    private Resource resource;

    private ValueMap properties;

    private final DefaultAlgoliaPropertyValueIntrospector introspector =
            new DefaultAlgoliaPropertyValueIntrospector();

    @BeforeEach
    void setup() {
        this.properties = new ValueMapDecorator(new HashMap<>());
        lenient().when(this.resource.getValueMap()).thenReturn(this.properties);
        lenient().when(this.resource.getPath()).thenReturn("/content/site/en/page");
    }

    // -------------------------------------------------------------------------
    // getConfigFields contract
    // -------------------------------------------------------------------------

    @Test
    void getConfigFieldsExposesThreeFields() {
        List<ExtenderConfigField> fields = this.introspector.getConfigFields();
        assertEquals(3, fields.size());
        assertEquals("propertyName", fields.get(0).getName());
        assertEquals("valueType", fields.get(1).getName());
        assertEquals("expectedValue", fields.get(2).getName());
    }

    @Test
    void valueTypeFieldDefaultIsString() {
        List<ExtenderConfigField> fields = this.introspector.getConfigFields();
        ExtenderConfigField valueTypeField = fields.get(1);
        assertEquals("String", valueTypeField.getDefaultValue());
        assertEquals(ExtenderConfigField.Type.SELECT, valueTypeField.getType());
    }

    @Test
    void valueTypeFieldHasAllThreeOptions() {
        List<ExtenderConfigField> fields = this.introspector.getConfigFields();
        List<String> options = fields.get(1).getOptions();
        assertEquals(List.of("String", "Integer", "Boolean"), options);
    }

    // -------------------------------------------------------------------------
    // String matching
    // -------------------------------------------------------------------------

    @Test
    void stringMatchOnResourceAborts() {
        this.properties.put("cq:robotsTags", "noindex");
        Outcome outcome = run("cq:robotsTags", "String", "noindex");
        assertEquals(ABORT, outcome);
    }

    @Test
    void stringMismatchContinues() {
        this.properties.put("cq:robotsTags", "index");
        Outcome outcome = run("cq:robotsTags", "String", "noindex");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void stringArrayMatchAborts() {
        this.properties.put("cq:robotsTags", new String[]{"index", "noindex"});
        Outcome outcome = run("cq:robotsTags", "String", "noindex");
        assertEquals(ABORT, outcome);
    }

    @Test
    void resourceValueWinsOverContentChild() {
        this.properties.put("status", "keep");
        Outcome outcome = run("status", "String", "skip");
        assertEquals(CONTINUE, outcome);
        verify(this.resource, never()).getChild(anyString());
    }

    @Test
    void stringOnPageContentAborts() {
        this.child("jcr:content").getValueMap().put("cq:robotsTags", "noindex");
        Outcome outcome = run("cq:robotsTags", "String", "noindex");
        assertEquals(ABORT, outcome);
    }

    @Test
    void stringOnAssetMetadataAborts() {
        this.child("jcr:content/metadata").getValueMap().put("dc:title", "Hidden");
        Outcome outcome = run("dc:title", "String", "Hidden");
        assertEquals(ABORT, outcome);
    }

    // -------------------------------------------------------------------------
    // Integer matching
    // -------------------------------------------------------------------------

    @Test
    void integerMatchAborts() {
        this.properties.put("priority", 3);
        Outcome outcome = run("priority", "Integer", "3");
        assertEquals(ABORT, outcome);
    }

    @Test
    void integerMatchWithDecimalRepresentation() {
        this.properties.put("rank", new BigDecimal("12.0"));
        Outcome outcome = run("rank", "Integer", "12");
        assertEquals(ABORT, outcome);
    }

    @Test
    void integerMismatchContinues() {
        this.properties.put("rank", 5);
        Outcome outcome = run("rank", "Integer", "12");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void integerZeroMatchAborts() {
        this.properties.put("rank", 0);
        Outcome outcome = run("rank", "Integer", "0");
        assertEquals(ABORT, outcome);
    }

    @Test
    void integerStringValueMatches() {
        this.properties.put("rank", "12");
        Outcome outcome = run("rank", "Integer", "12");
        assertEquals(ABORT, outcome);
    }

    @Test
    void integerStringMismatchContinues() {
        this.properties.put("rank", "5");
        Outcome outcome = run("rank", "Integer", "12");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void integerFloatPropertyContinues() {
        this.properties.put("rank", 12.5f);
        Outcome outcome = run("rank", "Integer", "12");
        assertEquals(CONTINUE, outcome);
    }

    // -------------------------------------------------------------------------
    // Boolean matching
    // -------------------------------------------------------------------------

    @Test
    void booleanTrueMatchAborts() {
        this.properties.put("hideInNav", true);
        Outcome outcome = run("hideInNav", "Boolean", "true");
        assertEquals(ABORT, outcome);
    }

    @Test
    void booleanFalseMatchAborts() {
        this.properties.put("hideInNav", false);
        Outcome outcome = run("hideInNav", "Boolean", "false");
        assertEquals(ABORT, outcome);
    }

    @Test
    void booleanCaseInsensitive() {
        this.properties.put("hideInNav", true);
        Outcome outcome = run("hideInNav", "Boolean", "TRUE");
        assertEquals(ABORT, outcome);
    }

    @Test
    void booleanMismatchContinues() {
        this.properties.put("hideInNav", false);
        Outcome outcome = run("hideInNav", "Boolean", "true");
        assertEquals(CONTINUE, outcome);
    }

    // -------------------------------------------------------------------------
    // Relative path property
    // -------------------------------------------------------------------------

    @Test
    void relativePathPropertyAborts() {
        this.child("jcr:content").getValueMap().put("cq:robotsTags", "noindex");
        Outcome outcome = run("jcr:content/cq:robotsTags", "String", "noindex");
        assertEquals(ABORT, outcome);
    }

    // -------------------------------------------------------------------------
    // Edge cases / validation
    // -------------------------------------------------------------------------

    @Test
    void nullResourceContinues() {
        Outcome outcome = this.introspector.introspect(null, settings("cq:robotsTags", "String", "noindex"));
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void nullPropertyNameContinues() {
        Outcome outcome = run(null, "String", null);
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void blankPropertyNameContinues() {
        Outcome outcome = run("  ", "String", "noindex");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void blankExpectedValueDoesNotAbortAnEmptyProperty() {
        this.properties.put("cq:robotsTags", "");
        Outcome outcome = run("cq:robotsTags", "String", "  ");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void nonIntegerExpectedValueContinues() {
        this.properties.put("rank", "1");
        Outcome outcome = run("rank", "Integer", "twelve");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void nonBooleanExpectedValueContinues() {
        this.properties.put("hideInNav", "yes");
        Outcome outcome = run("hideInNav", "Boolean", "yes");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void invalidValueTypeContinues() {
        this.properties.put("rank", "1");
        Outcome outcome = run("rank", "date", "1");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void parentPathInPropertyNameContinues() {
        this.properties.put("secret", "x");
        Outcome outcome = run("../secret", "String", "x");
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void oneArgIntrospectAlwaysContinues() {
        this.properties.put("cq:robotsTags", "noindex");
        // Calling the one-arg overload (no settings) must always CONTINUE
        Outcome outcome = this.introspector.introspect(this.resource);
        assertEquals(CONTINUE, outcome);
    }

    @Test
    void valueTypeDefaultsToStringWhenNotSet() {
        this.properties.put("cq:robotsTags", "noindex");
        // Pass no valueType — should default to String via field default
        ExtenderSettings settingsObj = settings("cq:robotsTags", null, "noindex");
        Outcome outcome = this.introspector.introspect(this.resource, settingsObj);
        assertEquals(ABORT, outcome);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Runs the introspector with the given settings against {@link #resource}.
     */
    private Outcome run(String propertyName, String valueType, String expectedValue) {
        return this.introspector.introspect(this.resource, settings(propertyName, valueType, expectedValue));
    }

    /**
     * Builds an {@link ExtenderSettings} from the introspector's declared config fields,
     * applying the given overrides (null values are omitted so field defaults apply).
     */
    private ExtenderSettings settings(String propertyName, String valueType, String expectedValue) {
        List<ExtenderConfigField> fields = this.introspector.getConfigFields();
        Map<String, String> values = new HashMap<>();
        if (propertyName != null) {
            values.put("propertyName", propertyName);
        }
        if (valueType != null) {
            values.put("valueType", valueType);
        }
        if (expectedValue != null) {
            values.put("expectedValue", expectedValue);
        }
        return new ExtenderSettings(values, fields);
    }

    private Resource child(String path) {
        Resource child = mock(Resource.class);
        ValueMap childProperties = new ValueMapDecorator(new HashMap<>());
        when(child.getValueMap()).thenReturn(childProperties);
        lenient().when(this.resource.getChild(path)).thenReturn(child);
        return child;
    }
}
