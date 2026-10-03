-- V10 is an in-place cutover from the legacy ordinal conversion type to a
-- string enum. Deploy it while message/conversation writes are stopped so the
-- participant and receipt backfills see a stable data set.

CREATE TEMPORARY TABLE v10_conversion_type_assertion (
    valid_value TINYINT NOT NULL,
    CONSTRAINT chk_v10_conversion_type_assertion CHECK (valid_value = 1)
);

INSERT INTO v10_conversion_type_assertion (valid_value)
SELECT IF(COUNT(*) = 0, 1, 0)
FROM conversions
WHERE conversion_type NOT IN (0, 1);

DROP TEMPORARY TABLE v10_conversion_type_assertion;

ALTER TABLE conversions
    ADD COLUMN conversion_type_name VARCHAR(20)
        CHARACTER SET ascii COLLATE ascii_bin NULL;

UPDATE conversions
SET conversion_type_name = CASE conversion_type
    WHEN 0 THEN 'INDIVIDUAL'
    WHEN 1 THEN 'GROUP'
END;

ALTER TABLE conversions
    CHANGE COLUMN conversion_type conversion_type_ordinal TINYINT NULL;

ALTER TABLE conversions
    CHANGE COLUMN conversion_type_name conversion_type VARCHAR(20)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL;

ALTER TABLE conversions
    ADD CONSTRAINT chk_conversions_conversion_type
        CHECK (conversion_type IN ('INDIVIDUAL', 'GROUP')),
    ADD COLUMN last_message_id BIGINT NULL,
    ADD COLUMN last_activity_at DATETIME(6) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE conversions
SET last_activity_at = COALESCE(updated_at, created_at, CURRENT_TIMESTAMP(6));

ALTER TABLE conversions
    MODIFY COLUMN last_activity_at DATETIME(6) NOT NULL,
    ADD INDEX idx_conversions_last_activity (last_activity_at, id),
    ADD INDEX idx_conversions_last_message_id (last_message_id);

ALTER TABLE messages
    ADD COLUMN client_message_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN created_at DATETIME(6) NULL,
    ADD COLUMN updated_at DATETIME(6) NULL,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE messages message
LEFT JOIN conversions conversion ON conversion.id = message.conversion_id
SET message.client_message_id = CONCAT('legacy:v10:', message.id),
    message.created_at = COALESCE(conversion.created_at, CURRENT_TIMESTAMP(6)),
    message.updated_at = COALESCE(conversion.updated_at, conversion.created_at, CURRENT_TIMESTAMP(6))
WHERE message.client_message_id IS NULL
   OR message.created_at IS NULL
   OR message.updated_at IS NULL;

UPDATE messages
SET message_type = 'TEXT'
WHERE message_type IS NULL;

ALTER TABLE messages
    MODIFY COLUMN client_message_id VARCHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    MODIFY COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'TEXT',
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SENT',
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT uk_messages_sender_client_message
        UNIQUE (user_id, client_message_id),
    ADD INDEX idx_messages_conversion_id_id (conversion_id, id),
    ADD INDEX idx_messages_client_message_id (client_message_id);

ALTER TABLE `groups`
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE `groups`
SET member_count = 0
WHERE member_count IS NULL;

ALTER TABLE `groups`
    MODIFY COLUMN member_count INT NOT NULL DEFAULT 0,
    ADD INDEX idx_groups_deleted_at (deleted_at);

ALTER TABLE group_members
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Preserve duplicate legacy rows but retain only the oldest one as active.
-- If any duplicate row was ADMIN, its role is retained on that active row.
CREATE TEMPORARY TABLE v10_active_group_member_keep (
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    keep_id BIGINT NOT NULL,
    keep_role VARCHAR(50) NOT NULL,
    PRIMARY KEY (group_id, user_id)
);

INSERT INTO v10_active_group_member_keep (group_id, user_id, keep_id, keep_role)
SELECT group_id,
       user_id,
       MIN(id),
       CASE
           WHEN SUM(CASE WHEN UPPER(role) = 'ADMIN' THEN 1 ELSE 0 END) > 0
               THEN 'ADMIN'
           ELSE 'MEMBER'
       END
FROM group_members
WHERE deleted_at IS NULL
GROUP BY group_id, user_id
HAVING COUNT(*) > 1;

UPDATE group_members active_member
JOIN v10_active_group_member_keep keeper ON keeper.keep_id = active_member.id
SET active_member.role = keeper.keep_role;

UPDATE group_members duplicate_member
JOIN v10_active_group_member_keep keeper
  ON keeper.group_id = duplicate_member.group_id
 AND keeper.user_id = duplicate_member.user_id
