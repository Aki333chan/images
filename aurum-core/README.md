# AurumCore 0.3.0

Non-authoritative foundation and guarded migration tooling for the Aurum
economy. This release never registers a Vault Economy provider and never changes
the live Vault/Essentials balances. `passive` observes balances; `shadow` can
take repeatable snapshots, import them into the dormant ledger, verify every
balance and export a rollback CSV.

## First installation

1. Keep EssentialsX and VaultUnlocked unchanged.
2. Copy `AurumCore-0.3.0.jar` to `plugins/`.
3. Start once with `economy.mode: passive` and `database.enabled: false`.
4. Run `/aurum status`; it must show the current Vault provider and writes OFF.
5. Configure MariaDB, enable only `database.enabled`, restart and verify READY.

## Shadow migration rehearsal

1. Keep EssentialsX and VaultUnlocked active and make a database backup.
2. Set `database.enabled: true` and `economy.mode: shadow`, then restart.
3. Run `/amigrate dry-run`. Vault reads are spread over ticks according to
   `migration.players-per-tick`.
4. Inspect `/amigrate status`. A snapshot with read failures or negative
   balances cannot be imported.
5. Run `/amigrate import <run-id> CONFIRM`. This writes only into the dormant
   Aurum ledger; live player money remains served by the old provider.
6. Run `/amigrate verify <run-id>` and `/amigrate rollback-export` before any
   later cutover. Active mode is intentionally not available in this release.

Placeholders: `%aurum_balance%`, `%aurum_balance_raw%`, `%aurum_currency%`,
`%aurum_currency_symbol%`. Global placeholders intentionally return an empty
value until the authoritative ledger exists.

See `../docs/aurum-core-architecture.md` for the accepted architecture,
migration gates, treasury, financial policies and guaranteed player trades.
