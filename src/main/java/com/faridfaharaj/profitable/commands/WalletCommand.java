package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.tables.AccountHoldings;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.hooks.PlayerPointsHook;
import com.faridfaharaj.profitable.tasks.gui.guis.HoldingsMenu;
import com.faridfaharaj.profitable.util.MessagingUtil;
import com.faridfaharaj.profitable.hooks.VaultHook;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

import static com.faridfaharaj.profitable.data.holderClasses.Asset.retrieveCommodityEntity;
import static com.faridfaharaj.profitable.data.holderClasses.Asset.retrieveCommodityItem;

public class WalletCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        if(sender instanceof Player player){

            if(args.length == 0){

                if(!sender.hasPermission("profitable.account.info.wallet")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                new HoldingsMenu(player, null).openGui(player);

                return true;
            }


            if(args[0].equals("deposit")){

                if(!sender.hasPermission("profitable.account.funds.deposit")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                if(args.length < 2){
                    MessagingUtil.sendSyntaxError(sender, "/wallet deposit <Asset|hand> [Amount]");
                    return true;
                }

                String assetid;
                if(args[1].equals("hand")){
                    assetid = player.getInventory().getItemInMainHand().getType().name();
                }else {
                    assetid = args[1].toUpperCase();
                }

                Asset asset = Assets.getAssetData(player.getWorld(), assetid);

                if(asset == null){
                    MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("assets.error.asset-not-found",
                        Map.entry("%asset%", assetid)
                    ));
                    return true;
                }

                double ammount;
                if(args.length != 2){
                    try{
                        ammount = Double.parseDouble(args[2]);
                        if(!Double.isFinite(ammount) || ammount <= 0){
                            MessagingUtil.sendGenericInvalidAmount(sender, args[2]);
                            return true;
                        }
                    }catch (Exception e){
                        MessagingUtil.sendGenericInvalidAmount(sender, args[2]);
                        return true;
                    }
                }else {
                    if(args[1].equals("hand")){
                        ammount = player.getInventory().getItemInMainHand().getAmount();
                    }else {
                        ammount = 1;
                    }
                }

                depositAsset(asset, ammount, player);

                return true;
            }else if(args[0].equals("withdraw")){

                if(!sender.hasPermission("profitable.account.funds.withdraw")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                if(args.length < 2){
                    MessagingUtil.sendSyntaxError(sender, "/wallet withdraw <Asset> [Amount]");
                    return true;
                }

                String assetid;
                if(args[1].equals("hand")){
                    assetid = player.getInventory().getItemInMainHand().getType().name();
                }else {
                    assetid = args[1].toUpperCase();
                }

                Asset asset = Assets.getAssetData(player.getWorld(), assetid);

                if(asset == null){
                    MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("assets.error.asset-not-found",
                            Map.entry("%asset%", assetid)
                    ));
                    return true;
                }

                double ammount;
                if(args.length != 2){
                    try{
                        ammount = Double.parseDouble(args[2]);
                        if(!Double.isFinite(ammount) || ammount <= 0){
                            MessagingUtil.sendGenericInvalidAmount(sender, args[2]);
                            return true;
                        }
                    }catch (Exception e){
                        MessagingUtil.sendGenericInvalidAmount(sender, args[2]);
                        return true;
                    }
                }else {
                    ammount = 1;
                }

                withdrawAsset(asset, ammount, player);

                return true;
            }

            MessagingUtil.sendGenericInvalidSubCom(sender, args[0]);
            return true;
        }else{
            MessagingUtil.sendGenericCantConsole(sender);
        }




