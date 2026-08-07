package com.faridfaharaj.profitable.redis;

import com.faridfaharaj.profitable.Profitable;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;

import java.util.Map;
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

    private final String channelPrefix;
    private RedisClient client;
    private StatefulRedisConnection<String, String> publishConnection;
    private StatefulRedisPubSubConnection<String, String> subscribeConnection;

    /** channel -> handler registered for that channel */
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();

    public RedisManager(String host, int port, String password, String channelPrefix) {
        this.channelPrefix = channelPrefix;

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
                    Consumer<String> handler = handlers.get(channel);
                    if (handler != null) {
                        try {
                            handler.accept(message);
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

            Profitable.getInstance().getLogger().info("Connected to Redis at " + host + ":" + port);

        } catch (RedisConnectionException e) {
            Profitable.getInstance().getLogger().warning(
                    "Could not connect to Redis: " + e.getMessage() + ". Running in standalone mode.");
            shutdown();
        }
    }

    /**
     * Returns whether the Redis connection is currently active.
     */
    public boolean isConnected() {
        return publishConnection != null && publishConnection.isOpen();
    }

    /**
     * Publishes a message to the given sub-channel (prefix is prepended automatically).
     */
    public void publish(String subChannel, String message) {
        if (!isConnected()) return;
        try {
            RedisCommands<String, String> sync = publishConnection.sync();
            sync.publish(channelPrefix + ":" + subChannel, message);
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Redis publish failed: " + e.getMessage());
        }
    }

    /**
     * Subscribes a handler to the given sub-channel (prefix is prepended automatically).
     * Only one handler per sub-channel is supported; re-subscribing replaces the handler.
     */
    public void subscribe(String subChannel, Consumer<String> handler) {
        if (!isConnected()) return;
        String fullChannel = channelPrefix + ":" + subChannel;
        handlers.put(fullChannel, handler);
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
        try {
            if (subscribeConnection != null) subscribeConnection.close();
        } catch (Exception ignored) {}
        try {
            if (publishConnection != null) publishConnection.close();
        } catch (Exception ignored) {}
        try {
            if (client != null) client.shutdown();
        } catch (Exception ignored) {}
        publishConnection = null;
        subscribeConnection = null;
        client = null;
    }
}
