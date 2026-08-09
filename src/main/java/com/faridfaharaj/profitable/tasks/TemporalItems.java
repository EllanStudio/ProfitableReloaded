package com.faridfaharaj.profitable.tasks;

import com.faridfaharaj.profitable.Configuration;
import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.data.holderClasses.Candle;
import com.faridfaharaj.profitable.data.tables.Assets;
import com.faridfaharaj.profitable.util.MessagingUtil;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

public class TemporalItems {

    public enum TemporalItem {
        INFOBOOK,
        CLAIMINGTAG,
        ITEMDELIVERYSTICK,
        ENTITYDELIVERYSTICK,
        GRAPHMAP
    }

    static final long GRAPH_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
    static final int GRAPH_CACHE_MAX_ENTRIES = 128;

    public static final Map<UUID, TemporalItem> holdingTemp = new ConcurrentHashMap<>();

    private static final Map<UUID, UUID> GRAPH_REQUESTS = new ConcurrentHashMap<>();
    private static final Map<GraphCacheKey, CachedGraph> GRAPH_CACHE = new ConcurrentHashMap<>();

    public static void removeTempItem(Player player) {
        UUID playerId = player.getUniqueId();
        GRAPH_REQUESTS.remove(playerId);
        TemporalItem removed = holdingTemp.remove(playerId);
        if (removed != null) {
            if (isTemporaryItem(player.getInventory().getItemInMainHand(), removed)) {
                player.getInventory().setItemInMainHand(null);
            }
            player.playSound(player, Sound.ENTITY_ITEM_BREAK, 1, 1);
        }
    }

    public static void removeTemp(Player player) {
        UUID playerId = player.getUniqueId();
        GRAPH_REQUESTS.remove(playerId);
        if (holdingTemp.remove(playerId) != null) {
            player.playSound(player, Sound.ENTITY_ITEM_BREAK, 1, 1);
        }
    }

    // ITEMS -------------------------------------

