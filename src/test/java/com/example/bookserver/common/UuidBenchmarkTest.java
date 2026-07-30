package com.example.bookserver.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.f4b6a3.uuid.UuidCreator;

/**
 * Compares UUIDv4 (random) vs UUIDv7 (time-ordered) as a primary key on PostgreSQL:
 *   1) bulk-insert throughput + PK index size / density / fragmentation
 *   2) range-scan cost (time + pages touched)
 *
 * shared_buffers is forced tiny (smaller than the index) so range scans cannot keep
 * the whole index cached between iterations -> page re-reads show up in EXPLAIN BUFFERS.
 *
 * Each metric also prints a "v4/v7 (%)" row = v4 value as a percentage of v7.
 *
 * Tagged "benchmark" so it is skipped by the normal `./gradlew test`.
 * Run with:  ./gradlew benchmark
 */
@Tag("benchmark")
@Testcontainers
public class UuidBenchmarkTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withCommand("postgres",
                    "-c", "shared_buffers=8MB",       // < index size, defeats PG's own cache
                    "-c", "debug_io_direct=data");     // bypass the OS page cache -> real disk I/O

    private static final int N = 500_000;     // rows per table
    private static final int BATCH = 1_000;
    private static final int RANGE = 10_000;    // rows fetched per range scan
    private static final int ITER = 10;      // range scans per table
    private static final String PAYLOAD = "x".repeat(64);

    @Test
    void v4_vs_v7() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.setAutoCommit(false);
            setup(conn);

            Result v4 = load(conn, "t_v4", UUID::randomUUID);
            Result v7 = load(conn, "t_v7", UuidCreator::getTimeOrderedEpoch);

            // VACUUM needs autocommit; also enables index-only scans for the range test
            conn.setAutoCommit(true);
            vacuum(conn, "t_v4");
            vacuum(conn, "t_v7");

            rangeScan(conn, "t_v4", v4);
            rangeScan(conn, "t_v7", v7);

            System.out.println();
            System.out.println("=============== UUID v4 vs v7 (N=" + N + ") ===============");
            System.out.printf("%-14s %11s %11s %13s %14s%n",
                    "variant", "insert(ms)", "index(MB)", "leaf_density", "fragmentation");
            printIndex("v4 (random)", v4);
            printIndex("v7 (ordered)", v7);
            System.out.printf("%-14s %11s %11s %13s %14s%n",
                    "v4/v7 (%)",
                    pct(v4.insertMillis, v7.insertMillis),
                    pct(v4.indexBytes, v7.indexBytes),
                    pct(v4.avgLeafDensity, v7.avgLeafDensity),
                    pct(v4.leafFragmentation, v7.leafFragmentation));

            System.out.println("--- range scan: " + RANGE + " rows x " + ITER + " iters ---");
            System.out.printf("%-14s %14s %18s%n", "variant", "avg scan(ms)", "buffers(hit/read)");
            printRange("v4 (random)", v4);
            printRange("v7 (ordered)", v7);
            System.out.printf("%-14s %14s %18s%n",
                    "v4/v7 (%)", pct(v4.avgScanMicros, v7.avgScanMicros), "-");
            System.out.println("=========================================================");
        }
    }

    private void setup(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pgstattuple");
            st.execute("CREATE TABLE t_v4 (id UUID PRIMARY KEY, payload TEXT NOT NULL)");
            st.execute("CREATE TABLE t_v7 (id UUID PRIMARY KEY, payload TEXT NOT NULL)");
        }
        conn.commit();
    }

    private Result load(Connection conn, String table, Supplier<UUID> idGen) throws Exception {
        System.out.println("[" + table + "] inserting " + N + " rows...");
        long start = System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + table + " (id, payload) VALUES (?, ?)")) {
            for (int i = 1; i <= N; i++) {
                ps.setObject(1, idGen.get());   // ids inserted in generation order
                ps.setString(2, PAYLOAD);
                ps.addBatch();
                if (i % BATCH == 0) {
                    ps.executeBatch();
                    conn.commit();
                }
                if (i % 100_000 == 0) {
                    System.out.println("[" + table + "] inserted " + i + " / " + N);
                }
            }
            ps.executeBatch();
            conn.commit();
        }
        Result r = new Result();
        r.insertMillis = (System.nanoTime() - start) / 1_000_000;
        readIndexStats(conn, table, r);
        return r;
    }

    private void vacuum(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("VACUUM ANALYZE " + table);   // sets visibility map -> index-only scans
        }
    }

    private void readIndexStats(Connection conn, String table, Result r) throws Exception {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT pg_relation_size('" + table + "_pkey')")) {
                rs.next();
                r.indexBytes = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT avg_leaf_density, leaf_fragmentation FROM pgstatindex('" + table + "_pkey')")) {
                rs.next();
                r.avgLeafDensity = rs.getDouble(1);
                r.leafFragmentation = rs.getDouble(2);
            }
        }
    }

    private void rangeScan(Connection conn, String table, Result r) throws Exception {
        System.out.println("[" + table + "] range scan " + ITER + " iters (range=" + RANGE + ")...");
        List<UUID> starts = sampleStartKeys(conn, table, ITER);

        String sql = "SELECT id FROM " + table + " WHERE id >= ? ORDER BY id LIMIT " + RANGE;
        long total = 0;
        int done = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (UUID start : starts) {
                ps.setObject(1, start);
                long t0 = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rs.getObject(1);   // consume
                }
                total += System.nanoTime() - t0;
                if (++done % 20 == 0) {
                    System.out.println("[" + table + "] scan " + done + " / " + ITER);
                }
            }
        }
        r.avgScanMicros = total / 1_000 / ITER;
        r.buffers = explainBuffers(conn, table, starts.get(0));
    }

    // grab ITER real keys at random so both tables scan valid, comparable ranges
    private List<UUID> sampleStartKeys(Connection conn, String table, int count) throws Exception {
        List<UUID> keys = new ArrayList<>(count);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id FROM " + table + " ORDER BY random() LIMIT " + count)) {
            while (rs.next()) keys.add((UUID) rs.getObject(1));
        }
        return keys;
    }

    // pull the top-level "Buffers: shared hit=.. read=.." line from EXPLAIN (ANALYZE, BUFFERS)
    private String explainBuffers(Connection conn, String table, UUID start) throws Exception {
        String sql = "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM " + table
                + " WHERE id >= ? ORDER BY id LIMIT " + RANGE;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setObject(1, start);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String line = rs.getString(1).trim();
                    if (line.startsWith("Buffers:")) {
                        return line.replace("Buffers: ", "");
                    }
                }
            }
        }
        return "n/a";
    }

    private void printIndex(String name, Result r) {
        System.out.printf("%-14s %11d %11.1f %13.2f %14.2f%n",
                name, r.insertMillis, r.indexBytes / 1024.0 / 1024.0, r.avgLeafDensity, r.leafFragmentation);
    }

    private void printRange(String name, Result r) {
        System.out.printf("%-14s %14.3f %18s%n", name, r.avgScanMicros / 1000.0, r.buffers);
    }

    // v4 value as a percentage of v7 (e.g. 125% means v4 is 1.25x v7); "-" if v7 is 0
    private String pct(double v4, double v7) {
        if (v7 == 0) return "-";
        return String.format("%.0f%%", v4 / v7 * 100.0);
    }

    private static class Result {
        long insertMillis;
        long indexBytes;
        double avgLeafDensity;
        double leafFragmentation;
        long avgScanMicros;
        String buffers;
    }
}
