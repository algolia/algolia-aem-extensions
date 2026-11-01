package com.algolia.core.extender.internal;

import com.algolia.connector.core.domain.AlgoliaRecord;

import java.util.List;
import java.util.Map;

import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_OBJECT_ID;

/**
 * Shared rules for the attribute names and prefixes an author enters on an extender.
 */
final class AttributeNames {

    private static final String NAME_PATTERN = "[A-Za-z0-9_-]+";

    private AttributeNames() {
    }

    /**
     * Returns the trimmed value, or {@code null} when it contains characters other than letters,
     * digits, underscores, and hyphens. Blank input returns an empty string.
     */
    static String prefix(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.matches(NAME_PATTERN)) {
            return trimmed;
        }
        return null;
    }

    /**
     * Copies every attribute of {@code source} except {@code objectID} onto {@code target},
     * renamed with {@code prefix}. List values are appended to an existing list of the same name.
     */
    @SuppressWarnings("unchecked")
    static void mergeWithPrefix(AlgoliaRecord source, AlgoliaRecord target, String prefix) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (ATTRIBUTE_OBJECT_ID.equals(entry.getKey())) {
                continue;
            }
            String key = prefix + entry.getKey();
            Object existing = target.get(key);
            if (existing instanceof List && entry.getValue() instanceof List) {
                ((List<Object>) existing).addAll((List<Object>) entry.getValue());
            } else {
                target.addAttribute(key, entry.getValue());
            }
        }
    }
}
