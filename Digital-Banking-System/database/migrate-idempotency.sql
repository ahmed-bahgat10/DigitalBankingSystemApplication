-- Manual migration for the existing MySQL database; not executed by the application.
-- Stop all application instances running the old transfer implementation first.
-- Select the application database before running this script with a MySQL client.
-- DDL commits implicitly in MySQL. Take a backup before applying schema changes.
--
-- Inspect unresolved legacy reservations before migration:
-- SELECT * FROM idempotency_records WHERE status NOT IN ('COMPLETED', 'FAILED');
-- Reconcile these against actual transactions/audit records. Never blindly delete
-- reservations or assume a missing transaction_id proves a transfer did not commit.
-- The procedure refuses to continue until every retained record is terminal.

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_transfer_idempotency$$
CREATE PROCEDURE migrate_transfer_idempotency()
BEGIN
    IF EXISTS (
        SELECT 1 FROM idempotency_records
        WHERE status NOT IN ('COMPLETED', 'FAILED') OR transaction_id IS NULL
           OR (status = 'FAILED' AND failure_reason IS NULL)
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Reconcile unresolved legacy idempotency records before migration';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'transactions'
          AND column_name = 'failure_message'
    ) THEN
        ALTER TABLE transactions ADD COLUMN failure_message VARCHAR(1024) NULL;
    END IF;

    ALTER TABLE transactions MODIFY COLUMN failure_reason
        ENUM('INSUFFICIENT_FUNDS', 'ACCOUNT_SUSPENDED', 'ACCOUNT_CLOSED',
             'DAILY_LIMIT_EXCEEDED', 'INVALID_TRANSFER') NULL;
    ALTER TABLE idempotency_records MODIFY COLUMN failure_reason
        ENUM('INSUFFICIENT_FUNDS', 'ACCOUNT_SUSPENDED', 'ACCOUNT_CLOSED',
             'DAILY_LIMIT_EXCEEDED', 'INVALID_TRANSFER') NULL;
    ALTER TABLE idempotency_records MODIFY COLUMN status ENUM('COMPLETED', 'FAILED') NOT NULL;

    -- Preserve the existing uk_user_idempotency_key unique constraint.
    -- The unused processing_expires_at column can remain for schema compatibility.
END$$
CALL migrate_transfer_idempotency()$$
DROP PROCEDURE migrate_transfer_idempotency$$
DELIMITER ;