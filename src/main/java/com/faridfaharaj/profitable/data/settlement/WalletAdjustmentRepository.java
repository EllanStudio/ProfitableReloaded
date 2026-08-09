package com.faridfaharaj.profitable.data.settlement;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Audited, idempotent absolute wallet adjustment for privileged operators.
 *
 * <p>The lock order is compatible with settlement: asset parent first, then account, then the
 * wallet row. A non-completed durable credit for the same account/asset rejects an absolute set,
 * preventing a delayed outbox credit from racing with the operator's intended final value.</p>
 */
public final class WalletAdjustmentRepository {

    private static final String EVENT_TYPE = "ADMIN_WALLET_SET";
    private static final int MAX_TRANSACTION_ATTEMPTS = 5;
    private static final long BASE_RETRY_DELAY_MILLIS = 10L;

    private WalletAdjustmentRepository() {
    }

    public enum Status {
        APPLIED,
        ALREADY_APPLIED,
        ACCOUNT_NOT_FOUND,
        ASSET_NOT_FOUND,
        INVALID_QUANTITY,
        PENDING_DELIVERY
    }

    public record Request(UUID requestId, byte[] worldId, String account, String assetId,
                          String actor, double newQuantity, long nowMillis) {
        public Request {
            worldId = worldId == null ? null : worldId.clone();
        }

        @Override
        public byte[] worldId() {
            return worldId == null ? null : worldId.clone();
        }
    }

    public record Result(Status status, UUID requestId, double oldQuantity,
                         double newQuantity, long createdAt) {
    }

