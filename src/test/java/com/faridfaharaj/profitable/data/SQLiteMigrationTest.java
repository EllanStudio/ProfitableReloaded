package com.faridfaharaj.profitable.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteMigrationTest {

    private static final String WORLD = "zeroblob(16)";
    private static final String ORDER_1 = "X'00000000000000000000000000000001'";
    private static final String ORDER_2 = "X'00000000000000000000000000000002'";
    private static final String ORDER_3 = "X'00000000000000000000000000000003'";
    private static final String ORDER_4 = "X'00000000000000000000000000000004'";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsProtectedExchangeSchemaWithDatabaseSequenceOrdering() throws Exception {
        try (Connection connection = open("fresh-migration.db")) {
            applyAll(connection);

            try (Statement statement = connection.createStatement()) {
                insertParents(statement);
                statement.executeUpdate("INSERT INTO account_assets VALUES (" + WORLD + ", 'server', 'EMD', 10)");

                insertOrder(statement, ORDER_2, 20);
                insertOrder(statement, ORDER_3, 10);
                insertOrder(statement, ORDER_1, 10);

                assertThrows(SQLException.class,
                        () -> statement.executeUpdate("UPDATE account_assets SET quantity = -1"));
                assertInvalidOrdersAreRejected(statement);
                assertThrows(SQLException.class,
                        () -> statement.executeUpdate("DELETE FROM assets WHERE asset_id = 'EMD'"));

                assertCreatedAtIsRequired(statement);
                assertEquals(List.of(
                                "00000000000000000000000000000002",
                                "00000000000000000000000000000003",
                                "00000000000000000000000000000001"),
                        deterministicPriceTimeOrder(statement));

                assertTrue(hasColumn(statement, "orders", "sequence_id"));
                assertTrue(hasColumn(statement, "orders", "escrow_amount"));
                assertTrue(hasColumn(statement, "orders", "maker_fee_remaining"));
                assertTrue(hasColumn(statement, "trade_executions", "request_id"));
                assertEquals(0, scalar(statement, "SELECT COUNT(*) FROM exchange_requests"));

                try (var result = statement.executeQuery("SELECT COUNT(*) FROM delivery_outbox")) {
                    result.next();
                    assertEquals(0, result.getInt(1));
                }
            }
        }
    }

    @Test
    void upgradesValidLegacyV2WithoutDroppingDataAndEnforcesV5() throws Exception {
        try (Connection connection = open("valid-legacy-migration.db")) {
            applyLegacyV2(connection);
            try (Statement statement = connection.createStatement()) {
                insertParents(statement);
                statement.executeUpdate("INSERT INTO account_assets VALUES (" + WORLD + ", 'server', 'EMD', 7)");
                statement.executeUpdate(legacyOrderInsert(ORDER_1, 5, 2, 0));
            }

            apply(connection, "V3__coordination.sql");
            apply(connection, "V4__order_fifo_and_constraints.sql");
            apply(connection, "V5__durable_settlement_and_sequence.sql");

            try (Statement statement = connection.createStatement()) {
                assertEquals(7, scalar(statement, "SELECT quantity FROM account_assets"));
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM orders"));
                assertEquals(1, scalar(statement, "SELECT created_at FROM orders"));
                assertEquals(1, scalar(statement, "SELECT sequence_id FROM orders"));
                assertEquals(10, scalar(statement, "SELECT escrow_amount FROM orders"));
                assertEquals(0, scalar(statement, "SELECT maker_fee_remaining FROM orders"));
                assertCreatedAtIsRequired(statement);

                assertThrows(SQLException.class,
                        () -> statement.executeUpdate("UPDATE account_assets SET quantity = -1"));
                assertInvalidOrdersAreRejected(statement);
                assertThrows(SQLException.class,
                        () -> statement.executeUpdate("DELETE FROM assets WHERE asset_id = 'EMD'"));
            }
        }
    }

    @Test
    void rejectsNegativeLegacyBalanceAndKeepsOriginalRows() throws Exception {
        try (Connection connection = open("negative-legacy-migration.db")) {
            applyLegacyV2(connection);
            try (Statement statement = connection.createStatement()) {
                insertParents(statement);
                statement.execute("PRAGMA ignore_check_constraints = ON");
                statement.executeUpdate("INSERT INTO account_assets VALUES (" + WORLD + ", 'server', 'EMD', -5)");
                statement.execute("PRAGMA ignore_check_constraints = OFF");
            }
            apply(connection, "V3__coordination.sql");

            assertThrows(SQLException.class,
                    () -> apply(connection, "V4__order_fifo_and_constraints.sql"));

            try (Statement statement = connection.createStatement()) {
                assertEquals(-5, scalar(statement, "SELECT quantity FROM account_assets"));
                assertFalse(hasColumn(statement, "orders", "created_at"));
                assertEquals(0, scalar(statement,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'account_assets_v4'"));
            }
        }
    }

    @Test
    void rejectsInvalidLegacyOrderAndKeepsOriginalRows() throws Exception {
        try (Connection connection = open("invalid-order-legacy-migration.db")) {
            applyLegacyV2(connection);
            try (Statement statement = connection.createStatement()) {
                insertParents(statement);
                statement.executeUpdate("INSERT INTO account_assets VALUES (" + WORLD + ", 'server', 'EMD', 7)");
                statement.execute("PRAGMA ignore_check_constraints = ON");
                statement.executeUpdate(legacyOrderInsert(ORDER_1, 0, 2, 0));
                statement.execute("PRAGMA ignore_check_constraints = OFF");
            }
            apply(connection, "V3__coordination.sql");

            assertThrows(SQLException.class,
                    () -> apply(connection, "V4__order_fifo_and_constraints.sql"));

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM orders"));
                assertEquals(0, scalar(statement, "SELECT price FROM orders"));
                assertEquals(7, scalar(statement, "SELECT quantity FROM account_assets"));
                assertFalse(hasColumn(statement, "orders", "created_at"));
                assertEquals(0, scalar(statement,
                        "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'orders_v4'"));
            }
        }
    }

    @Test
    void rejectsFractionalLegacyPhysicalOrderBeforeV5() throws Exception {
        try (Connection connection = open("fractional-physical-legacy.db")) {
            applyLegacyV2(connection);
            try (Statement statement = connection.createStatement()) {
                insertParents(statement);
                statement.executeUpdate("INSERT INTO assets VALUES (" + WORLD
                        + ", 'IRON_INGOT', 2, zeroblob(1))");
                statement.executeUpdate("INSERT INTO orders "
                        + "(world, order_uuid, owner, asset_id, sideBuy, price, units, order_type) VALUES ("
                        + WORLD + ", " + ORDER_1 + ", 'server', 'IRON_INGOT', 0, 5, 1.5, 0)");
            }
            apply(connection, "V3__coordination.sql");
            apply(connection, "V4__order_fifo_and_constraints.sql");

            assertThrows(SQLException.class,
                    () -> apply(connection, "V5__durable_settlement_and_sequence.sql"));

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM orders"));
                assertEquals(1.5, scalarDouble(statement, "SELECT units FROM orders"));
                assertFalse(hasColumn(statement, "orders", "sequence_id"));
                assertEquals(0, scalar(statement,
                        "SELECT COUNT(*) FROM sqlite_temp_master WHERE name = 'v5_physical_order_guard'"));
            }
        }
    }

    @Test
    void rejectsFractionalLegacyPhysicalOutboxBeforeV5() throws Exception {
        try (Connection connection = open("fractional-physical-outbox-legacy.db")) {
            applyLegacyV2(connection);
            try (Statement statement = connection.createStatement()) {
                insertParents(statement);
                statement.executeUpdate("INSERT INTO assets VALUES (" + WORLD
                        + ", 'IRON_INGOT', 2, zeroblob(1))");
            }
            apply(connection, "V3__coordination.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO delivery_outbox "
                        + "(delivery_id, world, account_name, asset_id, quantity, status, attempts, "
                        + "next_attempt_at, created_at) VALUES (" + ORDER_2 + ", " + WORLD
                        + ", 'server', 'IRON_INGOT', 1.5, 'PENDING', 0, 0, 1)");
            }
            apply(connection, "V4__order_fifo_and_constraints.sql");

            assertThrows(SQLException.class,
                    () -> apply(connection, "V5__durable_settlement_and_sequence.sql"));

            try (Statement statement = connection.createStatement()) {
                assertEquals(1, scalar(statement, "SELECT COUNT(*) FROM delivery_outbox"));
                assertEquals(1.5, scalarDouble(statement, "SELECT quantity FROM delivery_outbox"));
                assertFalse(hasColumn(statement, "delivery_outbox", "leg_key"));
                assertFalse(hasColumn(statement, "orders", "sequence_id"));
                assertEquals(0, scalar(statement,
                        "SELECT COUNT(*) FROM sqlite_temp_master WHERE name = 'v5_physical_order_guard'"));
            }
        }
    }

    @Test
    void v7KeepsExactV6MarketTimeAfterAssetRename() throws Exception {
        try (Connection connection = open("v7-renamed-market-time.db")) {
            applyLegacyV2(connection);
            apply(connection, "V3__coordination.sql");
            apply(connection, "V4__order_fifo_and_constraints.sql");
            apply(connection, "V5__durable_settlement_and_sequence.sql");
            apply(connection, "V6__audited_market_adjustments.sql");

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO assets VALUES (" + WORLD
                        + ", 'LEGACY', 1, zeroblob(1))");
                statement.executeUpdate("INSERT INTO market_locks(world, asset_id, revision) VALUES ("
                        + WORLD + ", 'LEGACY', 1)");
                statement.executeUpdate("INSERT INTO candles_day "
                        + "(world, time, open, close, high, low, volume, asset_id) VALUES ("
                        + WORLD + ", 792000, 10, 12, 12, 10, 2, 'LEGACY')");
                statement.executeUpdate("INSERT INTO market_adjustments "
                        + "(request_id, event_type, world, asset_id, actor, price, volume, market_time, created_at) "
                        + "VALUES (" + ORDER_1 + ", 'SYNTHETIC_CANDLE', " + WORLD
                        + ", 'LEGACY', 'admin', 12, 2, 800000, 1800000000000)");
                statement.executeUpdate("UPDATE assets SET asset_id = 'RENAMED' "
                        + "WHERE world = " + WORLD + " AND asset_id = 'LEGACY'");
                statement.executeUpdate("DELETE FROM candles_day WHERE world = " + WORLD
                        + " AND asset_id = 'RENAMED'");
            }

            apply(connection, "V7__market_time_and_wallet_adjustments.sql");

            try (Statement statement = connection.createStatement()) {
                assertEquals(800_000, scalar(statement,
                        "SELECT last_market_time FROM market_locks WHERE asset_id = 'RENAMED'"));
                assertEquals(0, scalar(statement,
                        "SELECT COUNT(*) FROM market_locks WHERE asset_id = 'LEGACY'"));
                assertEquals(800_000, scalar(statement,
                        "SELECT requested_market_time FROM market_adjustments WHERE asset_id = 'LEGACY'"));
            }
        }
    }

    private Connection open(String file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve(file));
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        return connection;
    }

    private static void applyAll(Connection connection) throws Exception {
        applyLegacyV2(connection);
        apply(connection, "V3__coordination.sql");
        apply(connection, "V4__order_fifo_and_constraints.sql");
        apply(connection, "V5__durable_settlement_and_sequence.sql");
        apply(connection, "V6__audited_market_adjustments.sql");
        apply(connection, "V7__market_time_and_wallet_adjustments.sql");
    }

    private static void applyLegacyV2(Connection connection) throws Exception {
        apply(connection, "V1__tables.sql");
        apply(connection, "V2__indexes.sql");
    }

    private static void insertParents(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO assets VALUES (" + WORLD + ", 'EMD', 1, zeroblob(1))");
        statement.executeUpdate("INSERT INTO accounts VALUES (" + WORLD + ", 'server', NULL, NULL, NULL, NULL, 100)");
    }

    private static void insertOrder(Statement statement, String orderId, long createdAt) throws SQLException {
        statement.executeUpdate("INSERT INTO orders "
                + "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                + "maker_fee_remaining, order_type, created_at) VALUES ("
                + WORLD + ", " + orderId + ", 'server', 'EMD', 1, 5, 1, 5, 0, 0, " + createdAt + ")");
    }

    private static String legacyOrderInsert(String orderId, double price, double units, int orderType) {
        return "INSERT INTO orders (world, order_uuid, owner, asset_id, sideBuy, price, units, order_type) VALUES ("
                + WORLD + ", " + orderId + ", 'server', 'EMD', 1, " + price + ", " + units + ", " + orderType + ")";
    }

    private static void assertInvalidOrdersAreRejected(Statement statement) {
        String columns = "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                + "maker_fee_remaining, order_type, created_at)";
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 1, 0, 1, 1, 0, 0, 30)"));
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 1, 1, 0, 1, 0, 0, 30)"));
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 1, 1, 1, 1, 0, 4, 30)"));
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 2, 1, 1, 1, 0, 0, 30)"));
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 1, 1, 1, 1, 0, 0, NULL)"));
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 1, 1, 1, 0, 0, 0, 30)"));
        assertThrows(SQLException.class,
                () -> statement.executeUpdate("INSERT INTO orders " + columns + " VALUES (" + WORLD + ", " + ORDER_4
                        + ", 'server', 'EMD', 1, 1, 1, 1, -1, 0, 30)"));
    }

    private static void assertCreatedAtIsRequired(Statement statement) throws SQLException {
        assertTrue(hasColumn(statement, "orders", "created_at"), "orders.created_at must exist after V4");
        try (var result = statement.executeQuery("PRAGMA table_info(orders)")) {
            while (result.next()) {
                if ("created_at".equals(result.getString("name"))) {
                    assertEquals("INTEGER", result.getString("type"));
                    assertEquals(1, result.getInt("notnull"));
                    return;
                }
            }
        }
    }

    private static boolean hasColumn(Statement statement, String table, String column) throws SQLException {
        try (var result = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equals(result.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> deterministicPriceTimeOrder(Statement statement) throws SQLException {
        List<String> orderIds = new ArrayList<>();
        try (var result = statement.executeQuery(
                "SELECT hex(order_uuid) FROM orders ORDER BY price ASC, sequence_id ASC")) {
            while (result.next()) {
                orderIds.add(result.getString(1));
            }
        }
        return orderIds;
    }

    private static int scalar(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static double scalarDouble(Statement statement, String sql) throws SQLException {
        try (var result = statement.executeQuery(sql)) {
            result.next();
            return result.getDouble(1);
        }
    }

    private static void apply(Connection connection, String file) throws Exception {
        String path = "db/migration/sqlite/" + file;
        try (InputStream resource = SQLiteMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (resource == null) {
                throw new IllegalStateException("Missing test migration " + path);
            }
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command.trim());
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }
}
