package com.faridfaharaj.profitable.exchange.Books;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Candle;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.data.tables.Candles;
import com.faridfaharaj.profitable.data.tables.Orders;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.redis.RedisManager;
import com.faridfaharaj.profitable.tasks.TemporalItems;
import com.faridfaharaj.profitable.util.MessagingUtil;
import com.faridfaharaj.profitable.util.NamingUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.util.*;

public class Exchange {

    /** Sentinel returned by {@link #validateOrder} when the problem was already reported to the player. */
    private static final String HANDLED_SENTINEL = "exchange.handled";

    /**
     * Validates an order before processing. Returns null if valid, or an error message key if invalid.
     */
    private static String validateOrder(Player player, Order order, Asset tradedAsset) {
        if (Objects.equals(order.getAsset(), Configuration.MAINCURRENCYASSET.getCode())) {
            return "exchange.error.identical-assets";
        }

        if (tradedAsset == null) {
            return "assets.error.asset-not-found";
        }

        if (Configuration.PHYSICALDELIVERY) {
            String assetTypeName = NamingUtil.nameType(tradedAsset.getAssetType());

            if (tradedAsset.getAssetType() == 2) {
                if (!player.hasPermission("profitable.market.trade.asset.item")) {
                    return "generic.error.missing-perm";
                }
                if (Accounts.getItemDelivery(player.getWorld(), order.getOwner()) == null) {
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.error.missing-item-delivery"));
                    MessagingUtil.sendComponentMessage(player,
                            Component.text("You must set a location for delivery ", Configuration.COLORWARN)
                                    .append(MessagingUtil.buttonComponent("[Click here!]", "/delivery set item"))
                    );
                    TemporalItems.sendDeliveryStick(player, true);
                    return HANDLED_SENTINEL; // handled specially, not a simple error
                }
            } else if (tradedAsset.getAssetType() == 3) {
                if (!player.hasPermission("profitable.market.trade.asset.entity")) {
                    return "generic.error.missing-perm";
                }
                if (Accounts.getEntityDelivery(player.getWorld(), order.getOwner()) == null) {
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.error.missing-entity-delivery"));
                    MessagingUtil.sendComponentMessage(player,
                            Component.text("You must set a location for delivery ", Configuration.COLORWARN)
                                    .append(MessagingUtil.buttonComponent("[Click here!]", "/delivery set entity"))
                    );
                    TemporalItems.sendDeliveryStick(player, false);
                    return HANDLED_SENTINEL; // handled specially
                }
            } else if (!player.hasPermission("profitable.market.trade.asset." + assetTypeName.toLowerCase())) {
                return "generic.error.missing-perm";
            }
        }

        if (order.getUnits() <= 0) {
            return "generic.error.invalid-amount";
        }
        if (order.getPrice() <= 0) {
            return "generic.error.invalid-amount";
        }

        return null; // valid
    }

