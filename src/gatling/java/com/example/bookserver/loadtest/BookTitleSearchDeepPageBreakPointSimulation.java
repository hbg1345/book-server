package com.example.bookserver.loadtest;

/** Measures the OFFSET cost of broad title searches at page 100 by default. */
public final class BookTitleSearchDeepPageBreakPointSimulation
        extends EndpointBreakPointSimulation {

    public BookTitleSearchDeepPageBreakPointSimulation() {
        super(BookCatalog.titleSearchDeepPageComparisonScenario(
                "book-title-search-deep-page-breakpoint"));
    }
}
