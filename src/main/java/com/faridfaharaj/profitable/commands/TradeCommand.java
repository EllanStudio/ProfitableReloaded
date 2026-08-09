package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.tasks.gui.guis.TradeGui;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;


public class TradeCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if(sender instanceof Player player){

            if(!player.hasPermission("profitable.market.trade.gui")){
                MessagingUtil.sendGenericMissingPerm(sender);
                return true;
            }

            new TradeGui(player, 2, null).openGui(player);
            return true;
        }else {
            MessagingUtil.sendGenericCantConsole(sender);
        }


        return true;
    }

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {

            return Collections.emptyList();

        }

    }
}
