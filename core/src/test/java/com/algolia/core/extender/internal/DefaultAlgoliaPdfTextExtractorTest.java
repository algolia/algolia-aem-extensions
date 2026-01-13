package com.algolia.core.extender.internal;

import com.algolia.connector.core.AlgoliaExceptionHandler;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.day.cq.dam.api.Asset;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static com.algolia.core.extender.internal.DefaultAlgoliaPdfTextExtractor.MIME_TYPE_PDF;
import static org.mockito.ArgumentMatchers.any;
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
}
