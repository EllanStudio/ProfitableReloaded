CREATE TEMP TABLE v5_physical_order_guard (
    invalid_rows INT NOT NULL CHECK (invalid_rows = 0)
);
INSERT INTO v5_physical_order_guard (invalid_rows)
SELECT
    (SELECT COUNT(*)
     FROM orders o
     JOIN assets a ON a.world = o.world AND a.asset_id = o.asset_id
     WHERE a.asset_type IN (2, 3)
       AND (o.units <> CAST(o.units AS INTEGER) OR o.units > 2147483647))
  + (SELECT COUNT(*)
     FROM delivery_outbox d
     JOIN assets a ON a.world = d.world AND a.asset_id = d.asset_id
     WHERE a.asset_type IN (2, 3)
       AND (d.quantity <> CAST(d.quantity AS INTEGER) OR d.quantity > 2147483647));
DROP TABLE v5_physical_order_guard;

CREATE TABLE orders_v5 (
    sequence_id INTEGER PRIMARY KEY AUTOINCREMENT,
    world BINARY(16) NOT NULL,
    order_uuid BINARY(16) NOT NULL,
    owner VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    sideBuy BOOLEAN NOT NULL CHECK (sideBuy IN (0, 1)),
    price DOUBLE NOT NULL CHECK (price > 0),
    units DOUBLE NOT NULL CHECK (units > 0),
    escrow_amount DOUBLE NOT NULL CHECK (escrow_amount > 0),
    maker_fee_remaining DOUBLE NOT NULL CHECK (maker_fee_remaining >= 0),
    order_type TINYINT NOT NULL CHECK (order_type BETWEEN 0 AND 3),
    created_at INTEGER NOT NULL,
    UNIQUE (world, order_uuid),
    FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    FOREIGN KEY (world, owner) REFERENCES accounts(world, account_name) ON DELETE RESTRICT
);

INSERT INTO orders_v5
    (world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount,
     maker_fee_remaining, order_type, created_at)
SELECT world, order_uuid, owner, asset_id, sideBuy, price, units,
       CASE WHEN sideBuy = 1 THEN price * units ELSE units END,
       0, order_type, created_at
FROM orders
ORDER BY created_at, order_uuid;

DROP TABLE orders;
ALTER TABLE orders_v5 RENAME TO orders;

CREATE INDEX idx_orders_owner ON orders (world, owner, asset_id);
CREATE INDEX idx_orders_match ON orders (world, asset_id, sideBuy, order_type, price, sequence_id);
CREATE INDEX idx_orders_stop ON orders (world, asset_id, order_type, price);

CREATE TABLE exchange_requests (
    request_id BINARY(16) NOT NULL PRIMARY KEY,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    side_buy BOOLEAN NOT NULL CHECK (side_buy IN (0, 1)),
    requested_price DOUBLE NOT NULL CHECK (requested_price > 0),
    requested_units DOUBLE NOT NULL CHECK (requested_units > 0),
    order_type TINYINT NOT NULL CHECK (order_type BETWEEN 0 AND 3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLACED', 'SETTLED')),
    execution_id BINARY(16),
    resting_order_id BINARY(16),
    created_at BIGINT NOT NULL
);
CREATE INDEX idx_exchange_request_account ON exchange_requests (world, account_name, created_at);

CREATE TABLE trade_executions (
    execution_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    execution_id BINARY(16) NOT NULL UNIQUE,
    request_id BINARY(16) NOT NULL UNIQUE,
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    taker_account VARCHAR(36) NOT NULL,
    taker_side_buy BOOLEAN NOT NULL CHECK (taker_side_buy IN (0, 1)),
    total_units DOUBLE NOT NULL CHECK (total_units > 0),
    total_money DOUBLE NOT NULL CHECK (total_money > 0),
    taker_fee DOUBLE NOT NULL CHECK (taker_fee >= 0),
    execution_price DOUBLE NOT NULL CHECK (execution_price > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('SETTLED', 'COMPLETED')),
    created_at BIGINT NOT NULL,
    completed_at BIGINT
);
CREATE INDEX idx_trade_execution_status ON trade_executions (status, created_at);

CREATE TABLE trade_fills (
    fill_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
    fill_id BINARY(16) NOT NULL UNIQUE,
    execution_id BINARY(16) NOT NULL,
    maker_order_uuid BINARY(16) NOT NULL,
    maker_order_sequence BIGINT NOT NULL,
    maker_account VARCHAR(36) NOT NULL,
    maker_side_buy BOOLEAN NOT NULL CHECK (maker_side_buy IN (0, 1)),
    price DOUBLE NOT NULL CHECK (price > 0),
    units DOUBLE NOT NULL CHECK (units > 0),
    maker_fee DOUBLE NOT NULL CHECK (maker_fee >= 0),
    UNIQUE (execution_id, maker_order_uuid),
    FOREIGN KEY (execution_id) REFERENCES trade_executions(execution_id) ON DELETE RESTRICT
);
CREATE INDEX idx_trade_fill_execution ON trade_fills (execution_id, fill_sequence);

ALTER TABLE delivery_outbox RENAME TO delivery_outbox_v3;
DROP INDEX IF EXISTS idx_delivery_pending;

CREATE TABLE delivery_outbox (
    delivery_id BINARY(16) NOT NULL PRIMARY KEY,
    execution_id BINARY(16),
    leg_key VARCHAR(96) NOT NULL UNIQUE,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL CHECK (quantity > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD')),
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    FOREIGN KEY (execution_id) REFERENCES trade_executions(execution_id) ON DELETE RESTRICT
);
CREATE INDEX idx_delivery_pending ON delivery_outbox (status, next_attempt_at, created_at, delivery_id);
CREATE INDEX idx_delivery_lease ON delivery_outbox (status, lease_until, created_at, delivery_id);
CREATE INDEX idx_delivery_execution ON delivery_outbox (execution_id, status);

INSERT INTO delivery_outbox
    (delivery_id, execution_id, leg_key, world, account_name, asset_id, quantity,
     status, attempts, next_attempt_at, lease_owner, lease_until, last_error, created_at, completed_at)
SELECT delivery_id, NULL, 'legacy:' || lower(hex(delivery_id)), world, account_name, asset_id, quantity,
       CASE WHEN status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD') THEN status ELSE 'PENDING' END,
       attempts, next_attempt_at, NULL, 0, NULL, created_at,
       CASE WHEN status = 'COMPLETED' THEN created_at ELSE NULL END
FROM delivery_outbox_v3;

DROP TABLE delivery_outbox_v3;