    public static void sendClaimingTag(Player player) {
        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
                sendOccupiedMessage(player);
                return;
            }
            if (Configuration.ENTITYCLAIMINGFEES != 0) {
                MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("exchange.claim-fee-notice",
                        Map.entry("%fee_asset_amount%", MessagingUtil.assetAmmount(
                                Configuration.MAINCURRENCYASSET, Configuration.ENTITYCLAIMINGFEES))
                ));
            }
            giveTempItem(player, TemporalItem.CLAIMINGTAG,
                    new ItemStack(Material.NAME_TAG), "§dClaiming Tag");
        });
    }

    public static void sendDeliveryStick(Player player, boolean items) {
        MessagingUtil.sendComponentMessage(player, Profitable.getLang().get("delivery.deprecated"));
    }

    public static void sendGraphMap(Player player, String assetId, long lookback, String intervalLabel) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(assetId, "assetId");

        Profitable.getfolialib().getScheduler().runAtEntity(player, task -> {
            if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
                sendOccupiedMessage(player);
                return;
            }

            UUID playerId = player.getUniqueId();
            World world = player.getWorld();
            UUID worldId = world.getUID();
            long currentTime = world.getFullTime();
            long safeLookback = Math.max(0, lookback);
            long candleInterval = MapGraphRenderer.intervalTicksFor(safeLookback);
            long candleBucket = Math.floorDiv(currentTime, candleInterval);
            GraphCacheKey cacheKey = new GraphCacheKey(
                    worldId, assetId, safeLookback, candleInterval, candleBucket);
            UUID requestId = UUID.randomUUID();
            GRAPH_REQUESTS.put(playerId, requestId);

            Profitable.getfolialib().getScheduler().runAsync(asyncTask -> {
                GraphLoadResult result;
                try {
                    Asset asset = Assets.getAssetData(world, assetId);
                    if (asset == null) {
                        result = GraphLoadResult.assetMissing();
                    } else {
                        List<Candle> candles = getOrLoadGraph(cacheKey, System.nanoTime(),
                                () -> MapGraphRenderer.loadCandles(world, assetId, safeLookback, currentTime));
                        result = GraphLoadResult.success(candles);
                    }
                } catch (RuntimeException exception) {
                    Profitable.getInstance().getLogger().log(Level.WARNING,
                            "Could not load graph data for asset " + assetId, exception);
                    result = GraphLoadResult.failure();
                }

                GraphLoadResult loaded = result;
                scheduleGraphCompletion(player, playerId, requestId, world,
                        assetId, intervalLabel, loaded);
            });
        });
    }

    private static void scheduleGraphCompletion(Player player, UUID playerId, UUID requestId,
                                                World world, String assetId, String intervalLabel,
                                                GraphLoadResult loaded) {
        if (loaded.failed() || !loaded.assetExists()) {
            Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask ->
                    completeGraphRequest(player, playerId, requestId, assetId, loaded, null));
            return;
        }

        // Bukkit.createMap allocates global map state. Keep it on the server's
        // global scheduler on Folia; only the final inventory mutation belongs
        // on the player's entity scheduler.
        Profitable.getfolialib().getScheduler().runNextTick(globalTask -> {
            ItemStack graphMap = null;
            GraphLoadResult completion = loaded;
            try {
                graphMap = MapGraphRenderer.createGraphMap(
                        world, assetId, intervalLabel, loaded.candles());
            } catch (RuntimeException exception) {
                Profitable.getInstance().getLogger().log(Level.WARNING,
                        "Could not create graph map for asset " + assetId, exception);
                completion = GraphLoadResult.failure();
            }

            ItemStack createdMap = graphMap;
            GraphLoadResult finalResult = completion;
            Profitable.getfolialib().getScheduler().runAtEntity(player, entityTask ->
                    completeGraphRequest(player, playerId, requestId,
                            assetId, finalResult, createdMap));
        });
    }

    private static void completeGraphRequest(Player player, UUID playerId, UUID requestId,
                                             String assetId, GraphLoadResult loaded,
                                             ItemStack graphMap) {
        // A newer graph request or a quit/removal invalidates this callback,
        // preventing slow requests from winning the race.
        if (!GRAPH_REQUESTS.remove(playerId, requestId) || !player.isOnline()) {
            return;
        }
        if (loaded.failed() || (graphMap == null && loaded.assetExists())) {
            MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get("generic.error.internal"));
            return;
        }
        if (!loaded.assetExists()) {
            MessagingUtil.sendComponentMessage(player,
                    Profitable.getLang().get("assets.error.asset-not-found",
                            Map.entry("%asset%", assetId)));
            return;
        }

        giveTempItem(player, TemporalItem.GRAPHMAP, graphMap, "§dGraph " + assetId);
    }

    private static void giveTempItem(Player player, TemporalItem temporalItem,
                                     ItemStack item, String displayName) {
        if (player.getInventory().getItemInMainHand().getType() != Material.AIR) {
            sendOccupiedMessage(player);
            return;
        }

        GRAPH_REQUESTS.remove(player.getUniqueId());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.itemName(LegacyComponentSerializer.legacySection().deserialize(displayName));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(new NamespacedKey(Profitable.getInstance(), "temporary_item"),
                    PersistentDataType.STRING, temporalItem.name());
            item.setItemMeta(meta);
        }

        holdingTemp.put(player.getUniqueId(), temporalItem);
        player.getInventory().setItemInMainHand(item);
        player.playSound(player, Sound.ENTITY_ITEM_PICKUP, 1, 1);
    }

    private static void sendOccupiedMessage(Player player) {
        MessagingUtil.sendComponentMessage(player,
                Profitable.getLang().get("temp-items.error.main-hand-occupied"));
    }

    public static boolean isTemporaryItem(ItemStack item, TemporalItem temporalItem) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        String marker = item.getItemMeta().getPersistentDataContainer().get(
                new NamespacedKey(Profitable.getInstance(), "temporary_item"), PersistentDataType.STRING);
        return temporalItem.name().equals(marker) && item.getType() == switch (temporalItem) {
            case CLAIMINGTAG -> Material.NAME_TAG;
            case ITEMDELIVERYSTICK, ENTITYDELIVERYSTICK -> Material.CARROT_ON_A_STICK;
            case GRAPHMAP -> Material.FILLED_MAP;
            case INFOBOOK -> Material.WRITTEN_BOOK;
        };
    }

    static List<Candle> getOrLoadGraph(GraphCacheKey key, long nowNanos,
                                       Supplier<List<Candle>> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");

        CachedGraph cached = GRAPH_CACHE.compute(key, (ignored, existing) -> {
            if (existing != null && !existing.expiredAt(nowNanos)) {
                return existing;
            }
            List<Candle> loaded = Objects.requireNonNull(loader.get(), "graph loader result");
            return new CachedGraph(List.copyOf(loaded), nowNanos + GRAPH_CACHE_TTL_NANOS);
        });
        pruneGraphCache(nowNanos);
        return cached.candles();
    }

    private static void pruneGraphCache(long nowNanos) {
        if (GRAPH_CACHE.size() <= GRAPH_CACHE_MAX_ENTRIES) {
            return;
        }

        GRAPH_CACHE.entrySet().removeIf(entry -> entry.getValue().expiredAt(nowNanos));
        while (GRAPH_CACHE.size() > GRAPH_CACHE_MAX_ENTRIES) {
            Map.Entry<GraphCacheKey, CachedGraph> oldest = null;
            for (Map.Entry<GraphCacheKey, CachedGraph> entry : GRAPH_CACHE.entrySet()) {
                if (oldest == null
                        || entry.getValue().expiresAtNanos() < oldest.getValue().expiresAtNanos()) {
                    oldest = entry;
                }
            }
            if (oldest == null || !GRAPH_CACHE.remove(oldest.getKey(), oldest.getValue())) {
                break;
            }
        }
    }

    static void clearGraphCache() {
        GRAPH_CACHE.clear();
    }

    static int graphCacheSize() {
        return GRAPH_CACHE.size();
    }

    record GraphCacheKey(UUID worldId, String assetId, long lookback,
                         long candleInterval, long candleBucket) {
        GraphCacheKey {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(assetId, "assetId");
        }
    }

    private record CachedGraph(List<Candle> candles, long expiresAtNanos) {
        CachedGraph {
            candles = List.copyOf(candles);
        }

        boolean expiredAt(long nowNanos) {
            return nowNanos - expiresAtNanos >= 0;
        }
    }

    private record GraphLoadResult(boolean assetExists, boolean failed, List<Candle> candles) {
        GraphLoadResult {
            candles = List.copyOf(candles);
        }

        static GraphLoadResult success(List<Candle> candles) {
            return new GraphLoadResult(true, false, candles);
        }

        static GraphLoadResult assetMissing() {
            return new GraphLoadResult(false, false, List.of());
        }

        static GraphLoadResult failure() {
            return new GraphLoadResult(false, true, List.of());
        }
    }
}
