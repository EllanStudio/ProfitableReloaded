package com.faridfaharaj.profitable.data.holderClasses;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.tables.Accounts;
import com.faridfaharaj.profitable.data.tables.AccountHoldings;
import com.faridfaharaj.profitable.hooks.PlayerPointsHook;
import com.faridfaharaj.profitable.hooks.VaultHook;
import com.faridfaharaj.profitable.util.RandomUtil;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

public class Asset {

    private final String code;
    private final int assetType;
    private final String name;
    private final TextColor color;

    List<String> stringData;
    List<Double> numericalData;

    public Asset(String code, int assetType, TextColor color, String name, List<String> stringData, List<Double> numericalData){

        this.code = code;
        this.assetType = assetType;
        this.name = name;
        this.color = color;

        this.stringData = stringData;
        this.numericalData = numericalData;

    }

    public Asset(String code, int assetType, TextColor color, String name){

        this.code = code;
        this.assetType = assetType;
        this.name = name;
        this.color = color;

        this.stringData = Collections.emptyList();
        this.numericalData = Collections.emptyList();

    }

    public static Asset assetFromMeta(String code, int assetType, byte[] meta){
        TextColor color;
        String name;

        List<String> stringList = new ArrayList<>();
        List<Double> numericList = new ArrayList<>();

        try (ByteArrayInputStream bis = new ByteArrayInputStream(meta);
             DataInputStream dis = new DataInputStream(bis)) {

            color = TextColor.color(dis.readInt());
            name = dis.readUTF();


            int lengthStrings = dis.readInt();
            if(lengthStrings > 0){
                for(int i = 0; i<lengthStrings; i++){
                    stringList.add(dis.readUTF());
                }
            }

            int lengthNumeric = dis.readInt();
            if(lengthNumeric > 0){
                for(int i = 0; i<lengthNumeric; i++){
                    numericList.add(dis.readDouble());
                }
            }


        } catch (IOException e) {
            color = NamedTextColor.WHITE;
            name = code.toLowerCase();
        }

        return new Asset(code, assetType, color, name, stringList, numericList);
    }

    public static Asset StringToCurrency(String currencystring){

        String[] MCdata = currencystring.split("_");

        TextColor color;
        String name;

        if(MCdata.length >= 2){

            name = MCdata[1];

        }else{
            name = MCdata[0];
        }
        if(MCdata.length >= 3){

            color = TextColor.fromHexString(MCdata[2]);

            if(color == null){
                color = RandomUtil.randomTextColor();
                Profitable.getInstance().getLogger().warning("Invalid main currency color, selecting at random");
            }

        }else {
            color = RandomUtil.randomTextColor();
        }

        return new Asset(MCdata[0].toUpperCase(), 1, color, name);
    }

    public String getCode(){
        return code;
    }

    public int getAssetType(){
        return assetType;
    }

    public String getName(){
        return name;
    }

    public TextColor getColor(){
        return color;
    }

    public List<String> getStringData(){
        return stringData;
    }

    public List<Double> getNumericalData(){
        return numericalData;
    }

    public static byte[] metaData(int color, String name) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeInt(color);

            dos.writeUTF(name);

            dos.writeInt(0);

            dos.writeInt(0);

