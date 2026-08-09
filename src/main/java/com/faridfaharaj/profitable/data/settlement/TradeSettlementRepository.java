package com.faridfaharaj.profitable.data.settlement;

import com.faridfaharaj.profitable.data.holderClasses.Order;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Database-only matching and settlement core.
 *
 * <p>The market row, maker orders, taker escrow debit, trade audit rows,
 * candles and durable credit legs are committed in one transaction. No Bukkit
 * or external economy API is called from this class, which is the boundary that
 * makes a committed fill recoverable after a process crash.</p>
 */
public final class TradeSettlementRepository {

    private static final double EPSILON = 1.0E-9;
    private static final String[] CANDLE_TABLES = {"day", "week", "month"};
    private static final long[] CANDLE_INTERVALS = {24_000L, 168_000L, 720_000L};

    private TradeSettlementRepository() {
    }

    public enum Status {
        EXECUTED,
        PARTIAL,
        PLACED,
        ALREADY_PROCESSED,
        NO_LIQUIDITY,
        INSUFFICIENT_FUNDS,
        INVALID_FEE,
        INVALID_STOP_TRIGGER
    }

    public record Request(
            byte[] worldId,
            UUID orderId,
            String takerAccount,
            String assetId,
            String mainCurrencyId,
            boolean sideBuy,
            double price,
            double units,
            Order.OrderType orderType,
            int assetType,
            String takerFee,
            String makerFee,
            long marketTime,
            long nowMillis
    ) {
        public Request {
            worldId = worldId == null ? null : Arrays.copyOf(worldId, worldId.length);
        }

        @Override
        public byte[] worldId() {
            return worldId == null ? null : Arrays.copyOf(worldId, worldId.length);
        }
    }

    public record Fill(
            UUID fillId,
            UUID makerOrderId,
            long makerOrderSequence,
            String makerAccount,
            boolean makerSideBuy,
            double price,
            double units,
            double makerFee
    ) {
    }

    public record Result(
            Status status,
            UUID executionId,
            UUID restingOrderId,
            double executedUnits,
            double money,
            double takerFee,
            double executionPrice,
            List<Fill> fills
    ) {
        public Result {
            fills = List.copyOf(fills);
        }

        public boolean executed() {
            return status == Status.EXECUTED || status == Status.PARTIAL;
        }
    }

    public record Cancellation(
            boolean cancelled,
            UUID orderId,
            String owner,
            String assetId,
            boolean sideBuy,
            double price,
            double units,
            String refundAssetId,
            double refundAmount
    ) {
        public static Cancellation missing(UUID orderId) {
            return new Cancellation(false, orderId, null, null, false, 0, 0, null, 0);
        }
    }

    private record Candidate(
            UUID orderId,
            long sequence,
            String owner,
            boolean sideBuy,
            double price,
            double units,
            double escrowAmount,
            double makerFeeRemaining
    ) {
    }

    private record PlannedFill(Candidate maker, double units, double makerFee, UUID fillId) {
    }

    private record TriggeredOrder(UUID orderId, String owner, boolean sideBuy, double price,
                                  double units, double escrowAmount, double makerFeeRemaining) {
    }

