ALTER TABLE messages
    ADD COLUMN edited_at DATETIME(6) NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL;
