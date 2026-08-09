CREATE TABLE account_assets_v4 (
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL CHECK (quantity >= 0),
    PRIMARY KEY (world, account_name, asset_id),
    FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE,
    FOREIGN KEY (world, account_name) REFERENCES accounts(world, account_name) ON DELETE CASCADE
);

INSERT INTO account_assets_v4 (world, account_name, asset_id, quantity)
SELECT world, account_name, asset_id, quantity
FROM account_assets;

DROP TABLE account_assets;
ALTER TABLE account_assets_v4 RENAME TO account_assets;

CREATE INDEX idx_account_asset_lookup ON account_assets (world, account_name);

CREATE TABLE orders_v4 (
    world BINARY(16) NOT NULL,
    order_uuid BINARY(16) NOT NULL,
    owner VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    sideBuy BOOLEAN NOT NULL CHECK (sideBuy IN (0, 1)),
    price DOUBLE NOT NULL CHECK (price > 0),
    units DOUBLE NOT NULL CHECK (units > 0),
    order_type TINYINT NOT NULL CHECK (order_type BETWEEN 0 AND 3),
    created_at INTEGER NOT NULL,
    PRIMARY KEY (world, order_uuid),
    FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    FOREIGN KEY (world, owner) REFERENCES accounts(world, account_name) ON DELETE RESTRICT
);

INSERT INTO orders_v4 (world, order_uuid, owner, asset_id, sideBuy, price, units, order_type, created_at)
SELECT legacy.world, legacy.order_uuid, legacy.owner, legacy.asset_id, legacy.sideBuy,
       legacy.price, legacy.units, legacy.order_type, CAST(legacy.rowid AS INTEGER)
FROM orders legacy;

DROP TABLE orders;
ALTER TABLE orders_v4 RENAME TO orders;

CREATE INDEX idx_orders_owner ON orders (world, owner, asset_id);
CREATE INDEX idx_orders_match ON orders (world, asset_id, sideBuy, order_type, price, created_at, order_uuid);
CREATE INDEX idx_orders_stop ON orders (world, asset_id, order_type, price);
