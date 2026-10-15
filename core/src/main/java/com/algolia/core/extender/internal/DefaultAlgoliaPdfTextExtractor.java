package com.algolia.core.extender.internal;

import com.algolia.connector.core.AlgoliaExceptionHandler;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.extender.ConfigurableRequestExtender;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;

import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_PATH;

/**
 * Algolia AssetRequestExtender for adding pdfText attribute to the {@link AlgoliaRecord}.
 *
 * @author Rakesh Kumar
 * @since 3.5.0
 */
@ComponentServiceProperties(description = "Algolia PDF Text Extractor")
@Component(service = AlgoliaAssetRequestExtender.class)
public class DefaultAlgoliaPdfTextExtractor implements AlgoliaAssetRequestExtender, ConfigurableRequestExtender {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    static final int RECORD_SIZE_LIMIT = 10 * 1024; // 10 KB

    static final String MIME_TYPE_PDF = "application/pdf";

    private static final String ATTRIBUTE_PDF_TEXT = "pdfText";

    private final PdfTextExtractor extractor;

    private final int wordSizeLimit;

    private final ResourceResolverFactory resolverFactory;

    @Activate
    public DefaultAlgoliaPdfTextExtractor(@Reference ResourceResolverFactory resolverFactory,
                                          @Reference PdfTextExtractor extractor,
                                          Config config) {
        this.resolverFactory = resolverFactory;
        this.extractor = extractor;
        this.wordSizeLimit = config.word_size_limit();
    }

    @Override
    public String getSettingsDescription() {
        return "Extracts text from PDF assets onto the Algolia record.";
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
                        .helpText("When the extracted text is longer than 10 KB, replace the original record "
                                + "with one record per chunk.")
                        .build()
        );
    }

    @Override
    public void augmentAlgoliaRequest(AlgoliaRequest request, Asset asset) {
        if (Strings.CS.equals(asset.getMimeType(), MIME_TYPE_PDF)) {
            LOGGER.info("Encountered PDF asset, extracting text from it.");
            String text = this.extractor.extractText(asset);
            if (StringUtils.isNotEmpty(text)) {
                ExtenderSettings settings = this.settingsOf(request);
                int wordLimit = settings.getInt("wordSizeLimit", this.wordSizeLimit);
                boolean splitOversizedRecords = settings.getBoolean("splitOversizedRecords", true);
                List<AlgoliaRecord> algoliaRecords = request.getAlgoliaRecords();
                AlgoliaRecord originalRecord = algoliaRecords.get(0);
                String attributeName = this.attributeName(settings, originalRecord);
                String originalObjectID = originalRecord.getObjectID();
                String[] tokens = WhitespaceTokenizer.INSTANCE.tokenize(text);
                if (tokens.length <= wordLimit) {
                    originalRecord.addAttribute(attributeName, text);
                } else {
                    int chunkCount = this.handleWords(tokens, originalRecord, wordLimit, attributeName);
                    // Split the record of overall size is over the threshold.
                    if (splitOversizedRecords && text.length() > RECORD_SIZE_LIMIT) {
                        for (int chunk = 1; chunk <= chunkCount; chunk++) {
                            AlgoliaRecord algoliaRecord = new AlgoliaRecord(originalObjectID + "_" + (chunk - 1));
                            algoliaRecord.addAttribute(attributeName, originalRecord.get(attributeName + chunk));
                            algoliaRecord.addAttribute(ATTRIBUTE_PATH, asset.getPath());
                            request.addRecord(algoliaRecord);
                        }
                        request.getAlgoliaRecords()
                                .removeIf(algoliaRecord -> algoliaRecord.getObjectID().equals(originalObjectID));
                        AlgoliaUtil.handleSplitRecordCount(request.getAlgoliaRecords().size(), asset.getPath(),
                                AlgoliaExceptionHandler.SplitRecordAction.ADD, this.resolverFactory);
                        request.setPdfTextSplittingAttempted(true);
                    }
                }
            }
        }
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
    }
}
