# UI Apps Module

The `ui.apps` module contains the `/apps` structure that defines AEM components, templates, client libraries, and application-specific configurations. This module is deployed as a content package (ZIP) to AEM and contains all the authorable components and their configurations.

## Purpose

This module provides:
- **AEM Components**: HTL templates, dialogs, and component configurations
- **Client Libraries**: CSS and JavaScript generated from `ui.frontend`
- **Component Structure**: Component hierarchy and organization
- **Internationalization**: i18n files for multi-language support
- **Oak Indexes**: Search index definitions

## Project Structure

```
ui.apps/
├── pom.xml                          # Maven POM file
├── src/
│   └── main/
│       └── content/
│           ├── jcr_root/
│           │   ├── apps/
│           │   │   └── algolia/
│           │   │       ├── clientlibs/          # Client-side libraries
│           │   │       │   ├── clientlib-base/
│           │   │       │   ├── clientlib-dependencies/
│           │   │       │   ├── clientlib-grid/
│           │   │       │   ├── clientlib-hooksInitialization/
│           │   │       │   └── clientlib-site/
│           │   │       ├── components/          # AEM Components
│           │   │       │   ├── browse/
│           │   │       │   ├── globalsearch/
│           │   │       │   ├── instantsearch/
│           │   │       │   └── recommend/
│           │   │       └── i18n/                # Internationalization
│           │   │           └── en.json
│           │   └── _oak_index/                  # Oak index definitions
│           │       └── algoliaHitTemplate/
│           └── META-INF/
│               └── vault/
│                   └── filter.xml              # Vault filter definition
└── target/                          # Build output directory
```

## Components

### Component Structure

Each component follows AEM component structure:

```
component-name/
├── _cq_dialog/                      # Touch UI dialog
├── _cq_design_dialog/               # Design dialog
├── _cq_editConfig.xml               # Edit configuration
├── _cq_icon.png                     # Component icon
├── _cq_template/                    # Component template
├── component.html                    # Main HTL template
├── model.html                        # Model template
├── placeholder.html                 # Placeholder template
└── view.html                        # View template
```

### InstantSearch Component (`instantsearch/`)

The main container component for Algolia InstantSearch implementation.

#### Structure

```
instantsearch/
├── _cq_dialog/                      # Component dialog
├── _cq_design_dialog/               # Design dialog
├── _cq_editConfig.xml               # Edit configuration
├── _cq_icon.png                     # Component icon
├── _cq_template/                    # Component template
├── clientlibs/                      # Authoring clientlibs
│   └── authoring/
│       ├── css/
│       │   └── authoring.css
│       ├── js/
│       │   └── index.js
│       ├── css.txt
│       └── js.txt
├── facets/                          # Facets sub-component
│   ├── _cq_dialog/
│   ├── _cq_editConfig.xml
│   ├── facets.html
│   ├── facets.list.html
│   ├── default.html
│   ├── facet/                       # Individual facet component
│   │   ├── _cq_dialog/
│   │   ├── _cq_editConfig.xml
│   │   └── facet.html
│   └── tabs/
│       └── basic/
├── indices/                         # Indices sub-component
│   ├── _cq_dialog/
│   ├── _cq_editConfig.xml
│   ├── indices.html
│   ├── stackview.html
│   ├── tabview.html
│   ├── customview.html
│   ├── index/                       # Individual index component
│   │   ├── _cq_dialog/
│   │   ├── _cq_editConfig.xml
│   │   ├── index.html
│   │   ├── autocompleteIndex.html
│   │   ├── view.html
│   │   ├── injectedhits/           # Injected hits component
│   │   │   ├── _cq_editConfig.xml
│   │   │   ├── injectedhits.html
│   │   │   └── hit/                # Individual hit component
│   │   │       ├── _cq_dialog/
│   │   │       ├── _cq_editConfig.xml
│   │   │       ├── hit.html
│   │   │       └── clientlibs/
│   │   │           └── editor/
│   │   ├── templatedatasource/      # Template data source
│   │   │   └── templatedatasource.html
│   │   ├── noresultstemplatedatasource/
│   │   │   └── noresultstemplatedatasource.html
│   │   ├── clientlibs/
│   │   │   └── authoring/
│   │   └── tabs/
│   │       ├── basic/
│   │       ├── advanced/
│   │       ├── components/
│   │       ├── configure/
│   │       └── templates/
│   └── touchuilibs/                 # Touch UI libraries
│       ├── container.js
│       ├── lists.js
│       ├── aem-core-overwrite.css
│       ├── css.txt
│       └── js.txt
├── searchbar/                       # Searchbar sub-component
│   ├── _cq_dialog/
│   ├── _cq_editConfig.xml
│   ├── searchbar.html
│   ├── templatedatasource/
│   │   └── templatedatasource.html
│   └── tabs/
│       ├── basic/
│       ├── autocomplete/
│       └── voice/
├── tabs/                            # Dialog tabs
│   ├── basic/
│   └── styling/
├── instantsearch.html               # Main template
├── template.html                    # Template template
├── view.html                        # View template
├── model.html                       # Model template
└── placeholder.html                 # Placeholder template
```

