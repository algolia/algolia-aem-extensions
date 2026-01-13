package com.algolia.core.extender.internal;

import com.algolia.connector.core.AlgoliaExceptionHandler;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static com.algolia.connector.core.AlgoliaConstants.ATTRIBUTE_PATH;
import static com.algolia.core.extender.internal.DefaultAlgoliaPdfTextExtractor.MIME_TYPE_PDF;
import static com.algolia.core.extender.internal.DefaultAlgoliaPdfTextExtractor.RECORD_SIZE_LIMIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link DefaultAlgoliaPdfTextExtractor}
 *
 * @author Rakesh.Kumar
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlgoliaPdfTextExtractorTest {

    private static final int ALGOLIA_WORDS_LIMIT = 900;
    private static final String ASSET_PATH = "/content/dam/test/document.pdf";
    private static final String OBJECT_ID = "/content/dam/test/document.pdf";

    @Mock
    private PdfTextExtractor pdfTextExtractor;

    @Mock
    private Asset asset;

    private DefaultAlgoliaPdfTextExtractor.Config config;

    @Mock
    private ResourceResolverFactory resolverFactory;

    @Mock
    private Resource resource;

    private AlgoliaRequest request;
    private AlgoliaRecord algoliaRecord;
    private DefaultAlgoliaPdfTextExtractor algoliaPdfTextExtractor;

    @BeforeEach
    void setup() {
        // Create a simple test implementation of Config
        this.config = new DefaultAlgoliaPdfTextExtractor.Config() {
            @Override
            public int word_size_limit() {
                return ALGOLIA_WORDS_LIMIT;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return DefaultAlgoliaPdfTextExtractor.Config.class;
            }
        };
        this.algoliaPdfTextExtractor = new DefaultAlgoliaPdfTextExtractor(
                this.resolverFactory, this.pdfTextExtractor, this.config);
        this.algoliaRecord = new AlgoliaRecord(OBJECT_ID);
        // AlgoliaConfiguration is not used by the extractors, so we can pass null
        this.request = new AlgoliaRequest(this.resource, null);
        this.request.addRecord(this.algoliaRecord);
        lenient().when(this.asset.getPath()).thenReturn(ASSET_PATH);
    }

    @Test
    void testAugmentAlgoliaRequestWhenMimeTypeIsNotPdf() {
        when(this.asset.getMimeType()).thenReturn("image/jpeg");

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verifyNoInteractions(this.pdfTextExtractor);
        assertTrue(this.algoliaRecord.isEmpty() || this.algoliaRecord.size() == 1); // Only objectID
    }

    @Test
    void testAugmentAlgoliaRequestWhenMimeTypeIsNull() {
        when(this.asset.getMimeType()).thenReturn(null);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verifyNoInteractions(this.pdfTextExtractor);
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextIsEmpty() {
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("");

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        assertFalse(this.algoliaRecord.containsKey("pdfText"));
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextIsNull() {
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(null);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        assertFalse(this.algoliaRecord.containsKey("pdfText"));
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextIsWhitespace() {
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("   ");

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        // StringUtils.isNotEmpty("   ") returns true, so whitespace is treated as non-empty
        // WhitespaceTokenizer will tokenize whitespace, potentially resulting in empty tokens
        // If tokenization results in 0 tokens or tokens.length <= wordSizeLimit, 
        // the text will be added as pdfText attribute
        // This test verifies the method handles whitespace input without crashing
        // The actual content depends on tokenization behavior
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextIsSmall() {
        String smallText = "This is a small text from PDF.";
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(smallText);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        assertEquals(smallText, this.algoliaRecord.get("pdfText"));
        assertEquals(2, this.algoliaRecord.size()); // objectID + pdfText
    }

    @Test
    void testAugmentAlgoliaRequestWhenWordCountExceedsLimit() {
        // Create text with exactly 901 words (exceeds limit of 900)
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 901; i++) {
            textBuilder.append("word").append(i).append(" ");
        }
        String largeText = textBuilder.toString().trim();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(largeText);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        // Should have pdfText1 and pdfText2 attributes
        assertTrue(this.algoliaRecord.containsKey("pdfText1"));
        assertTrue(this.algoliaRecord.containsKey("pdfText2"));
        assertFalse(this.algoliaRecord.containsKey("pdfText")); // No single pdfText attribute
    }

    @Test
    void testAugmentAlgoliaRequestWhenWordCountExactlyAtLimit() {
        // Create text with exactly 900 words (at limit)
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            textBuilder.append("word").append(i).append(" ");
        }
        String text = textBuilder.toString().trim();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        // Should have single pdfText attribute (not split)
        assertTrue(this.algoliaRecord.containsKey("pdfText"));
        assertFalse(this.algoliaRecord.containsKey("pdfText1"));
    }

    @Test
    void testAugmentAlgoliaRequestWhenWordCountOneBelowLimit() {
        // Create text with 899 words (one below limit)
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 899; i++) {
            textBuilder.append("word").append(i).append(" ");
        }
        String text = textBuilder.toString().trim();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        assertTrue(this.algoliaRecord.containsKey("pdfText"));
        assertFalse(this.algoliaRecord.containsKey("pdfText1"));
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextExceedsSizeLimit() {
        // Create text that exceeds 10KB size limit
        StringBuilder textBuilder = new StringBuilder();
        // Create text larger than 10KB (RECORD_SIZE_LIMIT)
        int targetSize = RECORD_SIZE_LIMIT + 1000;
        while (textBuilder.length() < targetSize) {
            textBuilder.append("This is a word that will make the text exceed the size limit. ");
        }
        String largeText = textBuilder.toString();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(largeText);

        try (MockedStatic<AlgoliaUtil> algoliaUtilMock = mockStatic(AlgoliaUtil.class)) {
            this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

            // Verify that records were split
            // The original record should be removed and new records added
            assertTrue(this.request.getAlgoliaRecords().size() > 0);
            
            // Verify AlgoliaUtil.handleSplitRecordCount was called
            algoliaUtilMock.verify(() -> AlgoliaUtil.handleSplitRecordCount(
                    anyInt(), eq(ASSET_PATH), eq(AlgoliaExceptionHandler.SplitRecordAction.ADD), eq(this.resolverFactory)));
            
            // Verify setPdfTextSplittingAttempted was called
            assertTrue(this.request.isPdfTextSplittingAttempted());
        }
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextExactlyAtSizeLimit() {
        // Create text exactly at 10KB size limit
        StringBuilder textBuilder = new StringBuilder();
        int targetSize = RECORD_SIZE_LIMIT;
        while (textBuilder.length() < targetSize) {
            textBuilder.append("word ");
        }
        String text = textBuilder.toString();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        // Should not split records (exactly at limit, not exceeding)
        assertFalse(this.request.isPdfTextSplittingAttempted());
    }

    @Test
    void testAugmentAlgoliaRequestWhenTextBelowSizeLimit() {
        // Create text below 10KB
        String text = "This is a small text that is definitely below the 10KB limit.";
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        assertEquals(text, this.algoliaRecord.get("pdfText"));
        assertFalse(this.request.isPdfTextSplittingAttempted());
    }

    @Test
    void testHandleWordsWithMultiplePartitions() {
        // Create text with 2700 words (3 partitions of 900 words each)
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 2700; i++) {
            textBuilder.append("word").append(i).append(" ");
        }
        String text = textBuilder.toString().trim();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        // Should have pdfText1, pdfText2, and pdfText3
        assertTrue(this.algoliaRecord.containsKey("pdfText1"));
        assertTrue(this.algoliaRecord.containsKey("pdfText2"));
        assertTrue(this.algoliaRecord.containsKey("pdfText3"));
        assertFalse(this.algoliaRecord.containsKey("pdfText"));
    }

    @Test
    void testHandleWordsWithRemainderPartition() {
        // Create text with 1801 words (2 full partitions + 1 remainder)
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 1801; i++) {
            textBuilder.append("word").append(i).append(" ");
        }
        String text = textBuilder.toString().trim();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.pdfTextExtractor).extractText(this.asset);
        // Should have pdfText1, pdfText2, and pdfText3 (with remainder)
        assertTrue(this.algoliaRecord.containsKey("pdfText1"));
        assertTrue(this.algoliaRecord.containsKey("pdfText2"));
        assertTrue(this.algoliaRecord.containsKey("pdfText3"));
    }

    @Test
    void testRecordSplittingCreatesCorrectRecords() {
        // Create text that exceeds size limit and word limit
        StringBuilder textBuilder = new StringBuilder();
        int targetSize = RECORD_SIZE_LIMIT + 1000;
        while (textBuilder.length() < targetSize) {
            textBuilder.append("word ");
        }
        String largeText = textBuilder.toString();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(largeText);

        try (MockedStatic<AlgoliaUtil> algoliaUtilMock = mockStatic(AlgoliaUtil.class)) {
            this.algoliaPdfTextExtractor.augmentAlgoliaRequest(this.request, this.asset);

            List<AlgoliaRecord> finalRecords = this.request.getAlgoliaRecords();
            assertTrue(finalRecords.size() > 0);
            
            // Verify each split record has correct structure
            for (AlgoliaRecord record : finalRecords) {
                assertNotNull(record.getObjectID());
                assertTrue(record.getObjectID().startsWith(OBJECT_ID + "_"));
                assertTrue(record.containsKey(ATTRIBUTE_PATH));
                assertEquals(ASSET_PATH, record.get(ATTRIBUTE_PATH));
                // Each record should have at least one pdfText attribute
                boolean hasPdfTextAttribute = false;
                for (Map.Entry<String, Object> entry : record.entrySet()) {
                    if (entry.getKey().startsWith("pdfText")) {
                        hasPdfTextAttribute = true;
                        assertNotNull(entry.getValue());
                        assertTrue(entry.getValue() instanceof String);
                        break;
                    }
                }
                assertTrue(hasPdfTextAttribute, "Record should have at least one pdfText attribute");
            }
        }
    }

    @Test
    void testConfigurationWithCustomWordLimit() {
        int customLimit = 500;
        DefaultAlgoliaPdfTextExtractor.Config customConfig = new DefaultAlgoliaPdfTextExtractor.Config() {
            @Override
            public int word_size_limit() {
                return customLimit;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return DefaultAlgoliaPdfTextExtractor.Config.class;
            }
        };
        DefaultAlgoliaPdfTextExtractor customExtractor = new DefaultAlgoliaPdfTextExtractor(
                this.resolverFactory, this.pdfTextExtractor, customConfig);

        // Create text with 501 words (exceeds custom limit)
        StringBuilder textBuilder = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            textBuilder.append("word").append(i).append(" ");
        }
        String text = textBuilder.toString().trim();
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text);

        customExtractor.augmentAlgoliaRequest(this.request, this.asset);

        // Should split into pdfText1 and pdfText2
        assertTrue(this.algoliaRecord.containsKey("pdfText1"));
        assertTrue(this.algoliaRecord.containsKey("pdfText2"));
    }
}
