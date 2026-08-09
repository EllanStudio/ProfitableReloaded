CREATE INDEX idx_account_asset_lookup ON account_assets (world, account_name);
CREATE INDEX idx_orders_owner ON orders (world, owner, asset_id);
CREATE INDEX idx_orders_match ON orders (world, asset_id, sideBuy, order_type, price);
CREATE INDEX idx_orders_stop ON orders (world, asset_id, order_type, price);
CREATE INDEX idx_candles_day_lookup ON candles_day (world, asset_id, time DESC);
CREATE INDEX idx_candles_week_lookup ON candles_week (world, asset_id, time DESC);
CREATE INDEX idx_candles_month_lookup ON candles_month (world, asset_id, time DESC);
