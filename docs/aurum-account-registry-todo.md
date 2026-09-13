# Aurum account registry and named funds — TODO

Status: implementation in progress; Core/Companion/panel and first plugin lifecycle shipped in
the 0.17.0 generation. Live MariaDB/Paper fault injection remains required before enabling
destructive close workflows on production data.

This stage turns ledger addresses that already exist into managed objects with a
name, owner/founder, lifecycle and a safe destination for the remaining balance.
It does not replace `aurum_accounts`: the ledger remains the authority for money;
the registry stores metadata and relationships only.

## Product requirements

- The panel economy tab must list accounts, not only players and
  `TREASURY:global`.
- The list must cover player wallets, guild accounts, Arena accounts, every
  individual Slots machine, named treasuries/funds, and future city/region
  treasuries. Technical source/sink and escrow accounts are hidden by default
  behind an administrator filter.
- A details view shows stable account key, type, currency balances, status,
  founder, current controller/owner, linked plugin object, creation/closure time
  and ledger history.
- Founder is immutable historical metadata. Current controller is separate:
  changing a guild leader or region owner must not rewrite who founded it.
- Players see their own wallets. Guild visibility/actions follow guild rank.
  Full cross-account browsing and administration require economy-admin rights.

No dedicated event-prize fund is planned: event prizes may use the global
treasury unless a named fund is explicitly created by an administrator.

## Named funds

Support managed `TREASURY:<id>` accounts, for example:

- `TREASURY:global` — the default central treasury;
- `TREASURY:procurement` — optional public procurement budget;
- `TREASURY:guild-support` — optional guild support budget;
- `TREASURY:city:<id>` / `TREASURY:region:<id>` — future city or region
  treasuries.

Cashback and subsidy continue to use `TREASURY:global` by default. Policy rules
already support another `funding-type`/`funding-id`, so named funding remains an
optional override rather than a mandatory extra account.

The panel must be able to create a named fund, inspect it, transfer money between
it and the global treasury, freeze/unfreeze it and close it. Every mutation needs
an idempotency key, actor and reason. Deleting ledger history is never allowed.

Administrators can also open one dedicated forced-transfer dialog and select
any two active non-technical managed account members. Both selectors are
server-side searchable, filterable and sorted by account type. The amount,
currency and reason are explicit; Core performs one balanced atomic transfer.
This does not bypass insufficient-funds or account-lifecycle checks and cannot
select `SYSTEM_SOURCE`/`SYSTEM_SINK` to disguise issuance or destruction as a
transfer.

## Arena and Slots ownership

Each Arena and each Slots machine owns an independent managed economic entity.

Arena already separates liabilities correctly into
`ARENA_ESCROW:bet:<arena>` and `ARENA_ESCROW:final:<arena>`. Do not merge these
balances: ordinary bet refunds must never spend the champion pool. Register them
as two subaccounts under one Arena account profile, and display their individual
and combined balances in the panel.

Each Slots machine keeps its own `SLOTS:<machine-id>` account. A later economy
decision must also replace the current `SYSTEM_SOURCE:slot-payouts` model with a
configurable machine/treasury bankroll; this is required before account closure
can honestly describe all money belonging to the machine.

On Arena or machine deletion, the plugin must settle/refund active obligations,
then atomically sweep every remaining balance to a configured destination before
the game object disappears. Default destination:

```yaml
economy:
  account-close-destination:
    type: TREASURY
    id: global
```

The destination may be another active named treasury/fund. An optional
per-Arena/per-machine override may be added, but the plugin-wide config above is
the safe default. A missing, frozen or closed destination fails deletion instead
of stranding or destroying money.

Deletion lifecycle:

1. mark the managed account `CLOSING` and reject new bets/spins;
2. reject closure while unresolved holds, payouts, refunds or claims remain;
3. persist an idempotent close plan, including every currency and destination;
4. sweep balances with stable transaction keys;
5. mark the account `CLOSED`, then remove/disable the Arena or machine;
6. keep metadata and ledger history visible in archived-account audit.

A crash resumes the same close plan; it must never pay the destination twice.

## Registry model

Add a metadata table keyed by the existing stable account key, conceptually:

- stable account type and reference;
- display name and purpose;
- owner kind/id and immutable founder UUID;
- source plugin and linked object ID;
- `ACTIVE`, `FROZEN`, `CLOSING` or `CLOSED` status;
- configured close destination;
- creation and closure timestamps.

Use indexed columns for fields used by account search/filtering. Arbitrary JSON
metadata may hold presentation-only extension data, but not ownership, status or
money-routing decisions.

## Procurement, guild support, cities and regions

- Procurement is a first-class future flow with an auditable budget and the
  existing `PROCUREMENT` transaction category. It may be funded directly from
  global treasury or from `TREASURY:procurement`.
- Guild support may use SUBSIDY or explicit transfers from global/named treasury.
  It must never mint silently through `SYSTEM_SOURCE` when configured as public
  spending.
- City/region treasuries are supported in the registry now, while gameplay is
  deferred. Link them by stable city/region ID, not a mutable display name.
  WorldGuard owner/member data is an authorization source, not the ledger owner
  record itself.

## Delivery order

1. [~] Finish current live smoke/fault-injection testing.
2. [x] Add registry schema, read API and account list/details in Companion + panel.
   Account details include on-demand ledger history with explicit member-role
   and currency selectors, so Arena `bet` and `final` are never mixed.
3. [x] Add named-fund create/transfer/freeze/close operations with permissions and
   audit. The status gate is an in-memory index loaded once and updated on registry
   mutations; it adds no SQL query to the hot payment path.
4. [x] Register existing player, guild, Arena and Slots accounts idempotently.
5. [x] Implement Arena/Slots close plans and configurable sweep destination.
6. [x] Add an administrator forced-transfer dialog for any active managed
   account members, including Arena `bet`/`final` roles.
7. Decide and implement Slots bankroll and NPC-buyer budget sources.
8. Add procurement and guild-support administration.
9. Leave city/region gameplay integration dormant until that system is designed.

Additional follow-up completed in Guilds 0.5.1: the managed guild profile is closed only after
the existing durable guild-disband settlement completes and before the guild row is deleted.
`keep` freezes the retained profile for manual administration. A zero balance also persists the
barrier, and ledger mode snapshots the authoritative Core balance before building the plan.
At first adoption of a legacy guild, the current leader remains the best available founder
because the old schema did not retain the original creator.

## Acceptance tests

- Two arenas and two machines never share a managed account or balance.
- Deleting one object cannot move or display another object's money.
- Default closure sweeps to `TREASURY:global`; configured named destination works.
- Active hold/claim/payout blocks closure without losing the object.
- Restart during closure resumes once and produces no duplicate posting.
- Multi-currency balances all move or the close plan remains incomplete and
  recoverable.
- Founder remains stable after leader/owner transfer; controller changes.
- Archived accounts remain searchable and their ledger history is intact.
