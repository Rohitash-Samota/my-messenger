CREATE TABLE notifications (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    conversion_id BIGINT NOT NULL,
    message_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    body VARCHAR(500) NOT NULL,
    read_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_notifications_event_recipient UNIQUE (event_id, user_id),
    INDEX idx_notifications_user_id_id (user_id, id),
    INDEX idx_notifications_message_id (message_id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_notifications_conversion FOREIGN KEY (conversion_id) REFERENCES conversions (id),
    CONSTRAINT fk_notifications_message FOREIGN KEY (message_id) REFERENCES messages (id)
);
