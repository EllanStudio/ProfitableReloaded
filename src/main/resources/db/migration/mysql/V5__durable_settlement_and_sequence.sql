DROP TEMPORARY TABLE IF EXISTS v5_physical_order_guard;
CREATE TEMPORARY TABLE v5_physical_order_guard (
    invalid_rows INT NOT NULL CHECK (invalid_rows = 0)
);
INSERT INTO v5_physical_order_guard (invalid_rows)
SELECT
    (SELECT COUNT(*)
     FROM orders o
     JOIN assets a ON a.world = o.world AND a.asset_id = o.asset_id
     WHERE a.asset_type IN (2, 3)
       AND (o.units <> FLOOR(o.units) OR o.units > 2147483647))
  + (SELECT COUNT(*)
     FROM delivery_outbox d
     JOIN assets a ON a.world = d.world AND a.asset_id = d.asset_id
     WHERE a.asset_type IN (2, 3)
       AND (d.quantity <> FLOOR(d.quantity) OR d.quantity > 2147483647));
DROP TEMPORARY TABLE v5_physical_order_guard;

CREATE TABLE orders_v5 (
    sequence_id BIGINT NOT NULL AUTO_INCREMENT,
    world BINARY(16) NOT NULL,
    order_uuid BINARY(16) NOT NULL,
    owner VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    sideBuy BOOLEAN NOT NULL,
    price DOUBLE NOT NULL,
    units DOUBLE NOT NULL,
    escrow_amount DOUBLE NOT NULL,
    maker_fee_remaining DOUBLE NOT NULL,
    order_type TINYINT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (world, order_uuid),
    UNIQUE KEY uq_orders_sequence (sequence_id),
    INDEX idx_orders_owner (world, owner, asset_id),
    INDEX idx_orders_match (world, asset_id, sideBuy, order_type, price, sequence_id),
    INDEX idx_orders_stop (world, asset_id, order_type, price),
    CONSTRAINT fk_order_asset_v5 FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_order_owner_v5 FOREIGN KEY (world, owner) REFERENCES accounts(world, account_name) ON DELETE RESTRICT,
    CONSTRAINT chk_order_side_buy_v5 CHECK (sideBuy IN (0, 1)),
    CONSTRAINT chk_order_price_v5 CHECK (price > 0),
    CONSTRAINT chk_order_units_v5 CHECK (units > 0),
    CONSTRAINT chk_order_escrow_v5 CHECK (escrow_amount > 0),
    CONSTRAINT chk_order_maker_fee_v5 CHECK (maker_fee_remaining >= 0),
    CONSTRAINT chk_order_type_v5 CHECK (order_type BETWEEN 0 AND 3)
) ENGINE=InnoDB;

INSERT INTO orders_v5
    (world, order_uuid, owner, asset_id, sideBuy, price, units, escrow_amount,
     maker_fee_remaining, order_type, created_at)
SELECT world, order_uuid, owner, asset_id, sideBuy, price, units,
       CASE WHEN sideBuy = 1 THEN price * units ELSE units END,
       0, order_type, created_at
FROM orders
ORDER BY created_at, order_uuid;

/* MySQL DDL commits implicitly. Keep a live table throughout the swap so an
   interrupted migration never leaves the application table name missing. */
RENAME TABLE orders TO orders_legacy_v5,
             orders_v5 TO orders;
DROP TABLE orders_legacy_v5;

CREATE TABLE exchange_requests (
    request_id BINARY(16) NOT NULL,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    side_buy BOOLEAN NOT NULL,
    requested_price DOUBLE NOT NULL,
    requested_units DOUBLE NOT NULL,
    order_type TINYINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    execution_id BINARY(16),
    resting_order_id BINARY(16),
    created_at BIGINT NOT NULL,
    PRIMARY KEY (request_id),
    INDEX idx_exchange_request_account (world, account_name, created_at),
    CONSTRAINT chk_request_side_buy CHECK (side_buy IN (0, 1)),
    CONSTRAINT chk_request_price CHECK (requested_price > 0),
    CONSTRAINT chk_request_units CHECK (requested_units > 0),
    CONSTRAINT chk_request_order_type CHECK (order_type BETWEEN 0 AND 3),
    CONSTRAINT chk_request_status CHECK (status IN ('PLACED', 'SETTLED'))
) ENGINE=InnoDB;

