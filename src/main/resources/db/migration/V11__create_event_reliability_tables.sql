CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(200) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    locked_until DATETIME(6) NULL,
    lock_owner VARCHAR(160) NULL,
    last_error VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id),
    INDEX idx_outbox_events_relay (status, available_at, locked_until, id),
    INDEX idx_outbox_events_lock_owner (lock_owner),
    INDEX idx_outbox_events_aggregate (aggregate_type, aggregate_id, id)
);

CREATE TABLE processed_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    consumer_name VARCHAR(100) NOT NULL,
    event_id VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_processed_events_consumer_event UNIQUE (consumer_name, event_id),
    INDEX idx_processed_events_processed_at (processed_at)
);
