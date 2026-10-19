package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoException;
import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.PdfTextExtractor;
import com.algolia.connector.core.config.AlgoliaConfiguration;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.algolia.core.extender.internal.DefaultAlgoliaPdfTextExtractor.MIME_TYPE_PDF;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests of the LLM enrichment path in {@link DefaultAlgoliaPdfTextExtractor}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlgoliaPdfTextExtractorLlmTest {

    @Mock
    private PdfTextExtractor pdfTextExtractor;

    @Mock
    private AlgoliaRequest request;

    @Mock
    private Asset asset;

    @Mock
    private DefaultAlgoliaPdfTextExtractor.Config config;

    @Mock
    private CryptoSupport cryptoSupport;

    @Mock
    private LlmEnricher llmEnricher;

    @Mock
    private AlgoliaConfiguration algoliaConfig;

    private AlgoliaRecord algoliaRecord;
    private List<AlgoliaRecord> records;
    private DefaultAlgoliaPdfTextExtractor extractor;

    @BeforeEach
    void setup() throws Exception {
        when(this.config.word_size_limit()).thenReturn(900);
        when(this.config.split_text_threshold_chars()).thenReturn(DefaultAlgoliaPdfTextExtractor.RECORD_SIZE_LIMIT);
        this.algoliaRecord = spy(new AlgoliaRecord("/content/dam/a.pdf"));
        this.records = new ArrayList<>();
        this.records.add(this.algoliaRecord);
        this.extractor = new DefaultAlgoliaPdfTextExtractor(
                mock(ResourceResolverFactory.class), this.pdfTextExtractor,
                this.cryptoSupport, this.config, this.llmEnricher);
        // Default: config has no providers (triggers legacy fallback)
        lenient().when(this.algoliaConfig.llmProviders()).thenReturn("");
        lenient().doCallRealMethod().when(this.llmEnricher)
                .enrichIfEnabled(any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Existing tests updated for the provider-resolver approach
    // (legacy fallback active: inline credentials in ExtenderSettings)
    // -------------------------------------------------------------------------

    @Test
    void llmSkippedWhenEnableFlagIsFalse() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "false",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o",
                "inferenceApiKey", "enc-key"
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void llmSkippedWhenConfigIsNull() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o",
                "inferenceApiKey", "enc-key"
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(null);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void llmSkippedWhenNoProviderResolved() throws Exception {
        // Legacy fallback requires all three inline fields. Here model is missing, so resolver returns empty.
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1"
                // model and inferenceApiKey absent → legacy fallback returns empty
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void llmSkippedWhenDecryptionFails() throws Exception {
        // Legacy fallback path (inline credentials)
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o",
                "inferenceApiKey", "{bad-ciphertext}"
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected(anyString())).thenReturn(true);
        when(this.cryptoSupport.unprotect(anyString())).thenThrow(new CryptoException("bad key"));

        // Should not throw; just skip enrichment
        assertDoesNotThrow(() -> this.extractor.augmentAlgoliaRequest(this.request, this.asset));
        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void pdfTextStillPresentWhenLlmFails() throws Exception {
        // Legacy fallback path (inline credentials)
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o",
                "inferenceApiKey", "plaintext-key"
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected("plaintext-key")).thenReturn(false);
        when(this.asset.getPath()).thenReturn("/content/dam/a.pdf");
        doThrow(new IOException("timeout")).when(this.llmEnricher)
                .enrich(any(), anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> this.extractor.augmentAlgoliaRequest(this.request, this.asset));
        verify(this.llmEnricher).enrich(any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        assertEquals("some text", this.algoliaRecord.get("pdfText"));
    }

    @Test
    void splitRecordsAllReceiveEnrichment() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            text.append("chunk").append(i).append(' ').append("x".repeat(5000)).append(' ');
        }
        ChatCompletionsClient client = mock(ChatCompletionsClient.class);
        when(client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"guide\",\"summary\":\"S.\",\"topics\":[\"t\"],"
                        + "\"keywords\":[\"k\"],\"language\":\"en\",\"documentType\":\"report\"}");
        DefaultAlgoliaPdfTextExtractor withClient = new DefaultAlgoliaPdfTextExtractor(
                mock(ResourceResolverFactory.class), this.pdfTextExtractor, this.cryptoSupport, this.config,
                LlmEnricher.forPdf(client, 12000));
        // Use inline credentials via legacy fallback (empty config → no providers)
        AlgoliaConfiguration realConfig = mock(AlgoliaConfiguration.class);
        lenient().when(realConfig.llmProviders()).thenReturn("");
        AlgoliaRequest realRequest = new AlgoliaRequest("/content/dam/a.pdf", realConfig);
        AlgoliaRecord original = new AlgoliaRecord("/content/dam/a.pdf");
        realRequest.addRecord(original);
        realRequest.setExtenderSettings(new ExtenderSettings(Map.of(
                "wordSizeLimit", "2",
                "attributePrefix", "doc_",
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "{enc}"
        ), List.of()));
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.asset.getPath()).thenReturn("/content/dam/a.pdf");
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn(text.toString().trim());

        try (MockedStatic<AlgoliaUtil> util = mockStatic(AlgoliaUtil.class)) {
            util.when(() -> AlgoliaUtil.unprotectText("{enc}", this.cryptoSupport)).thenReturn("sk-real");
            withClient.augmentAlgoliaRequest(realRequest, this.asset);
        }

        List<AlgoliaRecord> split = realRequest.getAlgoliaRecords();
        assertEquals(3, split.size());
        for (AlgoliaRecord record : split) {
            assertEquals("guide", record.get("doc_" + "pdfClassification"));
            assertEquals(List.of("k"), record.get("doc_" + "pdfKeywords"));
        }
        // Bulk indexing saves the original record object, not the split records on the request.
        assertEquals("guide", original.get("doc_" + "pdfClassification"));
        verify(client, times(1)).chat(eq("https://api.openai.com/v1"), eq("gpt-4o-mini"), eq("sk-real"), anyList());
    }

    @Test
    void testConnectionUsesTheEnricher() throws Exception {
        this.extractor.testConnection("https://api.openai.com/v1", "gpt-4o-mini", "sk-real");

        verify(this.llmEnricher).probe("https://api.openai.com/v1", "gpt-4o-mini", "sk-real");
    }

    @Test
    void callsEnricherWithFullTextAndPlaintextKeyViaLegacyFallback() throws Exception {
        // Legacy fallback: inline credentials in ExtenderSettings (no llmProviderId)
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "{enc}sk-real"
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected("{enc}sk-real")).thenReturn(true);
        when(this.cryptoSupport.unprotect("{enc}sk-real")).thenReturn("sk-real");
        when(this.asset.getPath()).thenReturn("/content/dam/a.pdf");

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher).enrich(
                eq(this.request),
                eq("/content/dam/a.pdf"),
                eq("some text"),
                eq("https://api.openai.com/v1"),
                eq("gpt-4o-mini"),
                eq("sk-real"),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    void callsEnricherWithProviderIdResolution() throws Exception {
        // New style: llmProviderId in ExtenderSettings, resolved against the config's provider list
        String providersJson = "[{\"id\":\"prov-1\",\"name\":\"OpenAI\",\"inferenceUrl\":\"https://api.openai.com/v1\","
                + "\"model\":\"gpt-4o-mini\",\"inferenceApiKey\":\"{enc}sk-real\"}]";
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "llmProviderId", "prov-1"
        ), List.of());
        when(this.asset.getMimeType()).thenReturn(MIME_TYPE_PDF);
        when(this.pdfTextExtractor.extractText(this.asset)).thenReturn("some text");
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.algoliaConfig.llmProviders()).thenReturn(providersJson);
        when(this.cryptoSupport.isProtected("{enc}sk-real")).thenReturn(true);
        when(this.cryptoSupport.unprotect("{enc}sk-real")).thenReturn("sk-real");
        when(this.asset.getPath()).thenReturn("/content/dam/a.pdf");

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher).enrich(
                eq(this.request),
                eq("/content/dam/a.pdf"),
                eq("some text"),
                eq("https://api.openai.com/v1"),
                eq("gpt-4o-mini"),
                eq("sk-real"),
                anyString(),
                anyString(),
                anyString());
    }
}
