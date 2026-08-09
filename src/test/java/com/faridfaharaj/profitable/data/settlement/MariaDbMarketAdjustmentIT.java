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

/** Opt-in MariaDB verification for V6/V7 audit, watermark and idempotency behavior. */
class MariaDbMarketAdjustmentIT {

    private static final byte[] WORLD = uuidBytes(new UUID(71, 92));

    @Test
    void migratesAndSerializesConcurrentSyntheticRequestRetries() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("profitablereloaded.market.mysql.live"),
                "Set profitablereloaded.market.mysql.live=true to run the isolated MariaDB IT");
        String url = System.getProperty("profitablereloaded.market.mysql.url",
                "jdbc:mysql://127.0.0.1:33307/profitablereloaded_market_adjustment_test");
        String user = System.getProperty("profitablereloaded.market.mysql.user", "root");
        String password = System.getProperty("profitablereloaded.market.mysql.password", "");
        Assumptions.assumeTrue(!password.isBlank(),
                "Set profitablereloaded.market.mysql.password for the isolated MariaDB IT");

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            for (String migration : List.of(
                    "V1__tables.sql",
                    "V2__indexes.sql",
                    "V3__coordination.sql",
                    "V4__order_fifo_and_constraints.sql",
                    "V5__durable_settlement_and_sequence.sql",
                    "V6__audited_market_adjustments.sql")) {
                apply(connection, migration);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO assets(world, asset_id, asset_type, meta) VALUES (?, ?, 1, ?)")) {
                for (String asset : List.of("GLD", "LEGACY")) {
                    statement.setBytes(1, WORLD);
                    statement.setString(2, asset);
                    statement.setBytes(3, new byte[]{0});
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            seedV6UpgradeState(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE assets SET asset_id='RENAMED' WHERE world=? AND asset_id='LEGACY'")) {
                statement.setBytes(1, WORLD);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM candles_day WHERE world=? AND asset_id='RENAMED'")) {
                statement.setBytes(1, WORLD);
                statement.executeUpdate();
            }
            apply(connection, "V7__market_time_and_wallet_adjustments.sql");
            assertEquals(900_000, scalar(connection,
                    "SELECT last_market_time FROM market_locks WHERE asset_id='RENAMED'"));
            assertEquals(800_000, scalar(connection,
                    "SELECT requested_market_time FROM market_adjustments WHERE asset_id='LEGACY'"));
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM market_locks WHERE asset_id='LEGACY'"));
            assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM market_locks WHERE asset_id='DELETED'"));
        }

        UUID requestId = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<MarketAdjustmentRepository.Status>> futures = new ArrayList<>();
        try {
            for (int worker = 0; worker < 2; worker++) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = DriverManager.getConnection(url, user, password)) {
                        ready.countDown();
                        start.await();
                        return MarketAdjustmentRepository.apply(connection, true,
                                new MarketAdjustmentRepository.Request(requestId, WORLD, "GLD",
                                        "integration-admin", 30, 5, 240_000, 1_800_000_000_000L)).status();
                    }
                }));
            }
            ready.await();
            start.countDown();
            List<MarketAdjustmentRepository.Status> statuses = new ArrayList<>();
            for (Future<MarketAdjustmentRepository.Status> future : futures) {
                statuses.add(future.get());
            }
            assertEquals(1, statuses.stream()
                    .filter(status -> status == MarketAdjustmentRepository.Status.APPLIED).count());
            assertEquals(1, statuses.stream()
                    .filter(status -> status == MarketAdjustmentRepository.Status.ALREADY_APPLIED).count());
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            MarketAdjustmentRepository.Result backward = MarketAdjustmentRepository.apply(connection, true,
                    new MarketAdjustmentRepository.Request(UUID.randomUUID(), WORLD, "GLD",
                            "integration-admin", 35, 2, 100_000, 1_800_000_000_001L));
            assertEquals(240_000, backward.marketTime());
            assertEquals(2, scalar(connection,
                    "SELECT COUNT(*) FROM market_adjustments WHERE asset_id='GLD'"));
            assertEquals(7, scalar(connection,
                    "SELECT SUM(volume) FROM candles_day WHERE asset_id='GLD'"));
            assertEquals(7, scalar(connection,
                    "SELECT SUM(volume) FROM candles_week WHERE asset_id='GLD'"));
            assertEquals(7, scalar(connection,
                    "SELECT SUM(volume) FROM candles_month WHERE asset_id='GLD'"));
            assertEquals(2, scalar(connection,
                    "SELECT revision FROM market_locks WHERE asset_id='GLD'"));
            assertEquals(240_000, scalar(connection,
                    "SELECT last_market_time FROM market_locks WHERE asset_id='GLD'"));
            assertEquals(100_000, scalar(connection,
                    "SELECT MIN(requested_market_time) FROM market_adjustments WHERE asset_id='GLD'"));
            assertEquals(240_000, scalar(connection,
                    "SELECT MIN(market_time) FROM market_adjustments WHERE asset_id='GLD'"));
        }
    }

    private static void seedV6UpgradeState(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO market_locks(world, asset_id, revision) VALUES (?, 'LEGACY', 3)")) {
            statement.setBytes(1, WORLD);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO candles_day(world, time, open, close, high, low, volume, asset_id) "
                        + "VALUES (?, 792000, 10, 12, 12, 10, 2, 'LEGACY')")) {
            statement.setBytes(1, WORLD);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO market_adjustments(request_id, event_type, world, asset_id, actor, price, "
                        + "volume, market_time, created_at) "
                        + "VALUES (?, 'SYNTHETIC_CANDLE', ?, 'LEGACY', 'legacy-admin', 12, 2, 800000, ?)")) {
            statement.setBytes(1, uuidBytes(UUID.randomUUID()));
            statement.setBytes(2, WORLD);
            statement.setLong(3, 1_799_999_999_999L);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO market_adjustments(request_id, event_type, world, asset_id, actor, price, "
                        + "volume, market_time, created_at) "
                        + "VALUES (?, 'SYNTHETIC_CANDLE', ?, 'DELETED', 'legacy-admin', 99, 1, 900000, ?)")) {
            statement.setBytes(1, uuidBytes(UUID.randomUUID()));
            statement.setBytes(2, WORLD);
            statement.setLong(3, 1_799_999_999_998L);
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
        try (InputStream resource = MariaDbMarketAdjustmentIT.class.getClassLoader()
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
