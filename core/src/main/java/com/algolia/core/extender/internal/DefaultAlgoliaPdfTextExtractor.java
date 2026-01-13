package com.algolia.core.extender.internal;

import com.algolia.connector.core.AlgoliaExceptionHandler;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import opennlp.tools.tokenize.WhitespaceTokenizer;
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
import java.util.Map;

import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_PATH;

/**
 * Algolia AssetRequestExtender for adding pdfText attribute to the {@link AlgoliaRecord}.
 *
 * @author Rakesh Kumar
 * @since 3.5.0
 */
@Component(name = "Algolia PDF Text Extractor")
public class DefaultAlgoliaPdfTextExtractor implements AlgoliaAssetRequestExtender {

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
    public void augmentAlgoliaRequest(AlgoliaRequest request, Asset asset) {
        if (StringUtils.equals(asset.getMimeType(), MIME_TYPE_PDF)) {
            LOGGER.info("Encountered PDF asset, extracting text from it.");
            String text = this.extractor.extractText(asset);
            if (StringUtils.isNotEmpty(text)) {
                List<AlgoliaRecord> algoliaRecords = request.getAlgoliaRecords();
                AlgoliaRecord originalRecord = algoliaRecords.get(0);
                String originalObjectID = originalRecord.getObjectID();
                String[] tokens = WhitespaceTokenizer.INSTANCE.tokenize(text);
                if (tokens.length <= this.wordSizeLimit) {
                    originalRecord.addAttribute(ATTRIBUTE_PDF_TEXT, text);
                } else {
                    this.handleWords(tokens, originalRecord);
                    // Split the record of overall size is over the threshold.
                    if (text.length() > RECORD_SIZE_LIMIT) {
                        int index = 0;
                        for (Map.Entry<String, Object> entry : originalRecord.entrySet()) {
                            String key = entry.getKey();
                            if (StringUtils.startsWith(key, ATTRIBUTE_PDF_TEXT)) {
                                String parentObjectID = originalRecord.getObjectID();
                                AlgoliaRecord algoliaRecord = new AlgoliaRecord(parentObjectID + "_" + index);
                                algoliaRecord.addAttribute(ATTRIBUTE_PDF_TEXT, entry.getValue());
                                algoliaRecord.addAttribute(ATTRIBUTE_PATH, asset.getPath());
                                request.addRecord(algoliaRecord);
                                index++;
                            }
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

    private void handleWords(String[] tokens, AlgoliaRecord record) {
        int length = tokens.length;
        int numOfFullPartitions = length / this.wordSizeLimit;
        int lastPartition = length % this.wordSizeLimit;
        int partitionSize = numOfFullPartitions;
        if (lastPartition > 0) {
            partitionSize += 1;
        }
        int rangeFrom = 0;
        String[][] partitionedArrays = new String[partitionSize][];
        for (int i = 0; i < numOfFullPartitions; i++) {
            partitionedArrays[i] = Arrays.copyOfRange(tokens, rangeFrom, (rangeFrom + this.wordSizeLimit));
            rangeFrom = rangeFrom + this.wordSizeLimit;
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
            record.addAttribute(ATTRIBUTE_PDF_TEXT + index, sentenceBuilder.toString().trim());
            index++;
        }
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