#### Features

- **Multi-index Support**: Configure and manage multiple search indices
- **Facet Management**: Add and configure search facets/refinements
- **Searchbar Configuration**: Configure search input with autocomplete
- **Template Data Sources**: Dynamic template configuration
- **Authoring Experience**: Enhanced authoring tools and dialogs

### GlobalSearch Component (`globalsearch/`)

Implementation for global site-wide search functionality.

#### Structure

```
globalsearch/
├── _cq_dialog/
├── _cq_design_dialog/
├── _cq_icon.png
├── _cq_template/
├── indices/                         # Indices configuration
│   └── indices.html
├── globalsearch.html
├── template.html
├── view.html
├── model.html
└── placeholder.html
```

#### Features

- **Site-wide Search**: Global search across entire site
- **Multi-index Support**: Search across multiple indices
- **Simple Configuration**: Easy-to-use dialog for configuration

### Browse Component (`browse/`)

Components for browsing/searching functionality.

#### Structure

```
browse/
├── _cq_dialog/
├── _cq_design_dialog/
├── _cq_icon.png
├── _cq_template/
├── browse.html
├── view.html
├── model.html
└── placeholder.html
```

### Recommend Component (`recommend/`)

Components for Algolia Recommend features.

#### Structure

```
recommend/
├── _cq_dialog/
├── _cq_design_dialog/
├── _cq_editConfig.xml
├── _cq_icon.png
├── recommend.html                   # Main recommend template
├── related.html                     # Related products template
├── frequentlyBoughtTogether.html    # Frequently bought together
├── lookingSimilar.html              # Looking similar items
├── trendingItems.html               # Trending items template
├── template.html
├── model.html
├── placeholder.html
├── modeldatasource/                 # Model data source
│   ├── modeldatasource.html
│   ├── models.json                  # Standard models
│   └── cifmodels.json              # CIF models
└── tabs/                            # Dialog tabs
    ├── basic/
    ├── configure/
    ├── recommend/
    └── template/
```

#### Recommendation Types

1. **Related Products** (`related.html`)
   - Shows products related to current item
   - Based on Algolia Recommend Related Products model

2. **Frequently Bought Together** (`frequentlyBoughtTogether.html`)
   - Shows products frequently bought with current item
   - Based on Algolia Recommend FBT model

3. **Looking Similar** (`lookingSimilar.html`)
   - Shows products similar to current item
   - Based on Algolia Recommend Looking Similar model

4. **Trending Items** (`trendingItems.html`)
   - Shows currently trending products
   - Based on Algolia Recommend Trending Items model

## Client Libraries

Client libraries are generated by the `ui.frontend` module and synchronized to this module.

### clientlib-site

- **Categories**: `algolia-extensions.site`
- **Location**: `/apps/algolia/clientlibs/clientlib-site`
- **Contents**:
  - Compiled TypeScript/JavaScript
  - Compiled SCSS/CSS
  - Resources (fonts, images)

### clientlib-dependencies

- **Categories**: `algolia-extensions.dependencies`
- **Location**: `/apps/algolia/clientlibs/clientlib-dependencies`
- **Contents**: Third-party dependencies

