package com.faridfaharaj.profitable.commands;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

public class HelpCommand implements CommandExecutor {

    private static final String[] ADMIN_HELP_PERMISSIONS = {
            "profitable.admin.config.reloadconfig",
            "profitable.admin.info.status",
            "profitable.admin.accounts.manage.forcelogout",
            "profitable.admin.accounts.info.getplayeracc",
            "profitable.admin.accounts.info.wallet",
            "profitable.admin.accounts.manage.wallet",
            "profitable.admin.accounts.manage.passwordreset",
            "profitable.admin.accounts.info.orders",
            "profitable.admin.accounts.info.claimid",
            "profitable.admin.accounts.manage.delete",
            "profitable.admin.outbox.info",
            "profitable.admin.outbox.retry",
            "profitable.admin.orders.info.findbyasset",
            "profitable.admin.orders.manage.cancel",
            "profitable.admin.orders.manage.delete",
            "profitable.admin.orders.manage.deleteall",
            "profitable.admin.orders.manage.cancelall",
            "profitable.admin.orders.manage.newlimitorder",
            "profitable.admin.assets.info.getallassets",
            "profitable.admin.assets.manage.register",
            "profitable.admin.assets.manage.register",
            "profitable.admin.assets.manage.register",
            "profitable.admin.assets.manage.delete",
            "profitable.admin.assets.manage.newtransaction",
            "profitable.admin.assets.manage.edit",
            "profitable.admin.assets.manage.resettransactions"
    };

    String[] pages =
            {
                    """
> /help
§e Sends a list of commands you can use §r
-----
> /help admin
§e Sends a list of commands meant for administrators and ops §r
-----
> /sell <Asset> <Units>
§e Sells immediately against the highest available bid §r
-----
> /sell <Asset> <Units> <Price>
§e Sends an order to sell an asset at an specified price §r
-----
> /sell <Asset> <Units> <Price> stop-limit
§e Sends an order that turns into a limit order when market reaches its price §r
-----
> /buy <Asset> <Units>
§e Sends an order to buy an asset immediately at the lowest price §r
-----
> /buy <Asset> <Units> <Price>
§e Sends an order to buy an asset at an specified price §r
-----
> /buy <Asset> <Units> <Price> stop-limit
§e Sends an order that turns into a limit order when market reaches its price §r
-----
> /account
§e Returns current active account §r
-----
> /account register <account> <password> <Repeat password>
§e Creates a new account §r
-----
> /account login <account> <password>
§e Changes active account §r
-----
> /account logout
§e logs out of active account §r
-----
> /account password <Old password> <New password>
§e Changes active account's password §r
-----
> /account delete <account> <password>
§e Deletes current active account §r
-----
> /wallet
§e Displays all asset balances on your account §r
-----
> /wallet deposit <Asset> <amount>
§e Transfers desired amount to your profitable's account wallet §r
-----
> /wallet withdraw <Asset> <amount>
§e Transfers desired amount from your wallet to you §r
-----
> /orders
§e Displays all active orders on your account §r
-----
> /orders cancelall
§e Cancels every active order on your account at once §r
-----
> /price <Asset>
§e Shows the latest price, day change, range and volume of an asset §r
-----
> /claimtag
§e Sends you a name-tag to mark entities as yours §r
-----
> /top GROW
§e Displays the top 9 growing assets (Monthly MC time) §r
-----
> /top HOT
§e Displays the top 9 assets with more % price movement (Monthly MC time) §r
-----
> /top LIQUID
§e Displays the top 9 most traded assets (Monthly MC time) §r
-----
> /top BIG
§e Displays the top 9 most expensive assets (Monthly MC time) §r
-----
> /assets
§e Opens the registered asset browser §r
-----
> /trade
§e Opens the complete trading interface §r""", """
> /admin config reloadconfig
§e Reloads and updates most config changes §r
-----
> /admin status
§e Shows database, Redis, correlation, execution and wallet-credit outbox health §r
-----
> /admin forcelogout <player>
§e Removes a player's active local account session §r
-----
> /admin getplayeracc <player>
§e shows player's current active account §r
-----
> /admin account <account> wallet
§e shows account asset balances §r
-----
> /admin account <account> wallet <asset> <amount> [request-uuid]
§e Audited absolute mint/burn. Reuse the UUID when retrying; pending durable credits reject the change, and physical assets require whole units. §r
-----
> /admin account <account> passwordreset <new password>
§e Replaces an account password with a new 8-31 character password §r
-----
> /admin account <account> orders
§e shows all active orders on the account §r
-----
> /admin account <account> claimid
§e shows the nametag name that recognizes someone's owned entities §r
-----
> /admin account <account> delete <account again>
§e Deletes an inactive account only after orders, unsettled credits and positive wallet balances are cleared §r
-----
> /admin outbox dead
§e Lists up to 50 DEAD wallet-credit legs with delivery and execution correlation IDs §r
-----
> /admin outbox retry <delivery-id>
§e Safely requeues a DEAD wallet credit; processed-event deduplication remains active §r
-----
> /admin orders findbyasset <asset>
§e shows all orders from a specific asset §r
-----
> /admin orders getbyid <ID> cancel
§e Cancels an order and queues its exact escrow refund to the owner's wallet §r
-----
> /admin orders getbyid <ID> delete
§e Compatibility alias for safe cancellation and queued escrow refund §r
-----
> /admin orders deleteall
§e Safely cancels all existing orders and queues every escrow refund §r
-----
> /admin orders cancelall
§e Cancels all existing orders and queues wallet refunds (may be heavy) §r
-----
> /admin orders newlimitorder <asset> <buy|sell> <units> <price>
§e Creates a collateralized order owned by server; the server wallet must fund it §r
-----
> /admin assets
§e shows all registered assets §r
-----
> /admin assets register commodityEntity <code>
§e allows you to enable trading for a specific entity (won't account for multiple worlds if not in config) §r
-----
> /admin assets register commodityItem <code>
§e allows you to enable trading for a specific item (won't account for multiple worlds if not in config) §r
-----
> /admin assets register currency <code>
§e allows you to create a currency to trade in the exchange §r
-----
> /admin assets fromid <asset> delete <asset again>
§e deletes a certain asset (will come back if in config) §r
-----
> /admin assets fromid <asset> newtransaction <price> <volume> [request-uuid]
§e Inserts an audited synthetic candle event. Reuse the request UUID when retrying so volume is applied only once. §r
-----
> /admin assets fromid <asset> edit <New symbol> <New name> <New Color>
§e Allows you to edit the appearance and identifier of registered assets §r
-----
> /admin assets fromid <asset> resettransactions
§e Legacy compatibility command; safely refuses to erase correlated execution history and changes no data. §r"""

            };

