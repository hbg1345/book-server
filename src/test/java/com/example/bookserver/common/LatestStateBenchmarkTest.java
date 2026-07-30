package com.example.bookserver.common;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * "List a user's orders with each order's current state" — fetch the latest state of one
 * user's ORDERS_PER_USER orders in a single query. Two storage designs compared:
 *
 *   A) append-only history : DISTINCT ON (greatest-per-group) over the (purchase_uuid, id)
 *      index — v7 ids are time-ordered so the largest id per order is its latest state.
 *   B) current-state table : a plain multi-key lookup (WHERE purchase_uuid = ANY(...)).
 *
 * Model: NUM_USERS users, each with ORDERS_PER_USER orders, each order STATES_PER_ORDER
 * state changes.  history rows = users*orders*states ; current rows = users*orders.
 *
 * Tagged "benchmark"; run with:  ./gradlew benchmark --tests "*LatestStateBenchmarkTest"
 */
@Tag("benchmark")
@Testcontainers
public class LatestStateBenchmarkTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withSharedMemorySize(512L * 1024 * 1024)
            .withCommand("postgres",
                    "-c", "shared_buffers=64MB",
                    "-c", "max_parallel_workers_per_gather=0",
                    "-c", "max_parallel_maintenance_workers=0");

    private static final int NUM_USERS = 50_000;
    private static final int ORDERS_PER_USER = 20;
    private static final int STATES_PER_ORDER = 5;
    private static final int TOTAL_ORDERS = NUM_USERS * ORDERS_PER_USER;   // = purchases
    private static final int BATCH = 1_000;
    private static final String STATE = "SHIPPING";

    @Test
    void history_vs_current() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            conn.setAutoCommit(false);
            setup(conn);

            List<UUID> orders = generateOrderIds();     // one uuid per order (purchase)
            loadHistory(conn, orders);
            loadCurrent(conn, orders);

            conn.setAutoCommit(true);
            vacuum(conn, "ph_history");
            vacuum(conn, "ph_current");

            // group orders into users of ORDERS_PER_USER; shuffle so each user's orders
            // are scattered across the index (realistic)
            List<UUID> shuffled = new ArrayList<>(orders);
            Collections.shuffle(shuffled, new Random(42));

            long histMs = readPerUser(conn, shuffled,
                    "SELECT DISTINCT ON (purchase_uuid) purchase_uuid, purchase_state "
                    + "FROM ph_history WHERE purchase_uuid = ANY(?) ORDER BY purchase_uuid, id DESC",
                    "history");
            long currMs = readPerUser(conn, shuffled,
                    "SELECT purchase_uuid, purchase_state FROM ph_current WHERE purchase_uuid = ANY(?)",
                    "current");

            long histSize = totalSize(conn, "ph_history");
            long currSize = totalSize(conn, "ph_current");

            System.out.println();
            System.out.println("======= latest of one user's " + ORDERS_PER_USER + " orders/query "
                    + "(users=" + NUM_USERS + ", states/order=" + STATES_PER_ORDER + ") =======");
            System.out.printf("%-10s %14s %16s %12s%n", "design", "read all(ms)", "per query(us)", "size(MB)");
            printRow("history", histMs, histSize);
            printRow("current", currMs, currSize);
            System.out.printf("history/current: time %.2fx, size %.2fx%n",
                    (double) histMs / currMs, (double) histSize / currSize);
            System.out.println("=====================================================================");
        }
    }

    private void setup(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE ph_history (id UUID PRIMARY KEY, purchase_uuid UUID NOT NULL, purchase_state VARCHAR(20) NOT NULL)");
            st.execute("CREATE INDEX idx_hist_latest ON ph_history (purchase_uuid, id)");
            st.execute("CREATE TABLE ph_current (purchase_uuid UUID PRIMARY KEY, purchase_state VARCHAR(20) NOT NULL)");
        }
        conn.commit();
    }

    private List<UUID> generateOrderIds() {
        List<UUID> list = new ArrayList<>(TOTAL_ORDERS);
        for (int i = 0; i < TOTAL_ORDERS; i++) {
            list.add(Uuids.newId());
        }
        return list;
    }

    private void loadHistory(Connection conn, List<UUID> orders) throws Exception {
        System.out.println("[history] inserting " + ((long) TOTAL_ORDERS * STATES_PER_ORDER) + " rows...");
        int rows = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ph_history (id, purchase_uuid, purchase_state) VALUES (?, ?, ?)")) {
            for (int o = 0; o < orders.size(); o++) {
                UUID order = orders.get(o);
                for (int s = 0; s < STATES_PER_ORDER; s++) {
                    ps.setObject(1, Uuids.newId());   // v7: later state changes get larger ids
                    ps.setObject(2, order);
                    ps.setString(3, STATE);
                    ps.addBatch();
                    if (++rows % BATCH == 0) {
                        ps.executeBatch();
                        conn.commit();
                    }
                }
                if ((o + 1) % 100_000 == 0) {
                    System.out.println("[history] " + (o + 1) + " / " + TOTAL_ORDERS + " orders");
                }
            }
            ps.executeBatch();
            conn.commit();
        }
    }

    private void loadCurrent(Connection conn, List<UUID> orders) throws Exception {
        System.out.println("[current] inserting " + TOTAL_ORDERS + " rows...");
        int rows = 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ph_current (purchase_uuid, purchase_state) VALUES (?, ?)")) {
            for (UUID order : orders) {
                ps.setObject(1, order);
                ps.setString(2, STATE);
                ps.addBatch();
                if (++rows % BATCH == 0) {
                    ps.executeBatch();
                    conn.commit();
                }
            }
            ps.executeBatch();
            conn.commit();
        }
    }

    // one query per user = fetch the latest state of that user's ORDERS_PER_USER orders
    private long readPerUser(Connection conn, List<UUID> orders, String sql, String label) throws Exception {
        System.out.println("[" + label + "] " + NUM_USERS + " per-user queries (" + ORDERS_PER_USER + " orders each)...");
        long start = System.nanoTime();
        int done = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < orders.size(); i += ORDERS_PER_USER) {
                int end = Math.min(i + ORDERS_PER_USER, orders.size());
                UUID[] batch = orders.subList(i, end).toArray(new UUID[0]);
                ps.setArray(1, conn.createArrayOf("uuid", batch));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rs.getString(2);
                }
                if (++done % 10_000 == 0) {
                    System.out.println("[" + label + "] " + done + " / " + NUM_USERS);
                }
            }
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void vacuum(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("VACUUM ANALYZE " + table);
        }
    }

    private long totalSize(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_total_relation_size('" + table + "')")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void printRow(String design, long readMs, long sizeBytes) {
        double perQueryUs = readMs * 1000.0 / NUM_USERS;
        System.out.printf("%-10s %14d %16.2f %12.1f%n",
                design, readMs, perQueryUs, sizeBytes / 1024.0 / 1024.0);
    }
}
