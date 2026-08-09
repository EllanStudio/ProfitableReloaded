package com.faridfaharaj.profitable;

import com.faridfaharaj.profitable.commands.*;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.data.tables.AssetDataCache;
import com.faridfaharaj.profitable.data.settlement.DeliveryOutboxWorker;
import com.faridfaharaj.profitable.exchange.Books.Exchange;
import com.faridfaharaj.profitable.redis.RedisManager;
import com.faridfaharaj.profitable.tasks.TemporalItems;
import com.tcoded.folialib.FoliaLib;

import com.faridfaharaj.profitable.data.DataBase;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;
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
 *   ProfitableReloaded is an exchange trading simulation plugin.             *
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
    private static DeliveryOutboxWorker deliveryOutboxWorker;
    private static boolean redisConfiguredAtStartup;
    private static boolean redisRequiredAtStartup;
    private Map<String, Object> activeInfrastructureSettings = Map.of();

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

    /** Schedules an immediate best-effort drain after a settlement or cancellation commits. */
    public static void wakeDeliveryOutbox() {
        if (deliveryOutboxWorker != null) {
            deliveryOutboxWorker.wakeUp();
        }
    }

    /** Whether new market risk may be accepted under the startup Redis policy. */
    public static boolean isTradingReady() {
        return !redisConfiguredAtStartup || !redisRequiredAtStartup
                || (redisManager != null && redisManager.isConnected());
    }

    @Override
    public void onEnable() {
        getLogger().info("================    ProfitableReloaded    ================" );

        instance = this;
        foliaLib = new FoliaLib(this);
        saveDefaultConfig();
        Configuration.loadConfig(this);
        activeInfrastructureSettings = infrastructureSettings(getConfig());
        redisConfiguredAtStartup = getConfig().getBoolean("redis.enabled", false);
        redisRequiredAtStartup = getConfig().getBoolean("redis.required", true);
        try {
            lang = new Lang(this);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (getConfig().getBoolean("update-check.enabled", false)) {
            foliaLib.getScheduler().runAsync(task -> checkForUpdate(this));
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
                default:
                    throw new IllegalArgumentException("database.database-type must be 0 (SQLite) or 1 (MySQL)");

            }
            DataBase.migrateDatabase();

        } catch (SQLException e) {
            getLogger().severe("Error loading database");
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

        initializeRedis();
        String outboxServerId = redisManager == null ? "local" : redisManager.getServerId();
        deliveryOutboxWorker = new DeliveryOutboxWorker(outboxServerId);
        deliveryOutboxWorker.start();

        getLogger().info("Using " + Configuration.MAINCURRENCYASSET.getCode() + " as main currency on the exchange");

        //commands-------------------------
        getCommand("buy").setExecutor(new TransactCommand());
        getCommand("buy").setTabCompleter(new TransactCommand.CommandTabCompleter());

        getCommand("sell").setExecutor(new TransactCommand());
        getCommand("sell").setTabCompleter(new TransactCommand.CommandTabCompleter());


        getCommand("assets").setExecutor(new AssetsCommand());
        getCommand("assets").setTabCompleter(new AssetsCommand.CommandTabCompleter());

        getCommand("trade").setExecutor(new TradeCommand());
        getCommand("trade").setTabCompleter(new TradeCommand.CommandTabCompleter());

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



        getLogger().info("=======    ProfitableReloaded is ready to profit!    =======" );
    }

    private void initializeRedis() {
        if (!getConfig().getBoolean("redis.enabled", false)) {
            redisManager = null;
            return;
        }

        redisManager = new RedisManager(
                getConfig().getString("redis.host", "localhost"),
                getConfig().getInt("redis.port", 6379),
                getConfig().getString("redis.password", ""),
                getConfig().getString("redis.channel-prefix", "profitable"),
                getConfig().getString("redis.server-id", "server-1"),
                getConfig().getInt("redis.reconnect-attempts", 5),
                getConfig().getLong("redis.reconnect-delay-ms", 2000)
        );

        if (getConfig().getBoolean("redis.required", true) && !redisManager.isConnected()) {
            throw new IllegalStateException("Redis is required but unavailable; refusing to start multi-server trading");
        }

        redisManager.subscribe("player_login", message -> {
            String[] parts = message.split(":", 2);
            if (parts.length == 2) {
                invalidateSession(parts[0]);
            }
        });
        redisManager.subscribe("player_logout", this::invalidateSession);

        // Shared MySQL is authoritative. Redis asset events invalidate metadata only;
        // replaying refunds, candles, balances or asset CRUD would apply them twice.
        redisManager.subscribe("asset_registered", ignored -> AssetDataCache.clear());
        redisManager.subscribe("asset_deleted", ignored -> AssetDataCache.clear());
        redisManager.subscribe("asset_updated", ignored -> AssetDataCache.clear());
        redisManager.subscribe("trade_notice", Exchange::handleRemoteTransactionNotice);
    }

    private void invalidateSession(String value) {
        final UUID playerId;
        try {
            playerId = UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Ignoring malformed Redis player id");
            return;
        }

        // Redis handlers run asynchronously. Resolve the Bukkit player on the
        // global scheduler, then inspect online state on the entity scheduler.
        getfolialib().getScheduler().runNextTick(task -> {
            Player localPlayer = getServer().getPlayer(playerId);
            if (localPlayer == null) {
                invalidateLocalSession(playerId);
                return;
            }
            getfolialib().getScheduler().runAtEntityWithFallback(localPlayer, entityTask -> {
                if (!localPlayer.isOnline()) {
                    invalidateLocalSession(playerId);
                }
                // A logout from the previous backend can arrive after this
                // backend's login during a server switch. An online local
                // player owns the current selection, so ignore that stale event.
            }, () -> invalidateLocalSession(playerId));
        });
    }

    private static void invalidateLocalSession(UUID playerId) {
        Accounts.logOutLocal(playerId);
        TemporalItems.holdingTemp.remove(playerId);
    }

    @Override
    public void onDisable() {
        if (deliveryOutboxWorker != null) {
            deliveryOutboxWorker.shutdown();
            deliveryOutboxWorker = null;
        }
        if (redisManager != null) {
            redisManager.shutdown();
            redisManager = null;
        }

        for(UUID playerid : List.copyOf(TemporalItems.holdingTemp.keySet())){

            Player player = getServer().getPlayer(playerid);
            if(player != null){
                TemporalItems.removeTempItem(player);
            }
        }
        TemporalItems.holdingTemp.clear();
        Accounts.clearLocalSessions();
        AssetDataCache.clear();
        DataBase.closeConnection();
    }

    /** Logs startup-only configuration keys that differ after a hot reload, without values. */
    public void warnInfrastructureConfigChanges() {
        Map<String, Object> requested = infrastructureSettings(getConfig());
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> active : activeInfrastructureSettings.entrySet()) {
            if (!Objects.equals(active.getValue(), requested.get(active.getKey()))) {
                changed.add(active.getKey());
            }
        }
        if (!changed.isEmpty()) {
            getLogger().warning("Restart required for changed infrastructure settings: "
                    + String.join(", ", changed));
        }
    }

    private static Map<String, Object> infrastructureSettings(FileConfiguration config) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("database.database-type", config.getInt("database.database-type", 0));
        settings.put("database.data-per-world", config.getBoolean("database.data-per-world", false));
        settings.put("database.pool.maximum-size", config.getInt("database.pool.maximum-size", 10));
        settings.put("database.pool.minimum-idle", config.getInt("database.pool.minimum-idle", 2));
        settings.put("database.pool.connection-timeout-ms",
                config.getLong("database.pool.connection-timeout-ms", 10000));
        settings.put("database.mysql.host", config.getString("database.mysql.host", ""));
        settings.put("database.mysql.port", config.getString("database.mysql.port", ""));
        settings.put("database.mysql.database", config.getString("database.mysql.database", ""));
        settings.put("database.mysql.username", config.getString("database.mysql.username", ""));
        settings.put("database.mysql.password", config.getString("database.mysql.password", ""));
        settings.put("database.mysql.options", config.getString("database.mysql.options", ""));
        settings.put("redis.enabled", config.getBoolean("redis.enabled", false));
        settings.put("redis.required", config.getBoolean("redis.required", true));
        settings.put("redis.server-id", config.getString("redis.server-id", "server-1"));
        settings.put("redis.host", config.getString("redis.host", "localhost"));
        settings.put("redis.port", config.getInt("redis.port", 6379));
        settings.put("redis.password", config.getString("redis.password", ""));
        settings.put("redis.channel-prefix", config.getString("redis.channel-prefix", "profitable"));
        settings.put("redis.reconnect-attempts", config.getInt("redis.reconnect-attempts", 5));
        settings.put("redis.reconnect-delay-ms", config.getLong("redis.reconnect-delay-ms", 2000));
        return Map.copyOf(settings);
    }

    public void checkForUpdate(Profitable plugin) {
        try {
            String projectSlug = getConfig().getString("update-check.modrinth-project", "").trim();
            if (projectSlug.isBlank()) {
                plugin.getLogger().warning("Update checking is enabled, but update-check.modrinth-project is empty");
                return;
            }
            String url = "https://api.modrinth.com/v2/project/" + projectSlug + "/version";

            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

            StringBuilder jsonBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line);
                }
            } finally {
                conn.disconnect();
            }

            String json = jsonBuilder.toString();
            Pattern pattern = Pattern.compile("\"version_number\"\\s*:\\s*\"(.*?)\"");
            Matcher matcher = pattern.matcher(json);

            if (matcher.find()) {
                String latestVersion = matcher.group(1).replace("v", "");
                String currentVersion = plugin.getPluginMeta().getVersion();

                if (!latestVersion.equalsIgnoreCase(currentVersion)) {
                    plugin.getLogger().warning("UPDATE AVAILABLE! Latest: " + latestVersion);
                    plugin.getLogger().info("Download latest here: " + getConfig().getString(
                            "update-check.download-url", "https://github.com/EllanServer/ProfitableReloaded/releases"));
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
