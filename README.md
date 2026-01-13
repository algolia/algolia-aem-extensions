# Algolia AEM Extensions

This project provides reference implementations of extensions for the Algolia AEM Connector. The project includes two indexing extensions that demonstrate how to customize and enhance the indexing behavior of the connector.

## Disclaimer

**No Warranties or SLA**: This project is provided as-is without any warranties, express or implied. There are no service level agreements (SLA) or guarantees regarding issues, bugs, or support. Use at your own risk.

## Overview

This project demonstrates how to build custom extensions for the Algolia AEM Connector. Extensions allow you to customize and enhance the indexing behavior of the connector to meet your specific requirements.

## Indexing Extensions

The project provides two reference implementations:

1. **DefaultAlgoliaPdfTextExtractor** - An asset request extender that extracts text from PDF assets and adds it to Algolia records. This extension:
   - Extracts text from PDF assets using the `PdfTextExtractor` service
   - Handles large PDFs by splitting text into multiple attributes when word count exceeds the configured limit
   - Splits records when the total size exceeds 10KB to comply with Algolia record size limits
   - Configurable word size limit (default: 900 words)

2. **DefaultAlgoliaTagsExtractor** - A dual-purpose extender that extracts tags from both pages and assets. This extension:
   - Implements both `AlgoliaPageRequestExtender` and `AlgoliaAssetRequestExtender` interfaces
   - Extracts tags from the `cq:tags` JCR property
   - Uses the `TagsParserService` to parse and add tags to Algolia records

These extensions serve as reference implementations and can be customized to add additional fields, modify existing data, or implement custom indexing logic.

## Modules

The main parts of the project are:

* **core**: Java bundle containing extension implementations and OSGi services
* **it.tests**: Java based integration tests
* **all**: A single content package that embeds all of the compiled modules (bundles and content packages) including any vendor dependencies

## Building Custom Extensions

To create your own extensions for the Algolia AEM Connector:

1. Implement the appropriate extender interface:
   - `AlgoliaPageRequestExtender` for page indexing extensions
   - `AlgoliaAssetRequestExtender` for asset indexing extensions
   - Both interfaces if your extension should handle both pages and assets (like `DefaultAlgoliaTagsExtractor`)

2. Register your implementation as an OSGi service component using `@Component` annotation

3. Optionally annotate with `@ComponentServiceProperties` to provide a human-readable description that will appear in the Algolia cloud config dropdown

4. Your extension will be automatically invoked during the indexing process

Refer to the example implementations in the `core` module:
- `DefaultAlgoliaPdfTextExtractor` - Example of an asset extender with configuration
- `DefaultAlgoliaTagsExtractor` - Example of a dual-purpose extender for both pages and assets

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
