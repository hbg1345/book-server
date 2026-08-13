package com.example.bookserver.loadtest;

/** Baseline for the deep-page comparison: broad title searches at page zero. */
public final class BookTitleSearchFirstPageBreakPointSimulation
        extends EndpointBreakPointSimulation {

    public BookTitleSearchFirstPageBreakPointSimulation() {
        super(BookCatalog.titleSearchFirstPageComparisonScenario(
                "book-title-search-first-page-breakpoint"));
    }
}
