package com.example.bookserver.loadtest;

/** Finds the capacity limit of a book detail lookup and nothing else. */
public final class BookDetailBreakPointSimulation extends EndpointBreakPointSimulation {

    public BookDetailBreakPointSimulation() {
        super(BookCatalog.bookDetailScenario(
                "book-detail-breakpoint",
                LoadTestConfig.thinkTimeMin(0),
                LoadTestConfig.thinkTimeMax(0)));
    }
}
