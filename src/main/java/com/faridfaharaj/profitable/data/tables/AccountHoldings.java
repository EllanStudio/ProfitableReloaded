package com.faridfaharaj.profitable.data.tables;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Candle;
import com.faridfaharaj.profitable.tasks.gui.elements.specific.AssetCache;
import com.faridfaharaj.profitable.util.MessagingUtil;
import com.faridfaharaj.profitable.util.NamingUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.World;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.logging.Level;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class AccountHoldings {

    public static boolean addHolding(World world, String account, String asset, double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || !isValidAssetQuantity(world, asset, amount)) {
            return false;
        }
        String sql = "INSERT INTO account_assets (world, account_name, asset_id, quantity) VALUES (?, ?, ?, ?) "
                + (DataBase.isMySQL()
                ? "ON DUPLICATE KEY UPDATE quantity = quantity + VALUES(quantity)"
                : "ON CONFLICT(world, account_name, asset_id) DO UPDATE SET quantity = quantity + excluded.quantity");
        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, account);
            stmt.setString(3, asset);
            stmt.setDouble(4, amount);
            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not credit account holding", e);
            return false;
        }
    }

    public static boolean takeHolding(World world, String account, String asset, double amount) {
        if (!Double.isFinite(amount) || amount <= 0 || !isValidAssetQuantity(world, asset, amount)) {
            return false;
        }
        String sql = "UPDATE account_assets SET quantity = quantity - ? "
                + "WHERE world = ? AND account_name = ? AND asset_id = ? AND quantity >= ?";
        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setDouble(1, amount);
            stmt.setBytes(2, MessagingUtil.getWorldId(world));
            stmt.setString(3, account);
            stmt.setString(4, asset);
            stmt.setDouble(5, amount);
            return stmt.executeUpdate() == 1;
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not debit account holding", e);
            return false;
        }
    }

    public static boolean setHolding(World world, String account, String asset, double quantity) {
        if (!Double.isFinite(quantity) || quantity < 0 || !isValidAssetQuantity(world, asset, quantity)) {
            return false;
        }
        String sql = "INSERT INTO account_assets (world , account_name, asset_id, quantity) VALUES (?, ?, ?, ?) " + (!DataBase.isMySQL()? "ON CONFLICT(world, account_name, asset_id) DO UPDATE SET quantity = excluded.quantity;" : "ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);");

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, account);
            stmt.setString(3, asset);
            stmt.setDouble(4, quantity);

            int rows = stmt.executeUpdate();

            return rows > 0;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return false;
    }

    private static boolean isValidAssetQuantity(World world, String assetCode, double quantity) {
        if (world == null || assetCode == null) {
            return false;
        }
        Asset asset = Assets.getAssetData(world, assetCode);
        return asset != null && ((asset.getAssetType() != 2 && asset.getAssetType() != 3)
                || (quantity <= Integer.MAX_VALUE && quantity == Math.rint(quantity)));
    }

    public static double getAccountAssetBalance(World world,String account, String asset) {
        String sql = "SELECT quantity FROM account_assets WHERE world = ? AND account_name = ? AND asset_id = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, account);
            stmt.setString(3, asset);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("quantity");
                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return 0;
    }

    public static List<AssetCache> AssetBalancesToAssetData(World world,String account) {
        String sql = "WITH latest_candles AS (" +
                "SELECT world, asset_id, close, ROW_NUMBER() OVER (PARTITION BY world, asset_id ORDER BY time DESC) AS rn " +
                "FROM candles_day) " +
                "SELECT aa.asset_id, a.asset_type, aa.quantity, a.meta, " +
                "IFNULL(c.close, 0) AS price, " +
                "(aa.quantity * IFNULL(c.close, 0)) AS value " +
                "FROM account_assets aa " +
                "JOIN assets a ON aa.world = a.world AND aa.asset_id = a.asset_id " +
                "LEFT JOIN latest_candles c ON aa.world = c.world AND aa.asset_id = c.asset_id AND c.rn = 1 " +
                "WHERE aa.world = ? AND aa.account_name = ? " +
                "ORDER BY a.asset_type";

        List<AssetCache> balances = new ArrayList<>();

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, account);

            try (ResultSet rs = stmt.executeQuery()) {

                while (rs.next()) {
                    String assetCode = rs.getString("asset_id");
                    byte[] meta = rs.getBytes("meta");
                    int iteratedType = rs.getInt("asset_type");

                    Asset asset = Asset.assetFromMeta(assetCode, iteratedType, meta);

                    double quantity = rs.getDouble("quantity");

                    double value;
                    if(!Objects.equals(assetCode, Configuration.MAINCURRENCYASSET.getCode())){
                        value = rs.getDouble("value");
                    }else {
                        balances.addFirst(new AssetCache(asset, new Candle(0, 1, 0,0, quantity)));
                        continue;
                    }

                    balances.add(new AssetCache(asset, new Candle(0, value, 0,0, quantity)));
                }

                if(balances.isEmpty() || !Objects.equals(balances.getFirst().getAsset().getCode(), Configuration.MAINCURRENCYASSET.getCode())){
                    balances.addFirst(new AssetCache(Configuration.MAINCURRENCYASSET, new Candle(0, 1, 0,0, 0)));
                }

            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return balances;
    }

}
