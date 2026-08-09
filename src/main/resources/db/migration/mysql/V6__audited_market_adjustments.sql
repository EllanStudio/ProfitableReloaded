CREATE TABLE market_adjustments (
    request_id BINARY(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    price DOUBLE NOT NULL,
    volume DOUBLE NOT NULL,
    market_time BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    INDEX idx_market_adjustment_asset (world, asset_id, market_time, created_at),
    CONSTRAINT chk_market_adjustment_event CHECK (event_type = 'SYNTHETIC_CANDLE'),
    CONSTRAINT chk_market_adjustment_price CHECK (price > 0),
    CONSTRAINT chk_market_adjustment_volume CHECK (volume > 0),
    CONSTRAINT chk_market_adjustment_market_time CHECK (market_time >= 0),
    CONSTRAINT chk_market_adjustment_created_at CHECK (created_at > 0)
) ENGINE=InnoDB;
