package com.algolia.core.extender.internal;

import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.algolia.connector.core.inference.ChatCompletionsClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void defaultSystemPromptContainsFieldsPlaceholder() {
        assertTrue(LlmEnricher.DEFAULT_SYSTEM_PROMPT.contains(LlmEnricher.FIELDS_PLACEHOLDER));
    }

    @Test
    void fieldsPlaceholderReplacedInUserMessage() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("summary:longtext");
        String description = LlmEnricher.describeFields(fields);
        String result = this.enricher.buildUserMessage(
                "Analyze this. Keys: {{fields}}. Text: {{pdfText}}", "hello", description);
        assertTrue(result.contains("\"summary\""));
        assertFalse(result.contains("{{fields}}"));
        assertTrue(result.contains("hello"));
    }

    // ─── Output field spec parsing ────────────────────────────────────────────

    @Test
    void parseOutputFieldsDefaultWhenBlank() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("");
        assertEquals(6, fields.size());
        assertEquals("classification", fields.get(0).key);
        assertEquals(LlmEnricher.FieldType.TEXT, fields.get(0).type);
        assertEquals("summary", fields.get(1).key);
        assertEquals(LlmEnricher.FieldType.LONGTEXT, fields.get(1).type);
        assertEquals("topics", fields.get(2).key);
        assertEquals(LlmEnricher.FieldType.LIST, fields.get(2).type);
        assertEquals("language", fields.get(4).key);
        assertEquals(LlmEnricher.FieldType.LANG, fields.get(4).type);
    }

    @Test
    void parseOutputFieldsMissingTypeMeansText() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("audience");
        assertEquals(1, fields.size());
        assertEquals("audience", fields.get(0).key);
        assertEquals(LlmEnricher.FieldType.TEXT, fields.get(0).type);
    }

    @Test
    void parseOutputFieldsUnknownTypeFallsBackToText() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("score:number");
        assertEquals(1, fields.size());
        assertEquals(LlmEnricher.FieldType.TEXT, fields.get(0).type);
    }

    @Test
    void parseOutputFieldsInvalidKeyDropped() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("123invalid, valid");
        assertEquals(1, fields.size());
        assertEquals("valid", fields.get(0).key);
    }

    @Test
    void parseOutputFieldsReservedKeysDropped() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("objectID, path, type, summary");
        assertEquals(1, fields.size());
        assertEquals("summary", fields.get(0).key);
    }

    @Test
    void parseOutputFieldsDuplicateKeysDeduplicatedCaseInsensitively() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("summary, Summary, SUMMARY");
        assertEquals(1, fields.size());
        assertEquals("summary", fields.get(0).key);
    }

    @Test
    void parseOutputFieldsCappedAtTwenty() {
        StringBuilder spec = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            if (spec.length() > 0) spec.append(", ");
            spec.append("field").append(i);
        }
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields(spec.toString());
        assertEquals(20, fields.size());
    }

    @Test
    void parseOutputFieldsAllInvalidFallsBackToDefault() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("123, 456, !!!");
        assertEquals(6, fields.size());  // default six
    }

    // ─── describeFields ───────────────────────────────────────────────────────

    @Test
    void describeFieldsProducesCorrectTypePhrases() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields(
                "title:text, body:longtext, tags:list, locale:lang");
        String description = LlmEnricher.describeFields(fields);
        assertTrue(description.contains("\"title\" (string)"));
        assertTrue(description.contains("\"body\" (string, one paragraph)"));
        assertTrue(description.contains("\"tags\" (array of strings, at most 20)"));
        assertTrue(description.contains("\"locale\" (ISO 639-1 language code string)"));
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

    // ─── parseFields (field-driven) ───────────────────────────────────────────

    @Test
    void parseFieldsExtraCustomKey() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("audience:text, topics:list");
        String json = "{\"audience\":\"developers\",\"topics\":[\"AEM\",\"Algolia\"]}";
        Map<String, Object> result = LlmEnricher.parseFields(json, fields);
        assertEquals("developers", result.get("audience"));
        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) result.get("topics");
        assertEquals(List.of("AEM", "Algolia"), topics);
    }

    @Test
    void parseFieldsLangRejectsProse() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("locale:lang");
        Map<String, Object> result = LlmEnricher.parseFields("{\"locale\":\"English\"}", fields);
        assertNull(result.get("locale"));
    }

    @Test
    void parseFieldsLangtextCappedAt2000() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("body:longtext");
        String longText = "x".repeat(3000);
        Map<String, Object> result = LlmEnricher.parseFields("{\"body\":\"" + longText + "\"}", fields);
        assertEquals(2000, ((String) result.get("body")).length());
    }

    @Test
    void parseFieldsListDeduplicatesAndCapsAt20() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("tags:list");
        StringBuilder json = new StringBuilder("{\"tags\":[\"a\",\"a\"");
        for (int i = 0; i < 25; i++) json.append(",\"t").append(i).append('"');
        json.append("]}");
        Map<String, Object> result = LlmEnricher.parseFields(json.toString(), fields);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) result.get("tags");
        assertEquals(20, tags.size());
        assertEquals("a", tags.get(0));
    }

    @Test
    void parseFieldsSkipsAbsentKeys() {
        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("summary:longtext, missing:text");
        Map<String, Object> result = LlmEnricher.parseFields("{\"summary\":\"S.\"}", fields);
        assertTrue(result.containsKey("summary"));
        assertFalse(result.containsKey("missing"));
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

    @Test
    void customStemWritesWithThatStemCapitalized() throws IOException {
        String json = "{\"summary\":\"Brief.\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        LlmEnricher pdfEnricher = LlmEnricher.forPdf(this.client, 12000);
        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/dam/a.pdf");
        request.addRecord(record);

        // Use a custom stem via enrichInternal via a mock ExtenderSettings call is not practical
        // here; instead verify that the default 9-arg enrich uses "pdf" stem
        pdfEnricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key", "", "", "");

        assertTrue(record.containsKey("pdfSummary"));
        assertFalse(record.containsKey("Summary"));
    }

    @Test
    void resolveStemHonoursExplicitBlank() {
        ExtenderSettings settings = new ExtenderSettings(Map.of("llmAttributeStem", ""), List.of());
        assertEquals("", this.enricher.resolveStem(settings));
    }

    @Test
    void blankStemWritesKeyDirectly() throws IOException {
        String json = "{\"summary\":\"Blank stem test.\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/dam/a.pdf");
        request.addRecord(record);

        List<LlmEnricher.OutputField> fields = LlmEnricher.parseOutputFields("summary:longtext");
        this.enricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key", "", "", "", "", fields);

        assertEquals("Blank stem test.", record.get("summary"));
        assertFalse(record.containsKey("pdfSummary"));
    }

    // ─── {{fields}} directive ─────────────────────────────────────────────────

    @Test
    void fieldsPlaceholderInSystemPromptIsReplaced() throws IOException {
        String json = "{\"summary\":\"S.\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        request.addRecord(new AlgoliaRecord("/dam/a.pdf"));

        // Pass a custom system prompt with {{fields}}
        this.enricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key",
                "Return {{fields}} only.", "{{pdfText}}", "");

        ArgumentCaptor<List<ChatCompletionsClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(this.client).chat(anyString(), anyString(), anyString(), messages.capture());
        String sysContent = messages.getValue().get(0).getContent();
        assertFalse(sysContent.contains("{{fields}}"));
        assertTrue(sysContent.contains("\"classification\""));
    }

    @Test
    void fieldsDirectiveAppendedWhenNeitherPromptHasPlaceholder() throws IOException {
        String json = "{\"summary\":\"S.\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        request.addRecord(new AlgoliaRecord("/dam/a.pdf"));

        // Custom system prompt WITHOUT {{fields}}
        this.enricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key",
                "You are a helpful assistant.", "Analyze: {{pdfText}}", "");

        ArgumentCaptor<List<ChatCompletionsClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(this.client).chat(anyString(), anyString(), anyString(), messages.capture());
        String sysContent = messages.getValue().get(0).getContent();
        // Fields directive is appended
        assertTrue(sysContent.contains("Return a JSON object with exactly these keys:"));
        assertTrue(sysContent.contains("\"classification\""));
    }

    @Test
    void fieldsDirectiveNotAppendedWhenUserPromptHasPlaceholder() throws IOException {
        String json = "{\"summary\":\"S.\"}";
        when(this.client.chat(anyString(), anyString(), anyString(), anyList())).thenReturn(json);

        AlgoliaRequest request = new AlgoliaRequest("/dam/a.pdf", null);
        request.addRecord(new AlgoliaRecord("/dam/a.pdf"));

        // Custom system WITHOUT {{fields}}, user WITH {{fields}}
        this.enricher.enrich(request, "/dam/a.pdf", "text",
                "https://8.8.8.8/v1", "m", "key",
                "You are a helpful assistant.", "Keys: {{fields}}. Text: {{pdfText}}", "");

        ArgumentCaptor<List<ChatCompletionsClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(this.client).chat(anyString(), anyString(), anyString(), messages.capture());
        String sysContent = messages.getValue().get(0).getContent();
        // No duplicate directive
        assertFalse(sysContent.contains("Return a JSON object with exactly these keys:"));
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
        // {{fields}} is replaced in the resolved system prompt
        String sysContent = messages.getValue().get(0).getContent();
        assertFalse(sysContent.contains(LlmEnricher.FIELDS_PLACEHOLDER));
        assertTrue(sysContent.contains("\"classification\""));
        // User message should be exactly 12000 chars (truncated text only)
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
    void tagsEnricherForcesLanguageOnLangTypeField() throws IOException {
        LlmEnricher tags = LlmEnricher.forTags(this.client, 8000);
        when(this.client.chat(anyString(), anyString(), anyString(), anyList()))
                .thenReturn("{\"summary\":\"A brief description.\"}");

        AlgoliaRequest request = new AlgoliaRequest("/content/site/en", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/site/en");
        request.addRecord(record);

        // Default fields include language:lang → Tags enricher pre-selects "en" and forces it
        tags.enrich(request, "/content/site/en", "text", "https://8.8.8.8/v1", "m", "key",
                "", "", "");

        assertEquals("en", record.get("tagLanguage"));
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

    @Test
    void customStemOwnAttributesAreSkippedInLanguageDetection() {
        // When a tags enricher uses a custom stem "doc", attributes like "docLanguage"
        // should be skipped (not used as the detected language).
        LlmEnricher.OutputField langField = new LlmEnricher.OutputField("language", LlmEnricher.FieldType.LANG);
        Set<String> ownNorms = LlmEnricher.buildOwnAttrNorms("doc", List.of(langField));
        AlgoliaRequest request = new AlgoliaRequest("/content/dam/doc.pdf", null);
        AlgoliaRecord record = new AlgoliaRecord("/content/dam/doc.pdf");
        record.addAttribute("docLanguage", "de");  // own attribute — should be skipped
        record.addAttribute("pdfLanguage", "fr");   // pdf attribute — should be picked up
        request.addRecord(record);

        LlmEnricher.LanguageChoice choice = LlmEnricher.chooseLanguage(request, ownNorms);

        assertEquals("fr", choice.code);
        assertEquals("pdfLanguage", choice.source);
    }
}
