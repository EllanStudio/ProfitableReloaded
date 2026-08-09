package com.faridfaharaj.profitable.data.settlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletAdjustmentRepositoryTest {

    private static final byte[] WORLD = uuidBytes(new UUID(101, 202));
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
    void auditsAbsoluteMintAndBurnAndRejectsUuidPayloadCollision() throws Exception {
        Path database = prepare("wallet-audit.db");
        UUID requestId = UUID.randomUUID();
        try (Connection connection = open(database)) {
            WalletAdjustmentRepository.Result mint = WalletAdjustmentRepository.apply(connection, false,
                    request(requestId, "EMD", 25));
            assertEquals(WalletAdjustmentRepository.Status.APPLIED, mint.status());
            assertEquals(10, mint.oldQuantity());
            assertEquals(25, mint.newQuantity());

            WalletAdjustmentRepository.Result replay = WalletAdjustmentRepository.apply(connection, false,
                    request(requestId, "EMD", 25));
            assertEquals(WalletAdjustmentRepository.Status.ALREADY_APPLIED, replay.status());
            assertEquals(10, replay.oldQuantity());
            assertEquals(25, balance(connection, "EMD"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));

            assertThrows(SQLException.class, () -> WalletAdjustmentRepository.apply(connection, false,
                    request(requestId, "EMD", 26)));
            assertEquals(25, balance(connection, "EMD"));

            WalletAdjustmentRepository.Result burn = WalletAdjustmentRepository.apply(connection, false,
                    request(UUID.randomUUID(), "EMD", 5));
            assertEquals(WalletAdjustmentRepository.Status.APPLIED, burn.status());
            assertEquals(25, burn.oldQuantity());
            assertEquals(5, balance(connection, "EMD"));
            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
            assertEquals("ADMIN_WALLET_SET", scalarString(connection,
                    "SELECT event_type FROM wallet_adjustments LIMIT 1"));

            // Idempotency is anchored in the immutable audit, not mutable account/asset rows.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM accounts WHERE account_name = 'alice'");
                statement.executeUpdate("DELETE FROM assets WHERE asset_id = 'EMD'");
            }
            assertEquals(WalletAdjustmentRepository.Status.ALREADY_APPLIED,
                    WalletAdjustmentRepository.apply(connection, false,
                            request(requestId, "EMD", 25)).status());
            assertThrows(SQLException.class, () -> WalletAdjustmentRepository.apply(connection, false,
                    new WalletAdjustmentRepository.Request(requestId, WORLD, "missing-account", "EMD",
                            "root-admin", 25, NOW)));
        }
    }

    @Test
    void rejectsPendingDurableCreditThenAppliesSameRequestAfterCreditCompletes() throws Exception {
        Path database = prepare("pending-credit.db");
        UUID requestId = UUID.randomUUID();
        try (Connection connection = open(database)) {
            insertPendingDelivery(connection, "EMD", 3);

            WalletAdjustmentRepository.Result blocked = WalletAdjustmentRepository.apply(connection, false,
                    request(requestId, "EMD", 20));
            assertEquals(WalletAdjustmentRepository.Status.PENDING_DELIVERY, blocked.status());
            assertEquals(10, balance(connection, "EMD"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));

            DeliveryOutboxRepository.ProcessResult delivery = DeliveryOutboxRepository.processOne(
                    connection, false, "wallet-adjustment-test", NOW + 1_000);
            assertEquals(DeliveryOutboxRepository.Status.PROCESSED, delivery.status());
            assertEquals(13, balance(connection, "EMD"));

            WalletAdjustmentRepository.Result applied = WalletAdjustmentRepository.apply(connection, false,
                    request(requestId, "EMD", 20));
            assertEquals(WalletAdjustmentRepository.Status.APPLIED, applied.status());
            assertEquals(13, applied.oldQuantity());
            assertEquals(20, balance(connection, "EMD"));
        }
    }

    @Test
    void enforcesIntegralBoundedPhysicalBalances() throws Exception {
        Path database = prepare("physical-quantity.db");
        try (Connection connection = open(database)) {
            WalletAdjustmentRepository.Result fractional = WalletAdjustmentRepository.apply(connection, false,
                    request(UUID.randomUUID(), "IRON_INGOT", 4.5));
            assertEquals(WalletAdjustmentRepository.Status.INVALID_QUANTITY, fractional.status());
            assertEquals(4, balance(connection, "IRON_INGOT"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));

            WalletAdjustmentRepository.Result oversized = WalletAdjustmentRepository.apply(connection, false,
                    request(UUID.randomUUID(), "IRON_INGOT", (double) Integer.MAX_VALUE + 1));
            assertEquals(WalletAdjustmentRepository.Status.INVALID_QUANTITY, oversized.status());

            WalletAdjustmentRepository.Result integral = WalletAdjustmentRepository.apply(connection, false,
                    request(UUID.randomUUID(), "IRON_INGOT", 8));
            assertEquals(WalletAdjustmentRepository.Status.APPLIED, integral.status());
            assertEquals(4, integral.oldQuantity());
            assertEquals(8, balance(connection, "IRON_INGOT"));
        }
    }

    @Test
    void concurrentRetriesCommitOneAuditRecord() throws Exception {
        Path database = prepare("wallet-concurrent-replay.db");
        UUID requestId = UUID.randomUUID();
        int workers = 6;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<WalletAdjustmentRepository.Status>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = open(database)) {
                        ready.countDown();
                        start.await();
                        return WalletAdjustmentRepository.apply(connection, false,
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
            assertEquals(workers - 1L, statuses.stream()
                    .filter(status -> status == WalletAdjustmentRepository.Status.ALREADY_APPLIED).count());
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = open(database)) {
            assertEquals(40, balance(connection, "EMD"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
            assertEquals(10, scalarDouble(connection,
                    "SELECT old_quantity FROM wallet_adjustments"));
        }
    }

    @Test
    void concurrentDistinctAbsoluteSetsSerializeAndAuditTruthfulOldValues() throws Exception {
        Path database = prepare("wallet-concurrent-distinct.db");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<WalletAdjustmentRepository.Status>> futures = new ArrayList<>();
            for (double quantity : List.of(20.0, 30.0)) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = open(database)) {
                        ready.countDown();
                        start.await();
                        return WalletAdjustmentRepository.apply(connection, false,
                                request(UUID.randomUUID(), "EMD", quantity)).status();
                    }
                }));
            }
            ready.await();
            start.countDown();
            for (Future<WalletAdjustmentRepository.Status> future : futures) {
                assertEquals(WalletAdjustmentRepository.Status.APPLIED, future.get());
            }
        } finally {
            executor.shutdownNow();
        }

        try (Connection connection = open(database)) {
            double finalBalance = balance(connection, "EMD");
            assertTrue(finalBalance == 20 || finalBalance == 30);
            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM wallet_adjustments WHERE old_quantity = 10"));
            assertEquals(1, scalarLong(connection,
                    "SELECT COUNT(*) FROM wallet_adjustments WHERE old_quantity IN (20, 30) "
                            + "AND new_quantity = " + finalBalance));
        }
    }

    @Test
    void retriesRolledBackTransientTransactionWithoutDuplicatingAudit() throws Exception {
        Path database = prepare("wallet-transient-retry.db");
        AtomicInteger holdingWrites = new AtomicInteger();
        try (Connection connection = open(database)) {
            Connection failFirstWrite = failFirstHoldingWrite(connection, holdingWrites,
                    new SQLException("synthetic transaction deadlock", "40001", 1213));
            WalletAdjustmentRepository.Result result = WalletAdjustmentRepository.apply(
                    failFirstWrite, false, request(UUID.randomUUID(), "EMD", 40));

            assertEquals(WalletAdjustmentRepository.Status.APPLIED, result.status());
            assertEquals(2, holdingWrites.get());
            assertEquals(40, balance(connection, "EMD"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
            assertTrue(connection.getAutoCommit());

            int requestedIsolation = connection.getTransactionIsolation() == Connection.TRANSACTION_SERIALIZABLE
                    ? Connection.TRANSACTION_READ_UNCOMMITTED : Connection.TRANSACTION_SERIALIZABLE;
            connection.setTransactionIsolation(requestedIsolation);
            connection.setAutoCommit(false);
            AtomicInteger manualCommitWrites = new AtomicInteger();
            Connection manualCommitRetry = failFirstHoldingWrite(connection, manualCommitWrites,
                    new SQLException("synthetic serialization rollback", "40001", 0));
            WalletAdjustmentRepository.Result manualCommitResult = WalletAdjustmentRepository.apply(
                    manualCommitRetry, false, request(UUID.randomUUID(), "EMD", 50));
            assertEquals(WalletAdjustmentRepository.Status.APPLIED, manualCommitResult.status());
            assertEquals(2, manualCommitWrites.get());
            assertFalse(connection.getAutoCommit());
            assertEquals(requestedIsolation, connection.getTransactionIsolation());
            assertEquals(50, balance(connection, "EMD"));
            connection.rollback();
            connection.setAutoCommit(true);

            AtomicInteger nonTransientWrites = new AtomicInteger();
            Connection failWithoutRetry = failFirstHoldingWrite(connection, nonTransientWrites,
                    new SQLException("synthetic non-transient failure", "42000", 1064));
            SQLException nonTransient = assertThrows(SQLException.class,
                    () -> WalletAdjustmentRepository.apply(failWithoutRetry, false,
                            request(UUID.randomUUID(), "EMD", 60)));
            assertEquals("42000", nonTransient.getSQLState());
            assertEquals(1, nonTransientWrites.get());
            assertEquals(50, balance(connection, "EMD"));
            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));

            AtomicInteger rollbackFailureWrites = new AtomicInteger();
            AtomicInteger unsafeAutoCommitRestores = new AtomicInteger();
            Connection failWrite = failFirstHoldingWrite(connection, rollbackFailureWrites,
                    new SQLException("synthetic primary failure", "42000", 1064));
            Connection failRollback = failRollbackAndObserveAutoCommit(
                    failWrite, unsafeAutoCommitRestores);
            SQLException primary = assertThrows(SQLException.class,
                    () -> WalletAdjustmentRepository.apply(failRollback, false,
                            request(UUID.randomUUID(), "EMD", 70)));
            assertEquals("42000", primary.getSQLState());
            assertTrue(primary.getSuppressed().length > 0);
            assertEquals(0, unsafeAutoCommitRestores.get());
            assertFalse(connection.getAutoCommit());
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(50, balance(connection, "EMD"));
            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM wallet_adjustments"));
        }
    }

    private Path prepare(String name) throws Exception {
        Path database = temporaryDirectory.resolve(name);
        try (Connection connection = open(database)) {
            for (String migration : MIGRATIONS) {
                apply(connection, migration);
            }
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
                            + "entity_delivery_pos, entity_claim_id) VALUES (?, 'alice', NULL, NULL, NULL, NULL, 1)")) {
                statement.setBytes(1, WORLD);
                statement.executeUpdate();
            }
            holding(connection, "EMD", 10);
            holding(connection, "IRON_INGOT", 4);
        }
        return database;
    }

    private static WalletAdjustmentRepository.Request request(UUID id, String asset, double quantity) {
        return new WalletAdjustmentRepository.Request(id, WORLD, "alice", asset,
                "root-admin", quantity, NOW);
    }

    private static void holding(Connection connection, String asset, double quantity) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_assets(world, account_name, asset_id, quantity) "
                        + "VALUES (?, 'alice', ?, ?)")) {
            statement.setBytes(1, WORLD);
            statement.setString(2, asset);
            statement.setDouble(3, quantity);
            statement.executeUpdate();
        }
    }

    private static void insertPendingDelivery(Connection connection, String asset, double quantity)
            throws SQLException {
        UUID deliveryId = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO delivery_outbox(delivery_id, execution_id, leg_key, world, account_name, "
                        + "asset_id, quantity, status, attempts, next_attempt_at, lease_until, created_at) "
                        + "VALUES (?, NULL, ?, ?, 'alice', ?, ?, 'PENDING', 0, 0, 0, ?)")) {
            statement.setBytes(1, uuidBytes(deliveryId));
            statement.setString(2, "wallet-test:" + deliveryId);
            statement.setBytes(3, WORLD);
            statement.setString(4, asset);
            statement.setDouble(5, quantity);
            statement.setLong(6, NOW);
            statement.executeUpdate();
        }
    }

    private static double balance(Connection connection, String asset) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT quantity FROM account_assets WHERE world = ? AND account_name = 'alice' "
                        + "AND asset_id = ?")) {
            statement.setBytes(1, WORLD);
            statement.setString(2, asset);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getDouble(1);
            }
        }
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

    private static Connection failFirstHoldingWrite(Connection delegate, AtomicInteger attempts,
                                                    SQLException firstFailure) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    Object value = invoke(delegate, method, arguments);
                    if (method.getName().equals("prepareStatement")
                            && arguments != null && arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.startsWith("INSERT INTO account_assets")) {
                        PreparedStatement statement = (PreparedStatement) value;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                                new Class<?>[]{PreparedStatement.class},
                                (statementProxy, statementMethod, statementArguments) -> {
                                    if (statementMethod.getName().equals("executeUpdate")
                                            && attempts.incrementAndGet() == 1) {
                                        throw firstFailure;
                                    }
                                    return invoke(statement, statementMethod, statementArguments);
                                });
                    }
                    return value;
                });
    }

    private static Connection failRollbackAndObserveAutoCommit(Connection delegate,
                                                               AtomicInteger unsafeRestores) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("rollback")) {
                        throw new SQLException("synthetic rollback failure", "08006", 0);
                    }
                    if (method.getName().equals("setAutoCommit") && arguments != null
                            && arguments.length == 1 && Boolean.TRUE.equals(arguments[0])) {
                        unsafeRestores.incrementAndGet();
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments)
            throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
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

    private static void apply(Connection connection, String file) throws Exception {
        try (InputStream resource = WalletAdjustmentRepositoryTest.class.getClassLoader()
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
