CREATE TABLE market_locks (
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (world, asset_id),
    CONSTRAINT fk_market_asset FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB;
CREATE TABLE processed_events (
    event_id BINARY(16) NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL,
    processed_at BIGINT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE delivery_outbox (
    delivery_id BINARY(16) NOT NULL PRIMARY KEY,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    CONSTRAINT chk_delivery_quantity CHECK (quantity > 0)
) ENGINE=InnoDB;
CREATE INDEX idx_delivery_pending ON delivery_outbox (world, status, next_attempt_at);