    /** Owns and completes a transaction on the supplied dedicated connection. */
    public static Result apply(Connection connection, boolean mysql, Request request) throws SQLException {
        validate(connection, request);
        boolean originalAutoCommit = connection.getAutoCommit();
        int originalIsolation = connection.getTransactionIsolation();
        Throwable primaryFailure = null;
        try {
            // Perform the immutable replay lookup before taking mutable metadata locks. On the
            // autocommit production connections this is an independent read. A caller-supplied
            // manual-commit connection is explicitly rolled back below so its read snapshot does
            // not leak into the lock-ordered write attempt.
            StoredAdjustment replay = find(connection, request.requestId());
            if (replay != null) {
                requireSamePayload(replay, request);
                return replay.result(Status.ALREADY_APPLIED);
            }
            if (!originalAutoCommit) {
                connection.rollback();
            }

            connection.setAutoCommit(false);
            for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
                try {
                    Integer assetType = lockAsset(connection, mysql, request.worldId(), request.assetId());
                    if (assetType == null) {
                        connection.rollback();
                        return rejected(Status.ASSET_NOT_FOUND, request);
                    }
                    if (assetType < 1 || assetType > 3) {
                        throw new SQLException("Invalid wallet adjustment asset type: " + assetType);
                    }
                    if (physical(assetType) && !physicalQuantity(request.newQuantity())) {
                        connection.rollback();
                        return rejected(Status.INVALID_QUANTITY, request);
                    }
                    if (!lockAccount(connection, mysql, request.worldId(), request.account())) {
                        connection.rollback();
                        return rejected(Status.ACCOUNT_NOT_FOUND, request);
                    }

                    // Keep the lock-ordered second check for same-wallet competitors. Cross-wallet UUID
                    // races are still resolved by the unique audit key and the duplicate-key recovery.
                    StoredAdjustment existing = find(connection, request.requestId());
                    if (existing != null) {
                        requireSamePayload(existing, request);
                        connection.rollback();
                        return existing.result(Status.ALREADY_APPLIED);
                    }

                    if (hasPendingDelivery(connection, request)) {
                        connection.rollback();
                        return rejected(Status.PENDING_DELIVERY, request);
                    }

                    double oldQuantity = readHolding(connection, mysql, request);
                    setHolding(connection, mysql, request);
                    insertAudit(connection, request, oldQuantity);
                    connection.commit();
                    return new Result(Status.APPLIED, request.requestId(), oldQuantity,
                            request.newQuantity(), request.nowMillis());
                } catch (SQLException failure) {
                    boolean rolledBack = rollback(connection, failure);
                    if (rolledBack && couldBeDuplicateKey(failure)) {
                        Result recovered = recoverDuplicateKey(connection, request, failure);
                        if (recovered != null) {
                            return recovered;
                        }
                    }
                    if (rolledBack && attempt < MAX_TRANSACTION_ATTEMPTS
                            && isRetryableTransactionFailure(failure, mysql)) {
                        if (!restoreForRetry(connection, originalAutoCommit,
                                originalIsolation, failure)) {
                            throw failure;
                        }
                        pauseBeforeRetry(attempt, failure);
                        try {
                            connection.setAutoCommit(false);
                        } catch (SQLException restartFailure) {
                            failure.addSuppressed(restartFailure);
                            throw failure;
                        }
                        continue;
                    }
                    throw failure;
                }
            }
            throw new SQLException("Wallet adjustment retry loop ended unexpectedly");
        } catch (SQLException | RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            restoreConnectionState(connection, originalAutoCommit, originalIsolation, primaryFailure);
        }
    }

    private static void validate(Connection connection, Request request) {
        if (connection == null || request == null || request.requestId() == null
                || request.worldId() == null || request.worldId().length != 16
                || blank(request.account()) || request.account().length() > 36
                || blank(request.assetId()) || request.assetId().length() > 20
                || blank(request.actor()) || request.actor().length() > 128
                || !Double.isFinite(request.newQuantity()) || request.newQuantity() < 0
                || request.nowMillis() <= 0) {
            throw new IllegalArgumentException("Invalid wallet adjustment request");
        }
    }

    private static Integer lockAsset(Connection connection, boolean mysql, byte[] worldId, String assetId)
            throws SQLException {
        String sql = mysql
                ? "SELECT asset_type FROM assets WHERE world = ? AND asset_id = ? LOCK IN SHARE MODE"
                : "UPDATE assets SET asset_id = asset_id WHERE world = ? AND asset_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, assetId);
            if (mysql) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt("asset_type") : null;
                }
            }
            if (statement.executeUpdate() != 1) {
                return null;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT asset_type FROM assets WHERE world = ? AND asset_id = ?")) {
            statement.setBytes(1, worldId);
            statement.setString(2, assetId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("asset_type") : null;
            }
        }
    }

    private static boolean lockAccount(Connection connection, boolean mysql, byte[] worldId, String account)
            throws SQLException {
        String sql = mysql
                ? "SELECT account_name FROM accounts WHERE world = ? AND account_name = ? FOR UPDATE"
                : "UPDATE accounts SET account_name = account_name WHERE world = ? AND account_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, account);
            if (mysql) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean hasPendingDelivery(Connection connection, Request request) throws SQLException {
        String sql = "SELECT 1 FROM delivery_outbox WHERE world = ? AND account_name = ? "
                + "AND asset_id = ? AND status <> 'COMPLETED' LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, request.worldId());
            statement.setString(2, request.account());
            statement.setString(3, request.assetId());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static double readHolding(Connection connection, boolean mysql, Request request)
            throws SQLException {
        String sql = "SELECT quantity FROM account_assets WHERE world = ? AND account_name = ? "
                + "AND asset_id = ?" + (mysql ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, request.worldId());
            statement.setString(2, request.account());
            statement.setString(3, request.assetId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return 0;
                }
                double quantity = result.getDouble("quantity");
                if (!Double.isFinite(quantity) || quantity < 0) {
                    throw new SQLException("Invalid existing wallet quantity");
                }
                return quantity;
            }
        }
    }

    private static void setHolding(Connection connection, boolean mysql, Request request) throws SQLException {
        String sql = "INSERT INTO account_assets(world, account_name, asset_id, quantity) VALUES (?, ?, ?, ?) "
                + (mysql
                ? "ON DUPLICATE KEY UPDATE quantity = VALUES(quantity)"
                : "ON CONFLICT(world, account_name, asset_id) DO UPDATE SET quantity = excluded.quantity");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, request.worldId());
            statement.setString(2, request.account());
            statement.setString(3, request.assetId());
            statement.setDouble(4, request.newQuantity());
            statement.executeUpdate();
        }
    }

    private static StoredAdjustment find(Connection connection, UUID requestId) throws SQLException {
        String sql = "SELECT event_type, world, account_name, asset_id, actor, old_quantity, "
                + "new_quantity, created_at FROM wallet_adjustments WHERE request_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(requestId));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredAdjustment(requestId, result.getString("event_type"),
                        result.getBytes("world"), result.getString("account_name"),
                        result.getString("asset_id"), result.getString("actor"),
                        result.getDouble("old_quantity"), result.getDouble("new_quantity"),
                        result.getLong("created_at"));
            }
        }
    }

    private static void insertAudit(Connection connection, Request request, double oldQuantity)
            throws SQLException {
        String sql = "INSERT INTO wallet_adjustments "
                + "(request_id, event_type, world, account_name, asset_id, actor, old_quantity, "
                + "new_quantity, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(request.requestId()));
            statement.setString(2, EVENT_TYPE);
            statement.setBytes(3, request.worldId());
            statement.setString(4, request.account());
            statement.setString(5, request.assetId());
            statement.setString(6, request.actor());
            statement.setDouble(7, oldQuantity);
            statement.setDouble(8, request.newQuantity());
            statement.setLong(9, request.nowMillis());
            statement.executeUpdate();
        }
    }

    private static void requireSamePayload(StoredAdjustment existing, Request request) throws SQLException {
        if (!EVENT_TYPE.equals(existing.eventType())
                || !Arrays.equals(existing.worldId(), request.worldId())
                || !existing.account().equals(request.account())
                || !existing.assetId().equals(request.assetId())
                || !existing.actor().equals(request.actor())
                || Double.compare(existing.newQuantity(), request.newQuantity()) != 0) {
            throw new SQLException("Wallet adjustment request UUID was reused with a different payload: "
                    + request.requestId());
        }
    }

    private static Result rejected(Status status, Request request) {
        return new Result(status, request.requestId(), 0, request.newQuantity(), 0);
    }

    private static boolean physical(int assetType) {
        return assetType == 2 || assetType == 3;
    }

    private static boolean physicalQuantity(double quantity) {
        return quantity <= Integer.MAX_VALUE && quantity == Math.rint(quantity);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
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

    private static Result recoverDuplicateKey(Connection connection, Request request,
                                              SQLException duplicateFailure) throws SQLException {
        StoredAdjustment existing;
        try {
            existing = find(connection, request.requestId());
        } catch (SQLException inspectionFailure) {
            duplicateFailure.addSuppressed(inspectionFailure);
            rollback(connection, duplicateFailure);
            return null;
        }
        if (existing == null) {
            rollback(connection, duplicateFailure);
            return null;
        }
        try {
            requireSamePayload(existing, request);
        } catch (SQLException collision) {
            rollback(connection, collision);
            collision.addSuppressed(duplicateFailure);
            throw collision;
        }
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            duplicateFailure.addSuppressed(rollbackFailure);
            throw duplicateFailure;
        }
        return existing.result(Status.ALREADY_APPLIED);
    }

    static boolean isRetryableTransactionFailure(SQLException failure, boolean mysql) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            String sqlState = current.getSQLState();
            if (sqlState != null && sqlState.startsWith("40")) {
                return true;
            }
            if (mysql && (current.getErrorCode() == 1205 || current.getErrorCode() == 1213)) {
                return true;
            }
            if (!mysql && (current.getErrorCode() == 5 || current.getErrorCode() == 6)) {
                return true;
            }
        }
        return false;
    }

    private static void pauseBeforeRetry(int attempt, SQLException original) throws SQLException {
        long exponentialDelay = BASE_RETRY_DELAY_MILLIS << Math.min(attempt - 1, 6);
        long delayMillis = exponentialDelay
                + ThreadLocalRandom.current().nextLong(exponentialDelay + 1);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            SQLException aborted = new SQLException(
                    "Interrupted while retrying a wallet adjustment transaction", "57014", interrupted);
            aborted.addSuppressed(original);
            throw aborted;
        }
    }

    private static boolean restoreForRetry(Connection connection, boolean originalAutoCommit,
                                           int originalIsolation, SQLException original) {
        SQLException restoreFailure = restoreConnectionStateFailure(
                connection, originalAutoCommit, originalIsolation);
        if (restoreFailure == null) {
            return true;
        }
        original.addSuppressed(restoreFailure);
        return false;
    }

    private static void restoreConnectionState(Connection connection, boolean originalAutoCommit,
                                               int originalIsolation, Throwable primaryFailure)
            throws SQLException {
        SQLException restoreFailure = restoreConnectionStateFailure(
                connection, originalAutoCommit, originalIsolation);
        if (restoreFailure == null) {
            return;
        }
        if (primaryFailure != null) {
            primaryFailure.addSuppressed(restoreFailure);
            return;
        }
        throw restoreFailure;
    }

    private static SQLException restoreConnectionStateFailure(Connection connection,
                                                              boolean originalAutoCommit,
                                                              int originalIsolation) {
        SQLException failure = null;
        boolean safeToRestoreProperties;
        try {
            if (connection.getAutoCommit()) {
                safeToRestoreProperties = true;
            } else {
                try {
                    connection.rollback();
                    safeToRestoreProperties = true;
                } catch (SQLException rollbackFailure) {
                    failure = append(failure, rollbackFailure);
                    safeToRestoreProperties = false;
                }
            }
        } catch (SQLException stateFailure) {
            failure = append(failure, stateFailure);
            safeToRestoreProperties = false;
        }
        // Switching false -> true commits according to JDBC. If rollback failed (or the
        // connection state is unknown), do not risk committing a partially applied adjustment;
        // leave this dedicated connection for the caller/pool to close and discard.
        if (!safeToRestoreProperties) {
            return failure;
        }
        try {
            if (connection.getTransactionIsolation() != originalIsolation) {
                connection.setTransactionIsolation(originalIsolation);
            }
        } catch (SQLException isolationFailure) {
            failure = append(failure, isolationFailure);
        }
        try {
            if (connection.getAutoCommit() != originalAutoCommit) {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException autoCommitFailure) {
            failure = append(failure, autoCommitFailure);
        }
        return failure;
    }

    private static SQLException append(SQLException primary, SQLException additional) {
        if (primary == null) {
            return additional;
        }
        primary.addSuppressed(additional);
        return primary;
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

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private record StoredAdjustment(UUID requestId, String eventType, byte[] worldId,
                                    String account, String assetId, String actor,
                                    double oldQuantity, double newQuantity, long createdAt) {
        private Result result(Status status) {
            return new Result(status, requestId, oldQuantity, newQuantity, createdAt);
        }
    }
}
