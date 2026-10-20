package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.config.AlgoliaConfiguration;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import com.algolia.connector.core.inference.LlmProvider;
import com.algolia.connector.core.inference.LlmProviderResolver;
import com.algolia.connector.core.inference.LlmProviderStore;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Calls an OpenAI-compatible Chat Completions endpoint with text from a page or asset and writes
 * the model's response as Algolia attributes on every record in the request. One instance serves
 * one extension: {@link #forPdf} writes {@code pdf*} attributes, {@link #forTags} writes
 * {@code tag*} attributes.
 *
 * <h2>Configurable output schema</h2>
 * The set of JSON keys the model must return, and their types, are configured via the
 * {@code llmOutputFields} Extension Setting as a comma-separated list of {@code key[:type]}
 * entries. The default schema is:
 * {@code classification:text, summary:longtext, topics:list, keywords:list, language:lang, documentType:text}.
 * Each key is written as the attribute stem plus the capitalised key, for example
 * {@code pdfClassification} or {@code tagDocumentType}, after the author's attribute prefix.
 * The stem itself is configurable via {@code llmAttributeStem} (defaults: {@code pdf} / {@code tag}).
 *
 * <h2>Prompts</h2>
 * System and user prompts come from the per-index Extension Settings. Defaults instruct the
 * model to output JSON only. The placeholder {@code {{fields}}} in either prompt is replaced
 * with a description of the configured fields. When neither prompt contains {@code {{fields}}},
 * a fields instruction is appended to the system prompt so older custom prompts still receive
 * the configured schema. The content placeholders ({@code {{pdfText}}} or {@code {{content}}})
 * in the user prompt are replaced with the (truncated) text. If absent, the text is appended.
 *
 * <p>For {@link #forTags}, the output language is fixed before the call from the record (see
 * {@link #chooseLanguage}) and appended to the system prompt.
 *
 * <h2>Error handling</h2>
 * Any exception thrown by this class is caught by the extender, logged as a warning, and
 * skipped — extraction always continues.
 *
 * @since 1.2.0
 */
final class LlmEnricher implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final String PDF_PLACEHOLDER = "{{pdfText}}";
    static final String TAGS_PLACEHOLDER = "{{content}}";
    /** Placeholder in prompts replaced with the description of the configured output fields. */
    static final String FIELDS_PLACEHOLDER = "{{fields}}";

    private static final int MAX_ARRAY_SIZE = 20;

    /** Maximum character length for a single LLM-generated string attribute. */
    private static final int MAX_STRING_LENGTH = 256;

    /** Maximum character length for the {@code longtext} type attribute (longer prose field). */
    private static final int MAX_SUMMARY_LENGTH = 2000;

    /** Maximum number of output fields an author may configure. */
    private static final int MAX_OUTPUT_FIELDS = 20;

    /** ISO 639 language code with optional BCP 47 subtags, such as {@code en}, {@code fil} or {@code en-US}. */
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8})*$");

    /** Valid identifier pattern for output field keys. */
    private static final Pattern FIELD_KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    /** Reserved Algolia attribute names that must not be used as field keys. */
    private static final Set<String> RESERVED_KEYS = Set.of("objectid", "path", "type");

    /**
     * Default output-field spec. Comma-separated {@code key:type} entries. Used as the
     * default value for the {@code llmOutputFields} Extension Setting.
     */
    static final String DEFAULT_OUTPUT_FIELDS =
            "classification:text, summary:longtext, topics:list, keywords:list, language:lang, documentType:text";

    static final String DEFAULT_SYSTEM_PROMPT =
            "You are a content analyst. When given document text, return ONLY a valid JSON object "
                    + "(no prose, no markdown fences) with exactly these keys: " + FIELDS_PLACEHOLDER + ". "
                    + "Determine the dominant language of the main text (title, description, and body), "
                    + "ignoring field labels, attribute names, tag names, and these instructions. "
                    + "Write the text fields in that language. "
                    + "Do not translate them into English or any other language. "
                    + "Set any language-code field to the ISO 639-1 code of that language, not a language name. "
                    + "Use null for any field you cannot determine.";

    static final String PDF_USER_PROMPT = "Analyze the following document text:\n\n" + PDF_PLACEHOLDER;
    static final String TAGS_USER_PROMPT = "Analyze the following content:\n\n" + TAGS_PLACEHOLDER;

    // ─── Field type ───────────────────────────────────────────────────────────

    /** Typing for a configured output field. */
    enum FieldType {
        /** Trimmed string, max {@value #MAX_STRING_LENGTH} chars. */
        TEXT,
        /** Trimmed string, max {@value #MAX_SUMMARY_LENGTH} chars. */
        LONGTEXT,
        /** String array, max {@value #MAX_ARRAY_SIZE} de-duplicated entries. */
        LIST,
        /** ISO 639-1 language code; prose strings are rejected. */
        LANG
    }

    /** One configured output field with its key and storage type. */
    static final class OutputField {
        final String key;
        final FieldType type;

        OutputField(String key, FieldType type) {
            this.key = key;
            this.type = type;
        }
    }

    // ─── Instance state ───────────────────────────────────────────────────────

    private final ChatCompletionsClient client;
    private final int maxInputChars;
    /** Default attribute stem for this enricher instance, e.g. {@code "pdf"} or {@code "tag"}. */
    private final String defaultStem;
    private final String placeholder;
    private final String defaultUserPrompt;

    private LlmEnricher(ChatCompletionsClient client, int maxInputChars,
                        String defaultStem, String placeholder, String defaultUserPrompt) {
        this.client = client;
        this.maxInputChars = Math.max(500, maxInputChars);
        this.defaultStem = defaultStem;
        this.placeholder = placeholder;
        this.defaultUserPrompt = defaultUserPrompt;
    }

    static LlmEnricher forPdf(ChatCompletionsClient client, int maxInputChars) {
        return new LlmEnricher(client, maxInputChars, "pdf", PDF_PLACEHOLDER, PDF_USER_PROMPT);
    }

    static LlmEnricher forTags(ChatCompletionsClient client, int maxInputChars) {
        return new LlmEnricher(client, maxInputChars, "tag", TAGS_PLACEHOLDER, TAGS_USER_PROMPT);
    }

    // ─── Public enrichment entry point ────────────────────────────────────────

    /**
     * Resolves the LLM provider and API key for the request, then calls {@link #enrich}. Fails
     * open: when enrichment is disabled or anything goes wrong, it logs and returns so indexing
     * continues.
     *
     * @param store   the shared provider registry; checked before the per-configuration JSON
     * @param content supplies the text to analyse; only called once a provider is resolved
     */
    void enrichIfEnabled(AlgoliaRequest request, String path, ExtenderSettings settings, String prefix,
                         CryptoSupport cryptoSupport, LlmProviderStore store, Supplier<String> content) {
        if (!settings.getBoolean("enableLlmEnrichment", false)) {
            LOGGER.debug("LLM enrichment skipped for {}: Enable LLM Enrichment is off.", path);
            return;
        }
        AlgoliaConfiguration config = request.getConfig();
        if (config == null) {
            LOGGER.debug("LLM enrichment skipped for {}: AlgoliaConfiguration is not available on this request.",
                    path);
            return;
        }
        Optional<LlmProvider> provider = LlmProviderResolver.resolve(store, config, settings);
        if (provider.isEmpty()) {
            LOGGER.info("LLM enrichment skipped for {}: no LLM provider resolved (provider id '{}').",
                    path, settings.getString("llmProviderId", ""));
            return;
        }
        String text = content.get();
        if (StringUtils.isBlank(text)) {
            LOGGER.info("LLM enrichment skipped for {}: there is no content to analyze.", path);
            return;
        }
        String apiKey;
        try {
            apiKey = AlgoliaUtil.unprotectText(provider.get().getInferenceApiKey(), cryptoSupport);
        } catch (Exception e) {
            LOGGER.warn("LLM enrichment skipped for {}: could not decrypt the API key.", path);
            return;
        }
        List<OutputField> fields = parseOutputFields(settings.getString("llmOutputFields", ""));
        String stem = resolveStem(settings);
        try {
            enrich(request, path, text, provider.get().getInferenceUrl(), provider.get().getModel(), apiKey,
                    settings.getString("systemPrompt"), settings.getString("userPrompt"), prefix, stem, fields);
        } catch (Exception e) {
            LOGGER.warn("LLM enrichment failed for {}, indexing continues without enrichment attributes: {}",
                    path, ChatCompletionsClient.sanitize(e.getMessage(), apiKey, 300));
        }
    }

    /**
     * Calls the LLM with explicit stem and output fields, then writes the results to all records.
     * This is the primary overload called by {@link #enrichIfEnabled} and testable by unit tests.
     *
     * @param request      the current indexing request (all records are updated)
     * @param path         used only for logging
     * @param text         the text to analyse (truncated internally before sending)
     * @param inferenceUrl the endpoint URL
     * @param model        the model identifier
     * @param apiKey       the Bearer token (already decrypted)
     * @param systemPrompt the system-role message (blank falls back to the default)
     * @param userPrompt   the user-role message with an optional placeholder
     * @param prefix       the attribute prefix from the author's Extension Settings
     * @param stem         the attribute stem (e.g. {@code "pdf"} or {@code "tag"})
     * @param fields       the configured output fields
     * @throws IOException if the HTTP call fails or the response cannot be parsed
     */
    void enrich(AlgoliaRequest request, String path, String text,
                String inferenceUrl, String model, String apiKey,
                String systemPrompt, String userPrompt, String prefix,
                String stem, List<OutputField> fields) throws IOException {
        enrichInternal(request, path, text, inferenceUrl, model, apiKey, systemPrompt, userPrompt, prefix,
                stem, fields);
    }

    /**
     * Convenience overload using the instance's default stem and the default output fields.
     * Kept for backward compatibility with unit tests.
     *
     * @throws IOException if the HTTP call fails or the response cannot be parsed
     */
    void enrich(AlgoliaRequest request, String path, String text,
                String inferenceUrl, String model, String apiKey,
                String systemPrompt, String userPrompt, String prefix) throws IOException {
        enrich(request, path, text, inferenceUrl, model, apiKey, systemPrompt, userPrompt, prefix,
                this.defaultStem, parseOutputFields(""));
    }

    // ─── Internal full enrichment ─────────────────────────────────────────────

    private void enrichInternal(AlgoliaRequest request, String path, String text,
                                String inferenceUrl, String model, String apiKey,
                                String systemPrompt, String userPrompt, String prefix,
                                String stem, List<OutputField> fields) throws IOException {
        String truncated = truncate(text);
        String fieldsDescription = describeFields(fields);

        String system = StringUtils.isNotBlank(systemPrompt) ? systemPrompt.trim() : DEFAULT_SYSTEM_PROMPT;

        // Determine whether this is a Tags enricher (needs language pre-selection)
        boolean isTags = "tag".equals(this.defaultStem);
        String langKey = findLangKey(fields);
        String outputLanguage = null;
        if (isTags && langKey != null) {
            Set<String> ownAttrNorms = buildOwnAttrNorms(stem, fields);
            outputLanguage = chooseLanguage(request, ownAttrNorms).code;
        }

        // Track whether {{fields}} appears BEFORE replacement
        boolean systemHasFields = system.contains(FIELDS_PLACEHOLDER);
        boolean userHasFields = StringUtils.isNotBlank(userPrompt) && userPrompt.contains(FIELDS_PLACEHOLDER);

        // Replace {{fields}} in the system prompt
        system = system.replace(FIELDS_PLACEHOLDER, fieldsDescription);

        // Append language directive for Tags (includes the chosen language)
        if (outputLanguage != null) {
            system = system + "\n\n" + languageDirective(outputLanguage, langKey);
        }

        // If neither prompt carried {{fields}}, append a compact fields instruction so custom
        // prompts saved before this feature was added still receive the configured schema
        if (!systemHasFields && !userHasFields) {
            system = system + "\n\n" + fieldsDirective(fieldsDescription);
        }

        String user = buildUserMessage(userPrompt, truncated, fieldsDescription);

        long start = System.currentTimeMillis();
        List<ChatCompletionsClient.Message> messages = List.of(
                new ChatCompletionsClient.Message("system", system),
                new ChatCompletionsClient.Message("user", user)
        );
        String reply;
        try {
            reply = this.client.chat(inferenceUrl, model, apiKey, messages);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("LLM enrichment for {} completed in {} ms (model: {}).", path, elapsed, model);

        Map<String, Object> parsed = parseFields(reply, fields);

        // Override the language field for Tags so the model's detected language is ignored
        if (outputLanguage != null && langKey != null) {
            parsed.put(langKey, outputLanguage);
        }

        applyFieldsToAllRecords(request, parsed, prefix, stem, fields);
        LOGGER.debug("LLM enrichment for {} wrote attributes with prefix='{}', stem='{}', fieldCount={}.",
                path, prefix, stem, fields.size());
    }

    // ─── Connection probe ─────────────────────────────────────────────────────

    /**
     * Sends a short completion to confirm the endpoint, model, and API key work with this
     * client's host allow-list and timeout.
     */
    void probe(String inferenceUrl, String model, String apiKey) throws IOException {
        try {
            this.client.chat(inferenceUrl, model, apiKey, List.of(
                    new ChatCompletionsClient.Message("system",
                            "Reply with a JSON object {\"ok\":true} and nothing else."),
                    new ChatCompletionsClient.Message("user", "ping")));
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    // ─── Prompt helpers ───────────────────────────────────────────────────────

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= this.maxInputChars ? text : text.substring(0, this.maxInputChars);
    }

    /**
     * Builds the user message. Replaces {@code {{fields}}} first (when fieldsDescription is
     * provided), then the content placeholder. If the content placeholder is absent, the text
     * is appended.
     */
    String buildUserMessage(String userPrompt, String text) {
        return buildUserMessage(userPrompt, text, null);
    }

    String buildUserMessage(String userPrompt, String text, String fieldsDescription) {
        String effective = StringUtils.isBlank(userPrompt) ? this.defaultUserPrompt : userPrompt;
        if (fieldsDescription != null && effective.contains(FIELDS_PLACEHOLDER)) {
            effective = effective.replace(FIELDS_PLACEHOLDER, fieldsDescription);
        }
        if (effective.contains(this.placeholder)) {
            return effective.replace(this.placeholder, text);
        }
        // Placeholder missing: append
        return effective.trim() + "\n\n" + text;
    }

    // ─── Output field spec parsing ────────────────────────────────────────────

    /**
     * Parses a comma-separated {@code key[:type]} spec into an ordered list of output fields.
     *
     * <p>Rules:
     * <ul>
     *   <li>Key must match {@code [A-Za-z][A-Za-z0-9_]*}; invalid keys are dropped.</li>
     *   <li>Reserved keys ({@code objectid}, {@code path}, {@code type}) are rejected.</li>
     *   <li>Duplicate keys (case-insensitive) are de-duplicated.</li>
     *   <li>Unknown type names default to {@link FieldType#TEXT}.</li>
     *   <li>At most {@value #MAX_OUTPUT_FIELDS} fields; extras are dropped.</li>
     *   <li>Blank or fully-invalid specs fall back to the default six fields.</li>
     * </ul>
     */
    static List<OutputField> parseOutputFields(String spec) {
        if (StringUtils.isBlank(spec)) {
            return parseRaw(DEFAULT_OUTPUT_FIELDS);
        }
        List<OutputField> result = parseRaw(spec);
        return result.isEmpty() ? parseRaw(DEFAULT_OUTPUT_FIELDS) : result;
    }

    private static List<OutputField> parseRaw(String spec) {
        List<OutputField> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String entry : spec.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            String key;
            FieldType type;
            if (colon > 0) {
                key = trimmed.substring(0, colon).trim();
                String typeName = trimmed.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
                type = parseFieldType(typeName);
            } else {
                key = trimmed;
                type = FieldType.TEXT;
            }
            if (!FIELD_KEY_PATTERN.matcher(key).matches()) {
                continue;
            }
            if (RESERVED_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!seen.add(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(new OutputField(key, type));
            if (result.size() >= MAX_OUTPUT_FIELDS) {
                break;
            }
        }
        return result;
    }

    private static FieldType parseFieldType(String typeName) {
        switch (typeName) {
            case "LONGTEXT": return FieldType.LONGTEXT;
            case "LIST":     return FieldType.LIST;
            case "LANG":     return FieldType.LANG;
            default:         return FieldType.TEXT;
        }
    }

    /**
     * Builds a human-readable description of the configured fields for use in prompts, e.g.
     * {@code "classification" (string), "summary" (string, one paragraph)}.
     */
    static String describeFields(List<OutputField> fields) {
        StringBuilder sb = new StringBuilder();
        for (OutputField f : fields) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(f.key).append('"');
            switch (f.type) {
                case LONGTEXT: sb.append(" (string, one paragraph)"); break;
                case LIST:     sb.append(" (array of strings, at most 20)"); break;
                case LANG:     sb.append(" (ISO 639-1 language code string)"); break;
                default:       sb.append(" (string)"); break;
            }
        }
        return sb.toString();
    }

    /** Short instruction appended when neither prompt contains {@link #FIELDS_PLACEHOLDER}. */
    private static String fieldsDirective(String fieldsDescription) {
        return "Return a JSON object with exactly these keys: " + fieldsDescription
                + ". Use null for any field you cannot determine.";
    }

    // ─── Stem resolution ──────────────────────────────────────────────────────

    /**
     * Resolves the attribute stem from settings.
     *
     * <p>When the author has not saved a value, the field default (typically {@code pdf} or
     * {@code tag}) applies. When the author clears the field, the stem is empty and attributes
     * are written as {@code prefix + key} without a stem segment. Invalid characters log a
     * warning and fall back to this instance's default stem.
     */
    String resolveStem(ExtenderSettings settings) {
        if (settings.hasValue("llmAttributeStem")) {
            String raw = settings.getString("llmAttributeStem").trim();
            if (raw.isEmpty()) {
                return "";
            }
            return validatedStem(raw);
        }
        String raw = settings.getString("llmAttributeStem", this.defaultStem).trim();
        if (raw.isEmpty()) {
            return this.defaultStem;
        }
        return validatedStem(raw);
    }

    private String validatedStem(String raw) {
        if (!raw.matches("[A-Za-z0-9_]*")) {
            LOGGER.warn("Ignoring attribute stem '{}' because it contains unsupported characters; "
                    + "using default '{}'.", raw, this.defaultStem);
            return this.defaultStem;
        }
        return raw;
    }

    // ─── Field-driven parsing and writing ─────────────────────────────────────

    /**
     * Parses the model's JSON reply and extracts values for the configured fields.
     * Values are coerced to the declared type; fields absent or of the wrong type yield no entry.
     */
    static Map<String, Object> parseFields(String reply, List<OutputField> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode root;
        try {
            root = ChatCompletionsClient.parseJson(reply);
        } catch (Exception e) {
            LOGGER.warn("LLM enrichment: could not parse model reply as JSON — skipping attributes.");
            return result;
        }
        for (OutputField field : fields) {
            Object value = extractValue(root.path(field.key), field.type);
            if (value != null) {
                result.put(field.key, value);
            }
        }
        return result;
    }

    private static Object extractValue(JsonNode node, FieldType type) {
        switch (type) {
            case LONGTEXT: return stringOrNull(node, MAX_SUMMARY_LENGTH);
            case LIST: {
                List<String> list = stringListOrEmpty(node);
                return list.isEmpty() ? null : list;
            }
            case LANG: return validLanguageOrNull(stringOrNull(node, MAX_STRING_LENGTH));
            default:   return stringOrNull(node, MAX_STRING_LENGTH);
        }
    }

    private static void applyFieldsToAllRecords(AlgoliaRequest request, Map<String, Object> parsed,
                                                String prefix, String stem, List<OutputField> fields) {
        for (AlgoliaRecord record : recordsToUpdate(request)) {
            for (OutputField field : fields) {
                String attrName = buildAttrName(prefix, stem, field.key);
                Object value = parsed.get(field.key);
                if (value instanceof String) {
                    putIfAbsent(record, attrName, (String) value);
                } else if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> list = (List<String>) value;
                    putListIfAbsent(record, attrName, list);
                }
            }
        }
    }

    private static String buildAttrName(String prefix, String stem, String key) {
        if (stem.isEmpty()) {
            return prefix + key;
        }
        return prefix + stem + capitalize(key);
    }

    private static String capitalize(String key) {
        if (key == null || key.isEmpty()) {
            return key;
        }
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private static String findLangKey(List<OutputField> fields) {
        for (OutputField f : fields) {
            if (f.type == FieldType.LANG) {
                return f.key;
            }
        }
        return null;
    }

    /**
     * Builds the set of normalised attribute names (lowercase, no prefix) that this enricher
     * writes, used to skip its own output from language detection.
     */
    static Set<String> buildOwnAttrNorms(String stem, List<OutputField> fields) {
        Set<String> norms = new HashSet<>();
        for (OutputField f : fields) {
            String attrName = stem.isEmpty() ? f.key : stem + capitalize(f.key);
            norms.add(attrName.toLowerCase(Locale.ROOT));
        }
        return norms;
    }

    // ─── Backward-compatible legacy parse ─────────────────────────────────────

    /**
     * Parses the model's JSON reply using the default six-field schema.
     * Kept for backward compatibility with unit tests.
     */
    static EnrichmentResult parse(String reply) {
        EnrichmentResult result = new EnrichmentResult();
        JsonNode root;
        try {
            root = ChatCompletionsClient.parseJson(reply);
        } catch (Exception e) {
            LOGGER.warn("LLM enrichment: could not parse model reply as JSON — skipping attributes.");
            return result;
        }
        result.classification = stringOrNull(root.path("classification"), MAX_STRING_LENGTH);
        result.summary = stringOrNull(root.path("summary"), MAX_SUMMARY_LENGTH);
        result.topics = stringListOrEmpty(root.path("topics"));
        result.keywords = stringListOrEmpty(root.path("keywords"));
        result.language = validLanguageOrNull(stringOrNull(root.path("language"), MAX_STRING_LENGTH));
        result.documentType = stringOrNull(root.path("documentType"), MAX_STRING_LENGTH);
        return result;
    }

    private static String stringOrNull(JsonNode node, int maxLength) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    /**
     * Validates a language string against a simple BCP 47 / ISO 639-1 pattern.
     * Returns {@code null} when the value looks like model-generated prose rather than a code.
     */
    private static String validLanguageOrNull(String language) {
        if (language == null) {
            return null;
        }
        return LANGUAGE_PATTERN.matcher(language).matches() ? language : null;
    }

    private static List<String> stringListOrEmpty(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                String raw = item.asText("").trim();
                // Cap each array element at the standard string limit
                String text = raw.length() > MAX_STRING_LENGTH ? raw.substring(0, MAX_STRING_LENGTH) : raw;
                if (!text.isEmpty() && seen.add(text)) {
                    result.add(text);
                    if (result.size() >= MAX_ARRAY_SIZE) {
                        break;
                    }
                }
            }
        }
        return result;
    }

    // ─── Records helper ───────────────────────────────────────────────────────

    /**
     * Returns the records on the request plus any record an earlier extender removed from it
     * but bulk indexing still saves (the original of a split PDF).
     */
    static List<AlgoliaRecord> recordsToUpdate(AlgoliaRequest request) {
        List<AlgoliaRecord> records = new ArrayList<>(request.getAlgoliaRecords());
        records.addAll(request.getRetainedRecords());
        return records;
    }

    // ─── Language selection (Tags) ────────────────────────────────────────────

    /**
     * Language for tag enrichment, in this order: an authored language attribute on the record
     * ({@code language}, {@code jcr:language}, …), {@code pdfLanguage} or another {@code *Language}
     * attribute an earlier extender wrote, then the language of text already on the record.
     * This extension's own {@code tag*} fields are ignored. Returns English when none of those
     * identifies a language.
     *
     * <p>This no-argument overload uses the default tag attribute names for the skip list.
     */
    static LanguageChoice chooseLanguage(AlgoliaRequest request) {
        // Default tag enricher attribute names (default stem "tag", default fields)
        Set<String> ownNorms = Set.of(
                "tagclassification", "tagsummary", "tagtopics", "tagkeywords",
                "taglanguage", "tagdocumenttype");
        return chooseLanguage(request, ownNorms);
    }

    /**
     * Parameterised variant used when the stem or output fields are customised.
     *
     * @param ownAttrNorms normalised attribute names (stem + key, lowercase, no prefix) that
     *                     belong to this enricher instance and must be excluded from sampling
     */
    static LanguageChoice chooseLanguage(AlgoliaRequest request, Set<String> ownAttrNorms) {
        // Extract the normalised language attribute name owned by this enricher (ends in "language")
        String ownLangAttrNorm = ownAttrNorms.stream()
                .filter(n -> n.endsWith("language"))
                .findFirst()
                .orElse(null);

        String pdfLanguage = null;
        String otherLanguage = null;
        String named = null;
        for (AlgoliaRecord record : recordsToUpdate(request)) {
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String normalized = key.toLowerCase(Locale.ROOT).replace(':', '_');
                // Skip this enricher's own language attribute to avoid circular dependency
                if (ownLangAttrNorm != null && normalized.endsWith(ownLangAttrNorm)) {
                    continue;
                }
                String code = validLanguageOrNull(textValue(entry.getValue()));
                if (code == null) {
                    continue;
                }
                if (normalized.endsWith("pdflanguage")) {
                    pdfLanguage = code;
                } else if (normalized.equals("language") || normalized.equals("lang")
                        || normalized.equals("jcr_language") || normalized.equals("dc_language")
                        || normalized.equals("cq_language")) {
                    named = code;
                } else if (normalized.endsWith("language")) {
                    otherLanguage = code;
                }
            }
        }
        if (named != null) {
            return new LanguageChoice(named, "namedLanguage");
        }
        if (pdfLanguage != null) {
            return new LanguageChoice(pdfLanguage, "pdfLanguage");
        }
        if (otherLanguage != null) {
            return new LanguageChoice(otherLanguage, "languageAttribute");
        }
        String fromText = languageFromRecordText(request, ownAttrNorms);
        if (fromText != null) {
            return new LanguageChoice(fromText, "recordText");
        }
        return new LanguageChoice("en", "default");
    }

    /**
     * Reads text an earlier extender already put on the record and picks a language when
     * distinctive words repeat. Short titles and product names do not count.
     * Uses the default tag attribute skip list.
     */
    private static String languageFromRecordText(AlgoliaRequest request) {
        Set<String> ownNorms = Set.of(
                "tagclassification", "tagsummary", "tagtopics", "tagkeywords",
                "taglanguage", "tagdocumenttype");
        return languageFromRecordText(request, ownNorms);
    }

    private static String languageFromRecordText(AlgoliaRequest request, Set<String> ownAttrNorms) {
        StringBuilder sample = new StringBuilder();
        for (AlgoliaRecord record : recordsToUpdate(request)) {
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                if (entry.getKey() == null || skipForLanguageSample(entry.getKey(), ownAttrNorms)) {
                    continue;
                }
                String text = flatten(entry.getValue());
                if (StringUtils.isBlank(text)) {
                    continue;
                }
                if (isBodyText(entry.getKey()) && text.length() > 500) {
                    text = text.substring(0, 500);
                }
                sample.append(' ').append(text);
                if (sample.length() > 4000) {
                    return detectLanguage(sample.substring(0, 4000));
                }
            }
        }
        return detectLanguage(sample.toString());
    }

    private static boolean skipForLanguageSample(String key, Set<String> ownAttrNorms) {
        String normalized = key.toLowerCase(Locale.ROOT).replace(':', '_');
        if (normalized.equals("objectid") || normalized.equals("path") || normalized.equals("type")) {
            return true;
        }
        for (String own : ownAttrNorms) {
            if (normalized.contains(own)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBodyText(String key) {
        return key.toLowerCase(Locale.ROOT).contains("text");
    }

    private static String flatten(Object value) {
        if (value instanceof String) {
            return ((String) value).trim();
        }
        if (value instanceof Collection<?>) {
            StringBuilder joined = new StringBuilder();
            for (Object item : (Collection<?>) value) {
                if (item != null) {
                    if (joined.length() > 0) {
                        joined.append(' ');
                    }
                    joined.append(item);
                }
            }
            return joined.toString();
        }
        return null;
    }

    private static final String[][] LANGUAGE_WORDS = {
            {"en", "the", "and", "of", "for", "this", "with", "from", "that", "are"},
            {"es", "el", "los", "las", "una", "para", "del", "que", "por", "documento"},
            {"fr", "les", "des", "une", "pour", "dans", "est", "sur", "qui", "avec"},
            {"de", "der", "die", "das", "und", "für", "ein", "nicht", "mit", "von"},
            {"pt", "uma", "não", "dos", "das", "com", "em", "nos", "ao", "pelo"},
            {"it", "il", "per", "che", "della", "degli", "sono", "una", "con", "nel"}
    };

    private static String detectLanguage(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String padded = " " + text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ") + " ";
        String bestCode = null;
        int best = 0;
        int second = 0;
        for (String[] language : LANGUAGE_WORDS) {
            int hits = 0;
            for (int i = 1; i < language.length; i++) {
                String word = " " + language[i] + " ";
                int from = 0;
                while (from < padded.length()) {
                    int at = padded.indexOf(word, from);
                    if (at < 0) {
                        break;
                    }
                    hits++;
                    from = at + word.length() - 1;
                }
            }
            if (hits > best) {
                second = best;
                best = hits;
                bestCode = language[0];
            } else if (hits > second) {
                second = hits;
            }
        }
        if (bestCode == null || best < 2 || best == second) {
            return null;
        }
        return bestCode;
    }

    private static String languageDirective(String language, String langKey) {
        return "The language for this content is already set to \"" + language + "\". "
                + "This replaces any earlier instruction to determine the language from the text. "
                + "Write the text fields in that language only. "
                + "Set \"" + langKey + "\" to \"" + language + "\". "
                + "Do not detect a different language and do not translate into any other language.";
    }

    private static String textValue(Object value) {
        return value instanceof String ? ((String) value).trim() : null;
    }

    /** Where {@link #chooseLanguage} found the code. */
    static final class LanguageChoice {
        final String code;
        final String source;

        LanguageChoice(String code, String source) {
            this.code = code;
            this.source = source;
        }
    }

    private static void putIfAbsent(AlgoliaRecord record, String key, String value) {
        if (value != null && !record.containsKey(key)) {
            record.addAttribute(key, value);
        }
    }

    private static void putListIfAbsent(AlgoliaRecord record, String key, List<String> values) {
        if (values != null && !values.isEmpty() && !record.containsKey(key)) {
            record.addAttribute(key, values);
        }
    }

    @Override
    public void close() throws IOException {
        this.client.close();
    }

    /**
     * Parsed model output using the default six-field schema.
     * Kept for backward compatibility with unit tests that call {@link #parse(String)}.
     */
    static final class EnrichmentResult {
        String classification;
        String summary;
        List<String> topics = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        String language;
        String documentType;
    }
}
