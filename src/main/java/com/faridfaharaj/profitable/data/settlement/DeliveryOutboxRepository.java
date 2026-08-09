package com.faridfaharaj.profitable.data.settlement;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Applies durable wallet-credit legs exactly once. */
public final class DeliveryOutboxRepository {

    private static final int MAX_ATTEMPTS = 10;
    private static final long LEASE_MS = 30_000L;

    private DeliveryOutboxRepository() {
    }

    public enum Status {
        EMPTY,
        PROCESSED,
        FAILED,
        LOST_OWNERSHIP
    }

    public record ProcessResult(Status status, UUID deliveryId, String error) {
        static ProcessResult empty() {
            return new ProcessResult(Status.EMPTY, null, null);
        }
    }

    /** Compact operational view used by the admin status command. */
    public record HealthSnapshot(
            long pending,
            long processing,
            long completed,
            long dead,
            long settledExecutions,
            long completedExecutions,
            long correlatedRequests,
            long auditedMarketAdjustments,
            long auditedWalletAdjustments
    ) {
    }

    public record DeadDelivery(
            UUID deliveryId,
            UUID executionId,
            String account,
            String asset,
            double quantity,
            int attempts,
            String lastError,
            long createdAt
    ) {
    }

    private record Delivery(UUID id, byte[] executionId, byte[] world, String account,
                            String asset, double quantity, int attempts, ClaimSource claimSource) {
    }

    private enum ClaimSource {
        PENDING,
        EXPIRED_PROCESSING
    }

    @FunctionalInterface
    private interface TimeSource {
        long currentTimeMillis(Connection connection, boolean mysql) throws SQLException;
    }

    /** Production entry point. All durable timestamps come from the database clock. */
    public static ProcessResult processOne(Connection connection, boolean mysql, String workerId)
            throws SQLException {
        return processOne(connection, mysql, workerId,
                DeliveryOutboxRepository::databaseCurrentTimeMillis);
    }

    /** Deterministic test entry point; production callers should use the overload without {@code now}. */
    public static ProcessResult processOne(Connection connection, boolean mysql, String workerId, long now)
            throws SQLException {
        return processOne(connection, mysql, workerId, (ignoredConnection, ignoredMysql) -> now);
    }