        return true;
    }

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {

            List<String> suggestions = new ArrayList<>();

            if(args.length == 1){
                if(commandSender.hasPermission("profitable.account.funds.deposit")){
                    suggestions.add("deposit");
                }
                if(commandSender.hasPermission("profitable.account.funds.withdraw")){
                    suggestions.add("withdraw");
                }
            }else{

                if(Objects.equals(args[0], "deposit") && commandSender.hasPermission("profitable.account.funds.deposit")){

                    if(args.length == 2){
                        suggestions.add("[<Asset>]");
                        suggestions.add("hand");
                    }

                    if(args.length == 3){
                        suggestions = List.of("[<Amount>]");
                    }

                }

                if(Objects.equals(args[0], "withdraw") && commandSender.hasPermission("profitable.account.funds.withdraw")){

                    if(args.length == 2){
                        suggestions.add("[<Asset>]");
                    }

                    if(args.length == 3){
                        suggestions = List.of("[<Amount>]");
                    }

                }

            }

            return suggestions;

        }

    }

    public static void depositAsset(Asset asset, double amount, Player player){

        if(!player.hasPermission("profitable.account.funds.deposit")){
            MessagingUtil.sendGenericMissingPerm(player);
            return;
        }
        if(!Double.isFinite(amount) || amount <= 0){
            MessagingUtil.sendGenericInvalidAmount(player, String.valueOf(amount));
            return;
        }

        if(asset.getAssetType() == 1){
            depositCurrency(asset, amount, player);
            return;

        }

        OptionalInt physicalUnits = WalletTransferAmounts.physicalUnits(amount);
        if (physicalUnits.isEmpty()) {
            sendInvalidPhysicalAmount(player, asset, amount);
            return;
        }
        int units = physicalUnits.getAsInt();

        if(asset.getAssetType() == 2){
            World world = player.getWorld();
            String account = Accounts.getAccount(player);
            Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
                if(retrieveCommodityItem(player, asset.getCode(), units)){
                    Profitable.getfolialib().getScheduler().runAsync(databaseTask -> {
                        if (AccountHoldings.addHolding(world, account, asset.getCode(), units)) {
                            MessagingUtil.sendPaymentNotice(player, units, 0, asset);
                            return;
                        }
                        Asset.sendItemToPlayer(player, asset.getCode(), units, compensated ->
                                reportPhysicalDepositFailure(player, compensated,
                                        "Item deposit compensation failed for " + player.getUniqueId()));
                    });
                }else {
                    sendInsufficientAsset(player, asset);
                }

            });
            return;

        }

        if(asset.getAssetType() == 3){
            World world = player.getWorld();
            String account = Accounts.getAccount(player);
            Profitable.getfolialib().getScheduler().runAsync(databaseTask -> {
                String claimId = Accounts.getEntityClaimId(world, account);
                Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
                    if(retrieveCommodityEntity(player, asset.getCode(), claimId, units)){
                        Profitable.getfolialib().getScheduler().runAsync(creditTask -> {
                            if (AccountHoldings.addHolding(world, account, asset.getCode(), units)) {
                                MessagingUtil.sendPaymentNotice(player, units, 0, asset);
                                return;
                            }
                            Asset.sendCommodityEntityToPlayerWithClaim(player, asset.getCode(), units, claimId,
                                    compensated -> reportPhysicalDepositFailure(player, compensated,
                                            "Entity deposit compensation failed for " + player.getUniqueId()));
                        });
                    }else{
                        sendInsufficientAsset(player, asset);
                    }
                });
            });
            return;

        }

    }

    public static void withdrawAsset(Asset asset, double ammount, Player player){

        if(!player.hasPermission("profitable.account.funds.withdraw")){
            MessagingUtil.sendGenericMissingPerm(player);
            return;
        }
        if(!Double.isFinite(ammount) || ammount <= 0){
            MessagingUtil.sendGenericInvalidAmount(player, String.valueOf(ammount));
            return;
        }

        if(asset.getAssetType() == 1){
            withdrawCurrency(asset, ammount, player);
            return;

        }


        OptionalInt physicalUnits = WalletTransferAmounts.physicalUnits(ammount);
        if (physicalUnits.isEmpty()) {
            sendInvalidPhysicalAmount(player, asset, ammount);
            return;
        }
        int units = physicalUnits.getAsInt();

        if(asset.getAssetType() == 3){
            World world = player.getWorld();
            String account = Accounts.getAccount(player);
            Profitable.getfolialib().getScheduler().runAsync(task -> {
                String claimId = Accounts.getEntityClaimId(world, account);
                if (claimId == null) {
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("generic.error.internal"));
                    return;
                }
                if (!AccountHoldings.takeHolding(world, account, asset.getCode(), units)) {
                    sendInsufficientAsset(player, asset);
                    return;
                }
                Asset.sendCommodityEntityToPlayerWithClaim(player, asset.getCode(), units, claimId, delivered -> {
                    if (delivered) {
                        MessagingUtil.sendPaymentNotice(player, units, 0, asset);
                    } else {
                        refundPhysicalWithdrawal(world, account, asset, units, player,
                                "Entity withdrawal refund failed for " + player.getUniqueId());
                    }
                });
            });
            return;

        }

        if(asset.getAssetType() == 2){
            World world = player.getWorld();
            String account = Accounts.getAccount(player);
            Profitable.getfolialib().getScheduler().runAsync(task -> {
                if (!AccountHoldings.takeHolding(world, account, asset.getCode(), units)) {
                    sendInsufficientAsset(player, asset);
                    return;
                }
                Asset.sendItemToPlayer(player, asset.getCode(), units, delivered -> {
                    if (delivered) {
                        MessagingUtil.sendPaymentNotice(player, units, 0, asset);
                    } else {
                        refundPhysicalWithdrawal(world, account, asset, units, player,
                                "Item withdrawal refund failed for " + player.getUniqueId());
                    }
                });
            });
            return;

        }

    }

    private static void depositCurrency(Asset asset, double amount, Player player) {
        World world = player.getWorld();
        String account = Accounts.getAccount(player);
        double configuredFee = Configuration.parseFee(Configuration.DEPOSITFEES, amount);

        if (isVaultAsset(asset)) {
            var calculated = WalletTransferAmounts.decimal(amount, configuredFee);
            if (calculated.isEmpty()) {
                sendInvalidTransferAmount(player, asset, amount);
                return;
            }
            var amounts = calculated.get();
            EconomyResponse withdrawal = VaultHook.getEconomy().withdrawPlayer(player, amounts.gross());
            if (!withdrawal.transactionSuccess()) {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("hooks.error.insufficient-funds"));
                return;
            }

            Profitable.getfolialib().getScheduler().runAsync(task -> {
                if (AccountHoldings.addHolding(world, account, asset.getCode(), amounts.net())) {
                    MessagingUtil.sendPaymentNotice(player, amounts.gross(), amounts.fee(), asset);
                    return;
                }
                Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask -> {
                    EconomyResponse compensation = VaultHook.getEconomy().depositPlayer(player, amounts.gross());
                    reportCompensatedFailure(player, compensation.transactionSuccess(),
                            "Vault deposit compensation failed for " + player.getUniqueId());
                });
            });
            return;
        }

        if (isPlayerPointsAsset(asset)) {
            var calculated = WalletTransferAmounts.integral(amount, configuredFee);
            if (calculated.isEmpty()) {
                sendInvalidTransferAmount(player, asset, amount);
                return;
            }
            var amounts = calculated.get();
            if (!PlayerPointsHook.getApi().take(player.getUniqueId(), amounts.gross())) {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("hooks.error.insufficient-funds"));
                return;
            }

            Profitable.getfolialib().getScheduler().runAsync(task -> {
                if (AccountHoldings.addHolding(world, account, asset.getCode(), amounts.net())) {
                    MessagingUtil.sendPaymentNotice(player, amounts.gross(), amounts.fee(), asset);
                    return;
                }
                Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask -> {
                    boolean compensated = PlayerPointsHook.getApi().give(player.getUniqueId(), amounts.gross());
                    reportCompensatedFailure(player, compensated,
                            "PlayerPoints deposit compensation failed for " + player.getUniqueId());
                });
            });
            return;
        }

        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.not-depositable",
                Map.entry("%asset%", asset.getCode())
        ));
    }

    private static void withdrawCurrency(Asset asset, double amount, Player player) {
        World world = player.getWorld();
        String account = Accounts.getAccount(player);
        double configuredFee = Configuration.parseFee(Configuration.WITHDRAWALFEES, amount);

        if (isVaultAsset(asset)) {
            var calculated = WalletTransferAmounts.decimal(amount, configuredFee);
            if (calculated.isEmpty()) {
                sendInvalidTransferAmount(player, asset, amount);
                return;
            }
            var amounts = calculated.get();
            Profitable.getfolialib().getScheduler().runAsync(task -> {
                if (!AccountHoldings.takeHolding(world, account, asset.getCode(), amounts.gross())) {
                    sendInsufficientAsset(player, asset);
                    return;
                }
                Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask -> {
                    EconomyResponse payment = VaultHook.getEconomy().depositPlayer(player, amounts.net());
                    if (payment.transactionSuccess()) {
                        MessagingUtil.sendChargeNotice(player, amounts.net(), amounts.fee(), asset);
                        return;
                    }
                    refundInternalDebit(world, account, asset, amounts.gross(), player,
                            payment.errorMessage, "Vault withdrawal refund failed for " + player.getUniqueId());
                });
            });
            return;
        }

        if (isPlayerPointsAsset(asset)) {
            var calculated = WalletTransferAmounts.integral(amount, configuredFee);
            if (calculated.isEmpty()) {
                sendInvalidTransferAmount(player, asset, amount);
                return;
            }
            var amounts = calculated.get();
            Profitable.getfolialib().getScheduler().runAsync(task -> {
                if (!AccountHoldings.takeHolding(world, account, asset.getCode(), amounts.gross())) {
                    sendInsufficientAsset(player, asset);
                    return;
                }
                Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask -> {
                    if (PlayerPointsHook.getApi().give(player.getUniqueId(), amounts.net())) {
                        MessagingUtil.sendChargeNotice(player, amounts.net(), amounts.fee(), asset);
                        return;
                    }
                    refundInternalDebit(world, account, asset, amounts.gross(), player, null,
                            "PlayerPoints withdrawal refund failed for " + player.getUniqueId());
                });
            });
            return;
        }

        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.not-depositable",
                Map.entry("%asset%", asset.getCode())
        ));
    }

    private static void refundInternalDebit(World world, String account, Asset asset, double amount, Player player,
                                            String externalError, String logMessage) {
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            boolean compensated = AccountHoldings.addHolding(world, account, asset.getCode(), amount);
            if (!compensated) {
                Profitable.getInstance().getLogger().severe(logMessage);
                MessagingUtil.sendSyntaxError(player, "Wallet refund failed; contact an administrator.");
            } else if (externalError != null && !externalError.isBlank()) {
                MessagingUtil.sendSyntaxError(player, externalError);
            } else {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("generic.error.internal"));
            }
        });
    }

    private static void reportCompensatedFailure(Player player, boolean compensated, String logMessage) {
        if (!compensated) {
            Profitable.getInstance().getLogger().severe(logMessage);
            MessagingUtil.sendSyntaxError(player, "Wallet compensation failed; contact an administrator.");
            return;
        }
        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("generic.error.internal"));
    }

    private static void reportPhysicalDepositFailure(Player player, boolean compensated, String logMessage) {
        if (!compensated) {
            Profitable.getInstance().getLogger().severe(logMessage);
            MessagingUtil.sendSyntaxError(player, "Physical asset compensation failed; contact an administrator.");
            return;
        }
        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("generic.error.internal"));
    }

    private static void refundPhysicalWithdrawal(World world, String account, Asset asset, double amount,
                                                 Player player, String logMessage) {
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            if (!AccountHoldings.addHolding(world, account, asset.getCode(), amount)) {
                Profitable.getInstance().getLogger().severe(logMessage);
                MessagingUtil.sendSyntaxError(player, "Wallet refund failed; contact an administrator.");
                return;
            }
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("generic.error.internal"));
        });
    }

    private static boolean isVaultAsset(Asset asset) {
        return VaultHook.isConnected() && VaultHook.getAsset() != null
                && Objects.equals(VaultHook.getAsset().getCode(), asset.getCode());
    }

    private static boolean isPlayerPointsAsset(Asset asset) {
        return PlayerPointsHook.isConnected() && PlayerPointsHook.getAsset() != null
                && Objects.equals(PlayerPointsHook.getAsset().getCode(), asset.getCode());
    }

    private static void sendInvalidTransferAmount(Player player, Asset asset, double amount) {
        if (isPlayerPointsAsset(asset) && Double.isFinite(amount) && amount > 0
                && amount <= Integer.MAX_VALUE && amount != Math.rint(amount)) {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.cant-fractional",
                    Map.entry("%asset%", asset.getCode())
            ));
            return;
        }
        MessagingUtil.sendGenericInvalidAmount(player, String.valueOf(amount));
    }

    private static void sendInvalidPhysicalAmount(Player player, Asset asset, double amount) {
        if (Double.isFinite(amount) && amount > 0 && amount <= Integer.MAX_VALUE
                && amount != Math.rint(amount)) {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.cant-fractional",
                    Map.entry("%asset%", asset.getCode())
            ));
            return;
        }
        MessagingUtil.sendGenericInvalidAmount(player, String.valueOf(amount));
    }

    private static void sendInsufficientAsset(Player player, Asset asset) {
        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.not-enough-asset",
                Map.entry("%asset%", asset.getCode())
        ));
    }

}
