package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoException;
import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.TagsParserService;
import com.algolia.connector.core.config.AlgoliaConfiguration;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import com.algolia.connector.core.util.AlgoliaUtil;
import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Page;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests of the LLM enrichment path in {@link DefaultAlgoliaTagsExtractor}.
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlgoliaTagsExtractorLlmTest {

    @Mock
    private TagsParserService tagsParserService;

    @Mock
    private AlgoliaRequest request;

    @Mock
    private Asset asset;

    @Mock
    private Page page;

    @Mock
    private CryptoSupport cryptoSupport;

    @Mock
    private LlmEnricher llmEnricher;

    @Mock
    private AlgoliaConfiguration algoliaConfig;

    private AlgoliaRecord algoliaRecord;
    private List<AlgoliaRecord> records;
    private DefaultAlgoliaTagsExtractor extractor;

    @BeforeEach
    void setup() {
        this.algoliaRecord = new AlgoliaRecord("/content/wknd/us/en");
        this.records = new ArrayList<>();
        this.records.add(this.algoliaRecord);
        this.extractor = new DefaultAlgoliaTagsExtractor(
                this.tagsParserService, this.cryptoSupport, this.llmEnricher);
        lenient().when(this.algoliaConfig.llmProviders()).thenReturn("");
        lenient().doCallRealMethod().when(this.llmEnricher)
                .enrichIfEnabled(any(), any(), any(), any(), any(), any());
        lenient().when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        lenient().when(this.asset.getPath()).thenReturn("/content/dam/a.pdf");
        lenient().when(this.asset.getMetadataValue("dc:title")).thenReturn("Asset title");
        lenient().when(this.page.getPath()).thenReturn("/content/wknd/us/en");
        lenient().when(this.page.getTitle()).thenReturn("Page title");
    }

    // -------------------------------------------------------------------------
    // LLM skipped cases
    // -------------------------------------------------------------------------

    @Test
    void llmSkippedWhenEnableFlagIsFalse() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of("enableLlmEnrichment", "false"), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void llmSkippedWhenConfigIsNull() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "llmProviderId", "prov-1"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(null);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void llmSkippedWhenNoProviderResolved() throws Exception {
        // No llmProviderId and no legacy inline fields → resolver returns empty
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true"
        ), List.of());
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
                "inferenceApiKey", "{bad}"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected(anyString())).thenReturn(true);
        when(this.cryptoSupport.unprotect(anyString())).thenThrow(new CryptoException("bad"));

        assertDoesNotThrow(() -> this.extractor.augmentAlgoliaRequest(this.request, this.asset));
        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void llmSkippedWhenThereIsNoContent() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "plain-key"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.asset.getMetadataValue("dc:title")).thenReturn(null);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher, never()).enrich(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // LLM active cases
    // -------------------------------------------------------------------------

    @Test
    void callsEnricherForAssetWithLegacyFallback() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "plain-key"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected("plain-key")).thenReturn(false);

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher).enrich(
                eq(this.request),
                eq("/content/dam/a.pdf"),
                anyString(),
                eq("https://api.openai.com/v1"),
                eq("gpt-4o-mini"),
                eq("plain-key"),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    void callsEnricherForPageWithLegacyFallback() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "plain-key"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected("plain-key")).thenReturn(false);

        this.extractor.augmentAlgoliaRequest(this.request, this.page);

        verify(this.llmEnricher).enrich(
                eq(this.request),
                eq("/content/wknd/us/en"),
                anyString(),
                eq("https://api.openai.com/v1"),
                eq("gpt-4o-mini"),
                eq("plain-key"),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    void callsEnricherWithProviderIdResolution() throws Exception {
        String providersJson = "[{\"id\":\"prov-1\",\"name\":\"OpenAI\",\"inferenceUrl\":\"https://api.openai.com/v1\","
                + "\"model\":\"gpt-4o-mini\",\"inferenceApiKey\":\"{enc}sk\"}]";
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "llmProviderId", "prov-1"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.algoliaConfig.llmProviders()).thenReturn(providersJson);
        when(this.cryptoSupport.isProtected("{enc}sk")).thenReturn(true);
        when(this.cryptoSupport.unprotect("{enc}sk")).thenReturn("sk-real");

        this.extractor.augmentAlgoliaRequest(this.request, this.asset);

        verify(this.llmEnricher).enrich(
                eq(this.request),
                eq("/content/dam/a.pdf"),
                anyString(),
                eq("https://api.openai.com/v1"),
                eq("gpt-4o-mini"),
                eq("sk-real"),
                anyString(),
                anyString(),
                anyString());
    }

    @Test
    void tagsStillIndexedWhenLlmFails() throws Exception {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "plain-key"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getConfig()).thenReturn(this.algoliaConfig);
        when(this.cryptoSupport.isProtected("plain-key")).thenReturn(false);
        doAnswer(inv -> {
            AlgoliaRecord record = inv.getArgument(1);
            record.addAttribute("topic", List.of("News"));
            return null;
        }).when(this.tagsParserService).parse(any(), any(), anyString());
        doThrow(new IOException("timeout")).when(this.llmEnricher)
                .enrich(any(), anyString(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> this.extractor.augmentAlgoliaRequest(this.request, this.asset));
        assertEquals(List.of("News"), this.algoliaRecord.get("topic"));
        assertFalse(this.algoliaRecord.containsKey("tagClassification"));
    }

    @Test
    void contentPropertiesFieldIsDeclaredForAuthors() {
        List<ExtenderConfigField> fields = this.extractor.getConfigFields();
        ExtenderConfigField contentProperties = fields.stream()
                .filter(field -> "llmContentProperties".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("Content Properties", contentProperties.getLabel());
        assertTrue(contentProperties.isAuthorOnly());
    }

    @Test
    void testConnectionUsesEnricher() throws Exception {
        this.extractor.testConnection("https://api.openai.com/v1", "gpt-4o-mini", "sk-real");

        verify(this.llmEnricher).probe("https://api.openai.com/v1", "gpt-4o-mini", "sk-real");
    }

    @Test
    void fullEnrichmentWithRealEnricher() throws Exception {
        ChatCompletionsClient client = mock(ChatCompletionsClient.class);
        when(client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"article\",\"summary\":\"A summary.\","
                        + "\"topics\":[\"tech\"],\"keywords\":[\"aem\"],"
                        + "\"language\":\"en\",\"documentType\":\"blog\"}");
        DefaultAlgoliaTagsExtractor realExtractor = new DefaultAlgoliaTagsExtractor(
                this.tagsParserService, this.cryptoSupport, LlmEnricher.forTags(client, 8000));

        AlgoliaConfiguration realConfig = mock(AlgoliaConfiguration.class);
        lenient().when(realConfig.llmProviders()).thenReturn("");
        AlgoliaRequest realRequest = new AlgoliaRequest("/content/wknd/us/en", realConfig);
        AlgoliaRecord record = new AlgoliaRecord("/content/wknd/us/en");
        realRequest.addRecord(record);
        realRequest.setExtenderSettings(new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "gpt-4o-mini",
                "inferenceApiKey", "{enc}"
        ), List.of()));
        when(this.asset.getPath()).thenReturn("/content/wknd/us/en");

        try (MockedStatic<AlgoliaUtil> util = mockStatic(AlgoliaUtil.class)) {
            util.when(() -> AlgoliaUtil.unprotectText("{enc}", this.cryptoSupport)).thenReturn("sk-real");
            realExtractor.augmentAlgoliaRequest(realRequest, this.asset);
        }

        assertEquals("article", record.get("tagClassification"));
        assertEquals(List.of("tech"), record.get("tagTopics"));
        assertEquals(List.of("aem"), record.get("tagKeywords"));
        verify(client, times(1)).chat(eq("https://api.openai.com/v1"), eq("gpt-4o-mini"), eq("sk-real"), anyList());
    }

    @Test
    void enrichmentIsWrittenOntoTheRecordBulkIndexingSavesAfterPdfSplit() throws Exception {
        ChatCompletionsClient client = mock(ChatCompletionsClient.class);
        when(client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"Technical Data Sheet\",\"summary\":\"A coating.\","
                        + "\"topics\":[\"Industrial Coatings\"],\"keywords\":[\"urethane\"],"
                        + "\"language\":\"en\",\"documentType\":\"Product Data Sheet\"}");
        DefaultAlgoliaTagsExtractor realExtractor = new DefaultAlgoliaTagsExtractor(
                this.tagsParserService, this.cryptoSupport, LlmEnricher.forTags(client, 8000));

        AlgoliaConfiguration realConfig = mock(AlgoliaConfiguration.class);
        lenient().when(realConfig.llmProviders()).thenReturn("");
        AlgoliaRequest realRequest = new AlgoliaRequest("/content/dam/doc.pdf", realConfig);
        AlgoliaRecord original = new AlgoliaRecord("/content/dam/doc.pdf");
        original.addAttribute("title", "Resutile HPS 100.pdf");
        AlgoliaRecord split = new AlgoliaRecord("/content/dam/doc.pdf_0");
        realRequest.addRecord(original);
        realRequest.retainRecord(original);
        realRequest.getAlgoliaRecords().clear();
        realRequest.addRecord(split);
        realRequest.setExtenderSettings(new ExtenderSettings(Map.of(
                "enableLlmEnrichment", "true",
                "attributePrefix", "tags_",
                "inferenceUrl", "https://api.openai.com/v1",
                "model", "medium",
                "inferenceApiKey", "{enc}"
        ), List.of()));
        when(this.asset.getPath()).thenReturn("/content/dam/doc.pdf");
        when(this.asset.getMetadataValue("dc:title")).thenReturn("Resutile");

        try (MockedStatic<AlgoliaUtil> util = mockStatic(AlgoliaUtil.class)) {
            util.when(() -> AlgoliaUtil.unprotectText("{enc}", this.cryptoSupport)).thenReturn("sk-real");
            realExtractor.augmentAlgoliaRequest(realRequest, this.asset);
        }

        assertEquals("Technical Data Sheet", original.get("tags_tagClassification"));
        assertEquals("A coating.", original.get("tags_tagSummary"));
        assertEquals(List.of("Industrial Coatings"), original.get("tags_tagTopics"));
        assertEquals("Technical Data Sheet", split.get("tags_tagClassification"));
    }

    @Test
    void contentHasTitleDescriptionAndRecordTextButNoIdentityAttributes() {
        AlgoliaRecord record = new AlgoliaRecord("/content/wknd/us/en");
        record.addAttribute("path", "/content/wknd/us/en");
        record.addAttribute("topic", List.of("News", "Sports"));
        record.addAttribute("count", 3);

        String content = DefaultAlgoliaTagsExtractor.buildContent(" Home ", "", record);

        assertEquals("Title: Home\ntopic: News, Sports", content);
    }

    @Test
    void contentUsesOnlyNamedRecordAndNestedProperties() {
        AlgoliaRecord record = new AlgoliaRecord("/content/ambetter-national/en_us/welcome-packet-redirects/perks-redirects/myperkswa");
        record.addAttribute("title", "MyPerksWA - Redirect");
        record.addAttribute("product", "commercial:commercial");
        record.addAttribute("state", "Alabama:AL");
        record.addAttribute("language", "English");
        record.addAttribute("meta", Map.of("jcr_title", "MyPerksWA - Redirect"));

        String content = DefaultAlgoliaTagsExtractor.buildContent(
                "MyPerksWA - Redirect", null, record, List.of("title", "product", "state", "meta.jcr_title"), null);

        assertEquals("title: MyPerksWA - Redirect\nproduct: commercial:commercial\nstate: Alabama:AL\nmeta.jcr_title: MyPerksWA - Redirect",
                content);
    }

    @Test
    void contentFallsBackToJcrWhenRecordDoesNotHaveTheProperty() {
        AlgoliaRecord record = new AlgoliaRecord("/content/page");
        org.apache.sling.api.resource.Resource resource = mock(org.apache.sling.api.resource.Resource.class);
        org.apache.sling.api.resource.Resource contentResource = mock(org.apache.sling.api.resource.Resource.class);
        org.apache.sling.api.resource.ValueMap values = mock(org.apache.sling.api.resource.ValueMap.class);
        when(resource.getValueMap()).thenReturn(org.apache.sling.api.resource.ValueMap.EMPTY);
        when(resource.getChild("jcr:content")).thenReturn(contentResource);
        when(contentResource.getValueMap()).thenReturn(values);
        when(values.get("jcr:description")).thenReturn("Welcome packet perks");
        when(contentResource.getChild("metadata")).thenReturn(null);

        String content = DefaultAlgoliaTagsExtractor.buildContent(
                null, null, record, List.of("jcr:description", "missing"), resource);

        assertEquals("jcr:description: Welcome packet perks", content);
    }
}
