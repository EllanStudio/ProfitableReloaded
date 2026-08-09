package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.data.settlement.DeliveryOutboxRepository;
import com.faridfaharaj.profitable.data.settlement.MarketAdjustmentRepository;
import com.faridfaharaj.profitable.data.settlement.TradeSettlementRepository;
import com.faridfaharaj.profitable.data.settlement.WalletAdjustmentRepository;
import com.faridfaharaj.profitable.data.tables.*;
import com.faridfaharaj.profitable.hooks.PlayerPointsHook;
import com.faridfaharaj.profitable.hooks.VaultHook;
import com.faridfaharaj.profitable.redis.RedisManager;
import com.faridfaharaj.profitable.tasks.gui.elements.specific.AssetCache;
import com.faridfaharaj.profitable.util.MessagingUtil;
import com.faridfaharaj.profitable.util.NamingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;

public class AdminCommand implements CommandExecutor {

    private static final int MIN_ACCOUNT_PASSWORD_LENGTH = 8;
    private static final int MAX_ACCOUNT_PASSWORD_LENGTH = 31;

    private static final String[] ADMIN_PERMISSIONS = {
            "profitable.admin.info.status",
            "profitable.admin.config.reloadconfig",
            "profitable.admin.accounts.info.getplayeracc",
            "profitable.admin.accounts.info.wallet",
            "profitable.admin.accounts.info.orders",
            "profitable.admin.accounts.info.claimid",
            "profitable.admin.accounts.manage.passwordreset",
            "profitable.admin.accounts.manage.forcelogout",
            "profitable.admin.accounts.manage.wallet",
            "profitable.admin.accounts.manage.delete",
            "profitable.admin.assets.manage.register",
            "profitable.admin.assets.manage.newtransaction",
            "profitable.admin.assets.manage.resettransactions",
            "profitable.admin.assets.manage.delete",
            "profitable.admin.assets.manage.edit",
            "profitable.admin.assets.info.getallassets",
            "profitable.admin.orders.manage.cancel",
            "profitable.admin.orders.manage.delete",
            "profitable.admin.orders.manage.cancelall",
            "profitable.admin.orders.manage.deleteall",
            "profitable.admin.orders.manage.newlimitorder",
            "profitable.admin.orders.info.findbyasset",
            "profitable.admin.outbox.info",
            "profitable.admin.outbox.retry"
    };

    static boolean hasAnyAdminPermission(CommandSender sender) {
        return hasAnyPermission(sender, ADMIN_PERMISSIONS);
    }

    private static boolean hasAnyPermission(CommandSender sender, String... permissions) {
        for (String permission : permissions) {
            if (sender.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> availableAdminRoots(CommandSender sender) {
        List<String> options = new ArrayList<>();
        if (sender.hasPermission("profitable.admin.info.status")) options.add("status");
        if (sender.hasPermission("profitable.admin.config.reloadconfig")) options.add("config");
        if (sender.hasPermission("profitable.admin.accounts.info.getplayeracc")) options.add("getplayeracc");
        if (sender.hasPermission("profitable.admin.accounts.manage.forcelogout")) options.add("forcelogout");
        if (hasAnyPermission(sender,
                "profitable.admin.assets.info.getallassets",
                "profitable.admin.assets.manage.register",
                "profitable.admin.assets.manage.newtransaction",
                "profitable.admin.assets.manage.resettransactions",
                "profitable.admin.assets.manage.delete",
                "profitable.admin.assets.manage.edit")) options.add("assets");
        if (hasAnyPermission(sender,
                "profitable.admin.orders.info.findbyasset",
                "profitable.admin.orders.manage.cancel",
                "profitable.admin.orders.manage.delete",
                "profitable.admin.orders.manage.deleteall",
                "profitable.admin.orders.manage.cancelall",
                "profitable.admin.orders.manage.newlimitorder")) options.add("orders");
        if (hasAnyPermission(sender,
                "profitable.admin.outbox.info",
                "profitable.admin.outbox.retry")) options.add("outbox");
        if (hasAnyPermission(sender,
                "profitable.admin.accounts.info.wallet",
                "profitable.admin.accounts.info.orders",
                "profitable.admin.accounts.info.claimid",
                "profitable.admin.accounts.manage.wallet",
                "profitable.admin.accounts.manage.passwordreset",
                "profitable.admin.accounts.manage.delete")) options.add("account");
        return options;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        if (args.length == 0) {
            List<String> available = availableAdminRoots(sender);
            if (available.isEmpty()) {
                MessagingUtil.sendGenericMissingPerm(sender);
            } else {
                MessagingUtil.sendSyntaxError(sender,
                        "/profitablereloaded:admin <" + String.join("|", available) + "> ...");
            }
            return true;
        }

        switch (args[0]) {
            case "status" -> handleStatus(sender);
            case "config" -> handleConfig(sender, args);
            case "getplayeracc" -> handleGetPlayerAcc(sender, args);
            case "forcelogout" -> handleForceLogout(sender, args);
            case "assets" -> handleAssets(sender, args);
            case "orders" -> handleOrders(sender, args);
            case "outbox" -> handleOutbox(sender, args);
            case "account" -> handleAccount(sender, args);
            default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[0]);
        }

        return true;
    }

    // ---------- helpers ----------

    /** Resolves the world used for data operations: player world, explicit name, or default world. */
    private World resolveWorld(CommandSender sender, String explicitName) {
        if (explicitName != null) {
            return Profitable.getInstance().getServer().getWorld(explicitName);
        }
        if (sender instanceof Player player) {
            return player.getWorld();
        }
        return Profitable.getInstance().getServer().getWorlds().getFirst();
    }

    private void sendSuccess(CommandSender sender, String text) {
        MessagingUtil.sendComponentMessage(sender, Component.text(text, Configuration.COLORHIGHLIGHT));
    }

    private void publishAssetRegistered(String worldName, String assetCode, int assetType) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("asset_registered", worldName + ":" + assetCode + ":" + assetType);
        }
    }

