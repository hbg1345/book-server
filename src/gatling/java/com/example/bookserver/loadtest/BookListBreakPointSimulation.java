package com.example.bookserver.loadtest;

/** Finds the capacity limit of the paginated catalogue list and nothing else. */
public final class BookListBreakPointSimulation extends EndpointBreakPointSimulation {

    public BookListBreakPointSimulation() {
        super(BookCatalog.bookListScenario(
                "book-list-breakpoint",
                LoadTestConfig.thinkTimeMin(0),
                LoadTestConfig.thinkTimeMax(0)));
    }
}
