package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.AlgoliaExceptionHandler;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.algolia.core.extender.internal.DefaultAlgoliaPdfTextExtractor.MIME_TYPE_PDF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link DefaultAlgoliaPdfTextExtractor}
 *
 * @author Rakesh.Kumar
 */
@ExtendWith(MockitoExtension.class)
public class DefaultAlgoliaPdfTextExtractorTest {

    private static final int ALGOLIA_WORDS_LIMIT = 900;

    @Mock
    private PdfTextExtractor pdfTextExtractor;

    @Mock
    private AlgoliaRequest request;

    @Mock
    private Asset asset;

    @Mock
    private DefaultAlgoliaPdfTextExtractor.Config config;

    @Mock
    private AlgoliaExceptionHandler exceptionHandler;

    @Mock
    private ResourceResolverFactory resolverFactory;

    @Mock
    private CryptoSupport cryptoSupport;

    private AlgoliaRecord algoliaRecord;

    private List<AlgoliaRecord> records;

    private DefaultAlgoliaPdfTextExtractor algoliaPdfTextExtractor;

    @BeforeEach
    void setup() {
        when(this.config.word_size_limit()).thenReturn(ALGOLIA_WORDS_LIMIT);
        when(this.config.split_text_threshold_chars()).thenReturn(DefaultAlgoliaPdfTextExtractor.RECORD_SIZE_LIMIT);
        // Pass null enricher so existing tests exercise only the text-extraction path
        this.algoliaPdfTextExtractor = new DefaultAlgoliaPdfTextExtractor(
                this.resolverFactory, this.pdfTextExtractor, this.cryptoSupport, null, this.config, null);
        this.records = new ArrayList<>();
        this.algoliaRecord = spy(new AlgoliaRecord("/content/dam/a/b.pdf"));
        this.records.add(this.algoliaRecord);
    }

