CREATE TABLE order_events (
    event_id     VARCHAR(64)  PRIMARY KEY,
    order_no     VARCHAR(32)  NOT NULL,
    version      BIGINT       NOT NULL,
    type         VARCHAR(40)  NOT NULL,
    payload      TEXT         NOT NULL,
    occurred_at  TIMESTAMP    NOT NULL,
    applied      BOOLEAN      NOT NULL,
    CONSTRAINT uq_order_version UNIQUE (order_no, version)
);
CREATE INDEX idx_events_pending ON order_events (order_no, applied, version);

CREATE TABLE order_views (
    order_no     VARCHAR(32)  PRIMARY KEY,
    customer_id  VARCHAR(40),
    email        VARCHAR(200),
    status       VARCHAR(24)  NOT NULL,
    version      BIGINT       NOT NULL,
    placed_at    TIMESTAMP,
    updated_at   TIMESTAMP    NOT NULL,
    doc          TEXT         NOT NULL
);
CREATE INDEX idx_views_customer ON order_views (customer_id, placed_at DESC);
