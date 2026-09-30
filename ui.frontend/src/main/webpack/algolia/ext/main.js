window.AlgoliaConfigs = {
    default: {
        searchPage: {
            getSearchRenderState: {
                getSearchStatus() {
                    const searchIndexesWrapper = Array.from(
                        this.querySelectorAll("algolia-index"),
                    );
                    if (searchIndexesWrapper.length > 0) {
                        if (
                            this.search.status === "stalled" ||
                            this.search.status === "loading"
                        ) {
                            searchIndexesWrapper.forEach((wrapper) => {
                                const loaderWrapper = wrapper.querySelector("algolia-loader");
                                if (loaderWrapper) {
                                    const hitWrapper =
                                        wrapper.querySelector("algolia-hits") ||
                                        wrapper.querySelector("algolia-infinite-hits");
                                    hitWrapper.style.display = "none";
                                    wrapper.querySelector("algolia-loader").style.display =
                                        "block";
                                }
                            });
                        } else if (this.search.status === "error") {
							// Error state handling
                            // container.innerText = "Error search results";
                        } else {
                            searchIndexesWrapper.forEach((wrapper) => {
                                const hitWrapper =
                                    wrapper.querySelector("algolia-hits") ||
                                    wrapper.querySelector("algolia-infinite-hits");
                                wrapper.querySelector("algolia-loader").style.display =
                                    "none";
                                hitWrapper.style.display = "block";
                            });
                        }
                    }
                },
            },
            panel: {
                hidden(options) {
                    return options?.results?.nbHits === 0;
                },
                collapsed: ({
                    state
                }) => {
                    return state?.query?.length === 0;
                },
                cssClasses: {
                    root: "MyCustomPanel",
                    header: ["MyCustomPanelHeader", "MyCustomPanelHeader--subclass"],
                },
            },
            customPagination: {
                variation1: {
                    renderPagination(renderOptions, isFirstRender) {
                        const {
                            pages,
                            currentRefinement,
                            nbHits,
                            nbPages,
                            isFirstPage,
                            isLastPage,
                            refine,
                            createURL,
                            widgetParams
                        } = renderOptions;

                        const container = widgetParams.container;

                        container.innerHTML = `
              <ul class='custom-pagination-wrapper'>
                ${
                  `
                    <li>
                      <a
                        href="${createURL(0)}"
                        data-value="${0}"
                        class="${isFirstPage ? 'disabled' : ''}"
                      >
                        First
                      </a>
                    </li>
                    <li>
                      <a
                        href="${createURL(currentRefinement - 1)}"
                        data-value="${currentRefinement - 1}"
                        class="${isFirstPage ? 'disabled' : ''}"
                      >
                        Previous
                      </a>
                    </li>
                    `
                  }
                  ${currentRefinement + 1} of ${nbPages} page(s) for ${nbHits} hit(s)
                  ${
                    `
                      <li>
                        <a
                          href="${createURL(currentRefinement + 1)}"
                          data-value="${currentRefinement + 1}"
                          class="${isLastPage ? 'disabled' : ''}"
                        >
                          Next
                        </a>
                      </li>
                      <li>
                        <a
                          href="${createURL(nbPages - 1)}"
                          data-value="${nbPages - 1}"
                          class="${isLastPage ? 'disabled' : ''}"
                        >
                          Last
                        </a>
                      </li>
                      `
                  }
              </ul>
            `;

                        [...container.querySelectorAll('a')].forEach(element => {
                            element.addEventListener('click', event => {
                                event.preventDefault();
                                refine(event.currentTarget.dataset.value);
                            });
                        });
                    }
                },
                variation2: {
                    renderPagination(renderOptions, isFirstRender) {
                        const {
                            pages,
                            currentRefinement,
                            nbHits,
                            nbPages,
                            isFirstPage,
                            isLastPage,
                            refine,
                            createURL,
                            widgetParams
                        } = renderOptions;

                        const container = widgetParams.container;

                        container.innerHTML = `
              <ul class='custom-pagination-wrapper'>
                ${
                  `
                    <li>
                      <a
                        href="${createURL(0)}"
                        data-value="${0}"
                        class="${isFirstPage ? 'disabled' : ''}"
                      >
                        First
                      </a>
                    </li>
                    <li>
                      <a
                        href="${createURL(currentRefinement - 1)}"
                        data-value="${currentRefinement - 1}"
                        class="${isFirstPage ? 'disabled' : ''}"
                      >
                        Previous
                      </a>
                    </li>
                    `
                  }
                  ${pages
                    .map(
                      page => `
                        <li>
                          <a
                            href="${createURL(page)}"
                            data-value="${page}"
                            style="font-weight: ${currentRefinement === page ? 'bold' : ''}"
                          >
                            ${page + 1}
                          </a>
                        </li>
                      `
                    )
                    .join('')
                  }
                  ${
                    `
                      <li>
                        <a
                          href="${createURL(currentRefinement + 1)}"
                          data-value="${currentRefinement + 1}"
                          class="${isLastPage ? 'disabled' : ''}"
                        >
                          Next
                        </a>
                      </li>
                      <li>
                        <a
                          href="${createURL(nbPages - 1)}"
                          data-value="${nbPages - 1}"
                          class="${isLastPage ? 'disabled' : ''}"
                        >
                          Last
                        </a>
                      </li>
                      `
                  }
              </ul>
            `;

                        [...container.querySelectorAll('a')].forEach(element => {
                            element.addEventListener('click', event => {
                                event.preventDefault();
                                refine(event.currentTarget.dataset.value);
                            });
                        });
                    }
                }
            },
            autocomplete: {
                reshape({
                    sourcesBySourceId,
                    sources,
                    state,
                    panelContainerClassName,
                }) {
                    const {
                        recentSearchesPlugin: recentSearches,
                        querySuggestionsPlugin: querySuggestions,
                        ...rest
                    } = sourcesBySourceId;

                    const autocompleteSourceHookPrefix = "plugin_";
                    const plugins = {};

                    // Loop through the original object
                    for (const key in sourcesBySourceId) {
                        if (key.startsWith(autocompleteSourceHookPrefix)) {
                            // If the key starts with the specified prefix, add it to the filtered object
                            plugins[key] = sourcesBySourceId[key];
                        }
                    }

                    const pluginsSourceIdsToExclude = [];
                    if (plugins) {
                        for (const objKey in plugins) {
                            if (plugins.hasOwnProperty(objKey)) {
                                pluginsSourceIdsToExclude.push(objKey);
                            }
                        }
                    }

                    const sourceIdsToExclude = [
                        "recentSearchesPlugin",
                        "querySuggestionsPlugin",
                        ...pluginsSourceIdsToExclude,
                    ];

                    const filteredSourcesData = sources.filter(
                        (source) => !sourceIdsToExclude.includes(source.sourceId),
                    );

                    let sourcesData;
                    if (sources) {
                        const sourceIDS = filteredSourcesData.map((element) => {
                            return element.sourceId;
                        });

                        sourcesData = sourceIDS.map((sourceId) => {
                            return sourcesBySourceId[sourceId];
                        });
                    }

                    const hasResults =
                        filteredSourcesData.reduce(
                            (prev, curr) => prev + curr.getItems().length,
                            0,
                        ) > 0;

                    if (state.query === "") {
                        return [
                            plugins.plugin_links,
                            plugins.plugin_recommendations,
                            sourcesData[2],
                            sourcesData[0],
                            sourcesData[1],
                            querySuggestions,
                        ];
                    } else if (state.query && hasResults) {
                        return [
                            plugins.plugin_recommendations,
                            plugins.plugin_links,
                            sourcesData[0],
                            querySuggestions,
                            sourcesData[2],
                            sourcesData[1],
                        ];
                    } else if (state.query && !hasResults) {
                        return [
                            querySuggestions,
                            sourcesData[0]
                        ];
                    }
                },
                classNames: {
                    panel: 'aa-Panel-InstantSearch'
                }
            },
			insights: {
                onItemsChange({
                    insights,
                    insightsEvents
                }) {
                    const events = insightsEvents.map((insightsEvent) => ({
                        ...insightsEvent,
                        eventName: "Product Viewed from Autocomplete",
                    }));
                    insights.viewedObjectIDs(...events);
                },
                onSelect({
                    insights,
                    insightsEvents
                }) {
                    const events = insightsEvents.map((insightsEvent) => ({
                        ...insightsEvent,
                        eventName: "Product Selected from Autocomplete",
                    }));
                    insights.clickedObjectIDsAfterSearch(...events);
                },
            },
        },
        globalSearch: {
            insights: {
                onItemsChange({
                    insights,
                    insightsEvents
                }) {
                    const events = insightsEvents.map((insightsEvent) => ({
                        ...insightsEvent,
                        eventName: "Product Viewed from Autocomplete",
                    }));
                    insights.viewedObjectIDs(...events);
                },
                onSelect({
                    insights,
                    insightsEvents
                }) {
                    const events = insightsEvents.map((insightsEvent) => ({
                        ...insightsEvent,
                        eventName: "Product Selected from Autocomplete",
                    }));
                    insights.clickedObjectIDsAfterSearch(...events);
                },
            },
        },
    },
};
