package com.algolia.core.extender.internal;

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

    private AlgoliaRecord algoliaRecord;

    private List<AlgoliaRecord> records;

    private DefaultAlgoliaPdfTextExtractor algoliaPdfTextExtractor;

    @BeforeEach
    void setup() {
        when(this.config.word_size_limit()).thenReturn(ALGOLIA_WORDS_LIMIT);
        this.algoliaPdfTextExtractor = new DefaultAlgoliaPdfTextExtractor(this.resolverFactory, this.pdfTextExtractor, this.config);
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
        for (int i = 0; i < 12; i++) {
            assertEquals("/content/dam/a/b.pdf_" + i, split.get(i).getObjectID());
            assertTrue(((String) split.get(i).get("pdfText")).startsWith("chunk" + i + " "));
            assertEquals("/content/dam/a/b.pdf", split.get(i).get("path"));
        }
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