SET duplicate_member.deleted_at = COALESCE(duplicate_member.updated_at, CURRENT_TIMESTAMP(6)),
    duplicate_member.updated_at = CURRENT_TIMESTAMP(6)
WHERE duplicate_member.id <> keeper.keep_id
  AND duplicate_member.deleted_at IS NULL;

DROP TEMPORARY TABLE v10_active_group_member_keep;

ALTER TABLE group_members
    ADD COLUMN active_user_id BIGINT
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, user_id, NULL)) STORED,
    ADD CONSTRAINT uk_group_members_active_user UNIQUE (group_id, active_user_id),
    ADD INDEX idx_group_members_group_active_user (group_id, deleted_at, user_id),
    ADD INDEX idx_group_members_user_active_group (user_id, deleted_at, group_id);

CREATE TABLE conversation_participants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversion_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    is_pin BOOLEAN NOT NULL DEFAULT FALSE,
    is_archive BOOLEAN NOT NULL DEFAULT FALSE,
    unread_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    last_delivered_message_id BIGINT NULL,
    last_read_message_id BIGINT NULL,
    joined_at DATETIME(6) NOT NULL,
    hidden_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_conversation_participants_conversion_user
        UNIQUE (conversion_id, user_id),
    CONSTRAINT chk_conversation_participants_unread_count
        CHECK (unread_count >= 0),
    CONSTRAINT fk_conversation_participants_conversion
        FOREIGN KEY (conversion_id) REFERENCES conversions (id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_participants_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_conversation_participants_delivered_message
        FOREIGN KEY (last_delivered_message_id) REFERENCES messages (id) ON DELETE SET NULL,
    CONSTRAINT fk_conversation_participants_read_message
        FOREIGN KEY (last_read_message_id) REFERENCES messages (id) ON DELETE SET NULL,
    INDEX idx_conversation_participants_user_active
        (user_id, deleted_at, hidden_at, conversion_id),
    INDEX idx_conversation_participants_conversion_active
        (conversion_id, deleted_at, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The owner row is the only legacy source for per-user pin/archive/hidden state.
-- Inner joins deliberately skip legacy user_id=0 and orphaned references.
INSERT INTO conversation_participants (
    conversion_id,
    user_id,
    is_pin,
    is_archive,
    unread_count,
    joined_at,
    hidden_at,
    created_at,
    updated_at
)
SELECT conversion.id,
       conversion.user_id,
       conversion.is_pin,
       conversion.is_archive,
       0,
       conversion.created_at,
       conversion.deleted_at,
       conversion.created_at,
       conversion.updated_at
FROM conversions conversion
JOIN users owner_user ON owner_user.id = conversion.user_id;

-- A valid direct peer becomes a participant. Mirrored legacy conversion rows
-- intentionally remain distinct until a later, explicitly planned merge.
INSERT INTO conversation_participants (
    conversion_id,
    user_id,
    is_pin,
    is_archive,
    unread_count,
    joined_at,
    created_at,
    updated_at
)
SELECT conversion.id,
       conversion.client_id,
       FALSE,
       FALSE,
       0,
       conversion.created_at,
       conversion.created_at,
       conversion.updated_at
FROM conversions conversion
JOIN users peer_user ON peer_user.id = conversion.client_id
WHERE conversion.conversion_type = 'INDIVIDUAL'
  AND NOT EXISTS (
      SELECT 1
      FROM conversation_participants participant
      WHERE participant.conversion_id = conversion.id
        AND participant.user_id = conversion.client_id
  );

-- Group participants are inferred only from currently active, valid legacy
-- memberships. GROUP BY prevents duplicate legacy membership rows from fanout.
INSERT INTO conversation_participants (
    conversion_id,
    user_id,
    is_pin,
    is_archive,
    unread_count,
    joined_at,
    created_at,
    updated_at
)
SELECT conversion.id,
       active_member.user_id,
       FALSE,
       FALSE,
       0,
       active_member.joined_at,
       active_member.joined_at,
       conversion.updated_at
FROM conversions conversion
JOIN (
    SELECT group_id,
           user_id,
           MIN(created_at) AS joined_at
    FROM group_members
    WHERE deleted_at IS NULL
    GROUP BY group_id, user_id
) active_member ON active_member.group_id = conversion.client_id
JOIN users group_user ON group_user.id = active_member.user_id
WHERE conversion.conversion_type = 'GROUP'
  AND NOT EXISTS (
      SELECT 1
      FROM conversation_participants participant
      WHERE participant.conversion_id = conversion.id
        AND participant.user_id = active_member.user_id
  );

-- A valid legacy message sender is also evidence of participation.
INSERT INTO conversation_participants (
    conversion_id,
    user_id,
    is_pin,
    is_archive,
    unread_count,
    joined_at,
    created_at,
    updated_at
)
SELECT message.conversion_id,
       message.user_id,
       FALSE,
       FALSE,
       0,
       MIN(message.created_at),
       MIN(message.created_at),
       MAX(message.updated_at)
FROM messages message
JOIN users sender ON sender.id = message.user_id
WHERE message.conversion_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM conversation_participants participant
      WHERE participant.conversion_id = message.conversion_id
        AND participant.user_id = message.user_id
  )
GROUP BY message.conversion_id, message.user_id;

CREATE TABLE message_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'SENT',
    delivered_at DATETIME(6) NULL,
    read_at DATETIME(6) NULL,
    legacy_backfilled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_message_receipts_message_user UNIQUE (message_id, user_id),
    CONSTRAINT chk_message_receipts_status
        CHECK (status IN ('SENT', 'DELIVERED', 'READ')),
    CONSTRAINT chk_message_receipts_timestamps CHECK (
        legacy_backfilled = TRUE
        OR (status = 'SENT' AND delivered_at IS NULL AND read_at IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL AND read_at IS NULL)
        OR (status = 'READ' AND delivered_at IS NOT NULL AND read_at IS NOT NULL)
    ),
    CONSTRAINT fk_message_receipts_message
        FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_receipts_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    INDEX idx_message_receipts_user_status_message (user_id, status, message_id),
    INDEX idx_message_receipts_message_status (message_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Historical group state was global, so these per-recipient rows are explicitly
-- marked as approximate. New writes must snapshot recipients transactionally.
INSERT INTO message_receipts (
    message_id,
    user_id,
    status,
    delivered_at,
    read_at,
    legacy_backfilled,
    created_at,
    updated_at
)
SELECT message.id,
       participant.user_id,
       CASE message.status
           WHEN 'READ' THEN 'READ'
           WHEN 'DELIVERED' THEN 'DELIVERED'
           ELSE 'SENT'
       END,
       CASE
           WHEN message.status IN ('DELIVERED', 'READ') THEN message.updated_at
           ELSE NULL
       END,
       CASE
           WHEN message.status = 'READ' THEN message.updated_at
           ELSE NULL
       END,
       TRUE,
       message.created_at,
       message.updated_at
FROM messages message
JOIN conversation_participants participant
  ON participant.conversion_id = message.conversion_id
 AND participant.deleted_at IS NULL
WHERE participant.user_id <> message.user_id
  AND message.status <> 'FAILED';

UPDATE conversation_participants participant
LEFT JOIN (
    SELECT message.conversion_id,
           receipt.user_id,
           SUM(CASE WHEN receipt.status <> 'READ' THEN 1 ELSE 0 END) AS unread_count,
           MAX(CASE
                   WHEN receipt.status IN ('DELIVERED', 'READ') THEN message.id
                   ELSE NULL
               END) AS last_delivered_message_id,
           MAX(CASE
                   WHEN receipt.status = 'READ' THEN message.id
                   ELSE NULL
               END) AS last_read_message_id
    FROM message_receipts receipt
    JOIN messages message ON message.id = receipt.message_id
    GROUP BY message.conversion_id, receipt.user_id
) receipt_state
  ON receipt_state.conversion_id = participant.conversion_id
 AND receipt_state.user_id = participant.user_id
SET participant.unread_count = COALESCE(receipt_state.unread_count, 0),
    participant.last_delivered_message_id = receipt_state.last_delivered_message_id,
    participant.last_read_message_id = receipt_state.last_read_message_id;

UPDATE conversions conversion
LEFT JOIN (
    SELECT conversion_id, MAX(id) AS message_id
    FROM messages
    WHERE conversion_id IS NOT NULL
    GROUP BY conversion_id
) latest ON latest.conversion_id = conversion.id
LEFT JOIN messages latest_message ON latest_message.id = latest.message_id
SET conversion.last_message_id = latest.message_id,
    conversion.last_activity_at = COALESCE(
        latest_message.created_at,
        conversion.updated_at,
        conversion.created_at,
        CURRENT_TIMESTAMP(6)
    );

ALTER TABLE conversions
    ADD CONSTRAINT fk_conversions_last_message
        FOREIGN KEY (last_message_id) REFERENCES messages (id) ON DELETE SET NULL;
