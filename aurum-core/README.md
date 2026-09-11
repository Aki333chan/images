# AurumCore 0.6.1

Authoritative economy foundation for the Aurum ecosystem. AurumCore owns the
MariaDB ledger in `active` mode, exposes `AurumEconomyApi` to our plugins and
registers the highest-priority Vault economy provider for third-party plugins.
Vault remains the compatibility API; it is not the money store.

`passive` and `shadow` remain non-authoritative. They never change live
Vault/Essentials balances. Shadow mode creates immutable snapshots, imports
them into the dormant ledger, verifies every balance and exports rollback CSV.

## Safe installation and migration

1. Keep EssentialsX and VaultUnlocked unchanged.
2. Copy `AurumCore-0.6.1.jar` to `plugins/`.
3. Start with `economy.mode: passive` and `database.enabled: false`.
4. Run `/aurum status`; it must show the current Vault provider and writes OFF.
5. Configure MariaDB, set `database.enabled: true` and `economy.mode: shadow`.
6. Run `/amigrate dry-run`, inspect `/amigrate status`, then run
   `/amigrate import <run-id> CONFIRM` and `/amigrate verify <run-id>`.
7. Run `/amigrate rollback-export` and back up MariaDB plus the server plugins.
8. In a maintenance window set `economy.mode: active` and restart. Active mode
   refuses its first start unless the latest imported snapshot still verifies.
9. Confirm `/aurum status`, `/abal`, `/atreasury`, `/apay` and one reversible
   test through another Vault-dependent plugin before opening the server.

Do not disable the old provider or delete its data before the staging and
rollback tests pass. `active.require-verified-migration: false` is only for a
deliberately fresh, empty ledger; a non-empty unverified ledger is rejected.

## Commands and permissions

- `/aurum balance [player] [currency]`, short form `/abal [currency]` — `aurum.balance`; viewing
  another online player also requires `aurum.balance.others`.
- `/pay <player> <amount> [reason]`, conflict-free `/apay` — `aurum.pay`.
- `/aurum treasury [currency]`, short form `/atreasury [currency]` — `aurum.admin`.
- `/aurum economy give|take|set <player> <amount> [currency:<id>] [reason]`, short form
  `/aeco ...` — `aurum.admin.economy`.
- `/aurum migrate ...`, short form `/amigrate ...` — `aurum.admin.migrate`.
- `/aurum policy ...`, short form `/apolicy ...` — `aurum.admin.policy`.
- `/aurum exchange ...`, short form `/aexchange ...` — `aurum.admin.exchange`.

The top-level commands `give`, `take` and `set` are intentionally not
registered. Commands have context-aware tab completion. Player names are
accepted only when known to the server, preventing misspellings from creating
money accounts.

## Financial policies

The policy pipeline supports percentage taxes, fees and commissions in
`INCLUDED` or `ADDED` mode, treasury-funded cashback and subsidies, transaction
limits and scoped exemptions. Several matching rules form one balanced ledger
plan and commit atomically with the original payment. Exact per-rule amounts,
rule IDs and immutable rule revisions are retained for audit.

Rules can be prepared in YAML and imported only on an empty policy database, or
explicitly re-imported with `/apolicy import-config CONFIRM`. Runtime edits are
stored as MariaDB revisions with actor, reason and schedule. The master switch
`financial-policies.enabled` changes only after restart. Raw Vault compatibility
deposits/withdrawals, migration, refunds and administrative adjustments are
protected from policies; our game plugins receive policy processing when they
move to `AurumEconomyApi` categories.

## Multiple currencies and exchange

`economy.currencies` defines up to 16 enabled currencies, while
`economy.primary-currency` selects the only currency exposed through Vault.
Native Aurum plugins can query and transfer every currency explicitly through
`AurumEconomyApi`. Exchange is a single MariaDB transaction: debit, fee,
settlement and credit either all commit or all roll back. A quote records the
rule revision, exact output and expiry; execution recalculates and rejects a
stale or changed quote.

Rules support fixed rates, percentage fees paid to the global treasury,
minimum/maximum input, time windows, priorities and account/metadata
conditions. `MINT_BURN` is convenient for centrally managed currencies;
`RESERVE` requires target liquidity and never creates it during exchange.
Fund or withdraw that liquidity with
`/aexchange reserve <rule> <currency> <give|take> <amount>`.

See `../docs/aurum-multi-currency.md` for the full command and API contract.

## Runtime behavior

Balance and PlaceholderAPI reads use authoritative memory snapshots and never
query MariaDB on the server tick. Vault mutations are synchronous because the
Vault contract requires a definitive result; each mutation commits atomically
or fails closed. Keep MariaDB close to the game server and monitor the
rate-limited warning emitted when a Vault write takes 50 ms or more.

Placeholders: `%aurum_balance%`, `%aurum_balance_raw%`, `%aurum_currency%`,
`%aurum_currency_symbol%`, `%aurum_treasury_balance%`,
`%aurum_money_supply%`, `%aurum_taxes_collected%`.
Currency-specific variants include `%aurum_balance_tokens%`,
`%aurum_balance_raw_tokens%`, `%aurum_treasury_balance_tokens%`,
`%aurum_money_supply_tokens%` and `%aurum_currency_tokens_symbol%`.

See `../docs/aurum-core-architecture.md` and
`../docs/aurum-policy-engine.md` for commands, configuration and semantics.