    @Test
    void testAugmentAlgoliaRequestWhenMimeTypeIsNotPdf() {
        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);
        verifyNoInteractions(this.algoliaRecord);
    }

    @Test
    void testAugmentAlgoliaRequestWhenMimeTypeIsPdf() {
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("This is a text from PDF!!");
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);
        verify(this.algoliaRecord).addAttribute(any(), any());
    }

    @Test
    void testAuthorSettingsControlTheAttributeNameWordLimitAndRecordSplit() {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "wordSizeLimit", "2",
                "attributeName", "body",
                "attributePrefix", "doc_",
                "splitOversizedRecords", "false"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("one two three");
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        assertEquals("one two", this.algoliaRecord.get("doc_body1"));
        assertEquals("three", this.algoliaRecord.get("doc_body2"));
        assertNull(this.algoliaRecord.get("pdfText"));
        verify(this.request, never()).addRecord(any());
    }

    @Test
    void testSplitRecordsKeepChunkOrderPastTenChunks() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            text.append("chunk").append(i).append(' ').append("x".repeat(1000)).append(' ');
        }
        AlgoliaRequest realRequest = new AlgoliaRequest("/content/dam/a/b.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/a/b.pdf");
        realRequest.addRecord(record);
        realRequest.setExtenderSettings(new ExtenderSettings(Map.of("wordSizeLimit", "2"), List.of()));
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text.toString().trim());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.asset.getPath()).thenReturn("/content/dam/a/b.pdf");

        try (MockedStatic<AlgoliaUtil> ignored = mockStatic(AlgoliaUtil.class)) {
            this.algoliaPdfTextExtractor.augmentAlgoliaRequest(realRequest, this.asset);
        }

        List<AlgoliaRecord> split = realRequest.getAlgoliaRecords();
        assertEquals(12, split.size());
        assertEquals(1, realRequest.getRetainedRecords().size());
        assertEquals("/content/dam/a/b.pdf", realRequest.getRetainedRecords().get(0).getObjectID());
        for (int i = 0; i < 12; i++) {
            assertEquals("/content/dam/a/b.pdf_" + i, split.get(i).getObjectID());
            assertTrue(((String) split.get(i).get("pdfText")).startsWith("chunk" + i + " "));
            assertEquals("/content/dam/a/b.pdf", split.get(i).get("path"));
        }
    }

    @Test
    void splitRecordsKeepAttributesWrittenBeforeTheSplit() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            text.append("chunk").append(i).append(' ').append("x".repeat(4000)).append(' ');
        }
        AlgoliaRequest realRequest = new AlgoliaRequest("/content/dam/a/b.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/a/b.pdf");
        record.addAttribute("tagClassification", "Technical Data Sheet");
        record.addAttribute("tagTopics", List.of("Urethane"));
        realRequest.addRecord(record);
        realRequest.setExtenderSettings(new ExtenderSettings(Map.of("wordSizeLimit", "2"), List.of()));
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text.toString().trim());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.asset.getPath()).thenReturn("/content/dam/a/b.pdf");

        try (MockedStatic<AlgoliaUtil> ignored = mockStatic(AlgoliaUtil.class)) {
            this.algoliaPdfTextExtractor.augmentAlgoliaRequest(realRequest, this.asset);
        }

        assertEquals(3, realRequest.getAlgoliaRecords().size());
        for (int i = 0; i < 3; i++) {
            AlgoliaRecord split = realRequest.getAlgoliaRecords().get(i);
            assertEquals("/content/dam/a/b.pdf_" + i, split.getObjectID());
            assertEquals("Technical Data Sheet", split.get("tagClassification"));
            assertEquals(List.of("Urethane"), split.get("tagTopics"));
            assertNull(split.get("pdfText1"));
            assertTrue(((String) split.get("pdfText")).startsWith("chunk" + i + " "));
        }
    }

    @Test
    void lowerSplitThresholdSplitsShorterText() {
        String word = "x".repeat(1200);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(word + " " + word);
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.asset.getPath()).thenReturn("/content/dam/a/b.pdf");

        AlgoliaRequest unsplit = this.extractWithThreshold(DefaultAlgoliaPdfTextExtractor.RECORD_SIZE_LIMIT);
        assertEquals(1, unsplit.getAlgoliaRecords().size());
        assertTrue(unsplit.getRetainedRecords().isEmpty());

        AlgoliaRequest split = this.extractWithThreshold(1024);
        assertEquals(2, split.getAlgoliaRecords().size());
        assertEquals("/content/dam/a/b.pdf_0", split.getAlgoliaRecords().get(0).getObjectID());
        assertEquals("/content/dam/a/b.pdf_1", split.getAlgoliaRecords().get(1).getObjectID());
    }

    @Test
    void splitHelpTextShowsTheConfiguredThreshold() {
        when(this.config.split_text_threshold_chars()).thenReturn(4096);
        DefaultAlgoliaPdfTextExtractor extractor = new DefaultAlgoliaPdfTextExtractor(
                this.resolverFactory, this.pdfTextExtractor, this.cryptoSupport, null, this.config, null);

        String helpText = extractor.getConfigFields().stream()
                .filter(field -> "splitOversizedRecords".equals(field.getName()))
                .findFirst().orElseThrow().getHelpText();
        assertTrue(helpText.contains("longer than 4096 characters"), helpText);
    }

    private AlgoliaRequest extractWithThreshold(int threshold) {
        when(this.config.split_text_threshold_chars()).thenReturn(threshold);
        DefaultAlgoliaPdfTextExtractor extractor = new DefaultAlgoliaPdfTextExtractor(
                this.resolverFactory, this.pdfTextExtractor, this.cryptoSupport, null, this.config, null);
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/a/b.pdf", null);
        request.addRecord(new AlgoliaRecord("/content/dam/a/b.pdf"));
        request.setExtenderSettings(new ExtenderSettings(Map.of("wordSizeLimit", "1"), List.of()));
        try (MockedStatic<AlgoliaUtil> ignored = mockStatic(AlgoliaUtil.class)) {
            extractor.augmentAlgoliaRequest(request, this.asset);
        }
        return request;
    }

    @Test
    void testBlankWordLimitUsesOsgiValueAndReservedNameFallsBack() {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "wordSizeLimit", "",
                "attributeName", "path"
        ), List.of());
        this.algoliaRecord.addAttribute("path", "/content/dam/a/b.pdf");
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("one two three");
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        assertEquals("one two three", this.algoliaRecord.get("pdfText"));
        assertEquals("/content/dam/a/b.pdf", this.algoliaRecord.get("path"));
    }
}
