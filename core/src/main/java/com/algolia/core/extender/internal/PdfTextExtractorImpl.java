package com.algolia.core.extender.internal;

import com.algolia.connector.core.PdfTextExtractor;
import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * PDF text extractor implementation based on Apache PDFBox.
 *
 * @author Rakesh Kumar
 * @since 3.5.0
 */
@Component
public class PdfTextExtractorImpl implements PdfTextExtractor {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    @Override
    public String extractText(Asset asset) {
        Rendition original = asset.getOriginal();
        if (original == null) {
            LOGGER.warn("Could not extract PDF text as original rendition is null for asset({})", asset.getPath());
            return null;
        }
        InputStream stream = original.getStream();
        if (stream == null) {
            LOGGER.warn("DAM Asset({}) original rendition returned null InputStream!", asset.getPath());
            return null;
        }
        long startTime = System.nanoTime();
        String text = null;
        try (PDDocument document = Loader.loadPDF(IOUtils.toByteArray(stream))) {
            // Get full document.
            text = new PDFTextStripper().getText(document);
            long endTime = NANOSECONDS.toMillis(System.nanoTime() - startTime);
            LOGGER.info("PDF text extraction took ({}) ms!", endTime);
        } catch (Exception ex) { // NOSONAR
            LOGGER.error("Exception while extracting text from DAM Asset.", ex);
        } finally {
            try {
                stream.close();
            } catch (IOException ioe) {
                LOGGER.error("Exception while closing input stream!", ioe);
            }
        }
        return text;
    }
}
