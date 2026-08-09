package com.faridfaharaj.profitable.data.tables;

import com.faridfaharaj.profitable.data.holderClasses.Asset;
import com.faridfaharaj.profitable.util.MessagingUtil;
import org.bukkit.World;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory cache for Asset data to reduce database lookups.
 * Assets are cached with a TTL and automatically expired.
 * Cache keys include the world id so MULTIWORLD setups never mix
 * assets that share the same code in different worlds.
 * Thread-safe via ConcurrentHashMap.
 */
public class AssetDataCache {

    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private static final int MAX_ENTRIES = 4096;

    private static final Map<String, CachedAsset> cache = new ConcurrentHashMap<>();

    private record CachedAsset(Asset asset, long timestamp) {
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > DEFAULT_TTL_MS;
        }
    }

    private static String cacheKey(World world, String assetCode) {
        return Base64.getEncoder().encodeToString(MessagingUtil.getWorldId(world)) + ":" + assetCode;
    }

    /**
     * Gets an asset from cache. Returns null if not cached or expired.
     */
    public static Asset get(World world, String assetCode) {
        String key = cacheKey(world, assetCode);
        CachedAsset cached = cache.get(key);
        if (cached == null) return null;
        if (cached.isExpired()) {
            cache.remove(key);
            return null;
        }
        return cached.asset();
    }

    /**
     * Puts an asset into the cache.
     */
    public static void put(World world, String assetCode, Asset asset) {
        if (assetCode != null && asset != null) {
            if (cache.size() >= MAX_ENTRIES) {
                cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
                if (cache.size() >= MAX_ENTRIES) {
                    cache.clear();
                }
            }
            cache.put(cacheKey(world, assetCode), new CachedAsset(asset, System.currentTimeMillis()));
        }
    }

    /**
     * Invalidates a specific asset cache entry.
     */
    public static void invalidate(World world, String assetCode) {
        cache.remove(cacheKey(world, assetCode));
    }

    /**
     * Clears the entire cache.
     */
    public static void clear() {
        cache.clear();
    }
}
