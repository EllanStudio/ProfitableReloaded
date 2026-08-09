package com.faridfaharaj.profitable.data.settlement;

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

/** Opt-in MariaDB verification for V7 wallet audit, outbox exclusion and idempotency. */
class MariaDbWalletAdjustmentIT {

    private static final byte[] WORLD = uuidBytes(new UUID(303, 404));
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void migratesAndSerializesAuditedWalletAdjustments() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("profitablereloaded.wallet.mysql.live"),
                "Set profitablereloaded.wallet.mysql.live=true to run the isolated MariaDB IT");
        String url = System.getProperty("profitablereloaded.wallet.mysql.url",
                "jdbc:mysql://127.0.0.1:33307/profitablereloaded_wallet_adjustment_test");
        String user = System.getProperty("profitablereloaded.wallet.mysql.user", "root");
        String password = System.getProperty("profitablereloaded.wallet.mysql.password", "");
        Assumptions.assumeTrue(!password.isBlank(),
                "Set profitablereloaded.wallet.mysql.password for the isolated MariaDB IT");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String migration : List.of(
                    "V1__tables.sql",
                    "V2__indexes.sql",
                    "V3__coordination.sql",
                    "V4__order_fifo_and_constraints.sql",
                    "V5__durable_settlement_and_sequence.sql",
                    "V6__audited_market_adjustments.sql",
                    "V7__market_time_and_wallet_adjustments.sql")) {
                apply(connection, migration);
            }
            seed(connection);
        }

        UUID requestId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<WalletAdjustmentRepository.Status>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = DriverManager.getConnection(url, user, password)) {
                        ready.countDown();
                        start.await();
                        return WalletAdjustmentRepository.apply(connection, true,
                                request(requestId, "EMD", 40)).status();
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<WalletAdjustmentRepository.Status> statuses = new ArrayList<>();
            for (Future<WalletAdjustmentRepository.Status> future : futures) {
                statuses.add(future.get());
            }
            assertEquals(1, statuses.stream()
                    .filter(status -> status == WalletAdjustmentRepository.Status.APPLIED).count());
            assertEquals(1, statuses.stream()
                    .filter(status -> status == WalletAdjustmentRepository.Status.ALREADY_APPLIED).count());
        } finally {
            executor.shutdownNow();
        }

        CountDownLatch distinctReady = new CountDownLatch(3);
        CountDownLatch distinctStart = new CountDownLatch(1);
        ExecutorService distinctExecutor = Executors.newFixedThreadPool(3);
        try {
            List<Future<WalletAdjustmentRepository.Status>> distinct = new ArrayList<>();
            int quantity = 20;
            for (String account : List.of("bob", "carol", "dave")) {
                int requestedQuantity = quantity++;
                distinct.add(distinctExecutor.submit(() -> {
                    try (Connection connection = DriverManager.getConnection(url, user, password)) {
                        distinctReady.countDown();
                        distinctStart.await();
                        return WalletAdjustmentRepository.apply(connection, true,
                                request(UUID.randomUUID(), account, "EMD", requestedQuantity)).status();
                    }
                }));
            }
            distinctReady.await();
            distinctStart.countDown();
            for (Future<WalletAdjustmentRepository.Status> future : distinct) {
                assertEquals(WalletAdjustmentRepository.Status.APPLIED, future.get());
            }
        } finally {
            distinctExecutor.shutdownNow();
        }

        UUID blockedRequest = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            assertEquals(40, scalar(connection, "SELECT quantity FROM account_assets WHERE asset_id='EMD'"));
            assertEquals(4, scalar(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
            assertEquals(3, scalar(connection, "SELECT COUNT(*) FROM account_assets WHERE "
                    + "account_name IN ('bob','carol','dave') AND asset_id='EMD'"));
            insertPendingDelivery(connection, 3);

            assertEquals(WalletAdjustmentRepository.Status.PENDING_DELIVERY,
                    WalletAdjustmentRepository.apply(connection, true,
                            request(blockedRequest, "EMD", 50)).status());
            assertEquals(40, scalar(connection, "SELECT quantity FROM account_assets WHERE asset_id='EMD'"));

            assertEquals(DeliveryOutboxRepository.Status.PROCESSED,
                    DeliveryOutboxRepository.processOne(connection, true,
                            "mariadb-wallet-adjustment", NOW + 1_000).status());
            WalletAdjustmentRepository.Result applied = WalletAdjustmentRepository.apply(connection, true,
                    request(blockedRequest, "EMD", 50));
            assertEquals(WalletAdjustmentRepository.Status.APPLIED, applied.status());
            assertEquals(43, applied.oldQuantity());
            assertEquals(50, scalar(connection, "SELECT quantity FROM account_assets WHERE asset_id='EMD'"));

            assertEquals(WalletAdjustmentRepository.Status.INVALID_QUANTITY,
                    WalletAdjustmentRepository.apply(connection, true,
                            request(UUID.randomUUID(), "IRON_INGOT", 1.5)).status());
            assertEquals(5, scalar(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
        }
    }

    private static WalletAdjustmentRepository.Request request(UUID id, String asset, double quantity) {
        return request(id, "alice", asset, quantity);
    }

    private static WalletAdjustmentRepository.Request request(UUID id, String account,
                                                              String asset, double quantity) {
        return new WalletAdjustmentRepository.Request(id, WORLD, account, asset,
                "integration-admin", quantity, NOW);
    }

    private static void seed(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO assets(world, asset_id, asset_type, meta) VALUES (?, ?, ?, ?)")) {
            for (Object[] row : List.of(new Object[]{"EMD", 1}, new Object[]{"IRON_INGOT", 2})) {
                statement.setBytes(1, WORLD);
                statement.setString(2, (String) row[0]);
                statement.setInt(3, (Integer) row[1]);
                statement.setBytes(4, new byte[]{0});
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO accounts(world, account_name, password, salt, item_delivery_pos, "
                        + "entity_delivery_pos, entity_claim_id) VALUES (?, ?, NULL, NULL, NULL, NULL, ?)")) {
            int claimId = 1;
            for (String account : List.of("alice", "bob", "carol", "dave")) {
                statement.setBytes(1, WORLD);
                statement.setString(2, account);
                statement.setInt(3, claimId++);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_assets(world, account_name, asset_id, quantity) "
                        + "VALUES (?, 'alice', 'EMD', 10)")) {
            statement.setBytes(1, WORLD);
            statement.executeUpdate();
        }
    }

    private static void insertPendingDelivery(Connection connection, double quantity) throws Exception {
        UUID deliveryId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO delivery_outbox(delivery_id, execution_id, leg_key, world, account_name, "
                        + "asset_id, quantity, status, attempts, next_attempt_at, lease_until, created_at) "
                        + "VALUES (?, NULL, ?, ?, 'alice', 'EMD', ?, 'PENDING', 0, 0, 0, ?)")) {
            statement.setBytes(1, uuidBytes(deliveryId));
            statement.setString(2, "mariadb-wallet:" + deliveryId);
            statement.setBytes(3, WORLD);
            statement.setDouble(4, quantity);
            statement.setLong(5, NOW);
            statement.executeUpdate();
        }
    }

    private static int scalar(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void apply(Connection connection, String file) throws Exception {
        try (InputStream resource = MariaDbWalletAdjustmentIT.class.getClassLoader()
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

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
