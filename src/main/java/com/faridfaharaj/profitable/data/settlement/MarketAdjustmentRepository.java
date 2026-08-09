package com.faridfaharaj.profitable.data.settlement;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Applies an explicitly requested synthetic market print as one audited transaction.
 *
 * <p>The repository deliberately takes the same parent-asset then market lock order used by
 * {@link TradeSettlementRepository}. The request UUID is the idempotency key: once committed,
 * retries with the same operator-visible payload return the original audit record and never add
 * candle volume again. {@code marketTime} is server-derived metadata, so a later retry may supply
 * a different clock value without changing the already committed event bucket. New events clamp
 * their input time to the market's committed watermark before writing candles.</p>
 */
public final class MarketAdjustmentRepository {

    private static final String EVENT_TYPE = "SYNTHETIC_CANDLE";
    private static final String[] CANDLE_TABLES = {"day", "week", "month"};
    private static final long[] CANDLE_INTERVALS = {24_000L, 168_000L, 720_000L};

    private MarketAdjustmentRepository() {
    }

    public enum Status {
        APPLIED,
        ALREADY_APPLIED,
        ASSET_NOT_FOUND
    }

    public record Request(UUID requestId, byte[] worldId, String assetId, String actor,
                          double price, double volume, long marketTime, long nowMillis) {
        public Request {
            worldId = worldId == null ? null : worldId.clone();
        }

        @Override
        public byte[] worldId() {
            return worldId == null ? null : worldId.clone();
        }
    }

    public record Result(Status status, UUID requestId, long marketTime, long createdAt) {
    }

