package com.faridfaharaj.profitable.data.tables;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.*;
import java.util.logging.Level;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Accounts {

    private static final AccountSessionRegistry currentAccounts = new AccountSessionRegistry();

    public static Map<UUID, String> getCurrentAccounts(){
        return currentAccounts.view();
    }

    public static String getAccount(Player player){

        return currentAccounts.computeIfAbsent(player.getUniqueId(),
                k -> {
            String uuidString = k.toString();
            if (!registerDefaultAccount(player.getWorld(), uuidString)) {
                throw new IllegalStateException("Could not create the default account for " + k);
            }
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("account.login",
                    Map.entry("%account%", Profitable.getLang().getString("account.default-name"))
                    ));
            publishLogin(k, uuidString);
            return uuidString;
        }

        );

    }

    public static String getAccount(World world, UUID playerId) {
        return currentAccounts.computeIfAbsent(playerId, key -> {
            String uuidString = key.toString();
            if (!registerDefaultAccount(world, uuidString)) {
                throw new IllegalStateException("Could not create the default account for " + key);
            }
            publishLogin(key, uuidString);
            return uuidString;
        });
    }

    public static String ensureDefaultAccount(World world, UUID playerId) {
        String account = playerId.toString();
        if (!registerDefaultAccount(world, account)) {
            throw new IllegalStateException("Could not create the default account for " + playerId);
        }
        String previous = currentAccounts.putIfAbsent(playerId, account);
        if (previous == null) {
            publishLogin(playerId, account);
            return account;
        }
        return previous;
    }

    public static int nextClaimID(){
        return ThreadLocalRandom.current().nextInt(100, Integer.MAX_VALUE);
    }

    public static boolean registerAccount(World world, String name, String password) {
        if (name == null || !name.matches("[A-Za-z0-9_]{3,36}") || password == null
                || password.length() < 8 || password.length() > 31) {
            return false;
        }
        String sql = "INSERT INTO accounts (world ,account_name, password, salt, item_delivery_pos, entity_delivery_pos, entity_claim_id) VALUES (?, ?, ?, ?, ?, ?, ?)";

        int claimid = nextClaimID();

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            byte[][] hashedpassword = hashPassword(password);

            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, name);
            stmt.setBytes(3, hashedpassword[0]);
            stmt.setBytes(4, hashedpassword[1]);

            stmt.setObject(5, null, Types.BINARY);
            stmt.setObject(6, null, Types.BINARY);

            stmt.setInt(7, claimid);

            stmt.executeUpdate();

            return true;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return false;
    }

    public static boolean registerDefaultAccount(World world, String name) {
        String sql = "INSERT " + (!DataBase.isMySQL() ? "OR ": "") + "IGNORE INTO accounts (world, account_name, password, salt, item_delivery_pos, entity_delivery_pos, entity_claim_id) VALUES (? ,?, ?, ?, ?, ?, ?)";
        String initialHoldingSql = "INSERT " + (!DataBase.isMySQL() ? "OR ": "")
                + "IGNORE INTO account_assets (world, account_name, asset_id, quantity) VALUES (?, ?, ?, ?)";

        int claimid = nextClaimID();
        byte[] worldId = MessagingUtil.getWorldId(world);

        try (Connection connection = DataBase.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.setBytes(1, worldId);
                    stmt.setString(2, name);
                    stmt.setNull(3, Types.BINARY);
                    stmt.setNull(4, Types.BINARY);
                    stmt.setObject(5, null, Types.BLOB);
                    stmt.setObject(6, null, Types.BLOB);
                    stmt.setInt(7, claimid);
                    stmt.executeUpdate();
                }

                double initialBalance = Profitable.getInstance().getConfig()
                        .getDouble("main-currency.initial-balance");
                if (Double.isFinite(initialBalance) && initialBalance > 0) {
                    try (PreparedStatement holding = connection.prepareStatement(initialHoldingSql)) {
                        holding.setBytes(1, worldId);
                        holding.setString(2, name);
                        holding.setString(3, Configuration.MAINCURRENCYASSET.getCode());
                        holding.setDouble(4, initialBalance);
                        holding.executeUpdate();
                    }
                }

                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
            return false;
        }
    }

    public static Map.Entry<byte[], byte[]> getPasswordHash(World world, String name){

        String sql = "SELECT * FROM accounts WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, name);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] pasword = rs.getBytes("password");
                    byte[] salt = rs.getBytes("salt");
                    if(pasword == null || salt == null){
                        return null;
                    }

                    return Map.entry(pasword, salt);
                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return null;

    }

    public static boolean changePassword(World world, String name, String password) {
        if (isProtectedAccount(name) || password == null || password.length() < 8 || password.length() > 31) {
            return false;
        }
        String sql = "UPDATE accounts SET password = ?, salt = ? WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            byte[][] hashedpassword = hashPassword(password);

            stmt.setBytes(1, hashedpassword[0]);
            stmt.setBytes(2, hashedpassword[1]);

            stmt.setBytes(3, MessagingUtil.getWorldId(world));
            stmt.setString(4, name);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return false;
    }

    public static boolean changeItemDelivery(World world, String name, Location location) {

        String sql = "UPDATE accounts SET item_delivery_pos = ? WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setBytes(1, encodeLocation(location));

            stmt.setBytes(2, MessagingUtil.getWorldId(world));
            stmt.setString(3, name);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    public static boolean changeEntityDelivery(World world, String name, Location location) {
        String sql = "UPDATE accounts SET entity_delivery_pos = ? WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setBytes(1, encodeLocation(location));

            stmt.setBytes(2, MessagingUtil.getWorldId(world));
            stmt.setString(3, name);

            return stmt.executeUpdate() > 0;

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return false;
    }

    public static String getEntityClaimId(World world, String name) {
        String sql = "SELECT entity_claim_id FROM accounts WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, name);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {

                    return ("§eE "+rs.getInt("entity_claim_id"));

                }
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return null;
    }

    public static Location getItemDelivery(World world, String name) {
        String sql = "SELECT item_delivery_pos FROM accounts WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, name);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] itemDeliveryPos = rs.getBytes("item_delivery_pos");

                    if (!rs.wasNull()) {

                        return decodeLocation(itemDeliveryPos);

                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return null;
    }

    public static Location getEntityDelivery(World world, String name) {
        String sql = "SELECT entity_delivery_pos FROM accounts WHERE world = ? AND account_name = ?;";

        try (Connection connection = DataBase.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setBytes(1, MessagingUtil.getWorldId(world));
            stmt.setString(2, name);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    byte[] itemDeliveryPos = rs.getBytes("entity_delivery_pos");

                    if (!rs.wasNull()) {

                        return decodeLocation(itemDeliveryPos);

                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        } catch (SQLException e) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "SQL error", e);
        }

        return null;
    }

    public static boolean deleteAccount(World world, String account) {
        if ("server".equalsIgnoreCase(account) || isUuid(account)) {
            return false;
        }
        byte[] worldId = MessagingUtil.getWorldId(world);
        try (Connection connection = DataBase.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                String lockSql = DataBase.isMySQL()
                        ? "SELECT account_name FROM accounts WHERE world = ? AND account_name = ? FOR UPDATE"
                        : "UPDATE accounts SET account_name = account_name WHERE world = ? AND account_name = ?";
                boolean exists;
                try (PreparedStatement lock = connection.prepareStatement(lockSql)) {
                    lock.setBytes(1, worldId);
                    lock.setString(2, account);
                    if (DataBase.isMySQL()) {
                        try (ResultSet result = lock.executeQuery()) {
                            exists = result.next();
                        }
                    } else {
                        exists = lock.executeUpdate() == 1;
                    }
                }
                if (!exists || hasUnsettledAccountState(connection, worldId, account)
                        || hasPositiveAccountHoldings(connection, worldId, account)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM accounts WHERE world = ? AND account_name = ?")) {
                    delete.setBytes(1, worldId);
                    delete.setString(2, account);
                    if (delete.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                connection.commit();
                return true;
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException error) {
            Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not safely delete account", error);
            return false;
        }
    }

    private static boolean hasUnsettledAccountState(Connection connection, byte[] worldId, String account)
            throws SQLException {
        String sql = "SELECT 1 FROM orders WHERE world = ? AND owner = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, account);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return true;
                }
            }
        }
        sql = "SELECT 1 FROM delivery_outbox WHERE world = ? AND account_name = ? "
                + "AND status <> 'COMPLETED' LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, worldId);
            statement.setString(2, account);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean hasPositiveAccountHoldings(Connection connection, byte[] worldId, String account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM account_assets WHERE world = ? AND account_name = ? AND quantity > 0 LIMIT 1")) {
            statement.setBytes(1, worldId);
            statement.setString(2, account);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public static void logOut(UUID playerid){
        String removed = currentAccounts.remove(playerid);
        if (removed != null && Profitable.getRedisManager() != null && Profitable.getRedisManager().isConnected()) {
            Profitable.getRedisManager().publishAsync("player_logout", playerid.toString());
        }
    }

    /** Removes the local session without publishing a Redis event (used by the Redis subscriber itself). */
    public static void logOutLocal(UUID playerid){
        currentAccounts.remove(playerid);
    }

    /** Clears all local-only session state during plugin shutdown or tests. */
    public static void clearLocalSessions() {
        currentAccounts.clear();
    }

    public static boolean logIn(Player player, String name, String password){

        if(comparePasswords(player.getWorld(), name, password)){
            currentAccounts.put(player.getUniqueId(), name);
            publishLogin(player.getUniqueId(), name);
            return true;
        }

        return false;
    }

    public static boolean logIn(World world, UUID playerId, String name, String password){

        if(comparePasswords(world, name, password)){
            currentAccounts.put(playerId, name);
            publishLogin(playerId, name);
            return true;
        }

        return false;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void publishLogin(UUID playerId, String account) {
        if (Profitable.getRedisManager() != null && Profitable.getRedisManager().isConnected()) {
            Profitable.getRedisManager().publish("player_login", playerId + ":" + account);
        }
    }

    public static boolean comparePasswords(World world, String name, String password){
        if (isProtectedAccount(name) || password == null) {
            return false;
        }
        Map.Entry<byte[], byte[]> hashedpassword  = getPasswordHash(world, name);

        if(hashedpassword == null || hashedpassword.getKey() == null || hashedpassword.getValue() == null){
            return false;
        }

        byte[] comparedHash = hashPassword(password, hashedpassword.getValue());

        // Use constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(hashedpassword.getKey(), comparedHash);
    }

    public static byte[][] hashPassword(String password) {
        int iterations = 10000;
        int keyLength = 128;
        char[] chars = password.toCharArray();

        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        try {
            PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, keyLength);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();

            byte[][] hashes = new byte[2][];

            hashes[0] = hash;
            hashes[1] = salt;

            return hashes;

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    public static byte[] hashPassword(String password, byte[] salt) {
        int iterations = 10000;
        int keyLength = 128;
        char[] chars = password.toCharArray();

        try {
            PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, keyLength);
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }

    public static byte[] encodeLocation(Location location) throws IOException {
        UUID worlduid = location.getWorld().getUID();
        ByteBuffer buffer = ByteBuffer.allocate(40); // 16 bytes for UUID + 3 doubles (8 bytes each)

        buffer.putLong(worlduid.getMostSignificantBits());
        buffer.putLong(worlduid.getLeastSignificantBits());
        buffer.putDouble(location.getX());
        buffer.putDouble(location.getY());
        buffer.putDouble(location.getZ());

        return buffer.array();
    }

    public static Location decodeLocation(byte[] locationBytes) throws IOException {


        if(locationBytes == null){
            return null;
        }
        if (locationBytes.length != 40) {
            throw new IOException("Invalid encoded delivery location length: " + locationBytes.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(locationBytes);

        World world = Profitable.getInstance().getServer().getWorld(new UUID(buffer.getLong(),buffer.getLong()));

        if(world == null){
            return null;
        }

        return new Location(world, buffer.getDouble(), buffer.getDouble(), buffer.getDouble());

    }

    /** Server-owned and UUID default accounts are intentionally not password-login accounts. */
    public static boolean isProtectedAccount(String account) {
        if (account == null || "server".equalsIgnoreCase(account)) {
            return true;
        }
        return isUuid(account);
    }

}
