# AurumCore 0.4.0

Authoritative economy foundation for the Aurum ecosystem. AurumCore owns the
MariaDB ledger in `active` mode, exposes `AurumEconomyApi` to our plugins and
registers the highest-priority Vault economy provider for third-party plugins.
Vault remains the compatibility API; it is not the money store.

`passive` and `shadow` remain non-authoritative. They never change live
Vault/Essentials balances. Shadow mode creates immutable snapshots, imports
them into the dormant ledger, verifies every balance and exports rollback CSV.

## Safe installation and migration

1. Keep EssentialsX and VaultUnlocked unchanged.
2. Copy `AurumCore-0.4.0.jar` to `plugins/`.
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

- `/aurum balance [player]`, short form `/abal` — `aurum.balance`; viewing
  another online player also requires `aurum.balance.others`.
- `/pay <player> <amount> [reason]`, conflict-free `/apay` — `aurum.pay`.
- `/aurum treasury`, short form `/atreasury` — `aurum.admin`.
- `/aurum economy give|take|set <player> <amount> [reason]`, short form
  `/aeco ...` — `aurum.admin.economy`.
- `/aurum migrate ...`, short form `/amigrate ...` — `aurum.admin.migrate`.

The top-level commands `give`, `take` and `set` are intentionally not
registered. Commands have context-aware tab completion. Player names are
accepted only when known to the server, preventing misspellings from creating
money accounts.

## Runtime behavior

Balance and PlaceholderAPI reads use authoritative memory snapshots and never
query MariaDB on the server tick. Vault mutations are synchronous because the
Vault contract requires a definitive result; each mutation commits atomically
or fails closed. Keep MariaDB close to the game server and monitor the
rate-limited warning emitted when a Vault write takes 50 ms or more.

Placeholders: `%aurum_balance%`, `%aurum_balance_raw%`, `%aurum_currency%`,
`%aurum_currency_symbol%`, `%aurum_treasury_balance%`,
`%aurum_money_supply%`, `%aurum_taxes_collected%`.

See `../docs/aurum-core-architecture.md` and
`../docs/aurum-ecosystem-roadmap.md` for the architecture and next stages.
