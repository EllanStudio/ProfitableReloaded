package com.faridfaharaj.profitable.exchange.Books;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.data.settlement.TradeSettlementRepository;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.redis.RedisManager;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** Coordinates player-facing validation with the database-only settlement state machine. */
public final class Exchange {

    private Exchange() {
    }

    /**
     * Captures all Bukkit-owned state on the player's region thread, then performs the
     * matching transaction asynchronously. Trading always consumes exchange-wallet
     * balances; physical inventory/world delivery is an explicit wallet withdrawal.
     */
    public static void sendNewOrder(Player player, Order order) {
        if (player == null || order == null) {
            return;
        }
        Profitable.getfolialib().getScheduler().runAtEntity(player,
                task -> prepareOrder(player, order));
    }

    private static void prepareOrder(Player player, Order order) {
        if (!Profitable.isTradingReady()) {
            MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get("exchange.error.sync-unavailable"));
            return;
        }

        World world = player.getWorld();
        Asset tradedAsset = Assets.getAssetData(world, order.getAsset());
        String validationError = validateOrder(player, order, tradedAsset);
        if (validationError != null) {
            sendValidationError(player, order, validationError);
            return;
        }

        String account = Accounts.getAccount(player);
        if (account == null || account.isBlank()) {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("generic.error.internal"));
            return;
        }

        byte[] worldId = MessagingUtil.getWorldId(world);
        String worldName = world.getName();
        long marketTime = world.getFullTime();
        UUID orderId = order.getUuid() == null ? UUID.randomUUID() : order.getUuid();
        Order captured = new Order(orderId, account, order.getAsset(), order.isSideBuy(),
                order.getPrice(), order.getUnits(), order.getType());

        Profitable.getfolialib().getScheduler().runAsync(task ->
                executeOrder(player, worldId, worldName, marketTime, captured, tradedAsset));
    }

    private static String validateOrder(Player player, Order order, Asset tradedAsset) {
        if (tradedAsset == null) {
            return "assets.error.asset-not-found";
        }
        if (Objects.equals(order.getAsset(), Configuration.MAINCURRENCYASSET.getCode())) {
            return "exchange.error.identical-assets";
        }

        String permissionType = switch (tradedAsset.getAssetType()) {
            case 1 -> "forex";
            case 2 -> "item";
            case 3 -> "entity";
            default -> null;
        };
        if (permissionType == null
                || !player.hasPermission("profitable.market.trade.asset." + permissionType)) {
            return "generic.error.missing-perm";
        }
        if (!Double.isFinite(order.getUnits()) || order.getUnits() <= 0
                || !Double.isFinite(order.getPrice()) || order.getPrice() <= 0) {
            return "generic.error.invalid-amount";
        }
        if ((tradedAsset.getAssetType() == 2 || tradedAsset.getAssetType() == 3)
                && (order.getUnits() > Integer.MAX_VALUE || order.getUnits() != Math.rint(order.getUnits()))) {
            return "assets.error.cant-fractional";
        }
        if (order.getType() == null || order.getType() == Order.OrderType.STOP_MARKET) {
            return "exchange.error.invalid-order-type";
        }
        return null;
    }

    private static void sendValidationError(Player player, Order order, String key) {
        if ("generic.error.invalid-amount".equals(key)) {
            String invalid = !Double.isFinite(order.getUnits()) || order.getUnits() <= 0
                    ? String.valueOf(order.getUnits()) : String.valueOf(order.getPrice());
            MessagingUtil.sendGenericInvalidAmount(player, invalid);
        } else if ("generic.error.missing-perm".equals(key)) {
            MessagingUtil.sendGenericMissingPerm(player);
        } else {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(key,
                    Map.entry("%asset%", order.getAsset())));
        }
    }

    private static void executeOrder(Player player, byte[] worldId, String worldName,
                                     long marketTime, Order order, Asset tradedAsset) {
        if (!Profitable.isTradingReady()) {
            MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get("exchange.error.sync-unavailable"));
            return;
        }

        TradeSettlementRepository.Request request = new TradeSettlementRepository.Request(
                worldId,
                order.getUuid(),
                order.getOwner(),
                order.getAsset(),
                Configuration.MAINCURRENCYASSET.getCode(),
                order.isSideBuy(),
                order.getPrice(),
                order.getUnits(),
                order.getType(),
                tradedAsset.getAssetType(),
                Configuration.ASSETFEES[tradedAsset.getAssetType()][0],
                Configuration.ASSETFEES[tradedAsset.getAssetType()][1],
                marketTime,
                System.currentTimeMillis()
        );

        TradeSettlementRepository.Result result;
        try (Connection connection = DataBase.getConnection()) {
            result = TradeSettlementRepository.settle(connection, DataBase.isMySQL(), request);
        } catch (SQLException | RuntimeException error) {
            Profitable.getInstance().getLogger().log(Level.SEVERE,
                    "Could not confirm settlement request " + order.getUuid(), error);
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(
                    "exchange.error.settlement-unknown",
                    Map.entry("%request_id%", order.getUuid().toString())));
            return;
        }

        try {
            handleResult(player, worldId, worldName, marketTime, order, tradedAsset, result);
        } catch (RuntimeException error) {
            // Settlement has already committed. Never label a presentation or
            // notification failure as a failed trade, which could invite a new
            // request and an unintended second fill.
            Profitable.getInstance().getLogger().log(Level.SEVERE,
                    "Settlement request " + order.getUuid()
                            + " committed but post-commit handling failed", error);
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(
                    "exchange.error.settlement-committed-notification-failed",
                    Map.entry("%request_id%", order.getUuid().toString())));
        }
    }

    private static void handleResult(Player player, byte[] worldId, String worldName, long marketTime, Order order,
                                     Asset tradedAsset, TradeSettlementRepository.Result result) {
        switch (result.status()) {
            case NO_LIQUIDITY -> MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get("exchange.error.no-orders-found"));
            case INSUFFICIENT_FUNDS -> {
                Asset collateral = order.isSideBuy() ? Configuration.MAINCURRENCYASSET : tradedAsset;
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(
                        "assets.error.not-enough-asset", Map.entry("%asset%", collateral.getCode())));
            }
            case INVALID_FEE -> {
                double estimated = Configuration.parseFee(
                        Configuration.ASSETFEES[tradedAsset.getAssetType()][order.getType() == Order.OrderType.MARKET ? 0 : 1],
                        order.getPrice() * order.getUnits());
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(
                        "exchange.warning.fee-higher-than-profit",
                        Map.entry("%fee_asset_amount%",
                                MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, estimated))));
            }
            case INVALID_STOP_TRIGGER -> MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get(order.isSideBuy()
                            ? "exchange.error.invalid-buy-stop-trigger"
                            : "exchange.error.invalid-sell-stop-trigger"));
            case ALREADY_PROCESSED -> MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get("exchange.warning.request-already-processed"));
            case PLACED -> sendRestingOrderNotice(player, order, tradedAsset, order.getUnits());
            case EXECUTED, PARTIAL -> {
                Profitable.wakeDeliveryOutbox();
                for (TradeSettlementRepository.Fill fill : result.fills()) {
                    double money = fill.price() * fill.units();
                    double visibleFee = fill.makerSideBuy() ? 0 : fill.makerFee();
                    sendTransactionNotice(fill.makerAccount(), fill.makerSideBuy(), tradedAsset,
                            fill.units(), money, visibleFee);
                    publishRemoteTransactionNotice(worldId, fill.makerAccount(), fill.makerSideBuy(), tradedAsset,
                            fill.units(), money, visibleFee);
                }
                sendTransactionNotice(player, order.isSideBuy(), tradedAsset,
                        result.executedUnits(), result.money(), result.takerFee());

                double remainder = Math.max(0, order.getUnits() - result.executedUnits());
                if (remainder > 1.0E-9) {
                    MessagingUtil.sendComponentMessage(player,
                            Profitable.getLang().get("exchange.warning.partial-fill-low-liquidity"));
                    if (result.restingOrderId() != null) {
                        sendRestingOrderNotice(player, order, tradedAsset, remainder);
                    }
                }
                publishTradeExecuted(worldName, tradedAsset, result.executionPrice(),
                        result.executedUnits(), marketTime);
            }
        }
    }

    private static void sendRestingOrderNotice(Player player, Order order, Asset tradedAsset, double units) {
        double cost = order.getPrice() * units;
        double makerFee = Configuration.parseFee(
                Configuration.ASSETFEES[tradedAsset.getAssetType()][1], cost);
        Profitable.getfolialib().getScheduler().runAtEntity(player,
                task -> player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 1, 1));
        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("exchange.new-order-notice",
                Map.entry("%order_type%", order.getType().toString().replace("_", "-").toLowerCase()),
                Map.entry("%side%", order.isSideBuy()
                        ? Profitable.getLang().getString("orders.sides.buy")
                        : Profitable.getLang().getString("orders.sides.sell")),
                Map.entry("%base_asset_amount%", MessagingUtil.assetAmmount(tradedAsset, units)),
                Map.entry("%quote_asset_amount%",
                        MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, order.getPrice()))));
        if (order.isSideBuy()) {
            MessagingUtil.sendChargeNotice(player, cost, makerFee, Configuration.MAINCURRENCYASSET);
        } else {
            MessagingUtil.sendChargeNotice(player, units, 0, tradedAsset);
        }
    }

    private static void publishTradeExecuted(String worldName, Asset tradedAsset,
                                             double price, double units, long marketTime) {
        RedisManager redis = Profitable.getRedisManager();
        if (redis != null && redis.isConnected()) {
            redis.publishAsync("trade_executed", worldName + ":" + tradedAsset.getCode()
                    + ":" + price + ":" + units + ":" + marketTime);
        }
    }

    private static void publishRemoteTransactionNotice(byte[] worldId, String account, boolean sideBuy,
                                                       Asset tradedAsset,
                                                       double units, double money, double fee) {
        RedisManager redis = Profitable.getRedisManager();
        if (redis != null && redis.isConnected()) {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String encodedWorld = encoder.encodeToString(worldId);
            String encodedAsset = encoder.encodeToString(
                    tradedAsset.getCode().getBytes(StandardCharsets.UTF_8));
            redis.publishAsync("trade_notice", "v1:" + encodedWorld + ":" + account + ":" + sideBuy
                    + ":" + encodedAsset + ":" + units + ":" + money + ":" + fee);
        }
    }

    /**
     * Displays a notification-only Redis event; it never mutates balances,
     * orders or candles. The payload carries the authoritative market world id,
     * so an async Redis listener never scans or reads Bukkit worlds.
     */
    public static void handleRemoteTransactionNotice(String payload) {
        if (payload == null || payload.isBlank() || payload.length() > 1_024) {
            return;
        }
        String[] parts = payload.split(":", 8);
        if (parts.length != 8 || !"v1".equals(parts[0])) {
            return;
        }
        try {
            byte[] worldId = Base64.getUrlDecoder().decode(parts[1]);
            String account = parts[2];
            if (worldId.length != 16 || account.isBlank()
                    || !("true".equals(parts[3]) || "false".equals(parts[3]))) {
                throw new IllegalArgumentException("Invalid trade notice identity");
            }
            boolean sideBuy = Boolean.parseBoolean(parts[3]);
            String assetCode = new String(Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            double units = Double.parseDouble(parts[5]);
            double money = Double.parseDouble(parts[6]);
            double fee = Double.parseDouble(parts[7]);
            if (assetCode.isBlank() || assetCode.length() > 20
                    || !Double.isFinite(units) || units <= 0
                    || !Double.isFinite(money) || money <= 0
                    || !Double.isFinite(fee) || fee < 0) {
                throw new IllegalArgumentException("Invalid trade notice values");
            }
            Asset asset = Assets.getAssetData(worldId, assetCode);
            if (asset == null) {
                return;
            }
            sendTransactionNotice(account, sideBuy, asset, units, money, fee);
        } catch (IllegalArgumentException ignored) {
            Profitable.getInstance().getLogger().warning("Ignoring malformed remote trade notice");
        }
    }

    public static void sendTransactionNotice(Player player, boolean sideBuy, Asset tradedAsset,
                                             double units, double money, double fee) {
        MessagingUtil.sendComponentMessage(player,
                Profitable.getLang().get(sideBuy ? "exchange.buying-notice" : "exchange.selling-notice",
                        Map.entry("%base_asset_amount%", MessagingUtil.assetAmmount(tradedAsset, units)),
                        Map.entry("%quote_asset_amount%",
                                MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, money))));
        if (sideBuy) {
            MessagingUtil.sendChargeNotice(player, money, fee, Configuration.MAINCURRENCYASSET);
        } else {
            MessagingUtil.sendPaymentNotice(player, money, fee, Configuration.MAINCURRENCYASSET);
        }
        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            Location location = player.getLocation();
            player.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
            player.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1, 1);
            player.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1, 1);
        });
    }

    public static void sendTransactionNotice(String account, boolean sideBuy, Asset tradedAsset,
                                             double units, double money, double fee) {
        final UUID playerId;
        try {
            playerId = UUID.fromString(account);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        Profitable.getfolialib().getScheduler().runNextTick(task -> {
            Player player = Profitable.getInstance().getServer().getPlayer(playerId);
            if (player != null) {
                Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask -> {
                    if (player.isOnline()) {
                        sendTransactionNotice(player, sideBuy, tradedAsset, units, money, fee);
                    }
                });
            }
        });
    }
}
