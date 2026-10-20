package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.AlgoliaExceptionHandler;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.extender.ConfigurableRequestExtender;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import com.algolia.connector.core.inference.InferenceProbe;
import com.algolia.connector.core.inference.LlmProviderStore;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceResolverFactory;
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
import java.util.List;
import java.util.Map;

import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_OBJECT_ID;
import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_PATH;

/**
 * Algolia AssetRequestExtender for adding pdfText attribute to the {@link AlgoliaRecord}.
 * Optionally enriches every PDF record with LLM-generated classification, summary, topics,
 * keywords, language, and document type by sending the extracted text to an OpenAI-compatible
 * Chat Completions endpoint.
 *
 * @author Rakesh Kumar
 * @since 3.5.0
 */
@ComponentServiceProperties(description = "Algolia PDF Text Extractor")
@Designate(ocd = DefaultAlgoliaPdfTextExtractor.Config.class)
@Component(service = {AlgoliaAssetRequestExtender.class, InferenceProbe.class})
public class DefaultAlgoliaPdfTextExtractor implements AlgoliaAssetRequestExtender, ConfigurableRequestExtender,
        InferenceProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final int RECORD_SIZE_LIMIT = 10 * 1024; // characters

    private static final int MIN_SPLIT_TEXT_THRESHOLD = 1024;

    static final String MIME_TYPE_PDF = "application/pdf";

    private static final String ATTRIBUTE_PDF_TEXT = "pdfText";

    private final PdfTextExtractor extractor;

    private final int wordSizeLimit;

    private final ResourceResolverFactory resolverFactory;

    private final CryptoSupport cryptoSupport;

    private final LlmProviderStore llmProviderStore;

    private final LlmEnricher llmEnricher;

    private final int splitTextThresholdChars;

    @Activate
    public DefaultAlgoliaPdfTextExtractor(@Reference ResourceResolverFactory resolverFactory,
                                          @Reference PdfTextExtractor extractor,
                                          @Reference CryptoSupport cryptoSupport,
                                          @Reference LlmProviderStore llmProviderStore,
                                          Config config) {
        this.resolverFactory = resolverFactory;
        this.extractor = extractor;
        this.cryptoSupport = cryptoSupport;
        this.llmProviderStore = llmProviderStore;
        this.wordSizeLimit = config.word_size_limit();
        this.splitTextThresholdChars = Math.max(MIN_SPLIT_TEXT_THRESHOLD, config.split_text_threshold_chars());
        this.llmEnricher = buildEnricher(config);
    }

    /**
     * Package-private constructor for unit tests that inject a pre-built enricher.
     */
    DefaultAlgoliaPdfTextExtractor(ResourceResolverFactory resolverFactory,
                                   PdfTextExtractor extractor,
                                   CryptoSupport cryptoSupport,
                                   LlmProviderStore llmProviderStore,
                                   Config config,
                                   LlmEnricher llmEnricher) {
        this.resolverFactory = resolverFactory;
        this.extractor = extractor;
        this.cryptoSupport = cryptoSupport;
        this.llmProviderStore = llmProviderStore;
        this.wordSizeLimit = config.word_size_limit();
        this.splitTextThresholdChars = Math.max(MIN_SPLIT_TEXT_THRESHOLD, config.split_text_threshold_chars());
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
        return LlmEnricher.forPdf(new ChatCompletionsClient(options), config.llm_max_input_chars());
    }

    @Override
    public String getSettingsDescription() {
        return "Extracts text from PDF assets onto the Algolia record. "
                + "Optionally enriches records with LLM-generated classification, summary, and metadata.";
    }

    @Override
    public List<ExtenderConfigField> getConfigFields() {
        return List.of(
                ExtenderConfigField.builder("wordSizeLimit", "Word Size Limit")
                        .type(ExtenderConfigField.Type.NUMBER)
                        .helpText("Maximum words stored in a single text attribute before the text is partitioned. "
                                + "Leave blank to use the OSGi Word Size Limit (currently " + this.wordSizeLimit + ").")
                        .build(),
                ExtenderConfigField.builder("attributeName", "Attribute Name")
                        .defaultValue(ATTRIBUTE_PDF_TEXT)
                        .helpText("Base name of the attribute that stores extracted text. "
                                + "Chunks are named with a number suffix, for example pdfText1. "
                                + "Letters, digits, underscores, and hyphens only.")
                        .build(),
                ExtenderConfigField.builder("attributePrefix", "Attribute Prefix")
                        .helpText("Prefix added to every attribute name the extension emits, e.g. \"doc_\" → doc_pdfText.")
                        .build(),
                ExtenderConfigField.builder("splitOversizedRecords", "Split Oversized Text")
                        .type(ExtenderConfigField.Type.BOOLEAN)
                        .defaultValue("true")
                        .helpText("When the extracted text is longer than " + this.splitTextThresholdChars
                                + " characters, replace the original record with one record per chunk. "
                                + "The threshold is set by the OSGi Split Text Threshold (Characters) property.")
                        .build(),
                // LLM enrichment fields — authorOnly so AI Setup cannot propose values
                ExtenderConfigField.builder("enableLlmEnrichment", "Enable LLM Enrichment")
                        .type(ExtenderConfigField.Type.BOOLEAN)
                        .defaultValue("false")
                        .authorOnly(true)
                        .helpText("Send extracted PDF text to an LLM to generate attributes from the "
                                + "Output Fields list. Enrichment is fail-open: if the LLM call fails, "
                                + "only pdfText is indexed.")
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
                        .defaultValue("pdf")
                        .authorOnly(true)
                        .helpText("Stem inserted between the Attribute Prefix and the capitalised field key. "
                                + "Default is \"pdf\", producing names such as pdfSummary. "
                                + "Letters, digits, and underscores only. Blank writes the key directly "
                                + "after the prefix.")
                        .build(),
                ExtenderConfigField.builder("systemPrompt", "System Prompt")
                        .type(ExtenderConfigField.Type.TEXTAREA)
                        .defaultValue(LlmEnricher.DEFAULT_SYSTEM_PROMPT)
                        .authorOnly(true)
                        .helpText("System message sent before the PDF text. Use {{fields}} as a placeholder "
                                + "for the Output Fields description; it is replaced automatically. "
                                + "The model must return a JSON object whose keys match Output Fields. "
                                + "By default text fields are written in the dominant language of the document.")
                        .build(),
                ExtenderConfigField.builder("userPrompt", "User Prompt")
                        .type(ExtenderConfigField.Type.TEXTAREA)
                        .defaultValue(LlmEnricher.PDF_USER_PROMPT)
                        .authorOnly(true)
                        .helpText("User message sent to the model. Use {{pdfText}} as a placeholder for the "
                                + "extracted text. {{fields}} is also supported. "
                                + "If {{pdfText}} is absent the text is appended.")
                        .build()
        );
    }

    @Override
    public void augmentAlgoliaRequest(AlgoliaRequest request, Asset asset) {
        if (!Strings.CS.equals(asset.getMimeType(), MIME_TYPE_PDF)) {
            return;
        }
        LOGGER.info("Encountered PDF asset, extracting text from it.");
        String text = this.extractor.extractText(asset);
        if (StringUtils.isEmpty(text)) {
            return;
        }
        ExtenderSettings settings = this.settingsOf(request);
        int wordLimit = settings.getInt("wordSizeLimit", this.wordSizeLimit);
        boolean splitOversizedRecords = settings.getBoolean("splitOversizedRecords", true);
        List<AlgoliaRecord> algoliaRecords = request.getAlgoliaRecords();
        AlgoliaRecord originalRecord = algoliaRecords.get(0);
        String attributeName = this.attributeName(settings, originalRecord);
        String originalObjectID = originalRecord.getObjectID();
        String[] tokens = WhitespaceTokenizer.INSTANCE.tokenize(text);
        boolean oversizedSplit = false;
        if (tokens.length <= wordLimit) {
            originalRecord.addAttribute(attributeName, text);
        } else {
            int chunkCount = this.handleWords(tokens, originalRecord, wordLimit, attributeName);
            // Split the record if overall size is over the threshold.
            // The original stays on the request until after LLM enrichment. Bulk indexing saves
            // that same record object, so enrichment has to land on it before it is removed.
            if (splitOversizedRecords && text.length() > this.splitTextThresholdChars) {
                oversizedSplit = true;
                for (int chunk = 1; chunk <= chunkCount; chunk++) {
                    AlgoliaRecord algoliaRecord = new AlgoliaRecord(originalObjectID + "_" + (chunk - 1));
                    // Earlier extenders (for example Tags LLM) already wrote attributes on the
                    // original. Chunks are new records, and the original is deleted after the split.
                    copySharedAttributes(originalRecord, algoliaRecord, attributeName);
                    algoliaRecord.addAttribute(attributeName, originalRecord.get(attributeName + chunk));
                    algoliaRecord.addAttribute(ATTRIBUTE_PATH, asset.getPath());
                    request.addRecord(algoliaRecord);
                }
            }
        }

        // LLM enrichment — always uses the full extracted text, regardless of splitting.
        // The original record is still on the request here, which is the record bulk indexing saves.
        this.enrichWithLlm(request, asset, text, settings);
        if (oversizedSplit) {
            request.retainRecord(originalRecord);
            request.getAlgoliaRecords()
                    .removeIf(algoliaRecord -> algoliaRecord.getObjectID().equals(originalObjectID));
            AlgoliaUtil.handleSplitRecordCount(request.getAlgoliaRecords().size(), asset.getPath(),
                    AlgoliaExceptionHandler.SplitRecordAction.ADD, this.resolverFactory);
            request.setPdfTextSplittingAttempted(true);
        }
    }

    @Override
    public void testConnection(String inferenceUrl, String model, String apiKey) throws IOException {
        if (this.llmEnricher == null) {
            throw new IOException("PDF inference is not available.");
        }
        this.llmEnricher.probe(inferenceUrl, model, apiKey);
    }

    private void enrichWithLlm(AlgoliaRequest request, Asset asset, String fullText, ExtenderSettings settings) {
        if (this.llmEnricher == null) {
            return;
        }
        this.llmEnricher.enrichIfEnabled(request, asset.getPath(), settings, this.resolvePrefix(settings),
                this.cryptoSupport, this.llmProviderStore, () -> fullText);
    }

    private ExtenderSettings settingsOf(AlgoliaRequest request) {
        ExtenderSettings settings = request.getExtenderSettings();
        return settings != null ? settings : ExtenderSettings.empty();
    }

    /**
     * Resolves the author's attribute name and prefix. Falls back to {@code pdfText} when the
     * name is unusable or would overwrite an attribute the indexer already put on the record.
     */
    private String attributeName(ExtenderSettings settings, AlgoliaRecord originalRecord) {
        String rawPrefix = settings.getString("attributePrefix", "");
        String prefix = AttributeNames.prefix(rawPrefix);
        if (prefix == null) {
            LOGGER.warn("Ignoring attribute prefix '{}' because it contains unsupported characters.", rawPrefix);
            prefix = "";
        }
        String fallback = prefix + ATTRIBUTE_PDF_TEXT;
        String rawName = settings.getString("attributeName", ATTRIBUTE_PDF_TEXT).trim();
        if (rawName.isEmpty()) {
            return fallback;
        }
        String name = AttributeNames.prefix(rawName);
        if (name == null) {
            LOGGER.warn("Ignoring attribute name '{}' because it contains unsupported characters.", rawName);
            return fallback;
        }
        String resolved = prefix + name;
        if (!resolved.equals(fallback) && originalRecord.containsKey(resolved)) {
            LOGGER.warn("Ignoring attribute name '{}' because the record already has that attribute.", resolved);
            return fallback;
        }
        return resolved;
    }

    /** Resolves just the prefix from settings (re-used by LLM enrichment). */
    private String resolvePrefix(ExtenderSettings settings) {
        String rawPrefix = settings.getString("attributePrefix", "");
        String prefix = AttributeNames.prefix(rawPrefix);
        if (prefix == null) {
            LOGGER.warn("Ignoring attribute prefix '{}' for LLM attributes because it contains unsupported characters.",
                    rawPrefix);
            prefix = "";
        }
        return prefix;
    }

    /**
     * Writes {@code attributeName1 … attributeNameN} onto {@code record} and returns {@code N}.
     */
    private int handleWords(String[] tokens, AlgoliaRecord record, int wordLimit, String attributeName) {
        int length = tokens.length;
        int numOfFullPartitions = length / wordLimit;
        int lastPartition = length % wordLimit;
        int partitionSize = numOfFullPartitions;
        if (lastPartition > 0) {
            partitionSize += 1;
        }
        int rangeFrom = 0;
        String[][] partitionedArrays = new String[partitionSize][];
        for (int i = 0; i < numOfFullPartitions; i++) {
            partitionedArrays[i] = Arrays.copyOfRange(tokens, rangeFrom, (rangeFrom + wordLimit));
            rangeFrom = rangeFrom + wordLimit;
        }
        if (lastPartition > 0) {
            partitionedArrays[partitionedArrays.length - 1] = Arrays.copyOfRange(tokens, rangeFrom, (rangeFrom + lastPartition));
        }
        int index = 1;
        for (String[] partitionedArray : partitionedArrays) {
            StringBuilder sentenceBuilder = new StringBuilder();
            for (String word : partitionedArray) {
                sentenceBuilder.append(word).append(" ");
            }
            record.addAttribute(attributeName + index, sentenceBuilder.toString().trim());
            index++;
        }
        return partitionSize;
    }

    /**
     * Copies attributes an earlier extender already put on the original record. Skips the
     * numbered text partitions ({@code pdfText1}, {@code pdfText2}, …) because each chunk
     * record keeps only its own slice under {@code attributeName}.
     */
    private static void copySharedAttributes(AlgoliaRecord from, AlgoliaRecord to, String attributeName) {
        for (Map.Entry<String, Object> entry : from.entrySet()) {
            String key = entry.getKey();
            if (ATTRIBUTE_OBJECT_ID.equals(key) || isNumberedPartition(key, attributeName)) {
                continue;
            }
            to.addAttribute(key, entry.getValue());
        }
    }

    private static boolean isNumberedPartition(String key, String attributeName) {
        if (attributeName == null || !key.startsWith(attributeName) || key.length() == attributeName.length()) {
            return false;
        }
        for (int i = attributeName.length(); i < key.length(); i++) {
            if (!Character.isDigit(key.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // OCD - processed by BND maven plugin.

    @ObjectClassDefinition(
            name = "DefaultAlgoliaPdfTextExtractor Configuration",
            description = "Configuration for DefaultAlgoliaPdfTextExtractor."
    )
    public @interface Config {

        @AttributeDefinition(
                name = "Word Size Limit",
                description = "Word size limit for extracting text out of PDF."
        )
        int word_size_limit() default 900; // NOSONAR

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
                description = "Maximum number of characters of the extracted PDF text sent to the LLM. "
                        + "Longer texts are truncated before the call."
        )
        int llm_max_input_chars() default 12000;

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

        @AttributeDefinition(
                name = "Split Text Threshold (Characters)",
                description = "Extracted PDF text longer than this many characters is split across multiple "
                        + "records when Split Oversized Text is enabled. Minimum 1024."
        )
        int split_text_threshold_chars() default RECORD_SIZE_LIMIT; // NOSONAR
    }
}