    private void publishAssetDeleted(String worldName, String assetCode) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("asset_deleted", worldName + ":" + assetCode);
        }
    }

    private void publishAssetUpdated(String worldName, String oldCode, Asset updated) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("asset_updated", worldName + ":" + oldCode + ":" + updated.getCode() + ":"
                    + updated.getAssetType() + ":" + updated.getColor().value() + ":" + updated.getName().replace(":", " "));
        }
    }

    // ---------- status ----------

    private void handleStatus(CommandSender sender) {

        if (!sender.hasPermission("profitable.admin.info.status")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        World world = resolveWorld(sender, null);
        int online = Profitable.getInstance().getServer().getOnlinePlayers().size();
        String version = Profitable.getInstance().getPluginMeta().getVersion();

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            int assetCount = Assets.getAll(world).size();
            int orderCount = Orders.getAllOrders().size();
            int sessions = Accounts.getCurrentAccounts().size();

            RedisManager rm = Profitable.getRedisManager();
            String redisStatus = rm == null ? "disabled" : (rm.isConnected() ? "connected" : "disconnected");
            String redisIdentity = rm == null ? "" : " (" + rm.getServerId() + " @ " + rm.getChannelPrefix() + ")";
            boolean tradingReady = Profitable.isTradingReady();
            String dbType = DataBase.isMySQL() ? "MySQL" : "SQLite";
            String multiworld = Configuration.MULTIWORLD ? "per-world" : "server-wide";
            DeliveryOutboxRepository.HealthSnapshot settlement = null;
            try (Connection connection = DataBase.getConnection()) {
                settlement = DeliveryOutboxRepository.healthSnapshot(connection);
            } catch (SQLException error) {
                Profitable.getInstance().getLogger().log(Level.WARNING,
                        "Could not read settlement/outbox health for /admin status", error);
            }

            Component component = Component.text("========== [ ", Configuration.COLORPROFITABLE)
                    .append(Component.text("Exchange Status", Configuration.COLORHIGHLIGHT))
                    .append(Component.text(" ] ==========", Configuration.COLORPROFITABLE)).appendNewline()

                    .append(Component.text("Version: ", Configuration.COLORTEXT))
                    .append(Component.text(version, Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Main currency: ", Configuration.COLORTEXT))
                    .append(Component.text(Configuration.MAINCURRENCYASSET.getCode(), Configuration.MAINCURRENCYASSET.getColor())).appendNewline()

                    .append(Component.text("Database: ", Configuration.COLORTEXT))
                    .append(Component.text(dbType + " (" + multiworld + ")", Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Redis coordination: ", Configuration.COLORTEXT))
                    .append(Component.text(redisStatus + redisIdentity,
                            rm != null && rm.isConnected() ? Configuration.COLORBULLISH : Configuration.COLORWARN)).appendNewline()

                    .append(Component.text("New trading: ", Configuration.COLORTEXT))
                    .append(Component.text(tradingReady ? "ready" : "blocked",
                            tradingReady ? Configuration.COLORBULLISH : Configuration.COLORWARN)).appendNewline()

                    .append(Component.text("Registered assets: ", Configuration.COLORTEXT))
                    .append(Component.text(String.valueOf(assetCount), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Open orders: ", Configuration.COLORTEXT))
                    .append(Component.text(String.valueOf(orderCount), Configuration.GUICOLORHIGHLIGHT)).appendNewline();

            if (settlement == null) {
                component = component.append(Component.text("Settlement/outbox: ", Configuration.COLORTEXT))
                        .append(Component.text("unavailable", Configuration.COLORWARN)).appendNewline();
            } else {
                TextColor outboxColor = settlement.dead() > 0 || settlement.settledExecutions() > 0
                        ? Configuration.COLORWARN : Configuration.GUICOLORHIGHLIGHT;
                component = component
                        .append(Component.text("Correlated requests: ", Configuration.COLORTEXT))
                        .append(Component.text(String.valueOf(settlement.correlatedRequests()),
                                Configuration.GUICOLORHIGHLIGHT)).appendNewline()
                        .append(Component.text("Audited market adjustments: ", Configuration.COLORTEXT))
                        .append(Component.text(String.valueOf(settlement.auditedMarketAdjustments()),
                                Configuration.GUICOLORHIGHLIGHT)).appendNewline()
                        .append(Component.text("Audited wallet adjustments: ", Configuration.COLORTEXT))
                        .append(Component.text(String.valueOf(settlement.auditedWalletAdjustments()),
                                Configuration.GUICOLORHIGHLIGHT)).appendNewline()
                        .append(Component.text("Executions (settled/completed): ", Configuration.COLORTEXT))
                        .append(Component.text(settlement.settledExecutions() + "/"
                                + settlement.completedExecutions(), outboxColor)).appendNewline()
                        .append(Component.text("Wallet-credit outbox (pending/processing/dead): ", Configuration.COLORTEXT))
                        .append(Component.text(settlement.pending() + "/" + settlement.processing() + "/"
                                + settlement.dead(), outboxColor)).appendNewline();
            }

            component = component.append(Component.text("Active sessions: ", Configuration.COLORTEXT))
                    .append(Component.text(sessions + " / " + online + " players online", Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("====================================", Configuration.COLORPROFITABLE));

            MessagingUtil.sendComponentMessage(sender, component);
        });
    }

    // ---------- durable wallet-credit outbox ----------

    private void handleOutbox(CommandSender sender, String[] args) {
        if (args.length < 2) {
            MessagingUtil.sendSyntaxError(sender, "/admin outbox <dead|retry> ...");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "dead" -> {
                if (!sender.hasPermission("profitable.admin.outbox.info")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    try (Connection connection = DataBase.getConnection()) {
                        List<DeliveryOutboxRepository.DeadDelivery> rows =
                                DeliveryOutboxRepository.listDead(connection, 50);
                        Component component = Component.text("Dead wallet-credit deliveries: ",
                                        Configuration.COLORHIGHLIGHT)
                                .append(Component.text(String.valueOf(rows.size()),
                                        rows.isEmpty() ? Configuration.GUICOLORHIGHLIGHT : Configuration.COLORWARN))
                                .appendNewline();
                        if (rows.isEmpty()) {
                            component = component.append(Component.text("No DEAD outbox rows",
                                    Configuration.COLOREMPTY));
                        } else {
                            for (DeliveryOutboxRepository.DeadDelivery row : rows) {
                                component = component
                                        .append(Component.text(row.deliveryId().toString(), NamedTextColor.YELLOW)
                                                .hoverEvent(HoverEvent.showText(Component.text(
                                                        "Execution: " + (row.executionId() == null
                                                                ? "cancellation" : row.executionId())
                                                                + "\nAttempts: " + row.attempts()
                                                                + "\nCreated: " + row.createdAt()
                                                                + "\nError: " + Objects.toString(row.lastError(), "unknown")))))
                                        .append(Component.text("  " + row.account() + " <- "
                                                + MessagingUtil.formatNumber(row.quantity()) + " " + row.asset(),
                                                Configuration.COLORTEXT));
                                if (sender.hasPermission("profitable.admin.outbox.retry")) {
                                    component = component.append(Component.text(" [Retry]", Configuration.COLORWARN)
                                            .clickEvent(ClickEvent.runCommand(
                                                    "/profitablereloaded:admin outbox retry " + row.deliveryId()))
                                            .hoverEvent(HoverEvent.showText(Component.text(
                                                    "Requeue this DEAD wallet credit; processed-event dedupe remains active"))));
                                }
                                component = component.appendNewline();
                            }
                        }
                        MessagingUtil.sendComponentMessage(sender, component);
                    } catch (SQLException error) {
                        Profitable.getInstance().getLogger().log(Level.WARNING,
                                "Could not list DEAD wallet-credit outbox rows", error);
                        MessagingUtil.sendComponentMessage(sender,
                                Profitable.getLang().get("generic.error.internal"));
                    }
                });
            }
            case "retry" -> {
                if (!sender.hasPermission("profitable.admin.outbox.retry")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }
                if (args.length < 3) {
                    MessagingUtil.sendSyntaxError(sender, "/admin outbox retry <delivery-id>");
                    return;
                }
                final UUID deliveryId;
                try {
                    deliveryId = UUID.fromString(args[2]);
                } catch (IllegalArgumentException error) {
                    MessagingUtil.sendSyntaxError(sender, "Invalid delivery ID");
                    return;
                }
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    try (Connection connection = DataBase.getConnection()) {
                        if (!DeliveryOutboxRepository.retryDead(connection, deliveryId)) {
                            MessagingUtil.sendSyntaxError(sender,
                                    "Delivery is missing or is not in DEAD state");
                            return;
                        }
                        Profitable.wakeDeliveryOutbox();
                        sendSuccess(sender, "Requeued wallet-credit delivery " + deliveryId);
                    } catch (SQLException error) {
                        Profitable.getInstance().getLogger().log(Level.WARNING,
                                "Could not requeue DEAD wallet-credit delivery " + deliveryId, error);
                        MessagingUtil.sendComponentMessage(sender,
                                Profitable.getLang().get("generic.error.internal"));
                    }
                });
            }
            default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[1]);
        }
    }

    // ---------- config ----------

    private void handleConfig(CommandSender sender, String[] args) {

        if (args.length < 2 || !Objects.equals(args[1], "reloadconfig")) {
            MessagingUtil.sendSyntaxError(sender, "/admin config reloadconfig");
            return;
        }

        if (!sender.hasPermission("profitable.admin.config.reloadconfig")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        Configuration.reloadConfig(Profitable.getInstance());
        sendSuccess(sender, "Successfully reloaded config file");
        MessagingUtil.sendComponentMessage(sender, Component.text("Some properties require restarting the server", Configuration.COLORWARN));
    }

    // ---------- getplayeracc / forcelogout ----------

    private void handleGetPlayerAcc(CommandSender sender, String[] args) {

        if (!sender.hasPermission("profitable.admin.accounts.info.getplayeracc")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        if (args.length < 2) {
            MessagingUtil.sendSyntaxError(sender, "/admin getplayeracc <player>");
            return;
        }

        Player gotPlayer = Profitable.getInstance().getServer().getPlayer(args[1]);
        if (gotPlayer == null) {
            MessagingUtil.sendSyntaxError(sender, args[1] + " isn't online");
            return;
        }

        String account = Accounts.getAccount(gotPlayer);
        MessagingUtil.sendComponentMessage(sender,
                Component.text(gotPlayer.getName() + "'s active account is: ", Configuration.GUICOLORTEXT)
                        .append(Component.text(account, Configuration.COLORHIGHLIGHT)));
    }

    private void handleForceLogout(CommandSender sender, String[] args) {

        if (!sender.hasPermission("profitable.admin.accounts.manage.forcelogout")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        if (args.length < 2) {
            MessagingUtil.sendSyntaxError(sender, "/admin forcelogout <player>");
            return;
        }

        Player gotPlayer = Profitable.getInstance().getServer().getPlayer(args[1]);
        if (gotPlayer == null) {
            MessagingUtil.sendSyntaxError(sender, args[1] + " isn't online");
            return;
        }

        UUID playerid = gotPlayer.getUniqueId();
        if (!Accounts.getCurrentAccounts().containsKey(playerid)) {
            MessagingUtil.sendSyntaxError(sender, "No active account found");
            return;
        }
        Accounts.logOut(playerid);

        sendSuccess(sender, "Logged " + gotPlayer.getName() + " out");
    }

    // ---------- assets ----------

    private void handleAssets(CommandSender sender, String[] args) {

        if (args.length == 1) {
            // list all assets
            if (!sender.hasPermission("profitable.admin.assets.info.getallassets")) {
                MessagingUtil.sendGenericMissingPerm(sender);
                return;
            }

            World world = resolveWorld(sender, null);
            String worldName = world.getName();
            Profitable.getfolialib().getScheduler().runAsync(task -> {
                Collection<String> assets = Assets.getAll(world);

                MessagingUtil.sendComponentMessage(sender,
                        Component.text("Showing all " + assets.size() + " registered assets in " + worldName + ":").color(Configuration.COLORHIGHLIGHT).appendNewline()
                                .append(Component.text("--------------------------------------------")).appendNewline()
                                .append(Component.text(String.join(", ", assets)).color(Configuration.COLORTEXT)).appendNewline()
                                .append(Component.text("--------------------------------------------")));
            });
            return;
        }

        switch (args[1]) {
            case "register" -> handleAssetsRegister(sender, args);
            case "fromid" -> handleAssetsFromId(sender, args);
            default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[1]);
        }
    }

    private void handleAssetsRegister(CommandSender sender, String[] args) {

        if (!sender.hasPermission("profitable.admin.assets.manage.register")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        if (args.length < 4) {
            MessagingUtil.sendSyntaxError(sender, "/admin assets register <currency|commodityitem|commodityentity> <Symbol> [Name] [HexColor]");
            return;
        }

        String asset = args[3].toUpperCase(Locale.ROOT);
        String assetKind = args[2].toLowerCase(Locale.ROOT);
        World world = resolveWorld(sender, null);
        String worldName = world.getName();
        int assetType;
        boolean commodityItem = false;
        boolean commodityEntity = false;
        byte[] metadata;

        try {
            switch (assetKind) {
                case "currency" -> {
                    if (asset.length() > 3) {
                        MessagingUtil.sendSyntaxError(sender, "Currencies must only have 3 letters");
                        return;
                    }
                    String generator = asset;
                    if (args.length > 4) {
                        generator += "_" + args[4].replace("_", " ");
                    }
                    if (args.length > 5) {
                        generator += "_" + args[5];
                    }
                    assetType = 1;
                    metadata = Asset.metaData(Asset.StringToCurrency(generator));
                }
                case "commodityitem" -> {
                    Material material = Material.matchMaterial(asset);
                    if (material == null || !material.isItem()) {
                        MessagingUtil.sendSyntaxError(sender, "Commodities must come from an existing item");
                        return;
                    }
                    assetType = 2;
                    commodityItem = true;
                    metadata = Asset.metaData(Configuration.COLORHIGHLIGHT.value(), NamingUtil.nameCommodity(asset));
                }
                case "commodityentity" -> {
                    EntityType entity = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(asset.toLowerCase(Locale.ROOT)));
                    if (entity == null) {
                        MessagingUtil.sendSyntaxError(sender, "Commodities must come from an existing entity");
                        return;
                    }
                    Class<?> entityClass = entity.getEntityClass();
                    if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass) || entity == EntityType.PLAYER) {
                        MessagingUtil.sendSyntaxError(sender, "Invalid asset");
                        return;
                    }
                    assetType = 3;
                    commodityEntity = true;
                    metadata = Asset.metaData(Configuration.COLORHIGHLIGHT.value(), NamingUtil.nameCommodity(asset));
                }
                default -> {
                    MessagingUtil.sendSyntaxError(sender, "Invalid asset type: " + args[2]);
                    return;
                }
            }
        } catch (IOException e) {
            MessagingUtil.sendSyntaxError(sender, "Error registering " + asset);
            return;
        }

        boolean updateItemConfig = commodityItem;
        boolean updateEntityConfig = commodityEntity;
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            if (Assets.registerAsset(world, asset, assetType, metadata)) {
                if (updateItemConfig || updateEntityConfig) {
                    Profitable.getfolialib().getScheduler().runNextTick(configTask ->
                            updateCommodityConfigLists(updateItemConfig, asset));
                }
                publishAssetRegistered(worldName, asset, assetType);
                sendSuccess(sender, "Registered: " + asset);
            } else {
                MessagingUtil.sendSyntaxError(sender, "There is already an asset with Symbol: " + asset);
            }
        });
    }

    /** Keeps config white/blacklists and runtime lists coherent when commodities are manually registered. */
    private void updateCommodityConfigLists(boolean isItem, String asset) {

        if (!Configuration.GENERATEASSETS) {
            return;
        }

        String modePath = isItem
                ? "exchange.commodities.generation.item-whitelisting"
                : "exchange.commodities.generation.entity-whitelisting";
        String listPath = isItem
                ? (Profitable.getInstance().getConfig().getBoolean(modePath)
                    ? "exchange.commodities.generation.commodity-item-whitelist"
                    : "exchange.commodities.generation.commodity-item-blacklist")
                : (Profitable.getInstance().getConfig().getBoolean(modePath)
                    ? "exchange.commodities.generation.commodity-entity-whitelist"
                    : "exchange.commodities.generation.commodity-entity-blacklist");

        boolean whitelisting = Profitable.getInstance().getConfig().getBoolean(modePath);

        List<String> list = Profitable.getInstance().getConfig().getStringList(listPath);
        if (whitelisting) {
            if (!list.contains(asset)) {
                list.add(asset);
            }
        } else {
            list.remove(asset);
        }
        Profitable.getInstance().getConfig().set(listPath, list);
        Profitable.getInstance().saveConfig();

        if (isItem) {
            if (!Configuration.ALLOWEITEMS.contains(asset)) {
                Configuration.ALLOWEITEMS.add(asset);
            }
        } else {
            if (!Configuration.ALLOWENTITIES.contains(asset)) {
                Configuration.ALLOWENTITIES.add(asset);
            }
        }
    }

    private void handleAssetsFromId(CommandSender sender, String[] args) {

        if (args.length < 4) {
            MessagingUtil.sendSyntaxError(sender, "/admin assets fromid <asset> <newtransaction|resettransactions|delete|edit> ...");
            return;
        }

        String assetCode = args[2].toUpperCase(Locale.ROOT);
        String operation = args[3].toLowerCase(Locale.ROOT);
        World world = resolveWorld(sender, null);
        String worldName = world.getName();

        switch (operation) {
            case "newtransaction" -> {
                if (!sender.hasPermission("profitable.admin.assets.manage.newtransaction")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }
                if (args.length < 6 || args.length > 7) {
                    MessagingUtil.sendSyntaxError(sender, "/admin assets fromid " + assetCode
                            + " newtransaction <price> <volume> [request-uuid]");
                    return;
                }
                double price;
                double volume;
                try {
                    price = Double.parseDouble(args[4]);
                    volume = Double.parseDouble(args[5]);
                } catch (NumberFormatException e) {
                    MessagingUtil.sendGenericInvalidAmount(sender, args[4] + " / " + args[5]);
                    return;
                }
                if (!Double.isFinite(price) || !Double.isFinite(volume) || price <= 0 || volume <= 0) {
                    MessagingUtil.sendGenericInvalidAmount(sender, args[4] + " / " + args[5]);
                    return;
                }
                UUID requestId;
                try {
                    requestId = args.length == 7 ? UUID.fromString(args[6]) : UUID.randomUUID();
                } catch (IllegalArgumentException invalidUuid) {
                    MessagingUtil.sendSyntaxError(sender, "Invalid request UUID: " + args[6]);
                    return;
                }
                byte[] worldId = MessagingUtil.getWorldId(world);
                long marketTime = world.getFullTime();
                long requestedAt = System.currentTimeMillis();
                String actor = sender.getName();
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    try (Connection connection = DataBase.getConnection()) {
                        MarketAdjustmentRepository.Result result = MarketAdjustmentRepository.apply(
                                connection, DataBase.isMySQL(), new MarketAdjustmentRepository.Request(
                                        requestId, worldId, assetCode, actor, price, volume,
                                        marketTime, requestedAt));
                        switch (result.status()) {
                            case APPLIED -> sendSuccess(sender, "Inserted audited synthetic transaction in "
                                    + assetCode + " (request " + requestId + ")");
                            case ALREADY_APPLIED -> sendSuccess(sender, "Request " + requestId
                                    + " was already applied; no duplicate volume was added");
                            case ASSET_NOT_FOUND -> MessagingUtil.sendSyntaxError(sender,
                                    "Could not find asset " + assetCode + "; request " + requestId
                                            + " was not applied");
                        }
                    } catch (SQLException error) {
                        Profitable.getInstance().getLogger().log(Level.WARNING,
                                "Could not apply synthetic market adjustment " + requestId, error);
                        MessagingUtil.sendSyntaxError(sender, "Synthetic transaction request " + requestId
                                + " was rejected; no additional candle volume was added");
                    }
                });
            }
            case "resettransactions" -> {
                if (!sender.hasPermission("profitable.admin.assets.manage.resettransactions")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }
                MessagingUtil.sendComponentMessage(sender, Component.text(
                        "Unsupported: resettransactions would destroy correlated execution history; no data was changed.",
                        Configuration.COLORWARN));
            }
            case "delete" -> {
                if (!sender.hasPermission("profitable.admin.assets.manage.delete")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }
                if (args.length < 5) {
                    MessagingUtil.sendSyntaxError(sender, "/admin assets fromid <asset> delete <asset again>");
                    return;
                }
                if (!Objects.equals(assetCode, args[4].toUpperCase(Locale.ROOT))) {
                    MessagingUtil.sendSyntaxError(sender, "Assets don't match");
                    return;
                }
                if (Objects.equals(assetCode, Configuration.MAINCURRENCYASSET.getCode())) {
                    MessagingUtil.sendSyntaxError(sender, "Cannot remove main currency");
                    return;
                }
                boolean updateGeneratedConfig = Configuration.GENERATEASSETS;
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    Asset asset = Assets.getAssetData(world, assetCode);
                    if (asset == null) {
                        MessagingUtil.sendSyntaxError(sender, "This asset does not exist");
                        return;
                    }
                    for (Order openOrder : Orders.getAssetOrders(world, assetCode)) {
                        if (!Orders.cancelOrder(world, openOrder.getUuid())) {
                            MessagingUtil.sendSyntaxError(sender, "Could not refund all open orders; asset was not deleted");
                            return;
                        }
                    }
                    if (Assets.deleteAsset(world, assetCode)) {
                        Candles.assetDeleteAllCandles(world, assetCode);
                        if (updateGeneratedConfig && (asset.getAssetType() == 2 || asset.getAssetType() == 3)) {
                            boolean item = asset.getAssetType() == 2;
                            Profitable.getfolialib().getScheduler().runNextTick(configTask ->
                                    removeCommodityFromConfigLists(item, assetCode));
                        }
                        publishAssetDeleted(worldName, assetCode);
                        MessagingUtil.sendComponentMessage(sender, Component.text("DELETED " + assetCode, NamedTextColor.RED));
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Could not delete that asset");
                    }
                });
            }
            case "edit" -> {
                if (!sender.hasPermission("profitable.admin.assets.manage.edit")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }
                if (args.length < 5) {
                    MessagingUtil.sendSyntaxError(sender, "/admin assets fromid <Asset> edit <New symbol> [New name] [New hexcolor]");
                    return;
                }
                String newCode = args[4].toUpperCase(Locale.ROOT);
                if (newCode.length() > 3) {
                    MessagingUtil.sendSyntaxError(sender, "Currencies must only have 3 letters");
                    return;
                }
                String requestedName = args.length > 5 ? args[5] : null;
                TextColor requestedColor = args.length > 6 ? TextColor.fromHexString(args[6]) : null;
                String mainCurrencyCode = Configuration.MAINCURRENCYASSET.getCode();
                String vaultAssetCode = VaultHook.isConnected() ? VaultHook.getAsset().getCode() : null;
                String playerPointsAssetCode = PlayerPointsHook.isConnected() ? PlayerPointsHook.getAsset().getCode() : null;

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    Asset asset = Assets.getAssetData(world, assetCode);
                    if (asset == null) {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't find asset: " + assetCode);
                        return;
                    }
                    if (asset.getAssetType() == 2 || asset.getAssetType() == 3) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit commodities");
                        return;
                    }
                    if (!Objects.equals(newCode, asset.getCode()) && Assets.getAssetData(world, newCode) != null) {
                        MessagingUtil.sendSyntaxError(sender, "There is already an asset with Symbol: " + newCode);
                        return;
                    }
                    if (Objects.equals(asset.getCode(), mainCurrencyCode)) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit the main currency");
                        return;
                    }
                    if (Objects.equals(asset.getCode(), vaultAssetCode)) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit Vault output currency, change on config!");
                        return;
                    }
                    if (Objects.equals(asset.getCode(), playerPointsAssetCode)) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit the PlayerPoints output currency, change on config!");
                        return;
                    }
                    String name = requestedName != null ? requestedName : asset.getName();
                    TextColor color = requestedColor != null ? requestedColor : asset.getColor();
                    Asset updated = new Asset(newCode, asset.getAssetType(), color, name);
                    if (Assets.updateAsset(world, asset.getCode(), updated)) {
                        publishAssetUpdated(worldName, asset.getCode(), updated);
                        sendSuccess(sender, "Updated " + newCode);
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't edit this asset");
                    }
                });
            }
            default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[3]);
        }
    }

    private void removeCommodityFromConfigLists(boolean isItem, String asset) {

        String modePath = isItem
                ? "exchange.commodities.generation.item-whitelisting"
                : "exchange.commodities.generation.entity-whitelisting";
        boolean whitelisting = Profitable.getInstance().getConfig().getBoolean(modePath);

        String listPath = isItem
                ? (whitelisting ? "exchange.commodities.generation.commodity-item-whitelist" : "exchange.commodities.generation.commodity-item-blacklist")
                : (whitelisting ? "exchange.commodities.generation.commodity-entity-whitelist" : "exchange.commodities.generation.commodity-entity-blacklist");

        List<String> list = Profitable.getInstance().getConfig().getStringList(listPath);
        if (whitelisting) {
            list.remove(asset);
        } else {
            if (!list.contains(asset)) {
                list.add(asset);
            }
        }
        Profitable.getInstance().getConfig().set(listPath, list);
        Profitable.getInstance().saveConfig();

        if (isItem) {
            Configuration.ALLOWEITEMS.remove(asset);
        } else {
            Configuration.ALLOWENTITIES.remove(asset);
        }
    }

    // ---------- orders ----------

    private void handleOrders(CommandSender sender, String[] args) {

        if (args.length == 1) {
            MessagingUtil.sendSyntaxError(sender, "/admin orders <findbyasset|getbyid|deleteall|cancelall|newlimitorder> ...");
            return;
        }

        switch (args[1]) {
            case "findbyasset" -> {

                if (!sender.hasPermission("profitable.admin.orders.info.findbyasset")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }

                if (args.length < 3) {
                    MessagingUtil.sendSyntaxError(sender, "/admin orders findbyasset <asset>");
                    return;
                }

                World world = resolveWorld(sender, null);
                String assetCode = args[2].toUpperCase(Locale.ROOT);
                boolean canCancel = sender.hasPermission("profitable.admin.orders.manage.cancel");
                boolean canDelete = sender.hasPermission("profitable.admin.orders.manage.delete");
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    List<Order> orders = Orders.getAssetOrders(world, assetCode);
                    sendOrdersList(sender, "Showing all active orders for " + assetCode + ":", orders,
                            canCancel, canDelete);
                });
            }

            case "getbyid" -> {

                if (args.length < 4) {
                    MessagingUtil.sendSyntaxError(sender, "/admin orders getbyid <ID> <cancel|delete>");
                    return;
                }

                UUID orderUuid;
                try {
                    orderUuid = UUID.fromString(args[2]);
                } catch (IllegalArgumentException e) {
                    MessagingUtil.sendSyntaxError(sender, "Invalid order ID");
                    return;
                }

                World world = resolveWorld(sender, null);
                String operation = args[3].toLowerCase(Locale.ROOT);
                String orderId = args[2];
                if (Objects.equals(operation, "cancel")) {
                    if (!sender.hasPermission("profitable.admin.orders.manage.cancel")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }
                    Profitable.getfolialib().getScheduler().runAsync(task -> {
                        if (Orders.cancelOrder(world, orderUuid)) {
                            sendSuccess(sender, "Cancelled: " + orderId);
                        } else {
                            MessagingUtil.sendSyntaxError(sender, "Couldn't cancel that order");
                        }
                    });
                } else if (Objects.equals(operation, "delete")) {
                    if (!sender.hasPermission("profitable.admin.orders.manage.delete")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }
                    Profitable.getfolialib().getScheduler().runAsync(task -> {
                        if (Orders.deleteOrder(world, orderUuid)) {
                            sendSuccess(sender, "Cancelled order " + orderId + " and queued its escrow refund");
                        } else {
                            MessagingUtil.sendSyntaxError(sender, "Couldn't delete that order");
                        }
                    });
                } else {
                    MessagingUtil.sendGenericInvalidSubCom(sender, args[3]);
                }
            }

            case "deleteall" -> {

                if (!sender.hasPermission("profitable.admin.orders.manage.deleteall")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    if (Orders.deleteAllOrders()) {
                        sendSuccess(sender, "Cancelled all orders and queued every escrow refund");
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't find any");
                    }
                });
            }

            case "cancelall" -> {

                if (!sender.hasPermission("profitable.admin.orders.manage.cancelall")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }

                List<World> worlds = List.copyOf(Profitable.getInstance().getServer().getWorlds());
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    int cancelled = 0;
                    for (World iteratedWorld : worlds) {
                        List<Order> orders = Orders.getAllOrders(iteratedWorld);
                        for (Order order : orders) {
                            if (Orders.cancelOrder(iteratedWorld, order.getUuid())) {
                                cancelled++;
                            }
                        }
                    }

                    if (cancelled == 0) {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't find any");
                    } else {
                        sendSuccess(sender, "Cancelled " + cancelled + " orders from all assets");
                    }
                });
            }

            case "newlimitorder" -> {

                if (!sender.hasPermission("profitable.admin.orders.manage.newlimitorder")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }

                if (args.length < 6) {
                    MessagingUtil.sendSyntaxError(sender, "/admin orders newlimitorder <asset> <buy|sell> <units> <price>");
                    return;
                }

                boolean sideBuy;
                switch (args[3].toLowerCase(Locale.ROOT)) {
                    case "buy" -> sideBuy = true;
                    case "sell" -> sideBuy = false;
                    default -> {
                        MessagingUtil.sendSyntaxError(sender,
                                "/admin orders newlimitorder <asset> <buy|sell> <units> <price>");
                        return;
                    }
                }

                double units;
                double price;
                try {
                    units = Double.parseDouble(args[4]);
                    price = Double.parseDouble(args[5]);
                } catch (NumberFormatException e) {
                    MessagingUtil.sendGenericInvalidAmount(sender, args[4] + " / " + args[5]);
                    return;
                }
                if (!Double.isFinite(units) || !Double.isFinite(price) || units <= 0 || price <= 0) {
                    MessagingUtil.sendGenericInvalidAmount(sender, args[4] + " / " + args[5]);
                    return;
                }

                World world = resolveWorld(sender, null);
                long marketTime = world.getFullTime();
                String assetCode = args[2].toUpperCase(Locale.ROOT);
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    TradeSettlementRepository.Result result = Orders.placeOrderFromWallet(
                            world, UUID.randomUUID(), "server", assetCode, sideBuy,
                            price, units, Order.OrderType.LIMIT, marketTime);
                    if (result != null && result.status() == TradeSettlementRepository.Status.PLACED) {
                        sendSuccess(sender, "Inserted new limit order " + (sideBuy ? "buy" : "sell") + " " + units + " " + assetCode + " at $" + price + " on server's account");
                    } else if (result != null
                            && result.status() == TradeSettlementRepository.Status.INSUFFICIENT_FUNDS) {
                        MessagingUtil.sendSyntaxError(sender,
                                "Server exchange wallet does not have enough collateral for that order");
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't add order for " + assetCode);
                    }
                });
            }

            default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[1]);
        }
    }

    private void sendOrdersList(CommandSender sender, String header, List<Order> orders,
                                boolean canCancel, boolean canDelete) {

        if (orders.isEmpty()) {
            MessagingUtil.sendComponentMessage(sender, Component.text(header, Configuration.COLORHIGHLIGHT).appendNewline()
                    .append(Component.text("No active orders", Configuration.COLOREMPTY)));
            return;
        }

        Component component = Component.text(header).color(Configuration.COLORHIGHLIGHT).appendNewline()
                .append(Component.text("--------------------------------------------")).appendNewline();

        for (Order order : orders) {
            component = component.append(order.toComponent()).appendNewline();
            boolean hasAction = false;
            if (canCancel) {
                component = component.append(Component.text("[Cancel] ", Configuration.COLORWARN)
                        .clickEvent(ClickEvent.runCommand("/profitablereloaded:admin orders getbyid " + order.getUuid() + " cancel"))
                        .hoverEvent(HoverEvent.showText(Component.text("Cancel this order and give back collateral to owner"))));
                hasAction = true;
            }
            if (canDelete) {
                component = component.append(Component.text("[Delete]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/profitablereloaded:admin orders getbyid " + order.getUuid() + " delete"))
                        .hoverEvent(HoverEvent.showText(Component.text("Cancel this order and queue its exact escrow refund"))));
                hasAction = true;
            }
            if (hasAction) {
                component = component.appendNewline();
            }
        }
        component = component.append(Component.text("--------------------------------------------"));
        MessagingUtil.sendComponentMessage(sender, component);
    }

    // ---------- account ----------

    private void handleAccount(CommandSender sender, String[] args) {

        if (args.length < 3) {
            MessagingUtil.sendSyntaxError(sender, "/admin account <account> <wallet|passwordreset|orders|claimid|delete> ...");
            return;
        }

        String account = args[1];
        World world = resolveWorld(sender, null);
        byte[] worldId = MessagingUtil.getWorldId(world);
        String actor = sender.getName();

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            switch (args[2].toLowerCase(Locale.ROOT)) {
                case "wallet" -> {

                    if (args.length == 3) {

                        if (!sender.hasPermission("profitable.admin.accounts.info.wallet")) {
                            MessagingUtil.sendGenericMissingPerm(sender);
                            return;
                        }

                        List<AssetCache> balances = AccountHoldings.AssetBalancesToAssetData(world, account);

                        Component component = Component.text("Wallet of " + account + ":").color(Configuration.COLORHIGHLIGHT).appendNewline()
                                .append(Component.text("--------------------------------------------")).appendNewline();

                        for (AssetCache holding : balances) {
                            component = component.append(
                                    Component.text(holding.getAsset().getCode(), holding.getAsset().getColor())
                                            .append(Component.text(": ", Configuration.COLORTEXT))
                                            .append(Component.text(MessagingUtil.formatNumber(holding.getlastCandle().getVolume()), Configuration.GUICOLORHIGHLIGHT))
                                            .append(Component.text("  (value: $" + MessagingUtil.formatNumber(holding.getlastCandle().getClose()) + ")", Configuration.COLORTEXT))
                            ).appendNewline();
                        }

                        component = component.append(Component.text("--------------------------------------------"));
                        MessagingUtil.sendComponentMessage(sender, component);
                        return;
                    }

                    if (args.length < 5 || args.length > 6) {
                        MessagingUtil.sendSyntaxError(sender,
                                "/admin account <account> wallet <asset> <amount> [request-uuid]");
                        return;
                    }

                    if (!sender.hasPermission("profitable.admin.accounts.manage.wallet")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    double amount;
                    try {
                        amount = Double.parseDouble(args[4]);
                    } catch (NumberFormatException e) {
                        MessagingUtil.sendGenericInvalidAmount(sender, args[4]);
                        return;
                    }
                    if (!Double.isFinite(amount) || amount < 0) {
                        MessagingUtil.sendGenericInvalidAmount(sender, args[4]);
                        return;
                    }

                    UUID requestId;
                    try {
                        requestId = args.length == 6 ? UUID.fromString(args[5]) : UUID.randomUUID();
                    } catch (IllegalArgumentException invalidUuid) {
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get(
                                "admin.wallet-adjustment.error.invalid-request-id",
                                Map.entry("%request_id%", args[5])));
                        return;
                    }
                    String assetCode = args[3].toUpperCase(Locale.ROOT);
                    try (Connection connection = DataBase.getConnection()) {
                        WalletAdjustmentRepository.Result result = WalletAdjustmentRepository.apply(
                                connection, DataBase.isMySQL(), new WalletAdjustmentRepository.Request(
                                        requestId, worldId, account, assetCode, actor, amount,
                                        System.currentTimeMillis()));
                        String key = switch (result.status()) {
                            case APPLIED -> "admin.wallet-adjustment.applied";
                            case ALREADY_APPLIED -> "admin.wallet-adjustment.already-applied";
                            case ACCOUNT_NOT_FOUND -> "admin.wallet-adjustment.error.account-not-found";
                            case ASSET_NOT_FOUND -> "admin.wallet-adjustment.error.asset-not-found";
                            case INVALID_QUANTITY -> "admin.wallet-adjustment.error.invalid-physical-quantity";
                            case PENDING_DELIVERY -> "admin.wallet-adjustment.error.pending-delivery";
                        };
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get(key,
                                Map.entry("%request_id%", requestId.toString()),
                                Map.entry("%account%", account),
                                Map.entry("%asset%", assetCode),
                                Map.entry("%old_amount%", MessagingUtil.formatNumber(result.oldQuantity())),
                                Map.entry("%new_amount%", MessagingUtil.formatNumber(result.newQuantity()))));
                    } catch (SQLException | IllegalArgumentException error) {
                        Profitable.getInstance().getLogger().log(Level.WARNING,
                                "Could not apply audited wallet adjustment " + requestId, error);
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get(
                                "admin.wallet-adjustment.error.rejected",
                                Map.entry("%request_id%", requestId.toString())));
                    }
                }

                case "passwordreset" -> {

                    if (!sender.hasPermission("profitable.admin.accounts.manage.passwordreset")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    if (args.length < 4) {
                        MessagingUtil.sendSyntaxError(sender, "/admin account <account> passwordreset <new password>");
                        return;
                    }

                    if (Accounts.isProtectedAccount(account)) {
                        MessagingUtil.sendSyntaxError(sender,
                                "Server-owned and UUID default accounts cannot have passwords");
                        return;
                    }

                    String newPassword = args[3];
                    if (newPassword.length() < MIN_ACCOUNT_PASSWORD_LENGTH
                            || newPassword.length() > MAX_ACCOUNT_PASSWORD_LENGTH) {
                        MessagingUtil.sendSyntaxError(sender, "Passwords must contain 8-31 characters");
                        return;
                    }

                    if (Accounts.changePassword(world, account, newPassword)) {
                        sendSuccess(sender, "Reset password for account " + account);
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Password was not reset: account not found");
                    }
                }

                case "orders" -> {

                    if (!sender.hasPermission("profitable.admin.accounts.info.orders")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    sendOrdersList(
                            sender,
                            "Showing all active orders on account " + account + ":",
                            Orders.getAccountOrders(world, account),
                            sender.hasPermission("profitable.admin.orders.manage.cancel"),
                            sender.hasPermission("profitable.admin.orders.manage.delete")
                    );
                }

                case "delivery" -> MessagingUtil.sendComponentMessage(
                        sender, Profitable.getLang().get("delivery.deprecated"));

                case "claimid" -> {

                    if (!sender.hasPermission("profitable.admin.accounts.info.claimid")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    String claimId = Accounts.getEntityClaimId(world, account);
                    if (claimId != null) {
                        MessagingUtil.sendComponentMessage(sender,
                                Component.text(account + "'s entity claim id: ", Configuration.GUICOLORTEXT)
                                        .append(Component.text(claimId).color(Configuration.COLORHIGHLIGHT)));
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Could not get this claim id");
                    }
                }

                case "delete" -> {

                    if (!sender.hasPermission("profitable.admin.accounts.manage.delete")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    if (args.length < 4) {
                        MessagingUtil.sendSyntaxError(sender, "Must write account name again as confirmation");
                        return;
                    }

                    if (!Objects.equals(account, args[3])) {
                        MessagingUtil.sendSyntaxError(sender, "Account names don't match");
                        return;
                    }

                    if (Accounts.getCurrentAccounts().containsValue(account)) {
                        MessagingUtil.sendSyntaxError(sender, "Someone is still using this account");
                        return;
                    }

                    if (Accounts.deleteAccount(world, account)) {
                        MessagingUtil.sendComponentMessage(sender, Component.text("DELETED account: " + account, NamedTextColor.RED));
                    } else {
                        MessagingUtil.sendSyntaxError(sender,
                                "Account was not deleted: it may be missing, protected, active, or still own orders");
                    }
                }

                default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[2]);
            }
        });
    }

    private void handleAccountDelivery(CommandSender sender, String[] args, World world, String account) {

        if (args.length == 3) {

            if (!sender.hasPermission("profitable.admin.accounts.info.delivery")) {
                MessagingUtil.sendGenericMissingPerm(sender);
                return;
            }

            Location entityDelivery = Accounts.getEntityDelivery(world, account);
            Location itemDelivery = Accounts.getItemDelivery(world, account);

            MessagingUtil.sendComponentMessage(sender,
                    Component.text("Delivery " + account + ":").color(Configuration.COLORHIGHLIGHT).appendNewline()
                            .append(Component.text("--------------------------------------------")).appendNewline()
                            .append(Component.text("Item delivery location: ").color(Configuration.COLORTEXT))
                            .append(Component.text(itemDelivery == null ? "Not set"
                                    : itemDelivery.getBlockX() + ", " + itemDelivery.getBlockY() + ", " + itemDelivery.getBlockZ() + " (" + itemDelivery.getWorld().getName() + ")")).appendNewline()
                            .append(Component.text("Entity delivery location: ").color(Configuration.COLORTEXT))
                            .append(Component.text(entityDelivery == null ? "Not set"
                                    : entityDelivery.getBlockX() + ", " + entityDelivery.getBlockY() + ", " + entityDelivery.getBlockZ() + " (" + entityDelivery.getWorld().getName() + ")")).appendNewline()
                            .append(Component.text("--------------------------------------------")));
            return;
        }

        if (args.length < 7) {
            MessagingUtil.sendSyntaxError(sender, "/admin account <account> delivery <setitem|setentity> <x> <y> <z> [world]");
            return;
        }

        if (!sender.hasPermission("profitable.admin.accounts.manage.delivery")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        World targetWorld;
        if (args.length > 7) {
            targetWorld = Profitable.getInstance().getServer().getWorld(args[7]);
        } else {
            targetWorld = resolveWorld(sender, null);
        }

        if (targetWorld == null) {
            MessagingUtil.sendSyntaxError(sender, "Invalid world");
            return;
        }

        double x, y, z;
        try {
            x = Double.parseDouble(args[4]);
            y = Double.parseDouble(args[5]);
            z = Double.parseDouble(args[6]);
        } catch (NumberFormatException e) {
            MessagingUtil.sendSyntaxError(sender, "Invalid coordinates");
            return;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            MessagingUtil.sendSyntaxError(sender, "Invalid coordinates");
            return;
        }

        Location location = new Location(targetWorld, x, y, z);

        if (Objects.equals(args[3], "setitem")) {
            if (Accounts.changeItemDelivery(targetWorld, account, location)) {
                sendSuccess(sender, "Changed " + account + " item delivery to: " + x + ", " + y + ", " + z);
            } else {
                MessagingUtil.sendSyntaxError(sender, "Couldn't change item delivery location");
            }
        } else if (Objects.equals(args[3], "setentity")) {
            if (Accounts.changeEntityDelivery(targetWorld, account, location)) {
                sendSuccess(sender, "Changed " + account + " entity delivery to: " + x + ", " + y + ", " + z);
            } else {
                MessagingUtil.sendSyntaxError(sender, "Couldn't change entity delivery location");
            }
        } else {
            MessagingUtil.sendGenericInvalidSubCom(sender, args[3]);
        }
    }

    // ---------- tab completion ----------

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String s, String[] args) {

            List<String> suggestions = new ArrayList<>();

            if (args.length == 1) {
                StringUtil.copyPartialMatches(args[0], availableAdminRoots(sender), suggestions);
                return suggestions;
            }

            switch (args[0]) {
                case "getplayeracc" -> {
                    if (args.length == 2 && sender.hasPermission("profitable.admin.accounts.info.getplayeracc")) {
                        return null;
                    }
                }

                case "forcelogout" -> {
                    if (args.length == 2 && sender.hasPermission("profitable.admin.accounts.manage.forcelogout")) {
                        return null;
                    }
                }

                case "config" -> {
                    if (args.length == 2 && sender.hasPermission("profitable.admin.config.reloadconfig")) {
                        StringUtil.copyPartialMatches(args[1], List.of("reloadconfig"), suggestions);
                    }
                }

                case "outbox" -> {
                    if (args.length == 2) {
                        List<String> options = new ArrayList<>();
                        if (sender.hasPermission("profitable.admin.outbox.info")) options.add("dead");
                        if (sender.hasPermission("profitable.admin.outbox.retry")) options.add("retry");
                        StringUtil.copyPartialMatches(args[1], options, suggestions);
                    } else if (args.length == 3 && Objects.equals(args[1], "retry")
                            && sender.hasPermission("profitable.admin.outbox.retry")) {
                        suggestions.add("[<delivery-id>]");
                    }
                }

                case "account" -> {
                    boolean canUseAccountCommands = hasAnyPermission(sender,
                            "profitable.admin.accounts.info.wallet",
                            "profitable.admin.accounts.info.orders",
                            "profitable.admin.accounts.info.claimid",
                            "profitable.admin.accounts.manage.wallet",
                            "profitable.admin.accounts.manage.passwordreset",
                            "profitable.admin.accounts.manage.delete");
                    if (args.length == 2 && canUseAccountCommands) {
                        return null;
                    }
                    if (args.length == 3) {
                        List<String> options = new ArrayList<>();
                        if (hasAnyPermission(sender, "profitable.admin.accounts.info.wallet", "profitable.admin.accounts.manage.wallet")) options.add("wallet");
                        if (sender.hasPermission("profitable.admin.accounts.manage.passwordreset")) options.add("passwordreset");
                        if (sender.hasPermission("profitable.admin.accounts.info.orders")) options.add("orders");
                        if (sender.hasPermission("profitable.admin.accounts.info.claimid")) options.add("claimid");
                        if (sender.hasPermission("profitable.admin.accounts.manage.delete")) options.add("delete");
                        StringUtil.copyPartialMatches(args[2], options, suggestions);
                    }
                    if (args.length > 3) {
                        switch (args[2]) {
                            case "delete" -> {
                                if (args.length == 4 && sender.hasPermission("profitable.admin.accounts.manage.delete")) {
                                    suggestions.add("[<Account>]");
                                }
                            }
                            case "passwordreset" -> {
                                if (args.length == 4 && sender.hasPermission("profitable.admin.accounts.manage.passwordreset")) {
                                    suggestions.add("[<New password>]");
                                }
                            }
                            case "wallet" -> {
                                if (args.length == 4 && sender.hasPermission("profitable.admin.accounts.manage.wallet")) {
                                    suggestions.add("[<Asset>]");
                                } else if (args.length == 5 && sender.hasPermission("profitable.admin.accounts.manage.wallet")) {
                                    suggestions.add("[<Amount>]");
                                } else if (args.length == 6 && sender.hasPermission("profitable.admin.accounts.manage.wallet")) {
                                    suggestions.add("[request-uuid]");
                                }
                            }
                        }
                    }
                }

                case "orders" -> {
                    if (args.length == 2) {
                        List<String> options = new ArrayList<>();
                        if (sender.hasPermission("profitable.admin.orders.info.findbyasset")) options.add("findbyasset");
                        if (hasAnyPermission(sender, "profitable.admin.orders.manage.cancel", "profitable.admin.orders.manage.delete")) options.add("getbyid");
                        if (sender.hasPermission("profitable.admin.orders.manage.deleteall")) options.add("deleteall");
                        if (sender.hasPermission("profitable.admin.orders.manage.cancelall")) options.add("cancelall");
                        if (sender.hasPermission("profitable.admin.orders.manage.newlimitorder")) options.add("newlimitorder");
                        StringUtil.copyPartialMatches(args[1], options, suggestions);
                    } else {
                        switch (args[1]) {
                            case "newlimitorder" -> {
                                if (sender.hasPermission("profitable.admin.orders.manage.newlimitorder")) {
                                    switch (args.length) {
                                        case 3 -> suggestions.add("[<Asset>]");
                                        case 4 -> StringUtil.copyPartialMatches(args[3], List.of("buy", "sell"), suggestions);
                                        case 5 -> suggestions.add("[<Units>]");
                                        case 6 -> suggestions.add("[<Price>]");
                                    }
                                }
                            }
                            case "findbyasset" -> {
                                if (args.length == 3 && sender.hasPermission("profitable.admin.orders.info.findbyasset")) {
                                    suggestions.add("[<Asset>]");
                                }
                            }
                            case "getbyid" -> {
                                if (args.length == 3 && hasAnyPermission(sender,
                                        "profitable.admin.orders.manage.cancel", "profitable.admin.orders.manage.delete")) {
                                    suggestions.add("[<ID>]");
                                } else if (args.length == 4) {
                                    List<String> options = new ArrayList<>();
                                    if (sender.hasPermission("profitable.admin.orders.manage.cancel")) options.add("cancel");
                                    if (sender.hasPermission("profitable.admin.orders.manage.delete")) options.add("delete");
                                    StringUtil.copyPartialMatches(args[3], options, suggestions);
                                }
                            }
                        }
                    }
                }

                case "assets" -> {
                    if (args.length == 2) {
                        List<String> options = new ArrayList<>();
                        if (sender.hasPermission("profitable.admin.assets.manage.register")) options.add("register");
                        if (hasAnyPermission(sender,
                                "profitable.admin.assets.manage.newtransaction",
                                "profitable.admin.assets.manage.resettransactions",
                                "profitable.admin.assets.manage.delete",
                                "profitable.admin.assets.manage.edit")) options.add("fromid");
                        StringUtil.copyPartialMatches(args[1], options, suggestions);
                    } else {
                        switch (args[1]) {
                            case "register" -> {
                                if (sender.hasPermission("profitable.admin.assets.manage.register")) {
                                    switch (args.length) {
                                        case 3 -> StringUtil.copyPartialMatches(args[2], List.of("currency", "commodityitem", "commodityentity"), suggestions);
                                        case 4 -> suggestions.add("[<Symbol>]");
                                        case 5 -> suggestions.add("[<Name>]");
                                        case 6 -> suggestions.add("[<Hex Color>]");
                                    }
                                }
                            }
                            case "fromid" -> {
                                if (args.length == 3 && hasAnyPermission(sender,
                                        "profitable.admin.assets.manage.newtransaction",
                                        "profitable.admin.assets.manage.resettransactions",
                                        "profitable.admin.assets.manage.delete",
                                        "profitable.admin.assets.manage.edit")) {
                                    suggestions.add("[<Asset>]");
                                } else if (args.length == 4) {
                                    List<String> options = new ArrayList<>();
                                    if (sender.hasPermission("profitable.admin.assets.manage.newtransaction")) options.add("newtransaction");
                                    if (sender.hasPermission("profitable.admin.assets.manage.resettransactions")) options.add("resettransactions");
                                    if (sender.hasPermission("profitable.admin.assets.manage.delete")) options.add("delete");
                                    if (sender.hasPermission("profitable.admin.assets.manage.edit")) options.add("edit");
                                    StringUtil.copyPartialMatches(args[3], options, suggestions);
                                } else {
                                    switch (args[3]) {
                                        case "newtransaction" -> {
                                            if (args.length == 5 && sender.hasPermission("profitable.admin.assets.manage.newtransaction")) {
                                                suggestions.add("[<price>]");
                                            } else if (args.length == 6 && sender.hasPermission("profitable.admin.assets.manage.newtransaction")) {
                                                suggestions.add("[<volume>]");
                                            } else if (args.length == 7 && sender.hasPermission("profitable.admin.assets.manage.newtransaction")) {
                                                suggestions.add("[request-uuid]");
                                            }
                                        }
                                        case "delete" -> {
                                            if (args.length == 5 && sender.hasPermission("profitable.admin.assets.manage.delete")) {
                                                suggestions.add("[<Asset again>]");
                                            }
                                        }
                                        case "edit" -> {
                                            if (sender.hasPermission("profitable.admin.assets.manage.edit")) {
                                                switch (args.length) {
                                                    case 5 -> suggestions.add("[<New symbol>]");
                                                    case 6 -> suggestions.add("[<New name>]");
                                                    case 7 -> suggestions.add("[<New hex color>]");
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return suggestions;
        }
    }

}
