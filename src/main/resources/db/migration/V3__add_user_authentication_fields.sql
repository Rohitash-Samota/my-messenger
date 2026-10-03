ALTER TABLE users
    ADD COLUMN email VARCHAR(50) NULL,
    ADD COLUMN phonenumber BIGINT NULL,
    ADD COLUMN status VARCHAR(50) NULL,
    ADD COLUMN user_role VARCHAR(50) NULL,
    ADD COLUMN photo VARCHAR(100) NULL,
    ADD COLUMN password VARCHAR(255) NULL,
    ADD COLUMN created_at DATETIME(6) NULL,
    ADD COLUMN updated_at DATETIME(6) NULL;

UPDATE users
SET email = CONCAT('legacy-', id, '@invalid.local'),
    status = 'ACTIVE',
    user_role = 'USER',
    created_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE email IS NULL;

ALTER TABLE users
    MODIFY COLUMN email VARCHAR(50) NOT NULL,
    MODIFY COLUMN status VARCHAR(50) NOT NULL,
    MODIFY COLUMN user_role VARCHAR(50) NOT NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT uk_users_email UNIQUE (email),
    ADD CONSTRAINT uk_users_phonenumber UNIQUE (phonenumber);