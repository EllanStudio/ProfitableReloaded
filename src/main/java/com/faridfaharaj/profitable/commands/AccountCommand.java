package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;

public class AccountCommand implements CommandExecutor {


    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        if(sender instanceof Player player){
            World world = player.getWorld();
            UUID playerId = player.getUniqueId();

            if(args.length == 0){
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    String account = Accounts.getAccount(world, playerId);

                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("account.display",
                            Map.entry("%account%", Objects.equals(account, playerId.toString())? Profitable.getLang().getString("account.default-name") : MessagingUtil.escapeMiniMessage(account))
                    ));

                });
                return true;
            }

            //BASIC ACCOUNT COMMANDS
            if(args[0].equals("register")){

                if(!sender.hasPermission("profitable.account.manage.register")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                if(args.length < 4){
                    MessagingUtil.sendSyntaxError(sender, "/account register <Username> <Password> <Repeat Password>");
                    return true;
                }

                if (!args[1].matches("[A-Za-z0-9_]{3,36}")) {
                    MessagingUtil.sendSyntaxError(sender, "Account names must contain 3-36 letters, digits or underscores");
                    return true;
                }
                if (args[2].length() < 8) {
                    MessagingUtil.sendSyntaxError(sender, "Passwords must contain at least 8 characters");
                    return true;
                }

                if(Objects.equals(args[2], args[3])){
                    if(args[2].length() < 32){
                        String accountName = args[1];
                        String password = args[2];
                        Profitable.getfolialib().getScheduler().runAsync(task -> {
                            if(Accounts.registerAccount(world, accountName, password)){

                                MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.registry",
                                        Map.entry("%account%", MessagingUtil.escapeMiniMessage(accountName))
                                ));

                            }else{
                                MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.account-already-exists"));
                            }
                        });
                        return true;
                    }else{
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.password-too-long"));
                        return true;
                    }
                }else {
                    MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.password-mismatch"));
                    return true;
                }

            }

            if(args[0].equals("delete")){

                if(!sender.hasPermission("profitable.account.manage.delete")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                if(args.length < 3){
                    MessagingUtil.sendSyntaxError(sender, "/account delete <Account> <password>");
                    return true;
                }

                String account = args[1];
                String password = args[2];

                if(Objects.equals(playerId.toString(), account)){
                    MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.cant-delete-default"));
                    return true;
                }

                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    if(Objects.equals(account, Accounts.getAccount(world, playerId))){
                        if(!Accounts.comparePasswords(world, account, password)){
                            MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.wrong-password"));
                            return;
                        }

                        boolean usedByAnotherPlayer = Accounts.getCurrentAccounts().entrySet().stream()
                                .anyMatch(entry -> !entry.getKey().equals(playerId)
                                        && Objects.equals(entry.getValue(), account));
                        if(usedByAnotherPlayer) {
                            MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.cant-delete-active-account"));
                        }else{
                            if (Accounts.deleteAccount(world, account)) {
                                Accounts.logOut(playerId);
                                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("account.delete",
                                        Map.entry("%account%", MessagingUtil.escapeMiniMessage(account))
                                ));
                            } else {
                                MessagingUtil.sendSyntaxError(sender,
                                        "Account was not deleted: it may be missing or still own open orders");
                            }
                        }

                    }else{
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.active-account-mismatch"));
                    }
                });

                return true;

            }

            if(args[0].equals("login")){

                if(!sender.hasPermission("profitable.account.manage.login")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                if(args.length < 3){
                    MessagingUtil.sendSyntaxError(sender, "/account login <Account> <Password>");
                    return true;
                }

                String accountName = args[1];
                String password = args[2];
                Profitable.getfolialib().getScheduler().runAsync(task -> {

                    if(Accounts.logIn(world, playerId, accountName, password)){
                        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("account.login",
                                Map.entry("%account%", MessagingUtil.escapeMiniMessage(accountName))
                        ));

                    }else{
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.wrong-password"));
                    }

                });

                return true;

            }

            if(args[0].equals("logout")){

                if(!sender.hasPermission("profitable.account.manage.logout")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                Accounts.logOut(playerId);

                MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.logout"));

                return  true;

            }

            if(args[0].equals("password")){

                if(!sender.hasPermission("profitable.account.manage.changepassword")){
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }

                if(args.length < 3){
                    MessagingUtil.sendSyntaxError(sender,"/account password <Old password> <New password>");
                    return true;
                }
                if (args[2].length() < 8) {
                    MessagingUtil.sendSyntaxError(sender, "Passwords must contain at least 8 characters");
                    return true;
                }

                String oldPassword = args[1];
                String newPassword = args[2];
                Profitable.getfolialib().getScheduler().runAsync(task -> {
                    String account = Accounts.getAccount(world, playerId);
                    if(Accounts.comparePasswords(world, account, oldPassword)){
                        if(newPassword.length() < 32){
                            if (Accounts.changePassword(world, account, newPassword)) {
                                MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.password-update"));
                            } else {
                                MessagingUtil.sendSyntaxError(sender, "Password was not updated");
                            }


                        }else{
                            MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.password-too-long"));
                        }
                    }else{
                        MessagingUtil.sendComponentMessage(sender, Profitable.getLang().get("account.error.wrong-password"));
                    }
                });
                return true;
            }

        }





        if (args.length == 0) {
            MessagingUtil.sendSyntaxError(sender, "/account <register|login|logout|password|delete>");
            return true;
        }

        MessagingUtil.sendGenericInvalidSubCom(sender, args[0]);
        return true;
    }

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] args) {

            List<String> suggestions = new ArrayList<>();

            if(args.length == 1){
                List<String> options = new ArrayList<>();
                if(commandSender.hasPermission("profitable.account.manage.register")) options.add("register");
                if(commandSender.hasPermission("profitable.account.manage.login")) options.add("login");
                if(commandSender.hasPermission("profitable.account.manage.logout")) options.add("logout");
                if(commandSender.hasPermission("profitable.account.manage.changepassword")) options.add("password");
                if(commandSender.hasPermission("profitable.account.manage.delete")) options.add("delete");

                StringUtil.copyPartialMatches(args[0], options, suggestions);
            }

            if(args.length >= 2){

                if(Objects.equals(args[0], "register")
                        && commandSender.hasPermission("profitable.account.manage.register")){
                    if(args.length == 2){
                        suggestions = List.of("[<Account>]");
                    }else if(args.length == 3){
                        suggestions = List.of("[<Password>]");
                    }else if(args.length == 4){
                        suggestions = List.of("[<Repeat password>]");
                    }
                }

                if(Objects.equals(args[0], "delete")
                        && commandSender.hasPermission("profitable.account.manage.delete")){
                    if(args.length == 2){
                        suggestions = List.of("[<Account>]");
                    }else if(args.length == 3){
                        suggestions = List.of("[<Password>]");
                    }
                }

                if(Objects.equals(args[0], "login")
                        && commandSender.hasPermission("profitable.account.manage.login")){
                    if(args.length == 2){
                        suggestions = List.of("[<Account>]");
                    }else if(args.length == 3){
                        suggestions = List.of("[<Password>]");
                    }
                }

                if(Objects.equals(args[0], "password")
                        && commandSender.hasPermission("profitable.account.manage.changepassword")){
                    if(args.length == 2){
                        suggestions = List.of("[<Old password>]");
                    }else{
                        suggestions = List.of("[<New password>]");
                    }
                }
            }

            return suggestions;
        }

    }
}
