package com.faridfaharaj.profitable.data.settlement;

import com.faridfaharaj.profitable.Profitable;
import com.faridfaharaj.profitable.data.DataBase;
import com.tcoded.folialib.wrapper.task.WrappedTask;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Periodically drains durable wallet credits on every backend. */
public final class DeliveryOutboxWorker {

    private static final int MAX_BATCH = 100;
    private static final long PERIOD_MS = 1_000L;

    private final String workerId;
    private final AtomicBoolean draining = new AtomicBoolean();
    private WrappedTask timer;

    public DeliveryOutboxWorker(String serverId) {
        this.workerId = serverId + ":" + UUID.randomUUID();
    }

    public void start() {
        if (timer != null && !timer.isCancelled()) {
            return;
        }
        timer = Profitable.getfolialib().getScheduler().runTimerAsync(
                this::drainSafely, 0, PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    public void wakeUp() {
        Profitable.getfolialib().getScheduler().runAsync(task -> drainSafely());
    }

    public void shutdown() {
        if (timer != null && !timer.isCancelled()) {
            timer.cancel();
        }
        timer = null;
    }

    private void drainSafely() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            for (int processed = 0; processed < MAX_BATCH; processed++) {
                DeliveryOutboxRepository.ProcessResult result;
                try (Connection connection = DataBase.getConnection()) {
                    result = DeliveryOutboxRepository.processOne(
                            connection, DataBase.isMySQL(), workerId);
                }
                if (result.status() == DeliveryOutboxRepository.Status.EMPTY) {
                    break;
                }
                // A compare-and-set miss changed no durable state and is an
                // expected race between independent backends.
                if (result.status() == DeliveryOutboxRepository.Status.LOST_OWNERSHIP) {
                    continue;
                }
                if (result.status() == DeliveryOutboxRepository.Status.FAILED) {
                    Profitable.getInstance().getLogger().warning(
                            "Outbox delivery " + result.deliveryId() + " failed: " + result.error());
                }
            }
        } catch (SQLException | RuntimeException error) {
            Profitable.getInstance().getLogger().log(Level.WARNING, "Could not drain delivery outbox", error);
        } finally {
            draining.set(false);
        }
    }
}
