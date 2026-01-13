package com.algolia.core.extender.internal;

import com.algolia.connector.core.TagsParserService;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.extender.AlgoliaPageRequestExtender;
import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Page;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import static com.day.cq.tagging.TagConstants.PN_TAGS;

/**
 * Default implementation extracting the tags from cq:tags jcr property of a Page or an Asset.
 *
 * @author Rakesh Kumar
 * @since 3.0.0
 */
@ComponentServiceProperties(description = "Algolia Default Tags Extractor")
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class
        }
)
public class DefaultAlgoliaTagsExtractor implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender {

    private final TagsParserService tagsParserService;

    @Activate
    public DefaultAlgoliaTagsExtractor(@Reference TagsParserService tagsParserService) {
        this.tagsParserService = tagsParserService;
    }

    @Override
    public void augmentAlgoliaRequest(AlgoliaRequest request, Asset asset) {
        this.addTagsToAlgoliaRecord(request);
    }

    @Override
    public void augmentAlgoliaRequest(AlgoliaRequest request, Page page) {
        this.addTagsToAlgoliaRecord(request);
    }

    private void addTagsToAlgoliaRecord(AlgoliaRequest request) {
        Resource resource = request.getResource();
        AlgoliaRecord algoliaRecord = request.getAlgoliaRecords().get(0);
        this.tagsParserService.parse(resource, algoliaRecord, PN_TAGS);
    }
}