            return bos.toByteArray();
        }
    }

    public static byte[] metaData(String name) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeInt(RandomUtil.randomTextColor().value());

            dos.writeUTF(name);

            dos.writeInt(0);

            dos.writeInt(0);

            return bos.toByteArray();
        }
    }

    public static byte[] metaData(Color color, String name, List<String> stringData, List<Double> numericData) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            if(color == null){
                dos.writeInt(RandomUtil.randomTextColor().value());
            }else{
                dos.writeInt(color.asRGB());
            }

            dos.writeUTF(name);

            dos.writeInt(stringData.size());
            for(String string : stringData){
                dos.writeUTF(string);
            }

            dos.writeInt(numericData.size());
            for(double number : numericData){
                dos.writeDouble(number);
            }

            return bos.toByteArray();
        }
    }

    public static byte[] metaData(Asset asset) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.writeInt(asset.getColor().value());

            dos.writeUTF(asset.getName());

            dos.writeInt(asset.getStringData().size());
            for(String string : asset.getStringData()){
                dos.writeUTF(string);
            }

            dos.writeInt(asset.getNumericalData().size());
            for(double number : asset.getNumericalData()){
                dos.writeDouble(number);
            }

            return bos.toByteArray();
        }
    }

    public static void distributeAsset(World world, String account, Asset asset, double ammount){
        if (asset == null || account == null || !Double.isFinite(ammount) || ammount <= 0) {
            Profitable.getInstance().getLogger().warning("Rejected invalid asset distribution request");
            return;
        }
        if ((asset.getAssetType() == 2 || asset.getAssetType() == 3)
                && (ammount > Integer.MAX_VALUE || ammount != Math.rint(ammount))) {
            Profitable.getInstance().getLogger().warning("Rejected non-integral or oversized physical asset distribution for " + asset.getCode());
            return;
        }

        switch (asset.getAssetType()) {
            case 2: // Item

                if(Configuration.PHYSICALDELIVERY){
                    Location delivery = Accounts.getItemDelivery(world, account);
                    Material material = Material.matchMaterial(asset.getCode());
                    if (delivery == null || delivery.getWorld() == null || material == null || !material.isItem()) {
                        sendBalance(world, account, asset.getCode(), ammount);
                    } else {
                        sendCommodityItem(world, account, asset.getCode(), (int) ammount);
                    }
                }else {
                    sendBalance(world, account, asset.getCode(), ammount);
                }
                break;

            case 3: // Entity

                if(Configuration.PHYSICALDELIVERY){
                    Location delivery = Accounts.getEntityDelivery(world, account);
                    EntityType type = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(asset.getCode().toLowerCase(Locale.ROOT)));
                    if (delivery == null || delivery.getWorld() == null || type == null || !type.isSpawnable()) {
                        sendBalance(world, account, asset.getCode(), ammount);
                    } else {
                        sendCommodityEntity(world, account, asset.getCode(), (int) ammount);
                    }
                }else {
                    sendBalance(world, account, asset.getCode(), ammount);
                }
                break;

            case 4: // Fluid

                Profitable.getInstance().getLogger().warning("Fluid asset distribution is not yet implemented for asset: " + asset.getCode());
                return;

            case 5: // Energy

                Profitable.getInstance().getLogger().warning("Energy asset distribution is not yet implemented for asset: " + asset.getCode());
                return;

            default: // numerical value

                    sendBalance(world, account, asset.getCode(), ammount);

                break;
        }

        /*
        player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1 , 1);
        player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1 , 1);
        player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1 , 1);
        */

    }

    public static void sendBalance(World world, String account, String asset, double ammount){

        if (!AccountHoldings.addHolding(world, account, asset, ammount)) {
            Profitable.getInstance().getLogger().warning("Could not credit " + ammount + " " + asset + " to " + account);
        }

    }

    public static void sendItemToPlayer(Player player, String asset, int amount){
        sendItemToPlayer(player, asset, amount, ignored -> { });
    }

    public static void sendItemToPlayer(Player player, String asset, int amount, Consumer<Boolean> completion){

        Material material = Material.matchMaterial(asset);
        if (material == null || !material.isItem() || amount <= 0) {
            Profitable.getInstance().getLogger().warning("sendItemToPlayer: unknown item Material '" + asset + "'");
            completion.accept(false);
            return;
        }
        int maxStackSize = material.getMaxStackSize();

        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            if (!player.isOnline()) {
                completion.accept(false);
                return;
            }
            Inventory inventory = player.getInventory();
            int inventoryBefore = countMaterial(inventory, material);
            List<Item> droppedItems = new ArrayList<>();
            try {
                int missing = amount;
                while (missing > 0) {
                    int giveAmount = Math.min(missing, maxStackSize);
                    ItemStack itemStack = new ItemStack(material, giveAmount);

                    for (ItemStack drop : inventory.addItem(itemStack).values()) {
                        droppedItems.add(player.getWorld().dropItemNaturally(player.getLocation(), drop));
                    }

                    missing -= giveAmount;
                }
                player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1,1);
                player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1,1);
                player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1,1);
                player.playSound(player, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1,1);
            } catch (RuntimeException error) {
                try {
                    rollbackItemDelivery(inventory, material, inventoryBefore, droppedItems);
                } catch (RuntimeException rollbackError) {
                    Profitable.getInstance().getLogger().log(Level.SEVERE,
                            "Could not roll back failed item delivery for " + asset, rollbackError);
                }
                Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not deliver item asset " + asset, error);
                completion.accept(false);
                return;
            }
            completion.accept(true);
        }
        );

    }

    public static void sendCommodityEntityToPlayer(Player player, String account, String asset, int amount){
        sendCommodityEntityToPlayer(player, account, asset, amount, ignored -> { });
    }

    public static void sendCommodityEntityToPlayer(Player player, String account, String asset, int amount,
                                                    Consumer<Boolean> completion){
        World accountWorld = player.getWorld();
        sendCommodityEntityToPlayer(player, accountWorld, account, asset, amount, completion);
    }

    public static void sendCommodityEntityToPlayer(Player player, World accountWorld, String account, String asset,
                                                    int amount, Consumer<Boolean> completion){
        Profitable.getfolialib().getScheduler().runAsync(task -> {
            String claimId = Accounts.getEntityClaimId(accountWorld, account);
            sendCommodityEntityToPlayerWithClaim(player, asset, amount, claimId, completion);
        });
    }

    public static void sendCommodityEntityToPlayerWithClaim(Player player, String asset, int amount, String claimId,
                                                             Consumer<Boolean> completion){

        EntityType entityType = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(asset.toLowerCase(Locale.ROOT)));
        if (entityType == null || !entityType.isSpawnable() || amount <= 0) {
            Profitable.getInstance().getLogger().warning("sendCommodityEntityToPlayer: unknown or unspawnable EntityType '" + asset + "'");
            completion.accept(false);
            return;
        }

        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            if (!player.isOnline() || claimId == null) {
                completion.accept(false);
                return;
            }
            List<Entity> spawned = new ArrayList<>();
            try {
                World world = player.getWorld();
                Location location = player.getLocation();

                for(int i = 0; i<amount; i++){
                    Entity entity = world.spawnEntity(location, entityType);
                    spawned.add(entity);
                    applyClaim(entity, claimId);
                }

                world.spawnParticle(Particle.FIREWORK, location, 10);
                world.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1,1);
                world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1,1);
                world.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1,1);
            } catch (RuntimeException error) {
                try {
                    rollbackEntityDelivery(spawned);
                } catch (RuntimeException rollbackError) {
                    Profitable.getInstance().getLogger().log(Level.SEVERE,
                            "Could not roll back failed entity delivery for " + asset, rollbackError);
                }
                Profitable.getInstance().getLogger().log(Level.SEVERE, "Could not deliver entity asset " + asset, error);
                completion.accept(false);
                return;
            }
            completion.accept(true);
        });

    }

    public static void sendCommodityItem(World world, String account, String asset, int amount){

        Material material = Material.matchMaterial(asset);
        if (material == null || !material.isItem()) {
            Profitable.getInstance().getLogger().warning("sendCommodityItem: unknown Material '" + asset + "'");
            return;
        }
        int maxStackSize = material.getMaxStackSize();

        Location location = Accounts.getItemDelivery(world, account);
        if (location == null) {
            Profitable.getInstance().getLogger().warning("sendCommodityItem: delivery location is null for account '" + account + "'");
            return;
        }

        Profitable.getfolialib().getScheduler().runAtLocation(location, task -> {

                    World deliveryWorld = location.getWorld();
                    Block block = location.getBlock();
                    if (block.getState() instanceof Chest chest) {

                        int missing = amount;
                        while (missing > 0) {
                            int giveAmount = Math.min(missing, maxStackSize);
                            ItemStack itemStack = new ItemStack(material, giveAmount);


                            Inventory inventory = chest.getInventory();
                            for (ItemStack drop : inventory.addItem(itemStack).values()) {
                                deliveryWorld.dropItemNaturally(location, drop);
                            }

                            missing -= giveAmount;
                        }

                    }else{
                        int missing = amount;
                        while (missing > 0) {
                            int giveAmount = Math.min(missing, maxStackSize);
                            deliveryWorld.dropItemNaturally(location, new ItemStack(material, giveAmount));
                            missing -= giveAmount;
                        }

                    }

                    deliveryWorld.spawnParticle(Particle.FIREWORK, location.clone().add(0.5,0.5,0.5), 5);
                    deliveryWorld.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1,1);
                    deliveryWorld.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1,1);
                    deliveryWorld.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1,1);
                }
        );

    }

    public static void sendCommodityEntity(World world, String account, String asset, int amount){

        EntityType entityType = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(asset.toLowerCase(Locale.ROOT)));
        if (entityType == null || !entityType.isSpawnable()) {
            Profitable.getInstance().getLogger().warning("sendCommodityEntity: unknown EntityType '" + asset + "'");
            return;
        }

        Location location = Accounts.getEntityDelivery(world ,account);
        if (location == null) {
            Profitable.getInstance().getLogger().warning("sendCommodityEntity: delivery location is null for account '" + account + "'");
            return;
        }
        String claimId = Accounts.getEntityClaimId(world ,account);
        Profitable.getfolialib().getScheduler().runAtLocation(location, task -> {
            World deliveryWorld = location.getWorld();

            for(int i = 0; i<amount; i++){
                Entity entity = deliveryWorld.spawnEntity(location, entityType);
                applyClaim(entity, claimId);
            }

            deliveryWorld.spawnParticle(Particle.FIREWORK, location, 10);
            deliveryWorld.playSound(location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1,1);
            deliveryWorld.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1,1);
            deliveryWorld.playSound(location, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1,1);
        });

    }

    public static void chargeAndRun(Player player, Asset asset, double ammount, Runnable runnable){
        if (asset == null || !Double.isFinite(ammount) || ammount < 0) {
            MessagingUtil.sendGenericInvalidAmount(player, String.valueOf(ammount));
            return;
        }
        if ((asset.getAssetType() == 2 || asset.getAssetType() == 3)
                && (ammount > Integer.MAX_VALUE || ammount != Math.rint(ammount))) {
            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.cant-fractional",
                    Map.entry("%asset%", asset.getCode())
            ));
            return;
        }
        if(ammount == 0){
            runnable.run();
            return;
        }

        switch (asset.getAssetType()) {
            case 2: // Item

                // get from wallet
                if(Configuration.ALLOWEDCOMMODITYCOLLATERAL[0]){
                    String account = Accounts.getAccount(player);
                    double balance = AccountHoldings.getAccountAssetBalance(player.getWorld(), account, asset.getCode());
                    if(retrieveBalance(player.getWorld(), account, balance, asset.getCode(), ammount)){
                        runnable.run();
                        return;
                    }
                }

                if(Configuration.ALLOWEDCOMMODITYCOLLATERAL[1]){

                    Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
                        if(retrieveCommodityItem(player, asset.getCode(), (int) ammount)){
                            runnable.run();
                        }else {
                            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.not-enough-asset",
                                    Map.entry("%asset%", asset.getCode())
                            ));
                        }

                    });

                }
                break;
            case 3: // Entity

                // get from wallet
                if(Configuration.ALLOWEDCOMMODITYCOLLATERAL[0]){
                    String account = Accounts.getAccount(player);
                    double balance = AccountHoldings.getAccountAssetBalance(player.getWorld(), account, asset.getCode());
                    if(retrieveBalance(player.getWorld(),account, balance, asset.getCode(), ammount)){
                        runnable.run();
                        return;
                    }
                }

                if (Configuration.ALLOWEDCOMMODITYCOLLATERAL[2]) {

                    Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
                        if(retrieveCommodityEntity(player, asset.getCode(), Accounts.getEntityClaimId(player.getWorld(), Accounts.getAccount(player)), (int) ammount)){
                            runnable.run();
                        }else{
                            MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.not-enough-asset",
                            Map.entry("%asset%", asset.getCode())
                            ));
                        }

                    });

                }
                break;
            case 4: // Fluid
                Profitable.getInstance().getLogger().warning("Fluid asset charging is not yet implemented for asset: " + asset.getCode());
                return;
            case 5: // Energy
                Profitable.getInstance().getLogger().warning("Energy asset charging is not yet implemented for asset: " + asset.getCode());
                return;
            default: // any numerical value

                String account = Accounts.getAccount(player);
                double balance = AccountHoldings.getAccountAssetBalance(player.getWorld(), account, asset.getCode());
                if(retrieveBalance(player.getWorld(), account, balance, asset.getCode(), ammount)){
                    runnable.run();
                }else {
                    if(retrieveBalanceExternal(player.getWorld(), account, balance, asset.getCode(), ammount, player)){
                        runnable.run();
                    }else {
                        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.error.not-enough-asset",
                            Map.entry("%asset%", asset.getCode())
                        ));
                    }
                }
        }
    }

    public static boolean retrieveBalance(World world, String account, double balance, String asset, double ammount){
        return AccountHoldings.takeHolding(world, account, asset, ammount);

    }

    public static boolean retrieveBalanceExternal(World world, String account, double balance, String asset, double ammount, Player player){
        if(VaultHook.isConnected() && Objects.equals(asset, VaultHook.getAsset().getCode())){

            double fee = Configuration.parseFee(Configuration.DEPOSITFEES, ammount);

            if(VaultHook.getEconomy().withdrawPlayer(player, ammount+fee).transactionSuccess()){
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.auto-deposit-notice",
                    Map.entry("%asset_amount%", MessagingUtil.assetAmmount(VaultHook.getAsset(), ammount))
                ));
                return true;
            }

        } else if (PlayerPointsHook.isConnected() && Objects.equals(asset, PlayerPointsHook.getAsset().getCode())) {

            double fee = Configuration.parseFee(Configuration.DEPOSITFEES, ammount);
            double total = Math.ceil(ammount+fee);
            if(PlayerPointsHook.getApi().take(player.getUniqueId(), (int) total)){
                double remainder = total - (ammount + fee);
                if(remainder > 0){
                    AccountHoldings.addHolding(world, account, asset, remainder);
                }
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("assets.auto-deposit-notice",
                        Map.entry("%asset_amount%", MessagingUtil.assetAmmount(PlayerPointsHook.getAsset(), ammount))
                ));
                return true;
            }

        }

        return false;
    }

    public static boolean getCommodityItemFromChest(Player player){
        /*
        // get from delivery chest
        if (Configuration.ALLOWEDCOMMODITYCOLLATERAL[0]) {
            Location location = Accounts.getItemDelivery(Accounts.getAccount(player));
            if (location != null) {
                final boolean[] result = {false};

                //this is wrong
                Scheduler.runAtLocation(location, () -> {
                    Block block = location.getBlock();
                    Inventory inventoryChest;
                    if (block.getState() instanceof Chest chest) {
                        inventoryChest = chest.getInventory();
                        if (inventoryChest.containsAtLeast(itemStack, amount)) {
                            inventoryChest.removeItem(itemStack);
                            result[0] = true;
                        }
                    }
                });

                return result[0];
            }
        }
        */
        return false;
    }

    public static boolean retrieveCommodityItem(Player player, String asset, int amount){

        // get from inventory
        Inventory inventory = player.getInventory();
        Material material = Material.matchMaterial(asset);
        if (material == null || !material.isItem() || amount <= 0) {
            return false;
        }
        ItemStack itemStack = new ItemStack(material, amount);
        if (inventory.containsAtLeast(itemStack, amount)) {
            inventory.removeItem(itemStack);
            return true;
        }

        return false;

    }

    public static boolean retrieveCommodityEntity(Player player, String asset, String id, int amount){

        // get from world

        EntityType entityType = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(asset.toLowerCase(Locale.ROOT)));
        if (entityType == null || id == null || amount <= 0) {
            return false;
        }

        int entitiesRemaining = amount;
        List<Entity> entities = new ArrayList<>();

        List<Entity> nearbyEntities = player.getNearbyEntities(20, 20, 20);
        for(Entity entity : nearbyEntities){

            String storedClaim = entity.getPersistentDataContainer().get(
                    new NamespacedKey(Profitable.getInstance(), "entity_claim_id"), PersistentDataType.STRING);
            boolean legacyClaim = entity.customName() != null
                    && id.equals(LegacyComponentSerializer.legacySection().serialize(entity.customName()));
            if(id.equals(storedClaim) || legacyClaim){

                if(entity.getType().equals(entityType)){

                    entities.add(entity);
                    entitiesRemaining --;

                }

            }

            if(entitiesRemaining <= 0){
                World world = player.getWorld();
                for(Entity retrieved : entities){
                    world.playSound(retrieved.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, retrieved.getLocation(), 5, 1,1,1,1);
                    retrieved.remove();
                }
                return true;
            }

        }
        return false;

    }

    private static void applyClaim(Entity entity, String claimId) {
        if (claimId == null) {
            return;
        }
        entity.getPersistentDataContainer().set(new NamespacedKey(Profitable.getInstance(), "entity_claim_id"),
                PersistentDataType.STRING, claimId);
        entity.customName(LegacyComponentSerializer.legacySection().deserialize(claimId));
        entity.setCustomNameVisible(false);
    }

    private static int countMaterial(Inventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static void rollbackItemDelivery(Inventory inventory, Material material, int inventoryBefore,
                                             List<Item> droppedItems) {
        for (Item droppedItem : droppedItems) {
            droppedItem.remove();
        }
        int excess = Math.max(0, countMaterial(inventory, material) - inventoryBefore);
        int maxStackSize = material.getMaxStackSize();
        while (excess > 0) {
            int removeAmount = Math.min(excess, maxStackSize);
            inventory.removeItem(new ItemStack(material, removeAmount));
            excess -= removeAmount;
        }
        if (countMaterial(inventory, material) != inventoryBefore) {
            throw new IllegalStateException("Inventory could not be restored to its pre-delivery state");
        }
    }

    private static void rollbackEntityDelivery(List<Entity> spawned) {
        RuntimeException failure = null;
        for (Entity entity : spawned) {
            try {
                entity.remove();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

}
