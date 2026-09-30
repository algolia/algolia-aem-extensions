# Tags Extractor

`DefaultAlgoliaTagsExtractor` copies AEM tags onto the Algolia record for both pages and assets. It reads the JCR `cq:tags` property and lets the indexer’s `TagsParserService` turn those tag IDs into record attributes.

Source: [`DefaultAlgoliaTagsExtractor`](../core/src/main/java/com/algolia/core/extender/internal/DefaultAlgoliaTagsExtractor.java).

---

## Table of contents

1. [Registration](#registration)
2. [What it writes](#what-it-writes)
3. [Page and asset entry points](#page-and-asset-entry-points)

---

## Registration

The class implements both extender interfaces and registers both OSGi services:

```java
@ComponentServiceProperties(description = "Algolia Default Tags Extractor")
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class
        }
)
public class DefaultAlgoliaTagsExtractor
        implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender {
```

`@ComponentServiceProperties` sets `service.description` to **Algolia Default Tags Extractor**. That string is the label in the Cloud Service dropdowns. The value stored on the configuration is the class name `com.algolia.core.extender.internal.DefaultAlgoliaTagsExtractor`.

Select it independently for pages and for assets:

| Dropdown | Select it when |
|---|---|
| **Page Request Extender Service** | Indexed pages should carry their `cq:tags` |
| **Asset Request Extender Service** | Indexed assets should carry their `cq:tags` |

One bundle installation covers both. Choosing it on only one dropdown limits it to that resource type.

---

## What it writes

Both entry points call the same method:

```java
private void addTagsToAlgoliaRecord(AlgoliaRequest request) {
    Resource resource = request.getResource();
    AlgoliaRecord algoliaRecord = request.getAlgoliaRecords().get(0);
    this.tagsParserService.parse(resource, algoliaRecord, PN_TAGS);
}
```

`PN_TAGS` is `com.day.cq.tagging.TagConstants.PN_TAGS`, the property name `cq:tags`.

Behavior that follows from this method:

- Tags are read from the resource already on the `AlgoliaRequest`, not by adapting the `Page` or `Asset` argument.
- Only the **first** record on the request is updated (`getAlgoliaRecords().get(0)`). Later records, including records created by the [PDF Text Extractor](PDF_TEXT.md#record-splitting), are not given tags by this class.
- The shape of the attributes is defined by `TagsParserService` in the indexer, not by this class. This extender does not rename tags or apply a locale.

If the request has no records, `get(0)` throws. The indexer is expected to have created the initial record before extenders run.

---

## Page and asset entry points

```java
@Override
public void augmentAlgoliaRequest(AlgoliaRequest request, Asset asset) {
    this.addTagsToAlgoliaRecord(request);
}

@Override
public void augmentAlgoliaRequest(AlgoliaRequest request, Page page) {
    this.addTagsToAlgoliaRecord(request);
}
```

The `Page` and `Asset` arguments are unused. Two methods exist so the class satisfies both interfaces and can be selected in both dropdowns. A custom extender that needs the page title, the asset MIME type, or a property that is not on `request.getResource()` should use those arguments. See [Building custom extensions](CUSTOM_EXTENSIONS.md).

The only injected service is `TagsParserService`.

---

**Related docs**

| Doc | Topics |
|---|---|
| [Installation](INSTALLATION.md) | Build, deploy, and select the extender |
| [PDF Text Extractor](PDF_TEXT.md) | Asset-only text extraction and record splitting |
| [Custom extensions](CUSTOM_EXTENSIONS.md) | `@ComponentServiceProperties` and the extender interfaces |
