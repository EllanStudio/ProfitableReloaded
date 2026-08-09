package com.faridfaharaj.profitable;

import com.faridfaharaj.profitable.data.DataBase;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.tasks.TemporalItems;
import com.faridfaharaj.profitable.tasks.gui.ChestGUI;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class Events implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        World world = player.getWorld();
        // Zero-gate onboarding: automatically create and log into the player's default account on first join
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            Accounts.ensureDefaultAccount(world, playerId);
            Profitable.getfolialib().getScheduler().runAtEntityWithFallback(player, entityTask -> {
                if (!player.isOnline()) {
                    Accounts.logOut(playerId);
                    return;
                }
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("account.login",
                        Map.entry("%account%", Profitable.getLang().getString("account.default-name"))));
            }, () -> {
                // A quick disconnect can race the asynchronous database task. The
                // retired-entity fallback removes the just-created local selection
                // and publishes the matching logout event without touching Bukkit.
                Accounts.logOut(playerId);
            });
        });

    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        Player player = event.getPlayer();
        if (TemporalItems.holdingTemp.containsKey(player.getUniqueId())) {
            TemporalItems.removeTempItem(player);
        }
        Accounts.logOut(player.getUniqueId());

    }

    @EventHandler
    public void onPlayerItemHeld(PlayerItemHeldEvent event) {

        TemporalItems.removeTempItem(event.getPlayer());

    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        TemporalItems.TemporalItem temporaryItem = TemporalItems.holdingTemp.get(player.getUniqueId());
        if (temporaryItem != null && TemporalItems.isTemporaryItem(event.getItemDrop().getItemStack(), temporaryItem)) {
            Item droppedItem = event.getItemDrop();
            droppedItem.remove();
            TemporalItems.removeTemp(player);
        }
    }

    @EventHandler
    public void onPlayerSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (TemporalItems.holdingTemp.containsKey(player.getUniqueId())) {
            TemporalItems.removeTempItem(player);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();

        if (TemporalItems.holdingTemp.containsKey(player.getUniqueId())) {
            ItemStack mainHandItem = player.getInventory().getItemInMainHand();
            event.getDrops().removeIf(item -> item != null && item.isSimilar(mainHandItem));
            TemporalItems.removeTemp(player);
        }
    }

    @EventHandler
    public void onClickInventory(InventoryClickEvent event) {
        HumanEntity player = event.getWhoClicked();
        if(TemporalItems.holdingTemp.containsKey(player.getUniqueId())){
            event.setCancelled(player.getGameMode() != GameMode.CREATIVE);
            TemporalItems.removeTempItem((Player) player);
        }

        Inventory topInventory = event.getView().getTopInventory();
        if(topInventory.getHolder() instanceof ChestGUI gui){
            event.setCancelled(true);

            if(event.getClickedInventory() == topInventory){
                gui.slotInteracted((Player) player, event.getRawSlot(), event.getClick());
            }

        }
    }

    @EventHandler
    public void onPlayerInteractAtEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if(Objects.equals(TemporalItems.holdingTemp.get(player.getUniqueId()), TemporalItems.TemporalItem.CLAIMINGTAG)){
            runItmCooldown(Material.NAME_TAG, event.getPlayer(), () -> {
                Entity entity = event.getRightClicked();
                NamespacedKey claimKey = new NamespacedKey(Profitable.getInstance(), "entity_claim_id");
                if(entity.getPersistentDataContainer().has(claimKey) || entity.customName() != null){
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.cant-reclaim-entity"));
                }else if(!Configuration.ALLOWENTITIES.contains(entity.getType().name())){
                    MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.cant-claim-entity"));

                } else {

                    Runnable claim = () -> {
                        Profitable.getfolialib().getScheduler().runAtEntity(entity, task -> {
                            String claimId = Accounts.getEntityClaimId(player.getWorld(), Accounts.getAccount(player));
                            if (claimId != null) {
                                entity.getPersistentDataContainer().set(claimKey, PersistentDataType.STRING, claimId);
                                entity.customName(LegacyComponentSerializer.legacySection().deserialize(claimId));
                                entity.setCustomNameVisible(false);
                            }
                        });
                        MessagingUtil.sendComponentMessage(player,Profitable.getLang().get("assets.entity-claim-notice",
                            Map.entry("%entity%", entity.getName()),
                                Map.entry("%asset_amount%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, Configuration.ENTITYCLAIMINGFEES))
                        ));
                    };


                    if(Configuration.ENTITYCLAIMINGFEES <= 0){
                        claim.run();
                    }else {
                        Asset.chargeAndRun(player, Configuration.MAINCURRENCYASSET, Configuration.ENTITYCLAIMINGFEES, claim);
                    }

                }
            });
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        if(Configuration.MULTIWORLD){
            Assets.generateAssets(event.getWorld());
            Accounts.registerDefaultAccount(event.getWorld(), "server");
            Accounts.changeEntityDelivery(event.getWorld(), "server", new Location(Profitable.getInstance().getServer().getWorlds().getFirst(), 0, 0 ,0));
            Accounts.changeItemDelivery(event.getWorld(), "server", new Location(Profitable.getInstance().getServer().getWorlds().getFirst(), 0, 0 ,0));
        }
    }

    @EventHandler
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event){
        if(Configuration.MULTIWORLD){
            Accounts.logOut(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event){
        // Ignore off-hand events to prevent duplicate message delivery
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        TemporalItems.TemporalItem tempItem = TemporalItems.holdingTemp.get(player.getUniqueId());
        if (tempItem != null) {
            if (tempItem == TemporalItems.TemporalItem.ITEMDELIVERYSTICK
                    || tempItem == TemporalItems.TemporalItem.ENTITYDELIVERYSTICK) {
                TemporalItems.removeTempItem(player);
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.deprecated"));
                event.setCancelled(true);
                return;
            }
            if ((tempItem == TemporalItems.TemporalItem.ITEMDELIVERYSTICK
                    || tempItem == TemporalItems.TemporalItem.ENTITYDELIVERYSTICK)
                    && !player.hasPermission("profitable.account.manage.setdelivery")) {
                TemporalItems.removeTempItem(player);
                MessagingUtil.sendGenericMissingPerm(player);
                event.setCancelled(true);
                return;
            }

            if(tempItem == TemporalItems.TemporalItem.ITEMDELIVERYSTICK){

                runItmCooldown(event.getMaterial(), player, () -> {
                    Block block = event.getClickedBlock();
                    if(block != null){
                        if(!(block.getState() instanceof Container)){
                            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.error.items-must-be-container"));
                            return;
                        }
                        Location correctedlocation = block.getLocation();
                        if(Accounts.changeItemDelivery(player.getWorld(), Accounts.getAccount(player), correctedlocation)){
                            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.updated-item",
                                    Map.entry("%position%", correctedlocation.toVector() + " (" + correctedlocation.getWorld().getName() + ")")
                            ));

                            TemporalItems.removeTempItem(player);
                        }
                    }
                });

            } else if (tempItem == TemporalItems.TemporalItem.ENTITYDELIVERYSTICK) {

                runItmCooldown(event.getMaterial(), player, () -> {

                    Block block = event.getClickedBlock();
                    if(block != null){
                        Location correctedlocation = block.getLocation().add(0.5,0,0.5).add(event.getBlockFace().getDirection());
                        if(Accounts.changeEntityDelivery(player.getWorld(), Accounts.getAccount(player), correctedlocation)){
                            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.updated-entity",
                                    Map.entry("%position%", correctedlocation.toVector() + " (" + correctedlocation.getWorld().getName() + ")")
                            ));

                            TemporalItems.removeTempItem(player);
                        }
                    }

                });

            }

            event.setCancelled(true);

        }
    }

    private void runItmCooldown(Material material, Player player, Runnable runnable){
        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            if (player.getCooldown(material) == 0) {
                player.setCooldown(material, 40);
                runnable.run();
            }
        });
    }


}
