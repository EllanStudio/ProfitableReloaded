ALTER TABLE market_locks
    ADD COLUMN last_market_time BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_market_last_time CHECK (last_market_time >= 0);

INSERT INTO market_locks (world, asset_id, revision, last_market_time)
SELECT historical_candles.world, historical_candles.asset_id, 0,
       GREATEST(
           0,
           MAX(historical_candles.time),
           COALESCE((
               SELECT MAX(world_adjustments.market_time)
               FROM market_adjustments world_adjustments
               WHERE world_adjustments.world = historical_candles.world
           ), 0)
       )
FROM (
    SELECT world, asset_id, time FROM candles_day
    UNION ALL
    SELECT world, asset_id, time FROM candles_week
    UNION ALL
    SELECT world, asset_id, time FROM candles_month
    UNION ALL
    SELECT world, asset_id, market_time AS time FROM market_adjustments
    UNION ALL
    SELECT world, asset_id, 0 AS time FROM market_locks
) historical_candles
INNER JOIN assets
    ON assets.world = historical_candles.world
   AND assets.asset_id = historical_candles.asset_id
GROUP BY historical_candles.world, historical_candles.asset_id
ON DUPLICATE KEY UPDATE
    last_market_time = GREATEST(last_market_time, VALUES(last_market_time));

ALTER TABLE market_adjustments
    ADD COLUMN requested_market_time BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_market_adjustment_requested_time CHECK (requested_market_time >= 0);

UPDATE market_adjustments
SET requested_market_time = market_time;

CREATE TABLE wallet_adjustments (
    request_id BINARY(16) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    old_quantity DOUBLE NOT NULL,
    new_quantity DOUBLE NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    INDEX idx_wallet_adjustment_account (world, account_name, asset_id, created_at),
    CONSTRAINT chk_wallet_adjustment_event CHECK (event_type = 'ADMIN_WALLET_SET'),
    CONSTRAINT chk_wallet_adjustment_old CHECK (old_quantity >= 0),
    CONSTRAINT chk_wallet_adjustment_new CHECK (new_quantity >= 0),
    CONSTRAINT chk_wallet_adjustment_created CHECK (created_at > 0)
) ENGINE=InnoDB;

CREATE INDEX idx_delivery_account_asset_status
    ON delivery_outbox (world, account_name, asset_id, status);