    public static Result settle(Connection connection, boolean mysql, Request request) throws SQLException {
        validate(request);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Map<String, Integer> assetTypes = lockAssets(connection, mysql, request.worldId(),
                    List.of(request.assetId(), request.mainCurrencyId()));
            int authoritativeAssetType = authoritativeAssetType(request, assetTypes);
            long lastMarketTime = lockMarket(connection, mysql, request.worldId(), request.assetId());
            if (requestAlreadyRecorded(connection, request)) {
                connection.rollback();
                return empty(Status.ALREADY_PROCESSED, request.orderId());
            }
            long effectiveMarketTime = Math.max(request.marketTime(), lastMarketTime);
            double previousClose = readCurrentClose(connection, request.worldId(), request.assetId(),
                    effectiveMarketTime);

            if (request.orderType() == Order.OrderType.STOP_LIMIT) {
                lockAccounts(connection, mysql, request.worldId(), List.of(request.takerAccount()));
                if (previousClose <= 0
                        || (request.sideBuy() ? previousClose >= request.price() : previousClose <= request.price())) {
                    connection.rollback();
                    return empty(Status.INVALID_STOP_TRIGGER, request.orderId());
                }
                double makerFee = fee(request.makerFee(), request.price() * request.units());
                double escrow = restingEscrow(request.sideBuy(), request.price(), request.units(), makerFee);
                if (!validRestingFee(request.sideBuy(), request.price(), request.units(), request.makerFee())) {
                    connection.rollback();
                    return empty(Status.INVALID_FEE, request.orderId());
                }
                if (!debitHolding(connection, request.worldId(), request.takerAccount(),
                        request.sideBuy() ? request.mainCurrencyId() : request.assetId(), escrow)) {
                    connection.rollback();
                    return empty(Status.INSUFFICIENT_FUNDS, request.orderId());
                }
                insertOrder(connection, request, request.orderId(), request.units(), escrow, makerFee,
                        request.orderType());
                recordRequest(connection, request, "PLACED", null, request.orderId());
                connection.commit();
                return empty(Status.PLACED, request.orderId());
            }

            List<Candidate> candidates = selectAndLockCandidates(
                    connection, mysql, request, authoritativeAssetType);
            List<String> settlementAccounts = new ArrayList<>();
            settlementAccounts.add(request.takerAccount());
            candidates.forEach(candidate -> settlementAccounts.add(candidate.owner()));
            lockAccounts(connection, mysql, request.worldId(), settlementAccounts);
            List<PlannedFill> planned = planFills(candidates, request.units());
            double executedUnits = planned.stream().mapToDouble(PlannedFill::units).sum();
            if (!Double.isFinite(executedUnits)) {
                connection.rollback();
                return empty(Status.INVALID_FEE, null);
            }

            if (executedUnits <= EPSILON) {
                if (request.orderType() == Order.OrderType.MARKET) {
                    connection.rollback();
                    return empty(Status.NO_LIQUIDITY, null);
                }
                double makerFee = fee(request.makerFee(), request.price() * request.units());
                double escrow = restingEscrow(request.sideBuy(), request.price(), request.units(), makerFee);
                if (!validRestingFee(request.sideBuy(), request.price(), request.units(), request.makerFee())) {
                    connection.rollback();
                    return empty(Status.INVALID_FEE, request.orderId());
                }
                if (!debitHolding(connection, request.worldId(), request.takerAccount(),
                        request.sideBuy() ? request.mainCurrencyId() : request.assetId(), escrow)) {
                    connection.rollback();
                    return empty(Status.INSUFFICIENT_FUNDS, request.orderId());
                }
                insertOrder(connection, request, request.orderId(), request.units(), escrow, makerFee,
                        Order.OrderType.LIMIT);
                recordRequest(connection, request, "PLACED", null, request.orderId());
                connection.commit();
                return empty(Status.PLACED, request.orderId());
            }

            double money = planned.stream().mapToDouble(fill -> fill.units() * fill.maker().price()).sum();
            if (!Double.isFinite(money) || money <= EPSILON) {
                connection.rollback();
                return empty(Status.INVALID_FEE, null);
            }
            double takerFee = fee(request.takerFee(), money);
            if (!request.sideBuy() && takerFee + EPSILON >= money) {
                connection.rollback();
                return empty(Status.INVALID_FEE, null);
            }
            for (PlannedFill fill : planned) {
                if (!fill.maker().sideBuy()
                        && fill.makerFee() + EPSILON >= fill.units() * fill.maker().price()) {
                    connection.rollback();
                    return empty(Status.INVALID_FEE, null);
                }
            }

            double remainder = Math.max(0, request.units() - executedUnits);
            boolean placeRemainder = request.orderType() == Order.OrderType.LIMIT && remainder > EPSILON;
            double remainderEscrow = 0;
            double remainderMakerFee = 0;
            if (placeRemainder) {
                if (!validRestingFee(request.sideBuy(), request.price(), remainder, request.makerFee())) {
                    connection.rollback();
                    return empty(Status.INVALID_FEE, null);
                }
                remainderMakerFee = fee(request.makerFee(), request.price() * remainder);
                remainderEscrow = restingEscrow(request.sideBuy(), request.price(), remainder, remainderMakerFee);
            }

            double fillDebit = request.sideBuy() ? money + takerFee : executedUnits;
            double totalDebit = fillDebit + remainderEscrow;
            if (!Double.isFinite(totalDebit) || totalDebit <= EPSILON) {
                connection.rollback();
                return empty(Status.INVALID_FEE, null);
            }
            String debitAsset = request.sideBuy() ? request.mainCurrencyId() : request.assetId();
            if (!debitHolding(connection, request.worldId(), request.takerAccount(), debitAsset, totalDebit)) {
                connection.rollback();
                return empty(Status.INSUFFICIENT_FUNDS, null);
            }

            UUID executionId = UUID.randomUUID();
            double executionPrice = planned.getLast().maker().price();
            insertExecution(connection, request, executionId, executedUnits, money, takerFee, executionPrice);

            List<Fill> fills = new ArrayList<>(planned.size());
            for (PlannedFill fill : planned) {
                consumeMakerOrder(connection, request, executionId, fill);
                insertFill(connection, executionId, fill);

                String makerAsset = fill.maker().sideBuy() ? request.assetId() : request.mainCurrencyId();
                double makerQuantity = fill.maker().sideBuy()
                        ? fill.units()
                        : fill.units() * fill.maker().price() - fill.makerFee();
                if (!Double.isFinite(makerQuantity) || makerQuantity <= EPSILON) {
                    connection.rollback();
                    return empty(Status.INVALID_FEE, null);
                }
                enqueueCredit(connection, executionId,
                        "execution:" + executionId + ":maker:" + fill.fillId(),
                        request.worldId(), fill.maker().owner(), makerAsset, makerQuantity, request.nowMillis());
                fills.add(new Fill(fill.fillId(), fill.maker().orderId(), fill.maker().sequence(),
                        fill.maker().owner(), fill.maker().sideBuy(), fill.maker().price(),
                        fill.units(), fill.makerFee()));
            }

            String takerAsset = request.sideBuy() ? request.assetId() : request.mainCurrencyId();
            double takerQuantity = request.sideBuy() ? executedUnits : money - takerFee;
            if (!Double.isFinite(takerQuantity) || takerQuantity <= EPSILON) {
                connection.rollback();
                return empty(Status.INVALID_FEE, null);
            }
            enqueueCredit(connection, executionId, "execution:" + executionId + ":taker",
                    request.worldId(), request.takerAccount(), takerAsset, takerQuantity, request.nowMillis());

            UUID restingOrderId = null;
            if (placeRemainder) {
                restingOrderId = request.orderId();
                insertOrder(connection, request, restingOrderId, remainder, remainderEscrow,
                        remainderMakerFee, Order.OrderType.LIMIT);
            }

            double openPrice = planned.getFirst().maker().price();
            double highPrice = planned.stream().mapToDouble(fill -> fill.maker().price()).max().orElse(executionPrice);
            double lowPrice = planned.stream().mapToDouble(fill -> fill.maker().price()).min().orElse(executionPrice);
            updateCandles(connection, mysql, request.worldId(), request.assetId(), openPrice,
                    executionPrice, highPrice, lowPrice, executedUnits, effectiveMarketTime);
            advanceMarketTime(connection, request.worldId(), request.assetId(), effectiveMarketTime);
            updateStopOrders(connection, request.worldId(), request.assetId(),
                    previousClose, executionPrice, request.nowMillis());
            recordRequest(connection, request, "SETTLED", executionId, restingOrderId);

            connection.commit();
            Status status = remainder > 0 ? Status.PARTIAL : Status.EXECUTED;
            return new Result(status, executionId, restingOrderId, executedUnits, money,
                    takerFee, executionPrice, fills);
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    public static Cancellation cancel(Connection connection, boolean mysql, byte[] worldId, UUID orderId,
                                      String expectedOwner, String mainCurrencyId, long nowMillis)
            throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String lookupSql = "SELECT owner, asset_id FROM orders WHERE world = ? AND order_uuid = ?";
            String hintedOwner;
            String hintedAsset;
            try (PreparedStatement lookup = connection.prepareStatement(lookupSql)) {
                lookup.setBytes(1, worldId);
                lookup.setBytes(2, uuidBytes(orderId));
                try (ResultSet result = lookup.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return Cancellation.missing(orderId);
                    }
                    hintedOwner = result.getString("owner");
                    hintedAsset = result.getString("asset_id");
                }
            }
            if (expectedOwner != null && !expectedOwner.equals(hintedOwner)) {
                connection.rollback();
                return Cancellation.missing(orderId);
            }

