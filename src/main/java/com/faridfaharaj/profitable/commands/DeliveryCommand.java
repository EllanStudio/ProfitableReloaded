package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public class DeliveryCommand implements CommandExecutor {


    @Override
    public boolean onCommand( CommandSender sender,  Command command,  String s,  String[] args) {
        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("delivery.deprecated"));
        return true;
    }

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {

            return List.of();

        }

    }

}
