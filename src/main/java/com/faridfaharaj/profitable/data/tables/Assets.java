package com.faridfaharaj.profitable.data.tables;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.hooks.PlayerPointsHook;
import com.faridfaharaj.profitable.hooks.VaultHook;
import com.faridfaharaj.profitable.util.MessagingUtil;
import com.faridfaharaj.profitable.util.NamingUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.World;

import java.io.ByteArrayInputStream;
import java.util.logging.Level;
import java.io.DataInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class Assets {

    public static boolean registerAsset(World world, String symbol, int assetType, byte[] meta) {

                if(VaultHook.isConnected() && Objects.equals(symbol, VaultHook.getAsset().getCode())){
            return false;
        }

        String sql = "INSERT INTO assets (world, asset_id, asset_type, meta) VALUES (?, ?, ?, ?)";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, symbol);
            stmt.setInt(3, assetType);
            stmt.setBytes(4, meta);

            stmt.executeUpdate();
            AssetDataCache.invalidate(world, symbol);
            return true;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return false;
    }

    public static void addAsset(World world, String ticker, int assetType, byte[] meta) {
        String sql = "INSERT " + (!DataBase.isMySQL() ? "OR ": "") + "IGNORE INTO assets (world, asset_id, asset_type, meta) VALUES (?, ?, ?, ?)";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, ticker);
            stmt.setInt(3, assetType);
            stmt.setBytes(4, meta);

            stmt.executeUpdate();

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }
    }

    public static boolean updateAsset(World world, String assetID, Asset updatedAsset){
        byte[] worldId = MessagingUtil.getWorldId(world);
        try (Connection connection = DataBase.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String lockSql = DataBase.isMySQL()
                        ? "SELECT asset_id FROM assets WHERE world = ? AND asset_id = ? FOR UPDATE"
                        : "UPDATE assets SET asset_id = asset_id WHERE world = ? AND asset_id = ?";
                boolean exists;
                try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
                    lock.setBytes(1, worldId);
                    lock.setString(2, assetID);
                    if (DataBase.isMySQL()) {
                        try (ResultSet result = lock.executeQuery()) {
                            exists = result.next();
                        }
                    } else {
                        exists = lock.executeUpdate() == 1;
                    }
                }
                if (!exists) {
                    connection.rollback();
                    return false;
                }

                boolean renaming = !assetID.equals(updatedAsset.getCode());
                if (renaming && hasUnsettledAssetState(connection, worldId, assetID)) {
                    connection.rollback();
                    return false;
                }

                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE assets SET asset_id = ?, meta = ? WHERE world = ? AND asset_id = ?")) {
                    update.setString(1, updatedAsset.getCode());
                    update.setBytes(2, Asset.metaData(updatedAsset));
                    update.setBytes(3, worldId);
                    update.setString(4, assetID);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                AssetDataCache.invalidate(world, assetID);
                AssetDataCache.invalidate(world, updatedAsset.getCode());
                return true;
            } catch (SQLException | IOException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    public static Asset getAssetData(World world, String assetID) {
        // Check cache first
        Asset cached = AssetDataCache.get(world, assetID);
        if (cached != null) {
            return cached;
        }

        String sql = "SELECT asset_type, meta FROM assets WHERE world = ? AND asset_id = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, assetID);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()){

                    byte[] meta = rs.getBytes("meta");
                    TextColor color;
                    String name;

                    List<String> stringList = new ArrayList<>();
                    List<Double> numericList = new ArrayList<>();

                    try (ByteArrayInputStream bis = new ByteArrayInputStream(meta);
                         DataInputStream dis = new DataInputStream(bis)) {

                        color = TextColor.color(dis.readInt());
                        name = dis.readUTF();


                        int lengthStrings = dis.readInt();
                        if(lengthStrings > 0){
                            for(int i = 0; i<lengthStrings; i++){
                                stringList.add(dis.readUTF());
                            }
                        }

                        int lengthNumeric = dis.readInt();
                        if(lengthNumeric > 0){
                            for(int i = 0; i<lengthNumeric; i++){
                                numericList.add(dis.readDouble());
                            }
                        }


                    } catch (IOException e) {
                        color = NamedTextColor.WHITE;
                        name = assetID.toLowerCase();
                    }

                    Asset asset = new Asset(assetID, rs.getInt("asset_type"), color, name, stringList, numericList);
                    // Cache the result
                    AssetDataCache.put(world, assetID, asset);
                    return asset;
                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return null;
    }

    /**
     * Loads asset metadata from an already captured database world id. This
     * overload is safe for asynchronous coordination callbacks because it does
     * not access a Bukkit {@link World} or the world-keyed runtime cache.
     */
    public static Asset getAssetData(byte[] worldId, String assetID) {
        if (worldId == null || worldId.length != 16 || assetID == null || assetID.isBlank()) {
            return null;
        }
        try (Connection connection = DataBase.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT asset_type, meta FROM assets WHERE world = ? AND asset_id = ?")) {
            statement.setBytes(1, worldId);
            statement.setString(2, assetID);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Asset.assetFromMeta(assetID, result.getInt("asset_type"), result.getBytes("meta"));
                }
            }
        } catch (SQLException error) {
            Profitable.getInstance().getLogger().log(Level.SEVERE,
                    "Could not load asset metadata for a remote trade notice", error);
        }
        return null;
    }

    public static Collection<String> getAssetCodeType(World world, int type) {
        String sql = "SELECT asset_id FROM assets WHERE world = ? AND asset_type = ?;";

        Collection<String> assetsFound = new ArrayList<>();
        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setInt(2, type);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()){
                    assetsFound.add(rs.getString("asset_id"));
                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return assetsFound;
    }

    public static List<Asset> getAssetFancyType(World world, int type) {
        String sql = "SELECT * FROM assets WHERE world = ? AND asset_type = ?;";

        List<Asset> assetsFound = new ArrayList<>();
        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setInt(2, type);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()){
                    assetsFound.add(
                        Asset.assetFromMeta(rs.getString("asset_id"), rs.getInt("asset_type"), rs.getBytes("meta"))
                    );
                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return assetsFound;
    }

    public static Collection<String> getAll(World world) {
        String sql = "SELECT asset_id FROM assets WHERE world = ?;";

        Collection<String> assetsFound = new ArrayList<>();
        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()){
                    assetsFound.add(rs.getString("asset_id"));
                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return assetsFound;
    }

    public static boolean deleteAsset(World world, String asset) {
        byte[] worldId = MessagingUtil.getWorldId(world);
        try (Connection connection = DataBase.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String lockSql = DataBase.isMySQL()
                        ? "SELECT asset_id FROM assets WHERE world = ? AND asset_id = ? FOR UPDATE"
                        : "UPDATE assets SET asset_id = asset_id WHERE world = ? AND asset_id = ?";
                boolean exists;
                try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
                    lock.setBytes(1, worldId);
                    lock.setString(2, asset);
                    if (DataBase.isMySQL()) {
                        try (ResultSet result = lock.executeQuery()) {
                            exists = result.next();
                        }
                    } else {
                        exists = lock.executeUpdate() == 1;
                    }
                }
                if (!exists || hasUnsettledAssetState(connection, worldId, asset)
                        || hasPositiveAssetHoldings(connection, worldId, asset)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM assets WHERE world = ? AND asset_id = ?")) {
                    delete.setBytes(1, worldId);
                    delete.setString(2, asset);
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                AssetDataCache.invalidate(world, asset);
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException error) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not safely delete asset", error);
            return false;
        }
    }

    private static boolean hasUnsettledAssetState(Connection connection, byte[] worldId, String asset)
            throws SQLException {
        String sql = "SELECT 1 FROM orders WHERE world = ? AND asset_id = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, asset);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return true;
                }
            }
        }
        sql = "SELECT 1 FROM delivery_outbox WHERE world = ? AND asset_id = ? "
                + "AND status <> 'COMPLETED' LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, asset);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean hasPositiveAssetHoldings(Connection connection, byte[] worldId, String asset)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM account_assets WHERE world = ? AND asset_id = ? AND quantity > 0 LIMIT 1")) {
            statement.setBytes(1, worldId);
            statement.setString(2, asset);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public static void generateAssets(World world){

        //Hooks asset generation----
        if(VaultHook.isConnected()){
            // Vault
            try {
                Assets.addAsset(world,VaultHook.getAsset().getCode(), 1, Asset.metaData(VaultHook.getAsset()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        if(PlayerPointsHook.isConnected()){
            // PlayerPoints
            try {
                Assets.addAsset(world,PlayerPointsHook.getAsset().getCode(), 1, Asset.metaData(PlayerPointsHook.getAsset()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try{
            Configuration.loadMainCurrency(world);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if(Configuration.GENERATEASSETS){
            //Base commodity items
            for(String item : Configuration.ALLOWEITEMS){
                try {
                    Assets.addAsset(world, item, 2, Asset.metaData(Configuration.COLORHIGHLIGHT.value(), NamingUtil.nameCommodity(item)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            //Base commodity entities
            for(String entity : Configuration.ALLOWENTITIES){
                try {
                    Assets.addAsset(world, entity, 3, Asset.metaData(Configuration.COLORHIGHLIGHT.value(), NamingUtil.nameCommodity(entity)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

    }

}