            lockAssets(connection, mysql, worldId, List.of(hintedAsset, mainCurrencyId));
            lockMarket(connection, mysql, worldId, hintedAsset);
            String suffix = mysql ? " FOR UPDATE" : "";
            String sql = "SELECT owner, asset_id, sideBuy, price, units, escrow_amount "
                    + "FROM orders WHERE world = ? AND order_uuid = ?" + suffix;
            String owner;
            String assetId;
            boolean sideBuy;
            double price;
            double units;
            double escrow;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, worldId);
                statement.setBytes(2, uuidBytes(orderId));
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return Cancellation.missing(orderId);
                    }
                    owner = result.getString("owner");
                    assetId = result.getString("asset_id");
                    sideBuy = result.getBoolean("sideBuy");
                    price = result.getDouble("price");
                    units = result.getDouble("units");
                    escrow = result.getDouble("escrow_amount");
                }
            }
            if (expectedOwner != null && !expectedOwner.equals(owner)) {
                connection.rollback();
                return Cancellation.missing(orderId);
            }
            if (!hintedAsset.equals(assetId) || !hintedOwner.equals(owner)) {
                throw new SQLException("Order identity changed while cancellation was acquiring locks");
            }
            lockAccounts(connection, mysql, worldId, List.of(owner));
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM orders WHERE world = ? AND order_uuid = ?")) {
                delete.setBytes(1, worldId);
                delete.setBytes(2, uuidBytes(orderId));
                if (delete.executeUpdate() != 1) {
                    connection.rollback();
                    return Cancellation.missing(orderId);
                }
            }
            String refundAsset = sideBuy ? mainCurrencyId : assetId;
            enqueueCredit(connection, null, "cancel:" + hex(worldId) + ":" + orderId,
                    worldId, owner, refundAsset, escrow, nowMillis);
            connection.commit();
            return new Cancellation(true, orderId, owner, assetId, sideBuy, price, units, refundAsset, escrow);
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    /**
     * Places a non-marketable maker order while debiting its wallet collateral in the same transaction.
     * This is used by administrative liquidity commands that intentionally must not cross the spread.
     */
    public static Result place(Connection connection, boolean mysql, Request request) throws SQLException {
        validate(request);
        if (request.orderType() != Order.OrderType.LIMIT
                && request.orderType() != Order.OrderType.STOP_LIMIT) {
            throw new IllegalArgumentException("Only limit orders can be placed without matching");
        }
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            Map<String, Integer> assetTypes = lockAssets(connection, mysql, request.worldId(),
                    List.of(request.assetId(), request.mainCurrencyId()));
            authoritativeAssetType(request, assetTypes);
            lockMarket(connection, mysql, request.worldId(), request.assetId());
            if (requestAlreadyRecorded(connection, request)) {
                connection.rollback();
                return empty(Status.ALREADY_PROCESSED, request.orderId());
            }
            lockAccounts(connection, mysql, request.worldId(), List.of(request.takerAccount()));
            double makerFee = fee(request.makerFee(), request.price() * request.units());
            if (!validRestingFee(request.sideBuy(), request.price(), request.units(), request.makerFee())) {
                connection.rollback();
                return empty(Status.INVALID_FEE, request.orderId());
            }
            double escrow = restingEscrow(request.sideBuy(), request.price(), request.units(), makerFee);
            String collateral = request.sideBuy() ? request.mainCurrencyId() : request.assetId();
            if (!debitHolding(connection, request.worldId(), request.takerAccount(), collateral, escrow)) {
                connection.rollback();
                return empty(Status.INSUFFICIENT_FUNDS, request.orderId());
            }
            insertOrder(connection, request, request.orderId(), request.units(), escrow, makerFee,
                    request.orderType());
            recordRequest(connection, request, "PLACED", null, request.orderId());
            connection.commit();
            return empty(Status.PLACED, request.orderId());
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static boolean requestAlreadyRecorded(Connection connection, Request request) throws SQLException {
        String sql = "SELECT world, account_name, asset_id, side_buy, requested_price, requested_units, "
                + "order_type FROM exchange_requests WHERE request_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(request.orderId()));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                boolean same = Arrays.equals(request.worldId(), result.getBytes("world"))
                        && request.takerAccount().equals(result.getString("account_name"))
                        && request.assetId().equals(result.getString("asset_id"))
                        && request.sideBuy() == result.getBoolean("side_buy")
                        && Double.compare(request.price(), result.getDouble("requested_price")) == 0
                        && Double.compare(request.units(), result.getDouble("requested_units")) == 0
                        && request.orderType().getValue() == result.getInt("order_type");
                if (!same) {
                    throw new SQLException("Settlement request UUID was reused with different order data");
                }
                return true;
            }
        }
    }

    private static void recordRequest(Connection connection, Request request, String status,
                                      UUID executionId, UUID restingOrderId) throws SQLException {
        String sql = "INSERT INTO exchange_requests "
                + "(request_id, world, account_name, asset_id, side_buy, requested_price, requested_units, "
                + "order_type, status, execution_id, resting_order_id, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(request.orderId()));
            statement.setBytes(2, request.worldId());
            statement.setString(3, request.takerAccount());
            statement.setString(4, request.assetId());
            statement.setBoolean(5, request.sideBuy());
            statement.setDouble(6, request.price());
            statement.setDouble(7, request.units());
            statement.setInt(8, request.orderType().getValue());
            statement.setString(9, status);
            setUuid(statement, 10, executionId);
            setUuid(statement, 11, restingOrderId);
            statement.setLong(12, request.nowMillis());
            statement.executeUpdate();
        }
    }

    private static double readCurrentClose(Connection connection, byte[] worldId, String assetId,
                                           long marketTime) throws SQLException {
        String sql = "SELECT close FROM candles_day WHERE world = ? AND asset_id = ? AND time <= ? "
                + "ORDER BY time DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, assetId);
            statement.setLong(3, marketTime);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return 0;
                }
                double close = result.getDouble(1);
                return Double.isFinite(close) && close > 0 ? close : 0;
            }
        }
    }

    private static void validate(Request request) {
        if (request == null || request.worldId() == null || request.worldId().length != 16
                || request.orderId() == null || blank(request.takerAccount()) || blank(request.assetId())
                || blank(request.mainCurrencyId()) || request.orderType() == null
                || request.assetId().equals(request.mainCurrencyId())
                || !Double.isFinite(request.price()) || request.price() <= 0
                || !Double.isFinite(request.units()) || request.units() <= 0
                || request.assetType() < 1 || request.assetType() > 3
                || request.marketTime() < 0 || request.nowMillis() <= 0
                || ((request.assetType() == 2 || request.assetType() == 3)
                && (request.units() > Integer.MAX_VALUE || request.units() != Math.rint(request.units())))) {
            throw new IllegalArgumentException("Invalid settlement request");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Result empty(Status status, UUID restingOrderId) {
        return new Result(status, null, restingOrderId, 0, 0, 0, 0, List.of());
    }

    private static long lockMarket(Connection connection, boolean mysql, byte[] worldId, String assetId)
            throws SQLException {
        if (mysql) {
            String upsert = "INSERT INTO market_locks(world, asset_id, revision, last_market_time) "
                    + "VALUES (?, ?, 1, 0) "
                    + "ON DUPLICATE KEY UPDATE revision = revision + 1";
            try (PreparedStatement statement = connection.prepareStatement(upsert)) {
                statement.setBytes(1, worldId);
                statement.setString(2, assetId);
                if (statement.executeUpdate() < 1) {
                    throw new SQLException("Could not lock market " + assetId);
                }
            }
        } else {
            String insert = "INSERT OR IGNORE INTO market_locks"
                    + "(world, asset_id, revision, last_market_time) VALUES (?, ?, 0, 0)";
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
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

    /**
     * Locks parent asset rows before the market row to avoid FK lock-conversion
     * deadlocks. MySQL/MariaDB only needs shared parent locks: rename/delete
     * still waits, while unrelated markets can settle concurrently instead of
     * serializing globally on the main-currency asset row.
     */
    private static Map<String, Integer> lockAssets(Connection connection, boolean mysql, byte[] worldId,
                                                   List<String> assets) throws SQLException {
        Map<String, Integer> types = new LinkedHashMap<>();
        for (String asset : new java.util.TreeSet<>(assets)) {
            String sql = mysql
                    ? "SELECT asset_id, asset_type FROM assets WHERE world = ? AND asset_id = ? LOCK IN SHARE MODE"
                    : "UPDATE assets SET asset_id = asset_id WHERE world = ? AND asset_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, worldId);
                statement.setString(2, asset);
                if (mysql) {
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Settlement asset no longer exists: " + asset);
                        }
                        types.put(asset, result.getInt("asset_type"));
                    }
                } else if (statement.executeUpdate() != 1) {
                    throw new SQLException("Settlement asset no longer exists: " + asset);
                }
            }
            if (!mysql) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT asset_type FROM assets WHERE world = ? AND asset_id = ?")) {
                    statement.setBytes(1, worldId);
                    statement.setString(2, asset);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Settlement asset no longer exists: " + asset);
                        }
                        types.put(asset, result.getInt("asset_type"));
                    }
                }
            }
        }
        return Map.copyOf(types);
    }

    private static int authoritativeAssetType(Request request, Map<String, Integer> assetTypes)
            throws SQLException {
        Integer tradedType = assetTypes.get(request.assetId());
        Integer currencyType = assetTypes.get(request.mainCurrencyId());
        if (tradedType == null || tradedType < 1 || tradedType > 3 || currencyType == null
                || currencyType != 1 || tradedType != request.assetType()) {
            throw new SQLException("Settlement asset metadata changed while the request was being prepared");
        }
        if ((tradedType == 2 || tradedType == 3) && !physicalQuantity(request.units())) {
            throw new SQLException("Physical settlement request is not an integral bounded quantity");
        }
        return tradedType;
    }

    /** Locks all settlement accounts in lexical order before any wallet mutation. */
    private static void lockAccounts(Connection connection, boolean mysql, byte[] worldId,
                                     List<String> accounts) throws SQLException {
        for (String account : new java.util.TreeSet<>(accounts)) {
            String sql = mysql
                    ? "SELECT account_name FROM accounts WHERE world = ? AND account_name = ? FOR UPDATE"
                    : "UPDATE accounts SET account_name = account_name WHERE world = ? AND account_name = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, worldId);
                statement.setString(2, account);
                if (mysql) {
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("Settlement account no longer exists: " + account);
                        }
                    }
                } else if (statement.executeUpdate() != 1) {
                    throw new SQLException("Settlement account no longer exists: " + account);
                }
            }
        }
    }

    private static List<Candidate> selectAndLockCandidates(Connection connection, boolean mysql, Request request,
                                                           int authoritativeAssetType)
            throws SQLException {
        String comparison = request.sideBuy() ? "<=" : ">=";
        String direction = request.sideBuy() ? "ASC" : "DESC";
        String sql = "SELECT order_uuid, sequence_id, owner, sideBuy, price, units, escrow_amount, "
                + "maker_fee_remaining "
                + "FROM orders WHERE world = ? AND asset_id = ? AND sideBuy = ? AND owner <> ? "
                + "AND order_type = ? AND price " + comparison + " ? "
                + "ORDER BY price " + direction + ", sequence_id ASC"
                + (mysql ? " FOR UPDATE" : "");
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, request.worldId());
            statement.setString(2, request.assetId());
            statement.setBoolean(3, !request.sideBuy());
            statement.setString(4, request.takerAccount());
            statement.setInt(5, Order.OrderType.LIMIT.getValue());
            statement.setDouble(6, request.price());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Candidate candidate = new Candidate(uuid(result.getBytes("order_uuid")),
                            result.getLong("sequence_id"), result.getString("owner"),
                            result.getBoolean("sideBuy"), result.getDouble("price"),
                            result.getDouble("units"), result.getDouble("escrow_amount"),
                            result.getDouble("maker_fee_remaining"));
                    double makerMoney = candidate.price() * candidate.units();
                    double expectedEscrow = candidate.sideBuy()
                            ? makerMoney + candidate.makerFeeRemaining()
                            : candidate.units();
                    if (candidate.sequence() <= 0 || blank(candidate.owner())
                            || !Double.isFinite(candidate.price()) || candidate.price() <= 0
                            || !Double.isFinite(candidate.units()) || candidate.units() <= 0
                            || !Double.isFinite(candidate.escrowAmount()) || candidate.escrowAmount() <= 0
                            || !Double.isFinite(candidate.makerFeeRemaining())
                            || candidate.makerFeeRemaining() < 0
                            || !Double.isFinite(makerMoney) || makerMoney <= EPSILON
                            || !Double.isFinite(expectedEscrow)
                            || !approximatelyEqual(candidate.escrowAmount(), expectedEscrow)
                            || (!candidate.sideBuy()
                            && candidate.makerFeeRemaining() + EPSILON >= makerMoney)
                            || ((authoritativeAssetType == 2 || authoritativeAssetType == 3)
                            && !physicalQuantity(candidate.units()))) {
                        throw new SQLException("Corrupt maker order encountered during settlement");
                    }
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private static boolean physicalQuantity(double quantity) {
        return Double.isFinite(quantity) && quantity > 0 && quantity <= Integer.MAX_VALUE
                && quantity == Math.rint(quantity);
    }

    private static boolean approximatelyEqual(double actual, double expected) {
        if (Double.compare(actual, expected) == 0) {
            return true;
        }
        // Financial invariants may only absorb floating-point representation
        // noise. A relative epsilon would permit material under-collateralization
        // at large values (for example, roughly 1,000 at an expected 1e12).
        double tolerance = Math.max(Math.ulp(actual), Math.ulp(expected)) * 32.0;
        return Math.abs(actual - expected) <= tolerance;
    }

    private static List<PlannedFill> planFills(List<Candidate> candidates, double requestedUnits) {
        List<PlannedFill> fills = new ArrayList<>();
        double missing = requestedUnits;
        for (Candidate maker : candidates) {
            if (missing <= EPSILON) {
                break;
            }
            double units = Math.min(missing, maker.units());
            double fee = maker.makerFeeRemaining() * (units / maker.units());
            fills.add(new PlannedFill(maker, units, fee, UUID.randomUUID()));
            missing -= units;
        }
        return fills;
    }

    private static boolean validRestingFee(boolean sideBuy, double price, double units, String makerFee) {
        double cost = price * units;
        if (!Double.isFinite(cost) || cost <= EPSILON) {
            return false;
        }
        double calculatedFee = fee(makerFee, cost);
        return Double.isFinite(calculatedFee)
                && Double.isFinite(sideBuy ? cost + calculatedFee : units)
                && (sideBuy || calculatedFee + EPSILON < cost);
    }

    private static double restingEscrow(boolean sideBuy, double price, double units, double makerFee) {
        double cost = price * units;
        double escrow = sideBuy ? cost + makerFee : units;
        return Double.isFinite(escrow) ? escrow : Double.POSITIVE_INFINITY;
    }

    private static double fee(String specification, double amount) {
        if (specification == null || specification.isBlank()) {
            return 0;
        }
        try {
            double result;
            String normalized = specification.trim().toLowerCase(Locale.ROOT);
            if (normalized.endsWith("%")) {
                result = Double.parseDouble(normalized.substring(0, normalized.length() - 1)) / 100.0 * amount;
            } else {
                result = Double.parseDouble(normalized);
            }
            if (!Double.isFinite(result) || result < 0) {
                throw new IllegalArgumentException("Invalid fee " + specification);
            }
            return result;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid fee " + specification, error);
        }
    }

    private static boolean debitHolding(Connection connection, byte[] worldId, String account,
                                        String asset, double quantity) throws SQLException {
        if (!Double.isFinite(quantity) || quantity <= 0) {
            return false;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE account_assets SET quantity = quantity - ? "
                        + "WHERE world = ? AND account_name = ? AND asset_id = ? AND quantity >= ?")) {
            statement.setDouble(1, quantity);
            statement.setBytes(2, worldId);
            statement.setString(3, account);
            statement.setString(4, asset);
            statement.setDouble(5, quantity);
            return statement.executeUpdate() == 1;
        }
    }

    private static void insertOrder(Connection connection, Request request, UUID orderId, double units,
                                    double escrow, double makerFee, Order.OrderType type) throws SQLException {
        if (!Double.isFinite(units) || units <= EPSILON || !Double.isFinite(escrow) || escrow <= EPSILON
                || !Double.isFinite(makerFee) || makerFee < 0) {
            throw new SQLException("Invalid derived resting order values");
        }
        String sql = "INSERT INTO orders "
                + "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                + "maker_fee_remaining, order_type, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setBytes(1, request.worldId());
            statement.setBytes(2, uuidBytes(orderId));
            statement.setString(3, request.takerAccount());
            statement.setString(4, request.assetId());
            statement.setBoolean(5, request.sideBuy());
            statement.setDouble(6, request.price());
            statement.setDouble(7, units);
            statement.setDouble(8, escrow);
            statement.setDouble(9, makerFee);
            statement.setInt(10, type.getValue());
            statement.setLong(11, request.nowMillis());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Could not place resting order");
            }
        }
    }

    private static void consumeMakerOrder(Connection connection, Request request, UUID executionId,
                                          PlannedFill fill) throws SQLException {
        Candidate maker = fill.maker();
        double filledUnits = fill.units();
        if (!Double.isFinite(filledUnits) || filledUnits <= 0 || filledUnits > maker.units()) {
            throw new SQLException("Invalid maker fill quantity");
        }
        double remainingUnits = maker.units() - filledUnits;
        if (Double.compare(filledUnits, maker.units()) >= 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM orders WHERE world = ? AND order_uuid = ?")) {
                statement.setBytes(1, request.worldId());
                statement.setBytes(2, uuidBytes(maker.orderId()));
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Maker order disappeared while locked");
                }
            }
            return;
        }
        double remainingEscrow = maker.escrowAmount() * (remainingUnits / maker.units());
        double remainingMakerFee = maker.makerFeeRemaining() * (remainingUnits / maker.units());
        if (!Double.isFinite(remainingEscrow) || remainingEscrow <= 0
                || !Double.isFinite(remainingMakerFee) || remainingMakerFee < 0) {
            throw new SQLException("Invalid maker remainder values");
        }
        if (remainingUnits <= EPSILON) {
            // The matching engine intentionally does not place sub-EPSILON dust
            // back on the book. Return every remaining escrow unit through the
            // same durable, exactly-once outbox instead of silently burning it.
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM orders WHERE world = ? AND order_uuid = ?")) {
                statement.setBytes(1, request.worldId());
                statement.setBytes(2, uuidBytes(maker.orderId()));
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Maker order disappeared while refunding dust");
                }
            }
            enqueueCredit(connection, executionId,
                    "execution:" + executionId + ":dust:" + fill.fillId(),
                    request.worldId(), maker.owner(),
                    maker.sideBuy() ? request.mainCurrencyId() : request.assetId(),
                    remainingEscrow, request.nowMillis());
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE orders SET units = ?, escrow_amount = ?, maker_fee_remaining = ? "
                        + "WHERE world = ? AND order_uuid = ?")) {
            statement.setDouble(1, remainingUnits);
            statement.setDouble(2, remainingEscrow);
            statement.setDouble(3, remainingMakerFee);
            statement.setBytes(4, request.worldId());
            statement.setBytes(5, uuidBytes(maker.orderId()));
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Maker order disappeared while locked");
            }
        }
    }

    private static void insertExecution(Connection connection, Request request, UUID executionId,
                                        double units, double money, double takerFee, double executionPrice)
            throws SQLException {
        String sql = "INSERT INTO trade_executions "
                + "(execution_id, request_id, world, asset_id, taker_account, taker_side_buy, total_units, "
                + "total_money, taker_fee, execution_price, status, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SETTLED', ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(executionId));
            statement.setBytes(2, uuidBytes(request.orderId()));
            statement.setBytes(3, request.worldId());
            statement.setString(4, request.assetId());
            statement.setString(5, request.takerAccount());
            statement.setBoolean(6, request.sideBuy());
            statement.setDouble(7, units);
            statement.setDouble(8, money);
            statement.setDouble(9, takerFee);
            statement.setDouble(10, executionPrice);
            statement.setLong(11, request.nowMillis());
            statement.executeUpdate();
        }
    }

    private static void insertFill(Connection connection, UUID executionId, PlannedFill fill) throws SQLException {
        String sql = "INSERT INTO trade_fills "
                + "(fill_id, execution_id, maker_order_uuid, maker_order_sequence, maker_account, maker_side_buy, "
                + "price, units, maker_fee) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(fill.fillId()));
            statement.setBytes(2, uuidBytes(executionId));
            statement.setBytes(3, uuidBytes(fill.maker().orderId()));
            statement.setLong(4, fill.maker().sequence());
            statement.setString(5, fill.maker().owner());
            statement.setBoolean(6, fill.maker().sideBuy());
            statement.setDouble(7, fill.maker().price());
            statement.setDouble(8, fill.units());
            statement.setDouble(9, fill.makerFee());
            statement.executeUpdate();
        }
    }

    public static void enqueueCredit(Connection connection, UUID executionId, String legKey, byte[] worldId,
                                     String account, String asset, double quantity, long nowMillis)
            throws SQLException {
        if (!Double.isFinite(quantity) || quantity <= 0) {
            throw new SQLException("Invalid outbox credit quantity");
        }
        String sql = "INSERT INTO delivery_outbox "
                + "(delivery_id, execution_id, leg_key, world, account_name, asset_id, quantity, status, "
                + "attempts, next_attempt_at, lease_until, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, 0, 0, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, uuidBytes(UUID.randomUUID()));
            if (executionId == null) {
                statement.setNull(2, java.sql.Types.BINARY);
            } else {
                statement.setBytes(2, uuidBytes(executionId));
            }
            statement.setString(3, legKey);
            statement.setBytes(4, worldId);
            statement.setString(5, account);
            statement.setString(6, asset);
            statement.setDouble(7, quantity);
            statement.setLong(8, nowMillis);
            statement.executeUpdate();
        }
    }

    private static void updateCandles(Connection connection, boolean mysql, byte[] worldId, String asset,
                                      double open, double close, double high, double low,
                                      double volume, long marketTime) throws SQLException {
        for (int index = 0; index < CANDLE_TABLES.length; index++) {
            String table = CANDLE_TABLES[index];
            String sql = mysql
                    ? "INSERT INTO candles_" + table
                    + " (world, time, open, close, high, low, volume, asset_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE high = GREATEST(high, VALUES(high)), "
                    + "low = LEAST(low, VALUES(low)), close = VALUES(close), volume = volume + VALUES(volume)"
                    : "INSERT INTO candles_" + table
                    + " (world, time, open, close, high, low, volume, asset_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(world, time, asset_id) DO UPDATE SET high = MAX(high, excluded.high), "
                    + "low = MIN(low, excluded.low), close = excluded.close, volume = volume + excluded.volume";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setBytes(1, worldId);
                statement.setLong(2, (marketTime / CANDLE_INTERVALS[index]) * CANDLE_INTERVALS[index]);
                statement.setDouble(3, open);
                statement.setDouble(4, close);
                statement.setDouble(5, high);
                statement.setDouble(6, low);
                statement.setDouble(7, volume);
                statement.setString(8, asset);
                statement.executeUpdate();
            }
        }
    }

    private static void updateStopOrders(Connection connection, byte[] worldId, String asset,
                                         double previous, double actual, long nowMillis) throws SQLException {
        if (!Double.isFinite(previous) || !Double.isFinite(actual) || previous <= 0 || previous == actual) {
            return;
        }
        boolean rising = actual > previous;
        String selectSql = "SELECT order_uuid, owner, sideBuy, price, units, escrow_amount, "
                + "maker_fee_remaining FROM orders WHERE world = ? AND asset_id = ? AND order_type = ? "
                + "AND sideBuy = ? AND price " + (rising ? ">" : "<") + " ? AND price "
                + (rising ? "<=" : ">=") + " ? ORDER BY sequence_id ASC";
        List<TriggeredOrder> triggered = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, asset);
            statement.setInt(3, Order.OrderType.STOP_LIMIT.getValue());
            statement.setBoolean(4, rising);
            statement.setDouble(5, previous);
            statement.setDouble(6, actual);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    triggered.add(new TriggeredOrder(uuid(result.getBytes("order_uuid")),
                            result.getString("owner"), result.getBoolean("sideBuy"),
                            result.getDouble("price"), result.getDouble("units"),
                            result.getDouble("escrow_amount"), result.getDouble("maker_fee_remaining")));
                }
            }
        }
        if (triggered.isEmpty()) {
            return;
        }

        String deleteSql = "DELETE FROM orders WHERE world = ? AND order_uuid = ?";
        String insertSql = "INSERT INTO orders "
                + "(world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount, "
                + "maker_fee_remaining, order_type, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement delete = connection.prepareStatement(deleteSql);
             PreparedStatement insert = connection.prepareStatement(insertSql)) {
            for (TriggeredOrder order : triggered) {
                delete.setBytes(1, worldId);
                delete.setBytes(2, uuidBytes(order.orderId()));
                if (delete.executeUpdate() != 1) {
                    throw new SQLException("Triggered stop order disappeared while market was locked");
                }
                insert.setBytes(1, worldId);
                insert.setBytes(2, uuidBytes(order.orderId()));
                insert.setString(3, order.owner());
                insert.setString(4, asset);
                insert.setBoolean(5, order.sideBuy());
                insert.setDouble(6, order.price());
                insert.setDouble(7, order.units());
                insert.setDouble(8, order.escrowAmount());
                insert.setDouble(9, order.makerFeeRemaining());
                insert.setInt(10, Order.OrderType.LIMIT.getValue());
                insert.setLong(11, nowMillis);
                if (insert.executeUpdate() != 1) {
                    throw new SQLException("Could not requeue triggered stop order");
                }
            }
        }
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BINARY);
        } else {
            statement.setBytes(index, uuidBytes(value));
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte current : value) {
            result.append(Character.forDigit((current >>> 4) & 0xF, 16));
            result.append(Character.forDigit(current & 0xF, 16));
        }
        return result.toString();
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) throws SQLException {
        if (value == null || value.length != 16) {
            throw new SQLException("Invalid UUID payload");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