### clientlib-base

- **Location**: `/apps/algolia/clientlibs/clientlib-base`
- **Contents**: Base styles and scripts

### clientlib-grid

- **Location**: `/apps/algolia/clientlibs/clientlib-grid`
- **Contents**: Grid system styles (LESS-based)

### clientlib-hooksInitialization

- **Categories**: `algolia-extensions.hooksInitialization`
- **Location**: `/apps/algolia/clientlibs/clientlib-hooksInitialization`
- **Contents**: Initialization hooks JavaScript

## Internationalization

### i18n Files

- **Location**: `/apps/algolia/i18n`
- **Files**: `en.json` (English translations)
- **Usage**: Component labels, messages, and tooltips

## Oak Indexes

### algoliaHitTemplate Index

- **Location**: `/_oak_index/algoliaHitTemplate`
- **Purpose**: Search index for hit templates

## Vault Filter

The `META-INF/vault/filter.xml` defines which JCR paths are included in the content package:

```xml
<filter root="/apps/algolia/clientlibs"/>
<filter root="/apps/algolia/components"/>
<filter root="/apps/algolia/i18n"/>
<filter root="/oak:index/algoliaHitTemplate"/>
```

## Building

### Prerequisites

- Maven 3.3.9+
- AEM SDK or running AEM instance
- `ui.frontend` module must be built first (for clientlibs)

### Build Commands

**Build the content package:**
```bash
mvn clean install
```

**Build and install to AEM:**
```bash
mvn clean install -PautoInstallPackage
```

### Build Process

1. Validates HTL scripts using HTL Maven Plugin
2. Packages JCR content into Vault package
3. Creates ZIP file: `target/algolia-aem-extensions.ui.apps-{version}.zip`
4. Installs to AEM (if profile is active)

### HTL Validation

The build validates HTL scripts:
- Syntax checking
- Expression validation
- Java class generation for use statements
- Allowed expression options validation

## Dependencies

### Module Dependencies

- **core**: Uses Sling Models in HTL templates
- **ui.frontend**: Client libraries are generated from this module

### AEM Dependencies

- AEM SDK API (provided scope)
- HTL Runtime (for HTL validation)

## Component Authoring

### Adding a New Component

1. Create component directory under `/apps/algolia/components`
2. Add HTL template files:
   - `component.html` (main template)
   - `model.html` (optional)
   - `placeholder.html` (optional)
3. Add dialog configuration:
   - `_cq_dialog/.content.xml` (Touch UI)
   - `_cq_dialog.xml` (Classic UI, if needed)
4. Add component icon: `_cq_icon.png`
5. Create Sling Model in `core` module (if needed)
6. Build and deploy

### Component Dialog Structure

Touch UI dialogs use Granite UI components:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:sling="http://sling.apache.org/jcr/sling/1.0"
          xmlns:granite="http://www.adobe.com/jcr/granite/1.0"
          xmlns:cq="http://www.day.com/jcr/cq/1.0"
          jcr:primaryType="nt:unstructured"
          sling:resourceType="cq/gui/components/authoring/dialog">
    <content>
        <items>
            <tabs>
                <items>
                    <basic>
                        <!-- Tab content -->
                    </basic>
                </items>
            </tabs>
        </items>
    </content>
</jcr:root>
```

## Best Practices

1. **HTL Templates**: Use HTL (Sightly) for all templates, avoid JSP
2. **Component Structure**: Follow AEM component structure conventions
3. **Dialogs**: Use Touch UI dialogs (Granite UI)
4. **Clientlibs**: Don't manually edit generated clientlibs
5. **i18n**: Use i18n for all user-facing text
6. **Testing**: Test components in both Author and Publish modes

## Troubleshooting

### Component Not Appearing

- Check component is in correct location: `/apps/algolia/components`
- Verify `_cq_icon.png` exists
- Check component group in policy configuration

### HTL Validation Errors

- Check HTL syntax
- Verify use statement classes exist
- Check allowed expression options

### Clientlibs Not Loading

- Verify `ui.frontend` module was built
- Check clientlib categories match page policy
- Verify clientlib files exist in JCR


