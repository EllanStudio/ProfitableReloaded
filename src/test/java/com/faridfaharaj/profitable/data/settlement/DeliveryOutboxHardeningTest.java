package com.faridfaharaj.profitable.data.settlement;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryOutboxHardeningTest {

    private static final byte[] WORLD = new byte[16];
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void productionEntryUsesDatabaseClockAndExplicitEntryRemainsDeterministic() throws Exception {
        try (Connection connection = database()) {
            UUID databaseTimed = UUID.randomUUID();
            insertDelivery(connection, databaseTimed, null, "database-timed", "recipient", "EMD",
                    2, "PENDING", 0, 0, null, 0, 1);

            long before = DeliveryOutboxRepository.databaseCurrentTimeMillis(connection, false);
            DeliveryOutboxRepository.ProcessResult result =
                    DeliveryOutboxRepository.processOne(connection, false, "database-clock-worker");
            long after = DeliveryOutboxRepository.databaseCurrentTimeMillis(connection, false);

            assertEquals(DeliveryOutboxRepository.Status.PROCESSED, result.status());
            long processedAt = longValue(connection,
                    "SELECT processed_at FROM processed_events WHERE event_id = " + blob(databaseTimed));
            long completedAt = longValue(connection,
                    "SELECT completed_at FROM delivery_outbox WHERE delivery_id = " + blob(databaseTimed));
            assertTrue(processedAt >= before && processedAt <= after,
                    () -> "processed_at=" + processedAt + " was outside DB interval " + before + ".." + after);
            assertTrue(completedAt >= before && completedAt <= after,
                    () -> "completed_at=" + completedAt + " was outside DB interval " + before + ".." + after);

            UUID explicitlyTimed = UUID.randomUUID();
            insertDelivery(connection, explicitlyTimed, null, "explicitly-timed", "recipient", "EMD",
                    3, "PENDING", 0, 0, null, 0, 2);
            assertEquals(DeliveryOutboxRepository.Status.PROCESSED,
                    DeliveryOutboxRepository.processOne(
                            connection, false, "explicit-clock-worker", NOW).status());
            assertEquals(NOW, longValue(connection,
                    "SELECT processed_at FROM processed_events WHERE event_id = " + blob(explicitlyTimed)));
            assertEquals(NOW, longValue(connection,
                    "SELECT completed_at FROM delivery_outbox WHERE delivery_id = " + blob(explicitlyTimed)));
        }
    }

    @Test
    void productionLeaseAndFailureBackoffAreAnchoredToDatabaseClock() throws Exception {
        try (Connection connection = database()) {
            UUID leased = UUID.randomUUID();
            insertDelivery(connection, leased, null, "leased", "recipient", "EMD",
                    1, "PENDING", 0, 0, null, 0, 1);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER steal_database_timed_claim "
                        + "AFTER UPDATE OF lease_owner ON delivery_outbox "
                        + "WHEN NEW.status = 'PROCESSING' AND NEW.lease_owner = 'lease-worker' "
                        + "BEGIN UPDATE delivery_outbox SET lease_owner = 'other-worker' "
                        + "WHERE delivery_id = NEW.delivery_id; END");
            }
            long beforeLease = DeliveryOutboxRepository.databaseCurrentTimeMillis(connection, false);
            assertEquals(DeliveryOutboxRepository.Status.LOST_OWNERSHIP,
                    DeliveryOutboxRepository.processOne(connection, false, "lease-worker").status());
            long afterLease = DeliveryOutboxRepository.databaseCurrentTimeMillis(connection, false);
            long leaseUntil = longValue(connection,
                    "SELECT lease_until FROM delivery_outbox WHERE delivery_id = " + blob(leased));
            assertTrue(leaseUntil >= beforeLease + 30_000L && leaseUntil <= afterLease + 30_000L,
                    () -> "lease_until=" + leaseUntil + " was not anchored to DB interval "
                            + beforeLease + ".." + afterLease);

            UUID failed = UUID.randomUUID();
            insertDelivery(connection, failed, null, "failed", "recipient", "EMD",
                    1, "PENDING", 0, 0, null, 0, 2);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_wallet_credit BEFORE INSERT ON account_assets "
                        + "BEGIN SELECT RAISE(ABORT, 'simulated wallet failure'); END");
            }
            long beforeFailure = DeliveryOutboxRepository.databaseCurrentTimeMillis(connection, false);
            assertEquals(DeliveryOutboxRepository.Status.FAILED,
                    DeliveryOutboxRepository.processOne(connection, false, "failure-worker").status());
            long afterFailure = DeliveryOutboxRepository.databaseCurrentTimeMillis(connection, false);
            long nextAttemptAt = longValue(connection,
                    "SELECT next_attempt_at FROM delivery_outbox WHERE delivery_id = " + blob(failed));
            assertTrue(nextAttemptAt >= beforeFailure + 1_000L && nextAttemptAt <= afterFailure + 1_000L,
                    () -> "next_attempt_at=" + nextAttemptAt + " was not anchored to DB interval "
                            + beforeFailure + ".." + afterFailure);
        }
    }

    @Test
    void expiredProcessingLeaseIsSelectedAheadOfContinuouslyReadyPendingWork() throws Exception {
        try (Connection connection = database()) {
            UUID olderPending = UUID.randomUUID();
            UUID expiredClaim = UUID.randomUUID();
            insertDelivery(connection, olderPending, null, "pending", "pending-recipient", "EMD",
                    1, "PENDING", 0, 0, null, 0, 1);
            insertDelivery(connection, expiredClaim, null, "expired", "expired-recipient", "EMD",
                    7, "PROCESSING", 4, 0, "crashed-worker", NOW - 1, 2);

            DeliveryOutboxRepository.ProcessResult result =
                    DeliveryOutboxRepository.processOne(connection, false, "replacement-worker", NOW);

            assertEquals(DeliveryOutboxRepository.Status.PROCESSED, result.status());
            assertEquals(expiredClaim, result.deliveryId());
            assertEquals("COMPLETED", stringValue(connection,
                    "SELECT status FROM delivery_outbox WHERE delivery_id = " + blob(expiredClaim)));
            assertEquals(5, longValue(connection,
                    "SELECT attempts FROM delivery_outbox WHERE delivery_id = " + blob(expiredClaim)));
            assertEquals("PENDING", stringValue(connection,
                    "SELECT status FROM delivery_outbox WHERE delivery_id = " + blob(olderPending)));
            assertEquals(7.0, balance(connection, "expired-recipient", "EMD"), 0);
            assertEquals(0.0, balance(connection, "pending-recipient", "EMD"), 0);
        }
    }

    @Test
    void lostLeaseOwnershipIsNotReportedOrPersistedAsDeliveryFailure() throws Exception {
        try (Connection connection = database()) {
            UUID deliveryId = UUID.randomUUID();
            insertDelivery(connection, deliveryId, null, "ownership-race", "recipient", "EMD",
                    1, "PENDING", 0, 0, null, 0, 1);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER steal_outbox_claim "
                        + "AFTER UPDATE OF lease_owner ON delivery_outbox "
                        + "WHEN NEW.status = 'PROCESSING' AND NEW.lease_owner = 'stale-worker' "
                        + "BEGIN UPDATE delivery_outbox SET lease_owner = 'new-worker' "
                        + "WHERE delivery_id = NEW.delivery_id; END");
            }

            DeliveryOutboxRepository.ProcessResult result =
                    DeliveryOutboxRepository.processOne(connection, false, "stale-worker", NOW);

            assertEquals(DeliveryOutboxRepository.Status.LOST_OWNERSHIP, result.status());
            assertEquals(deliveryId, result.deliveryId());
            assertNull(result.error());
            assertEquals("PROCESSING", stringValue(connection,
                    "SELECT status FROM delivery_outbox WHERE delivery_id = " + blob(deliveryId)));
            assertEquals("new-worker", stringValue(connection,
                    "SELECT lease_owner FROM delivery_outbox WHERE delivery_id = " + blob(deliveryId)));
            assertNull(nullableString(connection,
                    "SELECT last_error FROM delivery_outbox WHERE delivery_id = " + blob(deliveryId)));
            assertEquals(0.0, balance(connection, "recipient", "EMD"), 0);

            connection.setAutoCommit(false);
            assertFalse(DeliveryOutboxRepository.markFailed(
                    connection, deliveryId, 1, "stale-worker", NOW, "stale failure"));
            connection.commit();
            connection.setAutoCommit(true);
            assertEquals("PROCESSING", stringValue(connection,
                    "SELECT status FROM delivery_outbox WHERE delivery_id = " + blob(deliveryId)));
            assertEquals("new-worker", stringValue(connection,
                    "SELECT lease_owner FROM delivery_outbox WHERE delivery_id = " + blob(deliveryId)));
            assertNull(nullableString(connection,
                    "SELECT last_error FROM delivery_outbox WHERE delivery_id = " + blob(deliveryId)));
        }
    }

    @Test
    void reconcileRequiresLegsCompletesOnlyAfterLastLegAndRepairsMissedExecutionOnEmptyQueue() throws Exception {
        try (Connection connection = database()) {
            UUID twoLegExecution = UUID.randomUUID();
            UUID zeroLegExecution = UUID.randomUUID();
            UUID missedReconcileExecution = UUID.randomUUID();
            insertExecution(connection, twoLegExecution);
            insertExecution(connection, zeroLegExecution);
            insertExecution(connection, missedReconcileExecution);
            insertDelivery(connection, UUID.randomUUID(), twoLegExecution, "targeted:first", "recipient", "EMD",
                    1, "PENDING", 0, 0, null, 0, 1);
            insertDelivery(connection, UUID.randomUUID(), twoLegExecution, "targeted:last", "recipient", "EMD",
                    2, "PENDING", 0, 0, null, 0, 2);
            insertDelivery(connection, UUID.randomUUID(), missedReconcileExecution, "missed-reconcile",
                    "recipient", "EMD", 3, "COMPLETED", 1, 0, null, 0, 3);

            assertEquals(DeliveryOutboxRepository.Status.PROCESSED,
                    DeliveryOutboxRepository.processOne(connection, false, "worker", NOW).status());
            assertEquals("SETTLED", executionStatus(connection, twoLegExecution));
            assertEquals("SETTLED", executionStatus(connection, zeroLegExecution));
            assertEquals("SETTLED", executionStatus(connection, missedReconcileExecution));

            assertEquals(DeliveryOutboxRepository.Status.PROCESSED,
                    DeliveryOutboxRepository.processOne(connection, false, "worker", NOW + 1).status());
            assertEquals("COMPLETED", executionStatus(connection, twoLegExecution));
            assertEquals("SETTLED", executionStatus(connection, zeroLegExecution));
            assertEquals("SETTLED", executionStatus(connection, missedReconcileExecution));

            assertEquals(DeliveryOutboxRepository.Status.EMPTY,
                    DeliveryOutboxRepository.processOne(connection, false, "worker", NOW + 2).status());
            assertEquals("SETTLED", executionStatus(connection, zeroLegExecution));
            assertEquals("COMPLETED", executionStatus(connection, missedReconcileExecution));
            assertEquals(NOW + 2, longValue(connection,
                    "SELECT completed_at FROM trade_executions WHERE execution_id = "
                            + blob(missedReconcileExecution)));
        }
    }

    @Test
    void healthSnapshotIncludesBothAuditedAdjustmentStreams() throws Exception {
        try (Connection connection = database(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE exchange_requests (request_id BLOB PRIMARY KEY)");
            statement.execute("CREATE TABLE market_adjustments (request_id BLOB PRIMARY KEY)");
            statement.execute("CREATE TABLE wallet_adjustments (request_id BLOB PRIMARY KEY)");
            statement.execute("INSERT INTO exchange_requests VALUES (randomblob(16))");
            statement.execute("INSERT INTO market_adjustments VALUES (randomblob(16))");
            statement.execute("INSERT INTO wallet_adjustments VALUES (randomblob(16))");

            insertExecution(connection, UUID.randomUUID());
            try (PreparedStatement completedExecution = connection.prepareStatement(
                    "INSERT INTO trade_executions(execution_id, status, completed_at) "
                            + "VALUES (?, 'COMPLETED', ?)") ) {
                completedExecution.setBytes(1, uuidBytes(UUID.randomUUID()));
                completedExecution.setLong(2, NOW);
                completedExecution.executeUpdate();
            }

            DeliveryOutboxRepository.HealthSnapshot snapshot =
                    DeliveryOutboxRepository.healthSnapshot(connection);
            assertEquals(1, snapshot.correlatedRequests());
            assertEquals(1, snapshot.auditedMarketAdjustments());
            assertEquals(1, snapshot.auditedWalletAdjustments());
            assertEquals(1, snapshot.settledExecutions());
            assertEquals(1, snapshot.completedExecutions());
        }
    }

    private static Connection database() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE account_assets ("
                    + "world BLOB NOT NULL, account_name TEXT NOT NULL, asset_id TEXT NOT NULL, "
                    + "quantity REAL NOT NULL, PRIMARY KEY(world, account_name, asset_id))");
            statement.execute("CREATE TABLE processed_events ("
                    + "event_id BLOB PRIMARY KEY, event_type TEXT NOT NULL, processed_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE trade_executions ("
                    + "execution_id BLOB PRIMARY KEY, status TEXT NOT NULL, completed_at INTEGER)");
            statement.execute("CREATE TABLE delivery_outbox ("
                    + "delivery_id BLOB PRIMARY KEY, execution_id BLOB, leg_key TEXT NOT NULL UNIQUE, "
                    + "world BLOB NOT NULL, account_name TEXT NOT NULL, asset_id TEXT NOT NULL, "
                    + "quantity REAL NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, "
                    + "next_attempt_at INTEGER NOT NULL, lease_owner TEXT, lease_until INTEGER NOT NULL, "
                    + "last_error TEXT, created_at INTEGER NOT NULL, completed_at INTEGER)");
        }
        return connection;
    }

    private static void insertExecution(Connection connection, UUID executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO trade_executions(execution_id, status, completed_at) VALUES (?, 'SETTLED', NULL)")) {
            statement.setBytes(1, uuidBytes(executionId));
            statement.executeUpdate();
        }
    }

    private static void insertDelivery(Connection connection, UUID deliveryId, UUID executionId,
                                       String legKey, String account, String asset, double quantity,
                                       String status, int attempts, long nextAttemptAt, String leaseOwner,
                                       long leaseUntil, long createdAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO delivery_outbox(delivery_id, execution_id, leg_key, world, account_name, asset_id, "
                        + "quantity, status, attempts, next_attempt_at, lease_owner, lease_until, last_error, "
                        + "created_at, completed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL)")) {
            statement.setBytes(1, uuidBytes(deliveryId));
            if (executionId == null) {
                statement.setNull(2, java.sql.Types.BINARY);
            } else {
                statement.setBytes(2, uuidBytes(executionId));
            }
            statement.setString(3, legKey);
            statement.setBytes(4, WORLD);
            statement.setString(5, account);
            statement.setString(6, asset);
            statement.setDouble(7, quantity);
            statement.setString(8, status);
            statement.setInt(9, attempts);
            statement.setLong(10, nextAttemptAt);
            statement.setString(11, leaseOwner);
            statement.setLong(12, leaseUntil);
            statement.setLong(13, createdAt);
            statement.executeUpdate();
        }
    }

    private static double balance(Connection connection, String account, String asset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT quantity FROM account_assets WHERE world = ? AND account_name = ? AND asset_id = ?")) {
            statement.setBytes(1, WORLD);
            statement.setString(2, account);
            statement.setString(3, asset);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getDouble(1) : 0;
            }
        }
    }

    private static String executionStatus(Connection connection, UUID executionId) throws SQLException {
        return stringValue(connection,
                "SELECT status FROM trade_executions WHERE execution_id = " + blob(executionId));
    }

    private static long longValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String stringValue(Connection connection, String sql) throws SQLException {
        String value = nullableString(connection, sql);
        if (value == null) {
            throw new AssertionError("Expected non-null query result for: " + sql);
        }
        return value;
    }

    private static String nullableString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static String blob(UUID id) {
        StringBuilder hex = new StringBuilder("X'");
        for (byte value : uuidBytes(id)) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.append('\'').toString();
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
