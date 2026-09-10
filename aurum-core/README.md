# AurumCore 0.2.0

Passive, non-authoritative foundation for the Aurum economy. This release does
not register a Vault Economy provider and cannot modify money. It observes the
current provider, exposes a small Java API through Bukkit ServicesManager,
provides cached PlaceholderAPI values and can initialize the versioned MariaDB
schema. The authoritative ledger executor is implemented and tested internally,
but deliberately not exposed to Paper or Vault before migration verification.

## First installation

1. Keep EssentialsX and VaultUnlocked unchanged.
2. Copy `AurumCore-0.2.0.jar` to `plugins/`.
3. Start once with `economy.mode: passive` and `database.enabled: false`.
4. Run `/aurum status`; it must show the current Vault provider and writes OFF.
5. Configure MariaDB, enable only `database.enabled`, restart and verify READY.

Placeholders: `%aurum_balance%`, `%aurum_balance_raw%`, `%aurum_currency%`,
`%aurum_currency_symbol%`. Global placeholders intentionally return an empty
value until the authoritative ledger exists.

See `../docs/aurum-core-architecture.md` for the accepted architecture,
migration gates, treasury, financial policies and guaranteed player trades.
