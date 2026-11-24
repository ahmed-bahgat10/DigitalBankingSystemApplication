-- Manual MySQL migration. This file is NOT run by Spring/Flyway/Liquibase.
-- Stop application writers, take a backup, select the application database, and
-- apply the earlier idempotency migration first if that upgrade is still pending.
-- Test on a disposable MySQL clone before deployment. MySQL DDL commits implicitly.
-- Existing spring.jpa.hibernate.ddl-auto=update is unchanged; run this migration
-- before starting the new application against a populated database.
--
-- Preflight: inspect existing indexes rather than adding duplicate FK indexes:
SHOW INDEX FROM transactions;
-- The reference column already has a unique constraint.
-- FK-prefix indexes may already exist because MySQL creates them automatically.

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_transaction_history$$
CREATE PROCEDURE migrate_transaction_history()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'transactions'
          AND column_name = 'initiated_by_id'
    ) THEN
        ALTER TABLE transactions ADD COLUMN initiated_by_id BIGINT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.key_column_usage
        WHERE table_schema = DATABASE() AND table_name = 'transactions'
          AND column_name = 'initiated_by_id'
          AND referenced_table_name = 'user' AND referenced_column_name = 'id'
    ) THEN
        ALTER TABLE transactions ADD CONSTRAINT fk_transaction_initiator
            FOREIGN KEY (initiated_by_id) REFERENCES `user`(id) ON DELETE RESTRICT;
    END IF;

    -- Retained idempotency records are explicit initiating-user evidence.
    -- Never infer initiator from today's source/destination account ownership.
    -- Do not overwrite already attributed transactions.
    -- BEGIN RELIABLE BACKFILL
    UPDATE transactions t
    SET initiated_by_id = (
        SELECT i.user_id FROM idempotency_records i WHERE i.transaction_id = t.id
    )
    WHERE t.initiated_by_id IS NULL AND t.type = 'TRANSFER'
      AND EXISTS (SELECT 1 FROM idempotency_records i WHERE i.transaction_id = t.id);
    -- END RELIABLE BACKFILL

    -- Accept equivalent existing index prefixes even if their names differ.
    -- InnoDB secondary indexes already include the primary key id implicitly.
    IF NOT EXISTS (
        SELECT index_name FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'transactions'
        GROUP BY index_name
        HAVING MAX(CASE WHEN seq_in_index = 1 THEN column_name END) = 'source_account_id'
           AND MAX(CASE WHEN seq_in_index = 2 THEN column_name END) = 'created_at'
    ) THEN
        CREATE INDEX idx_transaction_source_history
            ON transactions(source_account_id, created_at);
    END IF;
    IF NOT EXISTS (
        SELECT index_name FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'transactions'
        GROUP BY index_name
        HAVING MAX(CASE WHEN seq_in_index = 1 THEN column_name END) = 'destination_account_id'
           AND MAX(CASE WHEN seq_in_index = 2 THEN column_name END) = 'created_at'
    ) THEN
        CREATE INDEX idx_transaction_destination_history
            ON transactions(destination_account_id, created_at);
    END IF;
    IF NOT EXISTS (
        SELECT index_name FROM information_schema.statistics
        WHERE table_schema = DATABASE() AND table_name = 'transactions'
        GROUP BY index_name
        HAVING MAX(CASE WHEN seq_in_index = 1 THEN column_name END) = 'initiated_by_id'
           AND MAX(CASE WHEN seq_in_index = 2 THEN column_name END) = 'created_at'
    ) THEN
        CREATE INDEX idx_transaction_initiator_history
            ON transactions(initiated_by_id, created_at);
    END IF;
END$$
CALL migrate_transaction_history()$$
DROP PROCEDURE migrate_transaction_history$$
DELIMITER ;

-- REQUIRED REVIEW REPORT: these rows remain intentionally unattributed.
-- Deposits/withdrawals without explicit initiator evidence are not guessed either.
-- Failed/pending transfers without an initiator are hidden (fail closed).
-- Completed transfers and deposits/withdrawals still use authorized account visibility.
SELECT t.id, t.reference, t.type, t.status, t.created_at,
       CASE WHEN t.type = 'TRANSFER'
            THEN 'No retained explicit initiating-user evidence'
            ELSE 'Account ownership is not initiating-user evidence'
       END AS unresolved_reason
FROM transactions t
WHERE t.initiated_by_id IS NULL
ORDER BY t.id;

-- Also investigate explicit evidence conflicting with an existing initiator.
SELECT t.id, t.reference
FROM transactions t
JOIN idempotency_records i ON i.transaction_id = t.id
WHERE t.initiated_by_id IS NOT NULL AND t.initiated_by_id <> i.user_id;

SHOW INDEX FROM transactions;
-- Preserve existing FK-only indexes: do not drop unrelated indexes automatically.
-- Use EXPLAIN on representative populated MySQL data to tune the OR visibility
-- predicates. Schema/index presence alone does not establish a production query plan.