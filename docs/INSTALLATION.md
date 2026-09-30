# Installation Guide

How to build and install the **Algolia AEM Extensions** package (`algolia-aem-extensions.all`) so the reference indexing extenders are available to the Algolia Connector for Adobe Experience Manager.

> **This package does not index content by itself.** Install and configure [`algolia-aem-indexer`](https://github.com/algolia/algolia-aem-indexer) first. The extenders in this project run during the indexer’s page and asset indexing and depend on services the indexer provides.

> **No warranties or SLA.** This project is a reference implementation, provided as-is. There is no service level agreement and no guarantee of support. Use it as a starting point and adapt it to your requirements.

---

## Table of contents

1. [Introduction](#introduction)
2. [Supported versions and runtimes](#supported-versions-and-runtimes)
3. [Before you start](#before-you-start)
4. [Modules](#modules)
5. [Build and install](#build-and-install)
6. [Select the extenders](#select-the-extenders)
7. [Testing](#testing)
8. [Maven settings](#maven-settings)

---

## Introduction

The extensions package ships two OSGi services that augment Algolia records while the indexer prepares them:

| Extender | Applies to | What it adds |
|---|---|---|
| [PDF Text Extractor](PDF_TEXT.md) | Assets | Text extracted from PDF renditions, split across attributes or records when the text is large |
| [Tags Extractor](TAGS.md) | Pages and assets | Tags read from the `cq:tags` property |

Both are registered as request extenders. After you install the package, select them on the indexer Cloud Service configuration. See [Building custom extensions](CUSTOM_EXTENSIONS.md) if you need a different payload.

---

## Supported versions and runtimes

| | |
|---|---|
| **AEM version** | 6.5+ (AEM SDK API `2025.12`) |
| **Java** | 11 or newer, for the Maven build |
| **Indexer dependency** | `algolia-aem-indexer.core` **4.1.2** |
| **Installation and runtime** | On-premises, Adobe Managed Services, and AEM as a Cloud Service |

The PDF extractor also embeds Apache OpenNLP `opennlp-tools` **2.5.0**, used to split extracted text on whitespace.

---

## Before you start

1. Install `algolia-aem-indexer.all` and upload a valid Algolia license. Indexing stays inactive until the license is in place.
2. Create an indexer Cloud Service configuration and assign it to the sites or DAM folders you index.
3. Host `algolia-aem-indexer.core` where this project’s Maven build can resolve it. The core module declares it as a compile dependency:

```xml
<dependency>
    <groupId>com.algolia</groupId>
    <artifactId>algolia-aem-indexer.core</artifactId>
    <version>4.1.2</version>
</dependency>
```

The indexer project documents how to install that JAR in a local or private Maven repository.

You need Maven 3.3.9 or newer.

---

## Modules

| Module | Artifact | Role |
|---|---|---|
| **core** | `algolia-aem-extensions.core` | OSGi bundle with `DefaultAlgoliaPdfTextExtractor` and `DefaultAlgoliaTagsExtractor` |
| **all** | `algolia-aem-extensions.all` | Container content package. Embeds the core bundle at `/apps/algolia-extensions-packages/application/install` |

An `it.tests` module is present for HTTP integration tests against a running AEM instance. It is not part of the reactor, so `mvn clean install` from the project root does not run it.

---

## Build and install

From the project root:

```bash
mvn clean install
```

That builds the core bundle and the `all` package. The installable file is:

`all/target/algolia-aem-extensions.all-1.0.0-SNAPSHOT.zip`

### Deploy to a local author

```bash
mvn clean install -PautoInstallSinglePackage
```

Publish instance:

```bash
mvn clean install -PautoInstallSinglePackagePublish
```

Or point the author install at another port:

```bash
mvn clean install -PautoInstallSinglePackage -Daem.port=4503
```

Bundle only, to the author:

```bash
mvn clean install -PautoInstallBundle
```

A single content package, run from that module (for example `all`):

```bash
mvn clean install -PautoInstallPackage
```

Default connection settings are `localhost:4502` (author) and `localhost:4503` (publish), with user `admin` / `admin`. Override them with `aem.host`, `aem.port`, `aem.publish.host`, `aem.publish.port`, `sling.user`, `sling.password`, `vault.user`, and `vault.password`.

---

## Select the extenders

Installing the package registers the services. Indexing does not call them until a Cloud Service configuration selects them.

On the indexer Cloud Service configuration **Advanced** tab:

| Dropdown | Service to select | Label |
|---|---|---|
| **Asset Request Extender Service** | `com.algolia.core.extender.internal.DefaultAlgoliaPdfTextExtractor` | Algolia PDF Text Extractor |
| **Asset Request Extender Service** | `com.algolia.core.extender.internal.DefaultAlgoliaTagsExtractor` | Algolia Default Tags Extractor |
| **Page Request Extender Service** | `com.algolia.core.extender.internal.DefaultAlgoliaTagsExtractor` | Algolia Default Tags Extractor |

The dropdown label is the OSGi `service.description` when the component sets one, and the component name otherwise. The stored value is the implementation class name.

Reindex or publish a page or PDF asset that uses the configuration, then confirm the new attributes on the Algolia record. See [PDF Text Extractor](PDF_TEXT.md) and [Tags Extractor](TAGS.md) for the attribute names.

---

## Testing

### Unit tests

```bash
mvn clean test
```

The core module tests the PDF extractor (mime type, empty text, word-limit splits, the 10 KB record split) and the tags extractor (page and asset paths both call `TagsParserService`).

### Integration tests

`it.tests` talks to a running AEM instance over HTTP. It is not a reactor module. From `it.tests`:

```bash
mvn clean verify -Plocal
```

Test classes live under `src/main/java` and must match `*IT.java`.

| Property | Description | Default |
|---|---|---|
| `it.author.url` | Author URL | `http://localhost:4502` |
| `it.author.user` | Author user | `admin` |
| `it.author.password` | Author password | `admin` |
| `it.publish.url` | Publish URL | `http://localhost:4503` |
| `it.publish.user` | Publish user | `admin` |
| `it.publish.password` | Publish password | `admin` |

---

## Maven settings

The project expects Adobe’s public Maven repository. To add it to your settings, follow [Set up the Adobe Maven repository](http://helpx.adobe.com/experience-manager/kb/SetUpTheAdobeMavenRepository.html).

---

**Related docs**

| Doc | Topics |
|---|---|
| [PDF Text Extractor](PDF_TEXT.md) | `pdfText` attributes, word limit, record splitting |
| [Tags Extractor](TAGS.md) | `cq:tags` on pages and assets |
| [Custom extensions](CUSTOM_EXTENSIONS.md) | Writing and registering your own extender |
