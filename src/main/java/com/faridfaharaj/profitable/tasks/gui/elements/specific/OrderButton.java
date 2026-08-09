package com.faridfaharaj.profitable.tasks.gui.elements.specific;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.data.tables.Orders;
import com.faridfaharaj.profitable.tasks.gui.ChestGUI;
import com.faridfaharaj.profitable.tasks.gui.elements.GuiElement;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class OrderButton extends GuiElement{

    Order order;
    private final AtomicBoolean cancelling = new AtomicBoolean();

    public OrderButton(ChestGUI gui, Order order, int slot, boolean actionCancel) {
        super(gui, order.isSideBuy()?new ItemStack(Material.PAPER):new ItemStack(Material.MAP), order.isSideBuy()?Profitable.getLang().get("orders.sides.buy").color(Configuration.COLORBULLISH):Profitable.getLang().get("orders.sides.sell").color(Configuration.COLORBEARISH),
                Profitable.getLang().langToLore(actionCancel?"gui.orders.buttons.order.lore":"gui.order-building.confirmation.buttons.submit.lore",
                        Map.entry("%asset%", order.getAsset()),
                        Map.entry("%order_type%", order.getType().toString()),
                        Map.entry("%base_asset_amount%", String.valueOf(order.getUnits())),
                        Map.entry("%quote_asset_amount%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, order.getPrice())),
                        Map.entry("%value_asset_amount%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, order.getPrice()*order.getUnits()))
                ),
                slot);

        this.order = order;


    }

    public void cancel(Player player, Consumer<Boolean> completion){
        if (!player.hasPermission("profitable.account.manage.orders.cancel")) {
            MessagingUtil.sendGenericMissingPerm(player);
            completion.accept(false);
            return;
        }
        if (!cancelling.compareAndSet(false, true)) {
            return;
        }
        World world = player.getWorld();
        String account = com.faridfaharaj.profitable.data.tables.Accounts.getAccount(player);
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            boolean cancelled = Orders.cancelOrder(order.getUuid(), player, world, account);
            Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask -> {
                if (!cancelled) {
                    cancelling.set(false);
                }
                completion.accept(cancelled);
            });
        });
    }

    public Order getOrder() {
        return order;
    }

}
