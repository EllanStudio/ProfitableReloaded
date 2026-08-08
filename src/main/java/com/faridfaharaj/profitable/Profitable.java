package com.faridfaharaj.profitable;

import com.faridfaharaj.profitable.commands.*;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.data.tables.AssetDataCache;
import com.faridfaharaj.profitable.data.tables.Candles;
import com.faridfaharaj.profitable.data.tables.Orders;
import com.faridfaharaj.profitable.redis.RedisManager;
import com.faridfaharaj.profitable.tasks.TemporalItems;
import com.tcoded.folialib.FoliaLib;

import com.faridfaharaj.profitable.data.DataBase;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/*                                                                            *
 *   Profitable is an exchange trading simulation plugin for minecraft.       *
 *   Copyright (C) 2025  faridfaharaj                                         *
 *                                                                            *
 *   This program is free software: you can redistribute it and/or modify     *
 *   it under the terms of the GNU General Public License as published by     *
 *   the Free Software Foundation, either version 3 of the License, or        *
 *   (at your option) any later version.                                      *
 *                                                                            *
 *   This program is distributed in the hope that it will be useful,          *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of           *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            *
 *   GNU General Public License for more details.                             *
 *                                                                            *
 *   You should have received a copy of the GNU General Public License        *
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.   *
 */
public final class Profitable extends JavaPlugin {

    private static Profitable instance;
    private static FoliaLib foliaLib;
    private static Lang lang;
    private static RedisManager redisManager;

    public static Profitable getInstance() {
        return instance;
    }

    public static Lang getLang(){
        return lang;
    }

    public static FoliaLib getfolialib() {
        return foliaLib;
    }

    public static RedisManager getRedisManager() {
        return redisManager;
    }

