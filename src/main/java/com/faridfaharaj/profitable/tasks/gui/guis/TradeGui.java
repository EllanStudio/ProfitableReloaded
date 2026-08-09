package com.faridfaharaj.profitable.tasks.gui.guis;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Order;
import com.faridfaharaj.profitable.data.tables.AccountHoldings;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.Candles;
import com.faridfaharaj.profitable.data.tables.Orders;
import com.faridfaharaj.profitable.tasks.gui.ChestGUI;
import com.faridfaharaj.profitable.tasks.gui.elements.GuiElement;
import com.faridfaharaj.profitable.tasks.gui.elements.specific.AssetButton;
import com.faridfaharaj.profitable.tasks.gui.elements.specific.AssetCache;
import com.faridfaharaj.profitable.tasks.gui.guis.orderBuilding.BuySellGui;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class TradeGui extends ChestGUI {

    GuiElement categoryButton;
    GuiElement pageButton;

    GuiElement walletButton;
    GuiElement ordersButton;
    GuiElement balanceButton;

    AssetCache[][] assetCache = new AssetCache[5][];
    int assetType;

    List<AssetButton> assetButtons = new ArrayList<>();
    List<AssetCache> pageAssets = new ArrayList<>();

    int page = 0;
    int pages = 0;

    public TradeGui(Player player, int assetType, AssetCache[][] previousCache) {
        super(6, Profitable.getLang().get("gui.trade.title"));

        this.assetType = assetType;

        fillSlots(0, 0, 8,0, Material.BLACK_STAINED_GLASS_PANE);
        fillSlots(0, 0, 0,5, Material.BLACK_STAINED_GLASS_PANE);
        fillSlots(8, 0, 8,5, Material.BLACK_STAINED_GLASS_PANE);
        fillSlots(0, 4, 8,5, Material.BLACK_STAINED_GLASS_PANE);

        categoryButton = new GuiElement(this, new ItemStack(Material.ENDER_EYE), Profitable.getLang().get("gui.asset-explorer.buttons.category-selector.name"),
                Profitable.getLang().langToLore("gui.asset-explorer.buttons.category-selector.lore",

                        Map.entry("%category_list%", categoryList())

                ), vectorSlotPosition(6, 5));

        pageButton = new GuiElement(this, new ItemStack(Material.PAPER), Profitable.getLang().get("gui.generic.buttons.page-selector.name",
                Map.entry("%page%",String.valueOf(page)),
                Map.entry("%pages%",String.valueOf(pages))
        ), Profitable.getLang().langToLore("gui.generic.buttons.page-selector.lore"), vectorSlotPosition(7,5));

        walletButton = new GuiElement(this, new ItemStack(Material.CHEST), Profitable.getLang().get("gui.asset-explorer.buttons.wallet.name"),
                Profitable.getLang().langToLore("gui.asset-explorer.buttons.wallet.lore")
                , vectorSlotPosition(2, 5));

        ordersButton = new GuiElement(this, new ItemStack(Material.BOOK), Profitable.getLang().get("gui.asset-explorer.buttons.orders.name"),
                Profitable.getLang().langToLore("gui.asset-explorer.buttons.orders.lore")
                , vectorSlotPosition(1, 5));

        balanceButton = new GuiElement(this, new ItemStack(Material.EMERALD), Profitable.getLang().get("gui.trade.buttons.balance.loading"),
                null, vectorSlotPosition(4, 5));

        long time = player.getWorld().getFullTime();
        updateAssets(player, assetType, previousCache, time);
        updateBalance(player);

    }

    private String categoryList(){
        return "<white>♦ </white><color:" + (assetType == 1? NamedTextColor.WHITE.asHexString():NamedTextColor.GRAY.asHexString()) + ">" + Profitable.getLang().getString("assets.categories.forex") + "</color>%&new_line&%" +
                "<green>♦ </green><color:" + (assetType == 2? NamedTextColor.GREEN.asHexString():NamedTextColor.GRAY.asHexString()) + ">" + Profitable.getLang().getString("assets.categories.commodity-item") + "</color>%&new_line&%" +
                "<green>♦ </green><color:" + (assetType == 3? NamedTextColor.GREEN.asHexString():NamedTextColor.GRAY.asHexString()) + ">" + Profitable.getLang().getString("assets.categories.commodity-entity") + "</color>";
    }

    private void updateAssets(Player player, int assetType, AssetCache[][] previousCache, long time) {
        World world = player.getWorld();
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            AssetCache[][] loadedCache = previousCache == null ? assetCache : previousCache;
            if(previousCache == null){
                loadedCache[assetType] = Candles.getAssetsNPrice(world, assetType, time).toArray(new AssetCache[0]);
            }else if(previousCache[assetType] == null){
                loadedCache[assetType] = Candles.getAssetsNPrice(world, assetType, time).toArray(new AssetCache[0]);
            }

            render(player, () -> {
                if (this.assetType != assetType) return;
                assetCache = loadedCache;
                page = 0;
                pages = (int) Math.ceil((double) assetCache[assetType].length / 21);
                updatePage();
                if(pages > 0){
                    pageButton.setDisplayName(Profitable.getLang().get("gui.generic.buttons.page-selector.name",
                            Map.entry("%page%",String.valueOf(page + 1)),
                            Map.entry("%pages%",String.valueOf(pages))));
                    pageButton.show(this);
                }else {
                    fillSlot(pageButton.getSlot(), new ItemStack(Material.BLACK_STAINED_GLASS_PANE));
                }
            });

        });
    }

    private void updateBalance(Player player){
        String account = Accounts.getAccount(player);
        World world = player.getWorld();
        String displayAccount = Objects.equals(account, player.getUniqueId().toString())? Profitable.getLang().getString("account.default-name") : account;
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            List<AssetCache> balances = AccountHoldings.AssetBalancesToAssetData(world, account);

            double balance = 0;
            double totalValue = 0;
            int holdings = 0;

            for(AssetCache held : balances){
                if(Objects.equals(held.getAsset().getCode(), Configuration.MAINCURRENCYASSET.getCode())){
                    balance = held.getlastCandle().getVolume();
                }else {
                    totalValue += held.getlastCandle().getClose();
                    holdings++;
                }
            }
            double loadedBalance = balance;
            double loadedTotalValue = totalValue;
            int loadedHoldings = holdings;

            render(player, () -> {
            balanceButton.setDisplayName(Profitable.getLang().get("gui.trade.buttons.balance.name",
                    Map.entry("%balance%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, loadedBalance))
            ));
            balanceButton.setLore(Profitable.getLang().langToLore("gui.trade.buttons.balance.lore",
                    Map.entry("%account%", MessagingUtil.escapeMiniMessage(displayAccount)),
                    Map.entry("%holdings%", String.valueOf(loadedHoldings)),
                    Map.entry("%total_value%", MessagingUtil.assetAmmount(Configuration.MAINCURRENCYASSET, loadedTotalValue))
            ));
            balanceButton.show(this);
            });
        });
    }

    public void updatePage(){
        assetButtons.clear();
        pageAssets.clear();
        for(int i = 0; i < 21; i++){

            int index = i+(page*21);
            int slot = (i+1)+9+((i/7)*2);
            if(index >= assetCache[assetType].length){
                getInventory().clear(slot);
            }else {
                AssetCache assetData = assetCache[assetType][index];
                pageAssets.add(assetData);
                assetButtons.add(new AssetButton(this, assetData, new int[]{assetType, index}, slot));
            }

        }
    }

    @Override
    public void slotInteracted(Player player, int slot, ClickType click) {

        for(AssetButton button : assetButtons){
            if(button.getSlot() == slot){
                AssetCache assetData = pageAssets.get(assetButtons.indexOf(button));
                if(click.isLeftClick()){
                    trade(player, assetData);
                }
                if(click.isRightClick()){
                    if (!player.hasPermission("profitable.asset.graphs")) {
                        MessagingUtil.sendGenericMissingPerm(player);
                        return;
                    }
                    player.closeInventory();
                    new GraphsMenu(assetData.getAsset().getCode(), assetCache, true, assetType).openGui(player);
                }
            }
        }

        if(balanceButton.getSlot() == slot){
            if (!player.hasPermission("profitable.account.info.wallet")) {
                MessagingUtil.sendGenericMissingPerm(player);
                return;
            }
            player.closeInventory();
            new HoldingsMenu(player, assetCache, true).openGui(player);
        }

        if(walletButton.getSlot() == slot){
            if (!player.hasPermission("profitable.account.info.wallet")) {
                MessagingUtil.sendGenericMissingPerm(player);
                return;
            }
            player.closeInventory();
            new HoldingsMenu(player, assetCache, true).openGui(player);
        }

        if(ordersButton.getSlot() == slot){
            if (!player.hasPermission("profitable.account.info.orders")) {
                MessagingUtil.sendGenericMissingPerm(player);
                return;
            }
            player.closeInventory();
            new UserOrdersGui(player, assetCache, true).openGui(player);
        }

        if(categoryButton.getSlot() == slot){
            assetType += 1;
            if(assetType >= 4){
                assetType = 1;
            }

            categoryButton.setLore(Profitable.getLang().langToLore("gui.asset-explorer.buttons.category-selector.lore",
                    Map.entry("%category_list%", categoryList())
            ));
            categoryButton.show(this);
            updateAssets(player, assetType, assetCache, player.getWorld().getFullTime());
        }

        if(pages > 0){
            if(slot == pageButton.getSlot()){
                if(click.isLeftClick()){
                    page+=1;
                }if(click.isRightClick()){
                    page-=1;
                }
                page = Math.clamp(page, 0, Math.max(0, pages - 1));
                updatePage();
                pageButton.setDisplayName(Profitable.getLang().get("gui.generic.buttons.page-selector.name",
                        Map.entry("%page%",String.valueOf(page + 1)),
                        Map.entry("%pages%",String.valueOf(pages))
                ));
                pageButton.show(this);
            }
        }

    }

    private void trade(Player player, AssetCache assetData){
        player.closeInventory();
        World world = player.getWorld();
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            List<Order> bids = Orders.getBidAsk(world, assetData.getAsset().getCode(), true);
            List<Order> asks = Orders.getBidAsk(world, assetData.getAsset().getCode(), false);
            Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask ->
                    new BuySellGui(assetCache, assetData, bids, asks, true).openGui(player));
        });
    }
}
