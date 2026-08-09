DROP TEMPORARY TABLE IF EXISTS v4_constraint_validation;

CREATE TEMPORARY TABLE v4_constraint_validation (
    valid TINYINT NOT NULL PRIMARY KEY
);

INSERT INTO v4_constraint_validation (valid) VALUES (1);

INSERT INTO v4_constraint_validation (valid)
SELECT 1
FROM account_assets holdings
LEFT JOIN assets asset
  ON asset.world = holdings.world AND asset.asset_id = holdings.asset_id
LEFT JOIN accounts account
  ON account.world = holdings.world AND account.account_name = holdings.account_name
WHERE holdings.world IS NULL
   OR holdings.account_name IS NULL
   OR holdings.asset_id IS NULL
   OR holdings.quantity IS NULL
   OR holdings.quantity < 0
   OR asset.asset_id IS NULL
   OR account.account_name IS NULL
LIMIT 1;

INSERT INTO v4_constraint_validation (valid)
SELECT 1
FROM orders legacy
LEFT JOIN assets asset
  ON asset.world = legacy.world AND asset.asset_id = legacy.asset_id
LEFT JOIN accounts account
  ON account.world = legacy.world AND account.account_name = legacy.owner
WHERE legacy.world IS NULL
   OR legacy.order_uuid IS NULL
   OR legacy.owner IS NULL
   OR legacy.asset_id IS NULL
   OR legacy.sideBuy IS NULL
   OR legacy.sideBuy NOT IN (0, 1)
   OR legacy.price IS NULL
   OR legacy.price <= 0
   OR legacy.units IS NULL
   OR legacy.units <= 0
   OR legacy.order_type NOT BETWEEN 0 AND 3
   OR asset.asset_id IS NULL
   OR account.account_name IS NULL
LIMIT 1;

DROP TEMPORARY TABLE v4_constraint_validation;

CREATE TABLE account_assets_v4 (
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL,
    PRIMARY KEY (world, account_name, asset_id),
    INDEX idx_account_asset_lookup (world, account_name),
    CONSTRAINT fk_holding_asset_v4 FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_holding_account_v4 FOREIGN KEY (world, account_name) REFERENCES accounts(world, account_name) ON DELETE CASCADE,
    CONSTRAINT chk_holding_quantity_v4 CHECK (quantity >= 0)
) ENGINE=InnoDB;

INSERT INTO account_assets_v4 (world, account_name, asset_id, quantity)
SELECT world, account_name, asset_id, quantity
FROM account_assets;

CREATE TABLE orders_v4 (
    world BINARY(16) NOT NULL,
    order_uuid BINARY(16) NOT NULL,
    owner VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    sideBuy BOOLEAN NOT NULL,
    price DOUBLE NOT NULL,
    units DOUBLE NOT NULL,
    order_type TINYINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (world, order_uuid),
    INDEX idx_orders_owner (world, owner, asset_id),
    INDEX idx_orders_match (world, asset_id, sideBuy, order_type, price, created_at, order_uuid),
    INDEX idx_orders_stop (world, asset_id, order_type, price),
    CONSTRAINT fk_order_asset_v4 FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_order_owner_v4 FOREIGN KEY (world, owner) REFERENCES accounts(world, account_name) ON DELETE RESTRICT,
    CONSTRAINT chk_order_side_buy_v4 CHECK (sideBuy IN (0, 1)),
    CONSTRAINT chk_order_price_v4 CHECK (price > 0),
    CONSTRAINT chk_order_units_v4 CHECK (units > 0),
    CONSTRAINT chk_order_type_v4 CHECK (order_type BETWEEN 0 AND 3)
) ENGINE=InnoDB;

INSERT INTO orders_v4 (world, order_uuid, owner, asset_id, sideBuy, price, units, order_type, created_at)
SELECT legacy.world, legacy.order_uuid, legacy.owner, legacy.asset_id, legacy.sideBuy,
       /* Legacy rows have no arrival timestamp; UUID supplies a stable tie-break baseline. */
       legacy.price, legacy.units, legacy.order_type, 0
FROM orders legacy;

RENAME TABLE account_assets TO account_assets_legacy_v4,
             account_assets_v4 TO account_assets,
             orders TO orders_legacy_v4,
             orders_v4 TO orders;

DROP TABLE account_assets_legacy_v4;
DROP TABLE orders_legacy_v4;
