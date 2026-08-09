package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Candle;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.data.tables.Candles;
import com.faridfaharaj.profitable.hooks.PlayerPointsHook;
import com.faridfaharaj.profitable.hooks.VaultHook;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;

public class PriceCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        if(sender instanceof Player player){

            if(!sender.hasPermission("profitable.asset.price")){
                MessagingUtil.sendGenericMissingPerm(sender);
                return true;
            }

            String asset;
            if(args.length == 0 || args[0].equalsIgnoreCase("hand")){
                Material material = player.getInventory().getItemInMainHand().getType();
                asset = material.name();
            }else{
                asset = args[0].toUpperCase();
            }

            if(Objects.equals(asset, Configuration.MAINCURRENCYASSET.getCode())){
                MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("price.error.main-currency",
                        Map.entry("%asset%", asset)
                ));
                return true;
            }

            String finalAsset = asset;
            Profitable.getfolialib().getScheduler().runAsync(task -> {

                if(Assets.getAssetData(player.getWorld(), finalAsset) == null){
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.asset-not-found",
                            Map.entry("%asset%", finalAsset)
                    ));
                    return;
                }

                Candle summary = Candles.getPriceSummary(player.getWorld(), finalAsset);

                if(summary == null){
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("price.no-data",
                            Map.entry("%asset%", finalAsset)
                    ));
                    return;
                }

                double price = summary.getClose();
                double previous = summary.getOpen();
                double change = price - previous;
                double percent = previous > 0 ? change / previous * 100 : 0;

                TextColor changeColor = change < 0 ? Configuration.COLORBEARISH : Configuration.COLORBULLISH;
                String sign = change < 0 ? "" : "+";

                Component component = Component.text("========= [ ", Configuration.COLORPROFITABLE)
                        .append(Component.text(finalAsset, Configuration.COLORHIGHLIGHT))
                        .append(Component.text(" ] =========", Configuration.COLORPROFITABLE)).appendNewline()

                        .append(Component.text("Price: ", Configuration.COLORTEXT))
                        .append(Component.text("$" + MessagingUtil.formatNumber(price), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                        .append(Component.text("Day change: ", Configuration.COLORTEXT))
                        .append(Component.text(sign + MessagingUtil.formatNumber(change) + "  (" + sign + String.format("%.2f", percent) + "%)", changeColor)).appendNewline()

                        .append(Component.text("Day's range: ", Configuration.COLORTEXT))
                        .append(Component.text("$" + MessagingUtil.formatNumber(summary.getLow()) + " - $" + MessagingUtil.formatNumber(summary.getHigh()), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                        .append(Component.text("Volume: ", Configuration.COLORTEXT))
                        .append(Component.text(MessagingUtil.formatVolume(summary.getVolume()), Configuration.GUICOLORHIGHLIGHT)).appendNewline()

                        .append(Component.text("[Trade] ", Configuration.COLORHIGHLIGHT)
                                .clickEvent(ClickEvent.runCommand("/profitablereloaded:assets"))
                                .hoverEvent(HoverEvent.showText(Component.text("/assets", Configuration.COLORHIGHLIGHT))));

                MessagingUtil.sendComponentMessage(player, component);

            });

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
                List<String> options = new ArrayList<>(Configuration.ALLOWEITEMS);
                options.addAll(Configuration.ALLOWENTITIES);
                if(VaultHook.isConnected()){
                    options.add(VaultHook.getAsset().getCode());
                }
                if(PlayerPointsHook.isConnected()){
                    options.add(PlayerPointsHook.getAsset().getCode());
                }
                options.add("hand");

                StringUtil.copyPartialMatches(args[0].toUpperCase(), options.stream().sorted().toList(), suggestions);
            }

            return suggestions;

        }

    }
}
