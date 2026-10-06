CREATE TABLE inventory (
    sku_id  VARCHAR(64) NOT NULL,
    stock   INT         NOT NULL,
    CONSTRAINT pk_inventory PRIMARY KEY (sku_id),
    CONSTRAINT chk_stock_non_negative CHECK (stock >= 0)
);

CREATE TABLE orders (
    order_id   VARCHAR(36)  NOT NULL,
    sku_id     VARCHAR(64)  NOT NULL,
    user_id    BIGINT       NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at    TIMESTAMP    NULL,
    CONSTRAINT pk_orders PRIMARY KEY (order_id),
    CONSTRAINT uq_user_sku UNIQUE (user_id, sku_id),
    INDEX idx_status_created (status, created_at)
);
