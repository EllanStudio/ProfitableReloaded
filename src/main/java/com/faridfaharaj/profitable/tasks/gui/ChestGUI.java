package com.faridfaharaj.profitable.tasks.gui;

import com.faridfaharaj.profitable.Profitable;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public abstract class ChestGUI implements InventoryHolder {

    private final Inventory inventory;
    private Runnable pendingRenderer;

    public ChestGUI(int height, Component title){
        inventory = Bukkit.createInventory(this, 9*height, title);
    }

    public void openGui(Player player){
        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            player.openInventory(inventory);
            Runnable renderer;
            synchronized (this) {
                renderer = pendingRenderer;
                pendingRenderer = null;
            }
            if (renderer != null && player.getOpenInventory().getTopInventory().getHolder() == this) {
                renderer.run();
            }
        });
    };

    protected void render(Player player, Runnable renderer) {
        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == this) {
                renderer.run();
            } else if (inventory.getViewers().isEmpty()) {
                synchronized (this) {
                    pendingRenderer = renderer;
                }
            }
        });
    }

    protected static int vectorSlotPosition(int x, int y){
        return x + (y * 9);
    }

    protected void fillSlot(int slot, ItemStack item){
        ItemStack itemStack = new ItemStack(item);
        ItemMeta metaAccountButton = itemStack.getItemMeta();
        metaAccountButton.itemName(Component.space());
        itemStack.setItemMeta(metaAccountButton);
        inventory.setItem(slot, itemStack);
    }

    protected void fillSlots(int x1, int y1, int x2, int y2, Material item){
        ItemStack itemStack = new ItemStack(item);
        ItemMeta metaAccountButton = itemStack.getItemMeta();
        metaAccountButton.itemName(Component.space());
        itemStack.setItemMeta(metaAccountButton);

        for(int i = y1; i <= y2; i++){
            for(int j = x1; j <= x2; j++){
                inventory.setItem(vectorSlotPosition(j,i), itemStack);
            }
        }



    }

    protected void fillAll(Material item){

        fillSlots(0,0,8, (inventory.getSize()/9)-1, item);

    }

    public abstract void slotInteracted(Player player, int slot, ClickType click);

    @Override
    public Inventory getInventory() {
        return inventory;
    }

}
