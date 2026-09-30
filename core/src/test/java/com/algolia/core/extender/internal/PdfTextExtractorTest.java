package com.algolia.core.extender.internal;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link PdfTextExtractorImpl}
 *
 * @author Rakesh.Kumar
 */
@ExtendWith(MockitoExtension.class)
public class PdfTextExtractorTest {

    @Mock
    private Asset asset;

    @Mock
    private Rendition original;

    @Mock
    private InputStream stream;

    @Mock
    private PDDocument document;

    private byte[] data;

    private PdfTextExtractorImpl pdfTextExtractor;

    @BeforeEach
    void setup() {
        this.data = new byte[0];
        this.pdfTextExtractor = new PdfTextExtractorImpl();
    }

    @Test
    void testWhenRenditionIsNull() {
        assertNull(this.pdfTextExtractor.extractText(this.asset));
    }

    @Test
    void testWhenRenditionHasNotData() {
        when(this.asset.getOriginal()).thenReturn(this.original);
        assertNull(this.pdfTextExtractor.extractText(this.asset));
    }

    @Test
    void testPdfExtraction() {
        try (MockedStatic<Loader> mockLoader = mockStatic(Loader.class);
             MockedStatic<IOUtils> mockIOUtils = mockStatic(IOUtils.class)) {
            mockIOUtils.when(() -> IOUtils.toByteArray(this.stream)).thenReturn(this.data);
            mockLoader.when(() -> Loader.loadPDF(IOUtils.toByteArray(this.stream))).thenReturn(this.document);
            PDPageTree pdPageTree = mock(PDPageTree.class);
            List<PDPage> pages = new ArrayList<>();
            PDPage pdPage = mock(PDPage.class);
            pages.add(pdPage);
            when(pdPageTree.iterator()).thenReturn(pages.iterator());
            when(this.document.getPages()).thenReturn(pdPageTree);
            when(this.original.getStream()).thenReturn(this.stream);
            when(this.asset.getOriginal()).thenReturn(this.original);
            assertNotNull(this.pdfTextExtractor.extractText(this.asset));
        }
    }
}
