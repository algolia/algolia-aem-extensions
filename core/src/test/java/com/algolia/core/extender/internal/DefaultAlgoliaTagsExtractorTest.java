package com.algolia.core.extender.internal;

import com.algolia.connector.core.TagsParserService;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
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

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
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
        this.algoliaTagsExtractor = new DefaultAlgoliaTagsExtractor(this.tagsParserService);
        this.records = new ArrayList<>();
        this.algoliaAssetRecord = spy(new AlgoliaRecord("/content/dam/a/b.pdf"));
        this.algoliaPageRecord = spy(new AlgoliaRecord("/content/dam/x/y"));
        this.records.add(this.algoliaAssetRecord);
        this.records.add(this.algoliaPageRecord);
    }

    @Test
    void testAugmentAlgoliaRequestForAsset() {
        when(this.request.getResource()).thenReturn(this.resource);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.request, this.asset);
        verifyNoInteractions(this.algoliaAssetRecord);
    }

    @Test
    void testAugmentAlgoliaRequestForPage() {
        when(this.request.getResource()).thenReturn(this.resource);
        when(this.request.getAlgoliaRecords()).thenReturn(this.records);
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.request, this.page);
        verifyNoInteractions(this.algoliaPageRecord);
    }
}