    @Override
    public void onEnable() {
        getLogger().info("====================    Profitable    ====================" );

        instance = this;
        foliaLib = new FoliaLib(this);
        try {
            lang = new Lang(this);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        foliaLib.getScheduler().runAsync(task -> {
            checkForUpdate(this);
        });

        //config-----------------
        Configuration.loadConfig(this);

        //REDIS (optional, for Velocity/multi-server synchronization)---------
        if (getConfig().getBoolean("redis.enabled", false)) {
            String redisHost = getConfig().getString("redis.host", "localhost");
            int redisPort = getConfig().getInt("redis.port", 6379);
            String redisPassword = getConfig().getString("redis.password", "");
            String redisPrefix = getConfig().getString("redis.channel-prefix", "profitable");
            int reconnectAttempts = getConfig().getInt("redis.reconnect-attempts", 5);
            long reconnectDelayMs = getConfig().getLong("redis.reconnect-delay-ms", 2000);
            boolean syncOrders = getConfig().getBoolean("redis.sync-orders", true);
            redisManager = new RedisManager(redisHost, redisPort, redisPassword, redisPrefix, reconnectAttempts, reconnectDelayMs);

            // Subscriptions are registered unconditionally: if the initial connection
            // fails, RedisManager restores them automatically once reconnected.
            {
                // Invalidate local account session when another server logs this player in
                redisManager.subscribe("player_login", message -> {
                    // message format: "playerId:accountName"
                    String[] parts = message.split(":", 2);
                    if (parts.length == 2) {
                        try {
                            UUID playerId = UUID.fromString(parts[0]);
                            Accounts.logOutLocal(playerId);
                            TemporalItems.holdingTemp.remove(playerId);
                        } catch (IllegalArgumentException ignored) {}
                    }
                });

                // Invalidate local account session when another server logs this player out
                redisManager.subscribe("player_logout", message -> {
                    try {
                        UUID playerId = UUID.fromString(message.trim());
                        Accounts.logOutLocal(playerId);
                        TemporalItems.holdingTemp.remove(playerId);
                    } catch (IllegalArgumentException ignored) {}
                });

                // Update local candle data when another server executes a trade
                // message format: "worldName:assetCode:price:units:fullTime"
                redisManager.subscribe("trade_executed", message -> {
                    String[] parts = message.split(":", 5);
                    if (parts.length == 5) {
                        try {
                            String worldName = parts[0];
                            String assetCode = parts[1];
                            double price = Double.parseDouble(parts[2]);
                            double units = Double.parseDouble(parts[3]);
                            World world = getServer().getWorld(worldName);
                            if (world != null) {
                                Candles.updateDay(world, assetCode, price, units);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                });

                // Sync order book when another server cancels an order
                if (syncOrders) {
                    redisManager.subscribe("order_cancelled", message -> {
                        // message format: "worldName:orderUUID"
                        String[] parts = message.split(":", 2);
                        if (parts.length == 2) {
                            try {
                                String worldName = parts[0];
                                UUID orderUUID = UUID.fromString(parts[1]);
                                World world = getServer().getWorld(worldName);
                                if (world != null) {
                                    // Remove the order from local order book
                                    Orders.cancelOrder(world, orderUUID);
                                }
                            } catch (IllegalArgumentException ignored) {}
                        }
                    });
                }

                // Sync balance changes across servers (optional, higher overhead)
                if (getConfig().getBoolean("redis.sync-balances", false)) {
                    redisManager.subscribe("balance_changed", message -> {
                        // message format: "worldName:accountName:assetCode:newBalance"
                        String[] parts = message.split(":", 4);
                        if (parts.length == 4) {
                            try {
                                String worldName = parts[0];
                                String accountName = parts[1];
                                String assetCode = parts[2];
                                double newBalance = Double.parseDouble(parts[3]);
                                World world = getServer().getWorld(worldName);
                                if (world != null) {
                                    com.faridfaharaj.profitable.data.tables.AccountHoldings.setHolding(world, accountName, assetCode, newBalance);
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    });
                }

                // Sync new asset registrations across servers
                redisManager.subscribe("asset_registered", message -> {
                    // message format: "worldName:assetCode:assetType"
                    String[] parts = message.split(":", 3);
                    if (parts.length == 3) {
                        try {
                            String worldName = parts[0];
                            String assetCode = parts[1];
                            int assetType = Integer.parseInt(parts[2]);
                            World world = getServer().getWorld(worldName);
                            if (world != null) {
                                // Register the asset locally if it doesn't exist
                                Assets.addAsset(world, assetCode, assetType, new byte[0]);
                                AssetDataCache.invalidate(world, assetCode);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                });

                // Sync asset deletions across servers
                redisManager.subscribe("asset_deleted", message -> {
                    // message format: "worldName:assetCode"
                    String[] parts = message.split(":", 2);
                    if (parts.length == 2) {
                        String worldName = parts[0];
                        String assetCode = parts[1];
                        World world = getServer().getWorld(worldName);
                        if (world != null) {
                            Assets.deleteAsset(world, assetCode);
                            AssetDataCache.invalidate(world, assetCode);
                        }
                    }
                });

                // Sync asset edits across servers
                redisManager.subscribe("asset_updated", message -> {
                    // message format: "worldName:oldCode:newCode:assetType:colorRgb:name"
                    String[] parts = message.split(":", 6);
                    if (parts.length == 6) {
                        try {
                            String worldName = parts[0];
                            String oldCode = parts[1];
                            String newCode = parts[2];
                            int assetType = Integer.parseInt(parts[3]);
                            int colorRgb = Integer.parseInt(parts[4]);
                            String name = parts[5];
                            World world = getServer().getWorld(worldName);
                            if (world != null) {
                                Assets.deleteAsset(world, oldCode);
                                Asset updated = new Asset(newCode, assetType, TextColor.color(colorRgb), name);
                                Assets.addAsset(world, newCode, assetType, Asset.metaData(updated));
                                AssetDataCache.invalidate(world, oldCode);
                                AssetDataCache.invalidate(world, newCode);
                            }
                        } catch (NumberFormatException | IOException ignored) {}
                    }
                });
            }
        }

        //DATABASE---------
        try {

            switch (getConfig().getInt("database.database-type")){
                case 0:
                    DataBase.connectSQLite();
                    getLogger().info("Connected to SQLite database");
                    break;
                case 1:
                    DataBase.connectMySQL();
                    getLogger().info("Connected to MySQL database");
                    break;

            }
            DataBase.migrateDatabase(DataBase.getConnection());

        } catch (SQLException e) {
            getLogger().severe("Error loading database");
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // MainCurrency
        if(Configuration.MULTIWORLD){
            for(World world : this.getServer().getWorlds()){
                Assets.generateAssets(world);
                Accounts.registerDefaultAccount(world, "server");
                Accounts.changeEntityDelivery(world, "server", new Location(Profitable.getInstance().getServer().getWorlds().getFirst(), 0, 0 ,0));
                Accounts.changeItemDelivery(world, "server", new Location(Profitable.getInstance().getServer().getWorlds().getFirst(), 0, 0 ,0));
            }
            getLogger().info("Using per-world data");
        }else{
            World world = getServer().getWorlds().getFirst();
            Assets.generateAssets(world);
            Accounts.registerDefaultAccount(world, "server");
            Accounts.changeEntityDelivery(world, "server", new Location(Profitable.getInstance().getServer().getWorlds().getFirst(), 0, 0 ,0));
            Accounts.changeItemDelivery(world, "server", new Location(Profitable.getInstance().getServer().getWorlds().getFirst(), 0, 0 ,0));
            getLogger().info("Using single server-wide data");
        }

        getLogger().info("Using " + Configuration.MAINCURRENCYASSET.getCode() + " as main currency on the exchange");

        //commands-------------------------
        getCommand("buy").setExecutor(new TransactCommand());
        getCommand("buy").setTabCompleter(new TransactCommand.CommandTabCompleter());

        getCommand("sell").setExecutor(new TransactCommand());
        getCommand("sell").setTabCompleter(new TransactCommand.CommandTabCompleter());


        getCommand("assets").setExecutor(new AssetsCommand());
        getCommand("assets").setTabCompleter(new AssetsCommand.CommandTabCompleter());

        getCommand("top").setExecutor(new TopCommand());
        getCommand("top").setTabCompleter(new TopCommand.CommandTabCompleter());


        getCommand("account").setExecutor(new AccountCommand());
        getCommand("account").setTabCompleter(new AccountCommand.CommandTabCompleter());

        getCommand("wallet").setExecutor(new WalletCommand());
        getCommand("wallet").setTabCompleter(new WalletCommand.CommandTabCompleter());

        getCommand("orders").setExecutor(new OrdersCommand());
        getCommand("orders").setTabCompleter(new OrdersCommand.CommandTabCompleter());

        getCommand("price").setExecutor(new PriceCommand());
        getCommand("price").setTabCompleter(new PriceCommand.CommandTabCompleter());

        getCommand("delivery").setExecutor(new DeliveryCommand());
        getCommand("delivery").setTabCompleter(new DeliveryCommand.CommandTabCompleter());

        getCommand("claimtag").setExecutor(new ClaimtagCommand());


        getCommand("admin").setExecutor(new AdminCommand());
        getCommand("admin").setTabCompleter(new AdminCommand.CommandTabCompleter());


        getCommand("help").setExecutor(new HelpCommand());
        getCommand("help").setTabCompleter(new HelpCommand.CommandTabCompleter());

        //event handler------------------
        getServer().getPluginManager().registerEvents(new Events(), this);



        getLogger().info("==========    Profitable is ready to profit!    ==========" );
    }

    @Override
    public void onDisable() {
        if (redisManager != null) {
            redisManager.shutdown();
        }

        try {
            DataBase.closeConnection();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        for(UUID playerid:TemporalItems.holdingTemp.keySet()){

            Player player = getServer().getPlayer(playerid);
            if(player != null){
                TemporalItems.removeTempItem(player);
            }
        }
    }

    public void checkForUpdate(Profitable plugin) {
        try {
            String projectSlug = "profitable";
            String url = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }
            reader.close();

            String json = jsonBuilder.toString();
            Pattern pattern = Pattern.compile("\"version_number\"\\s*:\\s*\"(.*?)\"");
            Matcher matcher = pattern.matcher(json);

            if (matcher.find()) {
                String latestVersion = matcher.group(1).replace("v", "");
                String currentVersion = plugin.getPluginMeta().getVersion();

                if (!latestVersion.equalsIgnoreCase(currentVersion)) {
                    plugin.getLogger().warning("UPDATE AVAILABLE! Latest: " + latestVersion);
                    plugin.getLogger().info("Download latest here: https://modrinth.com/plugin/profitable");
                } else {
                    plugin.getLogger().info("Up to date!");
                }
            } else {
                plugin.getLogger().warning("Could not parse version from Modrinth API response.");
            }

        } catch (Exception ex) {
            plugin.getLogger().warning("Could not check for updates: " + ex.getMessage());
        }
    }

}
