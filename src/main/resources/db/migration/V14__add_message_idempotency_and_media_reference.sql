ALTER TABLE messages
    ADD COLUMN original_content_sha256 CHAR(64)
        CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN media_id CHAR(36)
        CHARACTER SET ascii COLLATE ascii_bin NULL;

-- Only rows that have never been edited or deleted still contain a trustworthy
-- copy of their original request body. Mutated legacy rows deliberately remain
-- NULL so a replay cannot be incorrectly accepted against their current body.
UPDATE messages
SET original_content_sha256 = SHA2(content, 256)
WHERE edited_at IS NULL
  AND deleted_at IS NULL
  AND content IS NOT NULL;

-- Media URLs have always been canonical, local UUID URLs. Deleted legacy rows
-- no longer have content to recover; new deletes retain media_id on the row.
UPDATE messages
SET media_id = SUBSTRING(content, 15)
WHERE message_type IN ('IMAGE', 'VIDEO', 'AUDIO')
  AND REGEXP_LIKE(
        content,
        '^/v1/api/media/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$',
        'c');

ALTER TABLE messages
    ADD INDEX idx_messages_conversion_media_deleted
        (conversion_id, media_id, deleted_at);
