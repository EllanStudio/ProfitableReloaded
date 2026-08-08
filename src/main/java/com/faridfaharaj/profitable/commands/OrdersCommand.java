package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Orders;
import com.faridfaharaj.profitable.tasks.gui.guis.UserOrdersGui;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OrdersCommand  implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        if(sender instanceof Player player){

            if(args.length > 0 && args[0].equalsIgnoreCase("cancelall")){
                cancelAllOrders(player);
                return true;
            }

            new UserOrdersGui(player, null).openGui(player);

        }else{
            MessagingUtil.sendGenericCantConsole(sender);
        }
        return true;
    }

    private void cancelAllOrders(Player player){

        if(!player.hasPermission("profitable.account.manage.orders.cancelall")){
            MessagingUtil.sendGenericMissingPerm(player);
            return;
        }

        Profitable.getfolialib().getScheduler().runAsync(task -> {

            List<Order> orders = Orders.getAccountOrders(player.getWorld(), Accounts.getAccount(player));

            if(orders.isEmpty()){
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("orders.error.no-orders"));
                return;
            }

            int cancelled = 0;
            for(Order order : orders){
                if(Orders.cancelOrder(order.getUuid(), player)){
                    cancelled++;
                }
            }

            int finalCancelled = cancelled;
            Profitable.getfolialib().getScheduler().runAtEntity(player, soundTask ->
                    player.playSound(player, Sound.ENTITY_ITEM_BREAK, 1, 1));

            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("orders.cancelall",
                    Map.entry("%amount%", String.valueOf(finalCancelled))
            ));

        });

    }

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {

            if(args.length == 1){
                List<String> suggestions = new ArrayList<>();
                org.bukkit.util.StringUtil.copyPartialMatches(args[0], List.of("cancelall"), suggestions);
                return suggestions;
            }

            return List.of();

        }

    }

}
