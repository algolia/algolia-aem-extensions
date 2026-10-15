package com.algolia.core.extender.internal;

import com.algolia.connector.core.TagsParserService;
import com.algolia.connector.core.annotation.ComponentServiceProperties;
import com.algolia.connector.core.domain.AlgoliaRecord;
import com.algolia.connector.core.domain.AlgoliaRequest;
import com.algolia.connector.core.extender.AlgoliaAssetRequestExtender;
import com.algolia.connector.core.extender.AlgoliaPageRequestExtender;
import com.algolia.connector.core.extender.ConfigurableRequestExtender;
import com.algolia.connector.core.extender.ExtenderConfigField;
import com.algolia.connector.core.extender.ExtenderSettings;
import com.day.cq.dam.api.Asset;
import com.day.cq.wcm.api.Page;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;

import static com.day.cq.tagging.TagConstants.PN_TAGS;

/**
 * Default implementation extracting the tags from a JCR tag property of a Page or an Asset.
 *
 * @author Rakesh Kumar
 * @since 3.0.0
 */
@ComponentServiceProperties(description = "Algolia Tags Extractor")
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class
        }
)
public class DefaultAlgoliaTagsExtractor implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender,
        ConfigurableRequestExtender {

    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final TagsParserService tagsParserService;

    @Activate
    public DefaultAlgoliaTagsExtractor(@Reference TagsParserService tagsParserService) {
        this.tagsParserService = tagsParserService;
    }

    @Override
    public String getSettingsDescription() {
        return "Adds tag titles from a JCR tag property onto the Algolia record.";
    }

    @Override
    public List<ExtenderConfigField> getConfigFields() {
        return List.of(
                ExtenderConfigField.builder("tagProperty", "Tag Property")
                        .defaultValue(PN_TAGS)
                        .helpText("JCR property that stores tag IDs. Pages are read from jcr:content, "
                                + "and assets from jcr:content/metadata. Defaults to cq:tags.")
                        .build(),
                ExtenderConfigField.builder("attributePrefix", "Attribute Prefix")
                        .helpText("Prefix added to every attribute name the extension emits, e.g. \"cat_\" → cat_topic.")
                        .build()
        );
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
        ExtenderSettings settings = settingsOf(request);
        String tagProperty = settings.getString("tagProperty", PN_TAGS).trim();
        if (StringUtils.isEmpty(tagProperty)) {
            tagProperty = PN_TAGS;
        }
        String rawPrefix = settings.getString("attributePrefix", "");
        String prefix = AttributeNames.prefix(rawPrefix);
        if (prefix == null) {
            LOGGER.warn("Ignoring attribute prefix '{}' because it contains unsupported characters.", rawPrefix);
            prefix = "";
        }
        if (prefix.isEmpty()) {
            this.tagsParserService.parse(resource, algoliaRecord, tagProperty);
            return;
        }
        AlgoliaRecord parsed = new AlgoliaRecord(algoliaRecord.getObjectID());
        this.tagsParserService.parse(resource, parsed, tagProperty);
        AttributeNames.mergeWithPrefix(parsed, algoliaRecord, prefix);
    }

    private static ExtenderSettings settingsOf(AlgoliaRequest request) {
        ExtenderSettings settings = request.getExtenderSettings();
        return settings != null ? settings : ExtenderSettings.empty();
    }
}