    public static void sendNewOrder(Player player, Order order){

        // validation -------------------------
        Asset tradedAsset = Assets.getAssetData(player.getWorld(), order.getAsset());

        if (tradedAsset == null) {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.asset-not-found",
                    Map.entry("%asset%", order.getAsset())
            ));
            return;
        }

        String validationError = validateOrder(player, order, tradedAsset);
        if (validationError != null) {
            if (HANDLED_SENTINEL.equals(validationError)) {
                return; // feedback already sent inside validateOrder
            }
            if ("generic.error.invalid-amount".equals(validationError)) {
                String invalidVal = order.getUnits() <= 0 ? String.valueOf(order.getUnits()) : String.valueOf(order.getPrice());
                MessagingUtil.sendGenericInvalidAmount(player, invalidVal);
            } else if ("generic.error.missing-perm".equals(validationError)) {
                MessagingUtil.sendGenericMissingPerm(player);
            } else {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(validationError,
                        Map.entry("%asset%", order.getAsset())
                ));
            }
            return;
        }

        // Normalize units for item/entity assets
        if (tradedAsset.getAssetType() == 2 || tradedAsset.getAssetType() == 3) {
            order.setUnits((int) order.getUnits());
        }

        // Send Order -------------------------
        Asset collateralAsset = order.isSideBuy() ? Configuration.MAINCURRENCYASSET : tradedAsset;
        Candle lastday = Candles.getLastDay(player.getWorld(), tradedAsset.getCode(), player.getWorld().getFullTime());

        // Stop orders
        if (order.getType() == Order.OrderType.STOP_LIMIT) {
            if (order.isSideBuy() ? lastday.getClose() >= order.getPrice() : lastday.getClose() <= order.getPrice()) {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get(
                        order.isSideBuy() ? "exchange.error.invalid-sell-stop-trigger" : "exchange.error.invalid-buy-stop-trigger"));
                return;
            }
            addToBook(player, order, Configuration.MAINCURRENCYASSET, tradedAsset, collateralAsset);
            return;
        } else if (order.getType() == Order.OrderType.STOP_MARKET) {
            addToBook(player, order, Configuration.MAINCURRENCYASSET, tradedAsset, collateralAsset);
            return;
        }

        // Lookup matching orders
        List<Order> orders = Orders.getBestOrders(player.getWorld(), order.getAsset(), order.isSideBuy(), order.getPrice(), order.getUnits());

        // No matches
        if (orders.isEmpty()) {
            if (order.getType() == Order.OrderType.MARKET) {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("exchange.error.no-orders-found"));
                return;
            }
            addToBook(player, order, Configuration.MAINCURRENCYASSET, tradedAsset, collateralAsset);
            return;
        }

        // Transacting - calculate fills
        Order partialOrder = null;
        double moneyTransacted = 0;
        double unitsMissing = order.getUnits();
        for (Order iteratedOrder : orders) {
            // Prevent self transact
            if (Objects.equals(iteratedOrder.getOwner(), order.getOwner())) {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("exchange.error.cant-self-transact"));
                return;
            }

            double transactingUnits = Math.min(unitsMissing, iteratedOrder.getUnits());

            // Partial order
            if (iteratedOrder.getUnits() > unitsMissing) {
                Order clone = new Order(iteratedOrder);
                clone.setUnits(iteratedOrder.getUnits() - transactingUnits);
                partialOrder = clone;
            }

            iteratedOrder.setUnits(transactingUnits);

            // Tracking
            unitsMissing -= transactingUnits;
            moneyTransacted += iteratedOrder.getPrice() * transactingUnits;
        }

        double unitsTransacted = order.getUnits() - unitsMissing;
        double takerFee = !Configuration.ASSETFEES[tradedAsset.getAssetType()][0].endsWith("%")
                ? Configuration.parseFee(Configuration.ASSETFEES[tradedAsset.getAssetType()][0], 23)
                : Configuration.parseFee(Configuration.ASSETFEES[tradedAsset.getAssetType()][0], moneyTransacted);

        double finalMoneyTransacted = moneyTransacted;
        Order finalPartialOrder = partialOrder;
        Asset.chargeAndRun(player, collateralAsset, order.isSideBuy() ? moneyTransacted + takerFee : unitsTransacted, () -> {
            Profitable.getfolialib().getScheduler().runAsync(task -> {
                List<Order> ordersToDelete = new ArrayList<>(orders);
                if (finalPartialOrder != null) {
                    Orders.updateOrderUnits(player.getWorld(), finalPartialOrder.getUuid(), finalPartialOrder.getUnits());
                    ordersToDelete.removeLast();
                }
                wrapTransactions(player, order, orders, takerFee, tradedAsset, collateralAsset, finalMoneyTransacted, unitsTransacted);
                Candles.updateDay(player.getWorld(), tradedAsset.getCode(), orders.getLast().getPrice(), unitsTransacted);
                Orders.updateStopLimit(player.getWorld(), lastday.getClose(), orders.getLast().getPrice());
                Orders.deleteOrders(player.getWorld(), ordersToDelete);

                // Notify other servers about this trade
                publishTradeExecuted(player, tradedAsset, orders.getLast().getPrice(), unitsTransacted);
            });
        });
    }

    /**
     * Publishes a trade_executed event to Redis for cross-server synchronization.
     */
    private static void publishTradeExecuted(Player player, Asset tradedAsset, double price, double units) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            String tradeMsg = player.getWorld().getName() + ":"
                    + tradedAsset.getCode() + ":"
                    + price + ":"
                    + units + ":"
                    + player.getWorld().getFullTime();
            rm.publishAsync("trade_executed", tradeMsg);
        }
    }

    private static void wrapTransactions(Player player, Order takerOrder, List<Order> makerOrder, double takerFee, Asset tradedAsset, Asset collateralAsset, double moneyTransacted, double unitsTransacted) {

        for(Order iteratedOrder:makerOrder){
            if(iteratedOrder.isSideBuy()){

                Asset.distributeAsset(player.getWorld(), iteratedOrder.getOwner(), tradedAsset, iteratedOrder.getUnits());
                //                                                                                                                            Paid on order placement --------v
                sendTransactionNotice(iteratedOrder.getOwner(), true, tradedAsset, iteratedOrder.getUnits(), (iteratedOrder.getUnits()*iteratedOrder.getPrice()), 0);

            }else {

                double makerFee = Configuration.parseFee(Configuration.ASSETFEES[tradedAsset.getAssetType()][1],iteratedOrder.getUnits()*iteratedOrder.getPrice());
                Asset.distributeAsset(player.getWorld(), iteratedOrder.getOwner(), Configuration.MAINCURRENCYASSET, iteratedOrder.getUnits()*iteratedOrder.getPrice()-makerFee);
                sendTransactionNotice(iteratedOrder.getOwner(), false, tradedAsset, iteratedOrder.getUnits(), (iteratedOrder.getUnits()*iteratedOrder.getPrice()), makerFee);

            }
        }

        // Partial fill
        if(takerOrder.getType() == Order.OrderType.MARKET){

            if(unitsTransacted != takerOrder.getUnits()){
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("exchange.warning.partial-fill-low-liquidity"));
            }

        }else{
            if(unitsTransacted != takerOrder.getUnits()){
                takerOrder.setUnits(takerOrder.getUnits()-unitsTransacted);
                addToBook(player, takerOrder, Configuration.MAINCURRENCYASSET, tradedAsset, collateralAsset);
            }

        }

        Asset.distributeAsset(player.getWorld(), takerOrder.getOwner(), takerOrder.isSideBuy()?tradedAsset:collateralAsset, takerOrder.isSideBuy()?unitsTransacted:moneyTransacted - takerFee);
        sendTransactionNotice(player, takerOrder.isSideBuy(), tradedAsset, unitsTransacted, moneyTransacted, takerFee);
    }

    public static void sendTransactionNotice(Player player, boolean sideBuy, Asset tradedAsset, double units, double money, double fee){

        MessagingUtil.sendComponentMessage(player,
                Profitable.getLang().get(sideBuy?"exchange.buying-notice":"exchange.selling-notice",
                        Map.entry("%base_asset_amount%", MessagingUtil.assetAmmount(tradedAsset, units)),
                        Map.entry("%quote_asset_amount%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, money))
                )
        );

        if(sideBuy){
            MessagingUtil.sendChargeNotice(player, money, fee, Configuration.MAINCURRENCYASSET);
        }else{
            MessagingUtil.sendPaymentNotice(player, money, fee, Configuration.MAINCURRENCYASSET);
        }

        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            Location location = player.getLocation();
            player.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1,1);
            player.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1,1);
            player.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1,1);
        });
    }

    public static void sendTransactionNotice(String account, boolean sideBuy, Asset tradedAsset, double units, double money, double fee){
        UUID playerid;
        try{
            playerid = UUID.fromString(account);
        }catch (Exception e){
            return;
        }

        Player player = Profitable.getInstance().getServer().getPlayer(playerid);
        if(player != null){
            MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get(sideBuy?"exchange.buying-notice":"exchange.selling-notice",
                            Map.entry("%base_asset_amount%", MessagingUtil.assetAmmount(tradedAsset, units)),
                            Map.entry("%quote_asset_amount%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, money))
                    )
            );

            if(sideBuy){
                MessagingUtil.sendChargeNotice(player, money, fee, Configuration.MAINCURRENCYASSET);
            }else{
                MessagingUtil.sendPaymentNotice(player, money, fee, Configuration.MAINCURRENCYASSET);
            }

            Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
                Location location = player.getLocation();
                player.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1,1);
                player.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1,1);
                player.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1,1);
            });
        }
    }

    public static void addToBook(Player player, Order order, Asset currency, Asset tradedasset, Asset collateralAsset){

        // Collateral
        double cost = order.getPrice()*order.getUnits();
        double makerFee = Configuration.parseFee(Configuration.ASSETFEES[tradedasset.getAssetType()][1], cost);

        Asset.chargeAndRun(player, collateralAsset, order.isSideBuy()? cost+makerFee: order.getUnits(), () -> {
            // Insert
            UUID orderUuid = UUID.randomUUID();
            Orders.insertOrder(player.getWorld(), orderUuid, order.getOwner(), order.getAsset(), order.isSideBuy(), order.getPrice(), order.getUnits(), order.getType());

            // Feedback
            Profitable.getfolialib().getScheduler().runAtEntity(player, task -> player.playSound(player, Sound.ITEM_BOOK_PAGE_TURN, 1 , 1));

            MessagingUtil.sendComponentMessage(player,Profitable.getLang().get("exchange.new-order-notice",
                            Map.entry("%order_type%", order.getType().toString().replace("_","-").toLowerCase()),
                            Map.entry("%side%", order.isSideBuy()?
                                    Profitable.getLang().getString("orders.sides.buy"):
                                    Profitable.getLang().getString("orders.sides.sell")),
                            Map.entry("%base_asset_amount%", MessagingUtil.assetAmmount(tradedasset, order.getUnits())),
                            Map.entry("%quote_asset_amount%", MessagingUtil.assetAmmount(currency, order.getPrice()))
                            )
            );

            if(order.isSideBuy()){
                MessagingUtil.sendChargeNotice(player, cost, makerFee, collateralAsset);
            }else{
                MessagingUtil.sendChargeNotice(player, order.getUnits(), 0, collateralAsset);
                if(makerFee >= cost){
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("exchange.selling-notice",
                            Map.entry("%fee_asset_amount%", MessagingUtil.assetAmmount(currency, makerFee))
                    ));
                }
            }
        });
    }

}
