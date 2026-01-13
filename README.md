# Algolia AEM Extensions

This project provides guidance on building extensions for the Algolia AEM Connector. The project includes two indexing extensions that can be used as reference implementations and starting points for developing custom extensions.

## Disclaimer

**No Warranties or SLA**: This project is provided as-is without any warranties, express or implied. There are no service level agreements (SLA) or guarantees regarding issues, bugs, or support. Use at your own risk.

## Overview

This project demonstrates how to build custom extensions for the Algolia AEM Connector. Extensions allow you to customize and enhance the indexing behavior of the connector to meet your specific requirements.

## Indexing Extensions

The project provides two indexing extensions:

1. **Page Request Extender** - Extends the indexing behavior for AEM pages
2. **Asset Request Extender** - Extends the indexing behavior for AEM assets

These extensions serve as reference implementations and can be customized to add additional fields, modify existing data, or implement custom indexing logic.

## Modules

The main parts of the project are:

* [core:](core/README.md) Java bundle containing extension implementations, OSGi services, listeners, schedulers, and component-related Java code
* [it.tests:](it.tests/README.md) Java based integration tests
* [ui.apps:](ui.apps/README.md) contains the /apps (and /etc) parts of the project, including JS&CSS clientlibs, components, and templates
* [ui.frontend:](ui.frontend.general/README.md) an optional dedicated front-end build mechanism (Angular, React or general Webpack project)
* all: a single content package that embeds all of the compiled modules (bundles and content packages) including any vendor dependencies
* analyse: this module runs analysis on the project which provides additional validation for deploying into AEMaaCS

## Building Custom Extensions

To create your own extensions for the Algolia AEM Connector:

1. Implement the appropriate extender interface:
   - `AlgoliaPageRequestExtender` for page indexing extensions
   - `AlgoliaAssetRequestExtender` for asset indexing extensions

2. Register your implementation as an OSGi service component

3. Your extension will be automatically invoked during the indexing process

Refer to the example implementations in the `core` module for guidance on building custom extensions.

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

Or to deploy only a single content package, run in the sub-module directory (i.e `ui.apps`)

    mvn clean install -PautoInstallPackage

## Documentation

The build process also generates documentation in the form of README.md files in each module directory for easy reference. Depending on the options you select at build time, the content may be customized to your project.

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

## Static Analysis

The `analyse` module performs static analysis on the project for deploying into AEMaaCS. It is automatically
run when executing

    mvn clean install

from the project root directory. Additional information about this analysis and how to further configure it
can be found here https://github.com/adobe/aemanalyser-maven-plugin

## ClientLibs

The frontend module is made available using an [AEM ClientLib](https://helpx.adobe.com/experience-manager/6-5/sites/developing/using/clientlibs.html). When executing the NPM build script, the app is built and the [`aem-clientlib-generator`](https://github.com/wcm-io-frontend/aem-clientlib-generator) package takes the resulting build output and transforms it into such a ClientLib.

A ClientLib will consist of the following files and directories:

- `css/`: CSS files which can be requested in the HTML
- `css.txt` (tells AEM the order and names of files in `css/` so they can be merged)
- `js/`: JavaScript files which can be requested in the HTML
- `js.txt` (tells AEM the order and names of files in `js/` so they can be merged
- `resources/`: Source maps, non-entrypoint code chunks (resulting from code splitting), static assets (e.g. icons), etc.

## Maven settings

The project comes with the auto-public repository configured. To setup the repository in your Maven settings, refer to:

    http://helpx.adobe.com/experience-manager/kb/SetUpTheAdobeMavenRepository.html
