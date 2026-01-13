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


import static com.day.cq.tagging.TagConstants.PN_TAGS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test cases for {@link DefaultAlgoliaTagsExtractor}
 *
 * @author Rakesh.Kumar
 */
@ExtendWith(MockitoExtension.class)
class DefaultAlgoliaTagsExtractorTest {

    private static final String ASSET_PATH = "/content/dam/test/asset.jpg";
    private static final String PAGE_PATH = "/content/test/page";

    @Mock
    private TagsParserService tagsParserService;

    @Mock
    private Asset asset;

    @Mock
    private Page page;

    @Mock
    private Resource resource;

    private AlgoliaRequest assetRequest;
    private AlgoliaRequest pageRequest;
    private AlgoliaRecord algoliaAssetRecord;
    private AlgoliaRecord algoliaPageRecord;
    private DefaultAlgoliaTagsExtractor algoliaTagsExtractor;

    @BeforeEach
    void setup() {
        this.algoliaTagsExtractor = new DefaultAlgoliaTagsExtractor(this.tagsParserService);
        this.algoliaAssetRecord = new AlgoliaRecord(ASSET_PATH);
        // AlgoliaConfiguration is not used by the extractors, so we can pass null
        this.assetRequest = new AlgoliaRequest(this.resource, null);
        this.assetRequest.addRecord(this.algoliaAssetRecord);
        
        this.algoliaPageRecord = new AlgoliaRecord(PAGE_PATH);
        this.pageRequest = new AlgoliaRequest(this.resource, null);
        this.pageRequest.addRecord(this.algoliaPageRecord);
    }

    @Test
    void testAugmentAlgoliaRequestForAsset() {
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.assetRequest, this.asset);

        verify(this.tagsParserService).parse(this.resource, this.algoliaAssetRecord, PN_TAGS);
        verify(this.asset, never()).getPath(); // Asset parameter is not used in implementation
    }

    @Test
    void testAugmentAlgoliaRequestForPage() {
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.pageRequest, this.page);

        verify(this.tagsParserService).parse(this.resource, this.algoliaPageRecord, PN_TAGS);
        verify(this.page, never()).getPath(); // Page parameter is not used in implementation
    }

    @Test
    void testAugmentAlgoliaRequestForAssetWithMultipleRecords() {
        AlgoliaRecord secondRecord = new AlgoliaRecord("/content/dam/test/asset2.jpg");
        this.assetRequest.addRecord(secondRecord);

        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.assetRequest, this.asset);

        // Should only parse tags for the first record
        verify(this.tagsParserService).parse(eq(this.resource), eq(this.algoliaAssetRecord), eq(PN_TAGS));
        verify(this.tagsParserService, never()).parse(eq(this.resource), eq(secondRecord), eq(PN_TAGS));
    }

    @Test
    void testAugmentAlgoliaRequestForPageWithMultipleRecords() {
        AlgoliaRecord secondRecord = new AlgoliaRecord("/content/test/page2");
        this.pageRequest.addRecord(secondRecord);

        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.pageRequest, this.page);

        // Should only parse tags for the first record
        verify(this.tagsParserService).parse(eq(this.resource), eq(this.algoliaPageRecord), eq(PN_TAGS));
        verify(this.tagsParserService, never()).parse(eq(this.resource), eq(secondRecord), eq(PN_TAGS));
    }

    @Test
    void testAugmentAlgoliaRequestForAssetWithNullResource() {
        AlgoliaRequest requestWithNullResource = new AlgoliaRequest(null, null);
        requestWithNullResource.addRecord(this.algoliaAssetRecord);

        // Should not throw exception, but pass null to tagsParserService
        this.algoliaTagsExtractor.augmentAlgoliaRequest(requestWithNullResource, this.asset);

        verify(this.tagsParserService).parse(null, this.algoliaAssetRecord, PN_TAGS);
    }

    @Test
    void testAugmentAlgoliaRequestForPageWithNullResource() {
        AlgoliaRequest requestWithNullResource = new AlgoliaRequest(null, null);
        requestWithNullResource.addRecord(this.algoliaPageRecord);

        // Should not throw exception, but pass null to tagsParserService
        this.algoliaTagsExtractor.augmentAlgoliaRequest(requestWithNullResource, this.page);

        verify(this.tagsParserService).parse(null, this.algoliaPageRecord, PN_TAGS);
    }

    @Test
    void testAugmentAlgoliaRequestForAssetWithEmptyRecordsList() {
        AlgoliaRequest emptyRequest = new AlgoliaRequest(this.resource, null);

        assertThrows(IndexOutOfBoundsException.class, () -> {
            this.algoliaTagsExtractor.augmentAlgoliaRequest(emptyRequest, this.asset);
        });
    }

    @Test
    void testAugmentAlgoliaRequestForPageWithEmptyRecordsList() {
        AlgoliaRequest emptyRequest = new AlgoliaRequest(this.resource, null);

        assertThrows(IndexOutOfBoundsException.class, () -> {
            this.algoliaTagsExtractor.augmentAlgoliaRequest(emptyRequest, this.page);
        });
    }

    @Test
    void testAugmentAlgoliaRequestForAssetCallsCorrectServiceMethod() {
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.assetRequest, this.asset);

        // Verify the exact method call with correct parameters
        verify(this.tagsParserService).parse(this.resource, this.algoliaAssetRecord, PN_TAGS);
        // Verify no other interactions with the service
        verify(this.tagsParserService, never()).parse(
                eq(this.resource), eq(this.algoliaAssetRecord), eq("differentProperty"));
    }

    @Test
    void testAugmentAlgoliaRequestForPageCallsCorrectServiceMethod() {
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.pageRequest, this.page);

        // Verify the exact method call with correct parameters
        verify(this.tagsParserService).parse(this.resource, this.algoliaPageRecord, PN_TAGS);
        // Verify no other interactions with the service
        verify(this.tagsParserService, never()).parse(
                eq(this.resource), eq(this.algoliaPageRecord), eq("differentProperty"));
    }

    @Test
    void testBothInterfacesImplemented() {
        // Verify that the class implements both interfaces
        assertTrue(this.algoliaTagsExtractor instanceof com.algolia.connector.core.extender.AlgoliaPageRequestExtender);
        assertTrue(this.algoliaTagsExtractor instanceof com.algolia.connector.core.extender.AlgoliaAssetRequestExtender);
    }

    @Test
    void testAugmentAlgoliaRequestForAssetAndPageUseSameLogic() {
        // Both asset and page should use the same internal logic
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.assetRequest, this.asset);
        verify(this.tagsParserService).parse(this.resource, this.algoliaAssetRecord, PN_TAGS);

        // Test with page
        this.algoliaTagsExtractor.augmentAlgoliaRequest(this.pageRequest, this.page);
        verify(this.tagsParserService).parse(this.resource, this.algoliaPageRecord, PN_TAGS);
    }
}
