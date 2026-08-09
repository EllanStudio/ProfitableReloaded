CREATE INDEX IF NOT EXISTS idx_orders_match_v3 ON orders (world, asset_id, sideBuy, order_type, price);
CREATE INDEX IF NOT EXISTS idx_orders_stop_v3 ON orders (world, asset_id, order_type, price);
CREATE INDEX IF NOT EXISTS idx_candles_week_lookup_v3 ON candles_week (world, asset_id, time DESC);
CREATE INDEX IF NOT EXISTS idx_candles_month_lookup_v3 ON candles_month (world, asset_id, time DESC);

CREATE TABLE market_locks (
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (world, asset_id),
    FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE TABLE processed_events (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    processed_at BIGINT NOT NULL
);
CREATE TABLE delivery_outbox (
    delivery_id BINARY(16) NOT NULL PRIMARY KEY,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL CHECK (quantity > 0),
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_delivery_pending ON delivery_outbox (world, status, next_attempt_at);
