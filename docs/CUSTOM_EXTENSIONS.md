# Building Custom Extensions

The two classes in this project are reference implementations. Copy their shape when you need fields the indexer does not add on its own: a computed attribute, a transformed tag title, or extra records for a large payload.

The indexer invokes every extender selected on the Cloud Service configuration **Advanced** tab while it builds the indexing payload. Your service can add, change, or remove attributes, and it can add records. It cannot replace the indexer’s decision to index the resource; aborting indexing is a separate introspector hook, documented in the indexer project.

---

## Table of contents

1. [Choose an interface](#choose-an-interface)
2. [Register the OSGi service](#register-the-osgi-service)
3. [Augment the request](#augment-the-request)
4. [Compile against the indexer](#compile-against-the-indexer)
5. [Ship and select the service](#ship-and-select-the-service)

---

## Choose an interface

| Interface | Called for | Method |
|---|---|---|
| `AlgoliaPageRequestExtender` | Pages | `augmentAlgoliaRequest(AlgoliaRequest request, Page page)` |
| `AlgoliaAssetRequestExtender` | Assets | `augmentAlgoliaRequest(AlgoliaRequest request, Asset asset)` |

Implement one interface, or both when the same logic applies to pages and assets. `DefaultAlgoliaTagsExtractor` implements both and delegates to one private method. `DefaultAlgoliaPdfTextExtractor` implements only the asset interface, because PDF text does not apply to pages.

Both interfaces are `@ConsumerType`. The indexer calls `augmentAlgoliaRequest` after it has created the `AlgoliaRequest` and its initial record.

---

## Register the OSGi service

Declare the service interfaces explicitly when a class implements more than one, so OSGi publishes each contract:

```java
@ComponentServiceProperties(description = "My page and asset extender")
@Component(
        service = {
                AlgoliaPageRequestExtender.class,
                AlgoliaAssetRequestExtender.class
        }
)
public class MyRequestExtender
        implements AlgoliaPageRequestExtender, AlgoliaAssetRequestExtender {
```

`@ComponentServiceProperties` is an OSGi component property type from the indexer (`PREFIX_` is `service.`). Its `description` becomes `service.description`.

The Cloud Service dropdown uses that description as the option label. If `service.description` is empty, the dropdown falls back to the component name (`@Component(name = "...")`). The option **value** stored on the configuration is the implementation class name, not the label.

`DefaultAlgoliaPdfTextExtractor` does not set `service.description`. Its dropdown label is the component name **Algolia PDF Text Extractor**. Prefer `@ComponentServiceProperties` for a label you control independently of the component name.

A single-interface component can rely on Declarative Services to publish the implemented interface:

```java
@ComponentServiceProperties(description = "My asset extender")
@Component
public class MyAssetExtender implements AlgoliaAssetRequestExtender {
```

Inject indexer or AEM services with `@Reference` on the constructor, as both reference implementations do.

Optional configuration uses an OSGi `@ObjectClassDefinition` and a constructor parameter of that type, the same pattern as the PDF extractor’s `word_size_limit`.

---

## Augment the request

```java
@Override
public void augmentAlgoliaRequest(AlgoliaRequest request, Page page) {
    AlgoliaRecord record = request.getAlgoliaRecords().get(0);
    record.addAttribute("contentType", "article");
}
```

Guidelines that match the shipped extenders:

- Read the resource from `request.getResource()` when the JCR node is what you need. Use the `Page` or `Asset` argument for API that is not on that resource (template, MIME type, renditions).
- Update the record you mean to update. The tags extractor only touches `getAlgoliaRecords().get(0)`. The PDF extractor adds further records with `request.addRecord` and then removes the original.
- Keep each record within Algolia’s size limits. The PDF extractor treats 10 KB of extracted text as the point where one asset becomes many records, and it names the new object IDs `{originalObjectID}_{index}`.
- Do not assume your extender is the only one selected. Another extender may have already changed the first record, or may run after you. Order is the indexer’s service binding order, not something this package configures.
- Leave the request unchanged when your input is absent (the PDF extractor ignores non-PDFs and empty text) so unrelated resources still index.

---

## Compile against the indexer

Add the indexer core JAR to the bundle’s Maven dependencies. This project uses:

```xml
<dependency>
    <groupId>com.algolia</groupId>
    <artifactId>algolia-aem-indexer.core</artifactId>
    <version>4.1.2</version>
</dependency>
```

Packages you will import:

| Package | Types |
|---|---|
| `com.algolia.connector.core.extender` | `AlgoliaPageRequestExtender`, `AlgoliaAssetRequestExtender` |
| `com.algolia.connector.core.domain` | `AlgoliaRequest`, `AlgoliaRecord` |
| `com.algolia.connector.core.annotation` | `ComponentServiceProperties` |
| `com.algolia.connector.core` | `PdfTextExtractor`, `TagsParserService`, `AlgoliaConstants` |

`algolia-aem-indexer.core` must be on the Maven classpath at build time and installed in AEM at runtime. The extender bundle does not embed the indexer.

Put new classes in the `core` module, next to the reference implementations, or in your own bundle that imports the same packages. The `all` package embeds `algolia-aem-extensions.core` only. A separate bundle needs its own content package or an extra `<embedded>` entry.

---

## Ship and select the service

1. Build and deploy with the commands in [Installation](INSTALLATION.md#build-and-install).
2. Confirm the component is active in the OSGi console and that it is registered under the extender interface you implemented.
3. Open the indexer Cloud Service configuration, **Advanced** tab, and select the service in **Page Request Extender Service**, **Asset Request Extender Service**, or both.
4. Publish or reindex a resource that uses that configuration.
5. In Algolia, open the record and confirm the attributes you added. For a split PDF, expect several object IDs derived from the asset path.

Unit-test the extender by constructing it with mocks for its `@Reference` services and an `AlgoliaRequest` that already contains one record. The tests under `core/src/test/java/com/algolia/core/extender/internal/` cover the PDF and tags classes that way.

---

**Related docs**

| Doc | Topics |
|---|---|
| [Installation](INSTALLATION.md) | Prerequisites, Maven build, deploy profiles |
| [PDF Text Extractor](PDF_TEXT.md) | Asset extender with OSGi configuration and record splitting |
| [Tags Extractor](TAGS.md) | Dual page and asset registration |
