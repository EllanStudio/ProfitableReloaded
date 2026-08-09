package com.faridfaharaj.profitable.data.settlement;

import com.faridfaharaj.profitable.data.holderClasses.Order;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Opt-in live MariaDB integration test. Run with the three profitablereloaded.mysql.* properties. */
class MariaDbSettlementIT {

    private static final byte[] WORLD = new byte[16];
    private static final long NOW = 1_800_000_000_000L;
    private static final int ROUNDS = 25;

    @Test
    void migratesAndSerializesConcurrentCrossBackendTakers() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("profitablereloaded.mysql.live"),
                "Set profitablereloaded.mysql.live=true to run the isolated MariaDB IT");
        String url = System.getProperty("profitablereloaded.mysql.url",
                "jdbc:mysql://127.0.0.1:33307/profitablereloaded_test");
        String user = System.getProperty("profitablereloaded.mysql.user", "root");
        String password = System.getProperty("profitablereloaded.mysql.password", "");
        Assumptions.assumeTrue(!password.isBlank(),
                "Set profitablereloaded.mysql.password for the isolated MariaDB IT");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String migration : List.of("V1__tables.sql", "V2__indexes.sql", "V3__coordination.sql",
                    "V4__order_fifo_and_constraints.sql")) {
                apply(connection, migration);
            }
            seed(connection);
            seedLegacyV4State(connection);
            apply(connection, "V5__durable_settlement_and_sequence.sql");
            assertLegacyV5Upgrade(connection);
            apply(connection, "V6__audited_market_adjustments.sql");
            apply(connection, "V7__market_time_and_wallet_adjustments.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM orders");
                statement.executeUpdate("DELETE FROM delivery_outbox");
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                try (Connection connection = DriverManager.getConnection(url, user, password)) {
                    assertEquals(TradeSettlementRepository.Status.PLACED,
                            TradeSettlementRepository.place(connection, true,
                                    request(UUID.randomUUID(), "maker", false)).status());
                }

                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<TradeSettlementRepository.Status>> futures = new ArrayList<>();
                for (String taker : List.of("taker-a", "taker-b")) {
                    futures.add(executor.submit(() -> {
                        try (Connection connection = DriverManager.getConnection(url, user, password)) {
                            ready.countDown();
                            start.await();
                            return TradeSettlementRepository.settle(connection, true,
                                    request(UUID.randomUUID(), taker, true)).status();
                        }
                    }));
                }
                ready.await();
                start.countDown();
                List<TradeSettlementRepository.Status> statuses = new ArrayList<>();
                for (Future<TradeSettlementRepository.Status> future : futures) {
                    statuses.add(future.get());
                }
                assertEquals(1, statuses.stream()
                        .filter(status -> status == TradeSettlementRepository.Status.EXECUTED).count());
                assertEquals(1, statuses.stream()
                        .filter(status -> status == TradeSettlementRepository.Status.NO_LIQUIDITY).count());
            }
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals(ROUNDS, scalar(connection, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(ROUNDS, scalar(connection, "SELECT SUM(volume) FROM candles_day"));
            assertEquals(ROUNDS * 2, scalar(connection, "SELECT COUNT(*) FROM delivery_outbox"));
        }

        CountDownLatch deliveryReady = new CountDownLatch(2);
        CountDownLatch deliveryStart = new CountDownLatch(1);
        ExecutorService deliveryWorkers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> deliveries = new ArrayList<>();
            String longServerId = "s".repeat(64);
            for (String worker : List.of(longServerId + ":" + UUID.randomUUID(),
                    longServerId + ":" + UUID.randomUUID())) {
                deliveries.add(deliveryWorkers.submit(() -> {
                    try (Connection connection = DriverManager.getConnection(url, user, password)) {
                        deliveryReady.countDown();
                        deliveryStart.await();
                        int processed = 0;
                        while (true) {
                            DeliveryOutboxRepository.ProcessResult result = DeliveryOutboxRepository.processOne(
                                    connection, true, worker, NOW + 10_000);
                            if (result.status() == DeliveryOutboxRepository.Status.EMPTY) {
                                return processed;
                            }
                            if (result.status() != DeliveryOutboxRepository.Status.PROCESSED) {
                                throw new AssertionError("Outbox worker failed: " + result.error());
                            }
                            processed++;
                        }
                    }
                }));
            }
            deliveryReady.await();
            deliveryStart.countDown();
            int processed = 0;
            for (Future<Integer> delivery : deliveries) {
                processed += delivery.get();
            }
            assertEquals(ROUNDS * 2, processed);
        } finally {
            deliveryWorkers.shutdownNow();
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals(ROUNDS, scalar(connection,
                    "SELECT COALESCE(SUM(quantity), 0) FROM account_assets "
                            + "WHERE account_name IN ('taker-a','taker-b') AND asset_id='IRON_INGOT'"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM account_assets WHERE quantity < 0"));
            assertEquals(ROUNDS * 2, scalar(connection, "SELECT COUNT(*) FROM processed_events"));
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM delivery_outbox WHERE status <> 'COMPLETED'"));
            assertEquals(ROUNDS * 2, scalar(connection, "SELECT COUNT(*) FROM exchange_requests"));

            TradeSettlementRepository.place(connection, true,
                    request(UUID.randomUUID(), "maker", false));
            TradeSettlementRepository.place(connection, true,
                    request(UUID.randomUUID(), "maker", false));
            assertEquals(2, scalar(connection, "SELECT COUNT(DISTINCT sequence_id) FROM orders"));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM orders"));
        }
    }

    private static TradeSettlementRepository.Request request(UUID id, String account, boolean sideBuy) {
        return new TradeSettlementRepository.Request(WORLD, id, account, "IRON_INGOT", "EMD", sideBuy,
                sideBuy ? Double.MAX_VALUE : 10, 1,
                sideBuy ? Order.OrderType.MARKET : Order.OrderType.LIMIT,
                2, "0", "0", 240_000, NOW);
    }

    private static void seed(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO assets(world, asset_id, asset_type, meta) VALUES (?, ?, ?, ?)")) {
            for (Object[] asset : List.of(new Object[]{"EMD", 1}, new Object[]{"IRON_INGOT", 2})) {
                statement.setBytes(1, WORLD);
                statement.setString(2, (String) asset[0]);
                statement.setInt(3, (Integer) asset[1]);
                statement.setBytes(4, new byte[]{0});
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts(world, account_name, password, salt, item_delivery_pos, "
                        + "entity_delivery_pos, entity_claim_id) VALUES (?, ?, NULL, NULL, NULL, NULL, ?)")) {
            int id = 1;
            for (String account : List.of("maker", "taker-a", "taker-b")) {
                statement.setBytes(1, WORLD);
                statement.setString(2, account);
                statement.setInt(3, id++);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        holding(connection, "maker", "IRON_INGOT", 100);
        holding(connection, "taker-a", "EMD", 10_000);
        holding(connection, "taker-b", "EMD", 10_000);
    }

    private static void seedLegacyV4State(Connection connection) throws Exception {
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO orders(world, order_uuid, owner, asset_id, sideBuy, price, units, "
                        + "order_type, created_at) VALUES (?, ?, 'maker', 'IRON_INGOT', 0, 99, 1, 0, 7)")) {
            for (UUID id : List.of(second, first)) {
                statement.setBytes(1, WORLD);
                statement.setBytes(2, uuidBytes(id));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO delivery_outbox(delivery_id, world, account_name, asset_id, quantity, "
                        + "status, attempts, next_attempt_at, created_at) "
                        + "VALUES (?, ?, 'maker', 'EMD', 3, 'PENDING', 2, 123, 5)")) {
            statement.setBytes(1, uuidBytes(new UUID(0, 3)));
            statement.setBytes(2, WORLD);
            statement.executeUpdate();
        }
    }

    private static void assertLegacyV5Upgrade(Connection connection) throws Exception {
        assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM orders"));
        assertEquals(2, scalar(connection, "SELECT COUNT(DISTINCT sequence_id) FROM orders"));
        assertEquals(1, scalar(connection,
                "SELECT sequence_id FROM orders WHERE order_uuid=UNHEX('00000000000000000000000000000001')"));
        assertEquals(2, scalar(connection,
                "SELECT sequence_id FROM orders WHERE order_uuid=UNHEX('00000000000000000000000000000002')"));
        assertEquals(2, scalar(connection, "SELECT SUM(escrow_amount) FROM orders"));
        assertEquals(0, scalar(connection, "SELECT SUM(maker_fee_remaining) FROM orders"));
        assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM delivery_outbox"));
        assertEquals(1, scalar(connection,
                "SELECT COUNT(*) FROM delivery_outbox WHERE leg_key LIKE 'legacy:%' "
                        + "AND status='PENDING' AND attempts=2 AND lease_owner IS NULL"));
    }

    private static void holding(Connection connection, String account, String asset, double amount)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_assets(world, account_name, asset_id, quantity) VALUES (?, ?, ?, ?)")) {
            statement.setBytes(1, WORLD);
            statement.setString(2, account);
            statement.setString(3, asset);
            statement.setDouble(4, amount);
            statement.executeUpdate();
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void apply(Connection connection, String file) throws Exception {
        try (InputStream resource = MariaDbSettlementIT.class.getClassLoader()
                .getResourceAsStream("db/migration/mysql/" + file)) {
            if (resource == null) {
                throw new IllegalStateException("Missing MySQL migration " + file);
            }
            String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("(?s)/\\*.*?\\*/", "");
            try (Statement statement = connection.createStatement()) {
                for (String command : sql.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command.trim());
                    }
                }
            }
        }
    }
}
