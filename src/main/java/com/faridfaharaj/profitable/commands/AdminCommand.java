package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Order;
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
import java.util.*;

public class AdminCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        if (args.length == 0) {
            MessagingUtil.sendSyntaxError(sender, "/profitable:admin <status|config|getplayeracc|forcelogout|assets|orders|account> ...");
            return true;
        }

        switch (args[0]) {
            case "status" -> handleStatus(sender);
            case "config" -> handleConfig(sender, args);
            case "getplayeracc" -> handleGetPlayerAcc(sender, args);
            case "forcelogout" -> handleForceLogout(sender, args);
            case "assets" -> handleAssets(sender, args);
            case "orders" -> handleOrders(sender, args);
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

    private void publishAssetRegistered(World world, String assetCode, int assetType) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("asset_registered", world.getName() + ":" + assetCode + ":" + assetType);
        }
    }

    private void publishAssetDeleted(World world, String assetCode) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("asset_deleted", world.getName() + ":" + assetCode);
        }
    }

    private void publishAssetUpdated(World world, String oldCode, Asset updated) {
        RedisManager rm = Profitable.getRedisManager();
        if (rm != null && rm.isConnected()) {
            rm.publishAsync("asset_updated", world.getName() + ":" + oldCode + ":" + updated.getCode() + ":"
                    + updated.getAssetType() + ":" + updated.getColor().value() + ":" + updated.getName().replace(":", " "));
        }
    }

    // ---------- status ----------

    private void handleStatus(CommandSender sender) {

        if (!sender.hasPermission("profitable.admin.info.status")) {
            MessagingUtil.sendGenericMissingPerm(sender);
            return;
        }

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            World world = resolveWorld(sender, null);

            int assetCount = Assets.getAll(world).size();
            int orderCount = Orders.getAllOrders().size();
            int sessions = Accounts.getCurrentAccounts().size();
            int online = Profitable.getInstance().getServer().getOnlinePlayers().size();

            RedisManager rm = Profitable.getRedisManager();
            String redisStatus = rm == null ? "disabled" : (rm.isConnected() ? "connected" : "disconnected");
            String dbType = Profitable.getInstance().getConfig().getInt("database.database-type") == 0 ? "SQLite" : "MySQL";
            String multiworld = Configuration.MULTIWORLD ? "per-world" : "server-wide";

            Component component = Component.text("========== [ ", Configuration.COLORPROFITABLE)
                    .append(Component.text("Exchange Status", Configuration.COLORHIGHLIGHT))
                    .append(Component.text(" ] ==========", Configuration.COLORPROFITABLE)).appendNewline()

                    .append(Component.text("Version: ", Configuration.COLORTEXT))
                    .append(Component.text(Profitable.getInstance().getPluginMeta().getVersion(), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Main currency: ", Configuration.COLORTEXT))
                    .append(Component.text(Configuration.MAINCURRENCYASSET.getCode(), Configuration.MAINCURRENCYASSET.getColor())).appendNewline()

                    .append(Component.text("Database: ", Configuration.COLORTEXT))
                    .append(Component.text(dbType + " (" + multiworld + ")", Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Redis sync: ", Configuration.COLORTEXT))
                    .append(Component.text(redisStatus, rm != null && rm.isConnected() ? Configuration.COLORBULLISH : Configuration.COLORWARN)).appendNewline()

                    .append(Component.text("Registered assets: ", Configuration.COLORTEXT))
                    .append(Component.text(String.valueOf(assetCount), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Open orders: ", Configuration.COLORTEXT))
                    .append(Component.text(String.valueOf(orderCount), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("Active sessions: ", Configuration.COLORTEXT))
                    .append(Component.text(sessions + " / " + online + " players online", Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                    .append(Component.text("====================================", Configuration.COLORPROFITABLE));

            MessagingUtil.sendComponentMessage(sender, component);
        });
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

            Profitable.getfolialib().getScheduler().runAsync(task -> {
                World world = resolveWorld(sender, null);
                Collection<String> assets = Assets.getAll(world);

                MessagingUtil.sendComponentMessage(sender,
                        Component.text("Showing all " + assets.size() + " registered assets in " + world.getName() + ":").color(Configuration.COLORHIGHLIGHT).appendNewline()
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

        String asset = args[3].toUpperCase();

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            World world = resolveWorld(sender, null);

            switch (args[2].toLowerCase()) {
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

                    try {
                        if (Assets.registerAsset(world, asset, 1, Asset.metaData(Asset.StringToCurrency(generator)))) {
                            publishAssetRegistered(world, asset, 1);
                            sendSuccess(sender, "Registered: " + asset);
                        } else {
                            MessagingUtil.sendSyntaxError(sender, "There is already an asset with Symbol: " + asset);
                        }
                    } catch (IOException e) {
                        MessagingUtil.sendSyntaxError(sender, "Error registering " + asset);
                    }
                }

                case "commodityitem" -> {

                    if (Material.getMaterial(asset) == null) {
                        MessagingUtil.sendSyntaxError(sender, "Commodities must come from an existing item");
                        return;
                    }

                    try {
                        if (Assets.registerAsset(world, asset, 2, Asset.metaData(Configuration.COLORHIGHLIGHT.value(), NamingUtil.nameCommodity(asset)))) {
                            updateCommodityConfigLists(true, asset);
                            publishAssetRegistered(world, asset, 2);
                            sendSuccess(sender, "Registered: " + asset);
                        } else {
                            MessagingUtil.sendSyntaxError(sender, asset + " is already registered");
                        }
                    } catch (IOException e) {
                        MessagingUtil.sendSyntaxError(sender, "Error registering " + asset);
                    }
                }

                case "commodityentity" -> {

                    EntityType entity = EntityType.fromName(asset);
                    if (entity == null) {
                        MessagingUtil.sendSyntaxError(sender, "Commodities must come from an existing entity");
                        return;
                    }

                    Class<?> entityClass = entity.getEntityClass();
                    if (entityClass == null || !LivingEntity.class.isAssignableFrom(entityClass) || entity.name().equals("PLAYER")) {
                        MessagingUtil.sendSyntaxError(sender, "Invalid asset");
                        return;
                    }

                    try {
                        if (Assets.registerAsset(world, asset, 3, Asset.metaData(Configuration.COLORHIGHLIGHT.value(), NamingUtil.nameCommodity(asset)))) {
                            updateCommodityConfigLists(false, asset);
                            publishAssetRegistered(world, asset, 3);
                            sendSuccess(sender, "Registered: " + asset);
                        } else {
                            MessagingUtil.sendSyntaxError(sender, asset + " is already registered");
                        }
                    } catch (IOException e) {
                        MessagingUtil.sendSyntaxError(sender, "Error registering " + asset);
                    }
                }

                default -> MessagingUtil.sendSyntaxError(sender, "Invalid asset type: " + args[2]);
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

        String assetCode = args[2].toUpperCase();

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            World world = resolveWorld(sender, null);

            switch (args[3].toLowerCase()) {
                case "newtransaction" -> {

                    if (!sender.hasPermission("profitable.admin.assets.manage.newtransaction")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    if (args.length < 6) {
                        MessagingUtil.sendSyntaxError(sender, "/admin assets fromid " + assetCode + " newtransaction <price> <volume>");
                        return;
                    }

                    try {
                        double price = Double.parseDouble(args[4]);
                        double volume = Double.parseDouble(args[5]);
                        if (Candles.updateDay(world, assetCode, price, volume)) {
                            sendSuccess(sender, "Inserted transaction in " + assetCode);
                        } else {
                            MessagingUtil.sendSyntaxError(sender, "Could not insert transaction on " + assetCode);
                        }
                    } catch (NumberFormatException e) {
                        MessagingUtil.sendGenericInvalidAmount(sender, args[4] + " / " + args[5]);
                    }
                }

                case "resettransactions" -> {

                    if (!sender.hasPermission("profitable.admin.assets.manage.resettransactions")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    Candles.assetDeleteAllCandles(world, assetCode);
                    sendSuccess(sender, "Wiped all " + assetCode + "'s transactions");
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

                    if (!Objects.equals(assetCode, args[4].toUpperCase())) {
                        MessagingUtil.sendSyntaxError(sender, "Assets don't match");
                        return;
                    }

                    if (Objects.equals(assetCode, Configuration.MAINCURRENCYASSET.getCode())) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot remove main currency");
                        return;
                    }

                    Asset asset = Assets.getAssetData(world, assetCode);
                    if (asset == null) {
                        MessagingUtil.sendSyntaxError(sender, "This asset does not exist");
                        return;
                    }

                    if (Assets.deleteAsset(world, assetCode)) {
                        // Clean up orphaned orders and candles so nothing references the deleted asset
                        List<Order> orphaned = Orders.getAssetOrders(world, assetCode);
                        Orders.deleteOrders(world, orphaned);
                        Candles.assetDeleteAllCandles(world, assetCode);

                        if (Configuration.GENERATEASSETS) {
                            if (asset.getAssetType() == 2) {
                                removeCommodityFromConfigLists(true, assetCode);
                            } else if (asset.getAssetType() == 3) {
                                removeCommodityFromConfigLists(false, assetCode);
                            }
                        }

                        publishAssetDeleted(world, assetCode);
                        MessagingUtil.sendComponentMessage(sender, Component.text("DELETED " + assetCode, NamedTextColor.RED));
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Could not delete that asset");
                    }
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

                    Asset asset = Assets.getAssetData(world, assetCode);
                    if (asset == null) {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't find asset: " + assetCode);
                        return;
                    }

                    if (asset.getAssetType() == 2 || asset.getAssetType() == 3) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit commodities");
                        return;
                    }

                    String newCode = args[4].toUpperCase();

                    if (newCode.length() > 3) {
                        MessagingUtil.sendSyntaxError(sender, "Currencies must only have 3 letters");
                        return;
                    }

                    if (!Objects.equals(newCode, asset.getCode()) && Assets.getAssetData(world, newCode) != null) {
                        MessagingUtil.sendSyntaxError(sender, "There is already an asset with Symbol: " + newCode);
                        return;
                    }

                    if (Objects.equals(asset.getCode(), Configuration.MAINCURRENCYASSET.getCode())) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit the main currency");
                        return;
                    }

                    if (VaultHook.isConnected() && Objects.equals(asset.getCode(), VaultHook.getAsset().getCode())) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit Vault output currency, change on config!");
                        return;
                    }

                    if (PlayerPointsHook.isConnected() && Objects.equals(asset.getCode(), PlayerPointsHook.getAsset().getCode())) {
                        MessagingUtil.sendSyntaxError(sender, "Cannot edit the PlayerPoints output currency, change on config!");
                        return;
                    }

                    String name = args.length > 5 ? args[5] : asset.getName();

                    TextColor color = null;
                    if (args.length > 6) {
                        color = TextColor.fromHexString(args[6]);
                    }
                    if (color == null) {
                        color = asset.getColor();
                    }

                    Asset updated = new Asset(newCode, asset.getAssetType(), color, name);
                    if (Assets.updateAsset(world, asset.getCode(), updated)) {
                        publishAssetUpdated(world, asset.getCode(), updated);
                        sendSuccess(sender, "Updated " + newCode);
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't edit this asset");
                    }
                }

                default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[3]);
            }
        });
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

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    World world = resolveWorld(sender, null);
                    List<Order> orders = Orders.getAssetOrders(world, args[2].toUpperCase());
                    sendOrdersList(sender, "Showing all active orders for " + args[2].toUpperCase() + ":", orders);
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

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    World world = resolveWorld(sender, null);

                    if (Objects.equals(args[3], "cancel")) {

                        if (!sender.hasPermission("profitable.admin.orders.manage.cancel")) {
                            MessagingUtil.sendGenericMissingPerm(sender);
                            return;
                        }

                        if (Orders.cancelOrder(world, orderUuid)) {
                            sendSuccess(sender, "Cancelled: " + args[2]);
                        } else {
                            MessagingUtil.sendSyntaxError(sender, "Couldn't cancel that order");
                        }

                    } else if (Objects.equals(args[3], "delete")) {

                        if (!sender.hasPermission("profitable.admin.orders.manage.delete")) {
                            MessagingUtil.sendGenericMissingPerm(sender);
                            return;
                        }

                        if (Orders.deleteOrder(world, orderUuid)) {
                            MessagingUtil.sendComponentMessage(sender, Component.text("DELETED order " + args[2], NamedTextColor.RED));
                        } else {
                            MessagingUtil.sendSyntaxError(sender, "Couldn't delete that order");
                        }

                    } else {
                        MessagingUtil.sendGenericInvalidSubCom(sender, args[3]);
                    }
                });
            }

            case "deleteall" -> {

                if (!sender.hasPermission("profitable.admin.orders.manage.deleteall")) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return;
                }

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    if (Orders.deleteAllOrders()) {
                        MessagingUtil.sendComponentMessage(sender, Component.text("DELETED all orders from all assets", NamedTextColor.RED));
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

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    int cancelled = 0;
                    for (World iteratedWorld : Profitable.getInstance().getServer().getWorlds()) {
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

                boolean sideBuy = Objects.equals(args[3].toLowerCase(), "buy");

                double units;
                double price;
                try {
                    units = Double.parseDouble(args[4]);
                    price = Double.parseDouble(args[5]);
                } catch (NumberFormatException e) {
                    MessagingUtil.sendGenericInvalidAmount(sender, args[4] + " / " + args[5]);
                    return;
                }

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    World world = resolveWorld(sender, null);
                    if (Orders.insertOrder(world, UUID.randomUUID(), "server", args[2].toUpperCase(), sideBuy, price, units, Order.OrderType.LIMIT)) {
                        sendSuccess(sender, "Inserted new limit order " + (sideBuy ? "buy" : "sell") + " " + units + " " + args[2].toUpperCase() + " at $" + price + " on server's account");
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Couldn't add order for " + args[2]);
                    }
                });
            }

            default -> MessagingUtil.sendGenericInvalidSubCom(sender, args[1]);
        }
    }

    private void sendOrdersList(CommandSender sender, String header, List<Order> orders) {

        if (orders.isEmpty()) {
            MessagingUtil.sendComponentMessage(sender, Component.text(header, Configuration.COLORHIGHLIGHT).appendNewline()
                    .append(Component.text("No active orders", Configuration.COLOREMPTY)));
            return;
        }

        Component component = Component.text(header).color(Configuration.COLORHIGHLIGHT).appendNewline()
                .append(Component.text("--------------------------------------------")).appendNewline();

        for (Order order : orders) {
            component = component.append(order.toComponent()).appendNewline()
                    .append(Component.text("[Cancel] ", Configuration.COLORWARN)
                            .clickEvent(ClickEvent.runCommand("/profitable:admin orders getbyid " + order.getUuid() + " cancel"))
                            .hoverEvent(HoverEvent.showText(Component.text("Cancel this order and give back collateral to owner"))))
                    .append(Component.text("[Delete]", NamedTextColor.RED)
                            .clickEvent(ClickEvent.runCommand("/profitable:admin orders getbyid " + order.getUuid() + " delete"))
                            .hoverEvent(HoverEvent.showText(Component.text("Delete this order, no compensation")))).appendNewline();
        }
        component = component.append(Component.text("--------------------------------------------"));
        MessagingUtil.sendComponentMessage(sender, component);
    }

    // ---------- account ----------

    private void handleAccount(CommandSender sender, String[] args) {

        if (args.length < 3) {
            MessagingUtil.sendSyntaxError(sender, "/admin account <account> <wallet|passwordreset|orders|delivery|claimid|delete> ...");
            return;
        }

        String account = args[1];

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            World world = resolveWorld(sender, null);

            switch (args[2].toLowerCase()) {
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

                    if (args.length < 5) {
                        MessagingUtil.sendSyntaxError(sender, "/admin account <account> wallet <Asset> <Amount>");
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

                    if (AccountHoldings.setHolding(world, account, args[3].toUpperCase(), amount)) {
                        sendSuccess(sender, "Set " + args[3].toUpperCase() + " to " + amount + " on " + account + "'s wallet");
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Could not set " + args[3]);
                    }
                }

                case "passwordreset" -> {

                    if (Objects.equals(account, "server")) {
                        MessagingUtil.sendSyntaxError(sender, "Not a good idea");
                        return;
                    }

                    if (!sender.hasPermission("profitable.admin.accounts.manage.passwordreset")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    if (Accounts.changePassword(world, account, "1234")) {
                        sendSuccess(sender, account + "'s password set to '1234' for recovery");
                    } else {
                        MessagingUtil.sendSyntaxError(sender, "Account couldn't be found");
                    }
                }

                case "orders" -> {

                    if (!sender.hasPermission("profitable.admin.accounts.info.orders")) {
                        MessagingUtil.sendGenericMissingPerm(sender);
                        return;
                    }

                    sendOrdersList(sender, "Showing all active orders on account " + account + ":", Orders.getAccountOrders(world, account));
                }

                case "delivery" -> handleAccountDelivery(sender, args, world, account);

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
                        MessagingUtil.sendSyntaxError(sender, "Couldn't delete " + account);
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
                StringUtil.copyPartialMatches(args[0], List.of("status", "config", "getplayeracc", "forcelogout", "assets", "orders", "account"), suggestions);
                return suggestions;
            }

            switch (args[0]) {
                case "getplayeracc", "forcelogout" -> {
                    if (args.length == 2) {
                        return null; // default player suggestions
                    }
                }

                case "config" -> {
                    if (args.length == 2) {
                        StringUtil.copyPartialMatches(args[1], List.of("reloadconfig"), suggestions);
                    }
                }

                case "account" -> {
                    if (args.length == 2) {
                        return null;
                    }
                    if (args.length == 3) {
                        StringUtil.copyPartialMatches(args[2], List.of("wallet", "passwordreset", "orders", "delivery", "claimid", "delete"), suggestions);
                    }
                    if (args.length > 3) {
                        switch (args[2]) {
                            case "delete" -> {
                                if (args.length == 4) {
                                    suggestions.add("[<Account>]");
                                }
                            }
                            case "wallet" -> {
                                if (args.length == 4) {
                                    suggestions.add("[<Asset>]");
                                } else if (args.length == 5) {
                                    suggestions.add("[<Amount>]");
                                }
                            }
                            case "delivery" -> {
                                switch (args.length) {
                                    case 4 -> StringUtil.copyPartialMatches(args[3], List.of("setitem", "setentity"), suggestions);
                                    case 5 -> suggestions.add("[<x>]");
                                    case 6 -> suggestions.add("[<y>]");
                                    case 7 -> suggestions.add("[<z>]");
                                    case 8 -> suggestions.add("[<world name>]");
                                }
                            }
                        }
                    }
                }

                case "orders" -> {
                    if (args.length == 2) {
                        StringUtil.copyPartialMatches(args[1], List.of("findbyasset", "getbyid", "deleteall", "cancelall", "newlimitorder"), suggestions);
                    } else {
                        switch (args[1]) {
                            case "newlimitorder" -> {
                                switch (args.length) {
                                    case 3 -> suggestions.add("[<Asset>]");
                                    case 4 -> StringUtil.copyPartialMatches(args[3], List.of("buy", "sell"), suggestions);
                                    case 5 -> suggestions.add("[<Units>]");
                                    case 6 -> suggestions.add("[<Price>]");
                                }
                            }
                            case "findbyasset" -> {
                                if (args.length == 3) {
                                    suggestions.add("[<Asset>]");
                                }
                            }
                            case "getbyid" -> {
                                if (args.length == 3) {
                                    suggestions.add("[<ID>]");
                                } else if (args.length == 4) {
                                    StringUtil.copyPartialMatches(args[3], List.of("cancel", "delete"), suggestions);
                                }
                            }
                        }
                    }
                }

                case "assets" -> {
                    if (args.length == 2) {
                        StringUtil.copyPartialMatches(args[1], List.of("register", "fromid"), suggestions);
                    } else {
                        switch (args[1]) {
                            case "register" -> {
                                switch (args.length) {
                                    case 3 -> StringUtil.copyPartialMatches(args[2], List.of("currency", "commodityitem", "commodityentity"), suggestions);
                                    case 4 -> suggestions.add("[<Symbol>]");
                                    case 5 -> suggestions.add("[<Name>]");
                                    case 6 -> suggestions.add("[<Hex Color>]");
                                }
                            }
                            case "fromid" -> {
                                if (args.length == 3) {
                                    suggestions.add("[<Asset>]");
                                } else if (args.length == 4) {
                                    StringUtil.copyPartialMatches(args[3], List.of("newtransaction", "resettransactions", "delete", "edit"), suggestions);
                                } else {
                                    switch (args[3]) {
                                        case "newtransaction" -> {
                                            if (args.length == 5) {
                                                suggestions.add("[<price>]");
                                            } else if (args.length == 6) {
                                                suggestions.add("[<volume>]");
                                            }
                                        }
                                        case "delete" -> {
                                            if (args.length == 5) {
                                                suggestions.add("[<Asset again>]");
                                            }
                                        }
                                        case "edit" -> {
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

            return suggestions;
        }
    }

}
