package com.algolia.core.extender.internal;

import com.adobe.granite.crypto.CryptoSupport;
import com.algolia.connector.core.TagsParserService;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Page;
import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link DefaultAlgoliaTagsExtractor}
 *
 * @author Rakesh.Kumar
 */
@ExtendWith(MockitoExtension.class)
public class DefaultAlgoliaTagsExtractorTest {

    @Mock
    private TagsParserService tagsParserService;

    @Mock
    private AlgoliaRequest request;

    @Mock
    private Asset asset;

    @Mock
    private Page page;

    @Mock
    private Resource resource;

    private AlgoliaRecord algoliaAssetRecord;

    private AlgoliaRecord algoliaPageRecord;

    private List<AlgoliaRecord> records;

    private DefaultAlgoliaTagsExtractor algoliaTagsExtractor;

    @BeforeEach
    void setup() {
        this.algoliaTagsExtractor = new DefaultAlgoliaTagsExtractor(
                this.tagsParserService, mock(CryptoSupport.class), (LlmEnricher) null);
        this.records = new ArrayList<>();
        this.algoliaAssetRecord = new AlgoliaRecord("/content/dam/a/b.pdf");
        this.algoliaPageRecord = new AlgoliaRecord("/content/dam/x/y");
        this.records.add(this.algoliaAssetRecord);
        this.records.add(this.algoliaPageRecord);
    }

    @Test
    void testAugmentAlgoliaRequestForAsset() {
        when(this.request.getResource()).thenReturn(this.resource);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.request, this.asset);
        assertFalse(this.algoliaAssetRecord.containsKey("topic"));
    }

    @Test
    void testAugmentAlgoliaRequestForPage() {
        when(this.request.getResource()).thenReturn(this.resource);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.request, this.page);
        assertFalse(this.algoliaPageRecord.containsKey("topic"));
    }

    @Test
    void testAuthorSettingsChooseTheTagPropertyAndPrefixNewAttributes() {
        ExtenderSettings settings = new ExtenderSettings(Map.of(
                "tagProperty", "my:tags",
                "attributePrefix", "cat_"
        ), List.of());
        when(this.request.getExtenderSettings()).thenReturn(settings);
        when(this.request.getResource()).thenReturn(this.resource);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        this.algoliaAssetRecord.addAttribute("topic", new ArrayList<>(List.of("from another extender")));
        doAnswer(invocation -> {
            AlgoliaRecord record = invocation.getArgument(1);
            record.addAttribute("topic", new ArrayList<>(List.of("News")));
            return null;
        }).when(this.tagsParserService).parse(eq(this.resource), any(AlgoliaRecord.class), eq("my:tags"));

        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.request, this.asset);

        assertEquals(List.of("News"), this.algoliaAssetRecord.get("cat_topic"));
        assertEquals(List.of("from another extender"), this.algoliaAssetRecord.get("topic"));
        assertEquals("/content/dam/a/b.pdf", this.algoliaAssetRecord.getObjectID());
        assertFalse(this.algoliaAssetRecord.containsKey("cat_objectID"));
    }

    @Test
    void testBlankPrefixAddsTagAttributesUnprefixed() {
        when(this.request.getExtenderSettings()).thenReturn(ExtenderSettings.empty());
        when(this.request.getResource()).thenReturn(this.resource);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        doAnswer(invocation -> {
            AlgoliaRecord record = invocation.getArgument(1);
            record.addAttribute("topic", new ArrayList<>(List.of("News")));
            return null;
        }).when(this.tagsParserService).parse(eq(this.resource), any(AlgoliaRecord.class), eq("cq:tags"));

        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.request, this.page);

        assertEquals(List.of("News"), this.algoliaAssetRecord.get("topic"));
    }
}
