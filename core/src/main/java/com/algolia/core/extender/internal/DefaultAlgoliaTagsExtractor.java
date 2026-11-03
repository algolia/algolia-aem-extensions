package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.TagsParserService;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.extender.AlgoliaPageRequestExtender;
import com.algolia.connector.core.extender.ConfigurableRequestExtender;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import com.algolia.connector.core.inference.InferenceProbe;
import com.algolia.connector.core.inference.LlmProviderStore;
import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Page;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_OBJECT_ID;
import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_PATH;
import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_TYPE;
import static com.day.cq.dam.api.DamConstants.DC_DESCRIPTION;
import static com.day.cq.dam.api.DamConstants.DC_TITLE;
import static com.day.cq.tagging.TagConstants.PN_TAGS;

/**
 * Default implementation extracting the tags from a JCR tag property of a Page or an Asset.
 * Optionally enriches every indexed record with LLM-generated classification, summary, topics,
 * keywords, language, and document type attributes (prefixed with {@code tag}).
 *
 * @author Rakesh Kumar
 * @since 3.0.0
 */
@ComponentServiceProperties(description = "Algolia Tags Extractor")
@Designate(ocd = DefaultAlgoliaTagsExtractor.Config.class)
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class,
                InferenceProbe.class
        }
)
public class DefaultAlgoliaTagsExtractor implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender,
        ConfigurableRequestExtender, InferenceProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final Set<String> IDENTITY_ATTRIBUTES = Set.of(ATTRIBUTE_OBJECT_ID, ATTRIBUTE_PATH, ATTRIBUTE_TYPE);

    private final TagsParserService tagsParserService;
    private final CryptoSupport cryptoSupport;
    private final LlmProviderStore llmProviderStore;
    private final LlmEnricher llmEnricher;

    @Activate
    public DefaultAlgoliaTagsExtractor(@Reference TagsParserService tagsParserService,
                                       @Reference CryptoSupport cryptoSupport,
                                       @Reference LlmProviderStore llmProviderStore,
                                       Config config) {
        this(tagsParserService, cryptoSupport, llmProviderStore, buildEnricher(config));
    }

    /**
     * Package-private constructor for unit tests that inject a pre-built enricher.
     */
    DefaultAlgoliaTagsExtractor(TagsParserService tagsParserService, CryptoSupport cryptoSupport,
                                LlmProviderStore llmProviderStore, LlmEnricher llmEnricher) {
        this.tagsParserService = tagsParserService;
        this.cryptoSupport = cryptoSupport;
        this.llmProviderStore = llmProviderStore;
        this.llmEnricher = llmEnricher;
    }

    @Deactivate
    protected void deactivate() {
        if (this.llmEnricher != null) {
            try {
                this.llmEnricher.close();
            } catch (IOException e) {
                LOGGER.debug("Could not close the LLM enricher", e);
            }
        }
    }

    private static LlmEnricher buildEnricher(Config config) {
        ChatCompletionsClient.Options options = ChatCompletionsClient.Options.builder()
                .timeoutMs(Math.max(1000, config.llm_timeout_ms()))
                .maxTokens(Math.max(64, config.llm_max_tokens()))
                .maxResponseBytes(Math.max(1024, config.llm_max_response_bytes()))
                .allowedHostPatterns(config.llm_allowed_hosts())
                .useJsonResponseFormat(config.llm_json_response_format())
                .temperature(ChatCompletionsClient.parseTemperature(config.llm_temperature()))
                .maxTokensParameter(config.llm_max_tokens_parameter())
                .build();
        return LlmEnricher.forTags(new ChatCompletionsClient(options), config.llm_max_input_chars());
    }

    @Override
    public String getSettingsDescription() {
        return "Adds tag titles from a JCR tag property onto the Algolia record. "
                + "Optionally enriches records with LLM-generated classification, summary, and metadata.";
    }

    @Override
    public List<ExtenderConfigField> getConfigFields() {
        return List.of(
                ExtenderConfigField.builder("tagProperty", "Tag Property")
                        .defaultValue(PN_TAGS)
                        .helpText("JCR property that stores tag IDs. Pages are read from jcr:content, "
                                + "and assets from jcr:content/metadata. Defaults to cq:tags.")
                        .build(),
                ExtenderConfigField.builder("attributePrefix", "Attribute Prefix")
                        .helpText("Prefix added to every attribute name the extension emits, e.g. \"cat_\" → cat_topic.")
                        .build(),
                // LLM enrichment fields — authorOnly so AI Setup cannot propose values
                ExtenderConfigField.builder("enableLlmEnrichment", "Enable LLM Enrichment")
                        .type(ExtenderConfigField.Type.BOOLEAN)
                        .defaultValue("false")
                        .authorOnly(true)
                        .helpText("Send the page or asset content to an LLM to generate attributes from "
                                + "the Output Fields list. Enrichment is fail-open: if the LLM call fails, "
                                + "only tag attributes are indexed.")
                        .build(),
                ExtenderConfigField.builder("llmProviderId", "LLM Provider")
                        .type(ExtenderConfigField.Type.LLM_PROVIDER)
                        .helpText("Select the LLM provider defined on the Advanced tab. "
                                + "The provider supplies the inference URL, model, and encrypted API key.")
                        .build(),
                ExtenderConfigField.builder("llmOutputFields", "Output Fields")
                        .defaultValue(LlmEnricher.DEFAULT_OUTPUT_FIELDS)
                        .authorOnly(true)
                        .helpText("Comma-separated JSON keys the model must return, each optionally followed "
                                + "by a colon and type (text, longtext, list, lang). Leave blank to use the "
                                + "default: classification:text, summary:longtext, topics:list, "
                                + "keywords:list, language:lang, documentType:text. "
                                + "Each key is written as {prefix}{stem}{Key} on the Algolia record.")
                        .build(),
                ExtenderConfigField.builder("llmAttributeStem", "Attribute Stem")
                        .defaultValue("tag")
                        .authorOnly(true)
                        .helpText("Stem inserted between the Attribute Prefix and the capitalised field key. "
                                + "Default is \"tag\", producing names such as tagSummary. "
                                + "Letters, digits, and underscores only. Blank writes the key directly "
                                + "after the prefix.")
                        .build(),
                ExtenderConfigField.builder("llmContentProperties", "Content Properties")
                        .authorOnly(true)
                        .helpText("By default the LLM receives every text property on the record, plus the "
                                + "page or asset title and description. Enter a comma-separated list to "
                                + "restrict that, for example title, product, state, meta.jcr_title.")
                        .build(),
                ExtenderConfigField.builder("llmExcludedProperties", "Excluded Content Properties")
                        .defaultValue("pdfText")
                        .authorOnly(true)
                        .helpText("Comma-separated attribute names excluded from the default content sent to "
                                + "the model. Numbered partitions are excluded automatically (e.g. pdfText1, "
                                + "pdfText2). If the PDF Attribute Name was changed, add the new name here.")
                        .build(),
                ExtenderConfigField.builder("systemPrompt", "System Prompt")
                        .type(ExtenderConfigField.Type.TEXTAREA)
                        .defaultValue(LlmEnricher.DEFAULT_SYSTEM_PROMPT)
                        .authorOnly(true)
                        .helpText("System message sent before the page or asset text. Use {{fields}} as a "
                                + "placeholder for the Output Fields description; it is replaced automatically. "
                                + "The model must return a JSON object whose keys match Output Fields. "
                                + "Text fields are written in a language the extender picks from the record "
                                + "before calling the model; that language rule is always appended.")
                        .build(),
                ExtenderConfigField.builder("userPrompt", "User Prompt")
                        .type(ExtenderConfigField.Type.TEXTAREA)
                        .defaultValue(LlmEnricher.TAGS_USER_PROMPT)
                        .authorOnly(true)
                        .helpText("User message sent to the model. Use {{content}} as a placeholder for the "
                                + "page or asset text. {{fields}} is also supported. "
                                + "If {{content}} is absent the text is appended.")
                        .build()
        );
    }

    @Override
    public void augmentAlgoliaRequest(AlgoliaRequest request, Asset asset) {
        this.addTagsToAlgoliaRecord(request);
        this.enrichWithLlm(request, asset.getPath(),
                asset.getMetadataValue(DC_TITLE), asset.getMetadataValue(DC_DESCRIPTION));
    }

    @Override
    public void augmentAlgoliaRequest(AlgoliaRequest request, Page page) {
        this.addTagsToAlgoliaRecord(request);
        this.enrichWithLlm(request, page.getPath(), page.getTitle(), page.getDescription());
    }

    @Override
    public void testConnection(String inferenceUrl, String model, String apiKey) throws IOException {
        if (this.llmEnricher == null) {
            throw new IOException("Tags LLM inference is not available.");
        }
        this.llmEnricher.probe(inferenceUrl, model, apiKey);
    }

    private void addTagsToAlgoliaRecord(AlgoliaRequest request) {
        Resource resource = request.getResource();
        AlgoliaRecord algoliaRecord = request.getAlgoliaRecords().get(0);
        ExtenderSettings settings = settingsOf(request);
        String tagProperty = settings.getString("tagProperty", PN_TAGS).trim();
        if (StringUtils.isEmpty(tagProperty)) {
            tagProperty = PN_TAGS;
        }
        String prefix = prefixOf(settings);
        AlgoliaRecord parsed = new AlgoliaRecord(algoliaRecord.getObjectID());
        this.tagsParserService.parse(resource, parsed, tagProperty);
        for (AlgoliaRecord target : LlmEnricher.recordsToUpdate(request)) {
            AttributeNames.mergeWithPrefix(parsed, target, prefix);
        }
    }

    private void enrichWithLlm(AlgoliaRequest request, String path, String title, String description) {
        if (this.llmEnricher == null) {
            return;
        }
        ExtenderSettings settings = settingsOf(request);
        List<String> properties = settings.getList("llmContentProperties");
        List<String> excludedList = settings.getList("llmExcludedProperties");
        Set<String> excluded = excludedList.isEmpty() ? Set.of("pdfText") : new HashSet<>(excludedList);
        AlgoliaRecord record = request.getAlgoliaRecords().get(0);
        Resource resource = request.getResource();
        String content = buildContent(title, description, record, properties, resource, excluded);
        if (settings.getBoolean("enableLlmEnrichment", false)) {
            logContentSource(path, properties, content, title, description, record, resource);
        }
        this.llmEnricher.enrichIfEnabled(request, path, settings, prefixOf(settings), this.cryptoSupport,
                this.llmProviderStore, () -> content);
    }

    private static void logContentSource(String path, List<String> properties, String content, String title,
                                         String description, AlgoliaRecord record, Resource resource) {
        if (properties.isEmpty()) {
            if (content.isEmpty()) {
                LOGGER.info("Tags LLM enrichment has no content for {}: the page has no title, no description, "
                        + "and no text attributes on the record. Record attributes: {}", path, record.keySet());
            } else {
                LOGGER.debug("Tags LLM content for {}: {} characters from the title, description, and record text.",
                        path, content.length());
            }
            return;
        }
        List<String> missing = properties.stream()
                .filter(name -> StringUtils.isBlank(textOf(resolveProperty(name, title, description, record, resource))))
                .collect(Collectors.toList());
        if (content.isEmpty()) {
            LOGGER.info("Tags LLM enrichment has no content for {}: none of the Content Properties {} has a text value "
                    + "on the record or in JCR. Record attributes: {}", path, properties, record.keySet());
        } else if (!missing.isEmpty()) {
            LOGGER.debug("Tags LLM content for {}: {} characters; Content Properties without a value: {}",
                    path, content.length(), missing);
        } else {
            LOGGER.debug("Tags LLM content for {}: {} characters from Content Properties {}.",
                    path, content.length(), properties);
        }
    }

    /**
     * Builds the text sent to the LLM: the title and description, then every text or list
     * attribute already on the record (tag titles included). Identity attributes and attributes
     * in the default exclusion list ({@code pdfText} and its numbered partitions) are left out
     * to keep the input concise.
     */
    static String buildContent(String title, String description, AlgoliaRecord record) {
        return buildContent(title, description, record, List.of(), null, Set.of("pdfText"));
    }

    /**
     * When {@code properties} is empty, uses the default title / description / record-text
     * summary. Otherwise only the named Algolia attributes or JCR properties are included.
     * Uses the default exclusion set ({@code pdfText}).
     */
    static String buildContent(String title, String description, AlgoliaRecord record,
                               List<String> properties, Resource resource) {
        return buildContent(title, description, record, properties, resource, Set.of("pdfText"));
    }

    /**
     * Full variant with an explicit exclusion set. Excluded names and their numbered partitions
     * (e.g. {@code pdfText1}, {@code pdfText2}) are omitted from the default content path.
     * The exclusion set is ignored when {@code properties} is non-empty (named properties only).
     */
    static String buildContent(String title, String description, AlgoliaRecord record,
                               List<String> properties, Resource resource, Set<String> excluded) {
        StringBuilder content = new StringBuilder();
        if (properties == null || properties.isEmpty()) {
            appendLine(content, "Title", title);
            appendLine(content, "Description", description);
            if (record != null) {
                record.forEach((name, value) -> {
                    if (!IDENTITY_ATTRIBUTES.contains(name) && !isExcluded(name, excluded)) {
                        appendLine(content, name, textOf(value));
                    }
                });
            }
            return content.toString().trim();
        }
        for (String name : properties) {
            appendLine(content, name, textOf(resolveProperty(name, title, description, record, resource)));
        }
        return content.toString().trim();
    }

    /**
     * Returns {@code true} when {@code name} is in the exclusion set or is a numbered partition
     * of an excluded base name (e.g. {@code pdfText1} for excluded base {@code pdfText}).
     */
    private static boolean isExcluded(String name, Set<String> excluded) {
        if (excluded.contains(name)) {
            return true;
        }
        for (String base : excluded) {
            if (name.startsWith(base) && name.length() > base.length()) {
                String suffix = name.substring(base.length());
                if (!suffix.isEmpty() && suffix.chars().allMatch(Character::isDigit)) {
                    return true;
                }
            }
        }
        return false;
    }

    static Object resolveProperty(String name, String title, String description, AlgoliaRecord record,
                                  Resource resource) {
        if (StringUtils.isBlank(name)) {
            return null;
        }
        if ("title".equalsIgnoreCase(name) && StringUtils.isNotBlank(title)) {
            return title;
        }
        if (("description".equalsIgnoreCase(name) || "jcr:description".equals(name))
                && StringUtils.isNotBlank(description)) {
            return description;
        }
        Object fromRecord = lookupRecord(record, name);
        if (fromRecord != null) {
            return fromRecord;
        }
        return lookupJcr(resource, name);
    }

    private static Object lookupRecord(AlgoliaRecord record, String name) {
        if (record == null) {
            return null;
        }
        if (record.containsKey(name)) {
            return record.get(name);
        }
        int dot = name.indexOf('.');
        if (dot > 0) {
            Object nested = record.get(name.substring(0, dot));
            if (nested instanceof Map<?, ?>) {
                return ((Map<?, ?>) nested).get(name.substring(dot + 1));
            }
        }
        String sanitized = name.replace(':', '_');
        return record.containsKey(sanitized) ? record.get(sanitized) : null;
    }

    private static Object lookupJcr(Resource resource, String name) {
        if (resource == null || StringUtils.isBlank(name)) {
            return null;
        }
        Object value = valueMapOf(resource).get(name);
        if (value != null) {
            return value;
        }
        Resource content = resource.getChild("jcr:content");
        if (content != null) {
            value = valueMapOf(content).get(name);
            if (value != null) {
                return value;
            }
            Resource metadata = content.getChild("metadata");
            if (metadata != null) {
                return valueMapOf(metadata).get(name);
            }
        }
        return null;
    }

    private static ValueMap valueMapOf(Resource resource) {
        ValueMap values = resource.getValueMap();
        return values != null ? values : ValueMap.EMPTY;
    }

    private static void appendLine(StringBuilder content, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            content.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private static String textOf(Object value) {
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
        if (value instanceof Object[]) {
            return Arrays.stream((Object[]) value).map(String::valueOf).collect(Collectors.joining(", "));
        }
        return value instanceof String ? (String) value : null;
    }

    private static String prefixOf(ExtenderSettings settings) {
        String rawPrefix = settings.getString("attributePrefix", "");
        String prefix = AttributeNames.prefix(rawPrefix);
        if (prefix == null) {
            LOGGER.warn("Ignoring attribute prefix '{}' because it contains unsupported characters.", rawPrefix);
            return "";
        }
        return prefix;
    }

    private static ExtenderSettings settingsOf(AlgoliaRequest request) {
        ExtenderSettings settings = request.getExtenderSettings();
        return settings != null ? settings : ExtenderSettings.empty();
    }

    // OCD - processed by BND maven plugin.

    @ObjectClassDefinition(
            name = "DefaultAlgoliaTagsExtractor Configuration",
            description = "Configuration for DefaultAlgoliaTagsExtractor."
    )
    public @interface Config {

        @AttributeDefinition(
                name = "LLM Allowed Hosts",
                description = "Host patterns (with * wildcards) that the LLM enrichment endpoint is allowed to use. "
                        + "Empty allows any host except link-local, wildcard, and multicast addresses. "
                        + "Plain http is only allowed for localhost."
        )
        String[] llm_allowed_hosts() default {};

        @AttributeDefinition(
                name = "LLM Timeout (ms)",
                description = "HTTP socket and connect timeout for the LLM enrichment endpoint call."
        )
        int llm_timeout_ms() default 30000;

        @AttributeDefinition(
                name = "LLM Max Tokens",
                description = "Maximum number of tokens the LLM is asked to generate."
        )
        int llm_max_tokens() default 1024;

        @AttributeDefinition(
                name = "LLM Max Input Characters",
                description = "Maximum number of characters of the content summary sent to the LLM. "
                        + "Longer content is truncated before the call."
        )
        int llm_max_input_chars() default 8000;

        @AttributeDefinition(
                name = "LLM JSON Response Format",
                description = "When enabled, the request includes response_format:{type:json_object}. "
                        + "Only supported by endpoints that accept that parameter."
        )
        boolean llm_json_response_format() default false;

        @AttributeDefinition(
                name = "LLM Max Response Bytes",
                description = "Maximum size of the LLM response body in bytes. Responses larger than this are rejected."
        )
        int llm_max_response_bytes() default 2 * 1024 * 1024; // NOSONAR

        @AttributeDefinition(
                name = "LLM Temperature",
                description = "Sampling temperature sent to the model. Leave blank to omit the field entirely "
                        + "(required for reasoning models such as OpenAI o-series that reject explicit temperature)."
        )
        String llm_temperature() default "0"; // NOSONAR

        @AttributeDefinition(
                name = "LLM Max Tokens Parameter",
                description = "Name of the token-limit parameter in the LLM request body. "
                        + "Use \"max_completion_tokens\" for OpenAI o-series reasoning models."
        )
        String llm_max_tokens_parameter() default "max_tokens"; // NOSONAR
    }
}
