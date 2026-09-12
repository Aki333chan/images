# Aurum plugin package and Legacy compatibility

## What the panel installs

For a Paper server, the panel offers one logical **Aurum Ecosystem** package:

- AurumCompanion;
- AurumCore;
- AurumAuth;
- AurumGuilds;
- AddonsNPC;
- AurumArena;
- AurumSlots.

The package is not a monolithic shaded JAR. Every component remains a normal,
separately versioned plugin and is downloaded from its own latest stable release in
`Aki333chan/addons`. One button installs all missing components in dependency order;
files already present in `plugins/` are skipped. This keeps stack traces, updates and
rollback understandable while preventing an incomplete selection in the panel.

The API, not the browser, owns the package composition and dependency graph. AddonsNPC
is installed only after AurumCore succeeds. A failure of an independent component does
not hide the result of the others. A running server needs a restart to load newly placed
JARs.

Vault/VaultUnlocked is third-party software and is not repackaged into our releases.
The package window checks whether a Vault JAR is present and warns when it is missing.
Native Aurum integrations still work without it; other plugins that speak only Vault
need VaultUnlocked installed separately. AurumAuth also needs its MariaDB connection
configured before restart; on database connection failure it disables itself safely.

## Can a component run without AurumCore?

| Component      | Without AurumCore                   | Important limitation                                                                                                                                                                                                                     |
| -------------- | ----------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AddonsNPC      | **No, current build does not load** | `depend: [AurumCore]`. Shops, buyers, guild bonuses and exchangers rely on Core holds/claims. Restoring direct Vault payments would discard the crash-safety guarantees.                                                                 |
| AurumGuilds    | **Yes**                             | Guilds and parties load; the bank uses a real Vault provider. Once a particular guild bank has migrated to Core, it deliberately does not fall back to its old Vault mirror. Vault disband payouts cannot be exactly-once after a crash. |
| AurumArena     | **Yes, in item-currency mode**      | Fights, kits and statistics are independent. With legacy `economy.use_vault: true`, account-based bets now mean native AurumCore and are blocked without it. `false` keeps physical ingot/nugget stakes.                                 |
| AurumSlots     | **Yes, in item-currency mode**      | With legacy `use_vault: false`, machines use physical configured currency. `true` now means native AurumCore; the game is blocked when Core is absent.                                                                                   |
| AurumCompanion | **Yes**                             | Basic panel bridge works. Balance/summary and adjustments fall back to Vault; ledger audit, policies, holds and claims have no Vault equivalent and remain unavailable.                                                                  |
| AurumAuth      | **Yes**                             | Independent of Core, but requires its own MariaDB connection.                                                                                                                                                                            |
| AurumCore      | **Yes, natively**                   | VaultUnlocked is needed only to expose the primary Aurum currency to third-party Vault consumers.                                                                                                                                        |

The old config key names `use_vault` in Arena and Slots are retained so existing
configuration files do not break. They no longer mean a direct Vault write path in the
account-based mode. Renaming them requires a separate config migration and should not be
mixed into release installation logic.

## Policy

- Panel installations use the full package so supported servers receive the tested
  integration topology.
- Manual JAR downloads remain possible; the matrix above defines the honest degraded
  behavior.
- We do not add a silent Vault fallback to operations already migrated to holds/claims.
  Availability must not reintroduce item duplication, double payout or an unaudited
  monetary path.
- New hard dependencies must be declared in `packages/shared/src/addons.ts`; the API
  resolves them transitively and tests installation order and fail-closed behavior.
