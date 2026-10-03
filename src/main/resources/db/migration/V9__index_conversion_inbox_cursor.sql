ALTER TABLE conversions
    DROP INDEX idx_conversions_user_id,
    ADD INDEX idx_conversions_user_archive_pin_id (user_id, is_archive, is_pin, id);