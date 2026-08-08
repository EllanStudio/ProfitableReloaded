package com.faridfaharaj.profitable.redis;

import com.faridfaharaj.profitable.Profitable;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Manages the Redis connection used for Velocity/multi-server synchronization.
 * All pub/sub messaging goes through this class.
 *
 * When redis.enabled is false in config.yml this class is never instantiated
 * and the plugin behaves identically to standalone mode.
 */
public class RedisManager {

    private final String host;
    private final int port;
    private final String password;
    private final String channelPrefix;
    private final int maxReconnectAttempts;
    private final long reconnectDelayMs;

    /** Unique id of this server instance, used to ignore self-published messages. */
    private final String serverId = UUID.randomUUID().toString();

    private RedisClient client;
    private StatefulRedisConnection<String, String> publishConnection;
    private StatefulRedisPubSubConnection<String, String> subscribeConnection;

    /** channel -> handler registered for that channel */
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();

    private int reconnectAttempts = 0;
    private volatile boolean shutdownRequested = false;

    public RedisManager(String host, int port, String password, String channelPrefix) {
        this(host, port, password, channelPrefix, 5, 2000);
    }

    public RedisManager(String host, int port, String password, String channelPrefix,
                        int maxReconnectAttempts, long reconnectDelayMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.channelPrefix = channelPrefix;
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.reconnectDelayMs = reconnectDelayMs;

        connect();
    }

    private void connect() {
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port);

        if (password != null && !password.isBlank()) {
            uriBuilder.withPassword(password.toCharArray());
        }

        try {
            client = RedisClient.create(uriBuilder.build());
            publishConnection = client.connect();
            subscribeConnection = client.connectPubSub();

            subscribeConnection.addListener(new RedisPubSubListener<>() {
                @Override
                public void message(String channel, String message) {
                    // Every published message is prefixed with the origin server id.
                    // Strip it and ignore messages published by this server instance.
                    String payload = message;
                    int sep = message.indexOf(':');
                    if (sep > 0) {
                        String origin = message.substring(0, sep);
                        if (origin.equals(serverId)) {
                            return;
                        }
                        payload = message.substring(sep + 1);
                    }

                    Consumer<String> handler = handlers.get(channel);
                    if (handler != null) {
                        try {
                            handler.accept(payload);
                        } catch (Exception e) {
                            Profitable.getInstance().getLogger().log(Level.WARNING,
                                    "Error handling Redis message on channel " + channel, e);
                        }
                    }
                }

                @Override public void message(String pattern, String channel, String message) {}
                @Override public void subscribed(String channel, long count) {}
                @Override public void psubscribed(String pattern, long count) {}
                @Override public void unsubscribed(String channel, long count) {}
                @Override public void punsubscribed(String pattern, long count) {}
            });

            // Re-subscribe to all registered channels after (re)connection
            RedisPubSubCommands<String, String> sync = subscribeConnection.sync();
            for (String channel : handlers.keySet()) {
                sync.subscribe(channel);
            }

            reconnectAttempts = 0;
            Profitable.getInstance().getLogger().info("Connected to Redis at " + host + ":" + port);

        } catch (RedisConnectionException e) {
            Profitable.getInstance().getLogger().warning(
                    "Could not connect to Redis: " + e.getMessage());
            closeConnections();
            scheduleReconnect();
        }
    }

    private void closeConnections() {
        try { if (subscribeConnection != null) subscribeConnection.close(); } catch (Exception ignored) {}
        try { if (publishConnection != null) publishConnection.close(); } catch (Exception ignored) {}
        try { if (client != null) client.shutdown(); } catch (Exception ignored) {}
        publishConnection = null;
        subscribeConnection = null;
        client = null;
    }

    private void scheduleReconnect() {
        if (shutdownRequested || reconnectAttempts >= maxReconnectAttempts) {
            if (reconnectAttempts >= maxReconnectAttempts) {
                Profitable.getInstance().getLogger().warning(
                        "Redis: max reconnect attempts (" + maxReconnectAttempts + ") reached. Running in standalone mode.");
            }
            return;
        }

        reconnectAttempts++;
        long delay = reconnectDelayMs * reconnectAttempts; // linear backoff
        Profitable.getInstance().getLogger().info(
                "Redis: reconnecting in " + (delay / 1000) + "s (attempt " + reconnectAttempts + "/" + maxReconnectAttempts + ")...");

        Profitable.getfolialib().getScheduler().runLaterAsync(task -> {
            if (!shutdownRequested) {
                connect();
            }
        }, delay / 50); // convert ms to ticks (approx)
    }

    /**
     * Returns whether the Redis connection is currently active.
     */
    public boolean isConnected() {
        return publishConnection != null && publishConnection.isOpen();
    }

    /**
     * Publishes a message to the given sub-channel (prefix is prepended automatically).
     * Synchronous - use for critical messages that need confirmation.
     */
    public void publish(String subChannel, String message) {
        if (!isConnected()) return;
        try {
            RedisCommands<String, String> sync = publishConnection.sync();
            sync.publish(channelPrefix + ":" + subChannel, serverId + ":" + message);
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Redis publish failed: " + e.getMessage());
        }
    }

    /**
     * Publishes a message asynchronously to avoid blocking the calling thread.
     * Preferred for non-critical notifications like trade events and order updates.
     */
    public void publishAsync(String subChannel, String message) {
        if (!isConnected()) return;
        try {
            RedisAsyncCommands<String, String> async = publishConnection.async();
            async.publish(channelPrefix + ":" + subChannel, serverId + ":" + message);
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Redis async publish failed: " + e.getMessage());
        }
    }

    /**
     * Subscribes a handler to the given sub-channel (prefix is prepended automatically).
     * Only one handler per sub-channel is supported; re-subscribing replaces the handler.
     */
    public void subscribe(String subChannel, Consumer<String> handler) {
        String fullChannel = channelPrefix + ":" + subChannel;
        // Register first so the channel is restored automatically on (re)connect
        handlers.put(fullChannel, handler);
        if (!isConnected()) return;
        try {
            RedisPubSubCommands<String, String> sync = subscribeConnection.sync();
            sync.subscribe(fullChannel);
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Redis subscribe failed: " + e.getMessage());
        }
    }

    /**
     * Closes all Redis connections. Safe to call even if the initial connection failed.
     */
    public void shutdown() {
        shutdownRequested = true;
        closeConnections();
        publishConnection = null;
        subscribeConnection = null;
        client = null;
    }
}