    @Override
    public boolean onCommand(CommandSender sender, Command command, String s, String[] args) {

        Component content;

        if(args.length == 1){

            if(Objects.equals(args[0], "admin")){
                if (!AdminCommand.hasAnyAdminPermission(sender)) {
                    MessagingUtil.sendGenericMissingPerm(sender);
                    return true;
                }
                content = getAdminHelp(sender);
            }else{
                content = LegacyComponentSerializer.legacySection().deserialize(pages[0]);
            }

        }else{
            content = LegacyComponentSerializer.legacySection().deserialize(pages[0]);
        }

        MessagingUtil.sendComponentMessage(sender,
                Component.text("========== [ ", Configuration.COLORPROFITABLE)
                        .append(Component.text("ProfitableReloaded Help", Configuration.COLORHIGHLIGHT))
                        .append(Component.text(" ] ==========", Configuration.COLORPROFITABLE)).appendNewline()
                        .append(content)
        );

        return true;
    }

    private Component getAdminHelp(CommandSender sender) {
        String[] entries = pages[1].split("\\R-----\\R");
        StringJoiner visibleEntries = new StringJoiner("\n-----\n");

        for (int i = 0; i < ADMIN_HELP_PERMISSIONS.length && i < entries.length; i++) {
            if (sender.hasPermission(ADMIN_HELP_PERMISSIONS[i])) {
                visibleEntries.add(entries[i]);
            }
        }

        return LegacyComponentSerializer.legacySection().deserialize(visibleEntries.toString());
    }

    public static class CommandTabCompleter implements TabCompleter {

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String s, String[] args) {
            if (args.length == 1 && AdminCommand.hasAnyAdminPermission(sender)
                    && "admin".regionMatches(true, 0, args[0], 0, args[0].length())) {
                return List.of("admin");
            }
            return List.of();
        }

    }
}
