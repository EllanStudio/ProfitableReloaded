package com.faridfaharaj.profitable.data.settlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MarketAdjustmentRepositoryTest {

    private static final byte[] WORLD = uuidBytes(new UUID(11, 22));
    private static final long NOW = 1_800_000_000_000L;
    private static final List<String> MIGRATIONS = List.of(
            "V1__tables.sql",
            "V2__indexes.sql",
            "V3__coordination.sql",
            "V4__order_fifo_and_constraints.sql",
            "V5__durable_settlement_and_sequence.sql",
            "V6__audited_market_adjustments.sql",
            "V7__market_time_and_wallet_adjustments.sql");

    @TempDir
    Path temporaryDirectory;

    @Test
    void appliesAllIntervalsAndUsesRequestIdAsDurableIdempotencyKey() throws Exception {
        Path database = prepare("idempotency.db");
        UUID requestId = UUID.randomUUID();

        try (Connection connection = open(database)) {
            MarketAdjustmentRepository.Result applied = MarketAdjustmentRepository.apply(connection, false,
                    request(requestId, 12.5, 7.5, 800_000));
            assertEquals(MarketAdjustmentRepository.Status.APPLIED, applied.status());

            // A command retry observes a later world clock, but the committed request keeps its original bucket.
            MarketAdjustmentRepository.Result replay = MarketAdjustmentRepository.apply(connection, false,
                    request(requestId, 12.5, 7.5, 900_000));
            assertEquals(MarketAdjustmentRepository.Status.ALREADY_APPLIED, replay.status());
            assertEquals(800_000, replay.marketTime());

            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM market_adjustments"));
            assertEquals("SYNTHETIC_CANDLE", scalarString(connection,
                    "SELECT event_type FROM market_adjustments"));
            assertCandle(connection, "day", 792_000, 12.5, 7.5);
            assertCandle(connection, "week", 672_000, 12.5, 7.5);
            assertCandle(connection, "month", 720_000, 12.5, 7.5);
        }
    }

    @Test
    void rejectsRequestUuidReuseWithDifferentExplicitPayload() throws Exception {
        Path database = prepare("payload-collision.db");
        UUID requestId = UUID.randomUUID();

        try (Connection connection = open(database)) {
            assertEquals(MarketAdjustmentRepository.Status.APPLIED,
                    MarketAdjustmentRepository.apply(connection, false,
                            request(requestId, 10, 2, 240_000)).status());

            assertThrows(SQLException.class, () -> MarketAdjustmentRepository.apply(connection, false,
                    request(requestId, 10, 3, 240_000)));

            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM market_adjustments"));
            assertEquals(2.0, scalarDouble(connection, "SELECT volume FROM candles_day"));
        }
    }

    @Test
    void rollsBackAuditAndEveryCandleIntervalWhenAnyIntervalFails() throws Exception {
        Path database = prepare("atomic-rollback.db");
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE candles_month");

            assertThrows(SQLException.class, () -> MarketAdjustmentRepository.apply(connection, false,
                    request(UUID.randomUUID(), 17, 4, 240_000)));

            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM market_adjustments"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM candles_day"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM candles_week"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM market_locks"));
        }
    }

    @Test
    void concurrentRetriesCommitOneAuditAndOneVolumeIncrement() throws Exception {
        Path database = prepare("concurrent-idempotency.db");
        UUID requestId = UUID.randomUUID();
        int workers = 6;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<MarketAdjustmentRepository.Status>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = open(database)) {
                        ready.countDown();
                        start.await();
                        return MarketAdjustmentRepository.apply(connection, false,
                                request(requestId, 42, 4.5, 240_000)).status();
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
            assertEquals(workers - 1L, statuses.stream()
                    .filter(status -> status == MarketAdjustmentRepository.Status.ALREADY_APPLIED).count());
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = open(database)) {
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM market_adjustments"));
            assertEquals(4.5, scalarDouble(connection, "SELECT volume FROM candles_day"));
            assertEquals(4.5, scalarDouble(connection, "SELECT volume FROM candles_week"));
            assertEquals(4.5, scalarDouble(connection, "SELECT volume FROM candles_month"));
        }
    }

    @Test
    void clampsBackwardSyntheticTicksToCommittedWatermark() throws Exception {
        Path database = prepare("market-time-watermark.db");
        UUID firstId = UUID.randomUUID();
        UUID backwardId = UUID.randomUUID();
        try (Connection connection = open(database)) {
            assertEquals(800_000, MarketAdjustmentRepository.apply(connection, false,
                    request(firstId, 10, 1, 800_000)).marketTime());

            MarketAdjustmentRepository.Result backward = MarketAdjustmentRepository.apply(connection, false,
                    request(backwardId, 20, 2, 100_000));
            assertEquals(MarketAdjustmentRepository.Status.APPLIED, backward.status());
            assertEquals(800_000, backward.marketTime());

            assertEquals(800_000, scalarLong(connection,
                    "SELECT last_market_time FROM market_locks WHERE asset_id = 'GLD'"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM candles_day"));
            assertEquals(792_000, scalarLong(connection, "SELECT time FROM candles_day"));
            assertEquals(3, scalarDouble(connection, "SELECT volume FROM candles_day"));
            assertEquals(10, scalarDouble(connection, "SELECT open FROM candles_day"));
            assertEquals(20, scalarDouble(connection, "SELECT close FROM candles_day"));
            assertEquals(100_000, auditTime(connection, backwardId, "requested_market_time"));
            assertEquals(800_000, auditTime(connection, backwardId, "market_time"));
        }
    }

    @Test
    void v7BackfillsWorldWatermarkWithoutRecreatingDeletedMarkets() throws Exception {
        Path database = temporaryDirectory.resolve("v7-backfill.db");
        UUID auditId = UUID.randomUUID();
        try (Connection connection = open(database)) {
            for (int index = 0; index < MIGRATIONS.size() - 1; index++) {
                apply(connection, MIGRATIONS.get(index));
            }
            try (PreparedStatement asset = connection.prepareStatement(
                    "INSERT INTO assets(world, asset_id, asset_type, meta) VALUES (?, 'GLD', 1, ?)")) {
                asset.setBytes(1, WORLD);
                asset.setBytes(2, new byte[]{0});
                asset.executeUpdate();
            }
            try (PreparedStatement candle = connection.prepareStatement(
                    "INSERT INTO candles_day(world, time, open, close, high, low, volume, asset_id) "
                            + "VALUES (?, 720000, 10, 10, 10, 10, 1, 'GLD')")) {
                candle.setBytes(1, WORLD);
                candle.executeUpdate();
            }
            try (PreparedStatement audit = connection.prepareStatement(
                    "INSERT INTO market_adjustments(request_id, event_type, world, asset_id, actor, "
                            + "price, volume, market_time, created_at) "
                            + "VALUES (?, 'SYNTHETIC_CANDLE', ?, 'GLD', 'legacy-admin', 10, 1, 800000, ?)")) {
                audit.setBytes(1, uuidBytes(auditId));
                audit.setBytes(2, WORLD);
                audit.setLong(3, NOW);
                audit.executeUpdate();
            }
            try (PreparedStatement deletedAssetAudit = connection.prepareStatement(
                    "INSERT INTO market_adjustments(request_id, event_type, world, asset_id, actor, "
                            + "price, volume, market_time, created_at) "
                            + "VALUES (?, 'SYNTHETIC_CANDLE', ?, 'DELETED', 'legacy-admin', "
                            + "99, 1, 900000, ?)")) {
                deletedAssetAudit.setBytes(1, uuidBytes(UUID.randomUUID()));
                deletedAssetAudit.setBytes(2, WORLD);
                deletedAssetAudit.setLong(3, NOW);
                deletedAssetAudit.executeUpdate();
            }

            apply(connection, MIGRATIONS.getLast());

            // World ticks are shared by all markets in one world, so the deleted market's
            // immutable V6 audit provides a conservative floor for every current market.
            assertEquals(900_000, scalarLong(connection,
                    "SELECT last_market_time FROM market_locks WHERE asset_id = 'GLD'"));
            assertEquals(800_000, auditTime(connection, auditId, "requested_market_time"));
            assertEquals(0, scalarLong(connection,
                    "SELECT COUNT(*) FROM market_locks WHERE asset_id = 'DELETED'"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='wallet_adjustments'"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='idx_delivery_account_asset_status'"));
        }
    }

    private Path prepare(String name) throws Exception {
        Path database = temporaryDirectory.resolve(name);
        try (Connection connection = open(database)) {
            for (String migration : MIGRATIONS) {
                apply(connection, migration);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO assets(world, asset_id, asset_type, meta) VALUES (?, 'GLD', 1, ?)")) {
                statement.setBytes(1, WORLD);
                statement.setBytes(2, new byte[]{0});
                statement.executeUpdate();
            }
        }
        return database;
    }

    private static MarketAdjustmentRepository.Request request(UUID requestId, double price,
                                                               double volume, long marketTime) {
        return new MarketAdjustmentRepository.Request(requestId, WORLD, "GLD", "integration-admin",
                price, volume, marketTime, NOW);
    }

    private static Connection open(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 10000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private static void assertCandle(Connection connection, String interval, long time,
                                     double price, double volume) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT time, open, close, high, low, volume FROM candles_" + interval
                        + " WHERE world = ? AND asset_id = 'GLD'")) {
            statement.setBytes(1, WORLD);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                assertEquals(time, result.getLong("time"));
                assertEquals(price, result.getDouble("open"));
                assertEquals(price, result.getDouble("close"));
                assertEquals(price, result.getDouble("high"));
                assertEquals(price, result.getDouble("low"));
                assertEquals(volume, result.getDouble("volume"));
            }
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static double scalarDouble(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getDouble(1);
        }
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static long auditTime(Connection connection, UUID requestId, String column) throws SQLException {
        if (!"requested_market_time".equals(column) && !"market_time".equals(column)) {
            throw new IllegalArgumentException("Unexpected audit time column");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + column + " FROM market_adjustments WHERE request_id = ?")) {
            statement.setBytes(1, uuidBytes(requestId));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void apply(Connection connection, String file) throws Exception {
        try (InputStream resource = MarketAdjustmentRepositoryTest.class.getClassLoader()
                .getResourceAsStream("db/migration/sqlite/" + file)) {
            if (resource == null) {
                throw new IllegalStateException("Missing SQLite migration " + file);
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
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
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