    private static ProcessResult processOne(Connection connection, boolean mysql, String workerId,
                                            TimeSource timeSource) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        int originalIsolation = connection.getTransactionIsolation();
        if (mysql && originalIsolation != Connection.TRANSACTION_READ_COMMITTED) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
        connection.setAutoCommit(false);
        Delivery delivery = null;
        boolean claimCommitted = false;
        boolean claimReloaded = false;
        try {
            long claimNow = timeSource.currentTimeMillis(connection, mysql);
            delivery = selectPending(connection, mysql, claimNow);
            if (delivery == null) {
                reconcileCompletedExecutions(connection,
                        timeSource.currentTimeMillis(connection, mysql));
                connection.commit();
                return ProcessResult.empty();
            }
            if (!claim(connection, delivery, workerId, claimNow)) {
                connection.rollback();
                return lostOwnership(delivery.id());
            }
            // Keep the queue range lock out of the wallet-credit transaction. Once
            // committed, the lease makes this row invisible to every other worker.
            connection.commit();
            claimCommitted = true;

            Delivery claimedDelivery = selectClaimed(connection, mysql, delivery.id(), workerId);
            if (claimedDelivery == null) {
                connection.rollback();
                return lostOwnership(delivery.id());
            }
            delivery = claimedDelivery;
            claimReloaded = true;

            if (!alreadyProcessed(connection, delivery.id())) {
                credit(connection, mysql, delivery);
                recordProcessed(connection, delivery.id(),
                        timeSource.currentTimeMillis(connection, mysql));
            }
            long completionNow = timeSource.currentTimeMillis(connection, mysql);
            if (!complete(connection, delivery, workerId, completionNow)) {
                connection.rollback();
                return lostOwnership(delivery.id());
            }
            connection.commit();
            // Updating a shared execution row while sibling delivery transactions still
            // hold foreign-key locks creates a lock-upgrade deadlock on MySQL/MariaDB.
            // Reconcile only after this delivery transaction releases all of its locks.
            try {
                if (delivery.executionId() != null) {
                    reconcileCompletedExecution(connection, delivery.executionId(),
                            timeSource.currentTimeMillis(connection, mysql));
                }
                connection.commit();
            } catch (SQLException reconciliationError) {
                connection.rollback();
                // The financial leg and its processed marker are already committed.
                // A later empty scan will repair the execution's observability status.
            }
            return new ProcessResult(Status.PROCESSED, delivery.id(), null);
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            if (delivery != null && claimCommitted && claimReloaded) {
                long failureNow;
                try {
                    failureNow = timeSource.currentTimeMillis(connection, mysql);
                } catch (SQLException clockError) {
                    clockError.addSuppressed(error);
                    throw clockError;
                }
                boolean markedFailed = markFailed(connection, delivery.id(), delivery.attempts(),
                        workerId, failureNow, error.getMessage());
                connection.commit();
                return markedFailed
                        ? new ProcessResult(Status.FAILED, delivery.id(), safeMessage(error))
                        : lostOwnership(delivery.id());
            }
            // If the committed claim can no longer be reloaded under this
            // owner, another worker owns it (or already completed it). Leave
            // the row untouched; an expired lease remains safely recoverable.
            if (claimCommitted) {
                // A SQL failure while reloading is not proof that ownership was
                // lost. Surface it so operators do not confuse an outage with a
                // benign compare-and-set miss; the committed lease will expire.
                throw error;
            }
            throw error;
        } finally {
            try {
                connection.setAutoCommit(originalAutoCommit);
            } finally {
                if (mysql && connection.getTransactionIsolation() != originalIsolation) {
                    connection.setTransactionIsolation(originalIsolation);
                }
            }
        }
    }

    static long databaseCurrentTimeMillis(Connection connection, boolean mysql) throws SQLException {
        String sql = mysql
                ? "SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 AS SIGNED)"
                : "SELECT CAST(strftime('%s', 'now') AS INTEGER) * 1000 "
                + "+ CAST(substr(strftime('%f', 'now'), 4, 3) AS INTEGER)";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new SQLException("Database clock query returned no row");
            }
            return result.getLong(1);
        }
    }

    public static HealthSnapshot healthSnapshot(Connection connection) throws SQLException {
        long pending;
        long processing;
        long completed;
        long dead;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT "
                        + "SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'PROCESSING' THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'DEAD' THEN 1 ELSE 0 END) "
                        + "FROM delivery_outbox");
             ResultSet result = statement.executeQuery()) {
            result.next();
            pending = result.getLong(1);
            processing = result.getLong(2);
            completed = result.getLong(3);
            dead = result.getLong(4);
        }

        long settledExecutions;
        long completedExecutions;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT "
                        + "SUM(CASE WHEN status = 'SETTLED' THEN 1 ELSE 0 END), "
                        + "SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) "
                        + "FROM trade_executions");
             ResultSet result = statement.executeQuery()) {
            result.next();
            settledExecutions = result.getLong(1);
            completedExecutions = result.getLong(2);
        }

        long correlatedRequests;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM exchange_requests");
             ResultSet result = statement.executeQuery()) {
            result.next();
            correlatedRequests = result.getLong(1);
        }

        long auditedMarketAdjustments;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM market_adjustments");
             ResultSet result = statement.executeQuery()) {
            result.next();
            auditedMarketAdjustments = result.getLong(1);
        }

        long auditedWalletAdjustments;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM wallet_adjustments");
             ResultSet result = statement.executeQuery()) {
            result.next();
            auditedWalletAdjustments = result.getLong(1);
        }
        return new HealthSnapshot(pending, processing, completed, dead,
                settledExecutions, completedExecutions, correlatedRequests,
                auditedMarketAdjustments, auditedWalletAdjustments);
    }

    public static List<DeadDelivery> listDead(Connection connection, int requestedLimit) throws SQLException {
        int limit = Math.clamp(requestedLimit, 1, 100);
        String sql = "SELECT delivery_id, execution_id, account_name, asset_id, quantity, attempts, "
                + "last_error, created_at FROM delivery_outbox WHERE status = 'DEAD' "
                + "ORDER BY created_at, delivery_id LIMIT ?";
        List<DeadDelivery> deliveries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    byte[] execution = result.getBytes("execution_id");
                    deliveries.add(new DeadDelivery(
                            uuid(result.getBytes("delivery_id")),
                            execution == null ? null : uuid(execution),
                            result.getString("account_name"),
                            result.getString("asset_id"),
                            result.getDouble("quantity"),
                            result.getInt("attempts"),
                            result.getString("last_error"),
                            result.getLong("created_at")));
                }
            }
        }
        return List.copyOf(deliveries);
    }

    /** Safely re-drives only terminal rows; processed_events still prevents duplicate credit. */
    public static boolean retryDead(Connection connection, UUID deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE delivery_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = 0, "
                        + "lease_owner = NULL, lease_until = 0, last_error = NULL, completed_at = NULL "
                        + "WHERE delivery_id = ? AND status = 'DEAD'")) {
            statement.setBytes(1, uuidBytes(deliveryId));
            return statement.executeUpdate() == 1;
        }
    }

    private static Delivery selectPending(Connection connection, boolean mysql, long now) throws SQLException {
        if (mysql) {
            // Recover crash-expired claims first. A continuously busy PENDING queue
            // must never prevent a durable credit from a dead backend being replayed.
            Delivery expired = selectCandidate(connection,
                    "SELECT delivery_id, execution_id, world, account_name, asset_id, quantity, attempts "
                            + "FROM delivery_outbox WHERE status = 'PROCESSING' AND lease_until <= ? "
                            + "ORDER BY lease_until, created_at, delivery_id "
                            + "LIMIT 1 FOR UPDATE SKIP LOCKED",
                    now, ClaimSource.EXPIRED_PROCESSING);
            if (expired != null) {
                return expired;
            }
            return selectCandidate(connection,
                    "SELECT delivery_id, execution_id, world, account_name, asset_id, quantity, attempts "
                            + "FROM delivery_outbox WHERE status = 'PENDING' AND next_attempt_at <= ? "
                            + "ORDER BY next_attempt_at, created_at, delivery_id "
                            + "LIMIT 1 FOR UPDATE SKIP LOCKED",
                    now, ClaimSource.PENDING);
        }

        Delivery expired = selectCandidate(connection,
                "SELECT delivery_id, execution_id, world, account_name, asset_id, quantity, attempts "
                        + "FROM delivery_outbox WHERE status = 'PROCESSING' AND lease_until <= ? "
                        + "ORDER BY lease_until, created_at, delivery_id LIMIT 1",
                now, ClaimSource.EXPIRED_PROCESSING);
        if (expired != null) {
            return expired;
        }
        return selectCandidate(connection,
                "SELECT delivery_id, execution_id, world, account_name, asset_id, quantity, attempts "
                        + "FROM delivery_outbox WHERE status = 'PENDING' AND next_attempt_at <= ? "
                        + "ORDER BY next_attempt_at, created_at, delivery_id LIMIT 1",
                now, ClaimSource.PENDING);
    }

    private static Delivery selectCandidate(Connection connection, String sql, long deadline,
                                            ClaimSource claimSource) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, deadline);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Delivery(uuid(result.getBytes("delivery_id")),
                        result.getBytes("execution_id"), result.getBytes("world"),
                        result.getString("account_name"), result.getString("asset_id"),
                        result.getDouble("quantity"), result.getInt("attempts"), claimSource);
            }
        }
    }

    private static Delivery selectClaimed(Connection connection, boolean mysql, UUID deliveryId, String workerId)
            throws SQLException {
        String sql = "SELECT delivery_id, execution_id, world, account_name, asset_id, quantity, attempts "
                + "FROM delivery_outbox WHERE delivery_id = ? AND status = 'PROCESSING' AND lease_owner = ?"
                + (mysql ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(deliveryId));
            statement.setString(2, workerId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new Delivery(uuid(result.getBytes("delivery_id")),
                        result.getBytes("execution_id"), result.getBytes("world"),
                        result.getString("account_name"), result.getString("asset_id"),
                        result.getDouble("quantity"), result.getInt("attempts"), null);
            }
        }
    }

    private static boolean claim(Connection connection, Delivery delivery, String workerId, long now)
            throws SQLException {
        String eligibility = delivery.claimSource() == ClaimSource.EXPIRED_PROCESSING
                ? "status = 'PROCESSING' AND lease_until <= ?"
                : "status = 'PENDING' AND next_attempt_at <= ?";
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE delivery_outbox SET status = 'PROCESSING', attempts = ?, lease_owner = ?, "
                        + "lease_until = ?, last_error = NULL WHERE delivery_id = ? AND " + eligibility)) {
            statement.setInt(1, delivery.attempts() + 1);
            statement.setString(2, workerId);
            statement.setLong(3, saturatedAdd(now, LEASE_MS));
            statement.setBytes(4, uuidBytes(delivery.id()));
            statement.setLong(5, now);
            return statement.executeUpdate() == 1;
        }
    }

    private static boolean alreadyProcessed(Connection connection, UUID deliveryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM processed_events WHERE event_id = ?")) {
            statement.setBytes(1, uuidBytes(deliveryId));
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void credit(Connection connection, boolean mysql, Delivery delivery) throws SQLException {
        String sql = "INSERT INTO account_assets(world, account_name, asset_id, quantity) VALUES (?, ?, ?, ?) "
                + (mysql
                ? "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)"
                : "ON CONFLICT(world, account_name, asset_id) DO UPDATE SET quantity = quantity + excluded.quantity");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, delivery.world());
            statement.setString(2, delivery.account());
            statement.setString(3, delivery.asset());
            statement.setDouble(4, delivery.quantity());
            if (statement.executeUpdate() < 1) {
                throw new SQLException("Outbox credit affected no rows");
            }
        }
    }

    private static void recordProcessed(Connection connection, UUID deliveryId, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO processed_events(event_id, event_type, processed_at) VALUES (?, 'DELIVERY_CREDIT', ?)")) {
            statement.setBytes(1, uuidBytes(deliveryId));
            statement.setLong(2, now);
            statement.executeUpdate();
        }
    }

    private static boolean complete(Connection connection, Delivery delivery, String workerId, long now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE delivery_outbox SET status = 'COMPLETED', lease_owner = NULL, lease_until = 0, "
                        + "completed_at = ? WHERE delivery_id = ? "
                        + "AND status = 'PROCESSING' AND lease_owner = ?")) {
            statement.setLong(1, now);
            statement.setBytes(2, uuidBytes(delivery.id()));
            statement.setString(3, workerId);
            return statement.executeUpdate() == 1;
        }
    }

    private static void reconcileCompletedExecution(Connection connection, byte[] executionId, long now)
            throws SQLException {
        String sql = "UPDATE trade_executions SET status = 'COMPLETED', completed_at = ? "
                + "WHERE execution_id = ? AND status = 'SETTLED' "
                + "AND EXISTS (SELECT 1 FROM delivery_outbox "
                + "WHERE delivery_outbox.execution_id = trade_executions.execution_id) "
                + "AND NOT EXISTS (SELECT 1 FROM delivery_outbox "
                + "WHERE delivery_outbox.execution_id = trade_executions.execution_id "
                + "AND delivery_outbox.status <> 'COMPLETED')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.setBytes(2, executionId);
            statement.executeUpdate();
        }
    }

    private static void reconcileCompletedExecutions(Connection connection, long now) throws SQLException {
        String sql = "UPDATE trade_executions SET status = 'COMPLETED', completed_at = ? "
                + "WHERE status = 'SETTLED' AND EXISTS (SELECT 1 FROM delivery_outbox "
                + "WHERE delivery_outbox.execution_id = trade_executions.execution_id) "
                + "AND NOT EXISTS (SELECT 1 FROM delivery_outbox "
                + "WHERE delivery_outbox.execution_id = trade_executions.execution_id "
                + "AND delivery_outbox.status <> 'COMPLETED')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            statement.executeUpdate();
        }
    }

    static boolean markFailed(Connection connection, UUID deliveryId, int attempts, String workerId,
                              long now, String message)
            throws SQLException {
        // Claiming already persisted the increment in its own short transaction.
        boolean dead = attempts >= MAX_ATTEMPTS;
        long delay = Math.min(300_000L, 1_000L << Math.min(18, Math.max(0, attempts - 1)));
        long nextAttemptAt = dead || now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE delivery_outbox SET status = ?, attempts = ?, next_attempt_at = ?, lease_owner = NULL, "
                        + "lease_until = 0, last_error = ? WHERE delivery_id = ? "
                        + "AND status = 'PROCESSING' AND lease_owner = ?")) {
            statement.setString(1, dead ? "DEAD" : "PENDING");
            statement.setInt(2, attempts);
            statement.setLong(3, nextAttemptAt);
            statement.setString(4, truncate(message));
            statement.setBytes(5, uuidBytes(deliveryId));
            statement.setString(6, workerId);
            // Zero rows means this lease was completed or reclaimed after our
            // transaction failed; never regress a newer owner's state.
            return statement.executeUpdate() == 1;
        }
    }

    private static ProcessResult lostOwnership(UUID deliveryId) {
        return new ProcessResult(Status.LOST_OWNERSHIP, deliveryId, null);
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static String truncate(String message) {
        String value = message == null || message.isBlank() ? "Unknown delivery failure" : message;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private static String safeMessage(Throwable error) {
        return truncate(error.getMessage());
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) throws SQLException {
        if (value == null || value.length != 16) {
            throw new SQLException("Invalid outbox UUID");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
