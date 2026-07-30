package com.example.bookserver.common;

import java.util.UUID;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Central factory for entity ids. Uses UUIDv7 (time-ordered) so ids are globally
 * unique yet roughly sortable by creation time, which keeps the PK B-tree index
 * dense and un-fragmented on insert (see UuidBenchmarkTest for the measured effect).
 */
public final class Uuids {

    private Uuids() {
    }

    public static UUID newId() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}
