CREATE TABLE assets (
    world BINARY(16) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    asset_type INT NOT NULL,
    meta BLOB NOT NULL,
    PRIMARY KEY (world, asset_id)
) ENGINE=InnoDB;
CREATE INDEX idx_asset_type ON assets (world, asset_type);

CREATE TABLE accounts (
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    password BINARY(16),
    salt BINARY(16),
    item_delivery_pos BINARY(40),
    entity_delivery_pos BINARY(40),
    entity_claim_id INT NOT NULL,
    PRIMARY KEY (world, account_name),
    UNIQUE KEY uq_account_claim (world, entity_claim_id)
) ENGINE=InnoDB;

CREATE TABLE account_assets (
    world BINARY(16) NOT NULL,
    account_name VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    quantity DOUBLE NOT NULL,
    PRIMARY KEY (world, account_name, asset_id),
    CONSTRAINT fk_holding_asset FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_holding_account FOREIGN KEY (world, account_name) REFERENCES accounts(world, account_name) ON DELETE CASCADE,
    CONSTRAINT chk_holding_quantity CHECK (quantity >= 0)
) ENGINE=InnoDB;

CREATE TABLE orders (
    world BINARY(16) NOT NULL,
    order_uuid BINARY(16) NOT NULL,
    owner VARCHAR(36) NOT NULL,
    asset_id VARCHAR(20) NOT NULL,
    sideBuy BOOLEAN NOT NULL,
    price DOUBLE NOT NULL,
    units DOUBLE NOT NULL,
    order_type TINYINT NOT NULL,
    PRIMARY KEY (world, order_uuid),
    CONSTRAINT fk_order_asset FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT fk_order_owner FOREIGN KEY (world, owner) REFERENCES accounts(world, account_name) ON DELETE RESTRICT,
    CONSTRAINT chk_order_price CHECK (price > 0),
    CONSTRAINT chk_order_units CHECK (units > 0),
    CONSTRAINT chk_order_type CHECK (order_type BETWEEN 0 AND 3)
) ENGINE=InnoDB;

CREATE TABLE candles_day (
    world BINARY(16) NOT NULL, time BIGINT NOT NULL, open DOUBLE NOT NULL,
    close DOUBLE NOT NULL, high DOUBLE NOT NULL, low DOUBLE NOT NULL,
    volume DOUBLE NOT NULL, asset_id VARCHAR(20) NOT NULL,
    PRIMARY KEY (world, time, asset_id),
    CONSTRAINT fk_day_asset FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB;
CREATE TABLE candles_week (
    world BINARY(16) NOT NULL, time BIGINT NOT NULL, open DOUBLE NOT NULL,
    close DOUBLE NOT NULL, high DOUBLE NOT NULL, low DOUBLE NOT NULL,
    volume DOUBLE NOT NULL, asset_id VARCHAR(20) NOT NULL,
    PRIMARY KEY (world, time, asset_id),
    CONSTRAINT fk_week_asset FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB;
CREATE TABLE candles_month (
    world BINARY(16) NOT NULL, time BIGINT NOT NULL, open DOUBLE NOT NULL,
    close DOUBLE NOT NULL, high DOUBLE NOT NULL, low DOUBLE NOT NULL,
    volume DOUBLE NOT NULL, asset_id VARCHAR(20) NOT NULL,
    PRIMARY KEY (world, time, asset_id),
    CONSTRAINT fk_month_asset FOREIGN KEY (world, asset_id) REFERENCES assets(world, asset_id) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB;
