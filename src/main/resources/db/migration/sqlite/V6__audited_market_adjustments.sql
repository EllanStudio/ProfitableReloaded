CREATE TABLE market_adjustments (
    request_id BINARY(16) NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'SYNTHETIC_CANDLE'),
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    price DOUBLE NOT NULL CHECK (price > 0),
    volume DOUBLE NOT NULL CHECK (volume > 0),
    market_time BIGINT NOT NULL CHECK (market_time >= 0),
    created_at BIGINT NOT NULL CHECK (created_at > 0)
);

CREATE INDEX idx_market_adjustment_asset
    ON market_adjustments (world, asset_id, market_time, created_at);
