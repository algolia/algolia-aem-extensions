# Tags Extractor

`DefaultAlgoliaTagsExtractor` copies AEM tags onto the Algolia record for both pages and assets. It reads a JCR tag property and lets the indexer’s `TagsParserService` turn those tag IDs into record attributes.

Source: [`DefaultAlgoliaTagsExtractor`](../core/src/main/java/com/algolia/core/extender/internal/DefaultAlgoliaTagsExtractor.java).

---

## Table of contents

1. [Registration](#registration)
2. [Configuration](#configuration)
3. [What it writes](#what-it-writes)
4. [Page and asset entry points](#page-and-asset-entry-points)
5. [Backward compatibility](#backward-compatibility)

---

## Registration

The class implements both extender interfaces and registers both OSGi services:

```java
@ComponentServiceProperties(description = "Algolia Tags Extractor")
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class
        }
)
public class DefaultAlgoliaTagsExtractor
        implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender,
        ConfigurableRequestExtender {
```

`@ComponentServiceProperties` sets `service.description` to **Algolia Tags Extractor**. That string is the label in the Cloud Service dropdowns. The value stored on the configuration is the class name `com.algolia.core.extender.internal.DefaultAlgoliaTagsExtractor`.

Select it independently for pages and for assets. You can also select it together with other asset extenders, such as the [PDF Text Extractor](PDF_TEXT.md):

| List | Select it when |
|---|---|
| **Page Request Extender Services** | Indexed pages should carry their tags |
| **Asset Request Extender Services** | Indexed assets should carry their tags |

One bundle installation covers both. Choosing it on only one list limits it to that resource type. Page and asset selections store settings separately, so the same extender can use a different Tag Property or Attribute Prefix on each configuration.

---

## Configuration

Authors set these on the Cloud Service configuration, under **Extension Settings**, after selecting **Algolia Tags Extractor**.

![Extension Settings for Algolia Tags Extractor: Tag Property and Attribute Prefix](screenshots/extension-settings-tags.png)

| Setting | Type | Default | Meaning |
|---|---|---|---|
| Tag Property | text | `cq:tags` | JCR property that stores tag IDs. Pages are read from `jcr:content`, and assets from `jcr:content/metadata`. |
| Attribute Prefix | text | empty | Prefix added to every attribute name this extender emits, for example `cat_` → `cat_topic`. Letters, digits, `_`, and `-` only; an invalid prefix is ignored. |

The card header shows **Defaults** while both fields still use those values. Leaving Tag Property blank also falls back to `cq:tags`.

---

## What it writes

Both entry points call the same method. It reads the tag property named by the author, asks `TagsParserService` to turn those tag IDs into record attributes, then applies **Attribute Prefix** to the attributes it just added.

**Attribute Prefix** is prepended to every attribute this extender adds. `cat_` turns a `topic` attribute into `cat_topic`. A blank prefix leaves the names unchanged. The prefix may contain letters, digits, underscores, and hyphens.

Behavior that follows from this method:

- Tags are read from the resource already on the `AlgoliaRequest`, not by adapting the `Page` or `Asset` argument.
- Only the **first** record on the request is updated (`getAlgoliaRecords().get(0)`). Later records, including records created by the [PDF Text Extractor](PDF_TEXT.md#record-splitting), are not given tags by this class.
- The shape of the attributes is defined by `TagsParserService` in the indexer, not by this class. This extender does not change tag titles or apply a locale. The prefix changes only the attribute name.

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

The `Page` and `Asset` arguments are unused. Two methods exist so the class satisfies both interfaces and can be selected in both lists. A custom extender that needs the page title, the asset MIME type, or a property that is not on `request.getResource()` should use those arguments. See [Building custom extensions](CUSTOM_EXTENSIONS.md).

The only injected service is `TagsParserService`.

---

## Backward compatibility

On upgrade from extensions **1.0.x**, a configuration that already selected this class keeps working without a save. Settings JSON is empty, so the extender uses `cq:tags` and no prefix — the same attributes as before.

| 1.0.x behavior | 1.1.0 default |
|---|---|
| Always read `cq:tags` | **Tag Property** defaults to `cq:tags` |
| No attribute prefix | **Attribute Prefix** defaults to empty |
| Dropdown label **Algolia Default Tags Extractor** | Dropdown label **Algolia Tags Extractor**. The stored class name is unchanged. |

A custom extender that copied this class before `ConfigurableRequestExtender` existed does not need to implement that interface. It still runs; it just has no settings card. See [Installation — Backward compatibility](INSTALLATION.md#backward-compatibility).

---

**Related docs**

| Doc | Topics |
|---|---|
| [Installation](INSTALLATION.md) | Build, deploy, and select the extender |
| [PDF Text Extractor](PDF_TEXT.md) | Asset-only text extraction and record splitting |
| [Property Value Introspector](INTROSPECTOR.md) | Abort indexing on a JCR property value |
| [Custom extensions](CUSTOM_EXTENSIONS.md) | `@ComponentServiceProperties` and the extender interfaces |
