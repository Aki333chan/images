package ovh.aurumgg.core.engine.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class CoreMigrations {
    private CoreMigrations() {}

    public static List<SchemaMigration> all() {
        List<String> statements = List.of(
                """
                CREATE TABLE IF NOT EXISTS aurum_currencies (
                    id VARCHAR(32) PRIMARY KEY,
                    display_name VARCHAR(64) NOT NULL,
                    symbol VARCHAR(16) NOT NULL,
                    amount_scale TINYINT UNSIGNED NOT NULL,
                    active BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_accounts (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    account_type VARCHAR(32) NOT NULL,
                    reference_id VARCHAR(128) NOT NULL,
                    currency_id VARCHAR(32) NOT NULL,
                    balance DECIMAL(24,8) NOT NULL DEFAULT 0,
                    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                    UNIQUE KEY uq_aurum_account (account_type, reference_id, currency_id),
                    CONSTRAINT fk_aurum_account_currency FOREIGN KEY (currency_id) REFERENCES aurum_currencies(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_transactions (
                    id CHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(191) NOT NULL UNIQUE,
                    currency_id VARCHAR(32) NOT NULL,
                    category VARCHAR(48) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    gross_amount DECIMAL(24,8) NOT NULL,
                    net_amount DECIMAL(24,8) NOT NULL,
                    tax_amount DECIMAL(24,8) NOT NULL DEFAULT 0,
                    tax_rule_id VARCHAR(64) NULL,
                    metadata_json JSON NULL,
                    failure_reason VARCHAR(255) NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    committed_at TIMESTAMP(6) NULL,
                    KEY ix_aurum_transactions_created (created_at),
                    KEY ix_aurum_transactions_category (category, created_at),
                    CONSTRAINT fk_aurum_transaction_currency FOREIGN KEY (currency_id) REFERENCES aurum_currencies(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_ledger_entries (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    transaction_id CHAR(36) NOT NULL,
                    account_id BIGINT UNSIGNED NOT NULL,
                    amount DECIMAL(24,8) NOT NULL,
                    balance_after DECIMAL(24,8) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    KEY ix_aurum_ledger_account (account_id, id),
                    CONSTRAINT fk_aurum_ledger_transaction FOREIGN KEY (transaction_id) REFERENCES aurum_transactions(id),
                    CONSTRAINT fk_aurum_ledger_account FOREIGN KEY (account_id) REFERENCES aurum_accounts(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_financial_rules (
                    id VARCHAR(64) PRIMARY KEY,
                    rule_kind VARCHAR(32) NOT NULL,
                    handler_version INT UNSIGNED NOT NULL,
                    categories_json JSON NOT NULL,
                    definition_json JSON NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    effective_from TIMESTAMP(6) NULL,
                    effective_until TIMESTAMP(6) NULL,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                    KEY ix_aurum_rule_selection (rule_kind, enabled, priority)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_holds (
                    id CHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(191) NOT NULL UNIQUE,
                    account_id BIGINT UNSIGNED NOT NULL,
                    currency_id VARCHAR(32) NOT NULL,
                    amount DECIMAL(24,8) NOT NULL,
                    purpose VARCHAR(48) NOT NULL,
                    reference_id VARCHAR(128) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    expires_at TIMESTAMP(6) NULL,
                    resolved_at TIMESTAMP(6) NULL,
                    KEY ix_aurum_holds_account (account_id, status),
                    CONSTRAINT fk_aurum_hold_account FOREIGN KEY (account_id) REFERENCES aurum_accounts(id),
                    CONSTRAINT fk_aurum_hold_currency FOREIGN KEY (currency_id) REFERENCES aurum_currencies(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_trades (
                    id CHAR(36) PRIMARY KEY,
                    first_player CHAR(36) NOT NULL,
                    second_player CHAR(36) NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    first_confirmed_revision BIGINT UNSIGNED NULL,
                    second_confirmed_revision BIGINT UNSIGNED NULL,
                    expires_at TIMESTAMP(6) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                    KEY ix_aurum_trade_first (first_player, state),
                    KEY ix_aurum_trade_second (second_player, state)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_trade_offers (
                    trade_id CHAR(36) NOT NULL,
                    owner_uuid CHAR(36) NOT NULL,
                    currency_id VARCHAR(32) NULL,
                    money_amount DECIMAL(24,8) NULL,
                    items_blob MEDIUMBLOB NULL,
                    items_format_version INT UNSIGNED NOT NULL DEFAULT 1,
                    PRIMARY KEY (trade_id, owner_uuid),
                    CONSTRAINT fk_aurum_trade_offer_trade FOREIGN KEY (trade_id) REFERENCES aurum_trades(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_outbox (
                    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    event_id CHAR(36) NOT NULL UNIQUE,
                    event_type VARCHAR(64) NOT NULL,
                    payload_json JSON NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    published_at TIMESTAMP(6) NULL,
                    attempts INT UNSIGNED NOT NULL DEFAULT 0,
                    KEY ix_aurum_outbox_pending (published_at, id)
                ) ENGINE=InnoDB
                """
        );
        List<String> shadowAndPolicyLinks = List.of(
                """
                ALTER TABLE aurum_transactions
                    ADD COLUMN IF NOT EXISTS policy_rule_ids_json JSON NULL AFTER tax_rule_id
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_shadow_balances (
                    player_uuid CHAR(36) NOT NULL,
                    currency_id VARCHAR(32) NOT NULL,
                    external_balance DECIMAL(24,8) NOT NULL,
                    internal_balance DECIMAL(24,8) NULL,
                    difference_amount DECIMAL(24,8) NULL,
                    provider_name VARCHAR(64) NOT NULL,
                    observed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (player_uuid, currency_id),
                    KEY ix_aurum_shadow_difference (difference_amount),
                    CONSTRAINT fk_aurum_shadow_currency FOREIGN KEY (currency_id) REFERENCES aurum_currencies(id)
                ) ENGINE=InnoDB
                """
        );
        List<String> migrationRuns = List.of(
                """
                CREATE TABLE IF NOT EXISTS aurum_migration_runs (
                    run_id CHAR(36) PRIMARY KEY,
                    provider_name VARCHAR(64) NOT NULL,
                    currency_id VARCHAR(32) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    player_count INT UNSIGNED NOT NULL DEFAULT 0,
                    read_failures INT UNSIGNED NOT NULL DEFAULT 0,
                    mismatch_count INT UNSIGNED NOT NULL DEFAULT 0,
                    negative_count INT UNSIGNED NOT NULL DEFAULT 0,
                    external_total DECIMAL(24,8) NOT NULL DEFAULT 0,
                    internal_total DECIMAL(24,8) NOT NULL DEFAULT 0,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    completed_at TIMESTAMP(6) NULL,
                    KEY ix_aurum_migration_latest (currency_id, created_at),
                    CONSTRAINT fk_aurum_migration_currency FOREIGN KEY (currency_id) REFERENCES aurum_currencies(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_migration_balances (
                    run_id CHAR(36) NOT NULL,
                    player_uuid CHAR(36) NOT NULL,
                    username VARCHAR(64) NOT NULL,
                    external_balance DECIMAL(24,8) NOT NULL,
                    internal_balance DECIMAL(24,8) NOT NULL DEFAULT 0,
                    difference_amount DECIMAL(24,8) NOT NULL DEFAULT 0,
                    PRIMARY KEY (run_id, player_uuid),
                    KEY ix_aurum_migration_difference (run_id, difference_amount),
                    CONSTRAINT fk_aurum_migration_run FOREIGN KEY (run_id) REFERENCES aurum_migration_runs(run_id)
                        ON DELETE CASCADE
                ) ENGINE=InnoDB
                """
        );
        List<String> runtimeState = List.of(
                """
                CREATE TABLE IF NOT EXISTS aurum_runtime_state (
                    state_key VARCHAR(64) PRIMARY KEY,
                    state_value VARCHAR(512) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                        ON UPDATE CURRENT_TIMESTAMP(6)
                ) ENGINE=InnoDB
                """
        );
        List<String> policyRevisions = List.of(
                """
                ALTER TABLE aurum_transactions
                    ADD COLUMN IF NOT EXISTS policy_amounts_json JSON NULL AFTER policy_rule_ids_json
                """,
                """
                ALTER TABLE aurum_financial_rules
                    ADD COLUMN IF NOT EXISTS revision BIGINT UNSIGNED NOT NULL DEFAULT 0,
                    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(128) NOT NULL DEFAULT 'system',
                    ADD COLUMN IF NOT EXISTS update_reason VARCHAR(255) NOT NULL DEFAULT 'legacy'
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_financial_rule_revisions (
                    rule_id VARCHAR(64) NOT NULL,
                    revision BIGINT UNSIGNED NOT NULL,
                    rule_kind VARCHAR(32) NOT NULL,
                    handler_version INT UNSIGNED NOT NULL,
                    categories_json JSON NOT NULL,
                    definition_json JSON NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL,
                    effective_from TIMESTAMP(6) NULL,
                    effective_until TIMESTAMP(6) NULL,
                    changed_by VARCHAR(128) NOT NULL,
                    change_reason VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (rule_id, revision),
                    KEY ix_aurum_rule_revision_time (created_at)
                ) ENGINE=InnoDB
                """
        );
        List<String> currencyExchange = List.of(
                """
                CREATE TABLE IF NOT EXISTS aurum_exchange_rules (
                    id VARCHAR(64) PRIMARY KEY,
                    from_currency_id VARCHAR(32) NOT NULL,
                    to_currency_id VARCHAR(32) NOT NULL,
                    rate DECIMAL(36,18) NOT NULL,
                    fee_rate DECIMAL(18,12) NOT NULL DEFAULT 0,
                    minimum_source DECIMAL(24,8) NULL,
                    maximum_source DECIMAL(24,8) NULL,
                    settlement VARCHAR(24) NOT NULL,
                    conditions_json JSON NOT NULL,
                    priority INT NOT NULL DEFAULT 0,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    effective_from TIMESTAMP(6) NULL,
                    effective_until TIMESTAMP(6) NULL,
                    revision BIGINT UNSIGNED NOT NULL,
                    updated_by VARCHAR(128) NOT NULL,
                    update_reason VARCHAR(255) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                    KEY ix_aurum_exchange_pair (from_currency_id, to_currency_id, enabled, priority),
                    CONSTRAINT fk_aurum_exchange_from FOREIGN KEY (from_currency_id) REFERENCES aurum_currencies(id),
                    CONSTRAINT fk_aurum_exchange_to FOREIGN KEY (to_currency_id) REFERENCES aurum_currencies(id)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_exchange_rule_revisions (
                    rule_id VARCHAR(64) NOT NULL,
                    revision BIGINT UNSIGNED NOT NULL,
                    from_currency_id VARCHAR(32) NOT NULL,
                    to_currency_id VARCHAR(32) NOT NULL,
                    rate DECIMAL(36,18) NOT NULL,
                    fee_rate DECIMAL(18,12) NOT NULL,
                    minimum_source DECIMAL(24,8) NULL,
                    maximum_source DECIMAL(24,8) NULL,
                    settlement VARCHAR(24) NOT NULL,
                    conditions_json JSON NOT NULL,
                    priority INT NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    effective_from TIMESTAMP(6) NULL,
                    effective_until TIMESTAMP(6) NULL,
                    changed_by VARCHAR(128) NOT NULL,
                    change_reason VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (rule_id, revision)
                ) ENGINE=InnoDB
                """,
                """
                CREATE TABLE IF NOT EXISTS aurum_exchanges (
                    id CHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(191) NOT NULL UNIQUE,
                    rule_id VARCHAR(64) NOT NULL,
                    rule_revision BIGINT UNSIGNED NOT NULL,
                    account_type VARCHAR(32) NOT NULL,
                    account_reference VARCHAR(128) NOT NULL,
                    from_currency_id VARCHAR(32) NOT NULL,
                    to_currency_id VARCHAR(32) NOT NULL,
                    source_amount DECIMAL(24,8) NOT NULL,
                    fee_amount DECIMAL(24,8) NOT NULL,
                    converted_amount DECIMAL(24,8) NOT NULL,
                    target_amount DECIMAL(24,8) NOT NULL,
                    settlement VARCHAR(24) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    metadata_json JSON NULL,
                    failure_reason VARCHAR(255) NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    committed_at TIMESTAMP(6) NULL,
                    KEY ix_aurum_exchange_account (account_type, account_reference, created_at),
                    KEY ix_aurum_exchange_pair_time (from_currency_id, to_currency_id, created_at)
                ) ENGINE=InnoDB
                """
        );
        List<String> durableHolds = List.of(
                """
                ALTER TABLE aurum_holds
                    ADD COLUMN IF NOT EXISTS target_account_type VARCHAR(32) NULL AFTER expires_at,
                    ADD COLUMN IF NOT EXISTS target_reference_id VARCHAR(128) NULL AFTER target_account_type,
                    ADD COLUMN IF NOT EXISTS request_amount DECIMAL(24,8) NULL AFTER target_reference_id,
                    ADD COLUMN IF NOT EXISTS transaction_category VARCHAR(48) NULL AFTER request_amount,
                    ADD COLUMN IF NOT EXISTS metadata_json JSON NULL AFTER transaction_category
                """,
                """
                ALTER TABLE aurum_holds
                    ADD INDEX IF NOT EXISTS ix_aurum_holds_expiry (status, expires_at)
                """
        );
        List<String> deliveryClaims = List.of(
                """
                CREATE TABLE IF NOT EXISTS aurum_claims (
                    id CHAR(36) PRIMARY KEY,
                    idempotency_key VARCHAR(191) NOT NULL UNIQUE,
                    plugin VARCHAR(64) NOT NULL,
                    owner_uuid CHAR(36) NOT NULL,
                    kind VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    step_cursor INT UNSIGNED NOT NULL DEFAULT 0,
                    step_count INT UNSIGNED NOT NULL,
                    attempts INT UNSIGNED NOT NULL DEFAULT 0,
                    summary VARCHAR(255) NOT NULL DEFAULT '',
                    payload MEDIUMTEXT NOT NULL,
                    last_error VARCHAR(255) NOT NULL DEFAULT '',
                    claimed_by VARCHAR(64) NULL,
                    lease_until TIMESTAMP(6) NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
                    settled_at TIMESTAMP(6) NULL,
                    KEY ix_aurum_claims_owed (plugin, owner_uuid, status, created_at),
                    KEY ix_aurum_claims_status (status, plugin, updated_at),
                    KEY ix_aurum_claims_lease (status, lease_until)
                ) ENGINE=InnoDB
                """
        );
        List<String> tradeOfferOperations = List.of(
                """
                CREATE TABLE IF NOT EXISTS aurum_trade_offer_operations (
                    operation_key VARCHAR(191) PRIMARY KEY,
                    trade_id CHAR(36) NOT NULL,
                    owner_uuid CHAR(36) NOT NULL,
                    intent_hash CHAR(64) NOT NULL,
                    revision_after BIGINT UNSIGNED NOT NULL,
                    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    KEY ix_aurum_trade_offer_operation_owner (owner_uuid)
                ) ENGINE=InnoDB
                """
        );
        List<String> transactionIntentHashes = List.of(
                """
                ALTER TABLE aurum_transactions
                    ADD COLUMN IF NOT EXISTS request_hash CHAR(64) NULL AFTER idempotency_key
                """
        );
        return List.of(
                new SchemaMigration(1, "ledger, treasury, policies, holds, trades and outbox",
                        checksum(statements), statements),
                new SchemaMigration(2, "shadow balance observations and generalized policy links",
                        checksum(shadowAndPolicyLinks), shadowAndPolicyLinks),
                new SchemaMigration(3, "repeatable economy migration snapshots",
                        checksum(migrationRuns), migrationRuns),
                new SchemaMigration(4, "authoritative runtime cutover state",
                        checksum(runtimeState), runtimeState),
                new SchemaMigration(5, "versioned financial policy audit",
                        checksum(policyRevisions), policyRevisions),
                new SchemaMigration(6, "multi-currency exchange rates and atomic exchanges",
                        checksum(currencyExchange), currencyExchange),
                new SchemaMigration(7, "durable cross-system hold intents",
                        checksum(durableHolds), durableHolds),
                new SchemaMigration(8, "durable delivery claims and quarantine",
                        checksum(deliveryClaims), deliveryClaims),
                new SchemaMigration(9, "idempotent outgoing trade item offers",
                        checksum(tradeOfferOperations), tradeOfferOperations),
                new SchemaMigration(10, "bind ledger idempotency keys to transaction intents",
                        checksum(transactionIntentHashes), transactionIntentHashes)
        );
    }

    private static String checksum(List<String> statements) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            statements.forEach(sql -> digest.update(sql.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
