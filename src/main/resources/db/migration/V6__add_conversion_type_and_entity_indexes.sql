ALTER TABLE conversions
    ADD COLUMN conversion_type TINYINT NOT NULL DEFAULT 0,
    ADD INDEX idx_conversions_user_id (user_id),
    ADD INDEX idx_conversions_client_id (client_id);

ALTER TABLE messages
    ADD INDEX idx_messages_user_id (user_id),
    ADD INDEX idx_messages_parent_message_id (parent_message_id);

ALTER TABLE group_members
    ADD INDEX idx_group_members_user_id (user_id),
    ADD INDEX idx_group_members_group_id (group_id);