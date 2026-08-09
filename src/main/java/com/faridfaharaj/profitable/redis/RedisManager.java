package com.faridfaharaj.profitable.redis;

import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.tables.AssetDataCache;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class RedisManager {

    private static final String PROTOCOL_VERSION = "1";
    private static final int MAX_SEEN_EVENTS = 10000;
    private static final long LEASE_TTL_MS = 30_000;
    private static final long LEASE_RENEW_INTERVAL_MS = 10_000;
    private static final String CLAIM_OR_RENEW_LEASE_SCRIPT = """
            local current = redis.call('GET', KEYS[1])
            if (not current) or current == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 1
            end
            return 0
            """;
    private static final String RELEASE_LEASE_SCRIPT = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private final String host;
    private final int port;
    private final String password;
    private final String channelPrefix;
    private final String serverId;
    private final String instanceToken = UUID.randomUUID().toString();
    private final String leaseKey;
    private final int maxReconnectAttempts;
    private final long reconnectDelayMs;
    private final Map<String, Consumer<String>> handlers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> seenEvents = new ConcurrentHashMap<>();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> publishConnection;
    private volatile StatefulRedisPubSubConnection<String, String> subscribeConnection;
    private volatile boolean shutdownRequested;
    private volatile boolean leaseOwned;
    private volatile boolean leaseLost;
    private volatile long leaseValidUntilNanos;
    private volatile int reconnectAttempts;
    private volatile WrappedTask reconnectTask;
    private volatile WrappedTask leaseRenewalTask;

    public RedisManager(String host, int port, String password, String channelPrefix,
                        String serverId, int maxReconnectAttempts, long reconnectDelayMs) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.channelPrefix = requireChannelPrefix(channelPrefix);
        this.serverId = requireServerId(serverId);
        this.leaseKey = this.channelPrefix + ":instances:" + this.serverId;
        this.maxReconnectAttempts = Math.max(0, maxReconnectAttempts);
        this.reconnectDelayMs = Math.max(250, reconnectDelayMs);
        connect(true);
    }

    private synchronized void connect(boolean failOnLeaseConflict) {
        if (shutdownRequested || leaseLost || isConnected()) {
            return;
        }
        closeConnections();
        try {
            RedisURI.Builder builder = RedisURI.builder()
                    .withHost(host)
                    .withPort(port)
                    .withTimeout(Duration.ofSeconds(5));
            if (password != null && !password.isBlank()) {
                builder.withPassword(password.toCharArray());
            }

            client = RedisClient.create(builder.build());
            publishConnection = client.connect();
            if (!claimOrRenewLease()) {
                handleLeaseConflict();
                if (failOnLeaseConflict) {
                    throw new ServerIdLeaseConflictException(duplicateServerIdMessage());
                }
                return;
            }
            subscribeConnection = client.connectPubSub();
            subscribeConnection.addListener(listener());
            for (String channel : handlers.keySet()) {
                subscribeConnection.sync().subscribe(channel);
            }
            reconnectAttempts = 0;
            reconnectScheduled.set(false);
            reconnectTask = null;
            startLeaseRenewal();
            AssetDataCache.clear();
            Profitable.getInstance().getLogger().info("Connected to Redis at " + host + ":" + port
                    + " as " + serverId);
        } catch (ServerIdLeaseConflictException e) {
            throw e;
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Could not connect to Redis: " + e.getMessage());
            closeConnections();
            scheduleReconnect();
        }
    }

    private RedisPubSubListener<String, String> listener() {
        return new RedisPubSubListener<>() {
            @Override
            public void message(String channel, String message) {
                DecodedEvent event = decode(message);
                if (event == null || serverId.equals(event.origin()) || !markUnseen(event.eventId())) {
                    return;
                }
                Consumer<String> handler = handlers.get(channel);
                if (handler != null) {
                    Profitable.getfolialib().getScheduler().runAsync(task -> {
                        try {
                            handler.accept(event.payload());
                        } catch (Exception e) {
                            Profitable.getInstance().getLogger().log(Level.WARNING,
                                    "Error handling Redis message on " + channel, e);
                        }
                    });
                }
            }

            @Override public void message(String pattern, String channel, String message) { }
            @Override public void subscribed(String channel, long count) { }
            @Override public void psubscribed(String pattern, long count) { }
            @Override public void unsubscribed(String channel, long count) { }
            @Override public void punsubscribed(String pattern, long count) { }
        };
    }

    private boolean claimOrRenewLease() {
        StatefulRedisConnection<String, String> connection = publishConnection;
        if (connection == null || !connection.isOpen()) {
            return false;
        }
        Long result = connection.sync().eval(
                CLAIM_OR_RENEW_LEASE_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[]{leaseKey},
                instanceToken,
                Long.toString(LEASE_TTL_MS)
        );
        if (result == null || result != 1L) {
            return false;
        }
        leaseOwned = true;
        leaseValidUntilNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LEASE_TTL_MS);
        return true;
    }

    private void startLeaseRenewal() {
        WrappedTask current = leaseRenewalTask;
        if (shutdownRequested || leaseLost || (current != null && !current.isCancelled())) {
            return;
        }
        leaseRenewalTask = Profitable.getfolialib().getScheduler().runTimerAsync(
                this::renewLease,
                LEASE_RENEW_INTERVAL_MS,
                LEASE_RENEW_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private synchronized void renewLease() {
        if (shutdownRequested || leaseLost) {
            return;
        }
        if (!isTransportConnected()) {
            closeConnections();
            scheduleReconnect();
            return;
        }
        try {
            if (!claimOrRenewLease()) {
                handleLeaseConflict();
            }
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Could not renew Redis server-id lease: " + e.getMessage());
            closeConnections();
            scheduleReconnect();
        }
    }

    private void handleLeaseConflict() {
        leaseLost = true;
        leaseOwned = false;
        leaseValidUntilNanos = 0;
        cancelLeaseRenewal();
        cancelReconnect();
        closeConnections();
        Profitable.getInstance().getLogger().severe(duplicateServerIdMessage());
    }

    private String duplicateServerIdMessage() {
        return "Redis server-id '" + serverId + "' is already active for channel prefix '"
                + channelPrefix + "'; refusing duplicate backend identity";
    }

    public boolean isConnected() {
        return leaseOwned && System.nanoTime() < leaseValidUntilNanos && isTransportConnected();
    }

    private boolean isTransportConnected() {
        return publishConnection != null && publishConnection.isOpen()
                && subscribeConnection != null && subscribeConnection.isOpen();
    }

    public String getServerId() {
        return serverId;
    }

    public String getChannelPrefix() {
        return channelPrefix;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public void publish(String subChannel, String payload) {
        if (!isConnected()) {
            scheduleReconnect();
            return;
        }
        try {
            publishConnection.sync().publish(fullChannel(subChannel), encode(payload));
        } catch (Exception e) {
            publishFailed(e);
        }
    }

    public void publishAsync(String subChannel, String payload) {
        if (!isConnected()) {
            scheduleReconnect();
            return;
        }
        try {
            publishConnection.async().publish(fullChannel(subChannel), encode(payload))
                    .whenComplete((ignored, error) -> {
                        if (error != null) {
                            publishFailed(error);
                        }
                    });
        } catch (Exception e) {
            publishFailed(e);
        }
    }

    public void subscribe(String subChannel, Consumer<String> handler) {
        String channel = fullChannel(subChannel);
        handlers.put(channel, handler);
        if (!isConnected()) {
            return;
        }
        try {
            subscribeConnection.sync().subscribe(channel);
        } catch (Exception e) {
            Profitable.getInstance().getLogger().warning("Redis subscribe failed: " + e.getMessage());
            closeConnections();
            scheduleReconnect();
        }
    }

    public synchronized void shutdown() {
        shutdownRequested = true;
        cancelReconnect();
        cancelLeaseRenewal();
        releaseLease();
        handlers.clear();
        seenEvents.clear();
        closeConnections();
    }

    private void releaseLease() {
        StatefulRedisConnection<String, String> connection = publishConnection;
        if (connection == null || !connection.isOpen()) {
            return;
        }
        try {
            connection.sync().eval(
                    RELEASE_LEASE_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{leaseKey},
                    instanceToken
            );
        } catch (Exception e) {
            Profitable.getInstance().getLogger().log(Level.FINE,
                    "Could not release Redis server-id lease; it will expire automatically", e);
        }
    }

    private void publishFailed(Throwable error) {
        Profitable.getInstance().getLogger().warning("Redis publish failed: " + error.getMessage());
        closeConnections();
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (shutdownRequested || leaseLost || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        if (maxReconnectAttempts > 0 && reconnectAttempts >= maxReconnectAttempts) {
            Profitable.getInstance().getLogger().severe("Redis reconnect limit reached; synchronization is unavailable");
            reconnectScheduled.set(false);
            cancelLeaseRenewal();
            return;
        }
        reconnectAttempts++;
        long exponential = reconnectDelayMs * (1L << Math.min(6, reconnectAttempts - 1));
        long jitter = Math.abs(UUID.randomUUID().getLeastSignificantBits() % Math.max(1, reconnectDelayMs));
        long delay = Math.min(60000, exponential + jitter);
        reconnectTask = Profitable.getfolialib().getScheduler().runLaterAsync(() -> {
            reconnectTask = null;
            reconnectScheduled.set(false);
            connect(false);
        }, delay, TimeUnit.MILLISECONDS);
        if (shutdownRequested || leaseLost) {
            cancelReconnect();
        }
    }

    private void cancelReconnect() {
        WrappedTask task = reconnectTask;
        reconnectTask = null;
        reconnectScheduled.set(false);
        cancelTask(task);
    }

    private void cancelLeaseRenewal() {
        WrappedTask task = leaseRenewalTask;
        leaseRenewalTask = null;
        cancelTask(task);
    }

    private static void cancelTask(WrappedTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private synchronized void closeConnections() {
        try {
            if (subscribeConnection != null) subscribeConnection.close();
        } catch (Exception e) {
            logCloseFailure("subscriber", e);
        }
        try {
            if (publishConnection != null) publishConnection.close();
        } catch (Exception e) {
            logCloseFailure("publisher", e);
        }
        try {
            if (client != null) client.shutdown();
        } catch (Exception e) {
            logCloseFailure("client", e);
        }
        subscribeConnection = null;
        publishConnection = null;
        client = null;
        leaseOwned = false;
        leaseValidUntilNanos = 0;
    }

    private String encode(String payload) {
        return PROTOCOL_VERSION + "|" + serverId + "|" + UUID.randomUUID() + "|"
                + Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private DecodedEvent decode(String message) {
        try {
            String[] parts = message.split("\\|", 4);
            if (parts.length != 4 || !PROTOCOL_VERSION.equals(parts[0])) {
                Profitable.getInstance().getLogger().warning("Ignoring unsupported Redis event envelope");
                return null;
            }
            return new DecodedEvent(parts[1], UUID.fromString(parts[2]),
                    new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            Profitable.getInstance().getLogger().warning("Ignoring malformed Redis event: " + e.getMessage());
            return null;
        }
    }

    private boolean markUnseen(UUID eventId) {
        if (seenEvents.size() >= MAX_SEEN_EVENTS) {
            long cutoff = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis();
            seenEvents.entrySet().removeIf(entry -> entry.getValue() < cutoff);
            if (seenEvents.size() >= MAX_SEEN_EVENTS) {
                seenEvents.clear();
            }
        }
        return seenEvents.putIfAbsent(eventId, System.currentTimeMillis()) == null;
    }

    private String fullChannel(String subChannel) {
        return channelPrefix + ":" + subChannel;
    }

    private static String requireServerId(String serverId) {
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("redis.server-id must contain 1-64 letters, digits, '.', '_' or '-'");
        }
        return serverId;
    }

    private static String requireChannelPrefix(String channelPrefix) {
        if (channelPrefix == null || !channelPrefix.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException(
                    "redis.channel-prefix must contain 1-64 letters, digits, '.', '_' or '-'");
        }
        return channelPrefix;
    }

    private void logCloseFailure(String resource, Exception error) {
        Profitable.getInstance().getLogger().log(Level.FINE, "Could not close Redis " + resource, error);
    }

    private static final class ServerIdLeaseConflictException extends IllegalStateException {
        private ServerIdLeaseConflictException(String message) {
            super(message);
        }
    }

    private record DecodedEvent(String origin, UUID eventId, String payload) { }
}
