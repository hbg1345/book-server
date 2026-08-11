package com.example.bookserver.loadtest;

/** Finds the capacity limit of case-insensitive title search and nothing else. */
public final class BookTitleSearchBreakPointSimulation extends EndpointBreakPointSimulation {

    public BookTitleSearchBreakPointSimulation() {
        super(BookCatalog.titleSearchScenario(
                "book-title-search-breakpoint",
                LoadTestConfig.thinkTimeMin(0),
                LoadTestConfig.thinkTimeMax(0)));
    }
}
