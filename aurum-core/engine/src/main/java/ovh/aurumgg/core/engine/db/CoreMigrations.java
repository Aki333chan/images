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
        return List.of(
                new SchemaMigration(1, "ledger, treasury, policies, holds, trades and outbox",
                        checksum(statements), statements),
                new SchemaMigration(2, "shadow balance observations and generalized policy links",
                        checksum(shadowAndPolicyLinks), shadowAndPolicyLinks)
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