CREATE TABLE trade_executions (
    execution_sequence BIGINT NOT NULL AUTO_INCREMENT,
    execution_id BINARY(16) NOT NULL,
    request_id BINARY(16) NOT NULL,
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    taker_account VARCHAR(36) NOT NULL,
    taker_side_buy BOOLEAN NOT NULL,
    total_units DOUBLE NOT NULL,
    total_money DOUBLE NOT NULL,
    taker_fee DOUBLE NOT NULL,
    execution_price DOUBLE NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    PRIMARY KEY (execution_id),
    UNIQUE KEY uq_trade_execution_sequence (execution_sequence),
    UNIQUE KEY uq_trade_execution_request (request_id),
    INDEX idx_trade_execution_status (status, created_at),
    CONSTRAINT chk_execution_units CHECK (total_units > 0),
    CONSTRAINT chk_execution_money CHECK (total_money > 0),
    CONSTRAINT chk_execution_fee CHECK (taker_fee >= 0),
    CONSTRAINT chk_execution_status CHECK (status IN ('SETTLED', 'COMPLETED'))
) ENGINE=InnoDB;

CREATE TABLE trade_fills (
    fill_sequence BIGINT NOT NULL AUTO_INCREMENT,
    fill_id BINARY(16) NOT NULL,
    execution_id BINARY(16) NOT NULL,
    maker_order_uuid BINARY(16) NOT NULL,
    maker_order_sequence BIGINT NOT NULL,
    maker_account VARCHAR(36) NOT NULL,
    maker_side_buy BOOLEAN NOT NULL,
    price DOUBLE NOT NULL,
    units DOUBLE NOT NULL,
    maker_fee DOUBLE NOT NULL,
    PRIMARY KEY (fill_id),
    UNIQUE KEY uq_trade_fill_sequence (fill_sequence),
    UNIQUE KEY uq_trade_fill_maker (execution_id, maker_order_uuid),
    INDEX idx_trade_fill_execution (execution_id, fill_sequence),
    CONSTRAINT fk_fill_execution FOREIGN KEY (execution_id) REFERENCES trade_executions(execution_id) ON DELETE RESTRICT,
    CONSTRAINT chk_fill_price CHECK (price > 0),
    CONSTRAINT chk_fill_units CHECK (units > 0),
    CONSTRAINT chk_fill_fee CHECK (maker_fee >= 0)
) ENGINE=InnoDB;

CREATE TABLE delivery_outbox_v5 (
    delivery_id BINARY(16) NOT NULL,
    execution_id BINARY(16),
    leg_key VARCHAR(96) NOT NULL,
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at BIGINT NOT NULL DEFAULT 0,
    lease_owner VARCHAR(128),
    lease_until BIGINT NOT NULL DEFAULT 0,
    last_error VARCHAR(512),
    created_at BIGINT NOT NULL,
    completed_at BIGINT,
    PRIMARY KEY (delivery_id),
    UNIQUE KEY uq_delivery_leg (leg_key),
    INDEX idx_delivery_pending (status, next_attempt_at, created_at, delivery_id),
    INDEX idx_delivery_lease (status, lease_until, created_at, delivery_id),
    INDEX idx_delivery_execution (execution_id, status),
    CONSTRAINT fk_delivery_execution FOREIGN KEY (execution_id) REFERENCES trade_executions(execution_id) ON DELETE RESTRICT,
    CONSTRAINT chk_delivery_quantity_v5 CHECK (quantity > 0),
    CONSTRAINT chk_delivery_status_v5 CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD'))
) ENGINE=InnoDB;

INSERT INTO delivery_outbox_v5
    (delivery_id, execution_id, leg_key, world, account_name, asset_id, quantity,
     status, attempts, next_attempt_at, lease_owner, lease_until, last_error, created_at, completed_at)
SELECT delivery_id, NULL, CONCAT('legacy:', HEX(delivery_id)), world, account_name, asset_id, quantity,
       CASE WHEN status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'DEAD') THEN status ELSE 'PENDING' END,
       attempts, next_attempt_at, NULL, 0, NULL, created_at,
       CASE WHEN status = 'COMPLETED' THEN created_at ELSE NULL END
FROM delivery_outbox;

RENAME TABLE delivery_outbox TO delivery_outbox_legacy_v5,
             delivery_outbox_v5 TO delivery_outbox;
DROP TABLE delivery_outbox_legacy_v5;
