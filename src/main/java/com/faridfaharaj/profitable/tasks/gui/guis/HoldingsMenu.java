package com.faridfaharaj.profitable.tasks.gui.guis;

import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.tables.AccountHoldings;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.tasks.gui.ChestGUI;
import com.faridfaharaj.profitable.tasks.gui.elements.GuiElement;
import com.faridfaharaj.profitable.tasks.gui.elements.ReturnButton;
import com.faridfaharaj.profitable.tasks.gui.elements.specific.AssetCache;
import com.faridfaharaj.profitable.tasks.gui.elements.specific.AssetHolderButton;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HoldingsMenu extends ChestGUI {

    GuiElement returnButton;
    GuiElement pageButton = null;

    List<AssetCache> assets = new ArrayList<>();
    List<AssetHolderButton> assetButtons = new ArrayList<>();

    int page = 0;
    int pages = 0;

    AssetCache[][] assetCache;
    boolean fromTrade;
    public HoldingsMenu(Player player, AssetCache[][] assetCache) {
        this(player, assetCache, false);
    }

    public HoldingsMenu(Player player, AssetCache[][] assetCache, boolean fromTrade) {
        super(6, Profitable.getLang().get("gui.wallet.title"));
        this.fromTrade = fromTrade;
        fillSlots(0, 0, 8,0, Material.BLACK_STAINED_GLASS_PANE);
        fillSlots(0, 0, 0,5, Material.BLACK_STAINED_GLASS_PANE);
        fillSlots(8, 0, 8,5, Material.BLACK_STAINED_GLASS_PANE);
        fillSlots(0, 4, 8,5, Material.BLACK_STAINED_GLASS_PANE);

        this.assetCache= assetCache;
        returnButton = new ReturnButton(this, vectorSlotPosition(4, 5));
        World world = player.getWorld();
        String account = Accounts.getAccount(player);

        Profitable.getfolialib().getScheduler().runAsync(task -> {
            List<AssetCache> loadedAssets = AccountHoldings.AssetBalancesToAssetData(world, account);

            render(player, () -> {
                assets = loadedAssets;
                pages = (int) Math.ceil((double) assets.size() / 21);

                updatePage();

                if(pages > 0){
                    pageButton = new GuiElement(this, new ItemStack(Material.PAPER), Profitable.getLang().get("gui.generic.buttons.page-selector.name",
                            Map.entry("%page%",String.valueOf(page + 1)),
                            Map.entry("%pages%",String.valueOf(pages))
                    ), Profitable.getLang().langToLore("gui.generic.buttons.page-selector.lore"), vectorSlotPosition(7,5));
                }
            });

        });
    }

    void updatePage(){
        assetButtons.clear();
        for(int i = 0; i < 21; i++){

            int index = i+(page*21);
            int slot = (i+1)+9+((i/7)*2);
            if(index >= assets.size()){
                getInventory().clear(slot);
            }else {
                assetButtons.add(new AssetHolderButton(this, assets.get(index), slot));
            }

        }
    }

    @Override
    public void slotInteracted(Player player, int slot, ClickType click) {

        for(AssetHolderButton button : assetButtons){
            if(button.getSlot() == slot){
                if(click.isLeftClick()){

                    button.manage(player, false, assetCache, fromTrade);

                }else if(click.isRightClick()){
                    button.manage(player, true, assetCache, fromTrade);
                }
            }
        }

        if(returnButton.getSlot() == slot){
            player.closeInventory();
            if(fromTrade){
                new TradeGui(player, 2, assetCache).openGui(player);
            }else {
                new AssetExplorer(player, 2, assetCache).openGui(player);
            }
        }

        if(pageButton != null){
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
}
