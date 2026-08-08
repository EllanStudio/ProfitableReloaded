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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

public class Assets {

    public static boolean registerAsset(World world, String symbol, int assetType, byte[] meta) {

        if(Objects.equals(symbol, VaultHook.getAsset().getCode())){
            return false;
        }

        String sql = "INSERT INTO assets (world, asset_id, asset_type, meta) VALUES (?, ?, ?, ?)";

        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
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
        String sql = "INSERT " + (Profitable.getInstance().getConfig().getInt("database.database-type") == 0 ? "OR ": "") + "IGNORE INTO assets (world, asset_id, asset_type, meta) VALUES (?, ?, ?, ?)";

        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
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
        String sql = "UPDATE assets SET asset_id = ?, meta = ? WHERE world = ? AND asset_id = ?;";

        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {

            stmt.setString(1,updatedAsset.getCode());
            stmt.setBytes(2, Asset.metaData(updatedAsset));
            stmt.setBytes(3, MessagingUtil.getWorldId(world));
            stmt.setString(4, assetID);

            boolean success = stmt.executeUpdate() > 0;
            if (success) {
                AssetDataCache.invalidate(world, assetID);
                AssetDataCache.invalidate(world, updatedAsset.getCode());
            }
            return success;

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

        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
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

    public static Collection<String> getAssetCodeType(World world, int type) {
        String sql = "SELECT asset_id FROM assets WHERE world = ? AND asset_type = ?;";

        Collection<String> assetsFound = new ArrayList<>();
        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
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
        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
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
        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
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
        String sql = "DELETE FROM assets WHERE world = ? AND asset_id = ?;";

        try (PreparedStatement stmt = DataBase.getConnection().prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, asset);
            int affected = stmt.executeUpdate();
            if (affected > 0) {
                AssetDataCache.invalidate(world, asset);
            }
            return affected > 0;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
            return false;
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
