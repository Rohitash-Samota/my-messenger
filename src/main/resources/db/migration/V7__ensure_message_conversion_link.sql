SET @migration_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'messages'
       AND column_name = 'conversion_id') > 0,
    'SELECT 1',
    'ALTER TABLE messages ADD COLUMN conversion_id BIGINT NULL'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'messages'
       AND index_name = 'idx_messages_conversion_id') > 0,
    'SELECT 1',
    'ALTER TABLE messages ADD INDEX idx_messages_conversion_id (conversion_id)'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;

SET @migration_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
     WHERE constraint_schema = DATABASE()
       AND table_name = 'messages'
       AND constraint_name = 'fk_messages_conversion'
       AND constraint_type = 'FOREIGN KEY') > 0,
    'SELECT 1',
    'ALTER TABLE messages ADD CONSTRAINT fk_messages_conversion FOREIGN KEY (conversion_id) REFERENCES conversions (id) ON DELETE SET NULL'
);
PREPARE migration_statement FROM @migration_sql;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;