package com.faridfaharaj.profitable.data.settlement;

import com.faridfaharaj.profitable.data.holderClasses.Order;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeSettlementRepositoryTest {

    private static final byte[] WORLD = new byte[16];
    private static final long MARKET_TIME = 240_000L;
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void settlesByDatabaseSequenceAndCreditsEveryLegExactlyOnce() throws Exception {
        try (Connection connection = database("fifo.db")) {
            seedMarket(connection, "maker-a", "maker-b", "taker");
            holding(connection, "maker-a", "IRON_INGOT", 1);
            holding(connection, "maker-b", "IRON_INGOT", 1);
            holding(connection, "taker", "EMD", 100);

            UUID firstOrder = UUID.randomUUID();
            UUID secondOrder = UUID.randomUUID();
            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.place(connection, false,
                            request(firstOrder, "maker-a", false, 10, 1,
                                    Order.OrderType.LIMIT, "0", "2%", NOW)).status());
            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.place(connection, false,
                            request(secondOrder, "maker-b", false, 10, 1,
                                    Order.OrderType.LIMIT, "0", "2%", NOW)).status());

            TradeSettlementRepository.Result result = TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 2,
                            Order.OrderType.MARKET, "1%", "2%", NOW + 1));

            assertEquals(TradeSettlementRepository.Status.EXECUTED, result.status());
            assertEquals(List.of(firstOrder, secondOrder),
                    result.fills().stream().map(TradeSettlementRepository.Fill::makerOrderId).toList());
            assertEquals(20, result.money(), 1.0E-9);
            assertEquals(0.2, result.takerFee(), 1.0E-9);
            assertEquals(79.8, balance(connection, "taker", "EMD"), 1.0E-9);
            assertEquals(3, integer(connection, "SELECT COUNT(*) FROM delivery_outbox WHERE status='PENDING'"));
            assertEquals(2, decimal(connection, "SELECT volume FROM candles_day"), 1.0E-9);

            drain(connection);
            assertEquals(2, balance(connection, "taker", "IRON_INGOT"), 1.0E-9);
            assertEquals(9.8, balance(connection, "maker-a", "EMD"), 1.0E-9);
            assertEquals(9.8, balance(connection, "maker-b", "EMD"), 1.0E-9);
            assertEquals(3, integer(connection, "SELECT COUNT(*) FROM processed_events"));
            assertEquals("COMPLETED", string(connection, "SELECT status FROM trade_executions"));

            // A completed row is never selected again and therefore cannot credit twice.
            assertEquals(DeliveryOutboxRepository.Status.EMPTY,
                    DeliveryOutboxRepository.processOne(connection, false, "again", NOW + 10).status());
            assertEquals(2, balance(connection, "taker", "IRON_INGOT"), 1.0E-9);
        }
    }

    @Test
    void twoConcurrentTakersCannotDoubleFillOneMaker() throws Exception {
        Path file = temporaryDirectory.resolve("concurrent.db");
        try (Connection setup = database(file)) {
            seedMarket(setup, "maker", "taker-a", "taker-b");
            holding(setup, "maker", "IRON_INGOT", 1);
            holding(setup, "taker-a", "EMD", 100);
            holding(setup, "taker-b", "EMD", 100);
            TradeSettlementRepository.place(setup, false,
                    request(UUID.randomUUID(), "maker", false, 10, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW));
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<TradeSettlementRepository.Status>> futures = new ArrayList<>();
            for (String taker : List.of("taker-a", "taker-b")) {
                futures.add(executor.submit(() -> {
                    try (Connection connection = open(file)) {
                        ready.countDown();
                        start.await();
                        return TradeSettlementRepository.settle(connection, false,
                                request(UUID.randomUUID(), taker, true, Double.MAX_VALUE, 1,
                                        Order.OrderType.MARKET, "0", "0", NOW + 1)).status();
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
        } finally {
            executor.shutdownNow();
        }

        try (Connection verify = open(file)) {
            assertEquals(1, integer(verify, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(1, decimal(verify, "SELECT volume FROM candles_day"), 1.0E-9);
            assertEquals(0, integer(verify, "SELECT COUNT(*) FROM orders"));
            assertEquals(2, integer(verify, "SELECT COUNT(*) FROM delivery_outbox"));
            assertEquals(0, negativeBalances(verify));
        }
    }

    @Test
    void replayedRequestCannotChargeOrMatchAgain() throws Exception {
        try (Connection connection = database("replay.db")) {
            seedMarket(connection, "maker", "taker");
            holding(connection, "maker", "IRON_INGOT", 1);
            holding(connection, "taker", "EMD", 100);
            TradeSettlementRepository.place(connection, false,
                    request(UUID.randomUUID(), "maker", false, 10, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW));

            UUID requestId = UUID.randomUUID();
            TradeSettlementRepository.Request request = request(requestId, "taker", true,
                    Double.MAX_VALUE, 1, Order.OrderType.MARKET, "0", "0", NOW + 1);
            assertEquals(TradeSettlementRepository.Status.EXECUTED,
                    TradeSettlementRepository.settle(connection, false, request).status());
            double afterFirst = balance(connection, "taker", "EMD");

            assertEquals(TradeSettlementRepository.Status.ALREADY_PROCESSED,
                    TradeSettlementRepository.settle(connection, false, request).status());
            assertEquals(afterFirst, balance(connection, "taker", "EMD"), 0);
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(2, integer(connection, "SELECT COUNT(*) FROM delivery_outbox"));
            assertEquals(1, decimal(connection, "SELECT volume FROM candles_day"), 0);

            TradeSettlementRepository.Request collision = request(requestId, "taker", true,
                    Double.MAX_VALUE, 2, Order.OrderType.MARKET, "0", "0", NOW + 2);
            assertThrows(SQLException.class,
                    () -> TradeSettlementRepository.settle(connection, false, collision));
            assertEquals(afterFirst, balance(connection, "taker", "EMD"), 0);
        }
    }

    @Test
    void pendingOutboxSurvivesConnectionReopenAndResumesOnce() throws Exception {
        Path file = temporaryDirectory.resolve("restart.db");
        try (Connection connection = database(file)) {
            seedMarket(connection, "maker", "taker");
            holding(connection, "maker", "IRON_INGOT", 1);
            holding(connection, "taker", "EMD", 100);
            TradeSettlementRepository.place(connection, false,
                    request(UUID.randomUUID(), "maker", false, 10, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW));
            TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 1,
                            Order.OrderType.MARKET, "0", "0", NOW + 1));
            assertEquals(0, balance(connection, "taker", "IRON_INGOT"), 0);
            assertEquals(2, integer(connection, "SELECT COUNT(*) FROM delivery_outbox WHERE status='PENDING'"));
        }

        try (Connection restarted = open(file)) {
            drain(restarted);
            assertEquals(1, balance(restarted, "taker", "IRON_INGOT"), 0);
        }
        try (Connection restartedAgain = open(file)) {
            drain(restartedAgain);
            assertEquals(1, balance(restartedAgain, "taker", "IRON_INGOT"), 0);
            assertEquals(2, integer(restartedAgain, "SELECT COUNT(*) FROM processed_events"));
        }
    }

    @Test
    void expiredProcessingLeaseRecoversAfterCrashAndCreditsOnce() throws Exception {
        Path file = temporaryDirectory.resolve("claimed-crash.db");
        try (Connection connection = database(file)) {
            seedMarket(connection, "recipient");
            TradeSettlementRepository.enqueueCredit(connection, null, "crash-leg", WORLD,
                    "recipient", "EMD", 5, NOW);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE delivery_outbox SET status='PROCESSING', attempts=1, "
                            + "lease_owner='crashed-backend', lease_until=?")) {
                statement.setLong(1, NOW - 1);
                assertEquals(1, statement.executeUpdate());
            }
        }

        try (Connection restarted = open(file)) {
            DeliveryOutboxRepository.ProcessResult result = DeliveryOutboxRepository.processOne(
                    restarted, false, "replacement-backend", NOW + 1);
            assertEquals(DeliveryOutboxRepository.Status.PROCESSED, result.status());
            assertEquals(5, balance(restarted, "recipient", "EMD"), 0);
            assertEquals(2, integer(restarted, "SELECT attempts FROM delivery_outbox"));
            assertEquals("COMPLETED", string(restarted, "SELECT status FROM delivery_outbox"));
            assertEquals(1, integer(restarted, "SELECT COUNT(*) FROM processed_events"));
            assertEquals(DeliveryOutboxRepository.Status.EMPTY,
                    DeliveryOutboxRepository.processOne(
                            restarted, false, "replacement-backend", NOW + 2).status());
            assertEquals(5, balance(restarted, "recipient", "EMD"), 0);
        }
    }

    @Test
    void partialMakerFeeAndCancellationRefundUsePersistedRemainingEscrow() throws Exception {
        try (Connection connection = database("cancel.db")) {
            seedMarket(connection, "buyer", "seller");
            holding(connection, "buyer", "EMD", 100);
            holding(connection, "seller", "IRON_INGOT", 1);
            UUID buyOrder = UUID.randomUUID();
            TradeSettlementRepository.place(connection, false,
                    request(buyOrder, "buyer", true, 10, 2,
                            Order.OrderType.LIMIT, "0", "2", NOW));
            assertEquals(78, balance(connection, "buyer", "EMD"), 0);

            TradeSettlementRepository.Result fill = TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "seller", false, Double.MIN_VALUE, 1,
                            Order.OrderType.MARKET, "0", "2", NOW + 1));
            assertEquals(TradeSettlementRepository.Status.EXECUTED, fill.status());
            assertEquals(1, decimal(connection,
                    "SELECT units FROM orders WHERE order_uuid = " + blob(buyOrder)), 0);
            assertEquals(11, decimal(connection,
                    "SELECT escrow_amount FROM orders WHERE order_uuid = " + blob(buyOrder)), 0);
            assertEquals(1, decimal(connection,
                    "SELECT maker_fee_remaining FROM orders WHERE order_uuid = " + blob(buyOrder)), 0);

            TradeSettlementRepository.Cancellation cancellation = TradeSettlementRepository.cancel(
                    connection, false, WORLD, buyOrder, "buyer", "EMD", NOW + 2);
            assertTrue(cancellation.cancelled());
            assertEquals(11, cancellation.refundAmount(), 0);
            drain(connection);

            assertEquals(89, balance(connection, "buyer", "EMD"), 0);
            assertEquals(1, balance(connection, "buyer", "IRON_INGOT"), 0);
            assertEquals(10, balance(connection, "seller", "EMD"), 0);
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM orders"));
        }
    }

    @Test
    void candleSweepPreservesOpenCloseHighLowAndSingleVolumeIncrement() throws Exception {
        try (Connection connection = database("candles.db")) {
            seedMarket(connection, "maker-low", "maker-high", "taker");
            holding(connection, "maker-low", "IRON_INGOT", 1);
            holding(connection, "maker-high", "IRON_INGOT", 1);
            holding(connection, "taker", "EMD", 100);
            TradeSettlementRepository.place(connection, false,
                    request(UUID.randomUUID(), "maker-low", false, 9, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW));
            TradeSettlementRepository.place(connection, false,
                    request(UUID.randomUUID(), "maker-high", false, 11, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW + 1));

            TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", true, 11, 2,
                            Order.OrderType.LIMIT, "0", "0", NOW + 2));

            assertEquals(9, decimal(connection, "SELECT open FROM candles_day"), 0);
            assertEquals(11, decimal(connection, "SELECT close FROM candles_day"), 0);
            assertEquals(11, decimal(connection, "SELECT high FROM candles_day"), 0);
            assertEquals(9, decimal(connection, "SELECT low FROM candles_day"), 0);
            assertEquals(2, decimal(connection, "SELECT volume FROM candles_day"), 0);
            assertEquals(2, decimal(connection, "SELECT volume FROM candles_week"), 0);
            assertEquals(2, decimal(connection, "SELECT volume FROM candles_month"), 0);
        }
    }

    @Test
    void triggeredStopReceivesFreshSequenceBehindExistingLiquidity() throws Exception {
        try (Connection connection = database("stop.db")) {
            seedMarket(connection, "stop-owner", "regular-owner", "maker", "taker");
            holding(connection, "stop-owner", "EMD", 100);
            holding(connection, "regular-owner", "EMD", 100);
            holding(connection, "maker", "IRON_INGOT", 1);
            holding(connection, "taker", "EMD", 100);
            try (PreparedStatement candle = connection.prepareStatement(
                    "INSERT INTO candles_day VALUES (?, ?, 10, 10, 10, 10, 1, 'IRON_INGOT')")) {
                candle.setBytes(1, WORLD);
                candle.setLong(2, MARKET_TIME);
                candle.executeUpdate();
            }

            UUID stopId = UUID.randomUUID();
            UUID regularId = UUID.randomUUID();
            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.settle(connection, false,
                            request(stopId, "stop-owner", true, 12, 1,
                                    Order.OrderType.STOP_LIMIT, "0", "0", NOW)).status());
            TradeSettlementRepository.place(connection, false,
                    request(regularId, "regular-owner", true, 12, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW + 1));
            long oldStopSequence = longValue(connection,
                    "SELECT sequence_id FROM orders WHERE order_uuid=" + blob(stopId));
            long regularSequence = longValue(connection,
                    "SELECT sequence_id FROM orders WHERE order_uuid=" + blob(regularId));
            assertTrue(oldStopSequence < regularSequence);

            TradeSettlementRepository.place(connection, false,
                    request(UUID.randomUUID(), "maker", false, 12, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW + 2));
            TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 1,
                            Order.OrderType.MARKET, "0", "0", NOW + 3));

            long activatedSequence = longValue(connection,
                    "SELECT sequence_id FROM orders WHERE order_uuid=" + blob(stopId));
            assertTrue(activatedSequence > regularSequence);
            assertEquals(Order.OrderType.LIMIT.getValue(), integer(connection,
                    "SELECT order_type FROM orders WHERE order_uuid=" + blob(stopId)));
        }
    }

    @Test
    void failedDeliveryPersistsAttemptsAndBecomesDead() throws Exception {
        try (Connection connection = database("dead.db")) {
            seedMarket(connection, "existing");
            TradeSettlementRepository.enqueueCredit(connection, null, "bad-account", WORLD,
                    "missing-account", "EMD", 1, NOW);
            UUID deliveryId = null;
            for (int attempt = 1; attempt <= 10; attempt++) {
                DeliveryOutboxRepository.ProcessResult result = DeliveryOutboxRepository.processOne(
                        connection, false, "worker", NOW + attempt * 1_000_000L);
                assertEquals(DeliveryOutboxRepository.Status.FAILED, result.status());
                deliveryId = result.deliveryId();
                assertEquals(attempt, integer(connection, "SELECT attempts FROM delivery_outbox"));
            }
            assertEquals("DEAD", string(connection, "SELECT status FROM delivery_outbox"));
            assertEquals(1, DeliveryOutboxRepository.listDead(connection, 50).size());
            assertEquals(DeliveryOutboxRepository.Status.EMPTY,
                    DeliveryOutboxRepository.processOne(connection, false, "worker", Long.MAX_VALUE).status());

            assertTrue(DeliveryOutboxRepository.retryDead(connection, deliveryId));
            assertEquals("PENDING", string(connection, "SELECT status FROM delivery_outbox"));
            assertEquals(0, integer(connection, "SELECT attempts FROM delivery_outbox"));
            assertEquals(DeliveryOutboxRepository.Status.FAILED,
                    DeliveryOutboxRepository.processOne(connection, false, "worker", Long.MAX_VALUE).status());
            assertEquals(1, integer(connection, "SELECT attempts FROM delivery_outbox"));
            assertFalse(DeliveryOutboxRepository.retryDead(connection, deliveryId));
        }
    }

    @Test
    void insufficientFundsRollsBackMakerClaimOutboxRequestAndCandles() throws Exception {
        try (Connection connection = database("rollback.db")) {
            seedMarket(connection, "maker", "poor-taker");
            holding(connection, "maker", "IRON_INGOT", 1);
            TradeSettlementRepository.place(connection, false,
                    request(UUID.randomUUID(), "maker", false, 10, 1,
                            Order.OrderType.LIMIT, "0", "0", NOW));

            TradeSettlementRepository.Result result = TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "poor-taker", true, Double.MAX_VALUE, 1,
                            Order.OrderType.MARKET, "0", "0", NOW + 1));
            assertEquals(TradeSettlementRepository.Status.INSUFFICIENT_FUNDS, result.status());
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM orders"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM delivery_outbox"));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM exchange_requests"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM candles_day"));
            assertEquals(0, negativeBalances(connection));
        }
    }

    @Test
    void rejectsCorruptFractionalPhysicalMakerBeforeAnyMutation() throws Exception {
        try (Connection connection = database("fractional-maker.db")) {
            seedMarket(connection, "maker", "taker");
            holding(connection, "taker", "EMD", 100);
            UUID makerOrder = UUID.randomUUID();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO orders "
                        + "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                        + "maker_fee_remaining, order_type, created_at) VALUES (zeroblob(16), "
                        + blob(makerOrder) + ", 'maker', 'IRON_INGOT', 0, 10, 1.5, 1.5, 0, 0, " + NOW + ")");
            }

            assertThrows(SQLException.class, () -> TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 1,
                            Order.OrderType.MARKET, "0", "0", NOW + 1)));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM orders"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM delivery_outbox"));
            assertEquals(100, balance(connection, "taker", "EMD"));
        }
    }

    @Test
    void rejectsMakerWhoseEscrowCannotFundAdvertisedFill() throws Exception {
        try (Connection connection = database("underfunded-maker.db")) {
            seedMarket(connection, "maker", "taker");
            holding(connection, "taker", "EMD", 100);
            UUID makerOrder = UUID.randomUUID();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO orders "
                        + "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                        + "maker_fee_remaining, order_type, created_at) VALUES (zeroblob(16), "
                        + blob(makerOrder) + ", 'maker', 'IRON_INGOT', 0, 10, 1, 0.5, 0, 0, " + NOW + ")");
            }

            assertThrows(SQLException.class, () -> TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 1,
                            Order.OrderType.MARKET, "0", "0", NOW + 1)));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM orders"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM delivery_outbox"));
            assertEquals(100, balance(connection, "taker", "EMD"));
        }
    }

    @Test
    void rejectsMaterialEscrowDeficitAtLargeNotionalValues() throws Exception {
        try (Connection connection = database("large-underfunded-maker.db")) {
            seedMarket(connection, "maker", "taker");
            holding(connection, "taker", "IRON_INGOT", 1);
            UUID makerOrder = UUID.randomUUID();
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO orders "
                        + "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                        + "maker_fee_remaining, order_type, created_at) VALUES (zeroblob(16), "
                        + blob(makerOrder) + ", 'maker', 'IRON_INGOT', 1, 1000000000000, 1, "
                        + "999999999999, 0, 0, " + NOW + ")");
            }

            assertThrows(SQLException.class, () -> TradeSettlementRepository.settle(connection, false,
                    request(UUID.randomUUID(), "taker", false, Double.MIN_VALUE, 1,
                            Order.OrderType.MARKET, "0", "0", NOW + 1)));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM orders"));
            assertEquals(1, balance(connection, "taker", "IRON_INGOT"), 0);
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM trade_executions"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM delivery_outbox"));
        }
    }

    @Test
    void subEpsilonMakerRemainderIsDurablyRefundedWithoutBurningEscrow() throws Exception {
        try (Connection connection = database("maker-dust-refund.db")) {
            seedMarket(connection, "maker", "taker");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO assets(world, asset_id, asset_type, meta) "
                        + "VALUES (zeroblob(16), 'USD', 1, zeroblob(1))");
            }
            holding(connection, "maker", "EMD", 1_000_000_000_000.0);
            holding(connection, "taker", "USD", 0.9999999995);

            TradeSettlementRepository.Request maker = new TradeSettlementRepository.Request(
                    WORLD, UUID.randomUUID(), "maker", "USD", "EMD", true,
                    1_000_000_000_000.0, 1, Order.OrderType.LIMIT, 1,
                    "0", "0", MARKET_TIME, NOW);
            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.place(connection, false, maker).status());

            TradeSettlementRepository.Request taker = new TradeSettlementRepository.Request(
                    WORLD, UUID.randomUUID(), "taker", "USD", "EMD", false,
                    Double.MIN_VALUE, 0.9999999995, Order.OrderType.MARKET, 1,
                    "0", "0", MARKET_TIME, NOW + 1);
            TradeSettlementRepository.Result result =
                    TradeSettlementRepository.settle(connection, false, taker);

            assertEquals(TradeSettlementRepository.Status.EXECUTED, result.status());
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM orders"));
            assertEquals(3, integer(connection, "SELECT COUNT(*) FROM delivery_outbox"));
            drain(connection);

            double makerRefund = balance(connection, "maker", "EMD");
            double takerProceeds = balance(connection, "taker", "EMD");
            assertEquals(1_000_000_000_000.0 - result.money(), makerRefund, 0.001);
            assertEquals(result.money(), takerProceeds, 0.001);
            assertEquals(1_000_000_000_000.0, makerRefund + takerProceeds, 0.001);
            assertEquals(0.9999999995, balance(connection, "maker", "USD"), 1.0E-15);
            assertEquals(0, balance(connection, "taker", "USD"), 1.0E-15);
            assertEquals(3, integer(connection, "SELECT COUNT(*) FROM processed_events"));
        }
    }

    @Test
    void rejectsTradingMainCurrencyAgainstItself() throws Exception {
        try (Connection connection = database("self-market.db")) {
            seedMarket(connection, "taker");
            TradeSettlementRepository.Request selfMarket = new TradeSettlementRepository.Request(
                    WORLD, UUID.randomUUID(), "taker", "EMD", "EMD", true, 1, 1,
                    Order.OrderType.LIMIT, 1, "0", "0", MARKET_TIME, NOW);

            assertThrows(IllegalArgumentException.class,
                    () -> TradeSettlementRepository.settle(connection, false, selfMarket));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM orders"));
            assertEquals(0, integer(connection, "SELECT COUNT(*) FROM exchange_requests"));
        }
    }

    @Test
    void clampsBackwardBackendTicksForCandlesCurrentCloseAndStopActivation() throws Exception {
        try (Connection connection = database("market-time-watermark.db")) {
            seedMarket(connection, "maker", "taker", "stopper");
            holding(connection, "maker", "IRON_INGOT", 2);
            holding(connection, "taker", "EMD", 100);
            holding(connection, "stopper", "EMD", 100);

            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.place(connection, false,
                            timedRequest(UUID.randomUUID(), "maker", false, 10, 1,
                                    Order.OrderType.LIMIT, 800_000, NOW)).status());
            assertEquals(TradeSettlementRepository.Status.EXECUTED,
                    TradeSettlementRepository.settle(connection, false,
                            timedRequest(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 1,
                                    Order.OrderType.MARKET, 800_000, NOW + 1)).status());

            UUID stopOrderId = UUID.randomUUID();
            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.settle(connection, false,
                            timedRequest(stopOrderId, "stopper", true, 15, 1,
                                    Order.OrderType.STOP_LIMIT, 100_000, NOW + 2)).status());
            long originalStopSequence = longValue(connection,
                    "SELECT sequence_id FROM orders WHERE order_uuid = " + blob(stopOrderId));

            assertEquals(TradeSettlementRepository.Status.PLACED,
                    TradeSettlementRepository.place(connection, false,
                            timedRequest(UUID.randomUUID(), "maker", false, 20, 1,
                                    Order.OrderType.LIMIT, 100_000, NOW + 3)).status());
            assertEquals(TradeSettlementRepository.Status.EXECUTED,
                    TradeSettlementRepository.settle(connection, false,
                            timedRequest(UUID.randomUUID(), "taker", true, Double.MAX_VALUE, 1,
                                    Order.OrderType.MARKET, 100_000, NOW + 4)).status());

            assertEquals(800_000, longValue(connection,
                    "SELECT last_market_time FROM market_locks WHERE asset_id = 'IRON_INGOT'"));
            assertEquals(1, integer(connection, "SELECT COUNT(*) FROM candles_day"));
            assertEquals(792_000, longValue(connection, "SELECT time FROM candles_day"));
            assertEquals(10, decimal(connection, "SELECT open FROM candles_day"), 0);
            assertEquals(20, decimal(connection, "SELECT close FROM candles_day"), 0);
            assertEquals(20, decimal(connection, "SELECT high FROM candles_day"), 0);
            assertEquals(10, decimal(connection, "SELECT low FROM candles_day"), 0);
            assertEquals(2, decimal(connection, "SELECT volume FROM candles_day"), 0);

            assertEquals(Order.OrderType.LIMIT.getValue(), integer(connection,
                    "SELECT order_type FROM orders WHERE order_uuid = " + blob(stopOrderId)));
            assertTrue(longValue(connection,
                    "SELECT sequence_id FROM orders WHERE order_uuid = " + blob(stopOrderId))
                    > originalStopSequence);
        }
    }

    private Connection database(String file) throws Exception {
        return database(temporaryDirectory.resolve(file));
    }

    private Connection database(Path file) throws Exception {
        Connection connection = open(file);
        apply(connection, "V1__tables.sql");
        apply(connection, "V2__indexes.sql");
        apply(connection, "V3__coordination.sql");
        apply(connection, "V4__order_fifo_and_constraints.sql");
        apply(connection, "V5__durable_settlement_and_sequence.sql");
        apply(connection, "V6__audited_market_adjustments.sql");
        apply(connection, "V7__market_time_and_wallet_adjustments.sql");
        return connection;
    }

    private static Connection open(Path file) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 10000");
            statement.execute("PRAGMA journal_mode = WAL");
        }
        return connection;
    }

    private static void seedMarket(Connection connection, String... accounts) throws SQLException {
        try (PreparedStatement asset = connection.prepareStatement(
                "INSERT INTO assets(world, asset_id, asset_type, meta) VALUES (?, ?, ?, zeroblob(1))")) {
            for (Object[] row : List.of(new Object[]{"EMD", 1}, new Object[]{"IRON_INGOT", 2})) {
                asset.setBytes(1, WORLD);
                asset.setString(2, (String) row[0]);
                asset.setInt(3, (Integer) row[1]);
                asset.addBatch();
            }
            asset.executeBatch();
        }
        try (PreparedStatement account = connection.prepareStatement(
                "INSERT INTO accounts(world, account_name, password, salt, item_delivery_pos, "
                        + "entity_delivery_pos, entity_claim_id) VALUES (?, ?, NULL, NULL, NULL, NULL, ?)")) {
            int claimId = 1;
            for (String name : accounts) {
                account.setBytes(1, WORLD);
                account.setString(2, name);
                account.setInt(3, claimId++);
                account.addBatch();
            }
            account.executeBatch();
        }
    }

    private static void holding(Connection connection, String account, String asset, double quantity)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO account_assets(world, account_name, asset_id, quantity) VALUES (?, ?, ?, ?)")) {
            statement.setBytes(1, WORLD);
            statement.setString(2, account);
            statement.setString(3, asset);
            statement.setDouble(4, quantity);
            statement.executeUpdate();
        }
    }

    private static TradeSettlementRepository.Request request(
            UUID id, String account, boolean sideBuy, double price, double units,
            Order.OrderType type, String takerFee, String makerFee, long now) {
        return new TradeSettlementRepository.Request(WORLD, id, account, "IRON_INGOT", "EMD",
                sideBuy, price, units, type, 2, takerFee, makerFee, MARKET_TIME, now);
    }

    private static TradeSettlementRepository.Request timedRequest(
            UUID id, String account, boolean sideBuy, double price, double units,
            Order.OrderType type, long marketTime, long now) {
        return new TradeSettlementRepository.Request(WORLD, id, account, "IRON_INGOT", "EMD",
                sideBuy, price, units, type, 2, "0", "0", marketTime, now);
    }

    private static void drain(Connection connection) throws SQLException {
        for (int count = 0; count < 100; count++) {
            DeliveryOutboxRepository.ProcessResult result = DeliveryOutboxRepository.processOne(
                    connection, false, "test", NOW + 100_000L + count);
            if (result.status() == DeliveryOutboxRepository.Status.EMPTY) {
                return;
            }
            assertNotEquals(DeliveryOutboxRepository.Status.FAILED, result.status(), result.error());
        }
        throw new AssertionError("Outbox did not drain within the bounded test batch");
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

    private static int negativeBalances(Connection connection) throws SQLException {
        return integer(connection, "SELECT COUNT(*) FROM account_assets WHERE quantity < 0");
    }

    private static int integer(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static long longValue(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static double decimal(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getDouble(1);
        }
    }

    private static String string(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static String blob(UUID id) {
        byte[] bytes = ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
        StringBuilder hex = new StringBuilder("X'");
        for (byte value : bytes) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.append('\'').toString();
    }

    private static void apply(Connection connection, String file) throws Exception {
        String path = "db/migration/sqlite/" + file;
        try (InputStream resource = TradeSettlementRepositoryTest.class.getClassLoader()
                .getResourceAsStream(path)) {
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
            } catch (Exception error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }
}