    /**
     * Owns and completes a transaction on {@code connection}; callers should supply a dedicated
     * connection rather than wrapping this call in a wider transaction.
     */
    public static Result apply(Connection connection, boolean mysql, Request request) throws SQLException {
        validate(connection, request);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            if (!lockAsset(connection, mysql, request.worldId(), request.assetId())) {
                connection.rollback();
                return new Result(Status.ASSET_NOT_FOUND, request.requestId(), request.marketTime(), 0);
            }
            long lastMarketTime = lockMarket(connection, mysql, request.worldId(), request.assetId());

            StoredAdjustment existing = find(connection, request.requestId());
            if (existing != null) {
                requireSamePayload(existing, request);
                connection.rollback();
                return existing.result(Status.ALREADY_APPLIED);
            }

            long effectiveMarketTime = Math.max(request.marketTime(), lastMarketTime);
            insertAudit(connection, request, effectiveMarketTime);
            updateCandles(connection, mysql, request, effectiveMarketTime);
            advanceMarketTime(connection, request.worldId(), request.assetId(), effectiveMarketTime);
            connection.commit();
            return new Result(Status.APPLIED, request.requestId(), effectiveMarketTime, request.nowMillis());
        } catch (SQLException failure) {
            boolean rolledBack = rollback(connection, failure);

            // A request racing on a different market can lose the global UUID uniqueness race.
            // The winning audit row proves its candle mutation committed in the same transaction.
            if (rolledBack && couldBeDuplicateKey(failure)) {
                try {
                    StoredAdjustment existing = find(connection, request.requestId());
                    if (existing != null) {
                        requireSamePayload(existing, request);
                        return existing.result(Status.ALREADY_APPLIED);
                    }
                } catch (SQLException inspectionFailure) {
                    failure.addSuppressed(inspectionFailure);
                }
            }
            throw failure;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static void validate(Connection connection, Request request) {
        if (connection == null || request == null || request.requestId() == null
                || request.worldId() == null || request.worldId().length != 16
                || blank(request.assetId()) || request.assetId().length() > 20
                || blank(request.actor()) || request.actor().length() > 128
                || !Double.isFinite(request.price()) || request.price() <= 0
                || !Double.isFinite(request.volume()) || request.volume() <= 0
                || request.marketTime() < 0 || request.nowMillis() <= 0) {
            throw new IllegalArgumentException("Invalid synthetic market adjustment request");
        }
    }

    private static boolean lockAsset(Connection connection, boolean mysql, byte[] worldId, String assetId)
            throws SQLException {
        if (mysql) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT asset_id FROM assets WHERE world = ? AND asset_id = ? FOR UPDATE")) {
                statement.setBytes(1, worldId);
                statement.setString(2, assetId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE assets SET asset_id = asset_id WHERE world = ? AND asset_id = ?")) {
            statement.setBytes(1, worldId);
            statement.setString(2, assetId);
            return statement.executeUpdate() == 1;
        }
    }

    private static long lockMarket(Connection connection, boolean mysql, byte[] worldId, String assetId)
            throws SQLException {
        if (mysql) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO market_locks(world, asset_id, revision, last_market_time) VALUES (?, ?, 1, 0) "
                            + "ON DUPLICATE KEY UPDATE revision = revision + 1")) {
                statement.setBytes(1, worldId);
                statement.setString(2, assetId);
                if (statement.executeUpdate() < 1) {
                    throw new SQLException("Could not lock market " + assetId);
                }
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO market_locks"
                            + "(world, asset_id, revision, last_market_time) VALUES (?, ?, 0, 0)")) {
                statement.setBytes(1, worldId);
                statement.setString(2, assetId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE market_locks SET revision = revision + 1 WHERE world = ? AND asset_id = ?")) {
                statement.setBytes(1, worldId);
                statement.setString(2, assetId);
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Could not lock market " + assetId);
                }
            }
        }

        String select = "SELECT last_market_time FROM market_locks WHERE world = ? AND asset_id = ?"
                + (mysql ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setBytes(1, worldId);
            statement.setString(2, assetId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Could not read market time watermark for " + assetId);
                }
                long lastMarketTime = result.getLong("last_market_time");
                if (lastMarketTime < 0) {
                    throw new SQLException("Invalid market time watermark for " + assetId);
                }
                return lastMarketTime;
            }
        }
    }

    private static StoredAdjustment find(Connection connection, UUID requestId) throws SQLException {
        String sql = "SELECT event_type, world, asset_id, actor, price, volume, market_time, created_at "
                + "FROM market_adjustments WHERE request_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(requestId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredAdjustment(requestId, result.getString("event_type"),
                        result.getBytes("world"), result.getString("asset_id"), result.getString("actor"),
                        result.getDouble("price"), result.getDouble("volume"),
                        result.getLong("market_time"), result.getLong("created_at"));
            }
        }
    }

    private static void insertAudit(Connection connection, Request request, long effectiveMarketTime)
            throws SQLException {
        String sql = "INSERT INTO market_adjustments "
                + "(request_id, event_type, world, asset_id, actor, price, volume, requested_market_time, "
                + "market_time, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(request.requestId()));
            statement.setString(2, EVENT_TYPE);
            statement.setBytes(3, request.worldId());
            statement.setString(4, request.assetId());
            statement.setString(5, request.actor());
            statement.setDouble(6, request.price());
            statement.setDouble(7, request.volume());
            statement.setLong(8, request.marketTime());
            statement.setLong(9, effectiveMarketTime);
            statement.setLong(10, request.nowMillis());
            statement.executeUpdate();
        }
    }

    private static void updateCandles(Connection connection, boolean mysql, Request request,
                                      long effectiveMarketTime)
            throws SQLException {
        for (int index = 0; index < CANDLE_TABLES.length; index++) {
            String table = CANDLE_TABLES[index];
            String sql = mysql
                    ? "INSERT INTO candles_" + table
                    + " (world, time, open, close, high, low, volume, asset_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                    + "high = GREATEST(high, VALUES(high)), low = LEAST(low, VALUES(low)), "
                    + "close = VALUES(close), volume = volume + VALUES(volume)"
                    : "INSERT INTO candles_" + table
                    + " (world, time, open, close, high, low, volume, asset_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(world, time, asset_id) DO UPDATE SET "
                    + "high = MAX(high, excluded.high), low = MIN(low, excluded.low), "
                    + "close = excluded.close, volume = volume + excluded.volume";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, request.worldId());
                statement.setLong(2, bucket(effectiveMarketTime, CANDLE_INTERVALS[index]));
                statement.setDouble(3, request.price());
                statement.setDouble(4, request.price());
                statement.setDouble(5, request.price());
                statement.setDouble(6, request.price());
                statement.setDouble(7, request.volume());
                statement.setString(8, request.assetId());
                statement.executeUpdate();
            }
        }
    }

    private static void advanceMarketTime(Connection connection, byte[] worldId, String assetId,
                                          long effectiveMarketTime) throws SQLException {
        String sql = "UPDATE market_locks SET last_market_time = CASE "
                + "WHEN last_market_time < ? THEN ? ELSE last_market_time END "
                + "WHERE world = ? AND asset_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, effectiveMarketTime);
            statement.setLong(2, effectiveMarketTime);
            statement.setBytes(3, worldId);
            statement.setString(4, assetId);
            statement.executeUpdate();
        }
    }

    private static void requireSamePayload(StoredAdjustment existing, Request request) throws SQLException {
        if (!EVENT_TYPE.equals(existing.eventType())
                || !Arrays.equals(existing.worldId(), request.worldId())
                || !existing.assetId().equals(request.assetId())
                || !existing.actor().equals(request.actor())
                || Double.compare(existing.price(), request.price()) != 0
                || Double.compare(existing.volume(), request.volume()) != 0) {
            throw new SQLException("Synthetic market adjustment request UUID was reused with a different payload: "
                    + request.requestId());
        }
    }

    private static long bucket(long marketTime, long interval) {
        return (marketTime / interval) * interval;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static boolean couldBeDuplicateKey(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 19 || current.getErrorCode() == 1062
                    || "23000".equals(current.getSQLState()) || "23505".equals(current.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
            return true;
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
            return false;
        }
    }

    private record StoredAdjustment(UUID requestId, String eventType, byte[] worldId, String assetId,
                                    String actor, double price, double volume,
                                    long marketTime, long createdAt) {
        private Result result(Status status) {
            return new Result(status, requestId, marketTime, createdAt);
        }
    }
}
