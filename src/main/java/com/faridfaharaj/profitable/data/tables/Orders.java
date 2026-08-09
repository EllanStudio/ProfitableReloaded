package com.faridfaharaj.profitable.data.tables;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.data.settlement.TradeSettlementRepository;
import com.faridfaharaj.profitable.redis.RedisManager;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.logging.Level;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class Orders {

    /** Places administrative maker liquidity using the same atomic wallet escrow as player orders. */
    public static TradeSettlementRepository.Result placeOrderFromWallet(
            World world, UUID uuid, String owner, String asset, boolean sideBuy,
            double price, double units, Order.OrderType orderType, long marketTime) {
        if (!Profitable.isTradingReady() || world == null || uuid == null || owner == null || asset == null
                || orderType == null || !Double.isFinite(price) || price <= 0
                || !Double.isFinite(units) || units <= 0 || marketTime < 0) {
            return null;
        }
        Asset assetData = Assets.getAssetData(world, asset);
        if (assetData == null || ((assetData.getAssetType() == 2 || assetData.getAssetType() == 3)
                && (units > Integer.MAX_VALUE || units != Math.rint(units)))) {
            return null;
        }
        TradeSettlementRepository.Request request = new TradeSettlementRepository.Request(
                MessagingUtil.getWorldId(world), uuid, owner, asset,
                Configuration.MAINCURRENCYASSET.getCode(), sideBuy, price, units, orderType,
                assetData.getAssetType(), "0", Configuration.ASSETFEES[assetData.getAssetType()][1],
                marketTime, System.currentTimeMillis());
        try (Connection connection = DataBase.getConnection()) {
            return TradeSettlementRepository.place(connection, DataBase.isMySQL(), request);
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not place escrowed order", e);
            return null;
        }
    }

    /*public static List<Order> getStopMarket(double old, double actual) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * WHERE world = ? AND order_type == 2 AND price <= ? AND price >= ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, DataBase.getCurrentWorld());
            stmt.setDouble(2, Math.max(old,actual));
            stmt.setDouble(3, Math.min(old,actual));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    Order order = new Order(
                            TextUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                            rs.getString("owner"),
                            rs.getString("asset_id"),
                            rs.getBoolean("sideBuy"),
                            rs.getDouble("price"),
                            rs.getDouble("units"),
                            Order.OrderType.fromValue(rs.getInt("order_type"))
                    );
                    orders.add(order);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }
    }*/


    public static List<Order> getBestOrders(World world,String asset, boolean sideBuy, double price, double units, String excludedOwner) {
        List<Order> orders = new ArrayList<>();
        if (!(units > 0) || !Double.isFinite(units)) {
            return orders;
        }

        String sql = "SELECT * FROM orders WHERE world = ? AND asset_id = ? AND sideBuy = ? AND owner <> ? AND price " + (sideBuy ? "<=" : ">=") + " ? AND order_type = " + Order.OrderType.LIMIT.getValue() + " ORDER BY price " + (sideBuy ? "ASC" : "DESC") + ", sequence_id ASC;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, asset);
            stmt.setBoolean(3, !sideBuy);
            stmt.setString(4, excludedOwner);
            stmt.setDouble(5, price);

            try (ResultSet rs = stmt.executeQuery()) {
                double accumulatedUnits = 0;
                while (rs.next()) {
                    double iteratedUnits = rs.getDouble("units");

                    Order order = new Order(
                            MessagingUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                            rs.getString("owner"),
                            rs.getString("asset_id"),
                            rs.getBoolean("sideBuy"),
                            rs.getDouble("price"),
                            iteratedUnits,
                            Order.OrderType.fromValue(rs.getInt("order_type"))
                    );
                    orders.add(order);

                    accumulatedUnits += iteratedUnits;
                    if(accumulatedUnits >= units){
                        break;
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }
        return orders;
    }

    public static List<Order> getBidAsk(World world,String asset, boolean isBid) {
        List<Order> orders = new ArrayList<>();
        String orderDirection = isBid ? "DESC" : "ASC";

        String sql = "SELECT price, SUM(units) as units FROM orders " +
                "WHERE world = ? AND asset_id = ? AND sideBuy = ? AND order_type = ? " +
                "GROUP BY price " +
                "ORDER BY price " + orderDirection + " " +
                "LIMIT 7;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, asset);
            stmt.setBoolean(3, isBid);
            stmt.setInt(4, Order.OrderType.LIMIT.getValue());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    orders.add(new Order(
                            null,
                            null,
                            null,
                            isBid,
                            rs.getDouble("price"),
                            rs.getDouble("units"),
                            null
                    ));
                }
            }
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return orders;
    }

    public static List<Order> getAccountOrders(World world,String owner) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE world = ? AND owner = ? ORDER BY asset_id;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, owner);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    Order order = new Order(
                            MessagingUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                            rs.getString("owner"),
                            rs.getString("asset_id"),
                            rs.getBoolean("sideBuy"),
                            rs.getDouble("price"),
                            rs.getDouble("units"),
                            Order.OrderType.fromValue(rs.getInt("order_type"))
                    );

                    orders.add(order);

                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }
        return orders;
    }

    public static List<Order> getAssetOrders(World world,String asset) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE world = ? AND asset_id = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, asset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    Order order = new Order(
                            MessagingUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                            rs.getString("owner"),
                            rs.getString("asset_id"),
                            rs.getBoolean("sideBuy"),
                            rs.getDouble("price"),
                            rs.getDouble("units"),
                            Order.OrderType.fromValue(rs.getInt("order_type"))
                    );

                    orders.add(order);

                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }
        return orders;
    }

    public static Order getOrder(World world,UUID uuid) {
        String sql = "SELECT * FROM orders WHERE world = ? AND order_uuid = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setBytes(2, MessagingUtil.UUIDtoBytes(uuid));

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {

                    return new Order(
                            MessagingUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                            rs.getString("owner"),
                            rs.getString("asset_id"),
                            rs.getBoolean("sideBuy"),
                            rs.getDouble("price"),
                            rs.getDouble("units"),
                            Order.OrderType.fromValue(rs.getInt("order_type"))
                    );

                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return null;

    }



    public static List<Order> getAllOrders(World world) {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE world = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Order order = new Order(
                            MessagingUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                            rs.getString("owner"),
                            rs.getString("asset_id"),
                            rs.getBoolean("sideBuy"),
                            rs.getDouble("price"),
                            rs.getDouble("units"),
                            Order.OrderType.fromValue(rs.getInt("order_type"))
                    );
                    orders.add(order);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }
        return orders;
    }

    public static List<Order> getAllOrders() {
        List<Order> orders = new ArrayList<>();
        String sql = "SELECT * FROM orders;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                Order order = new Order(
                        MessagingUtil.UUIDfromBytes(rs.getBytes("order_uuid")),
                        rs.getString("owner"),
                        rs.getString("asset_id"),
                        rs.getBoolean("sideBuy"),
                        rs.getDouble("price"),
                        rs.getDouble("units"),
                        Order.OrderType.fromValue(rs.getInt("order_type"))
                );
                orders.add(order);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return orders;
    }

    public static void deleteOrders(World world,List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        for (Order order : orders) {
            cancelOrder(world, order.getUuid());
        }
    }

    public static boolean deleteOrder(World world,UUID uuid) {
        // Historical "delete" commands now preserve escrow and use durable refunds.
        return cancelOrder(world, uuid);
    }

    public static boolean deleteAllOrders() {
        List<OrderKey> keys = new ArrayList<>();
        try (Connection connection = DataBase.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT world, order_uuid FROM orders");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                keys.add(new OrderKey(result.getBytes("world"),
                        MessagingUtil.UUIDfromBytes(result.getBytes("order_uuid"))));
            }
        } catch (SQLException | IOException error) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not list orders for cancellation", error);
            return false;
        }
        int cancelled = 0;
        for (OrderKey key : keys) {
            if (cancelOrderInternal(key.worldId(), key.orderId(), null).cancelled()) {
                cancelled++;
            }
        }
        return cancelled > 0;
    }

    public static boolean cancelOrder(UUID orderid, Player player){
        return cancelOrder(orderid, player, player.getWorld(), Accounts.getAccount(player));
    }

    public static boolean cancelOrder(UUID orderid, Player player, World world, String account){
        TradeSettlementRepository.Cancellation cancellation = cancelOrderInternal(
                MessagingUtil.getWorldId(world), orderid, account);
        if (!cancellation.cancelled()) {
            return false;
        }

        Order order = new Order(cancellation.orderId(), cancellation.owner(), cancellation.assetId(),
                cancellation.sideBuy(), cancellation.price(), cancellation.units(), Order.OrderType.LIMIT);
        Asset refundAsset = cancellation.sideBuy()
                ? Configuration.MAINCURRENCYASSET : Assets.getAssetData(world, cancellation.assetId());

        Profitable.getfolialib().getScheduler().runAtEntity(player,
                task -> player.playSound(player, Sound.ENTITY_ITEM_BREAK, 1, 1));

        // Notify other servers about order cancellation
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("order_cancelled", world.getName() + ":" + order.getUuid());
        }

        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("orders.cancel",
                        Map.entry("%order%", order.toStringSimplified()))
                );
        if (refundAsset != null) {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("orders.refund-queued",
                    Map.entry("%asset_amount%",
                            MessagingUtil.assetAmmount(refundAsset, cancellation.refundAmount()))));
        }

        return true;
    }

    public static boolean cancelOrder(World world, UUID orderid){
        return cancelOrderInternal(MessagingUtil.getWorldId(world), orderid, null).cancelled();
    }

    private static TradeSettlementRepository.Cancellation cancelOrderInternal(
            byte[] worldId, UUID orderId, String expectedOwner) {
        try (Connection connection = DataBase.getConnection()) {
            TradeSettlementRepository.Cancellation cancellation = TradeSettlementRepository.cancel(
                    connection, DataBase.isMySQL(), worldId, orderId, expectedOwner,
                    Configuration.MAINCURRENCYASSET.getCode(), System.currentTimeMillis());
            if (cancellation.cancelled()) {
                Profitable.wakeDeliveryOutbox();
            }
            return cancellation;
        } catch (SQLException error) {
            Profitable.getInstance().getLogger().log(Level.SEVERE,
                    "Could not cancel order with durable refund " + orderId, error);
            return TradeSettlementRepository.Cancellation.missing(orderId);
        }
    }

    private record OrderKey(byte[] worldId, UUID orderId) {
        private OrderKey {
            worldId = Arrays.copyOf(worldId, worldId.length);
        }

        @Override
        public byte[] worldId() {
            return Arrays.copyOf(worldId, worldId.length);
        }
    }

}
