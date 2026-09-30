# Algolia AEM Extensions

This project provides reference implementations of extensions for the Algolia AEM Connector. The project includes two indexing extensions that demonstrate how to customize and enhance the indexing behavior of the connector.

## Disclaimer

**No Warranties or SLA**: This project is provided as-is without any warranties, express or implied. There are no service level agreements (SLA) or guarantees regarding issues, bugs, or support. Use at your own risk.

## Overview

This project demonstrates how to build custom extensions for the Algolia AEM Connector. Extensions allow you to customize and enhance the indexing behavior of the connector to meet your specific requirements.

## Indexing Extensions

The project provides two reference implementations:

1. **DefaultAlgoliaPdfTextExtractor** — asset request extender that extracts PDF text onto Algolia records, partitions it by word count, and splits records when the text exceeds 10 KB. See [PDF Text Extractor](docs/PDF_TEXT.md).
2. **DefaultAlgoliaTagsExtractor** — page and asset extender that adds `cq:tags` through the indexer’s `TagsParserService`. See [Tags Extractor](docs/TAGS.md).

Use them as starting points for your own extenders. See [Building custom extensions](docs/CUSTOM_EXTENSIONS.md).

## Modules

The main parts of the project are:

* **core**: Java bundle containing extension implementations and OSGi services
* **it.tests**: Java based integration tests (not part of the Maven reactor)
* **all**: A single content package that embeds the core bundle

## Documentation

Guides for this package live in [`docs/`](docs/):

| Guide | Topics |
|-------|--------|
| [Installation](docs/INSTALLATION.md) | Prerequisites, Maven build, deploy profiles, selecting extenders, tests |
| [PDF Text Extractor](docs/PDF_TEXT.md) | `pdfText` attributes, word-size limit, 10 KB record splitting |
| [Tags Extractor](docs/TAGS.md) | `cq:tags` on pages and assets |
| [Custom extensions](docs/CUSTOM_EXTENSIONS.md) | Extender interfaces, OSGi registration, Cloud Service selection |

Build a branded PDF (requires [pandoc](https://pandoc.org/), Node.js, and Google Chrome):

```bash
mvn -N exec:exec@docs-pdf
```

`npm run docs:pdf` runs the same script.

The file is written to `docs/pdf/Algolia-AEM-Extensions-Documentation-v1.0.0.pdf`.

## How to build

To build all the modules run in the project root directory the following command with Maven 3:

    mvn clean install

To build all the modules and deploy the `all` package to a local instance of AEM, run in the project root directory the following command:

    mvn clean install -PautoInstallSinglePackage

Or to deploy it to a publish instance, run

    mvn clean install -PautoInstallSinglePackagePublish

Or alternatively

    mvn clean install -PautoInstallSinglePackage -Daem.port=4503

Or to deploy only the bundle to the author, run

    mvn clean install -PautoInstallBundle

Or to deploy only a single content package, run in the sub-module directory (i.e `all`)

    mvn clean install -PautoInstallPackage

## Dependencies

This project depends on:
- `algolia-aem-indexer.core` (version 4.1.2) - The core Algolia AEM Connector library
- `opennlp-tools` (version 2.5.0) - Used for text tokenization in PDF text extraction
- AEM SDK API - For AEM-specific functionality

## Testing

There are two levels of testing contained in the project:

### Unit tests

This show-cases classic unit testing of the code contained in the bundle. To
test, execute:

    mvn clean test

### Integration tests

This allows running integration tests that exercise the capabilities of AEM via
HTTP calls to its API. To run the integration tests, run:

    mvn clean verify -Plocal

Test classes must be saved in the `src/main/java` directory (or any of its
subdirectories), and must be contained in files matching the pattern `*IT.java`.

The configuration provides sensible defaults for a typical local installation of
AEM. If you want to point the integration tests to different AEM author and
publish instances, you can use the following system properties via Maven's `-D`
flag.

| Property              | Description                                         | Default value           |
|-----------------------|-----------------------------------------------------|-------------------------|
| `it.author.url`       | URL of the author instance                          | `http://localhost:4502` |
| `it.author.user`      | Admin user for the author instance                  | `admin`                 |
| `it.author.password`  | Password of the admin user for the author instance  | `admin`                 |
| `it.publish.url`      | URL of the publish instance                         | `http://localhost:4503` |
| `it.publish.user`     | Admin user for the publish instance                 | `admin`                 |
| `it.publish.password` | Password of the admin user for the publish instance | `admin`                 |

The integration tests in this archetype use the [AEM Testing
Clients](https://github.com/adobe/aem-testing-clients) and showcase some
recommended [best
practices](https://github.com/adobe/aem-testing-clients/wiki/Best-practices) to
be put in use when writing integration tests for AEM.


## Maven settings

The project comes with the auto-public repository configured. To setup the repository in your Maven settings, refer to:

    http://helpx.adobe.com/experience-manager/kb/SetUpTheAdobeMavenRepository.html
