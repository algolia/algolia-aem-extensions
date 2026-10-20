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
 * <h2>Fixed output schema</h2>
 * The model is expected to return a JSON object with these keys:
 * <ul>
 *   <li>{@code classification} – String: broad category of the document</li>
 *   <li>{@code summary} – String: one-paragraph summary</li>
 *   <li>{@code topics} – String[]: up to 20 main topics</li>
 *   <li>{@code keywords} – String[]: up to 20 search keywords</li>
 *   <li>{@code language} – String: ISO 639-1 language code</li>
 *   <li>{@code documentType} – String: document sub-type (policy, guide, report, …)</li>
 * </ul>
 * Each key is written as the attribute stem plus the capitalized key, for example
 * {@code pdfClassification} or {@code tagDocumentType}, after the author's attribute prefix.
 *
 * <h2>Prompts</h2>
 * System and user prompts come from the per-index Extension Settings. Defaults instruct the
 * model to output JSON only, and to write classification, summary, topics, keywords, and
 * document type in the dominant language of the main text, ignoring field labels and tag names.
 * The {@code language} value stays an ISO 639-1 code. For {@link #forTags}, the language is
 * fixed before the call from the record (see {@link #chooseLanguage}) and that rule is appended
 * to the system prompt, including a custom one. The placeholder ({@code {{pdfText}}} or {@code {{content}}}) in the
 * user prompt is replaced with the (truncated) text. If the placeholder is absent, the text is
 * appended.
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

    private static final int MAX_ARRAY_SIZE = 20;

    /** Maximum character length for a single LLM-generated string attribute. */
    private static final int MAX_STRING_LENGTH = 256;

    /** Maximum character length for the {@code summary} attribute (longer prose field). */
    private static final int MAX_SUMMARY_LENGTH = 2000;

    /** ISO 639 language code with optional BCP 47 subtags, such as {@code en}, {@code fil} or {@code en-US}. */
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-zA-Z]{2,3}([_-][a-zA-Z0-9]{2,8})*$");

    static final String DEFAULT_SYSTEM_PROMPT =
            "You are a content analyst. When given document text, return ONLY a valid JSON object "
                    + "(no prose, no markdown fences) with exactly these keys: "
                    + "\"classification\" (string), "
                    + "\"summary\" (string, one paragraph), "
                    + "\"topics\" (array of strings, at most 20), "
                    + "\"keywords\" (array of strings, at most 20), "
                    + "\"language\" (ISO 639-1 language code string), "
                    + "\"documentType\" (string). "
                    + "Determine the dominant language of the main text (title, description, and body), "
                    + "ignoring field labels, attribute names, tag names, and these instructions. "
                    + "Write classification, summary, topics, keywords, and documentType in that language. "
                    + "Do not translate them into English or any other language. "
                    + "Set language to the ISO 639-1 code of that language, not a language name. "
                    + "Use null for any field you cannot determine.";

    static final String PDF_USER_PROMPT = "Analyze the following document text:\n\n" + PDF_PLACEHOLDER;
    static final String TAGS_USER_PROMPT = "Analyze the following content:\n\n" + TAGS_PLACEHOLDER;

    private final ChatCompletionsClient client;
    private final int maxInputChars;
    private final String attributeStem;
    private final String placeholder;
    private final String defaultUserPrompt;

    private LlmEnricher(ChatCompletionsClient client, int maxInputChars,
                        String attributeStem, String placeholder, String defaultUserPrompt) {
        this.client = client;
        this.maxInputChars = Math.max(500, maxInputChars);
        this.attributeStem = attributeStem;
        this.placeholder = placeholder;
        this.defaultUserPrompt = defaultUserPrompt;
    }

    static LlmEnricher forPdf(ChatCompletionsClient client, int maxInputChars) {
        return new LlmEnricher(client, maxInputChars, "pdf", PDF_PLACEHOLDER, PDF_USER_PROMPT);
    }

    static LlmEnricher forTags(ChatCompletionsClient client, int maxInputChars) {
        return new LlmEnricher(client, maxInputChars, "tag", TAGS_PLACEHOLDER, TAGS_USER_PROMPT);
    }

    /**
     * Resolves the LLM provider and API key for the request, then calls {@link #enrich}. Fails open: when
     * enrichment is disabled or anything goes wrong, it logs and returns so indexing continues.
     *
     * @param store   the shared provider registry; checked before the per-configuration JSON
     * @param content supplies the text to analyze; only called once a provider is resolved
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
        try {
            this.enrich(request, path, text, provider.get().getInferenceUrl(), provider.get().getModel(), apiKey,
                    settings.getString("systemPrompt"), settings.getString("userPrompt"), prefix);
        } catch (Exception e) {
            LOGGER.warn("LLM enrichment failed for {}, indexing continues without enrichment attributes: {}",
                    path, ChatCompletionsClient.sanitize(e.getMessage(), apiKey, 300));
        }
    }

    /**
     * Calls the LLM and applies the enrichment attributes to every record on the request.
     *
     * @param request      the current indexing request (all records are updated)
     * @param path         used only for logging
     * @param text         the text to analyze (truncated internally before sending)
     * @param inferenceUrl the endpoint URL
     * @param model        the model identifier
     * @param apiKey       the Bearer token (already decrypted)
     * @param systemPrompt the system-role message (blank falls back to the default)
     * @param userPrompt   the user-role message with an optional placeholder
     * @param prefix       the attribute prefix from the author's Extension Settings
     * @throws IOException if the HTTP call fails or the response cannot be parsed
     */
    void enrich(AlgoliaRequest request, String path, String text,
                String inferenceUrl, String model, String apiKey,
                String systemPrompt, String userPrompt, String prefix) throws IOException {
        String truncated = truncate(text);
        String system = StringUtils.isNotBlank(systemPrompt) ? systemPrompt.trim() : DEFAULT_SYSTEM_PROMPT;
        String outputLanguage = null;
        if ("tag".equals(this.attributeStem)) {
            outputLanguage = chooseLanguage(request).code;
            system = system + "\n\n" + languageDirective(outputLanguage);
        }
        String user = buildUserMessage(userPrompt, truncated);

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

        EnrichmentResult result = parse(reply);
        if (outputLanguage != null) {
            result.language = outputLanguage;
        }
        String namePrefix = prefix + this.attributeStem;
        applyToAllRecords(request, result, namePrefix);
        LOGGER.debug("LLM enrichment for {} wrote {}* attributes: classification={}, summary={}, topics={}, "
                        + "keywords={}, language={}, documentType={}.", path, namePrefix,
                result.classification != null, result.summary != null, result.topics.size(),
                result.keywords.size(), result.language, result.documentType != null);
    }

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

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= this.maxInputChars ? text : text.substring(0, this.maxInputChars);
    }

    String buildUserMessage(String userPrompt, String text) {
        if (StringUtils.isBlank(userPrompt)) {
            return this.defaultUserPrompt.replace(this.placeholder, text);
        }
        if (userPrompt.contains(this.placeholder)) {
            return userPrompt.replace(this.placeholder, text);
        }
        // Placeholder missing: append
        return userPrompt.trim() + "\n\n" + text;
    }

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

    /**
     * Returns the records on the request plus any record an earlier extender removed from it
     * but bulk indexing still saves (the original of a split PDF).
     */
    static List<AlgoliaRecord> recordsToUpdate(AlgoliaRequest request) {
        List<AlgoliaRecord> records = new ArrayList<>(request.getAlgoliaRecords());
        records.addAll(request.getRetainedRecords());
        return records;
    }

    private static void applyToAllRecords(AlgoliaRequest request, EnrichmentResult result, String namePrefix) {
        for (AlgoliaRecord record : recordsToUpdate(request)) {
            putIfAbsent(record, namePrefix + "Classification", result.classification);
            putIfAbsent(record, namePrefix + "Summary", result.summary);
            putListIfAbsent(record, namePrefix + "Topics", result.topics);
            putListIfAbsent(record, namePrefix + "Keywords", result.keywords);
            putIfAbsent(record, namePrefix + "Language", result.language);
            putIfAbsent(record, namePrefix + "DocumentType", result.documentType);
        }
    }

    /**
     * Language for tag enrichment, in this order: an authored language attribute on the record
     * ({@code language}, {@code jcr:language}, …), {@code pdfLanguage} or another {@code *Language}
     * attribute an earlier extender wrote, then the language of text already on the record.
     * This extension's own {@code tag*} fields are ignored. Returns English when none of those
     * identifies a language.
     */
    static LanguageChoice chooseLanguage(AlgoliaRequest request) {
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
                if (normalized.endsWith("taglanguage")) {
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
        String fromText = languageFromRecordText(request);
        if (fromText != null) {
            return new LanguageChoice(fromText, "recordText");
        }
        return new LanguageChoice("en", "default");
    }

    /**
     * Reads text an earlier extender already put on the record and picks a language when
     * distinctive words repeat. Short titles and product names do not count.
     */
    private static String languageFromRecordText(AlgoliaRequest request) {
        StringBuilder sample = new StringBuilder();
        for (AlgoliaRecord record : recordsToUpdate(request)) {
            for (Map.Entry<String, Object> entry : record.entrySet()) {
                if (entry.getKey() == null || skipForLanguageSample(entry.getKey())) {
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

    private static boolean skipForLanguageSample(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace(':', '_');
        if (normalized.equals("objectid") || normalized.equals("path") || normalized.equals("type")) {
            return true;
        }
        return normalized.contains("tagclassification") || normalized.contains("tagsummary")
                || normalized.contains("tagtopics") || normalized.contains("tagkeywords")
                || normalized.contains("taglanguage") || normalized.contains("tagdocumenttype");
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

    private static String languageDirective(String language) {
        return "The language for this content is already set to \"" + language + "\". "
                + "This replaces any earlier instruction to determine the language from the text. "
                + "Write classification, summary, topics, keywords, and documentType in that language only. "
                + "Set language to \"" + language + "\". "
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

    /** Parsed model output — all fields are nullable / may be empty. */
    static final class EnrichmentResult {
        String classification;
        String summary;
        List<String> topics = new ArrayList<>();
        List<String> keywords = new ArrayList<>();
        String language;
        String documentType;
    }
}
