ALTER TABLE market_locks
    ADD COLUMN last_market_time BIGINT NOT NULL DEFAULT 0 CHECK (last_market_time >= 0);

INSERT INTO market_locks (world, asset_id, revision, last_market_time)
SELECT historical_candles.world, historical_candles.asset_id, 0,
       MAX(
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
ON CONFLICT(world, asset_id) DO UPDATE SET
    last_market_time = MAX(market_locks.last_market_time, excluded.last_market_time);

ALTER TABLE market_adjustments
    ADD COLUMN requested_market_time BIGINT NOT NULL DEFAULT 0 CHECK (requested_market_time >= 0);

UPDATE market_adjustments
SET requested_market_time = market_time;

CREATE TABLE wallet_adjustments (
    request_id BINARY(16) NOT NULL PRIMARY KEY,
    event_type VARCHAR(32) NOT NULL CHECK (event_type = 'ADMIN_WALLET_SET'),
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    old_quantity DOUBLE NOT NULL CHECK (old_quantity >= 0),
    new_quantity DOUBLE NOT NULL CHECK (new_quantity >= 0),
    created_at BIGINT NOT NULL CHECK (created_at > 0)
);

CREATE INDEX idx_wallet_adjustment_account
    ON wallet_adjustments (world, account_name, asset_id, created_at);

CREATE INDEX idx_delivery_account_asset_status
    ON delivery_outbox (world, account_name, asset_id, status);
