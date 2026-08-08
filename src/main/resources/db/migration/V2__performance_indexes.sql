-- Performance indexes for faster queries

-- Covering index for candles_day lookups (used by getAssetsNPrice, getLastDay, getInterval)
CREATE INDEX IF NOT EXISTS idx_candles_day_cover ON candles_day (world, asset_id, time DESC, open, close, high, low, volume);

-- Covering index for candles_week lookups
CREATE INDEX IF NOT EXISTS idx_candles_week_cover ON candles_week (world, asset_id, time DESC, open, close, high, low, volume);

-- Covering index for candles_month lookups
CREATE INDEX IF NOT EXISTS idx_candles_month_cover ON candles_month (world, asset_id, time DESC, open, close, high, low, volume);

-- Index for account_assets balance lookups
CREATE INDEX IF NOT EXISTS idx_account_assets_balance ON account_assets (world, account_name, asset_id, quantity);

-- Index for hot assets queries (candles_month sorted by volume/price change)
CREATE INDEX IF NOT EXISTS idx_candles_month_hot ON candles_month (world, time, asset_id, open, close, volume);
