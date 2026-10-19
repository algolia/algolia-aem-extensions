package com.algolia.core.extender.internal;

import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmEnricherTest {

    @Mock
    private ChatCompletionsClient client;

    private LlmEnricher enricher;

    @BeforeEach
    void setup() {
        this.enricher = LlmEnricher.forPdf(this.client, 12000);
    }

    // ─── Prompt building ──────────────────────────────────────────────────────

    @Test
    void replacesPlaceholderInUserPrompt() {
        String result = this.enricher.buildUserMessage("Analyze: {{pdfText}}", "hello world");
        assertEquals("Analyze: hello world", result);
    }

    @Test
    void appendsTextWhenPlaceholderAbsent() {
        String result = this.enricher.buildUserMessage("Analyze this document.", "hello world");
        assertTrue(result.endsWith("hello world"));
        assertTrue(result.startsWith("Analyze this document."));
    }

    @Test
    void defaultSystemPromptWritesEnrichmentInTheSourceLanguage() {
        String prompt = LlmEnricher.DEFAULT_SYSTEM_PROMPT;
        assertTrue(prompt.contains("dominant language of the main text"));
        assertTrue(prompt.contains("ignoring field labels, attribute names, tag names"));
        assertTrue(prompt.contains("Do not translate them into English or any other language"));
        assertTrue(prompt.contains("ISO 639-1 code of that language"));
    }

    @Test
    void usesDefaultPromptWhenBlank() {
        String result = this.enricher.buildUserMessage("", "text");
        assertTrue(result.contains("text"));
    }

    // ─── Parsing ─────────────────────────────────────────────────────────────

    @Test
    void parsesFullReply() {
        String json = "{\"classification\":\"policy\",\"summary\":\"A summary.\","
                + "\"topics\":[\"retirement\"],\"keywords\":[\"401k\"],"
                + "\"language\":\"en\",\"documentType\":\"disclosure\"}";
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse(json);
        assertEquals("policy", result.classification);
        assertEquals("A summary.", result.summary);
        assertEquals(List.of("retirement"), result.topics);
        assertEquals(List.of("401k"), result.keywords);
        assertEquals("en", result.language);
        assertEquals("disclosure", result.documentType);
    }

    @Test
    void capsTheLengthOfModelOutput() {
        String longText = "x".repeat(5000);
        String json = "{\"classification\":\"" + longText + "\",\"summary\":\"" + longText + "\","
                + "\"topics\":[\"" + longText + "\"],\"documentType\":\"" + longText + "\"}";
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse(json);
        assertEquals(256, result.classification.length());
        assertEquals(2000, result.summary.length());
        assertEquals(256, result.topics.get(0).length());
        assertEquals(256, result.documentType.length());
    }

    @Test
    void keepsOnlyLanguageCodes() {
        assertEquals("en-US", LlmEnricher.parse("{\"language\":\"en-US\"}").language);
        assertEquals("fil", LlmEnricher.parse("{\"language\":\"fil\"}").language);
        assertNull(LlmEnricher.parse("{\"language\":\"English\"}").language);
        assertNull(LlmEnricher.parse("{\"language\":\"The document is in French.\"}").language);
    }

    @Test
    void returnsEmptyResultForInvalidJson() {
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse("not json");
        assertNull(result.classification);
        assertNull(result.summary);
        assertTrue(result.topics.isEmpty());
        assertTrue(result.keywords.isEmpty());
    }

    @Test
    void toleratesNullFields() {
        String json = "{\"classification\":null,\"summary\":\"\",\"topics\":[],\"keywords\":[]}";
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse(json);
        assertNull(result.classification);
        assertNull(result.summary);
        assertTrue(result.topics.isEmpty());
    }

    @Test
    void capsArraysAt20Entries() {
        StringBuilder json = new StringBuilder("{\"topics\":[");
        for (int i = 0; i < 25; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("\"t").append(i).append('"');
        }
        json.append("]}");
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse(json.toString());
        assertEquals(20, result.topics.size());
    }

    @Test
    void deduplicatesArrayEntries() {
        String json = "{\"topics\":[\"a\",\"b\",\"a\"]}";
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse(json);
        assertEquals(2, result.topics.size());
    }

    // ─── Happy path ───────────────────────────────────────────────────────────

    @Test
    void writesAttributesToAllRecords() throws IOException {
        String json = "{\"classification\":\"guide\",\"summary\":\"Short.\","
                + "\"topics\":[\"topic1\"],\"keywords\":[\"kw1\"],"
                + "\"language\":\"en\",\"documentType\":\"report\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/content/dam/a.pdf", null);
        AlgoliaRecord r1 = new AlgoliaRecord("/content/dam/a.pdf");
        AlgoliaRecord r2 = new AlgoliaRecord("/content/dam/a.pdf_0");
        request.addRecord(r1);
        request.addRecord(r2);

        this.enricher.enrich(request, "/content/dam/a.pdf", "some text",
                "https://8.8.8.8/v1", "m", "key", "", "", "");

        // Both records get the attributes
        assertEquals("guide", r1.get("pdfClassification"));
        assertEquals("guide", r2.get("pdfClassification"));
        assertEquals("Short.", r1.get("pdfSummary"));
        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) r1.get("pdfTopics");
        assertEquals(List.of("topic1"), topics);
    }

    @Test
    void appliesPrefixToAllAttributes() throws IOException {
        String json = "{\"classification\":\"policy\",\"summary\":\"S.\","
                + "\"topics\":[],\"keywords\":[],\"language\":\"en\",\"documentType\":\"d\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/dam/a.pdf");
        request.addRecord(record);

        this.enricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key", "", "", "doc_");

        assertTrue(record.containsKey("doc_" + "pdfClassification"));
        assertFalse(record.containsKey("pdfClassification"));
    }

    @Test
    void doesNotOverwriteExistingAttributes() throws IOException {
        String json = "{\"classification\":\"new\",\"summary\":\"S.\","
                + "\"topics\":[],\"keywords\":[],\"language\":\"en\",\"documentType\":\"d\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/dam/a.pdf");
        record.addAttribute("pdfClassification", "existing");
        request.addRecord(record);

        this.enricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key", "", "", "");

        assertEquals("existing", record.get("pdfClassification"));
    }

    // ─── Error handling ───────────────────────────────────────────────────────

    @Test
    void propagatesIoException() throws IOException {
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new IOException("network error"));

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        request.addRecord(new AlgoliaRecord("/dam/a.pdf"));

        assertThrows(IOException.class, () ->
                this.enricher.enrich(request, "/dam/a.pdf", "text",
                        "https://8.8.8.8/v1", "m", "key", "", "", ""));
    }

    @Test
    void truncatesInputToMaxChars() throws IOException {
        String longText = "a".repeat(20000);
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"c\",\"summary\":\"s.\",\"topics\":[],"
                        + "\"keywords\":[],\"language\":\"en\",\"documentType\":\"d\"}");

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        request.addRecord(new AlgoliaRecord("/dam/a.pdf"));

        this.enricher.enrich(request, "/dam/a.pdf", longText, "https://8.8.8.8/v1", "m", "key", "", "{{pdfText}}", "");

        ArgumentCaptor<List<ChatCompletionsClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(this.client).chat(anyString(), anyString(), anyString(), messages.capture());
        assertEquals("system", messages.getValue().get(0).getRole());
        assertEquals(LlmEnricher.DEFAULT_SYSTEM_PROMPT, messages.getValue().get(0).getContent());
        assertEquals(12000, messages.getValue().get(1).getContent().length());
    }

    @Test
    void probeSendsAShortCompletion() throws IOException {
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn("{\"ok\":true}");

        this.enricher.probe("https://api.openai.com/v1", "gpt-4o-mini", "sk");

        verify(this.client).chat(eq("https://api.openai.com/v1"), eq("gpt-4o-mini"), eq("sk"), anyList());
    }

    @Test
    void probeReportsARejectedUrlAsIoFailure() throws IOException {
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenThrow(new IllegalArgumentException("The inference host is not in the list of allowed hosts."));

        IOException failure = assertThrows(IOException.class, () ->
                this.enricher.probe("https://evil.example/v1", "m", "sk"));
        assertEquals("The inference host is not in the list of allowed hosts.", failure.getMessage());
    }

    @Test
    void dropsValuesOfTheWrongType() {
        String json = "{\"classification\":42,\"summary\":{\"text\":\"x\"},\"topics\":\"single\","
                + "\"keywords\":[\"ok\",7,null],\"language\":\"en\"}";
        LlmEnricher.EnrichmentResult result = LlmEnricher.parse(json);
        assertNull(result.classification);
        assertNull(result.summary);
        assertTrue(result.topics.isEmpty());
        assertEquals(List.of("ok"), result.keywords);
        assertEquals("en", result.language);
    }

    @Test
    void tagsEnricherUsesItsOwnPlaceholderAndAttributeNames() throws IOException {
        LlmEnricher tags = LlmEnricher.forTags(this.client, 8000);
        assertEquals("Content: hello", tags.buildUserMessage("Content: {{content}}", "hello"));
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"article\",\"documentType\":\"blog\"}");
        AlgoliaRequest request = new AlgoliaRequest("/content/site/en", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/site/en");
        request.addRecord(record);

        tags.enrich(request, "/content/site/en", "text", "https://8.8.8.8/v1", "m", "key", "", "", "my_");

        assertEquals("article", record.get("my_tagClassification"));
        assertEquals("blog", record.get("my_tagDocumentType"));
        assertEquals("en", record.get("my_tagLanguage"));
        assertFalse(record.containsKey("my_pdfClassification"));
    }

    @Test
    void tagsDefaultToEnglishWhenTheRecordHasNoLanguage() throws IOException {
        LlmEnricher tags = LlmEnricher.forTags(this.client, 8000);
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"Ficha técnica\",\"summary\":\"El documento.\","
                        + "\"topics\":[\"Materiales\"],\"keywords\":[\"Sherwin\"],"
                        + "\"language\":\"es\",\"documentType\":\"Documento técnico\"}");
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf");
        record.addAttribute("title", "Resutile HPS 100.pdf");
        record.addAttribute("dc_title", "Resutile HPS 100.indd - Sherwin");
        request.addRecord(record);

        tags.enrich(request, "/content/dam/doc.pdf", "Title: Resutile HPS 100.pdf",
                "https://8.8.8.8/v1", "m", "key", "", "", "");

        ArgumentCaptor<List<ChatCompletionsClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(this.client).chat(anyString(), anyString(), anyString(), messages.capture());
        assertTrue(messages.getValue().get(0).getContent().contains("already set to \"en\""));
        assertEquals("en", record.get("tagLanguage"));
    }

    @Test
    void tagsUseTheLanguageAlreadySetOnTheRecord() throws IOException {
        LlmEnricher tags = LlmEnricher.forTags(this.client, 8000);
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"classification\":\"Technical Data Sheet\",\"summary\":\"A coating.\","
                        + "\"language\":\"en\",\"documentType\":\"Product Data Sheet\"}");
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf");
        record.addAttribute("pdfLanguage", "es");
        record.addAttribute("tagLanguage", "fr");
        request.addRecord(record);

        tags.enrich(request, "/content/dam/doc.pdf", "content",
                "https://8.8.8.8/v1", "m", "key", "", "", "");

        ArgumentCaptor<List<ChatCompletionsClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(this.client).chat(anyString(), anyString(), anyString(), messages.capture());
        String system = messages.getValue().get(0).getContent();
        assertTrue(system.contains("already set to \"es\""));
        assertFalse(system.contains("already set to \"fr\""));
        assertEquals("fr", record.get("tagLanguage"));
    }

    @Test
    void tagsUseEnglishTextAnEarlierExtenderWrote() {
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf_2");
        record.addAttribute("title", "Resutile HPS 100.pdf");
        record.addAttribute("pdfSummary", "This document provides detailed technical specifications and application instructions for the coating.");
        record.addAttribute("pdfText", "The coating is applied with a roller and the floor must be clean.");
        request.addRecord(record);

        LlmEnricher.LanguageChoice choice = LlmEnricher.chooseLanguage(request);

        assertEquals("en", choice.code);
        assertEquals("recordText", choice.source);
    }

    @Test
    void tagsUseSpanishTextWhenNoLanguageAttributeIsSet() {
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf");
        record.addAttribute("pdfSummary", "El documento es una referencia técnica para el producto y para el sistema.");
        request.addRecord(record);

        LlmEnricher.LanguageChoice choice = LlmEnricher.chooseLanguage(request);

        assertEquals("es", choice.code);
        assertEquals("recordText", choice.source);
    }

    @Test
    void pdfLanguageWinsOverOtherRecordText() {
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf_2");
        record.addAttribute("pdfLanguage", "en");
        record.addAttribute("pdfSummary", "El documento es una referencia técnica para el producto y para el sistema.");
        record.addAttribute("tagLanguage", "es");
        request.addRecord(record);

        LlmEnricher.LanguageChoice choice = LlmEnricher.chooseLanguage(request);

        assertEquals("en", choice.code);
        assertEquals("pdfLanguage", choice.source);
    }

    @Test
    void authoredLanguageWinsOverPdfLanguage() {
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf");
        record.addAttribute("pdfLanguage", "en");
        record.addAttribute("jcr:language", "fr");
        request.addRecord(record);

        LlmEnricher.LanguageChoice choice = LlmEnricher.chooseLanguage(request);

        assertEquals("fr", choice.code);
        assertEquals("namedLanguage", choice.source);
    }

    @Test
    void languageNamesAndShortTitlesFallBackToEnglish() {
        AlgoliaRequest request = new AlgoliaRequest("/content/page", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/page");
        record.addAttribute("language", "English");
        record.addAttribute("title", "Resutile HPS 100.indd - Sherwin");
        request.addRecord(record);

        LlmEnricher.LanguageChoice choice = LlmEnricher.chooseLanguage(request);

        assertEquals("en", choice.code);
        assertEquals("default", choice.source);
    }
}
